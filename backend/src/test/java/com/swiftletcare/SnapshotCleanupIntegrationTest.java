package com.swiftletcare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import com.swiftletcare.ai.AiPredatorService;
import com.swiftletcare.ai.SnapshotCleanup;

@EnabledIfEnvironmentVariable(named = "DB_TEST_ENABLED", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {
        "app.jwt-secret=build104-test-secret-at-least-32-bytes",
        "app.ai-service-key=build104-test-service-key-at-least-24",
        "app.mqtt.enabled=false"
    })
class SnapshotCleanupIntegrationTest {
  private static final Instant AS_OF = Instant.parse("2001-01-09T00:00:00Z");
  private static final Duration RETENTION = Duration.ofHours(48);
  private static final Instant CUTOFF = AS_OF.minus(RETENTION);

  @Autowired JdbcTemplate db;
  @Autowired SnapshotCleanup cleanup;
  @Autowired AiPredatorService predator;
  @Autowired JwtEncoder encoder;
  @LocalServerPort int port;

  private long house;
  private long zone;
  private long camera;

  @BeforeEach
  void fixture() {
    house = house();
    zone = db.queryForObject("""
        insert into swiftletcare.zones(house_id,name,active)
        values(?,'B3-17 test',true) returning id
        """, Long.class, house);
    camera = db.queryForObject("""
        insert into swiftletcare.devices(zone_id,device_id,name,type,enabled)
        values(?,?,'B3-17 camera','CAMERA',true) returning id
        """, Long.class, zone, UUID.randomUUID().toString());
  }

  @Test
  void countsOnlyUnlinkedSnapshotsStrictlyBeforeCutoffAndIsRepeatable() throws Exception {
    var before = audit();
    byte[] png = png();
    long old = snapshot(png, CUTOFF.minusNanos(1_000));
    long boundary = snapshot(png, CUTOFF);
    long recent = snapshot(png, CUTOFF.plusNanos(1_000));

    var after = audit();
    assertThat(after.cutoff()).isEqualTo(CUTOFF);
    assertThat(after.scanned() - before.scanned()).isEqualTo(1);
    assertThat(after.eligible() - before.eligible()).isEqualTo(1);
    assertThat(after.linked()).isEqualTo(before.linked());
    assertThat(after.processed()).isZero();
    assertThat(after.errors()).isZero();
    assertThat(audit()).isEqualTo(after);
    for (long id : new long[] {old, boundary, recent}) {
      assertThat(bytes(id)).containsExactly(png);
      assertThat(db.queryForObject("select byte_size from swiftletcare.ai_snapshots where id=?",
          Integer.class, id)).isEqualTo(png.length);
    }
  }

  @Test
  void eitherForeignKeyProtectsSnapshotAndDoubleLinkCountsOnce() throws Exception {
    var before = audit();
    byte[] png = png();
    Instant created = CUTOFF.minusSeconds(1);
    long eventOnly = snapshot(png, created);
    long alertOnly = snapshot(png, created);
    long both = snapshot(png, created);
    long eventId = linkEvent(eventOnly);
    long alertId = linkAlert(alertOnly, null);
    long bothEvent = linkEvent(both);
    long bothAlert = linkAlert(both, bothEvent);

    var after = audit();
    assertThat(after.scanned() - before.scanned()).isEqualTo(3);
    assertThat(after.linked() - before.linked()).isEqualTo(3);
    assertThat(after.eligible()).isEqualTo(before.eligible());
    assertThat(after.processed()).isZero();
    assertThat(after.errors()).isZero();
    assertThat(db.queryForObject("select snapshot_id from swiftletcare.anomaly_events where id=?",
        Long.class, eventId)).isEqualTo(eventOnly);
    assertThat(db.queryForObject("select snapshot_id from swiftletcare.alerts where id=?",
        Long.class, alertId)).isEqualTo(alertOnly);
    assertThat(db.queryForObject("select anomaly_event_id from swiftletcare.alerts where id=?",
        Long.class, bothAlert)).isEqualTo(bothEvent);
    assertThat(bytes(both)).containsExactly(png);
  }

  @Test
  void linkedSnapshotRemainsPrivateAndReadableAfterDryRun() throws Exception {
    byte[] image = png();
    long snapshotId = predator.saveSnapshot(camera, "image/png", image).snapshot_id();
    var event = predator.acceptEvent(new AiPredatorService.EventInput(
        UUID.randomUUID().toString(), camera, "owl", 0.9, snapshotId));
    db.update("update swiftletcare.ai_snapshots set created_at=? where id=?",
        Timestamp.from(CUTOFF.minusSeconds(1)), snapshotId);
    assertThat(audit().errors()).isZero();

    try (var http = HttpClient.newHttpClient()) {
      var allowed = read(http, snapshotId, viewer(house));
      assertThat(allowed.statusCode()).isEqualTo(200);
      assertThat(allowed.body()).containsExactly(image);
      assertThat(allowed.headers().firstValue("Cache-Control")).hasValue("private, no-store");
      assertThat(read(http, snapshotId, viewer(house())).statusCode()).isEqualTo(403);
    }
    assertThat(db.queryForObject("select snapshot_id from swiftletcare.alerts where id=?",
        Long.class, event.alert_id())).isEqualTo(snapshotId);
    assertThat(predator.saveSnapshot(camera, "image/png", image).snapshot_id()).isEqualTo(snapshotId);
  }

  @Test
  void databaseFailureReportsErrorAndSuccessfulRetryLeavesSnapshotUntouched() throws Exception {
    byte[] png = png();
    long old = snapshot(png, CUTOFF.minusSeconds(1));
    var expected = audit();
    var unavailable = new AbstractDataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        throw new SQLException("Simulated database connection failure");
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
      }
    };
    var report = new SnapshotCleanup(new JdbcTemplate(unavailable), "").dryRun(AS_OF, RETENTION);
    assertThat(report.errors()).isEqualTo(1);
    assertThat(report.processed()).isZero();
    assertThat(audit()).isEqualTo(expected);
    assertThat(bytes(old)).containsExactly(png);
  }

  @Test
  void rejectsZeroOrNegativeRetention() {
    assertThatThrownBy(() -> cleanup.dryRun(AS_OF, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SnapshotCleanup(db, "-PT1H"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private SnapshotCleanup.Report audit() {
    return cleanup.dryRun(AS_OF, RETENTION);
  }

  private long snapshot(byte[] png, Instant created) {
    String hash = UUID.randomUUID().toString().replace("-", "");
    return db.queryForObject("""
        insert into swiftletcare.ai_snapshots(camera_id,media_type,image_bytes,byte_size,sha256,created_at)
        values(?,'image/png',?,?,?,?) returning id
        """, Long.class, camera, png, png.length, hash.repeat(2), Timestamp.from(created));
  }

  private byte[] bytes(long id) {
    return db.queryForObject("select image_bytes from swiftletcare.ai_snapshots where id=?",
        byte[].class, id);
  }

  private long linkEvent(long id) {
    return db.queryForObject("""
        insert into swiftletcare.anomaly_events(device_id,event_id,species,confidence,detected_at,snapshot_id)
        values(?,?,'owl',0.9,now(),?) returning id
        """, Long.class, camera, UUID.randomUUID().toString(), id);
  }

  private long linkAlert(long id, Long event) {
    return db.queryForObject("""
        insert into swiftletcare.alerts(zone_id,device_id,anomaly_event_id,snapshot_id,type,level,message)
        values(?,?,?,?,'PREDATOR','CRITICAL','B3-17 fixture') returning id
        """, Long.class, zone, camera, event, id);
  }

  private long house() {
    return db.queryForObject("""
        insert into swiftletcare.houses(name,active) values('B3-17 test',true) returning id
        """, Long.class);
  }

  private String viewer(long houseId) {
    long user = db.queryForObject("""
        insert into swiftletcare.users(email,password_hash,role,active)
        values(?,'unused','OWNER',true) returning id
        """, Long.class, UUID.randomUUID() + "@test.invalid");
    db.update("""
        insert into swiftletcare.house_memberships(user_id,house_id,role,active,joined_at)
        values(?,?,'VIEWER',true,now())
        """, user, houseId);
    Instant now = Instant.now();
    var claims = JwtClaimsSet.builder().issuer("swiftletcare").subject(Long.toString(user))
        .issuedAt(now).expiresAt(now.plusSeconds(900)).build();
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        claims)).getTokenValue();
  }

  private HttpResponse<byte[]> read(HttpClient http, long id, String token) throws Exception {
    return http.send(HttpRequest.newBuilder(
        URI.create("http://localhost:" + port + "/api/snapshots/" + id))
        .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static byte[] png() throws Exception {
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", output);
    return output.toByteArray();
  }
}
