package com.swiftletcare.reporting;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.security.Access;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class StatisticsController {
  private static final ZoneOffset UTC_PLUS_7 = ZoneOffset.ofHours(7);
  private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

  public enum Granularity {
    HOURLY("hour"), DAILY("day"), WEEKLY("week");
    final String unit;
    Granularity(String unit) { this.unit = unit; }
  }

  public enum Sensor {
    TEMPERATURE("temperature", "Temperature", "celsius"),
    HUMIDITY("humidity", "Humidity", "percent"),
    CO2("co2", "Co2", "ppm"),
    LIGHT("light", "Light", "lux");
    final String column;
    final String field;
    final String unit;
    Sensor(String column, String field, String unit) {
      this.column = column;
      this.field = field;
      this.unit = unit;
    }
  }

  public record CountPoint(OffsetDateTime timestamp, long entryCount, long exitCount, long detectCount) {}

  private final Access access;
  private final JdbcTemplate db;

  StatisticsController(Access access, JdbcTemplate db) {
    this.access = access;
    this.db = db;
  }

  private void check(Jwt jwt, long zoneId, Long houseId, Instant from, Instant to) {
    if (!from.isBefore(to)) throw ApiError.bad("from must precede to");
    access.requireZone(jwt, zoneId, Access.Permission.READ);
    if (houseId != null) {
      access.requireHouse(jwt, houseId, Access.Permission.READ);
      if (!Boolean.TRUE.equals(db.queryForObject(
          "select exists(select 1 from swiftletcare.zones where id=? and house_id=?)",
          Boolean.class, zoneId, houseId))) throw ApiError.bad("Zone does not belong to House");
    }
  }

  @GetMapping("/environment")
  public Map<String, Object> environment(@AuthenticationPrincipal Jwt jwt,
      @RequestParam long zoneId, @RequestParam(required = false) Long houseId,
      @RequestParam Instant from, @RequestParam Instant to,
      @RequestParam(defaultValue = "DAILY") Granularity granularity,
      @RequestParam(required = false) Sensor sensor) {
    check(jwt, zoneId, houseId, from, to);
    var sensors = selected(sensor);
    var response = window(zoneId, from, to, granularity);
    response.put("points", environmentPoints(zoneId, from, to, granularity, sensors));
    return response;
  }

  @GetMapping("/environment.csv")
  public ResponseEntity<String> environmentCsv(@AuthenticationPrincipal Jwt jwt,
      @RequestParam long zoneId, @RequestParam(required = false) Long houseId,
      @RequestParam Instant from, @RequestParam Instant to,
      @RequestParam(defaultValue = "DAILY") Granularity granularity,
      @RequestParam(required = false) Sensor sensor) {
    check(jwt, zoneId, houseId, from, to);
    var sensors = selected(sensor);
    var csv = new StringBuilder("timestamp_utc_plus_7,sample_count");
    for (Sensor item : sensors) {
      for (String operation : List.of("avg", "min", "max"))
        csv.append(',').append(operation).append('_').append(item.column).append('_').append(item.unit);
    }
    csv.append('\n');
    for (var point : environmentPoints(zoneId, from, to, granularity, sensors)) {
      csv.append(point.get("timestamp")).append(',').append(point.get("sampleCount"));
      for (Sensor item : sensors) {
        for (String operation : List.of("avg", "min", "max")) {
          Object value = point.get(operation + item.field);
          csv.append(',');
          if (value != null) csv.append(value);
        }
      }
      csv.append('\n');
    }
    return csv("environment", csv);
  }

  @GetMapping("/bird-count")
  public Map<String, Object> birdCount(@AuthenticationPrincipal Jwt jwt,
      @RequestParam long zoneId, @RequestParam(required = false) Long houseId,
      @RequestParam Instant from, @RequestParam Instant to,
      @RequestParam(defaultValue = "DAILY") Granularity granularity) {
    check(jwt, zoneId, houseId, from, to);
    var points = countPoints(zoneId, from, to, granularity);
    var response = window(zoneId, from, to, granularity);
    response.put("entryCount", points.stream().mapToLong(CountPoint::entryCount).sum());
    response.put("exitCount", points.stream().mapToLong(CountPoint::exitCount).sum());
    response.put("detectCount", points.stream().mapToLong(CountPoint::detectCount).sum());
    response.put("points", points);
    return response;
  }

  @GetMapping("/bird-count.csv")
  public ResponseEntity<String> birdCountCsv(@AuthenticationPrincipal Jwt jwt,
      @RequestParam long zoneId, @RequestParam(required = false) Long houseId,
      @RequestParam Instant from, @RequestParam Instant to,
      @RequestParam(defaultValue = "DAILY") Granularity granularity) {
    check(jwt, zoneId, houseId, from, to);
    var csv = new StringBuilder("timestamp_utc_plus_7,entry_count_birds,exit_count_birds,detect_count_birds\n");
    for (var point : countPoints(zoneId, from, to, granularity)) {
      csv.append(point.timestamp()).append(',').append(point.entryCount()).append(',')
          .append(point.exitCount()).append(',').append(point.detectCount()).append('\n');
    }
    return csv("bird-count", csv);
  }

  private static List<Sensor> selected(Sensor sensor) {
    return sensor == null ? List.of(Sensor.values()) : List.of(sensor);
  }

  private List<Map<String, Object>> environmentPoints(long zone, Instant from, Instant to,
      Granularity granularity, List<Sensor> sensors) {
    if (granularity == Granularity.WEEKLY) throw ApiError.bad("Telemetry supports HOURLY or DAILY");
    var fields = new StringBuilder();
    for (Sensor sensor : sensors) {
      for (String op : List.of("avg", "min", "max"))
        fields.append(", ").append(op).append("(t.").append(sensor.column).append(')');
    }
    String available = String.join(" or ", sensors.stream()
        .map(sensor -> "t." + sensor.column + " is not null").toList());
    return db.query("""
        select date_trunc(?, t.timestamp, 'Asia/Ho_Chi_Minh') as bucket, count(*)
        """ + fields + """
        from swiftletcare.telemetry t join swiftletcare.devices d on d.id=t.device_id
        where d.zone_id=? and t.timestamp>=? and t.timestamp<? and (
        """ + available + ") group by 1 order by 1", (rs, row) -> {
      Map<String, Object> point = new LinkedHashMap<>();
      point.put("timestamp", rs.getTimestamp(1).toInstant().atOffset(UTC_PLUS_7));
      point.put("sampleCount", rs.getLong(2));
      int column = 3;
      for (Sensor sensor : sensors) {
        for (String op : List.of("avg", "min", "max"))
          point.put(op + sensor.field, rs.getObject(column++));
      }
      return point;
    }, granularity.unit, zone, Timestamp.from(from), Timestamp.from(to));
  }

  private List<CountPoint> countPoints(long zone, Instant from, Instant to, Granularity granularity) {
    // Aligned source windows make exact keys sufficient to prevent overlapping windows.
    // For a complete stored daily window, prefer DAILY for that camera/day in coarse reports.
    return db.query("""
        with windows as (
          select c.*, date_trunc('day', c.period_start, 'Asia/Ho_Chi_Minh') as day
          from swiftletcare.count_logs c join swiftletcare.devices d on d.id=c.device_id
          where d.zone_id=? and d.type='CAMERA' and c.period_start>=? and c.period_end<=?
        ), daily as (
          select * from windows where aggregation_type='DAILY' and period_start=day
            and period_end=day + interval '24 hours' and ?
        ), chosen as (
          select * from daily
          union all
          select h.* from windows h where h.aggregation_type='HOURLY'
            and h.period_start=date_trunc('hour',h.period_start,'Asia/Ho_Chi_Minh')
            and h.period_end=h.period_start + interval '1 hour'
            and not exists (select 1 from daily d where d.device_id=h.device_id and d.day=h.day)
        )
        select date_trunc(?,period_start,'Asia/Ho_Chi_Minh'), sum(entry_count), sum(exit_count), sum(detect_count)
        from chosen group by 1 order by 1
        """, (rs, row) -> new CountPoint(rs.getTimestamp(1).toInstant().atOffset(UTC_PLUS_7),
        rs.getLong(2), rs.getLong(3), rs.getLong(4)),
        zone, Timestamp.from(from), Timestamp.from(to), granularity != Granularity.HOURLY, granularity.unit);
  }

  private static Map<String, Object> window(long zone, Instant from, Instant to, Granularity granularity) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("zoneId", zone);
    response.put("from", from.atOffset(UTC_PLUS_7));
    response.put("to", to.atOffset(UTC_PLUS_7));
    response.put("granularity", granularity);
    return response;
  }

  private static ResponseEntity<String> csv(String name, StringBuilder csv) {
    return ResponseEntity.ok().contentType(CSV).cacheControl(CacheControl.noStore())
        .header("Content-Disposition", "attachment; filename=\"" + name + ".csv\"")
        .body(csv.toString());
  }
}
