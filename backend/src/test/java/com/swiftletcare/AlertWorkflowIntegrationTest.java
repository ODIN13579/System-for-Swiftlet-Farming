package com.swiftletcare;

import static org.assertj.core.api.Assertions.assertThat;


import com.swiftletcare.realtime.HouseAlertRealtime;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
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
class AlertWorkflowIntegrationTest {

  @Autowired JdbcTemplate db;
  @Autowired JwtEncoder encoder;
  @Autowired JsonMapper json;
  @Autowired HouseAlertRealtime realtime;
  @LocalServerPort int port;

  final HttpClient http = HttpClient.newHttpClient();

  long house;
  long otherHouse;
  long zone;
  long alertId;
  long ownerId;

  String ownerToken;
  String viewerToken;
  String foreignToken;

  long insert(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  String token(long userId) {
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

  String createUser(long houseId, String houseRole) {
    long id = insert(
        "insert into swiftletcare.users"
            + "(email,password_hash,role,active)"
            + " values(?,'unused','OWNER',true) returning id",
        UUID.randomUUID() + "@test.invalid"
    );

    db.update(
        "insert into swiftletcare.house_memberships"
            + "(user_id,house_id,role,active,joined_at)"
            + " values(?,?,?,true,now())",
        id, houseId, houseRole
    );

    return token(id);
  }

  @BeforeEach
  void fixture() {
    house = insert(
        "insert into swiftletcare.houses(name,active)"
            + " values('B3 alert test',true) returning id"
    );

    otherHouse = insert(
        "insert into swiftletcare.houses(name,active)"
            + " values('B3 other house',true) returning id"
    );

    zone = insert(
        "insert into swiftletcare.zones(house_id,name,active)"
            + " values(?,'B3 zone',true) returning id",
        house
    );

    ownerToken = createUser(house, "OWNER");
    viewerToken = createUser(house, "VIEWER");
    foreignToken = createUser(otherHouse, "OWNER");

    ownerId = db.queryForObject(
        "select u.id from swiftletcare.users u"
            + " join swiftletcare.house_memberships m"
            + " on m.user_id=u.id"
            + " where m.house_id=? and m.role='OWNER'",
        Long.class, house
    );

    alertId = insert(
        "insert into swiftletcare.alerts"
            + "(zone_id,type,level,status,message)"
            + " values(?,'ENV_THRESHOLD','WARNING','NEW','Low RH')"
            + " returning id",
        zone
    );
  }

  HttpResponse<String> call(
      String method, String path, String body, String auth
  ) throws Exception {
    var request = HttpRequest.newBuilder(
        URI.create("http://localhost:" + port + "/api/v1" + path)
    ).header("Content-Type", "application/json");

    if (auth != null) {
      request.header("Authorization", "Bearer " + auth);
    }

    return http.send(
        request.method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)
        ).build(),
        HttpResponse.BodyHandlers.ofString()
    );
  }

  @Test
  void listDetailFiltersAndHousePermissions() throws Exception {
    String path = "/alerts?houseId=" + house
        + "&status=NEW&level=WARNING";

    var result = call("GET", path, null, ownerToken);

    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(json.readTree(result.body())
        .get("items").size()).isEqualTo(1);

    assertThat(call(
        "GET",
        "/alerts?houseId=" + house + "&level=CRITICAL",
        null, ownerToken
    ).body()).contains("\"items\":[]");

    assertThat(call(
        "GET", "/alerts/" + alertId, null, ownerToken
    ).statusCode()).isEqualTo(200);

    assertThat(call(
        "GET", "/alerts/" + alertId, null, viewerToken
    ).statusCode()).isEqualTo(200);

    assertThat(call(
        "GET", "/alerts/" + alertId, null, foreignToken
    ).statusCode()).isEqualTo(403);

    assertThat(call(
        "GET", path, null, foreignToken
    ).statusCode()).isEqualTo(403);

    assertThat(call(
        "GET", path, null, null
    ).statusCode()).isEqualTo(401);

    assertThat(call(
        "GET",
        "/alerts?houseId=" + house + "&page=-1",
        null, ownerToken
    ).statusCode()).isEqualTo(400);
  }

  @Test
  void paginatesAlertHistoryAcrossActiveZonesWithStableTies() throws Exception {
    long secondZone = insert("""
        insert into swiftletcare.zones(house_id,name,active)
        values(?,'B3-17 second zone',true) returning id
        """, house);
    long inactiveZone = insert("""
        insert into swiftletcare.zones(house_id,name,active)
        values(?,'B3-17 inactive zone',false) returning id
        """, house);
    long foreignZone = insert("""
        insert into swiftletcare.zones(house_id,name,active)
        values(?,'B3-17 foreign zone',true) returning id
        """, otherHouse);

    String sql = """
        insert into swiftletcare.alerts(zone_id,type,level,status,message,created_at)
        values(?,'B317_FIXTURE',?,?,'Paging fixture',cast(? as timestamptz)) returning id
        """;
    String tiedTime = "2026-10-15T00:00:00Z";
    long first = insert(sql, zone, "CRITICAL", "NEW", tiedTime);
    long second = insert(sql, secondZone, "CRITICAL", "NEW", tiedTime);
    long third = insert(sql, zone, "CRITICAL", "NEW", "2026-10-14T23:59:00Z");
    long fourth = insert(sql, secondZone, "CRITICAL", "NEW", "2026-10-14T23:58:00Z");
    insert(sql, zone, "WARNING", "NEW", tiedTime);
    insert(sql, secondZone, "CRITICAL", "RESOLVED", tiedTime);
    insert(sql, inactiveZone, "CRITICAL", "NEW", tiedTime);
    insert(sql, foreignZone, "CRITICAL", "NEW", tiedTime);

    String path = "/alerts?houseId=" + house + "&status=NEW&level=CRITICAL&size=2";
    var firstPage = call("GET", path, null, viewerToken);
    assertThat(firstPage.statusCode()).as(firstPage.body()).isEqualTo(200);
    var firstBody = json.readTree(firstPage.body());
    assertThat(firstBody.get("page").get("totalElements").asLong()).isEqualTo(4);
    assertThat(firstBody.get("items").get(0).get("id").asLong()).isEqualTo(second);
    assertThat(firstBody.get("items").get(1).get("id").asLong()).isEqualTo(first);

    var nextPage = call("GET", path + "&page=1", null, viewerToken);
    assertThat(nextPage.statusCode()).as(nextPage.body()).isEqualTo(200);
    var nextBody = json.readTree(nextPage.body());
    assertThat(nextBody.get("items").get(0).get("id").asLong()).isEqualTo(third);
    assertThat(nextBody.get("items").get(1).get("id").asLong()).isEqualTo(fourth);
    assertThat(json.readTree(call("GET", path + "&page=2", null, viewerToken).body())
        .get("items").size()).isZero();

    var zonePage = call("GET", path + "&zoneId=" + zone, null, viewerToken);
    assertThat(zonePage.statusCode()).as(zonePage.body()).isEqualTo(200);
    var zoneBody = json.readTree(zonePage.body());
    assertThat(zoneBody.get("page").get("totalElements").asLong()).isEqualTo(2);
    assertThat(zoneBody.get("items").get(0).get("id").asLong()).isEqualTo(first);
    assertThat(zoneBody.get("items").get(1).get("id").asLong()).isEqualTo(third);
    assertThat(call("GET", path + "&zoneId=" + foreignZone, null, viewerToken).statusCode())
        .isEqualTo(403);
  }

  @Test
  void workflowAuditAndInvalidTransitions() throws Exception {
    String path = "/alerts/" + alertId + "/status";

    assertThat(call(
        "PATCH", path, "{\"status\":\"RESOLVED\"}", ownerToken
    ).statusCode()).isEqualTo(409);

    assertThat(call(
        "PATCH", path, "{\"status\":\"IN_PROGRESS\"}", viewerToken
    ).statusCode()).isEqualTo(403);

    assertThat(call(
        "PATCH", path, "{\"status\":\"IN_PROGRESS\"}", foreignToken
    ).statusCode()).isEqualTo(403);

    Instant auditStart = db.queryForObject("select clock_timestamp()", java.sql.Timestamp.class)
        .toInstant();
    var accepted = call(
        "PATCH", path,
        "{\"status\":\"IN_PROGRESS\",\"note\":\"Investigating\"}",
        ownerToken
    );
    assertThat(accepted.statusCode()).isEqualTo(200);
    assertThat(json.readTree(accepted.body()).get("handledAt").isNull()).isTrue();

    // Idempotent same-state request.
    assertThat(call(
        "PATCH", path, "{\"status\":\"IN_PROGRESS\"}", ownerToken
    ).statusCode()).isEqualTo(200);

    assertThat(call(
        "PATCH", path, "{\"status\":\"NEW\"}", ownerToken
    ).statusCode()).isEqualTo(409);

    var resolved = call(
        "PATCH", path,
        "{\"status\":\"RESOLVED\",\"note\":\"Fixed\"}",
        ownerToken
    );

    assertThat(resolved.statusCode()).isEqualTo(200);
    assertThat(json.readTree(resolved.body())
        .get("status").asText()).isEqualTo("RESOLVED");
    assertThat(json.readTree(resolved.body())
        .get("handledAt").isNull()).isFalse();

    assertThat(call(
        "PATCH", path, "{\"status\":\"NEW\"}", ownerToken
    ).statusCode()).isEqualTo(409);

    assertThat(db.queryForObject(
        "select count(*) from swiftletcare.alert_histories"
            + " where alert_id=?",
        Integer.class, alertId
    )).isEqualTo(2);

    var history = call(
        "GET", "/alerts/" + alertId + "/history",
        null, ownerToken
    );

    assertThat(history.statusCode()).isEqualTo(200);
    var entries = json.readTree(history.body());
    assertThat(entries.size()).isEqualTo(2);
    var inProgress = entries.get(0);
    var completed = entries.get(1);
    assertThat(inProgress.get("fromStatus").asText()).isEqualTo("NEW");
    assertThat(inProgress.get("toStatus").asText()).isEqualTo("IN_PROGRESS");
    assertThat(inProgress.get("note").asText()).isEqualTo("Investigating");
    assertThat(completed.get("fromStatus").asText()).isEqualTo("IN_PROGRESS");
    assertThat(completed.get("toStatus").asText()).isEqualTo("RESOLVED");
    assertThat(completed.get("note").asText()).isEqualTo("Fixed");
    assertThat(inProgress.get("changedBy").asLong()).isEqualTo(ownerId);
    assertThat(completed.get("changedBy").asLong()).isEqualTo(ownerId);
    Instant auditEnd = db.queryForObject("select clock_timestamp()", java.sql.Timestamp.class)
        .toInstant();
    Instant startedAt = Instant.parse(inProgress.get("changedAt").asText());
    Instant resolvedAt = Instant.parse(completed.get("changedAt").asText());
    assertThat(startedAt).isBetween(auditStart, auditEnd);
    assertThat(resolvedAt).isBetween(startedAt, auditEnd);
    assertThat(Instant.parse(json.readTree(resolved.body()).get("handledAt").asText()))
        .isEqualTo(resolvedAt);

    String historyPath = "/alerts/" + alertId + "/history";
    var viewerHistory = call("GET", historyPath, null, viewerToken);
    assertThat(viewerHistory.statusCode()).isEqualTo(200);
    assertThat(json.readTree(viewerHistory.body())).isEqualTo(entries);
    assertThat(call("GET", historyPath, null, foreignToken).statusCode()).isEqualTo(403);

    assertThat(db.queryForObject(
        "select changed_by from swiftletcare.alert_histories"
            + " where alert_id=? order by id limit 1",
        Long.class, alertId
    )).isEqualTo(ownerId);
  }

  @Test
  void websocketScopesEventsAndRechecksMembership() throws Exception {
    var messages = new LinkedBlockingQueue<String>();
    var closed = new CompletableFuture<Integer>();

    var socket = http.newWebSocketBuilder()
        .header("Authorization", "Bearer " + ownerToken)
        .buildAsync(
            URI.create(
                "ws://localhost:" + port + "/ws/houses/" + house
            ),
            new WebSocket.Listener() {
              @Override
              public void onOpen(WebSocket ws) {
                ws.request(1);
              }

              @Override
              public CompletionStage<?> onText(
                  WebSocket ws, CharSequence text, boolean last
              ) {
                messages.add(text.toString());
                ws.request(1);
                return null;
              }

              @Override
              public CompletionStage<?> onClose(
                  WebSocket ws, int code, String reason
              ) {
                closed.complete(code);
                return null;
              }
            }
        ).get(5, TimeUnit.SECONDS);

    try {
      String first = null;

      // Wait until the server has registered the session.
      for (int i = 0; i < 20 && first == null; i++) {
        realtime.publish(
            house, "alert.probe", Map.of("id", alertId)
        );
        first = messages.poll(150, TimeUnit.MILLISECONDS);
      }

      assertThat(first).contains("alert.probe");

      assertThat(call(
          "PATCH", "/alerts/" + alertId + "/status",
          "{\"status\":\"IN_PROGRESS\"}", ownerToken
      ).statusCode()).isEqualTo(200);

      assertThat(messages.poll(5, TimeUnit.SECONDS))
          .contains("alert.updated")
          .contains("IN_PROGRESS");

      db.update(
          "update swiftletcare.house_memberships"
              + " set active=false where user_id=?",
          ownerId
      );

      realtime.publish(
          house, "alert.probe", Map.of("id", alertId)
      );

      assertThat(closed.get(5, TimeUnit.SECONDS))
          .isEqualTo(1008);

      assertThat(messages.poll(200, TimeUnit.MILLISECONDS))
          .isNull();

    } finally {
      socket.abort();
    }
  }

  @Test
  void liveWorkflowUpdateReachesOnlyTheAlertsHouse() throws Exception {
    var local = new LinkedBlockingQueue<String>();
    var foreign = new LinkedBlockingQueue<String>();
    var localSocket = observeHouse(house, viewerToken, local);
    try {
      var foreignSocket = observeHouse(otherHouse, foreignToken, foreign);
      try {
        awaitHouseSubscription(house, local);
        awaitHouseSubscription(otherHouse, foreign);

        var response = call("PATCH", "/alerts/" + alertId + "/status",
            "{\"status\":\"IN_PROGRESS\"}", ownerToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String message = local.poll(5, TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        var update = json.readTree(message);
        assertThat(update.get("type").asText()).isEqualTo("alert.updated");
        assertThat(update.get("data").get("id").asLong()).isEqualTo(alertId);
        assertThat(update.get("data").get("houseId").asLong()).isEqualTo(house);
        assertThat(update.get("data").get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(foreign.poll(300, TimeUnit.MILLISECONDS)).isNull();

        // Confirm silence on the foreign socket came from scoping, not a closed connection.
        awaitHouseSubscription(otherHouse, foreign);
      } finally {
        foreignSocket.abort();
      }
    } finally {
      localSocket.abort();
    }
  }

  private WebSocket observeHouse(long id, String token, BlockingQueue<String> messages)
      throws Exception {
    return http.newWebSocketBuilder()
        .header("Authorization", "Bearer " + token)
        .header("Origin", "http://localhost:5173")
        .buildAsync(URI.create("ws://localhost:" + port + "/ws/houses/" + id),
            new WebSocket.Listener() {
              private final StringBuilder fragments = new StringBuilder();

              @Override
              public void onOpen(WebSocket socket) {
                socket.request(1);
              }

              @Override
              public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
                fragments.append(text);
                if (last) {
                  messages.add(fragments.toString());
                  fragments.setLength(0);
                }
                socket.request(1);
                return null;
              }
            }).get(5, TimeUnit.SECONDS);
  }

  private void awaitHouseSubscription(long id, BlockingQueue<String> messages)
      throws InterruptedException {
    for (int attempt = 0; attempt < 20; attempt++) {
      realtime.publish(id, "alert.probe", Map.of("houseId", id));
      String message = messages.poll(150, TimeUnit.MILLISECONDS);
      if (message != null) {
        assertThat(json.readTree(message).get("type").asText()).isEqualTo("alert.probe");
        return;
      }
    }
    throw new AssertionError("House WebSocket was not registered: " + id);
  }

  @Test
  void foreignHouseWebsocketIsRejected() throws Exception {
    var closed = new CompletableFuture<Integer>();

    var socket = http.newWebSocketBuilder()
        .header("Authorization", "Bearer " + foreignToken)
        .buildAsync(
            URI.create(
                "ws://localhost:" + port + "/ws/houses/" + house
            ),
            new WebSocket.Listener() {
              @Override
              public void onOpen(WebSocket ws) {
                ws.request(1);
              }

              @Override
              public CompletionStage<?> onClose(
                  WebSocket ws, int code, String reason
              ) {
                closed.complete(code);
                return null;
              }
            }
        ).get(5, TimeUnit.SECONDS);

    try {
      assertThat(closed.get(5, TimeUnit.SECONDS))
          .isEqualTo(1008);
    } finally {
      socket.abort();
    }
  }
}
