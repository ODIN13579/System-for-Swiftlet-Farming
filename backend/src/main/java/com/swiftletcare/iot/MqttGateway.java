package com.swiftletcare.iot;

import com.swiftletcare.realtime.Realtime;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "app.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class MqttGateway implements MqttCallbackExtended {
  private static final Logger log = LoggerFactory.getLogger(MqttGateway.class);
  private final MqttAsyncClient client;
  private final MqttConnectOptions options = new MqttConnectOptions();
  private final Ingestion ingestion;
  private final Realtime realtime;
  private final JsonMapper json;
  private final Validator validator;
  private final DeviceEvents deviceEvents;

  public MqttGateway(
      Ingestion ingestion,
      Realtime realtime,
      JsonMapper json,
      Validator validator,
      DeviceEvents deviceEvents,
      @Value("${app.mqtt.url}") String url,
      @Value("${app.mqtt.client-id:swiftletcare-backend}") String id,
      @Value("${app.mqtt.username:}") String username,
      @Value("${app.mqtt.password:}") String password)
      throws MqttException {
    this.ingestion = ingestion;
    this.realtime = realtime;
    this.json = json;
    this.validator = validator;
    this.deviceEvents = deviceEvents;
    client = new MqttAsyncClient(url, id, new MemoryPersistence());
    client.setCallback(this);
    options.setAutomaticReconnect(true);
    options.setCleanSession(true);
    options.setConnectionTimeout(3);
    options.setKeepAliveInterval(10);
    if (!username.isBlank()) {
      options.setUserName(username);
      options.setPassword(password.toCharArray());
    }
  }

  @Scheduled(fixedDelayString = "${app.mqtt.reconnect-ms:3000}")
  public void connect() {
    if (!client.isConnected())
      try {
        client.connect(options);
      } catch (MqttException e) {
        /* Paho reconnect or connection is already in progress. */
      }
  }

  public void connectComplete(boolean reconnect, String uri) {
    try {
      client.subscribe(
          new String[] {"swiftletcare/+/+/+/telemetry", "swiftletcare/+/+/+/heartbeat", "swiftletcare/+/+/+/events"},
          new int[] {0, 0, 1});
    } catch (MqttException e) {
      log.warn("MQTT subscription failed: {}", e.getReasonCode());
    }
  }

  public void connectionLost(Throwable cause) {
    log.warn("MQTT connection lost; reconnect enabled");
  }

  public void deliveryComplete(IMqttDeliveryToken token) {}

  public void messageArrived(String topic, MqttMessage message) {
    try {
      if (message.isRetained()) throw new IllegalArgumentException("Retained input rejected");
      if (message.getPayload().length > 16384)
        throw new IllegalArgumentException("Payload too large");
      var parts = topic.split("/");
      if (parts.length != 5 || !parts[0].equals("swiftletcare")) return;
      long house = Long.parseLong(parts[1]);
      long zone = Long.parseLong(parts[2]);
      if (parts[4].equals("telemetry")) {
        var p = json.readValue(message.getPayload(), Ingestion.Payload.class);
        if (!validator.validate(p).isEmpty() || !parts[3].equals(p.device_id()))
          throw new IllegalArgumentException("Invalid telemetry");
        var result =
            ingestion.store(house, zone, p); // already committed, including duplicate verification
        try {
          client.publish(
              topic.substring(0, topic.lastIndexOf('/')) + "/telemetry_ack",
              json.writeValueAsBytes(
                  Map.of("device_id", p.device_id(), "event_id", p.event_id(), "status", "STORED")),
              1,
              false);
        } finally {
          if (result.inserted())
            realtime.publish(result.data().deviceId(), "telemetry.updated", result.data());
        }
      } else if (parts[4].equals("events")) {
        var p = json.readValue(message.getPayload(), DeviceEvents.Event.class);
        if (!parts[3].equals(p.device_id())) throw new IllegalArgumentException("Invalid event topic");
        deviceEvents.accept(house, zone, p);
      } else if (parts[4].equals("heartbeat")) {
        var p = json.readValue(message.getPayload(), Ingestion.Heartbeat.class);
        if (!validator.validate(p).isEmpty() || !parts[3].equals(p.device_id()))
          throw new IllegalArgumentException("Invalid heartbeat");
        ingestion.heartbeat(house, zone, p);
      }
    } catch (Exception e) {
      log.warn("MQTT input rejected or processing failed ({})", e.getClass().getSimpleName());
    }
  }

  @PreDestroy
  public void close() throws MqttException {
    if (client.isConnected()) client.disconnect().waitForCompletion(3000);
    client.close();
  }
}
