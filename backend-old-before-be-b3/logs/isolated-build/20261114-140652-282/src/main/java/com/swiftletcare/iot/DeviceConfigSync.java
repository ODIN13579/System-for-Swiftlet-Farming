package com.swiftletcare.iot;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DeviceConfigSync {
  // Only send configurations the current firmware can apply in full.
  private static final String COMPATIBLE = """
      c.humidity_on IS NOT NULL AND c.humidity_off IS NOT NULL
      AND c.temp_min IS NOT NULL AND c.temp_max IS NOT NULL
      AND c.temp_hysteresis IS NULL
      AND c.co2_min IS NULL AND c.co2_max IS NULL
      AND c.light_min IS NULL AND c.light_max IS NULL
      AND c.heartbeat_interval_sec = 2
      """;

  public record Config(int version, double humidity_on, double humidity_off,
      double temp_min, double temp_max, int telemetry_interval_ms) {}

  public record Pending(long deviceId, long houseId, long zoneId, String deviceCode,
      Config config) {
    public String topic() {
      return "swiftletcare/" + houseId + "/" + zoneId + "/" + deviceCode + "/cfg";
    }
  }

  public record Ack(String device_id, Integer version, String status, String reason,
      String timestamp) {}

  private final JdbcTemplate jdbc;
  private final boolean enabled;

  public DeviceConfigSync(JdbcTemplate jdbc,
      @Value("${CONFIG_SYNC_ENABLED:false}") boolean enabled) {
    this.jdbc = jdbc;
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  // Called in the same transaction that writes zone_configs.
  public void queueForZone(long zoneId, int version) {
    if (!enabled) return;
    jdbc.update("""
        INSERT INTO swiftletcare.device_config_sync AS s
            (device_id, zone_id, desired_version, status)
        SELECT d.id, z.id, c.version, 'PENDING'
        FROM swiftletcare.zone_configs c
        JOIN swiftletcare.zones z ON z.id = c.zone_id
        JOIN swiftletcare.houses h ON h.id = z.house_id
        JOIN swiftletcare.devices d ON d.zone_id = z.id
        WHERE z.id = ? AND c.version = ? AND d.type = 'ESP32' AND d.enabled
          AND z.active AND h.active AND
        """ + COMPATIBLE + """
        ON CONFLICT (device_id, zone_id) DO UPDATE
        SET desired_version = EXCLUDED.desired_version, status = 'PENDING',
            ack_at = NULL, last_attempt_at = NULL
        WHERE s.desired_version < EXCLUDED.desired_version
        """, zoneId, version);
  }

  public String statusForZone(long zoneId, int version) {
    if (!enabled) return "UNKNOWN";
    return jdbc.queryForObject("""
        SELECT CASE
          WHEN count(*) = 0 THEN 'UNKNOWN'
          WHEN count(*) FILTER (WHERE s.status = 'REJECTED') > 0 THEN 'REJECTED'
          WHEN count(*) FILTER (WHERE s.status = 'STALE') > 0 THEN 'STALE'
          WHEN count(*) FILTER (WHERE s.status = 'APPLIED' AND
              s.applied_version = s.desired_version) = count(*) THEN 'APPLIED'
          WHEN count(*) FILTER (WHERE s.status = 'PENDING') > 0 THEN 'PENDING'
          ELSE 'UNKNOWN' END
        FROM swiftletcare.devices d
        JOIN swiftletcare.zones z ON z.id = d.zone_id
        JOIN swiftletcare.houses h ON h.id = z.house_id
        LEFT JOIN swiftletcare.device_config_sync s ON s.device_id = d.id
          AND s.zone_id = d.zone_id AND s.desired_version = ?
        WHERE d.zone_id = ? AND d.type = 'ESP32' AND d.enabled AND z.active AND h.active
        """, String.class, version, zoneId);
  }

  public List<Pending> pending() {
    if (!enabled) return List.of();
    return jdbc.query("""
        SELECT d.id, h.id AS house_id, z.id AS zone_id, d.device_id AS device_code,
          c.version, c.humidity_on, c.humidity_off, c.temp_min, c.temp_max,
          c.telemetry_interval_sec
        FROM swiftletcare.device_config_sync s
        JOIN swiftletcare.devices d ON d.id = s.device_id AND d.zone_id = s.zone_id
        JOIN swiftletcare.zones z ON z.id = d.zone_id
        JOIN swiftletcare.houses h ON h.id = z.house_id
        JOIN swiftletcare.zone_configs c ON c.zone_id = z.id AND c.version = s.desired_version
        WHERE s.status = 'PENDING' AND d.type = 'ESP32' AND d.enabled AND z.active AND h.active
          AND (s.last_attempt_at IS NULL OR s.last_attempt_at <= now() - interval '5 seconds')
          AND
        """ + COMPATIBLE + """
        ORDER BY s.last_attempt_at NULLS FIRST, d.id
        LIMIT 32
        """, (rs, row) -> new Pending(rs.getLong("id"), rs.getLong("house_id"),
        rs.getLong("zone_id"), rs.getString("device_code"), new Config(rs.getInt("version"),
        rs.getDouble("humidity_on"), rs.getDouble("humidity_off"), rs.getDouble("temp_min"),
        rs.getDouble("temp_max"), 1000 * rs.getInt("telemetry_interval_sec"))));
  }

  public boolean beginAttempt(Pending p) {
    return jdbc.update("""
        UPDATE swiftletcare.device_config_sync
        SET last_attempt_at = clock_timestamp()
        WHERE device_id = ? AND zone_id = ? AND desired_version = ? AND status = 'PENDING'
          AND (last_attempt_at IS NULL OR last_attempt_at <= now() - interval '5 seconds')
        """, p.deviceId(), p.zoneId(), p.config().version()) == 1;
  }

  public void accept(long houseId, long zoneId, String deviceCode, Ack ack) {
    if (!enabled || ack.version() == null || ack.version() < 1 || ack.status() == null
        || !deviceCode.equals(ack.device_id())) return;
    String status = switch (ack.status()) {
      case "APPLIED", "REJECTED", "STALE" -> ack.status();
      // Safe only with the documented firmware that compares the actual values.
      case "ALREADY_CURRENT" -> "SAME_VERSION".equals(ack.reason()) ? "APPLIED" : null;
      default -> null;
    };
    if (status == null) return;
    jdbc.update("""
        UPDATE swiftletcare.device_config_sync AS s
        SET status = ?, applied_version = CASE WHEN ? = 'APPLIED' THEN s.desired_version
                ELSE s.applied_version END, ack_at = clock_timestamp()
        FROM swiftletcare.devices d
        JOIN swiftletcare.zones z ON z.id = d.zone_id
        JOIN swiftletcare.houses h ON h.id = z.house_id
        JOIN swiftletcare.zone_configs c ON c.zone_id = z.id
        WHERE s.device_id = d.id AND s.zone_id = z.id AND h.id = ? AND z.id = ?
          AND d.device_id = ? AND d.type = 'ESP32' AND d.enabled AND z.active AND h.active
          AND s.status = 'PENDING' AND s.last_attempt_at IS NOT NULL
          AND s.desired_version = ? AND c.version = s.desired_version AND
        """ + COMPATIBLE, status, status, houseId, zoneId, deviceCode, ack.version());
  }
}
