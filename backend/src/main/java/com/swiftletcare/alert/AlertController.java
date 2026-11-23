package com.swiftletcare.alert;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.api.Views;
import com.swiftletcare.security.Access;
import com.swiftletcare.security.Access.Permission;
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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
  public enum Status {
    NEW, IN_PROGRESS, RESOLVED
  }

  public record Update(Status status, String note) {}

  public record Alert(
      long id,
      long zoneId,
      Long anomalyEventId,
      String type,
      String level,
      Status status,
      String message,
      Instant createdAt,
      Instant handledAt
  ) {}

  private static final RowMapper<Alert> MAPPER = (row, index) -> {
    Timestamp handled = row.getTimestamp("handled_at");
    return new Alert(
        row.getLong("id"),
        row.getLong("zone_id"),
        row.getObject("anomaly_event_id", Long.class),
        row.getString("type"),
        row.getString("level"),
        Status.valueOf(row.getString("status")),
        row.getString("message"),
        row.getTimestamp("created_at").toInstant(),
        handled == null ? null : handled.toInstant()
    );
  };

  private final JdbcTemplate db;
  private final Access access;

  public AlertController(JdbcTemplate db, Access access) {
    this.db = db;
    this.access = access;
  }

  @GetMapping
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Views.ListView<Alert> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam long houseId,
      @RequestParam(required = false) Long zoneId,
      @RequestParam(required = false) Status status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    access.requireHouse(jwt, houseId, Permission.READ);

    if (page < 0 || size < 1 || size > 100) {
      throw ApiError.bad("Invalid pagination");
    }

    String scope = " from swiftletcare.alerts a"
        + " join swiftletcare.zones z on z.id=a.zone_id"
        + " where z.house_id=? and z.active";

    List<Object> args = new ArrayList<>();
    args.add(houseId);

    if (zoneId != null) {
      scope += " and z.id=?";
      args.add(zoneId);
    }
    if (status != null) {
      scope += " and a.status=?";
      args.add(status.name());
    }

    long count = db.queryForObject(
        "select count(*)" + scope, Long.class, args.toArray()
    );

    args.add(size);
    args.add((long) page * size);

    var items = db.query(
        "select a.*" + scope
            + " order by a.created_at desc, a.id desc limit ? offset ?",
        MAPPER,
        args.toArray()
    );

    var info = new Views.PageInfo(
        page, size, count, (int) Math.ceilDiv(count, size)
    );
    return new Views.ListView<>(items, info);
  }

  private Alert lookup(long id, boolean lock) {
    String sql = "select * from swiftletcare.alerts where id=?";
    if (lock) sql += " for update";

    return db.query(sql, MAPPER, id)
        .stream()
        .findFirst()
        .orElseThrow(ApiError::missing);
  }

  @PatchMapping("/{id}/status")
  @Transactional
  public Alert update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long id,
      @RequestBody Update change
  ) {
    if (change.status() == null) {
      throw ApiError.bad("Missing status");
    }

    Alert current = lookup(id, true);
    access.requireZone(jwt, current.zoneId(), Permission.WRITE);

    if (current.status() == change.status()) {
      return current;
    }

    String status = change.status().name();
    db.update(
        "update swiftletcare.alerts set status=?,"
            + " handled_at=case when ?='RESOLVED'"
            + " then current_timestamp else null end where id=?",
        status, status, id
    );

    db.update(
        "insert into swiftletcare.alert_histories"
            + "(alert_id,changed_by,from_status,to_status,note)"
            + " values(?,?,?,?,?)",
        id,
        Long.parseLong(jwt.getSubject()),
        current.status().name(),
        status,
        change.note()
    );

    return lookup(id, false);
  }
}
