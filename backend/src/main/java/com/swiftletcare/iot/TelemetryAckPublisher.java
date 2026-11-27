package com.swiftletcare.iot;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Hands committed telemetry ACKs to Paho without waiting on its callback thread. */
final class TelemetryAckPublisher implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(TelemetryAckPublisher.class);
  private static final int MAX_ATTEMPTS = 4;
  private static final long TICK_MS = 10;
  private static final long SLOW_CONFIRM_NS = TimeUnit.SECONDS.toNanos(10);
  private static final long DRAIN_NS = TimeUnit.SECONDS.toNanos(3);

  private record Key(String topic, String eventId) {}

  private static final class Pending {
    final byte[] payload;
    int attempts;
    long retryAt;
    long publishedAt;
    boolean slowReported;
    CompletableFuture<Throwable> confirmation;

    Pending(byte[] payload) {
      this.payload = payload;
    }
  }

  private final MqttAsyncClient client;
  private final int capacity;
  private final LinkedHashMap<Key, Pending> pending = new LinkedHashMap<>();
  private final Counter queued, published, confirmed, coalesced, retry, dropped, slowConfirm;
  private java.util.concurrent.ScheduledExecutorService worker;
  private boolean closing;

  TelemetryAckPublisher(MqttAsyncClient client, int capacity, MeterRegistry meters) {
    if (capacity < 1) throw new IllegalArgumentException("ACK queue capacity must be positive");
    this.client = client;
    this.capacity = capacity;
    queued = counter(meters, "queued");
    published = counter(meters, "published");
    confirmed = counter(meters, "confirmed");
    coalesced = counter(meters, "coalesced");
    retry = counter(meters, "retry");
    dropped = counter(meters, "dropped");
    slowConfirm = counter(meters, "slow_confirmation");
  }

  private static Counter counter(MeterRegistry meters, String stage) {
    return meters.counter("swiftletcare.mqtt.telemetry.ack", "stage", stage);
  }

  synchronized void start() {
    worker = Executors.newSingleThreadScheduledExecutor(
        task -> Thread.ofPlatform().daemon().name("mqtt-telemetry-ack").unstarted(task));
    worker.scheduleWithFixedDelay(() -> tick(System.nanoTime()), 0, TICK_MS, TimeUnit.MILLISECONDS);
  }

  synchronized void enqueue(String topic, String eventId, byte[] payload) {
    var key = new Key(topic, eventId);
    if (!closing && pending.containsKey(key)) {
      coalesced.increment();
      return;
    }
    if (closing || pending.size() >= capacity) {
      dropped.increment();
      log.warn("Telemetry ACK not queued eventId={} reason={} pending={}; device can retry",
          eventId, closing ? "shutdown" : "queue_full", pending.size());
      return;
    }
    pending.put(key, new Pending(payload));
    queued.increment();
    log.debug("Telemetry ACK queued eventId={} pending={}", eventId, pending.size());
  }

  synchronized void tick(long now) {
    if (pending.isEmpty()) return;
    var entry = pending.firstEntry();
    var key = entry.getKey();
    var ack = entry.getValue();
    if (ack.confirmation != null) {
      if (!ack.confirmation.isDone()) {
        if (!ack.slowReported && now - ack.publishedAt >= SLOW_CONFIRM_NS) {
          ack.slowReported = true;
          slowConfirm.increment();
          log.warn("Telemetry ACK waiting for MQTT PUBACK eventId={} pending={}",
              key.eventId(), pending.size());
        }
        return; // Paho still owns this QoS 1 send. Do not start a second copy.
      }
      Throwable failure = ack.confirmation.getNow(null);
      ack.confirmation = null;
      if (failure == null) {
        confirmed.increment();
        log.debug("Telemetry ACK confirmed by broker eventId={}", key.eventId());
        finish(key);
      } else {
        failed(key, ack, now, failure);
      }
      return;
    }
    if ((ack.attempts > 0 && now - ack.retryAt < 0) || !client.isConnected()) return;

    ack.attempts++;
    var completion = new CompletableFuture<Throwable>();
    try {
      var token = client.publish(key.topic(), ack.payload, 1, false, null, new IMqttActionListener() {
        @Override public void onSuccess(IMqttToken ignored) {
          completion.complete(null);
        }

        @Override public void onFailure(IMqttToken ignored, Throwable failure) {
          completion.complete(failure == null ? new IllegalStateException("MQTT publish failed") : failure);
        }
      });
      ack.confirmation = completion;
      ack.publishedAt = now;
      ack.slowReported = false;
      published.increment();
      log.debug("Telemetry ACK published to Paho eventId={} messageId={} attempt={}",
          key.eventId(), token.getMessageId(), ack.attempts);
    } catch (MqttException failure) {
      failed(key, ack, now, failure);
    }
  }

  private void failed(Key key, Pending ack, long now, Throwable failure) {
    Object reason = failure instanceof MqttException mqtt ? mqtt.getReasonCode() : failure.getClass().getSimpleName();
    if (ack.attempts >= MAX_ATTEMPTS) {
      dropped.increment();
      log.warn("Telemetry ACK retries exhausted eventId={} attempt={} reason={}; device can retry",
          key.eventId(), ack.attempts, reason);
      finish(key);
      return;
    }
    retry.increment();
    ack.retryAt = now + TimeUnit.MILLISECONDS.toNanos(100L << (ack.attempts - 1));
    log.warn("Telemetry ACK retry scheduled eventId={} attempt={} reason={}",
        key.eventId(), ack.attempts, reason);
  }

  private void finish(Key key) {
    pending.remove(key);
    if (pending.isEmpty()) notifyAll();
  }

  @Override
  public synchronized void close() {
    if (closing) return;
    closing = true;
    if (worker != null) {
      long deadline = System.nanoTime() + DRAIN_NS;
      try {
        long remaining;
        while (!pending.isEmpty() && (remaining = deadline - System.nanoTime()) > 0) {
          TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        worker.shutdown();
      }
    }
    if (!pending.isEmpty()) {
      dropped.increment(pending.size());
      log.warn("Telemetry ACK publisher shutdown with {} unconfirmed entries; devices can retry", pending.size());
      pending.clear();
    }
  }
}
