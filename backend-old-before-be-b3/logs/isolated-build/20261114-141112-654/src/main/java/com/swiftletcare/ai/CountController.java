package com.swiftletcare.ai;

import com.swiftletcare.api.*;
import com.swiftletcare.management.DeviceRepository;
import com.swiftletcare.realtime.Realtime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class CountController {
  public record Input(
      @NotNull @Positive Long deviceId,
      @NotNull Instant periodStart,
      @NotNull Instant periodEnd,
      @NotNull @Min(0) Integer entryCount,
      @NotNull @Min(0) Integer exitCount,
      @NotNull @Min(0) Integer detectCount,
      @NotNull @Pattern(regexp = "HOURLY|DAILY") String aggregationType,
      @NotNull Instant processedAt) {
    public Input {
      if (periodStart != null)
        periodStart = periodStart.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      if (periodEnd != null)
        periodEnd = periodEnd.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      if (processedAt != null)
        processedAt = processedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
  }

  private final String key;
  private final JdbcTemplate jdbc;
  private final DeviceRepository devices;
  private final CountLogRepository counts;
  private final TransactionTemplate tx;
  private final Realtime realtime;

  public CountController(
      @Value("${app.ai-service-key}") String key,
      JdbcTemplate jdbc,
      DeviceRepository devices,
      CountLogRepository counts,
      PlatformTransactionManager tm,
      Realtime realtime) {
    if (key.length() < 24)
      throw new IllegalArgumentException("AI_SERVICE_KEY needs at least 24 characters");
    this.key = key;
    this.jdbc = jdbc;
    this.devices = devices;
    this.counts = counts;
    this.tx = new TransactionTemplate(tm);
    this.realtime = realtime;
  }

  @PostMapping("/api/v1/internal/ai/count-logs")
  public ResponseEntity<Views.CountView> ingest(
      @RequestHeader(value = "X-Service-Key", defaultValue = "") String provided,
      @Valid @RequestBody Input p) {
    if (!MessageDigest.isEqual(
        key.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8)))
      throw new ApiError.Failure(401, "UNAUTHORIZED", "Invalid service credential");
    if (!p.periodStart().isBefore(p.periodEnd())
        || p.processedAt().isBefore(p.periodEnd())
        || p.processedAt().isAfter(Instant.now().plusSeconds(300)))
      throw ApiError.bad("Invalid count period/processedAt");
    boolean[] inserted = {false};
    var result =
        tx.execute(
            s -> {
              var d = devices.findById(p.deviceId()).orElseThrow(ApiError::missing);
              if (!d.enabled
                  || !d.type.equals("CAMERA")
                  || jdbc.queryForList(
                          "select d.id from swiftletcare.devices d join swiftletcare.zones z on z.id=d.zone_id"
                              + " join swiftletcare.houses h on h.id=z.house_id where d.id=? and d.enabled"
                              + " and d.type='CAMERA' and z.active and h.active for share of h,z for update of d",
                          Long.class,
                          d.id).isEmpty()) throw ApiError.bad("Enabled camera required");
              inserted[0] =
                  jdbc.update(
                          """
insert into swiftletcare.count_logs(device_id,period_start,period_end,entry_count,exit_count,detect_count,aggregation_type,processed_at)
values(?,?,?,?,?,?,?,?) on conflict(device_id,aggregation_type,period_start,period_end) do nothing
""",
                          p.deviceId(),
                          Timestamp.from(p.periodStart()),
                          Timestamp.from(p.periodEnd()),
                          p.entryCount(),
                          p.exitCount(),
                          p.detectCount(),
                          p.aggregationType(),
                          Timestamp.from(p.processedAt()))
                      == 1;
              var id =
                  jdbc.queryForObject(
                      "select id from swiftletcare.count_logs where device_id=? and"
                          + " aggregation_type=? and period_start=? and period_end=?",
                      Long.class,
                      p.deviceId(),
                      p.aggregationType(),
                      Timestamp.from(p.periodStart()),
                      Timestamp.from(p.periodEnd()));
              var c = counts.findById(id).orElseThrow(ApiError::missing);
              if (!c.entryCount.equals(p.entryCount())
                  || !c.exitCount.equals(p.exitCount())
                  || !c.detectCount.equals(p.detectCount())
                  || !c.processedAt.equals(
                      p.processedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
                throw ApiError.conflict();
              return Views.CountView.of(c);
            });
    if (inserted[0]) realtime.publish(result.deviceId(), "ai.count.updated", result);
    return ResponseEntity.status(inserted[0] ? 201 : 200).body(result);
  }
}
