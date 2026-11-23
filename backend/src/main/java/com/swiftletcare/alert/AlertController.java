
package com.swiftletcare.alert;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.api.Views;
import com.swiftletcare.realtime.HouseAlertRealtime;
import com.swiftletcare.security.Access;
import com.swiftletcare.security.Access.Permission;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

  public enum Status {
    NEW, IN_PROGRESS, RESOLVED
  }

  public enum Level {
    WARNING, CRITICAL
  }

  public record Update(Status status, String note) {}

  public record Alert(
      long id,
      long houseId,
      long zoneId,
      Long deviceId,
      Long anomalyEventId,
      String type,
      String level,
      Status status,
      String message,
      Instant createdAt,
      Instant handledAt,
      Instant clearedAt
  ) {}

  public record History(
      long id,
      Long changedBy,
      Status fromStatus,
      Status toStatus,
      Instant changedAt,
      String note
  ) {}

  private static final String ALERT_FROM =
      " from swiftletcare.alerts a"
      + " join swiftletcare.zones z on z.id=a.zone_id";

  private static final RowMapper<Alert> MAPPER = (rs, index) -> {
    Timestamp handled = rs.getTimestamp("handled_at");
    Timestamp cleared = rs.getTimestamp("cleared_at");
    return new Alert(
        rs.getLong("id"),
        rs.getLong("house_id"),
        rs.getLong("zone_id"),
        rs.getObject("device_id", Long.class),
        rs.getObject("anomaly_event_id", Long.class),
        rs.getString("type"),
        rs.getString("level"),
        Status.valueOf(rs.getString("status")),
        rs.getString("message"),
        rs.getTimestamp("created_at").toInstant(),
        handled == null ? null : handled.toInstant(),
        cleared == null ? null : cleared.toInstant()
    );
  };

  private final JdbcTemplate db;
  private final Access access;
  private final HouseAlertRealtime realtime;

  public AlertController(
      JdbcTemplate db,
      Access access,
      HouseAlertRealtime realtime
  ) {
    this.db = db;
    this.access = access;
    this.realtime = realtime;
  }

  @GetMapping
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Views.ListView<Alert> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam long houseId,
      @RequestParam(required = false) Long zoneId,
      @RequestParam(required = false) Status status,
      @RequestParam(required = false) Level level,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    access.requireHouse(jwt, houseId, Permission.READ);

    if (page < 0 || size < 1 || size > 100) {
      throw ApiError.bad("Invalid pagination");
    }

    String scope = ALERT_FROM + " where z.house_id=? and z.active";

    List<Object> args = new ArrayList<>();
    args.add(houseId);

    if (zoneId != null) {
      boolean belongs = Boolean.TRUE.equals(db.queryForObject(
          "select exists(select 1 from swiftletcare.zones"
              + " where id=? and house_id=? and active)",
          Boolean.class, zoneId, houseId
      ));

      if (!belongs) {
        throw new ApiError.Failure(
            403, "ACCESS_DENIED", "Zone outside requested House"
        );
      }

      scope += " and z.id=?";
      args.add(zoneId);
    }

    if (status != null) {
      scope += " and a.status=?";
      args.add(status.name());
    }

    if (level != null) {
      scope += " and a.level=?";
      args.add(level.name());
    }

    long count = db.queryForObject(
        "select count(*)" + scope,
        Long.class,
        args.toArray()
    );

    args.add(size);
    args.add((long) page * size);

    var items = db.query(
        "select a.*,z.house_id" + scope
            + " order by a.created_at desc,a.id desc"
            + " limit ? offset ?",
        MAPPER,
        args.toArray()
    );

    return new Views.ListView<>(
        items,
        new Views.PageInfo(
            page, size, count,
            Math.toIntExact(Math.ceilDiv(count, size))
        )
    );
  }

  private Alert lookup(long id, boolean lock) {
    String sql =
        "select a.*,z.house_id" + ALERT_FROM
            + " where a.id=?"
            + (lock ? " for update of a" : "");

    return db.query(sql, MAPPER, id)
        .stream()
        .findFirst()
        .orElseThrow(ApiError::missing);
  }

  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  public Alert detail(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long id
  ) {
    Alert alert = lookup(id, false);
    access.requireZone(jwt, alert.zoneId(), Permission.READ);
    return alert;
  }

  @GetMapping("/{id}/history")
  @Transactional(readOnly = true)
  public List<History> history(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long id
  ) {
    Alert alert = lookup(id, false);
    access.requireZone(jwt, alert.zoneId(), Permission.READ);

    return db.query("""
        select id, changed_by, from_status, to_status,
               change_at, note
        from swiftletcare.alert_histories
        where alert_id=?
        order by change_at asc, id asc
        """,
        (rs, index) -> {
          String previous = rs.getString("from_status");
          return new History(
              rs.getLong("id"),
              rs.getObject("changed_by", Long.class),
              previous == null ? null : Status.valueOf(previous),
              Status.valueOf(rs.getString("to_status")),
              rs.getTimestamp("change_at").toInstant(),
              rs.getString("note")
          );
        },
        id
    );
  }

  @PatchMapping("/{id}/status")
  @Transactional
  public Alert update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long id,
      @RequestBody Update change
  ) {
    if (change == null || change.status() == null) {
      throw ApiError.bad("Missing status");
    }

    if (change.note() != null && change.note().length() > 1000) {
      throw ApiError.bad("Note is too long");
    }

    Alert current = lookup(id, true);
    access.requireZone(jwt, current.zoneId(), Permission.WRITE);

    if (current.status() == change.status()) {
      return current;
    }

    boolean allowedTransition =
        (current.status() == Status.NEW
            && change.status() == Status.IN_PROGRESS)
        ||
        (current.status() == Status.IN_PROGRESS
            && change.status() == Status.RESOLVED);

    if (!allowedTransition) {
      throw new ApiError.Failure(
          409,
          "INVALID_ALERT_TRANSITION",
          "Allowed workflow: NEW -> IN_PROGRESS -> RESOLVED"
      );
    }

    db.update(
        "update swiftletcare.alerts"
            + " set status=?,"
            + " handled_at=case when ?='RESOLVED'"
            + " then current_timestamp else null end"
            + " where id=?",
        change.status().name(),
        change.status().name(),
        id
    );

    db.update(
        "insert into swiftletcare.alert_histories"
            + " (alert_id,changed_by,from_status,to_status,note)"
            + " values(?,?,?,?,?)",
        id,
        Long.parseLong(jwt.getSubject()),
        current.status().name(),
        change.status().name(),
        change.note()
    );

    Alert updated = lookup(id, false);

    // Only publish committed changes to WebSocket clients.
    realtime.publishAfterCommit(
        updated.houseId(), "alert.updated", updated
    );

    return updated;
  }
}
