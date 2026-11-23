
package com.swiftletcare;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.swiftletcare.alert.AlertEngine;
import com.swiftletcare.iot.DeviceEvents;
import com.swiftletcare.iot.Ingestion;

@EnabledIfEnvironmentVariable(
    named = "DB_TEST_ENABLED", matches = "true"
)
@SpringBootTest(
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {
      "app.jwt-secret=build104-test-secret-at-least-32-bytes",
      "app.ai-service-key=build104-test-service-key-at-least-24",
      "app.mqtt.enabled=false"
    }
)
class AlertEngineIntegrationTest {

  @Autowired JdbcTemplate db;
  @Autowired Ingestion ingestion;
  @Autowired DeviceEvents events;
  @Autowired AlertEngine engine;

  long house;
  long zone;
  long device;
  String deviceCode;
  int sequence;

  long insert(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  @BeforeEach
  void fixture() {
    house = insert(
        "insert into swiftletcare.houses(name,active)"
            + " values('B3 engine test',true) returning id"
    );

    zone = insert(
        "insert into swiftletcare.zones(house_id,name,active)"
            + " values(?,'B3 zone',true) returning id",
        house
    );

    deviceCode = "B3-ESP-" + UUID.randomUUID();

    device = insert(
        "insert into swiftletcare.devices"
            + "(zone_id,device_id,name,type,enabled)"
            + " values(?,?,'B3 ESP','ESP32',true) returning id",
        zone, deviceCode
    );
  }

  void config() {
    db.update("""
        insert into swiftletcare.zone_configs
            (zone_id,humidity_on,humidity_off,
             temp_min,temp_max,temp_hysteresis)
        values(?,70,75,20,31,1)
        """, zone);
  }

  void send(double humidity) {
    ingestion.store(
        house,
        zone,
        new Ingestion.Payload(
            "b3-" + (++sequence) + "-" + UUID.randomUUID(),
            deviceCode,
            Instant.now(),
            28.0,
            humidity,
            500.0,
            100.0,
            "OK"
        )
    );
  }

  int count(String type) {
    return db.queryForObject(
        "select count(*) from swiftletcare.alerts"
            + " where zone_id=? and type=?",
        Integer.class, zone, type
    );
  }

  boolean cleared(String type) {
    return Boolean.TRUE.equals(db.queryForObject(
        "select cleared_at is not null"
            + " from swiftletcare.alerts"
            + " where zone_id=? and type=?"
            + " order by id desc limit 1",
        Boolean.class, zone, type
    ));
  }

  @Test
  void environmentalAlertRequiresThreeConsecutiveSamples() {
    config();

    send(60);
    send(60);

    assertThat(count("ENV_THRESHOLD")).isZero();

    send(60);

    assertThat(count("ENV_THRESHOLD")).isEqualTo(1);

    // More violations must not spam duplicate alerts.
    send(60);
    send(60);

    assertThat(count("ENV_THRESHOLD")).isEqualTo(1);

    send(80);
    send(80);
    send(80);

    assertThat(cleared("ENV_THRESHOLD")).isTrue();
  }

  @Test
  void sensorFaultCreatesAlertAndRecoversAfterThreeNewSamples() {
    events.accept(
        house, zone,
        new DeviceEvents.Event(
            deviceCode,
            Instant.now(),
            "SENSOR_FAULT",
            "DHT22",
            "INVALID_OR_NAN_READING"
        )
    );

    assertThat(count("SENSOR_FAULT")).isEqualTo(1);
    assertThat(cleared("SENSOR_FAULT")).isFalse();

    // A duplicate fault must not create another alert.
    events.accept(
        house, zone,
        new DeviceEvents.Event(
            deviceCode,
            Instant.now(),
            "SENSOR_FAULT",
            "DHT22",
            "INVALID_OR_NAN_READING"
        )
    );

    assertThat(count("SENSOR_FAULT")).isEqualTo(1);

    send(80);
    send(80);

    assertThat(cleared("SENSOR_FAULT")).isFalse();

    send(80);

    assertThat(cleared("SENSOR_FAULT")).isTrue();
  }

  @Test
  void offlineRequiresNineSecondsAndRecoversOnHeartbeat() {
    db.update("""
        update swiftletcare.devices
        set online=false,
            last_seen_at=clock_timestamp()-interval '8 seconds'
        where id=?
        """, device);

    engine.scanOffline();

    assertThat(count("DEVICE_OFFLINE")).isZero();

    db.update("""
        update swiftletcare.devices
        set online=false,
            last_seen_at=clock_timestamp()-interval '11 seconds'
        where id=?
        """, device);

    engine.scanOffline();

    assertThat(count("DEVICE_OFFLINE")).isEqualTo(1);

    ingestion.heartbeat(
        house, zone,
        new Ingestion.Heartbeat(deviceCode, Instant.now())
    );

    assertThat(cleared("DEVICE_OFFLINE")).isTrue();
  }

  @Test
  void manualOutOfRangeRequiresSustainedCondition() {
    config();

    db.update("""
        insert into swiftletcare.actuators
            (device_id,kind,mode)
        values(?,'FAN','MANUAL')
        """, device);

    send(60);

    assertThat(count("MANUAL_OUT_OF_RANGE")).isZero();

    // Simulate a sustained condition using the test DB clock.
    db.update("""
        update swiftletcare.alert_manual_conditions
        set first_seen_at=clock_timestamp()-interval '6 minutes'
        where device_id=?
        """, device);

    send(60);

    assertThat(count("MANUAL_OUT_OF_RANGE")).isEqualTo(1);
    assertThat(cleared("MANUAL_OUT_OF_RANGE")).isFalse();

    send(80);

    assertThat(cleared("MANUAL_OUT_OF_RANGE")).isTrue();
  }
}
