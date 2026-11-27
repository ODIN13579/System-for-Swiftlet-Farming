package com.swiftletcare.management;

public interface DeviceRepository
    extends org.springframework.data.jpa.repository.JpaRepository<Device, Long> {
  java.util.Optional<Device> findByDeviceId(String deviceId);
}
