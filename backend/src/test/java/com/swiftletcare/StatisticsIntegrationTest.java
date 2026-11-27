package com.swiftletcare;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@EnabledIfEnvironmentVariable(named = "DB_TEST_ENABLED", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.jwt-secret=build3-test-secret-at-least-32-bytes",
        "app.ai-service-key=build3-test-service-key-at-least-24", "app.mqtt.enabled=false"})
class StatisticsIntegrationTest {
  private static final String FROM = "2026-07-21T17:00:00Z";
  private static final String TO = "2026-07-23T17:00:00Z";
  @Autowired JdbcTemplate db;
  @Autowired JwtEncoder encoder;
  @LocalServerPort int port;
  private final HttpClient http = HttpClient.newHttpClient();
  private long zone, house, sensor, camera;
  private String viewer;

  @BeforeEach void seedScope() {
    house = insert("insert into swiftletcare.houses(name,active) values('Report test',true) returning id");
    zone = insert("insert into swiftletcare.zones(house_id,name,active) values(?,'Report zone',true) returning id", house);
    sensor = device("ESP32");
    camera = device("CAMERA");
    viewer = token(house);
  }

  @Test void aggregatesAcrossVietnamMidnightAndExportsDatabaseValues() throws Exception {
    sample("2026-07-22T16:10:00Z", 20);
    sample("2026-07-22T16:50:00Z", 30);
    sample("2026-07-22T17:00:00Z", 40);
    sample(TO, 60);
    var json = get("/reports/environment", "&sensor=TEMPERATURE", viewer);
    assertThat(json.statusCode()).isEqualTo(200);
    assertThat(json.body()).contains("2026-07-22T00:00:00+07:00", "2026-07-23T00:00:00+07:00",
        "\"avgTemperature\":25.0", "\"minTemperature\":20.0", "\"maxTemperature\":30.0")
        .doesNotContain("avgHumidity");
    var hourly = get("/reports/environment", "&granularity=HOURLY&sensor=TEMPERATURE", viewer);
    assertThat(hourly.body()).contains("2026-07-22T23:00:00+07:00");
    var csv = get("/reports/environment.csv", "&sensor=TEMPERATURE", viewer);
    assertThat(csv.statusCode()).isEqualTo(200);
    assertThat(csv.body()).startsWith("timestamp_utc_plus_7,sample_count,avg_temperature_celsius,min_temperature_celsius,max_temperature_celsius\n")
        .contains("2026-07-22T00:00+07:00,2,25.0,20.0,30.0");
    var oracle = db.queryForObject("""
        select avg(temperature) from swiftletcare.telemetry
        where device_id=? and timestamp>=? and timestamp<?
        """, Double.class, sensor, timestamp(FROM), timestamp("2026-07-22T17:00:00Z"));
    assertThat(csv.body().split("\n")[1].split(",")[2]).isEqualTo(oracle.toString());
    String evidence = System.getenv("P3_CSV_EVIDENCE");
    if (evidence != null) {
      java.nio.file.Files.writeString(java.nio.file.Path.of(evidence), csv.body(), java.nio.file.StandardOpenOption.CREATE_NEW);
    }
  }

  @Test void deniesForeignHouseAndRetainsExistingHistoryPagination() throws Exception {
    long foreign = insert("insert into swiftletcare.houses(name,active) values('Other report house',true) returning id");
    var foreignViewer = token(foreign);
    for (String path : new String[]{"/reports/environment", "/reports/environment.csv",
        "/reports/bird-count", "/reports/bird-count.csv"}) {
      assertThat(get(path, "", foreignViewer).statusCode()).isEqualTo(403);
    }
    assertThat(get("/reports/environment", "", viewer).body()).contains("\"points\":[]");
    assertThat(get("/reports/environment.csv", "", viewer).body().lines()).hasSize(1);
    sample(FROM, 20);
    sample("2026-07-22T17:00:00Z", 40);
    var page = get("/devices/" + sensor + "/telemetry", "&page=1&size=1", viewer);
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("\"temperature\":20.0", "\"totalElements\":2").doesNotContain("\"temperature\":40.0");
  }

  @Test void doesNotMixRepresentationsForTheSameCameraDay() throws Exception {
    count("DAILY", FROM, "2026-07-22T17:00:00Z", 12);
    count("HOURLY", FROM, "2026-07-21T18:00:00Z", 3);
    count("HOURLY", "2026-07-21T17:15:00Z", "2026-07-21T18:15:00Z", 99);
    count("HOURLY", "2026-07-22T17:00:00Z", "2026-07-22T18:00:00Z", 5);
    assertThat(get("/reports/bird-count", "&granularity=HOURLY", viewer).body()).contains("\"entryCount\":8");
    assertThat(get("/reports/bird-count", "&granularity=DAILY", viewer).body()).contains("\"entryCount\":17");
    assertThat(get("/reports/bird-count", "&granularity=WEEKLY", viewer).body()).contains("\"entryCount\":17");
    assertThat(get("/reports/bird-count.csv", "&granularity=DAILY", viewer).body())
        .contains("2026-07-22T00:00+07:00,12,2,14", "2026-07-23T00:00+07:00,5,2,7");
  }

  private HttpResponse<String> get(String path, String extra, String auth) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path
        + "?from=" + FROM + "&to=" + TO + "&zoneId=" + zone + extra))
        .header("Authorization", "Bearer " + auth).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private long insert(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }

  private long device(String type) {
    return insert("insert into swiftletcare.devices(zone_id,device_id,name,type,enabled) values(?,?,'Report test',?,true) returning id",
        zone, UUID.randomUUID().toString(), type);
  }

  private String token(long membership) {
    long user = insert("insert into swiftletcare.users(email,password_hash,role,active) values(?,'unused','OWNER',true) returning id",
        UUID.randomUUID() + "@test.invalid");
    db.update("insert into swiftletcare.house_memberships(user_id,house_id,role,active,joined_at) values(?,?,'VIEWER',true,now())",
        user, membership);
    Instant now = Instant.now();
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("swiftletcare").subject(Long.toString(user)).issuedAt(now)
            .expiresAt(now.plusSeconds(600)).build())).getTokenValue();
  }

  private void sample(String instant, double temperature) {
    db.update("insert into swiftletcare.telemetry(device_id,event_id,timestamp,received_at,temperature,sensor_status) values(?,?,?,now(),?,'PARTIAL_ERROR')",
        sensor, UUID.randomUUID().toString(), timestamp(instant), temperature);
  }

  private void count(String type, String from, String to, int entries) {
    db.update("insert into swiftletcare.count_logs(device_id,period_start,period_end,entry_count,exit_count,detect_count,aggregation_type,processed_at) values(?,?,?,?,2,?,?,?)",
        camera, timestamp(from), timestamp(to), entries, entries + 2, type, timestamp(to));
  }

  private static Timestamp timestamp(String iso) { return Timestamp.from(Instant.parse(iso)); }
}
