
package com.swiftletcare;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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


    private void sendMetrics(
        String code, double humidity, double co2, double light
    ) {
        ingestion.store(
            house, zone,
            new Ingestion.Payload(
                "acceptance-" + UUID.randomUUID(),
                code,
                Instant.now(),
                28.0, humidity, co2, light, "OK"
            )
        );
    }

    private long secondDevice(String code) {
        return insert(
            "insert into swiftletcare.devices"
            + "(zone_id,device_id,name,type,enabled)"
            + " values(?,?,'Second ESP','ESP32',true)"
            + " returning id",
            zone, code
        );
    }

    @Test
    void co2AndLightThresholdsTriggerAndRecover() {
        db.update("""
            insert into swiftletcare.zone_configs
                (zone_id,co2_max,light_max)
            values(?,1200,300)
            """, zone);

        // Only CO2 exceeds its configured threshold.
        for (int i = 0; i < 3; i++)
            sendMetrics(deviceCode, 80, 1500, 100);

        assertThat(count("ENV_THRESHOLD")).isEqualTo(1);

        // Three safe samples clear the alert.
        for (int i = 0; i < 3; i++)
            sendMetrics(deviceCode, 80, 500, 100);

        assertThat(cleared("ENV_THRESHOLD")).isTrue();

        // Only light exceeds its threshold: reopen same alert.
        for (int i = 0; i < 3; i++)
            sendMetrics(deviceCode, 80, 500, 500);

        assertThat(count("ENV_THRESHOLD")).isEqualTo(1);
        assertThat(cleared("ENV_THRESHOLD")).isFalse();
    }

    @Test
    void zoneAlertClearsOnlyAfterBothDevicesRecover() {
        config();

        String otherCode = "B3-SECOND-" + UUID.randomUUID();
        secondDevice(otherCode);

        for (int i = 0; i < 3; i++)
            sendMetrics(deviceCode, 60, 500, 100);

        assertThat(count("ENV_THRESHOLD")).isEqualTo(1);

        // Second device is healthy, first is still unhealthy.
        for (int i = 0; i < 3; i++)
            sendMetrics(otherCode, 80, 500, 100);

        assertThat(cleared("ENV_THRESHOLD")).isFalse();

        // Both devices have three healthy samples.
        for (int i = 0; i < 3; i++)
            sendMetrics(deviceCode, 80, 500, 100);

        assertThat(cleared("ENV_THRESHOLD")).isTrue();
    }

    @Test
    void dedupSuppressesNewAlertWithinFiveMinutes() {
        config();

        for (int i = 0; i < 3; i++)
            send(60);

        assertThat(count("ENV_THRESHOLD")).isEqualTo(1);

        long originalId = db.queryForObject(
            "select id from swiftletcare.alerts"
            + " where zone_id=? and type='ENV_THRESHOLD'",
            Long.class, zone
        );

        // Simulate a handled alert for dedup testing.
        db.update("""
            update swiftletcare.alerts
            set status='RESOLVED',
                handled_at=clock_timestamp()
            where id=?
            """, originalId);

        send(60);
        assertThat(count("ENV_THRESHOLD")).isEqualTo(1);

        // Move the original beyond the 5-minute dedup window.
        db.update("""
            update swiftletcare.alerts
            set created_at=clock_timestamp()-interval '6 minutes'
            where id=?
            """, originalId);

        send(60);

        assertThat(count("ENV_THRESHOLD")).isEqualTo(2);
    }

    @Test
    void repeatedFaultRequiresThreeNewOkSamples() {
        events.accept(house, zone, new DeviceEvents.Event(
            deviceCode, Instant.now(), "SENSOR_FAULT",
            "DHT22", "INITIAL_FAULT"
        ));

        send(80);
        send(80);

        // Fault happens again during recovery.
        events.accept(house, zone, new DeviceEvents.Event(
            deviceCode, Instant.now(), "SENSOR_FAULT",
            "DHT22", "FAULT_AGAIN"
        ));

        send(80);
        assertThat(cleared("SENSOR_FAULT")).isFalse();

        send(80);
        send(80);

        assertThat(count("SENSOR_FAULT")).isEqualTo(1);
        assertThat(cleared("SENSOR_FAULT")).isTrue();
    }

    @Test
    void concurrentTelemetryCreatesOnlyOneZoneAlert()
        throws Exception {
        config();
        send(60);
        send(60);

        var executor = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);

        try {
            var tasks = new java.util.ArrayList<
                java.util.concurrent.Future<?>>();

            for (int i = 0; i < 4; i++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    sendMetrics(deviceCode, 60, 500, 100);
                    return null;
                }));
            }

            start.countDown();

            for (var task : tasks)
                task.get(20, TimeUnit.SECONDS);

            assertThat(count("ENV_THRESHOLD")).isEqualTo(1);

            long histories = db.queryForObject("""
                select count(*)
                from swiftletcare.alert_histories h
                join swiftletcare.alerts a on a.id=h.alert_id
                where a.zone_id=?
                and a.type='ENV_THRESHOLD'
                and h.from_status is null
                and h.to_status='NEW'
                """, Long.class, zone);

            assertThat(histories).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void offlineAlertClearsOnlyAfterAllDevicesRecover() {
        String otherCode = "B3-OFFLINE-" + UUID.randomUUID();
        secondDevice(otherCode);

        db.update("""
            update swiftletcare.devices
            set online=false,
                last_seen_at=clock_timestamp()-interval '11 seconds'
            where zone_id=? and type='ESP32'
            """, zone);

        engine.scanOffline();

        assertThat(count("DEVICE_OFFLINE")).isEqualTo(1);

        ingestion.heartbeat(
            house, zone,
            new Ingestion.Heartbeat(deviceCode, Instant.now())
        );

        assertThat(cleared("DEVICE_OFFLINE")).isFalse();

        ingestion.heartbeat(
            house, zone,
            new Ingestion.Heartbeat(otherCode, Instant.now())
        );

        assertThat(cleared("DEVICE_OFFLINE")).isTrue();
    }

}
