package com.swiftletcare.reporting;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.management.DeviceRepository;
import com.swiftletcare.security.Access;

/** Inspects full-day Entry evidence for one camera without choosing a flock baseline. */
@Service
public class DailyEntryAudit {
  private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final Duration HOUR = Duration.ofHours(1);

  public enum Coverage { MISSING, INCOMPLETE, INVALID, COMPLETE }

  public record Series(Coverage coverage, Long entryTotal) {}

  public record DayEvidence(
      LocalDate day, Instant start, Instant end, Series hourly, Series daily) {}

  private record CountWindow(
      String aggregation, Instant start, Instant end, Instant processedAt, int entries) {}

  private final Access access;
  private final DeviceRepository devices;
  private final JdbcTemplate db;

  public DailyEntryAudit(Access access, DeviceRepository devices, JdbcTemplate db) {
    this.access = access;
    this.devices = devices;
    this.db = db;
  }

  @Transactional(readOnly = true)
  public DayEvidence inspect(Jwt jwt, long cameraId, LocalDate day) {
    access.requireDevice(jwt, cameraId, Access.Permission.READ);
    devices.findById(cameraId).filter(device -> "CAMERA".equals(device.type))
        .orElseThrow(() -> ApiError.bad("CAMERA required"));

    Instant start = day.atStartOfDay(REPORTING_ZONE).toInstant();
    Instant end = day.plusDays(1).atStartOfDay(REPORTING_ZONE).toInstant();
    var windows = db.query("""
        select aggregation_type, period_start, period_end, processed_at, entry_count
        from swiftletcare.count_logs
        where device_id=? and period_start < ? and period_end > ?
        order by period_start, period_end
        """, (rs, row) -> new CountWindow(
        rs.getString("aggregation_type"), rs.getTimestamp("period_start").toInstant(),
        rs.getTimestamp("period_end").toInstant(), rs.getTimestamp("processed_at").toInstant(),
        rs.getInt("entry_count")
    ), cameraId, Timestamp.from(end), Timestamp.from(start));

    // Compare the coverage of the two series independently. Mixing them can count the same birds twice.
    var hourly = windows.stream().filter(window -> "HOURLY".equals(window.aggregation())).toList();
    var daily = windows.stream().filter(window -> "DAILY".equals(window.aggregation())).toList();
    return new DayEvidence(day, start, end, hourlyTotal(hourly, start, end), dailyTotal(daily, start, end));
  }

  private static Series hourlyTotal(List<CountWindow> windows, Instant start, Instant end) {
    if (windows.isEmpty()) return new Series(Coverage.MISSING, null);

    Instant cursor = start;
    boolean gap = false;
    long total = 0;
    for (var window : windows) {
      if (window.start().isBefore(cursor) || window.end().isAfter(end)
          || window.processedAt().isBefore(window.end())
          || !Duration.between(window.start(), window.end()).equals(HOUR)
          || Duration.between(start, window.start()).toNanos() % HOUR.toNanos() != 0) {
        return new Series(Coverage.INVALID, null);
      }
      gap |= !window.start().equals(cursor);
      cursor = window.end();
      total += window.entries();
    }
    return gap || !cursor.equals(end)
        ? new Series(Coverage.INCOMPLETE, null)
        : new Series(Coverage.COMPLETE, total);
  }

  private static Series dailyTotal(List<CountWindow> windows, Instant start, Instant end) {
    if (windows.isEmpty()) return new Series(Coverage.MISSING, null);
    var window = windows.getFirst();
    if (windows.size() != 1 || !window.start().equals(start) || !window.end().equals(end)
        || window.processedAt().isBefore(end)) {
      return new Series(Coverage.INVALID, null);
    }
    return new Series(Coverage.COMPLETE, (long) window.entries());
  }
}
