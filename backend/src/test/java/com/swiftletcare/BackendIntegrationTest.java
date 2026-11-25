package com.swiftletcare;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManagerFactory;

@EnabledIfEnvironmentVariable(named = "DB_TEST_ENABLED", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {
      "app.jwt-secret=build104-test-secret-at-least-32-bytes",
      "app.ai-service-key=build104-test-service-key-at-least-24",
      "app.mqtt.enabled=false"
    })
class BackendIntegrationTest {
  @LocalServerPort int port;
  @Autowired Flyway flyway;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManagerFactory entityManagerFactory;

  @Test
  void migrationCreatesSchemaAndCanBeRepeated() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.schemata where schema_name ="
                    + " 'swiftletcare'",
                Integer.class))
        .isEqualTo(1);
    assertThat(flyway.info().current().getVersion().toString()).isEqualTo("11");
    assertThat(jdbc.queryForObject("""
        select to_regclass('swiftletcare.idx_count_logs_device_period_id') is not null
        """, Boolean.class)).isTrue();
    assertThat(jdbc.queryForObject("""
        select exists (
          select 1 from information_schema.columns
          where table_schema='swiftletcare' and table_name='devices'
            and column_name='camera_source_ok' and data_type='boolean'
        )
        """, Boolean.class)).isTrue();
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.tables"
            + " where table_schema='swiftletcare'"
            + " and table_type='BASE TABLE'",
        Integer.class
    )).isEqualTo(21);
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.tables where table_schema='swiftletcare' and table_type='BASE TABLE'",
        Integer.class)).isEqualTo(21);
    assertThat(jdbc.queryForObject("""
        select exists (
          select 1 from information_schema.tables
          where table_schema = 'swiftletcare' and table_name = 'device_config_sync'
        )
        """, Boolean.class)).isTrue();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(entityManagerFactory.isOpen()).isTrue();
  }

  @Test
  void healthIncludesWorkingDatabaseAndProbes() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      for (String path :
          new String[] {
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"
          }) {
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(response.body()).doesNotContain("jdbc:postgresql", "password");
        assertThat(jdbc.queryForObject("""
                                        SELECT EXISTS (
                                            SELECT 1 FROM information_schema.tables
                                            WHERE table_schema = 'swiftletcare'
                                              AND table_name = 'device_config_sync'
                                        )
                                        """, Boolean.class)).isTrue();
      }
    }
  }
}
