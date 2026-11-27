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

    assertThat(call(
        "PATCH", path,
        "{\"status\":\"IN_PROGRESS\",\"note\":\"Investigating\"}",
        ownerToken
    ).statusCode()).isEqualTo(200);

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
    assertThat(json.readTree(history.body()).size()).isEqualTo(2);

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
