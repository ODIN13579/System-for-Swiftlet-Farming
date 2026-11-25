package com.swiftletcare.alert;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.realtime.HouseAlertRealtime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Service
public class CameraHeartbeatService {
  private static final Logger log = LoggerFactory.getLogger(CameraHeartbeatService.class);
  private static final int TIMEOUT_SECONDS = 30;
  private static final String ACTIVE_CAMERAS = """
      from swiftletcare.devices d
      join swiftletcare.zones z on z.id=d.zone_id
      join swiftletcare.houses h on h.id=z.house_id
      where d.type='CAMERA' and d.enabled and z.active and h.active
      """;

  public record Heartbeat(@NotNull @Positive Long camera_id, @NotNull Boolean source_ok) {}

  private record Camera(
      long id, String code, long houseId, long zoneId, boolean online, Boolean sourceOk) {}

  private record CameraStatus(
      long houseId, long zoneId, long cameraId, boolean online, Boolean sourceOk, Instant lastSeenAt) {}

  private final JdbcTemplate db;
  private final TransactionTemplate transactions;
  private final AlertEngine alerts;
  private final HouseAlertRealtime realtime;

  public CameraHeartbeatService(
      JdbcTemplate db, PlatformTransactionManager manager, AlertEngine alerts,
      HouseAlertRealtime realtime) {
    this.db = db;
    this.transactions = new TransactionTemplate(manager);
    this.alerts = alerts;
    this.realtime = realtime;
  }

  @Transactional
  public void receive(Heartbeat heartbeat) {
    var camera = lockCamera(heartbeat.camera_id())
        .orElseThrow(() -> ApiError.bad("Active CAMERA required"));
    Boolean sourceOk = heartbeat.source_ok();
    Instant receivedAt = db.queryForObject("""
        update swiftletcare.devices
        set last_seen_at=clock_timestamp(), camera_source_ok=?, online=true
        where id=?
        returning last_seen_at
        """, Timestamp.class, sourceOk, camera.id()).toInstant();

    // Transport reachability and the AI's ability to read the camera source are separate.
    if (!camera.online()) {
      alerts.cameraRecovered(camera.houseId(), camera.id());
    }
    if (!camera.online() || !Objects.equals(camera.sourceOk(), sourceOk)) {
      publishStatus(camera, true, sourceOk, receivedAt);
    }
  }

  public void scanOffline() {
    var overdue = db.queryForList("select d.id " + ACTIVE_CAMERAS + """
        and d.online and d.last_seen_at < statement_timestamp() - (? * interval '1 second')
        order by d.last_seen_at
        """, Long.class, TIMEOUT_SECONDS);

    for (long id : overdue) {
      try {
        transactions.executeWithoutResult(status -> lockCamera(id).ifPresent(this::expire));
      } catch (RuntimeException e) {
        log.error("Could not expire camera heartbeat for device {}", id, e);
      }
    }
  }

  private Optional<Camera> lockCamera(long id) {
    return db.query("""
        select d.id, d.device_id, z.house_id, d.zone_id, d.online, d.camera_source_ok
        """ + ACTIVE_CAMERAS + """
        and d.id=?
        for share of h for update of z,d
        """, (rs, row) -> new Camera(
        rs.getLong("id"), rs.getString("device_id"), rs.getLong("house_id"),
        rs.getLong("zone_id"), rs.getBoolean("online"), rs.getObject("camera_source_ok", Boolean.class)
    ), id).stream().findFirst();
  }

  private void expire(Camera camera) {
    // Recheck the deadline after acquiring the same lock used by heartbeat ingestion.
    var expired = db.queryForList("""
        update swiftletcare.devices
        set online=false
        where id=? and online and last_seen_at < clock_timestamp() - (? * interval '1 second')
        returning last_seen_at
        """, Timestamp.class, camera.id(), TIMEOUT_SECONDS);
    if (!expired.isEmpty()) {
      alerts.cameraOffline(camera.houseId(), camera.zoneId(), camera.id(), camera.code());
      publishStatus(camera, false, camera.sourceOk(), expired.getFirst().toInstant());
    }
  }

  private void publishStatus(Camera camera, boolean online, Boolean sourceOk, Instant lastSeenAt) {
    realtime.publishAfterCommit(camera.houseId(), "camera.status.changed",
        new CameraStatus(camera.houseId(), camera.zoneId(), camera.id(), online, sourceOk, lastSeenAt));
  }
}
