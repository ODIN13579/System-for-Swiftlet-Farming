package com.swiftletcare;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class SwiftletCareApplication {
  public static void main(String[] args) {
    // JDBC sends the JVM timezone during connection startup, before Hibernate settings apply.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(SwiftletCareApplication.class, args);
  }
}
