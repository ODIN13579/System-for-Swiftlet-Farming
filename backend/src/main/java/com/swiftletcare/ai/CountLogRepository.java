package com.swiftletcare.ai;

public interface CountLogRepository
    extends org.springframework.data.jpa.repository.JpaRepository<CountLog, Long> {
  org.springframework.data.domain.Page<CountLog> findByDeviceIdAndPeriodStartGreaterThanEqualAndPeriodStartLessThan(
      long deviceId, java.time.Instant from, java.time.Instant to, org.springframework.data.domain.Pageable page);
}
