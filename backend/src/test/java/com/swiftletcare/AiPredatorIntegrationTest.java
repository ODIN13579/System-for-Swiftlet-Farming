
package com.swiftletcare;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(
    named = "DB_TEST_ENABLED", matches = "true"
)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {
        "app.jwt-secret=build104-test-secret-at-least-32-bytes",
        "app.ai-service-key=build104-test-service-key-at-least-24",
        "app.mqtt.enabled=false"
    }
)
class AiPredatorIntegrationTest {

  static final String KEY =
      "build104-test-service-key-at-least-24";

  @Autowired JdbcTemplate db;
  @Autowired JsonMapper json;
  @Autowired JwtEncoder encoder;

  @LocalServerPort int port;

  final HttpClient http = HttpClient.newHttpClient();

  long house, zone, camera;
  long otherHouse, otherZone, otherCamera;
  String ownerToken, foreignToken;

  long insert(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  String userToken(long houseId) {
    long userId = insert("""
        insert into swiftletcare.users
            (email,password_hash,role,active)
        values(?,'unused','OWNER',true)
        returning id
        """, UUID.randomUUID() + "@test.invalid");

    db.update("""
        insert into swiftletcare.house_memberships
            (user_id,house_id,role,active,joined_at)
        values(?,?,'OWNER',true,now())
        """, userId, houseId);

    Instant now = Instant.now();

    return encoder.encode(
        JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder()
                .issuer("swiftletcare")
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build()
        )
    ).getTokenValue();
  }

  @BeforeEach
  void setup() {
    house = insert("""
        insert into swiftletcare.houses(name,active)
        values('B3-12 owner house',true) returning id
        """);

    zone = insert("""
        insert into swiftletcare.zones
            (house_id,name,active)
        values(?,'B3-12 zone',true) returning id
        """, house);

    camera = insert("""
        insert into swiftletcare.devices
            (zone_id,device_id,name,type,enabled)
        values(?,?,'Predator camera','CAMERA',true)
        returning id
        """, zone, "CAM-" + UUID.randomUUID());

    otherHouse = insert("""
        insert into swiftletcare.houses(name,active)
        values('B3-12 foreign house',true) returning id
        """);

    otherZone = insert("""
        insert into swiftletcare.zones
            (house_id,name,active)
        values(?,'Foreign zone',true) returning id
        """, otherHouse);

    otherCamera = insert("""
        insert into swiftletcare.devices
            (zone_id,device_id,name,type,enabled)
        values(?,?,'Foreign camera','CAMERA',true)
        returning id
        """, otherZone, "CAM-" + UUID.randomUUID());

    ownerToken = userToken(house);
    foreignToken = userToken(otherHouse);
  }

  byte[] png() throws Exception {
    var image = new BufferedImage(
        4, 4, BufferedImage.TYPE_INT_RGB
    );

    var output = new ByteArrayOutputStream();

    assertThat(ImageIO.write(image, "png", output)).isTrue();

    return output.toByteArray();
  }

  HttpResponse<String> upload(
      long cameraId, String key,
      String mediaType, byte[] image
  ) throws Exception {

    String boundary = "B3-" + UUID.randomUUID();

    var body = new ByteArrayOutputStream();

    body.write((
        "--" + boundary + "\r\n"
        + "Content-Disposition: form-data; name=\"camera_id\"\r\n"
        + "\r\n" + cameraId + "\r\n"
        + "--" + boundary + "\r\n"
        + "Content-Disposition: form-data;"
        + " name=\"file\"; filename=\"test.png\"\r\n"
        + "Content-Type: " + mediaType + "\r\n\r\n"
    ).getBytes(StandardCharsets.UTF_8));

    body.write(image);

    body.write((
        "\r\n--" + boundary + "--\r\n"
    ).getBytes(StandardCharsets.UTF_8));

    var request = HttpRequest.newBuilder(
        URI.create("http://localhost:" + port
            + "/api/ai/snapshots")
    )
        .header("X-Service-Key", key)
        .header(
            "Content-Type",
            "multipart/form-data; boundary=" + boundary
        )
        .POST(HttpRequest.BodyPublishers.ofByteArray(
            body.toByteArray()
        ))
        .build();

    return http.send(
        request,
        HttpResponse.BodyHandlers.ofString()
    );
  }

  HttpResponse<String> event(
      String eventId, long cameraId, long snapshotId,
      String species, String key
  ) throws Exception {

    String data = json.writeValueAsString(Map.of(
        "event_id", eventId,
        "camera_id", cameraId,
        "species", species,
        "confidence", 0.92,
        "snapshot_id", snapshotId
    ));

    var request = HttpRequest.newBuilder(
        URI.create("http://localhost:" + port
            + "/api/ai/events")
    )
        .header("X-Service-Key", key)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(data))
        .build();

    return http.send(
        request,
        HttpResponse.BodyHandlers.ofString()
    );
  }

  long uploadValid(long cameraId) throws Exception {
    var response = upload(
        cameraId, KEY, "image/png", png()
    );

    assertThat(response.statusCode())
        .as(response.body()).isEqualTo(201);

    return json.readTree(response.body())
        .get("snapshot_id").asLong();
  }

  int alerts() {
    return db.queryForObject("""
        select count(*) from swiftletcare.alerts
        where zone_id=? and type='PREDATOR'
        """, Integer.class, zone);
  }

  @Test
  void snapshotRequiresServiceKeyAndHousePermission()
      throws Exception {

    assertThat(upload(
        camera, "wrong-key", "image/png", png()
    ).statusCode()).isEqualTo(401);

    long snapshotId = uploadValid(camera);

    String path = "http://localhost:" + port
        + "/api/snapshots/" + snapshotId;

    var owner = http.send(
        HttpRequest.newBuilder(URI.create(path))
            .header("Authorization", "Bearer " + ownerToken)
            .GET().build(),
        HttpResponse.BodyHandlers.ofByteArray()
    );

    assertThat(owner.statusCode()).isEqualTo(200);
    assertThat(owner.body()).containsExactly(png());
    assertThat(owner.headers()
        .firstValue("Cache-Control").orElse(""))
        .contains("no-store");

    var foreign = http.send(
        HttpRequest.newBuilder(URI.create(path))
            .header("Authorization", "Bearer " + foreignToken)
            .GET().build(),
        HttpResponse.BodyHandlers.ofByteArray()
    );

    assertThat(foreign.statusCode()).isEqualTo(403);

    var anonymous = http.send(
        HttpRequest.newBuilder(URI.create(path))
            .GET().build(),
        HttpResponse.BodyHandlers.ofByteArray()
    );

    assertThat(anonymous.statusCode()).isEqualTo(401);
  }

  @Test
  void sameEventIdIsIdempotentAndConflictIsRejected()
      throws Exception {

    long snapshotId = uploadValid(camera);
    String eventId = "predator-" + UUID.randomUUID();

    var first = event(
        eventId, camera, snapshotId, "OWL", KEY
    );

    assertThat(first.statusCode())
        .as(first.body()).isEqualTo(201);

    var retry = event(
        eventId, camera, snapshotId, "OWL", KEY
    );

    assertThat(retry.statusCode())
        .as(retry.body()).isEqualTo(200);

    assertThat(alerts()).isEqualTo(1);

    assertThat(db.queryForObject("""
        select count(*) from swiftletcare.anomaly_events
        where event_id=?
        """, Integer.class, eventId)).isEqualTo(1);

    assertThat(db.queryForObject("""
        select count(*) from swiftletcare.alert_histories h
        join swiftletcare.alerts a on a.id=h.alert_id
        where a.zone_id=? and a.type='PREDATOR'
          and h.changed_by is null
          and h.to_status='NEW'
        """, Integer.class, zone)).isEqualTo(1);

    assertThat(event(
        eventId, camera, snapshotId, "RAT", KEY
    ).statusCode()).isEqualTo(409);

    assertThat(alerts()).isEqualTo(1);
  }

  @Test
  void rejectsInvalidImageAndCameraMismatch()
      throws Exception {

    assertThat(upload(
        camera, KEY, "image/png",
        "not an image".getBytes(StandardCharsets.UTF_8)
    ).statusCode()).isEqualTo(400);

    assertThat(upload(
        camera, KEY, "image/png",
        new byte[307201]
    ).statusCode()).isEqualTo(413);

    long snapshotId = uploadValid(camera);

    assertThat(event(
        "mismatch-" + UUID.randomUUID(),
        otherCamera, snapshotId, "OWL", KEY
    ).statusCode()).isEqualTo(400);

    assertThat(event(
        "no-key-" + UUID.randomUUID(),
        camera, snapshotId, "OWL", "invalid"
    ).statusCode()).isEqualTo(401);

    assertThat(alerts()).isZero();
  }

  @Test
  void differentEventsWithinCooldownShareOneAlert()
      throws Exception {

    long snapshotId = uploadValid(camera);

    String firstId = "owl-1-" + UUID.randomUUID();
    String secondId = "owl-2-" + UUID.randomUUID();

    assertThat(event(
        firstId, camera, snapshotId, "OWL", KEY
    ).statusCode()).isEqualTo(201);

    assertThat(event(
        secondId, camera, snapshotId, "OWL", KEY
    ).statusCode()).isEqualTo(201);

    assertThat(db.queryForObject("""
        select count(*) from swiftletcare.anomaly_events
        where event_id in (?,?)
        """, Integer.class, firstId, secondId)).isEqualTo(2);

    assertThat(alerts()).isEqualTo(1);

    assertThat(db.queryForObject("""
        select level from swiftletcare.alerts
        where zone_id=? and type='PREDATOR'
        """, String.class, zone)).isEqualTo("CRITICAL");
  }
}
