package com.swiftletcare.iot;

public interface TelemetryRepository
    extends org.springframework.data.jpa.repository.JpaRepository<Telemetry, Long> {
  java.util.Optional<Telemetry> findFirstByDeviceIdOrderByTimestampDescReceivedAtDescIdDesc(
      Long deviceId);

  org.springframework.data.domain.Page<Telemetry>
      findByDeviceIdAndTimestampGreaterThanEqualAndTimestampLessThan(
          Long deviceId,
          java.time.Instant from,
          java.time.Instant to,
          org.springframework.data.domain.Pageable page);
}
