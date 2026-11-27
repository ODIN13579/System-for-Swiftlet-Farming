package com.swiftletcare.api;

import com.swiftletcare.iot.*;
import com.swiftletcare.management.*;
import com.swiftletcare.security.Access;
import java.time.Instant;
import org.springframework.data.domain.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DeviceController {
  private final DeviceRepository devices;
  private final TelemetryRepository telemetry;
  private final Access access;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;

  public DeviceController(
      DeviceRepository devices,
      TelemetryRepository telemetry,
      Access access,
      org.springframework.jdbc.core.JdbcTemplate jdbc) {
    this.devices = devices;
    this.telemetry = telemetry;
    this.access = access;
    this.jdbc = jdbc;
  }

  private void check(Jwt jwt, long id) {
    if (!devices.existsById(id)) throw ApiError.missing();
    access.require(jwt, id);
  }

  public static PageRequest page(int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw ApiError.bad("page >= 0 and size 1..100 required");
    return PageRequest.of(
        page,
        size,
        Sort.by(
            Sort.Order.desc("timestamp"), Sort.Order.desc("receivedAt"), Sort.Order.desc("id")));
  }

  @GetMapping("/devices/{id}")
  public Views.DeviceView device(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
    check(jwt, id);
    return Views.DeviceView.of(devices.findById(id).orElseThrow(ApiError::missing));
  }

  @GetMapping("/zones/{zoneId}/devices")
  public Views.ListView<Views.DeviceView> devices(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long zoneId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    page(page, size);
    access.requireZone(jwt, zoneId, Access.Permission.READ);
    var ids =
        jdbc.queryForList(
            "select id from swiftletcare.devices where zone_id=? order by id limit ? offset ?",
            Long.class,
            zoneId,
            size,
            (long) page * size);
    long total =
        jdbc.queryForObject(
            "select count(*) from swiftletcare.devices where zone_id=?", Long.class, zoneId);
    return new Views.ListView<>(
        ids.stream()
            .map(i -> Views.DeviceView.of(devices.findById(i).orElseThrow(ApiError::missing)))
            .toList(),
        new Views.PageInfo(page, size, total, (int) ((total + size - 1) / size)));
  }

  @GetMapping("/devices/{id}/telemetry/latest")
  public Views.TelemetryView latest(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
    check(jwt, id);
    return Views.TelemetryView.of(
        telemetry
            .findFirstByDeviceIdOrderByTimestampDescReceivedAtDescIdDesc(id)
            .orElseThrow(ApiError::missing));
  }

  @GetMapping("/devices/{id}/telemetry")
  public Views.ListView<Views.TelemetryView> history(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long id,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    check(jwt, id);
    if (!from.isBefore(to)) throw ApiError.bad("from must precede to");
    return Views.ListView.of(
        telemetry
            .findByDeviceIdAndTimestampGreaterThanEqualAndTimestampLessThan(
                id, from, to, page(page, size))
            .map(Views.TelemetryView::of));
  }
}
