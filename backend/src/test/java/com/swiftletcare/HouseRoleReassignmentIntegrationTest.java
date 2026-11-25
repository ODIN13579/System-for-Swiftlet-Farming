package com.swiftletcare;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

import com.swiftletcare.realtime.HouseAlertRealtime;
import com.swiftletcare.realtime.Realtime;

import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "DB_TEST_ENABLED", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {
        "app.jwt-secret=build104-test-secret-at-least-32-bytes",
        "app.ai-service-key=build104-test-service-key-at-least-24",
        "app.mqtt.enabled=false"
    })
class HouseRoleReassignmentIntegrationTest {
  private static final long TOKEN_VERSION = 3;

  @Autowired JdbcTemplate db;
  @Autowired JwtEncoder encoder;
  @Autowired JsonMapper json;
  @Autowired HouseAlertRealtime houseRealtime;
  @Autowired Realtime deviceRealtime;
  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();
  private long house;
  private Member owner;
  private Member viewer;

  private record Member(long userId, long membershipId, String token) {}

  @BeforeEach
  void fixture() {
    house = house();
    owner = member(house, "OWNER");
    viewer = member(house, "VIEWER");
  }

  @AfterEach
  void closeClient() {
    http.close();
  }

  @Test
  void promotionAndDemotionApplyToTheNextHttpRequestWithTheSameJwt() throws Exception {
    assertThat(createZone(viewer.token())).isEqualTo(403);

    var response = change(house, viewer.membershipId(), "ADMIN", owner.token());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var result = json.readTree(response.body());
    assertThat(result.get("id").asLong()).isEqualTo(viewer.membershipId());
    assertThat(result.get("userId").asLong()).isEqualTo(viewer.userId());
    assertThat(result.get("houseId").asLong()).isEqualTo(house);
    assertThat(result.get("role").asText()).isEqualTo("ADMIN");
    assertThat(result.get("active").asBoolean()).isTrue();
    assertThat(result.get("joinedAt").asText()).isNotBlank();
    assertThat(createZone(viewer.token())).isEqualTo(201);
    assertThat(call("GET", "/api/v1/admin/users", null, viewer.token()).statusCode()).isEqualTo(403);

    assertThat(change(house, viewer.membershipId(), "VIEWER", owner.token()).statusCode())
        .isEqualTo(200);
    assertThat(createZone(viewer.token())).isEqualTo(403);
    assertThat(call("GET", "/api/v1/houses/" + house, null, viewer.token()).statusCode())
        .isEqualTo(200);
    assertThat(db.queryForObject("select token_version from swiftletcare.users where id=?",
        Long.class, viewer.userId())).isEqualTo(TOKEN_VERSION);

    assertThat(change(house, viewer.membershipId(), "OWNER", owner.token()).statusCode())
        .isEqualTo(200);
    var third = member(house, "VIEWER");
    assertThat(change(house, third.membershipId(), "ADMIN", viewer.token()).statusCode())
        .isEqualTo(200);
  }

  @Test
  void onlyOwnersAndGlobalAdminsManageMembershipsAndIdsAreHouseScoped() throws Exception {
    var houseAdmin = member(house, "ADMIN");
    long foreignHouse = house();
    var foreign = member(foreignHouse, "VIEWER");
    var outsider = member(foreignHouse, "OWNER");
    assertThat(change(house, viewer.membershipId(), "ADMIN", outsider.token()).statusCode())
        .isEqualTo(403);
    assertThat(change(foreignHouse, foreign.membershipId(), "ADMIN", owner.token()).statusCode())
        .isEqualTo(403);
    assertThat(change(house, viewer.membershipId(), "OWNER", viewer.token()).statusCode())
        .isEqualTo(403);
    assertThat(change(house, viewer.membershipId(), "OWNER", houseAdmin.token()).statusCode())
        .isEqualTo(403);
    assertThat(change(house, foreign.membershipId(), "ADMIN", owner.token()).statusCode())
        .isEqualTo(404);
    assertThat(role(foreign)).isEqualTo("VIEWER");
    assertThat(change(foreignHouse, foreign.membershipId(), "ADMIN", globalAdmin()).statusCode())
        .isEqualTo(200);
    assertThat(role(foreign)).isEqualTo("ADMIN");
  }

  @Test
  void invalidRoleAndMissingMembershipAreRejectedWithoutChangingState() throws Exception {
    assertThat(change(house, viewer.membershipId(), "SUPER_ADMIN", owner.token()).statusCode())
        .isEqualTo(400);
    assertThat(call("PATCH", path(house, viewer.membershipId()), Map.of(), owner.token())
        .statusCode()).isEqualTo(400);
    assertThat(call("PATCH", path(house, viewer.membershipId()),
        Map.of("role", "ADMIN", "active", false), owner.token()).statusCode()).isEqualTo(400);
    assertThat(change(house, Long.MAX_VALUE, "ADMIN", owner.token()).statusCode()).isEqualTo(404);
    assertThat(change(house, viewer.membershipId(), "ADMIN", null).statusCode()).isEqualTo(401);
    assertThat(role(viewer)).isEqualTo("VIEWER");
  }

  @Test
  void rejectsSelfRoleChangesAndRequiresAnotherEffectiveOwner() throws Exception {
    String global = globalAdmin();
    var inactiveAccountOwner = member(house, "OWNER");
    db.update("update swiftletcare.users set active=false where id=?", inactiveAccountOwner.userId());
    var inactiveMemberOwner = member(house, "OWNER");
    deactivateMembership(inactiveMemberOwner);

    assertConflict(change(house, owner.membershipId(), "ADMIN", owner.token()), "SELF_ROLE_CHANGE");
    assertConflict(change(house, owner.membershipId(), "VIEWER", global), "LAST_HOUSE_OWNER");
    assertThat(role(owner)).isEqualTo("OWNER");

    var secondOwner = member(house, "OWNER");
    assertConflict(change(house, owner.membershipId(), "VIEWER", owner.token()), "SELF_ROLE_CHANGE");
    assertThat(change(house, owner.membershipId(), "OWNER", owner.token()).statusCode())
        .isEqualTo(200);
    assertThat(change(house, owner.membershipId(), "ADMIN", global).statusCode()).isEqualTo(200);
    assertThat(role(owner)).isEqualTo("ADMIN");
    assertThat(role(secondOwner)).isEqualTo("OWNER");
  }

  @Test
  void simultaneousDemotionsKeepOneActiveOwner() throws Exception {
    var secondOwner = member(house, "OWNER");
    String global = globalAdmin();
    var responses = race(owner, global, secondOwner, global);
    assertThat(responses.stream().map(HttpResponse::statusCode).toList())
        .containsExactlyInAnyOrder(200, 409);
    for (var response : responses) {
      if (response.statusCode() == 409) assertConflict(response, "LAST_HOUSE_OWNER");
    }
    assertThat(List.of(role(owner), role(secondOwner))).containsExactlyInAnyOrder("VIEWER", "OWNER");
  }

  @Test
  void concurrentOwnersCannotUseAuthorizationThatWasLostWhileWaiting() throws Exception {
    var secondOwner = member(house, "OWNER");
    var responses = race(owner, secondOwner.token(), secondOwner, owner.token());
    assertThat(responses.stream().map(HttpResponse::statusCode).toList())
        .containsExactlyInAnyOrder(200, 403);
    assertThat(List.of(role(owner), role(secondOwner))).containsExactlyInAnyOrder("VIEWER", "OWNER");
  }

  @Test
  void openHouseAndDeviceSocketsRespectLiveReadAccess() throws Exception {
    long device = device();
    var houseListener = new Listener();
    var deviceListener = new Listener();
    assertThat(change(house, viewer.membershipId(), "ADMIN", owner.token()).statusCode())
        .isEqualTo(200);
    var houseSocket = connect("/ws/houses/" + house, viewer.token(), houseListener);
    var deviceSocket = connect("/ws/devices/" + device, viewer.token(), deviceListener);
    try {
      awaitSubscription(houseListener,
          type -> houseRealtime.publish(house, type, Map.of("houseId", house)));
      awaitSubscription(deviceListener,
          type -> deviceRealtime.publish(device, type, Map.of("id", device)));

      assertThat(change(house, viewer.membershipId(), "VIEWER", owner.token()).statusCode())
          .isEqualTo(200);
      assertThat(createZone(viewer.token())).isEqualTo(403);
      houseRealtime.publish(house, "alert.created", Map.of("id", 1L));
      deviceRealtime.publish(device, "telemetry.updated", Map.of("id", 1L));
      assertThat(houseListener.messages.poll(5, TimeUnit.SECONDS)).contains("alert.created");
      assertThat(deviceListener.messages.poll(5, TimeUnit.SECONDS)).contains("telemetry.updated");

      deactivateMembership(viewer);
      assertThat(call("GET", "/api/v1/houses/" + house, null, viewer.token()).statusCode())
          .isEqualTo(403);
      houseRealtime.publish(house, "alert.updated", Map.of("id", 2L));
      deviceRealtime.publish(device, "telemetry.updated", Map.of("id", 2L));
      assertThat(houseListener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
      assertThat(deviceListener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
      assertThat(houseListener.messages).isEmpty();
      assertThat(deviceListener.messages).isEmpty();
    } finally {
      houseSocket.abort();
      deviceSocket.abort();
    }
  }

  private List<HttpResponse<String>> race(Member first, String firstToken, Member second,
      String secondToken) throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> {
        ready.countDown();
        start.await();
        return change(house, first.membershipId(), "VIEWER", firstToken);
      });
      var b = pool.submit(() -> {
        ready.countDown();
        start.await();
        return change(house, second.membershipId(), "VIEWER", secondToken);
      });
      boolean bothReady = ready.await(5, TimeUnit.SECONDS);
      start.countDown();
      assertThat(bothReady).isTrue();
      return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    }
  }

  private void assertConflict(HttpResponse<String> response, String code) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
    assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo(code);
  }

  private long house() {
    return db.queryForObject("""
        insert into swiftletcare.houses(name,active) values('B3-16 test',true) returning id
        """, Long.class);
  }

  private long device() {
    long zone = db.queryForObject("""
        insert into swiftletcare.zones(house_id,name,active)
        values(?,'B3-16 zone',true) returning id
        """, Long.class, house);
    return db.queryForObject("""
        insert into swiftletcare.devices(zone_id,device_id,name,type,enabled)
        values(?,?,'B3-16 device','ESP32',true) returning id
        """, Long.class, zone, "B316-" + UUID.randomUUID());
  }

  private Member member(long houseId, String role) {
    long user = account("OWNER");
    long id = db.queryForObject("""
        insert into swiftletcare.house_memberships(user_id,house_id,role,active,joined_at)
        values(?,?,?,true,now()) returning id
        """, Long.class, user, houseId, role);
    return new Member(user, id, token(user));
  }

  private long account(String globalRole) {
    return db.queryForObject("""
        insert into swiftletcare.users(email,password_hash,role,active,token_version)
        values(?,'unused',?,true,?) returning id
        """, Long.class, UUID.randomUUID() + "@example.test", globalRole, TOKEN_VERSION);
  }

  private String globalAdmin() {
    return token(account("ADMIN"));
  }

  private String token(long user) {
    Instant now = Instant.now();
    var claims = JwtClaimsSet.builder().issuer("swiftletcare").subject(Long.toString(user))
        .issuedAt(now).expiresAt(now.plusSeconds(900)).claim("token_version", TOKEN_VERSION).build();
    return encoder.encode(JwtEncoderParameters.from(
        JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
  }

  private String role(Member member) {
    return db.queryForObject("select role from swiftletcare.house_memberships where id=?",
        String.class, member.membershipId());
  }

  private void deactivateMembership(Member member) {
    db.update("update swiftletcare.house_memberships set active=false where id=?", member.membershipId());
  }

  private int createZone(String auth) throws Exception {
    return call("POST", "/api/v1/houses/" + house + "/zones",
        Map.of("name", "B3-16 zone"), auth).statusCode();
  }

  private static String path(long houseId, long membershipId) {
    return "/api/v1/houses/" + houseId + "/members/" + membershipId;
  }

  private HttpResponse<String> change(long houseId, long membershipId, String role, String auth)
      throws Exception {
    return call("PATCH", path(houseId, membershipId), Map.of("role", role), auth);
  }

  private HttpResponse<String> call(String method, String path, Object body, String auth)
      throws Exception {
    var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (auth != null) req.header("Authorization", "Bearer " + auth);
    if (body != null) req.header("Content-Type", "application/json");
    return http.send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private WebSocket connect(String path, String token, Listener listener) throws Exception {
    return http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
        .header("Origin", "http://localhost:5173")
        .buildAsync(URI.create("ws://localhost:" + port + path), listener)
        .get(5, TimeUnit.SECONDS);
  }

  private void awaitSubscription(Listener listener, Consumer<String> publish)
      throws InterruptedException {
    for (int i = 0; i < 30; i++) {
      publish.accept("test.ready");
      var result = listener.messages.poll(100, TimeUnit.MILLISECONDS);
      if (result != null) {
        assertThat(result).contains("test.ready");
        return;
      }
    }
    throw new AssertionError("WebSocket subscription did not become ready");
  }

  private static final class Listener implements WebSocket.Listener {
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
