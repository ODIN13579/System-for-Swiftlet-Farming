package com.swiftletcare.ai;

import com.swiftletcare.api.*;
import com.swiftletcare.security.Access;
import java.time.Instant;
import org.springframework.data.domain.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class CountReadController {
  private final Access access;
  private final CountLogRepository counts;

  public CountReadController(Access access, CountLogRepository counts) {
    this.access=access; this.counts=counts;
  }

  /** Raw windows, never a sum of potentially overlapping HOURLY and DAILY records. */
  @GetMapping("/api/v1/devices/{deviceId}/count-logs")
  public Views.ListView<Views.CountView> history(@AuthenticationPrincipal Jwt jwt,
      @PathVariable long deviceId, @RequestParam Instant from, @RequestParam Instant to,
      @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
    access.requireDevice(jwt,deviceId,Access.Permission.READ);
    DeviceController.page(page,size);
    if(!from.isBefore(to)) throw ApiError.bad("from must precede to");
    var paging=PageRequest.of(page,size,Sort.by(Sort.Order.desc("periodStart"),Sort.Order.desc("id")));
    return Views.ListView.of(counts.findByDeviceIdAndPeriodStartGreaterThanEqualAndPeriodStartLessThan(
        deviceId,from,to,paging).map(Views.CountView::of));
  }
}
