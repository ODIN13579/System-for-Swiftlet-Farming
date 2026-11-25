package com.swiftletcare;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.annotation.DirtiesContext;

import com.swiftletcare.alert.CameraHeartbeatService;
import com.swiftletcare.realtime.HouseAlertRealtime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "DB_TEST_ENABLED", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {
        "app.jwt-secret=build104-test-secret-at-least-32-bytes",
        "app.ai-service-key=build104-test-service-key-at-least-24",
        "app.mqtt.enabled=false",
        "app.camera-heartbeat.scheduling-enabled=true"
    })
class CameraHeartbeatIntegrationTest {
  private static final String KEY = "build104-test-service-key-at-least-24";

  @Autowired JdbcTemplate db;
  @Autowired JwtEncoder encoder;
  @Autowired JsonMapper json;
  @Autowired CameraHeartbeatService heartbeats;
  @Autowired HouseAlertRealtime realtime;
  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();
  private long house;
  private long zone;
  private long camera;

  @BeforeEach
  void fixture() {
    house = house();
    zone = db.queryForObject("""
        insert into swiftletcare.zones(house_id,name,active)
        values(?,'B3-14 zone',true) returning id
        """, Long.class, house);
    camera = device("CAMERA");
  }

  @AfterEach
  void closeClient() {
    http.close();
  }

  @Test
  void serviceKeyAndBackendReceiptTime() throws Exception {
    var body = Map.of("camera_id", camera, "source_ok", true);
    assertThat(call("POST", "/api/ai/heartbeat", body, null, null).statusCode()).isEqualTo(401);
    assertThat(call("POST", "/api/ai/heartbeat", body, KEY + "-wrong", null).statusCode())
        .isEqualTo(401);
    assertThat(call("POST", "/api/ai/heartbeat", body, null, viewer(house)).statusCode())
        .isEqualTo(401);
    assertThat(lastSeen(camera)).isNull();

    Instant before = dbNow();
    heartbeat(camera, false);
    assertThat(lastSeen(camera)).isBetween(before, dbNow());
    assertThat(online(camera)).isTrue();
    assertThat(db.queryForObject("select camera_source_ok from swiftletcare.devices where id=?",
        Boolean.class, camera)).isFalse();

    heartbeat(camera, true);
    assertThat(db.queryForObject("select camera_source_ok from swiftletcare.devices where id=?",
        Boolean.class, camera)).isTrue();
  }

  @Test
  void rejectsNonCameraDisabledCameraAndInactiveHierarchy() throws Exception {
    assertThat(call("POST", "/api/ai/heartbeat", Map.of("camera_id", camera), KEY, null)
        .statusCode()).isEqualTo(400);
    assertThat(rejected(-1)).isEqualTo(400);
    assertThat(rejected(Long.MAX_VALUE)).isEqualTo(400);
    assertThat(rejected(device("ESP32"))).isEqualTo(400);

    db.update("update swiftletcare.devices set enabled=false where id=?", camera);
    assertThat(rejected(camera)).isEqualTo(400);
    db.update("update swiftletcare.devices set enabled=true where id=?", camera);
    db.update("update swiftletcare.zones set active=false where id=?", zone);
    assertThat(rejected(camera)).isEqualTo(400);
    db.update("update swiftletcare.zones set active=true where id=?", zone);
    db.update("update swiftletcare.houses set active=false where id=?", house);
    assertThat(rejected(camera)).isEqualTo(400);
    assertThat(lastSeen(camera)).isNull();
    heartbeats.scanOffline();
    assertThat(alertCount(camera)).isZero();
  }

  @Test
  void schedulerSendsCriticalAlertWithin35SecondsOfLastRealHeartbeat() throws Exception {
    var socket = new HouseListener();
    var ws = connect(house, viewer(house), socket);
    try {
      awaitSubscription(house, socket);
      heartbeat(camera, true);
      assertEvent(socket.messages.poll(5, TimeUnit.SECONDS), "camera.status.changed", camera);

      Instant first = lastSeen(camera);
      Thread.sleep(Duration.ofSeconds(10));
      heartbeat(camera, true);
      Instant last = lastSeen(camera);
      assertThat(last).isAfter(first);
      assertThat(alertCount(camera)).isZero();

      // Stop heartbeat traffic. Use the real schedule and the persisted backend receipt time.
      Duration remaining = Duration.between(dbNow(), last.plusSeconds(35));
      var created = assertEvent(socket.messages.poll(remaining.toNanos(), TimeUnit.NANOSECONDS),
          "alert.created", camera);
      Duration delivered = Duration.between(last, dbNow());
      var stored = Duration.between(last, Instant.parse(created.get("createdAt").asText()));
      assertThat(created.get("type").asText()).isEqualTo("CAMERA_OFFLINE");
      assertThat(created.get("level").asText()).isEqualTo("CRITICAL");
      assertThat(stored).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(35));
      assertThat(delivered).isBetween(stored, Duration.ofSeconds(35));
      assertThat(online(camera)).isFalse();
      assertThat(alertCount(camera)).isEqualTo(1);
      System.out.printf("B3-14 timing: lastHeartbeat=%s alertCreated=%s database=%dms websocket=%dms%n",
          last, created.get("createdAt").asText(), stored.toMillis(), delivered.toMillis());
    } finally {
      ws.abort();
    }
  }

  @Test
  void repeatedScansDeduplicateAndRecoveryClearsOnlyThatCamera() throws Exception {
    long otherCamera = device("CAMERA");
    heartbeat(camera, true);
    heartbeat(otherCamera, true);
    makeOverdue(camera, otherCamera);

    try (var scans = Executors.newFixedThreadPool(2)) {
      var first = scans.submit(heartbeats::scanOffline);
      var second = scans.submit(heartbeats::scanOffline);
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    }
    heartbeats.scanOffline();
    assertThat(alertCount(camera)).isEqualTo(1);
    assertThat(alertCount(otherCamera)).isEqualTo(1);
    assertThat(online(camera)).isFalse();
    assertThat(online(otherCamera)).isFalse();
    long firstAlert = alertId(camera);
    assertThat(db.queryForObject("""
        select count(*) from swiftletcare.alert_histories
        where alert_id=? and to_status='NEW' and changed_by is null
        """, Long.class, firstAlert)).isEqualTo(1L);

    heartbeat(camera, true);
    assertThat(online(camera)).isTrue();
    assertThat(online(otherCamera)).isFalse();
    assertThat(cleared(firstAlert)).isTrue();
    assertThat(cleared(alertId(otherCamera))).isFalse();
    assertThat(alertCount(camera)).isEqualTo(1);

    makeOverdue(camera);
    heartbeats.scanOffline();
    assertThat(alertCount(camera)).isEqualTo(2);
    assertThat(cleared(alertId(camera))).isFalse();
    assertThat(alertCount(otherCamera)).isEqualTo(1);
  }

  @Test
  void houseMembersReceiveAlertsAndRecoveryWhileOtherHousesCannotReadThem() throws Exception {
    long foreignHouse = house();
    String ownToken = viewer(house);
    String foreignToken = viewer(foreignHouse);
    var owner = new HouseListener();
    var foreign = new HouseListener();
    var denied = new HouseListener();
    var ownSocket = connect(house, ownToken, owner);
    var otherSocket = connect(foreignHouse, foreignToken, foreign);
    var deniedSocket = connect(house, foreignToken, denied);
    try {
      assertThat(denied.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
      awaitSubscription(house, owner);
      awaitSubscription(foreignHouse, foreign);

      heartbeat(camera, true);
      assertEvent(owner.messages.poll(5, TimeUnit.SECONDS), "camera.status.changed", camera);
      makeOverdue(camera);
      heartbeats.scanOffline();
      var created = assertEvent(owner.messages.poll(5, TimeUnit.SECONDS), "alert.created", camera);
      var offline = assertEvent(owner.messages.poll(5, TimeUnit.SECONDS), "camera.status.changed", camera);
      assertThat(offline.get("online").asBoolean()).isFalse();
      long id = created.get("id").asLong();
      String path = "/api/v1/alerts/" + id;
      assertThat(call("GET", path, null, null, ownToken).statusCode()).isEqualTo(200);
      assertThat(call("GET", path, null, null, foreignToken).statusCode()).isEqualTo(403);

      heartbeat(camera, true);
      var updated = assertEvent(owner.messages.poll(5, TimeUnit.SECONDS), "alert.updated", camera);
      var recovered = assertEvent(owner.messages.poll(5, TimeUnit.SECONDS), "camera.status.changed", camera);
      assertThat(updated.get("id").asLong()).isEqualTo(id);
      assertThat(updated.get("clearedAt").isNull()).isFalse();
      assertThat(updated.get("status").asText()).isEqualTo("NEW");
      assertThat(recovered.get("online").asBoolean()).isTrue();
      assertThat(cleared(id)).isTrue();
      assertThat(foreign.messages).isEmpty();
      assertThat(denied.messages).isEmpty();
    } finally {
      ownSocket.abort();
      otherSocket.abort();
      deniedSocket.abort();
    }
  }

  private long house() {
    return db.queryForObject("""
        insert into swiftletcare.houses(name,active) values('B3-14 house',true) returning id
        """, Long.class);
  }

  private long device(String type) {
    return db.queryForObject("""
        insert into swiftletcare.devices(zone_id,device_id,name,type,enabled)
        values(?,?,'B3-14 device',?,true) returning id
        """, Long.class, zone, "B314-" + UUID.randomUUID(), type);
  }

  private String viewer(long houseId) {
    long user = db.queryForObject("""
        insert into swiftletcare.users(email,password_hash,role,active)
        values(?,'unused','OWNER',true) returning id
        """, Long.class, UUID.randomUUID() + "@example.test");
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

  private HttpResponse<String> call(String method, String path, Object body, String key, String jwt)
      throws Exception {
    var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (key != null) req.header("X-Service-Key", key);
    if (jwt != null) req.header("Authorization", "Bearer " + jwt);
    if (body != null) req.header("Content-Type", "application/json");
    return http.send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void heartbeat(long id, boolean sourceOk) throws Exception {
    var response = call("POST", "/api/ai/heartbeat",
        Map.of("camera_id", id, "source_ok", sourceOk), KEY, null);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
  }

  private int rejected(long id) throws Exception {
    return call("POST", "/api/ai/heartbeat", Map.of("camera_id", id, "source_ok", true), KEY, null)
        .statusCode();
  }

  private Instant dbNow() {
    return db.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
  }

  private Instant lastSeen(long id) {
    var seen = db.queryForObject("select last_seen_at from swiftletcare.devices where id=?",
        Timestamp.class, id);
    return seen == null ? null : seen.toInstant();
  }

  private boolean online(long id) {
    return db.queryForObject("select online from swiftletcare.devices where id=?", Boolean.class, id);
  }

  private void makeOverdue(long... ids) {
    for (long id : ids) {
      db.update("""
          update swiftletcare.devices
          set last_seen_at=clock_timestamp() - interval '31 seconds' where id=?
          """, id);
    }
  }

  private long alertCount(long id) {
    return db.queryForObject("""
        select count(*) from swiftletcare.alerts where type='CAMERA_OFFLINE' and device_id=?
        """, Long.class, id);
  }

  private long alertId(long id) {
    return db.queryForObject("""
        select id from swiftletcare.alerts where type='CAMERA_OFFLINE' and device_id=?
        order by id desc limit 1
        """, Long.class, id);
  }

  private boolean cleared(long id) {
    return db.queryForObject("select cleared_at is not null from swiftletcare.alerts where id=?",
        Boolean.class, id);
  }

  private WebSocket connect(long id, String token, HouseListener listener) throws Exception {
    return http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
        .header("Origin", "http://localhost:5173")
        .buildAsync(URI.create("ws://localhost:" + port + "/ws/houses/" + id), listener)
        .get(5, TimeUnit.SECONDS);
  }

  private void awaitSubscription(long id, HouseListener listener) throws InterruptedException {
    for (int attempt = 0; attempt < 30; attempt++) {
      realtime.publish(id, "test.ready", Map.of());
      var event = listener.messages.poll(100, TimeUnit.MILLISECONDS);
      if (event != null) {
        assertThat(json.readTree(event).get("type").asText()).isEqualTo("test.ready");
        return;
      }
    }
    throw new AssertionError("House WebSocket subscription did not become ready");
  }

  private JsonNode assertEvent(String message, String type, long id) {
    assertThat(message).as(type).isNotNull();
    var event = json.readTree(message);
    assertThat(event.get("type").asText()).isEqualTo(type);
    var data = event.get("data");
    assertThat(data.get("houseId").asLong()).isEqualTo(house);
    assertThat(data.get(type.startsWith("alert.") ? "deviceId" : "cameraId").asLong()).isEqualTo(id);
    return data;
  }

  private static final class HouseListener implements WebSocket.Listener {
    private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final StringBuilder fragments = new StringBuilder();

    @Override
    public void onOpen(WebSocket socket) {
      socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence part, boolean last) {
      fragments.append(part);
      if (last) {
        messages.add(fragments.toString());
        fragments.setLength(0);
      }
      socket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
      closed.complete(statusCode);
      return null;
    }
  }
}
