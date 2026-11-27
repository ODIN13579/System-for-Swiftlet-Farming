package com.swiftletcare.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("local")
public class LocalData {
  @Bean
  CommandLineRunner seed(
      JdbcTemplate jdbc,
      BCryptPasswordEncoder encoder,
      PlatformTransactionManager tm,
      @Value("${LOCAL_ADMIN_PASSWORD}") String password) {
    return args ->
        new TransactionTemplate(tm)
            .executeWithoutResult(
                s -> {
                  // Entire seed is opt-in; do not overwrite any existing installation.
                  // Reset password only when explicitly enabled for local development.
                if (Boolean.parseBoolean(System.getenv("LOCAL_RESET_PASSWORD"))) {
                jdbc.update(
                    "UPDATE swiftletcare.users SET password_hash = ? WHERE email = ?",
                    encoder.encode(password),
                    "local@example.test");
                return;
                }

                // Do not overwrite an existing installation.
                if (jdbc.queryForObject("select count(*) from swiftletcare.houses", Long.class)
                    != 0) return;
                  long house =
                      jdbc.queryForObject(
                          "insert into swiftletcare.houses(name,active) values('Local test"
                              + " house',true) returning id",
                          Long.class);
                  long zone =
                      jdbc.queryForObject(
                          "insert into swiftletcare.zones(house_id,name,active) values(?,'Local"
                              + " test zone',true) returning id",
                          Long.class,
                          house);
                  long user =
                      jdbc.queryForObject(
                          "insert into swiftletcare.users(email,password_hash,role,active)"
                              + " values('local@example.test',?,'OWNER',true) returning id",
                          Long.class,
                          encoder.encode(password));
                  jdbc.update(
                      "insert into"
                          + " swiftletcare.house_memberships(user_id,house_id,role,active,joined_at)"
                          + " values(?,?,'OWNER',true,now())",
                      user,
                      house);
                  jdbc.update(
                      "insert into swiftletcare.devices(zone_id,device_id,name,type,enabled)"
                          + " values(?,'ESP32-LOCAL','Local"
                          + " ESP32','ESP32',true),(?,'CAMERA-LOCAL','Local camera','CAMERA',true)",
                      zone,
                      zone);
                });
  }
}
