package com.swiftletcare.iot;

import com.swiftletcare.api.*;
import com.swiftletcare.management.*;
import com.swiftletcare.realtime.Realtime;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Ingestion {
  public record Payload(
      @NotBlank @Size(max = 150) String event_id,
      @NotBlank @Size(max = 100) String device_id,
      @NotNull Instant timestamp,
      Double temperature,
      Double humidity,
      Double co2,
      Double light,
      @NotNull String sensor_status) {
    public Payload {
      if (timestamp != null)
        timestamp = timestamp.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
  }

  public record Heartbeat(
      @NotBlank @Size(max = 100) String device_id, @NotNull Instant timestamp) {}

  public record Stored(Views.TelemetryView data, boolean inserted) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final DeviceRepository devices;
  private final Realtime realtime;
  private final long timeout;

  public Ingestion(
      JdbcTemplate jdbc,
      PlatformTransactionManager tm,
      DeviceRepository devices,
      Realtime realtime,
      @Value("${app.heartbeat-timeout-ms:6000}") long timeout) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(tm);
    this.devices = devices;
    this.realtime = realtime;
    if (timeout < 1) throw new IllegalArgumentException("timeout must be positive");
    this.timeout = timeout;
  }

  private Device valid(String code, long house, long zone, String type) {
    var d = devices.findByDeviceId(code).orElseThrow(ApiError::missing);
    if (!d.enabled
        || !d.type.equals(type)
        || jdbc.queryForList(
                "select d.id from swiftletcare.devices d join swiftletcare.zones z on z.id=d.zone_id"
                    + " join swiftletcare.houses h on h.id=z.house_id where z.id=? and z.id=? and h.id=?"
                    + " and d.id=? and d.enabled and z.active and h.active for share of h,z for update of d",
                Long.class,
                d.zoneId,
                zone,
                house,
                d.id).isEmpty()) throw ApiError.bad("Device/topic mismatch or disabled resource");
    return d;
  }

  private void validate(Payload p) {
    if (p.timestamp() == null || p.timestamp().isAfter(Instant.now().plusSeconds(300)))
      throw ApiError.bad("Invalid measurement timestamp");
    Double[] values = {p.temperature(), p.humidity(), p.co2(), p.light()};
    int present = 0;
    for (Double v : values)
      if (v != null) {
        if (!Double.isFinite(v)) throw ApiError.bad("Measurement must be finite");
        present++;
      }
    // NFR-IOT-004: inclusive measurement ranges; null still represents a failed sensor.
    if (p.temperature() != null && (p.temperature() < -10 || p.temperature() > 60)
        || p.humidity() != null && (p.humidity() < 0 || p.humidity() > 100)
        || p.co2() != null && (p.co2() < 0 || p.co2() > 5000)
        || p.light() != null && (p.light() < 0 || p.light() > 100000))
      throw ApiError.bad("Measurement outside NFR-IOT-004 range");
    String status = present == 4 ? "OK" : present == 0 ? "ERROR" : "PARTIAL_ERROR";
    if (!status.equals(p.sensor_status()))
      throw ApiError.bad("sensor_status inconsistent with null measurements");
  }

  // TransactionTemplate returns only after commit. A PostgreSQL upsert waits for concurrent
  // inserts;
  // DO NOTHING does not abort the transaction, unlike catching a unique violation inside it.
  public Stored store(long house, long zone, Payload p) {
    validate(p);
    return tx.execute(
        s -> {
          var d = valid(p.device_id(), house, zone, "ESP32");
          int n =
              jdbc.update(
                  """
insert into swiftletcare.telemetry(device_id,event_id,timestamp,received_at,temperature,humidity,co2,light,sensor_status)
values(?,?,?,now(),?,?,?,?,?) on conflict(device_id,event_id) do nothing
""",
                  d.id,
                  p.event_id(),
                  java.sql.Timestamp.from(p.timestamp()),
                  p.temperature(),
                  p.humidity(),
                  p.co2(),
                  p.light(),
                  p.sensor_status());
          var t =
              jdbc.queryForObject(
                  "select * from swiftletcare.telemetry where device_id=? and event_id=?",
                  (rs, row) -> {
                    var x = new Telemetry();
                    x.id = rs.getLong("id");
                    x.deviceId = d.id;
                    x.eventId = rs.getString("event_id");
                    x.timestamp = rs.getTimestamp("timestamp").toInstant();
                    x.receivedAt = rs.getTimestamp("received_at").toInstant();
                    x.temperature = (Double) rs.getObject("temperature");
                    x.humidity = (Double) rs.getObject("humidity");
                    x.co2 = (Double) rs.getObject("co2");
                    x.light = (Double) rs.getObject("light");
                    x.sensorStatus = rs.getString("sensor_status");
                    return x;
                  },
                  d.id,
                  p.event_id());
          if (!t.timestamp.equals(p.timestamp().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
              || !Objects.equals(t.temperature, p.temperature())
              || !Objects.equals(t.humidity, p.humidity())
              || !Objects.equals(t.co2, p.co2())
              || !Objects.equals(t.light, p.light())
              || !Objects.equals(t.sensorStatus, p.sensor_status())) throw ApiError.conflict();
          return new Stored(Views.TelemetryView.of(t), n == 1);
        });
  }

  public synchronized void heartbeat(long house, long zone, Heartbeat p) {
    if (p.timestamp() == null || p.timestamp().isAfter(Instant.now().plusSeconds(300)))
      throw ApiError.bad("Invalid heartbeat timestamp");
    Views.DeviceView changed =
        tx.execute(
            s -> {
              var d = valid(p.device_id(), house, zone, "ESP32");
              // Serialize heartbeat and scheduler on the device row; use database receipt time.
              var old =
                  jdbc.queryForObject(
                      "select online from swiftletcare.devices where id=? for update",
                      Boolean.class,
                      d.id);
              jdbc.update(
                  "update swiftletcare.devices set online=true,last_seen_at=clock_timestamp() where"
                      + " id=?",
                  d.id);
              if (Boolean.TRUE.equals(old)) return null;
              var seen =
                  jdbc.queryForObject(
                          "select last_seen_at from swiftletcare.devices where id=?",
                          java.sql.Timestamp.class,
                          d.id)
                      .toInstant();
              return new Views.DeviceView(
                  d.id, d.deviceId, d.zoneId, d.name, d.type, d.enabled, true, seen);
            });
    if (changed != null) realtime.publish(changed.id(), "device.status.changed", changed);
  }

  @Scheduled(fixedDelayString = "${app.status-scan-ms:1000}")
  public synchronized void expire() {
    var changed =
        tx.execute(
            s ->
                jdbc.query(
                    """
update swiftletcare.devices set online=false where online and (last_seen_at is null or last_seen_at < clock_timestamp() - (? * interval '1 millisecond')) returning *
""",
                    (rs, row) -> {
                      var d = new Device();
                      d.id = rs.getLong("id");
                      d.deviceId = rs.getString("device_id");
                      d.zoneId = rs.getLong("zone_id");
                      d.name = rs.getString("name");
                      d.type = rs.getString("type");
                      d.enabled = rs.getBoolean("enabled");
                      d.online = false;
                      var ts = rs.getTimestamp("last_seen_at");
                      d.lastSeenAt = ts == null ? null : ts.toInstant();
                      return d;
                    },
                    timeout));
    for (var d : changed) realtime.publish(d.id, "device.status.changed", Views.DeviceView.of(d));
  }
}
