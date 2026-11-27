package com.swiftletcare.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.Test;

class TelemetryAckPublisherTest {
  private static final String TOPIC = "swiftletcare/1/1/device/telemetry_ack";
  private final MqttAsyncClient client = mock(MqttAsyncClient.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final List<IMqttActionListener> callbacks = new ArrayList<>();
  private final byte[] payload = {1};

  private void acceptPublishes() throws MqttException {
    when(client.publish(eq(TOPIC), any(byte[].class), eq(1), eq(false), isNull(),
        any(IMqttActionListener.class))).thenAnswer(call -> {
          callbacks.add(call.getArgument(5));
          return mock(IMqttDeliveryToken.class);
        });
  }

  @Test
  void keepsOneUnconfirmedPublishAndCoalescesOnlyWhilePending() throws Exception {
    when(client.isConnected()).thenReturn(true);
    acceptPublishes();
    try (var publisher = new TelemetryAckPublisher(client, 2, meters)) {
      publisher.enqueue(TOPIC, "first", payload);
      publisher.enqueue(TOPIC, "first", payload);
      publisher.enqueue(TOPIC, "second", payload);
      publisher.enqueue(TOPIC, "overflow", payload);
      publisher.tick(1);
      publisher.tick(TimeUnit.SECONDS.toNanos(20));
      assertThat(callbacks).hasSize(1);
      assertThat(count("slow_confirmation")).isEqualTo(1);
      callbacks.getFirst().onSuccess(null);
      publisher.tick(TimeUnit.SECONDS.toNanos(21));
      publisher.tick(TimeUnit.SECONDS.toNanos(22));
      assertThat(callbacks).hasSize(2);
      callbacks.getLast().onSuccess(null);
      publisher.tick(TimeUnit.SECONDS.toNanos(23));
      publisher.enqueue(TOPIC, "first", payload);
      publisher.tick(TimeUnit.SECONDS.toNanos(24));
      callbacks.getLast().onSuccess(null);
      publisher.tick(TimeUnit.SECONDS.toNanos(25));
      assertThat(callbacks).hasSize(3);
      assertThat(count("confirmed")).isEqualTo(3);
      assertThat(count("dropped")).isEqualTo(1);
      assertThat(count("coalesced")).isEqualTo(1);
    }
  }

  @Test
  void retriesDefinitiveFailureAfterReconnectAndBackoff() throws Exception {
    when(client.isConnected()).thenReturn(true);
    acceptPublishes();
    try (var publisher = new TelemetryAckPublisher(client, 4, meters)) {
      publisher.enqueue(TOPIC, "retry", payload);
      publisher.tick(1);
      callbacks.getFirst().onFailure(null, new MqttException(MqttException.REASON_CODE_CONNECTION_LOST));
      when(client.isConnected()).thenReturn(false);
      publisher.tick(2);
      publisher.tick(TimeUnit.SECONDS.toNanos(1));
      assertThat(callbacks).hasSize(1);
      when(client.isConnected()).thenReturn(true);
      publisher.tick(TimeUnit.SECONDS.toNanos(2));
      callbacks.getLast().onSuccess(null);
      publisher.tick(TimeUnit.SECONDS.toNanos(3));
      assertThat(callbacks).hasSize(2);
      assertThat(count("retry")).isEqualTo(1);
      assertThat(count("confirmed")).isEqualTo(1);
      assertThat(count("dropped")).isZero();
    }
  }

  @Test
  void maxInflightIsRetriedWithABoundedBudget() throws Exception {
    when(client.isConnected()).thenReturn(true);
    when(client.publish(eq(TOPIC), any(byte[].class), eq(1), eq(false), isNull(),
        any(IMqttActionListener.class)))
        .thenThrow(new MqttException(MqttException.REASON_CODE_MAX_INFLIGHT));
    try (var publisher = new TelemetryAckPublisher(client, 1, meters)) {
      publisher.enqueue(TOPIC, "retry", payload);
      publisher.tick(0);
      publisher.tick(1);
      for (int seconds = 1; seconds <= 5; seconds++) publisher.tick(TimeUnit.SECONDS.toNanos(seconds));
      verify(client, times(4)).publish(eq(TOPIC), any(byte[].class), eq(1), eq(false), isNull(),
          any(IMqttActionListener.class));
      assertThat(count("retry")).isEqualTo(3);
      assertThat(count("dropped")).isEqualTo(1);
      assertThat(count("confirmed")).isZero();
    }
  }

  private double count(String stage) {
    return meters.get("swiftletcare.mqtt.telemetry.ack").tag("stage", stage).counter().count();
  }
}
