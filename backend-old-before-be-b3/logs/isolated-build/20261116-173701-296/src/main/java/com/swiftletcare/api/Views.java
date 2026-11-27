package com.swiftletcare.api;

import com.swiftletcare.ai.CountLog;
import com.swiftletcare.iot.Telemetry;
import com.swiftletcare.management.Device;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public class Views {
  public record DeviceView(
      Long id,
      String deviceId,
      Long zoneId,
      String name,
      String type,
      boolean enabled,
      boolean online,
      Instant lastSeenAt) {
    public static DeviceView of(Device d) {
      return new DeviceView(
          d.id, d.deviceId, d.zoneId, d.name, d.type, d.enabled, d.online, d.lastSeenAt);
    }
  }

  public record TelemetryView(
      Long id,
      Long deviceId,
      String eventId,
      Instant timestamp,
      Instant receivedAt,
      Double temperature,
      Double humidity,
      Double co2,
      Double light,
      String sensorStatus) {
    public static TelemetryView of(Telemetry t) {
      return new TelemetryView(
          t.id,
          t.deviceId,
          t.eventId,
          t.timestamp,
          t.receivedAt,
          t.temperature,
          t.humidity,
          t.co2,
          t.light,
          t.sensorStatus);
    }
  }

  public record CountView(
      Long id,
      Long deviceId,
      Instant periodStart,
      Instant periodEnd,
      Integer entryCount,
      Integer exitCount,
      Integer detectCount,
      String aggregationType,
      Instant processedAt) {
    public static CountView of(CountLog c) {
      return new CountView(
          c.id,
          c.deviceId,
          c.periodStart,
          c.periodEnd,
          c.entryCount,
          c.exitCount,
          c.detectCount,
          c.aggregationType,
          c.processedAt);
    }
  }

  public record PageInfo(int page, int size, long totalElements, int totalPages) {}

  public record ListView<T>(List<T> items, PageInfo page) {
    public static <T> ListView<T> of(Page<T> p) {
      return new ListView<>(
          p.getContent(),
          new PageInfo(p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages()));
    }
  }
}
