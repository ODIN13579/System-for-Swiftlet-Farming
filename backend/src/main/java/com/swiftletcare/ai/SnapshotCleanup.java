package com.swiftletcare.ai;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Counts cleanup candidates without altering snapshot data or its references. */
@Service
public class SnapshotCleanup {
  private static final Logger log = LoggerFactory.getLogger(SnapshotCleanup.class);

  public record Report(Instant cutoff, long scanned, long linked, long eligible, long processed, int errors) {}

  private final JdbcTemplate db;
  private final Duration scheduledRetention;

  public SnapshotCleanup(
      JdbcTemplate db,
      @Value("${app.snapshot.cleanup.retention:}") String retention
  ) {
    this.db = db;
    this.scheduledRetention = retention.isBlank() ? null : positive(Duration.parse(retention));
  }

  @Scheduled(cron = "${app.snapshot.cleanup.cron:-}", zone = "UTC")
  void scheduledDryRun() {
    if (scheduledRetention == null) {
      log.warn("Snapshot cleanup dry-run skipped: no retention configured");
      return;
    }
    dryRun(Instant.now(), scheduledRetention);
  }

  public Report dryRun(Instant asOf, Duration retention) {
    Instant cutoff = asOf.minus(positive(retention));
    try {
      var report = db.queryForObject("""
          select count(*) as scanned, count(*) filter (where linked) as linked
          from (
              select exists (
                  select 1 from swiftletcare.anomaly_events e where e.snapshot_id=s.id
              ) or exists (
                  select 1 from swiftletcare.alerts a where a.snapshot_id=s.id
              ) as linked
              from swiftletcare.ai_snapshots s
              where s.created_at < ?
          ) candidates
          """, (rs, row) -> {
        long scanned = rs.getLong("scanned");
        long linked = rs.getLong("linked");
        return new Report(cutoff, scanned, linked, scanned - linked, 0, 0);
      }, Timestamp.from(cutoff));
      log.info("Snapshot cleanup dry-run cutoff={} scanned={} linked={} eligible={} processed={} errors={}",
          cutoff, report.scanned(), report.linked(), report.eligible(), report.processed(), report.errors());
      return report;
    } catch (DataAccessException e) {
      log.warn("Snapshot cleanup dry-run failed cutoff={} scanned=0 eligible=0 processed=0 errors=1 cause={}",
          cutoff, e.getClass().getSimpleName());
      return new Report(cutoff, 0, 0, 0, 0, 1);
    }
  }

  private static Duration positive(Duration retention) {
    if (retention.isNegative() || retention.isZero()) {
      throw new IllegalArgumentException("Snapshot retention must be positive");
    }
    return retention;
  }
}
