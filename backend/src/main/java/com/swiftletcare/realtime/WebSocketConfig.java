
package com.swiftletcare.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final Realtime deviceRealtime;
  private final HouseAlertRealtime alertRealtime;
  private final String origins;

  public WebSocketConfig(
      Realtime deviceRealtime,
      HouseAlertRealtime alertRealtime,
      @Value("${app.origins}") String origins
  ) {
    this.deviceRealtime = deviceRealtime;
    this.alertRealtime = alertRealtime;
    this.origins = origins;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(deviceRealtime, "/ws/devices/*")
        .setAllowedOrigins(origins.split(","));

    registry.addHandler(alertRealtime, "/ws/houses/*")
        .setAllowedOrigins(origins.split(","));
  }
}
