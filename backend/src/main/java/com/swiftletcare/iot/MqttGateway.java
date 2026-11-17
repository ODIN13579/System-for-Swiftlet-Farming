package com.swiftletcare.iot;

import java.util.Map;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.swiftletcare.realtime.Realtime;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
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
  private final DeviceConfigSync deviceConfigSync;
  private final ControlCommands control;
  private volatile boolean configAckReady;
  private volatile boolean controlSubscribed;

  public MqttGateway(
      Ingestion ingestion,
      Realtime realtime,
      JsonMapper json,
      Validator validator,
      DeviceEvents deviceEvents,
      DeviceConfigSync deviceConfigSync,
      ControlCommands control,
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
    this.deviceConfigSync = deviceConfigSync;
    this.control = control;
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
    configAckReady = false;
    controlSubscribed = false;
    try {
      client.subscribe(
          new String[] {"swiftletcare/+/+/+/telemetry", "swiftletcare/+/+/+/heartbeat", "swiftletcare/+/+/+/events"},
          new int[] {0, 0, 1});
      if (deviceConfigSync.enabled())
        client.subscribe("swiftletcare/+/+/+/cfg_ack", 1, null, new IMqttActionListener() {
          @Override public void onSuccess(IMqttToken token) {
            int[] granted = token.getGrantedQos();
            configAckReady = granted.length == 1 && granted[0] <= 2;
            if (!configAckReady) log.warn("Broker refused cfg_ack subscription");
          }

          @Override public void onFailure(IMqttToken token, Throwable error) {
            log.warn("Unable to subscribe to cfg_ack", error);
          }
        });
    } catch (MqttException e) {
      log.warn("MQTT subscription failed: {}", e.getReasonCode());
    }
    subscribeControl();
  }

  private void subscribeControl() {
    if (!control.enabled()) return;
    try {
      client.subscribe(new String[] {"swiftletcare/+/+/+/command_ack", "swiftletcare/+/+/+/state"},
          new int[] {1, 1}, null, new IMqttActionListener() {
            @Override public void onSuccess(IMqttToken token) {
              int[] granted = token.getGrantedQos();
              controlSubscribed = granted.length == 2 && granted[0] <= 2 && granted[1] <= 2;
              if (!controlSubscribed) log.warn("Broker refused actuator subscriptions");
            }

            @Override public void onFailure(IMqttToken token, Throwable error) {
              log.warn("Unable to subscribe to actuator topics", error);
            }
          });
    } catch (MqttException e) {
      log.warn("Could not subscribe to actuator topics: {}", e.getReasonCode());
    }
  }

  boolean controlReady() {
    return controlSubscribed && client.isConnected();
  }

  @Scheduled(fixedDelay = 200)
  public void sendPendingCommands() {
    if (!controlReady()) return;
    for (String id : control.pending()) {
      if (!controlReady()) break;
      var command = control.claim(id);
      if (command == null) continue;
      try {
        client.publish(command.topic(), json.writeValueAsBytes(command.payload()), 1, false);
      } catch (Exception e) {
        control.publishFailed(id);
        log.warn("Could not send actuator command {} ({})", id, e.getClass().getSimpleName());
      }
    }
  }

  public void connectionLost(Throwable cause) {
    configAckReady = false;
    controlSubscribed = false;
    log.warn("MQTT connection lost; reconnect enabled");
  }

  @Scheduled(fixedDelayString = "${app.mqtt.config-sync-poll-ms:1000}")
  public void sendPendingConfig() {
    if (!configAckReady || !client.isConnected()) return;
    for (var p : deviceConfigSync.pending()) {
      if (!configAckReady || !client.isConnected()) break;
      if (!deviceConfigSync.beginAttempt(p)) continue;
      try {
        client.publish(p.topic(), json.writeValueAsBytes(p.config()), 1, false);
      } catch (MqttException e) {
        log.warn("Could not send config for device {}: {}", p.deviceId(), e.getReasonCode());
      }
    }
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
      } else if (parts[4].equals("command_ack") && control.enabled()) {
        control.acceptAck(house, zone, parts[3], json.readValue(message.getPayload(), ControlCommands.Ack.class));
      } else if (parts[4].equals("state") && control.enabled()) {
        control.acceptState(house, zone, parts[3], json.readValue(message.getPayload(), ControlCommands.State.class));
      } else if (parts[4].equals("cfg_ack") && deviceConfigSync.enabled()) {
        var ack = json.readValue(message.getPayload(), DeviceConfigSync.Ack.class);
        deviceConfigSync.accept(house, zone, parts[3], ack);
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
