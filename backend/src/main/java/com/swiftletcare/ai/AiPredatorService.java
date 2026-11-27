
package com.swiftletcare.ai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.realtime.HouseAlertRealtime;
import com.swiftletcare.security.Access;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Service
public class AiPredatorService {
  public static final int MAX_IMAGE_BYTES = 300 * 1024;

  public record EventInput(
      @NotBlank @Size(max = 150) String event_id,
      @NotNull @Positive Long camera_id,
      @NotBlank @Size(max = 100) String species,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0")
      Double confidence,
      @NotNull @Positive Long snapshot_id
  ) {}

  public record EventResult(
      long anomaly_event_id,
      String event_id,
      long camera_id,
      long snapshot_id,
      Long alert_id,
      boolean created
  ) {}

  public record SnapshotResult(
      long snapshot_id,
      long camera_id,
      int byte_size,
      String media_type
  ) {}

  public record SnapshotContent(
      String mediaType,
      byte[] bytes
  ) {}

  private record Camera(long id, long zoneId, long houseId) {}

  private record StoredEvent(
      long id, long cameraId, String species,
      double confidence, long snapshotId
  ) {}

  private record NewAlert(long id, Instant createdAt) {}

  private final JdbcTemplate db;
  private final Access access;
  private final HouseAlertRealtime realtime;

  public AiPredatorService(
      JdbcTemplate db,
      Access access,
      HouseAlertRealtime realtime
  ) {
    this.db = db;
    this.access = access;
    this.realtime = realtime;
  }

  private Camera activeCamera(long id) {
    var cameras = db.query("""
        select d.id, d.zone_id, z.house_id
        from swiftletcare.devices d
        join swiftletcare.zones z on z.id=d.zone_id
        join swiftletcare.houses h on h.id=z.house_id
        where d.id=? and d.type='CAMERA' and d.enabled
          and z.active and h.active
        for share of h for update of z,d
        """,
        (rs, row) -> new Camera(
            rs.getLong("id"),
            rs.getLong("zone_id"),
            rs.getLong("house_id")
        ), id);

    if (cameras.isEmpty()) {
      throw ApiError.bad("Active CAMERA required");
    }

    return cameras.getFirst();
  }

  private static void validateImage(
      String mediaType, byte[] bytes
  ) {
    if (bytes == null || bytes.length == 0) {
      throw ApiError.bad("Snapshot is empty");
    }
    if (bytes.length > MAX_IMAGE_BYTES) {
      throw new ApiError.Failure(
          413, "IMAGE_TOO_LARGE", "Snapshot exceeds 300 KiB"
      );
    }

    boolean png = "image/png".equals(mediaType)
        && bytes.length >= 8
        && Arrays.equals(
            Arrays.copyOf(bytes, 8),
            new byte[] {
                (byte) 137, 80, 78, 71, 13, 10, 26, 10
            }
        );

    boolean jpeg = "image/jpeg".equals(mediaType)
        && bytes.length >= 4
        && (bytes[0] & 0xff) == 0xff
        && (bytes[1] & 0xff) == 0xd8;

    if (!png && !jpeg) {
      throw ApiError.bad("Only valid JPEG or PNG is allowed");
    }

    // Validate decoded format and bounded dimensions.
    try (var stream = new MemoryCacheImageInputStream(
        new ByteArrayInputStream(bytes))) {

      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) {
        throw ApiError.bad("Unsupported image encoding");
      }

      var reader = readers.next();

      try {
        reader.setInput(stream, true, true);

        String actual = reader.getFormatName();
        if (png && !"png".equalsIgnoreCase(actual)
            || jpeg && !"jpeg".equalsIgnoreCase(actual)
                && !"jpg".equalsIgnoreCase(actual)) {
          throw ApiError.bad("Image content type mismatch");
        }

        int width = reader.getWidth(0);
        int height = reader.getHeight(0);

        if (width < 1 || height < 1
            || width > 4096 || height > 4096
            || (long) width * height > 4_000_000L) {
          throw ApiError.bad("Image dimensions too large");
        }

        if (reader.read(0) == null) {
          throw ApiError.bad("Image cannot be decoded");
        }
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw ApiError.bad("Corrupted image");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(bytes)
      );
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  
    @Transactional
    public SnapshotResult saveSnapshot(
        long cameraId,
        String mediaType,
        byte[] bytes
    ) {
        if (cameraId <= 0)
            throw ApiError.bad("Invalid camera_id");

        validateImage(mediaType, bytes);
        activeCamera(cameraId);

        String hash = sha256(bytes);

        var inserted = db.queryForList("""
            insert into swiftletcare.ai_snapshots
                (camera_id,media_type,image_bytes,byte_size,sha256)
            values(?,?,?,?,?)
            on conflict(camera_id,sha256) do nothing
            returning id
            """,
            Long.class,
            cameraId,
            mediaType,
            bytes,
            bytes.length,
            hash
        );

        long id = inserted.isEmpty()
            ? db.queryForObject("""
                select id from swiftletcare.ai_snapshots
                where camera_id=? and sha256=?
                """, Long.class, cameraId, hash)
            : inserted.getFirst();

        return new SnapshotResult(
            id, cameraId, bytes.length, mediaType
        );
    }


  @Transactional(readOnly = true)
  public SnapshotContent readSnapshot(
      long snapshotId, Jwt jwt
  ) {
    var cameraIds = db.queryForList("""
        select camera_id
        from swiftletcare.ai_snapshots
        where id=?
        """, Long.class, snapshotId);

    if (cameraIds.isEmpty()) throw ApiError.missing();

    access.requireDevice(
        jwt, cameraIds.getFirst(), Access.Permission.READ
    );

    return db.queryForObject("""
        select media_type,image_bytes
        from swiftletcare.ai_snapshots
        where id=? and camera_id=?
        """,
        (rs, row) -> new SnapshotContent(
            rs.getString("media_type"),
            rs.getBytes("image_bytes")
        ),
        snapshotId,
        cameraIds.getFirst()
    );
  }

  private static boolean validText(String text) {
    return text != null
        && !text.isBlank()
        && text.equals(text.trim())
        && text.codePoints().noneMatch(Character::isISOControl);
  }

  @Transactional
  public EventResult acceptEvent(EventInput p) {
    if (p == null || p.camera_id() == null
        || p.snapshot_id() == null
        || p.confidence() == null
        || !Double.isFinite(p.confidence())
        || p.confidence() < 0 || p.confidence() > 1
        || !validText(p.event_id())
        || !validText(p.species())
        || p.event_id().length() > 150
        || p.species().length() > 100) {
      throw ApiError.bad("Invalid AI event");
    }

    Camera camera = activeCamera(p.camera_id());

    var snapshotCamera = db.queryForList("""
        select camera_id
        from swiftletcare.ai_snapshots
        where id=?
        """, Long.class, p.snapshot_id());

    if (snapshotCamera.isEmpty()) {
      throw ApiError.missing();
    }

    if (!snapshotCamera.getFirst().equals(camera.id())) {
      throw ApiError.bad("Snapshot belongs to another camera");
    }

    // Unique event_id is the final concurrency-safe guard.
    var createdIds = db.queryForList("""
        insert into swiftletcare.anomaly_events
            (device_id,event_id,species,confidence,
             detected_at,snapshot_id)
        values(?,?,?,?,clock_timestamp(),?)
        on conflict(event_id) do nothing
        returning id
        """,
        Long.class,
        camera.id(),
        p.event_id(),
        p.species(),
        p.confidence(),
        p.snapshot_id()
    );

    boolean created = !createdIds.isEmpty();

    StoredEvent stored = db.queryForObject("""
        select id,device_id,species,confidence,snapshot_id
        from swiftletcare.anomaly_events
        where event_id=?
        """,
        (rs, row) -> new StoredEvent(
            rs.getLong("id"),
            rs.getLong("device_id"),
            rs.getString("species"),
            rs.getDouble("confidence"),
            rs.getLong("snapshot_id")
        ),
        p.event_id()
    );

    // Same key with different content must fail, not succeed silently.
    if (stored.cameraId() != camera.id()
        || stored.snapshotId() != p.snapshot_id()
        || !stored.species().equals(p.species())
        || Double.compare(
            stored.confidence(), p.confidence()
        ) != 0) {
      throw ApiError.conflict();
    }

    if (!created) {
      Long existingAlert = db.query("""
          select id from swiftletcare.alerts
          where anomaly_event_id=?
          order by id desc limit 1
          """, rs -> rs.next() ? rs.getLong(1) : null,
          stored.id());

      return new EventResult(
          stored.id(),
          p.event_id(),
          camera.id(),
          p.snapshot_id(),
          existingAlert,
          false
      );
    }

    // Serialize alert creation per Zone. Distinct AI events are
    // retained, but P41 suppresses extra Alert rows for five minutes.
    var alertIds = db.queryForList("""
        select id from swiftletcare.alerts
        where zone_id=? and type='PREDATOR'
          and (
              status <> 'RESOLVED'
              or created_at >
                  clock_timestamp() - interval '5 minutes'
          )
        order by created_at desc,id desc
        limit 1
        """, Long.class, camera.zoneId());

    Long alertId;

    if (!alertIds.isEmpty()) {
      alertId = alertIds.getFirst();
    } else {
      String message = "Predator detected: " + p.species();

      NewAlert alert = db.queryForObject("""
          insert into swiftletcare.alerts
            (zone_id,device_id,anomaly_event_id,
             snapshot_id,type,level,status,message)
          values(?,?,?,?,'PREDATOR','CRITICAL','NEW',?)
          returning id,created_at
          """,
          (rs, row) -> new NewAlert(
              rs.getLong("id"),
              rs.getTimestamp("created_at").toInstant()
          ),
          camera.zoneId(),
          camera.id(),
          stored.id(),
          p.snapshot_id(),
          message
      );

      alertId = alert.id();

      db.update("""
          insert into swiftletcare.alert_histories
              (alert_id,changed_by,from_status,to_status,note)
          values(?,null,null,'NEW',
                 'Created from AI predator event')
          """, alertId);

      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", alertId);
      payload.put("houseId", camera.houseId());
      payload.put("zoneId", camera.zoneId());
      payload.put("deviceId", camera.id());
      payload.put("anomalyEventId", stored.id());
      payload.put("snapshotId", p.snapshot_id());
      payload.put("type", "PREDATOR");
      payload.put("level", "CRITICAL");
      payload.put("status", "NEW");
      payload.put("message", message);
      payload.put("createdAt", alert.createdAt());
      payload.put("handledAt", null);
      payload.put("clearedAt", null);

      realtime.publishAfterCommit(
          camera.houseId(), "alert.created", payload
      );
    }

    return new EventResult(
        stored.id(),
        p.event_id(),
        camera.id(),
        p.snapshot_id(),
        alertId,
        true
    );
  }
}
