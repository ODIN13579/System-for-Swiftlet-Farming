package com.swiftletcare.alert;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.camera-heartbeat.scheduling-enabled", matchIfMissing = true)
class CameraHeartbeatScheduling {
  private final CameraHeartbeatService heartbeats;

  CameraHeartbeatScheduling(CameraHeartbeatService heartbeats) {
    this.heartbeats = heartbeats;
  }

  // Keep the camera deadline independent of ESP32, MQTT and other scheduled jobs.
  @Bean(defaultCandidate = false)
  static ThreadPoolTaskScheduler cameraHeartbeatScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setThreadNamePrefix("camera-heartbeat-");
    return scheduler;
  }

  @Scheduled(fixedDelay = 1000, scheduler = "cameraHeartbeatScheduler")
  void scanOffline() {
    heartbeats.scanOffline();
  }
}
