
package com.swiftletcare.alert;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.swiftletcare.realtime.HouseAlertRealtime;

@Service
public class AlertEngine {
  private static final Logger log =
      LoggerFactory.getLogger(AlertEngine.class);


  record Thresholds(
    Double rhOn, Double rhOff,
    Double tempMin, Double tempMax, Double hysteresis,
    Double co2Min, Double co2Max,
    Double lightMin, Double lightMax
  ) {}

  record Sample(
    Double humidity, Double temperature,
    Double co2, Double light,
    String status, Instant receivedAt
  ) {}

  record Offline(long id, long zoneId, String code) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final HouseAlertRealtime realtime;
  private final long offlineMs;
  private final long dedupMs;
  private final long manualMs;

  public AlertEngine(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      HouseAlertRealtime realtime,
      @Value("${app.alert.device-offline-ms:9000}") long offlineMs,
      @Value("${app.alert.dedup-ms:300000}") long dedupMs,
      @Value("${app.alert.manual-min-ms:300000}") long manualMs) {
    if (offlineMs <= 0 || dedupMs <= 0 || manualMs <= 0)
      throw new IllegalArgumentException("Invalid alert timing");

    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.realtime = realtime;
    this.offlineMs = offlineMs;
    this.dedupMs = dedupMs;
    this.manualMs = manualMs;
  }

  private Long lockHouse(long zone) {
    var result = db.queryForList("""
        select z.house_id from swiftletcare.zones z
        join swiftletcare.houses h on h.id=z.house_id
        where z.id=? and z.active and h.active
        for update of z
        """, Long.class, zone);

    return result.isEmpty() ? null : result.getFirst();
  }

  private Thresholds thresholds(long zone) {
    var values = db.query("""
        select humidity_on, humidity_off,
                temp_min, temp_max, temp_hysteresis,
                co2_min, co2_max, light_min, light_max
        from swiftletcare.zone_configs
        where zone_id=?
        """, (rs, i) -> new Thresholds(
            (Double) rs.getObject("humidity_on"),
            (Double) rs.getObject("humidity_off"),
            (Double) rs.getObject("temp_min"),
            (Double) rs.getObject("temp_max"),
            (Double) rs.getObject("temp_hysteresis"),
            (Double) rs.getObject("co2_min"),
            (Double) rs.getObject("co2_max"),
            (Double) rs.getObject("light_min"),
            (Double) rs.getObject("light_max")
        ), zone);

    return values.isEmpty() ? null : values.getFirst();
  }



  private List<Sample> recent(long device) {
    return db.query("""
        select humidity, temperature, co2, light,
                sensor_status, received_at
        from swiftletcare.telemetry
        where device_id=?
        order by received_at desc, id desc
        limit 3
        """, (rs, i) -> new Sample(
            (Double) rs.getObject("humidity"),
            (Double) rs.getObject("temperature"),
            (Double) rs.getObject("co2"),
            (Double) rs.getObject("light"),
            rs.getString("sensor_status"),
            rs.getTimestamp("received_at").toInstant()
        ), device);
  }
  

  private boolean configured(Thresholds c) {
    return c != null && (
        c.rhOn() != null ||
        c.tempMin() != null || c.tempMax() != null ||
        c.co2Min() != null || c.co2Max() != null ||
        c.lightMin() != null || c.lightMax() != null
    );
  }

  private String violation(Thresholds c, Sample s) {
    if (!configured(c)) return null;

    if (c.rhOn() != null && s.humidity() != null
            && s.humidity() < c.rhOn())
        return "LOW_HUMIDITY";

    if (c.tempMin() != null && s.temperature() != null
            && s.temperature() < c.tempMin())
        return "LOW_TEMPERATURE";

    if (c.tempMax() != null && s.temperature() != null
            && s.temperature() > c.tempMax())
        return "HIGH_TEMPERATURE";

    if (c.co2Min() != null && s.co2() != null
            && s.co2() < c.co2Min())
        return "LOW_CO2";

    if (c.co2Max() != null && s.co2() != null
            && s.co2() > c.co2Max())
        return "HIGH_CO2";

    if (c.lightMin() != null && s.light() != null
            && s.light() < c.lightMin())
        return "LOW_LIGHT";

    if (c.lightMax() != null && s.light() != null
            && s.light() > c.lightMax())
        return "HIGH_LIGHT";

    return null;
  }

  private String threeConsecutive(
      Thresholds c, List<Sample> values) {
    if (values.size() != 3) return null;

    String reason = violation(c, values.getFirst());
    if (reason == null) return null;

    for (var sample : values) {
      if (!reason.equals(violation(c, sample))) return null;
    }
    return reason;
  }

  private boolean healthy(Thresholds c, Sample s) {
    if (!configured(c)) return false;

    double hysteresis =
        c.hysteresis() == null ? 0.0 : c.hysteresis();

    if (c.rhOn() != null) {
        double recovery =
            c.rhOff() == null ? c.rhOn() : c.rhOff();

        if (s.humidity() == null || s.humidity() < recovery)
            return false;
    }

    if (c.tempMin() != null &&
            (s.temperature() == null ||
              s.temperature() < c.tempMin() + hysteresis))
        return false;

    if (c.tempMax() != null &&
            (s.temperature() == null ||
              s.temperature() > c.tempMax() - hysteresis))
        return false;

    if (c.co2Min() != null &&
            (s.co2() == null || s.co2() < c.co2Min()))
        return false;

    if (c.co2Max() != null &&
            (s.co2() == null || s.co2() > c.co2Max()))
        return false;

    if (c.lightMin() != null &&
            (s.light() == null || s.light() < c.lightMin()))
        return false;

    if (c.lightMax() != null &&
            (s.light() == null || s.light() > c.lightMax()))
        return false;

    return true;
  }

  private boolean zoneHealthy(long zone, Thresholds c) {
    var deviceIds = db.queryForList("""
        select id from swiftletcare.devices
        where zone_id=? and enabled and type='ESP32'
        """, Long.class, zone);

    if (deviceIds.isEmpty()) return false;

    for (long device : deviceIds) {
      var samples = recent(device);
      if (samples.size() != 3 ||
          !samples.stream().allMatch(s -> healthy(c, s)))
        return false;
    }
    return true;
  }

  private boolean manual(long device) {
    return Boolean.TRUE.equals(db.queryForObject("""
        select exists(
          select 1 from swiftletcare.actuators
          where device_id=? and mode='MANUAL'
        )
        """, Boolean.class, device));
  }

  private boolean sustainedManual(long zone) {
    return Boolean.TRUE.equals(db.queryForObject("""
        select exists(
          select 1 from swiftletcare.alert_manual_conditions m
          join swiftletcare.devices d on d.id=m.device_id
          where d.zone_id=? and d.enabled
          and m.first_seen_at <
              clock_timestamp() - (? * interval '1 millisecond')
        )
        """, Boolean.class, zone, manualMs));
  }

  private void observeManual(
      long house, long zone, long device, String reason) {
    if (reason == null || !manual(device)) {
      db.update(
          "delete from swiftletcare.alert_manual_conditions"
              + " where device_id=?", device);
    } else {
      db.update("""
          insert into swiftletcare.alert_manual_conditions
              (device_id,condition_code,first_seen_at)
          values(?,?,clock_timestamp())
          on conflict(device_id) do update
          set condition_code=excluded.condition_code,
              first_seen_at=case
                when alert_manual_conditions.condition_code =
                     excluded.condition_code
                then alert_manual_conditions.first_seen_at
                else excluded.first_seen_at
              end
          """, device, reason);

      boolean sustained = Boolean.TRUE.equals(db.queryForObject("""
          select first_seen_at <
              clock_timestamp() - (? * interval '1 millisecond')
          from swiftletcare.alert_manual_conditions
          where device_id=?
          """, Boolean.class, manualMs, device));

      if (sustained)
        raise(house, zone, device,
            "MANUAL_OUT_OF_RANGE", "WARNING",
            "Manual out of range: " + reason);
    }

    if (!sustainedManual(zone))
      clear(house, zone, "MANUAL_OUT_OF_RANGE");
  }

  private boolean hasSensorFault(long zone) {
    return Boolean.TRUE.equals(db.queryForObject("""
        select exists(
          select 1 from swiftletcare.alert_sensor_faults f
          join swiftletcare.devices d on d.id=f.device_id
          where d.zone_id=? and d.enabled
        )
        """, Boolean.class, zone));
  }

  public void telemetry(long zone, long device) {
    try {
      tx.executeWithoutResult(s -> {
        Long house = lockHouse(zone);
        if (house == null) return;

        Thresholds c = thresholds(zone);
        List<Sample> samples = recent(device);

        if (configured(c) && samples.size() == 3) {
          String reason = threeConsecutive(c, samples);

          if (reason != null) {
            raise(house, zone, device,
                "ENV_THRESHOLD", "WARNING",
                "Environmental threshold: " + reason);
          } else if (zoneHealthy(zone, c)) {
            clear(house, zone, "ENV_THRESHOLD");
          }
        }

        String latestReason =
            samples.isEmpty() ? null : violation(c, samples.getFirst());
        observeManual(house, zone, device, latestReason);

        
        if (samples.size() == 3 && samples.stream()
            .allMatch(x -> "OK".equals(x.status()))) {

        Instant earliest = samples.getLast().receivedAt();

        // Each sensor fault needs three newer OK measurements.
        db.update("""
            delete from swiftletcare.alert_sensor_faults
            where device_id=? and detected_at < ?
            """, device, Timestamp.from(earliest));

        if (!hasSensorFault(zone))
            clear(house, zone, "SENSOR_FAULT");
        }

      });
    } catch (RuntimeException e) {
      // A committed telemetry measurement must not lose its MQTT ACK
      // because the independent alert pipeline failed.
      log.error("Alert evaluation failed for device {}", device, e);
    }
  }

  public void deviceEvent(
      long zone, long device,
      String eventType, String sensor, String reason) {
    try {
      tx.executeWithoutResult(s -> {
        Long house = lockHouse(zone);
        if (house == null) return;

        if ("SENSOR_FAULT".equals(eventType)) {
          db.update("""
              insert into swiftletcare.alert_sensor_faults
                  (device_id,sensor)
              values(?,?)
              on conflict(device_id,sensor) do update
              set detected_at = clock_timestamp()
              """, device, sensor);

          raise(house, zone, device,
              "SENSOR_FAULT", "CRITICAL",
              "Sensor " + sensor + ": " + reason);

        } else if ("SENSOR_RECOVERED".equals(eventType)) {
          db.update(
              "delete from swiftletcare.alert_sensor_faults"
                  + " where device_id=? and sensor=?",
              device, sensor);

          if (!hasSensorFault(zone))
            clear(house, zone, "SENSOR_FAULT");
        }
        // MANUAL_LIMIT is diagnostic evidence. The alert is raised
        // only when actual MANUAL mode and sustained telemetry confirm P17.
      });
    } catch (RuntimeException e) {
      log.error("Alert event evaluation failed for device {}", device, e);
    }
  }

  public void heartbeat(long zone) {
    try {
      tx.executeWithoutResult(s -> {
        Long house = lockHouse(zone);
        if (house == null) return;

        boolean remaining = Boolean.TRUE.equals(db.queryForObject("""
            select exists(
              select 1 from swiftletcare.devices
              where zone_id=? and type='ESP32' and enabled
              and last_seen_at is not null
              and last_seen_at <
                  clock_timestamp() - (? * interval '1 millisecond')
            )
            """, Boolean.class, zone, offlineMs));

        if (!remaining) clear(house, zone, "DEVICE_OFFLINE");
      });
    } catch (RuntimeException e) {
      log.error("Offline recovery evaluation failed for zone {}", zone, e);
    }
  }

  @Scheduled(fixedDelayString = "${app.alert.scan-ms:1000}")
  public void scanOffline() {
    List<Offline> overdue;

    try {
      overdue = db.query("""
          select d.id,d.zone_id,d.device_id
          from swiftletcare.devices d
          join swiftletcare.zones z on z.id=d.zone_id
          join swiftletcare.houses h on h.id=z.house_id
          where d.type='ESP32' and d.enabled
            and z.active and h.active
            and d.last_seen_at is not null
            and d.last_seen_at <
                clock_timestamp() - (? * interval '1 millisecond')
          """, (rs, i) -> new Offline(
              rs.getLong("id"),
              rs.getLong("zone_id"),
              rs.getString("device_id")
          ), offlineMs);
    } catch (RuntimeException e) {
      log.error("Offline alert scan failed", e);
      return;
    }

    for (Offline d : overdue) {
      try {
        tx.executeWithoutResult(s -> {
          Long house = lockHouse(d.zoneId());
          if (house == null) return;

          boolean stillOffline = Boolean.TRUE.equals(
              db.queryForObject("""
                  select exists(
                    select 1 from swiftletcare.devices
                    where id=? and enabled
                      and last_seen_at <
                          clock_timestamp() -
                          (? * interval '1 millisecond')
                  )
                  """, Boolean.class, d.id(), offlineMs));

          if (stillOffline)
            raise(house, d.zoneId(), d.id(),
                "DEVICE_OFFLINE", "CRITICAL",
                "Device heartbeat missing: " + d.code());
        });
      } catch (RuntimeException e) {
        log.error("Offline alert failed for device {}", d.id(), e);
      }
    }
  }

  private void raise(
      long house, long zone, long device,
      String type, String level, String message) {

    var existing = db.queryForList("""
        select id from swiftletcare.alerts
        where zone_id=? and type=?
          and (
            status <> 'RESOLVED'
            or created_at > clock_timestamp()
                 - (? * interval '1 millisecond')
          )
        order by created_at desc,id desc
        limit 1
        """, Long.class, zone, type, dedupMs);

    if (!existing.isEmpty()) {
        long id = existing.getFirst();

        int reopened = db.update("""
            update swiftletcare.alerts
            set cleared_at=null
            where id=? and status <> 'RESOLVED'
                and cleared_at is not null
            """, id);

        if (reopened == 1)
            notifyAfterCommit(house, "alert.updated", id);
        return;
    }

    long id = db.queryForObject("""
        insert into swiftletcare.alerts
            (zone_id,device_id,type,level,status,message)
        values(?,?,?,?,'NEW',?)
        returning id
        """, Long.class, zone, device, type, level, message);

    db.update("""
        insert into swiftletcare.alert_histories
            (alert_id,changed_by,from_status,to_status,note)
        values(?,null,null,'NEW','Created by Alert Engine')
        """, id);

    notifyAfterCommit(house, "alert.created", id);
    }

    private void clear(long house, long zone, String type) {
    var changed = db.queryForList("""
        update swiftletcare.alerts
        set cleared_at=clock_timestamp()
        where zone_id=? and type=? and cleared_at is null
        returning id
        """, Long.class, zone, type);

    for (Long id : changed)
        notifyAfterCommit(house, "alert.updated", id);
    }

    private void notifyAfterCommit(
        long house, String event, long alertId) {
    var data = db.queryForObject("""
        select a.*,z.house_id
        from swiftletcare.alerts a
        join swiftletcare.zones z on z.id=a.zone_id
        where a.id=?
        """, (rs, i) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", rs.getLong("id"));
            payload.put("houseId", rs.getLong("house_id"));
            payload.put("zoneId", rs.getLong("zone_id"));
            payload.put("deviceId", rs.getObject("device_id", Long.class));
            payload.put("type", rs.getString("type"));
            payload.put("level", rs.getString("level"));
            payload.put("status", rs.getString("status"));
            payload.put("message", rs.getString("message"));
            payload.put(
                "createdAt",
                rs.getTimestamp("created_at").toInstant()
            );
            Timestamp handled = rs.getTimestamp("handled_at");
            Timestamp cleared = rs.getTimestamp("cleared_at");
            payload.put(
                "handledAt", handled == null ? null : handled.toInstant()
            );
            payload.put(
                "clearedAt", cleared == null ? null : cleared.toInstant()
            );
            return payload;
        }, alertId);

    realtime.publishAfterCommit(house, event, data);
  }
}
