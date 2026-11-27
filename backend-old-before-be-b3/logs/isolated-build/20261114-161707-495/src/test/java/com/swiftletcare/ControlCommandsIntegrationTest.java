package com.swiftletcare;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
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

@EnabledIfEnvironmentVariable(named = "DB_TEST_ENABLED", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=build104-test-secret-at-least-32-bytes",
    "app.ai-service-key=build104-test-service-key-at-least-24",
    "app.mqtt.url=${TEST_MQTT_URL:tcp://localhost:51884}",
    "app.mqtt.client-id=build2-control-test",
    "app.mqtt.control-commands-enabled=true",
    "app.heartbeat-timeout-ms=60000"
})
class ControlCommandsIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtEncoder encoder;
  @Autowired JsonMapper json;
  @LocalServerPort int port;

  @Test
  void handlesFourOutputsAndRequiresMatchingAckBeforeSuccess() throws Exception {
    long house = insert("insert into swiftletcare.houses(name,active) values('B2 control',true) returning id");
    long zone = insert("insert into swiftletcare.zones(house_id,name,active) values(?,'B2 control',true) returning id", house);
    String code = "ESP-" + UUID.randomUUID();
    long device = insert("""
        insert into swiftletcare.devices(zone_id,device_id,name,type,enabled)
        values(?,?,'ESP','ESP32',true) returning id
        """, zone, code);
    var actuators = new LinkedHashMap<String, Long>();
    for (String kind : new String[] {"FAN", "MIST", "HEATER", "AMPLIFIER"}) {
      actuators.put(kind, insert(
          "insert into swiftletcare.actuators(device_id,kind) values(?,?) returning id", device, kind));
    }
    String owner = login(house, "OWNER");
    String viewer = login(house, "VIEWER");
    String prefix = "swiftletcare/" + house + "/" + zone + "/" + code;
    var wire = new LinkedBlockingQueue<String>();
    var mqtt = new MqttClient(System.getenv().getOrDefault("TEST_MQTT_URL", "tcp://localhost:51884"),
        "actuator-probe-" + UUID.randomUUID(), new MemoryPersistence());
    try (var http = HttpClient.newHttpClient()) {
      mqtt.connect();
      mqtt.subscribe(prefix + "/command", 1,
          (topic, message) -> wire.add(new String(message.getPayload(), StandardCharsets.UTF_8)));
      long fan = actuators.get("FAN");
      long mist = actuators.get("MIST");
      assertThat(post(http, viewer, fan, Map.of("action", "SET_MODE", "targetMode", "MANUAL"))
          .statusCode()).isEqualTo(403);
      assertThat(post(http, owner, fan, Map.of("action", "SET_MODE", "targetMode", "MANUAL",
          "targetState", true)).statusCode()).isEqualTo(400);

      // Probe both heartbeat and actuator subscriptions before testing writes.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      do {
        publish(mqtt, prefix + "/heartbeat", Map.of("device_id", code, "timestamp", Instant.now().toString()));
        state(mqtt, prefix, code, "AUTO", false, "OFF");
        Thread.sleep(100);
      } while (System.nanoTime() < deadline && !Boolean.TRUE.equals(jdbc.queryForObject("""
          select d.online and a.state_received_at is not null from swiftletcare.devices d
          join swiftletcare.actuators a on a.device_id=d.id where a.id=?
          """, Boolean.class, fan)));
      var blocked = post(http, owner, mist, Map.of("action", "SET_STATE", "targetState", true));
      assertThat(blocked.statusCode()).isEqualTo(409);
      assertThat(blocked.body()).contains("ACTUATOR_NOT_IN_MANUAL_MODE");

      String modeId = accepted(post(http, owner, fan, Map.of("action", "SET_MODE", "targetMode", "MANUAL")));
      assertWire(wire, modeId, "SET_MODE", "FAN", "targetMode", "MANUAL");
      assertThat(post(http, owner, mist, Map.of("action", "SET_MODE", "targetMode", "MANUAL")).body())
          .contains("COMMAND_IN_PROGRESS");
      ack(mqtt, prefix, code, modeId, "FAN", "APPLIED", "OK", "OFF");
      await(() -> "ACKNOWLEDGED".equals(status(modeId)));
      // Even an ACK of SET_MODE does not invent device state before a state report.
      assertThat(jdbc.queryForObject("select mode from swiftletcare.actuators where id=?", String.class, mist))
          .isEqualTo("AUTO");
      state(mqtt, prefix, code, "MANUAL", true, "OFF");
      await(() -> jdbc.queryForObject("""
          select count(*) from swiftletcare.actuators where device_id=? and mode='MANUAL' and safety_override
          """, Integer.class, device) == 4);
      var unsafe = post(http, owner, fan, Map.of("action", "SET_STATE", "targetState", false));
      assertThat(unsafe.statusCode()).isEqualTo(409);
      assertThat(unsafe.body()).contains("SAFETY_OVERRIDE");

      String mistId = accepted(post(http, owner, mist, Map.of("action", "SET_STATE", "targetState", true)));
      assertWire(wire, mistId, "SET_STATE", "MIST", "targetState", "ON");
      // Same ID, wrong actuator: an ACK for FAN cannot complete the MIST command.
      ack(mqtt, prefix, code, mistId, "FAN", "APPLIED", "OK", "ON");
      ack(mqtt, prefix, code, mistId, "MIST", "REJECTED", "MODE_NOT_MANUAL", "OFF");
      await(() -> "REJECTED".equals(status(mistId)));
      assertThat(jdbc.queryForObject(
          "select failure_reason from swiftletcare.control_commands where command_id=?", String.class, mistId))
          .isEqualTo("MODE_NOT_MANUAL");

      for (String kind : new String[] {"HEATER", "AMPLIFIER"}) {
        state(mqtt, prefix, code, "MANUAL", false, "OFF");
        String id = accepted(post(http, owner, actuators.get(kind), Map.of("action", "SET_STATE", "targetState", true)));
        assertWire(wire, id, "SET_STATE", kind, "targetState", "ON");
        if ("HEATER".equals(kind)) {
          ack(mqtt, prefix, code, id, kind, "APPLIED", "OK", "ON");
          await(() -> "ACKNOWLEDGED".equals(status(id)));
        } else {
          jdbc.update("update swiftletcare.control_commands set sent_at=clock_timestamp()-interval '11 seconds' where command_id=?", id);
          await(() -> "FAILED".equals(status(id)));
          assertThat(jdbc.queryForObject(
              "select failure_reason from swiftletcare.control_commands where command_id=?", String.class, id))
              .isEqualTo("ACK_TIMEOUT");
          ack(mqtt, prefix, code, id, kind, "APPLIED", "OK", "ON");
          state(mqtt, prefix, code, "MANUAL", false, "ON");
          await(() -> Boolean.TRUE.equals(jdbc.queryForObject(
              "select \"on\" from swiftletcare.actuators where id=?", Boolean.class, actuators.get(kind))));
          assertThat(status(id)).isEqualTo("FAILED");
        }
      }
    } finally {
      if (mqtt.isConnected()) mqtt.disconnect();
      mqtt.close();
    }
  }

  private long insert(String sql, Object... params) {
    return jdbc.queryForObject(sql, Long.class, params);
  }

  private String login(long house, String role) {
    long user = insert("""
        insert into swiftletcare.users(email,password_hash,role,active) values(?,'unused','OWNER',true) returning id
        """, UUID.randomUUID() + "@test.invalid");
    jdbc.update("""
        insert into swiftletcare.house_memberships(user_id,house_id,role,active,joined_at) values(?,?,?,true,now())
        """, user, house, role);
    var now = Instant.now();
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("swiftletcare").subject(Long.toString(user))
            .issuedAt(now).expiresAt(now.plusSeconds(900)).build())).getTokenValue();
  }

  private HttpResponse<String> post(HttpClient http, String token, long actuator, Map<String, ?> body)
      throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
        + "/api/v1/actuators/" + actuator + "/commands"))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String accepted(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    var body = json.readTree(response.body());
    assertThat(body.get("status").asText()).isEqualTo("PENDING");
    return body.get("commandId").asText();
  }

  private void assertWire(LinkedBlockingQueue<String> wire, String id, String action, String kind,
      String targetKey, String target) throws Exception {
    var raw = wire.poll(5, TimeUnit.SECONDS);
    assertThat(raw).isNotNull();
    var payload = json.readTree(raw);
    assertThat(payload.get("command_id").asText()).isEqualTo(id);
    assertThat(payload.get("action").asText()).isEqualTo(action);
    assertThat(payload.get("actuator").asText()).isEqualTo(kind);
    assertThat(payload.get(targetKey).asText()).isEqualTo(target);
    assertThat(payload.has(targetKey.equals("targetMode") ? "targetState" : "targetMode")).isFalse();
    assertThat(Instant.parse(payload.get("expiresAt").asText()).getNano()).isZero();
  }

  private void publish(MqttClient mqtt, String topic, Map<String, ?> payload) throws Exception {
    mqtt.publish(topic, json.writeValueAsBytes(payload), 1, false);
  }

  private void state(MqttClient mqtt, String topic, String code, String mode, boolean safety, String amplifier)
      throws Exception {
    publish(mqtt, topic + "/state", Map.of("device_id", code, "mode", mode, "safetyOverride", safety,
        "actuators", Map.of("FAN", "ON", "MIST", "OFF", "HEATER", "OFF", "AMPLIFIER", amplifier)));
  }

  private void ack(MqttClient mqtt, String topic, String code, String id, String kind, String status,
      String reason, String state) throws Exception {
    publish(mqtt, topic + "/command_ack", Map.of("device_id", code, "command_id", id,
        "actuator", kind, "status", status, "reason", reason, "mode", "MANUAL",
        "state", state, "gpio_state", state.equals("ON") ? "HIGH" : "LOW"));
  }

  private String status(String id) {
    return jdbc.queryForObject("select status from swiftletcare.control_commands where command_id=?",
        String.class, id);
  }

  private void await(BooleanSupplier check) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline && !check.getAsBoolean()) Thread.sleep(50);
    assertThat(check.getAsBoolean()).isTrue();
  }
}
