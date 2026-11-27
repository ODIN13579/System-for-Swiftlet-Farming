package com.swiftletcare;

import static com.swiftletcare.reporting.DailyEntryAudit.Coverage.COMPLETE;
import static com.swiftletcare.reporting.DailyEntryAudit.Coverage.INCOMPLETE;
import static com.swiftletcare.reporting.DailyEntryAudit.Coverage.INVALID;
import static com.swiftletcare.reporting.DailyEntryAudit.Coverage.MISSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.reporting.DailyEntryAudit;
import com.swiftletcare.reporting.DailyEntryAudit.Series;

@EnabledIfEnvironmentVariable(named = "DB_TEST_ENABLED", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {
        "app.jwt-secret=build104-test-secret-at-least-32-bytes",
        "app.ai-service-key=build104-test-service-key-at-least-24",
        "app.mqtt.enabled=false"
    })
class DailyEntryAuditIntegrationTest {
  private static final LocalDate DAY = LocalDate.parse("2026-07-22");
  private static final Instant START = Instant.parse("2026-07-21T17:00:00Z");
  private static final Instant END = Instant.parse("2026-07-22T17:00:00Z");

  @Autowired DailyEntryAudit audit;
  @Autowired JdbcTemplate db;

  private long zone;
  private long camera;
  private Jwt viewer;

  @BeforeEach
  void fixture() {
    long house = house();
    zone = db.queryForObject("""
        insert into swiftletcare.zones(house_id,name,active)
        values(?,'B3-15 test',true) returning id
        """, Long.class, house);
    camera = camera();
    viewer = viewer(house);
  }

  @Test
  void usesVietnamDayBoundariesAndKeepsHourlyAndDailyTotalsSeparate() {
    for (Instant hour = START; hour.isBefore(END); hour = hour.plus(1, ChronoUnit.HOURS)) {
      count(camera, "HOURLY", hour, hour.plus(1, ChronoUnit.HOURS), 2);
    }
    count(camera, "DAILY", START, END, 39);
    count(camera, "HOURLY", START.minus(1, ChronoUnit.HOURS), START, 90);
    count(camera, "HOURLY", END, END.plus(1, ChronoUnit.HOURS), 90);
    count(camera(), "DAILY", START, END, 700);

    var result = audit.inspect(viewer, camera, DAY);
    assertThat(result.start()).isEqualTo(START);
    assertThat(result.end()).isEqualTo(END);
    assertThat(result.hourly()).isEqualTo(new Series(COMPLETE, 48L));
    assertThat(result.daily()).isEqualTo(new Series(COMPLETE, 39L));
  }

  @Test
  void distinguishesMissingHistoricalEvidenceAndPartialDataFromARecordedZero() {
    count(camera, "HOURLY", START, START.plus(1, ChronoUnit.HOURS), 0);
    count(camera, "DAILY", START, END, 0);

    var current = audit.inspect(viewer, camera, DAY);
    assertThat(current.hourly()).isEqualTo(new Series(INCOMPLETE, null));
    assertThat(current.daily()).isEqualTo(new Series(COMPLETE, 0L));

    var possibleBaseline = audit.inspect(viewer, camera, DAY.minusDays(1));
    assertThat(possibleBaseline.hourly()).isEqualTo(new Series(MISSING, null));
    assertThat(possibleBaseline.daily()).isEqualTo(new Series(MISSING, null));
  }

  @Test
  void rejectsOverlappingCrossDayAndPrematurelyProcessedWindows() {
    count(camera, "HOURLY", START, START.plus(1, ChronoUnit.HOURS), 1);
    Instant overlapping = START.plus(30, ChronoUnit.MINUTES);
    count(camera, "HOURLY", overlapping, overlapping.plus(1, ChronoUnit.HOURS), 4);
    count(camera, "DAILY", START.minus(1, ChronoUnit.HOURS), END.minus(1, ChronoUnit.HOURS), 60);
    var result = audit.inspect(viewer, camera, DAY);
    assertThat(result.hourly()).isEqualTo(new Series(INVALID, null));
    assertThat(result.daily()).isEqualTo(new Series(INVALID, null));

    long secondCamera = camera();
    count(secondCamera, "DAILY", START, END, 12, END.minusSeconds(1));
    assertThat(audit.inspect(viewer, secondCamera, DAY).daily()).isEqualTo(new Series(INVALID, null));
  }

  @Test
  void repeatedInspectionsDoNotCreateOrDuplicateFlockDropAlerts() {
    count(camera, "DAILY", START, END, 0);
    audit.inspect(viewer, camera, DAY);
    assertThat(flockAlerts()).isEmpty();

    long alertId = db.queryForObject("""
        insert into swiftletcare.alerts(zone_id,device_id,type,level,message)
        values(?,?,'FLOCK_DROP','WARNING','synthetic fixture') returning id
        """, Long.class, zone, camera);
    var first = audit.inspect(viewer, camera, DAY);
    assertThat(audit.inspect(viewer, camera, DAY)).isEqualTo(first);
    assertThat(flockAlerts()).containsExactly(alertId);
    assertThat(db.queryForObject("""
        select count(*) from swiftletcare.alert_histories where alert_id=?
        """, Long.class, alertId)).isZero();
  }

  @Test
  void requiresReadAccessToTheCamerasHouse() {
    count(camera, "DAILY", START, END, 17);
    assertThat(audit.inspect(viewer, camera, DAY).daily()).isEqualTo(new Series(COMPLETE, 17L));
    Jwt foreign = viewer(house());
    assertThatThrownBy(() -> audit.inspect(foreign, camera, DAY))
        .isInstanceOfSatisfying(ApiError.Failure.class, failure ->
            assertThat(failure.status).isEqualTo(403));
  }

  private java.util.List<Long> flockAlerts() {
    return db.queryForList("""
        select id from swiftletcare.alerts where zone_id=? and type='FLOCK_DROP'
        """, Long.class, zone);
  }

  private long house() {
    return db.queryForObject("""
        insert into swiftletcare.houses(name,active) values('B3-15 test',true) returning id
        """, Long.class);
  }

  private long camera() {
    return db.queryForObject("""
        insert into swiftletcare.devices(zone_id,device_id,name,type,enabled)
        values(?,?,'B3-15 camera','CAMERA',true) returning id
        """, Long.class, zone, "B315-" + UUID.randomUUID());
  }

  private Jwt viewer(long house) {
    long user = db.queryForObject("""
        insert into swiftletcare.users(email,password_hash,role,active)
        values(?,'unused','OWNER',true) returning id
        """, Long.class, UUID.randomUUID() + "@example.test");
    db.update("""
        insert into swiftletcare.house_memberships(user_id,house_id,role,active,joined_at)
        values(?,?,'VIEWER',true,now())
        """, user, house);
    return Jwt.withTokenValue("b315-test").header("alg", "HS256").subject(Long.toString(user))
        .expiresAt(Instant.now().plusSeconds(900)).build();
  }

  private void count(long cameraId, String type, Instant start, Instant end, int entries) {
    count(cameraId, type, start, end, entries, end);
  }

  private void count(long cameraId, String type, Instant start, Instant end, int entries,
      Instant processedAt) {
    db.update("""
        insert into swiftletcare.count_logs
          (device_id,period_start,period_end,entry_count,exit_count,detect_count,aggregation_type,processed_at)
        values(?,?,?,?,0,?, ?,?)
        """, cameraId, Timestamp.from(start), Timestamp.from(end), entries, entries,
        type, Timestamp.from(processedAt));
  }
}
