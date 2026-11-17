package com.swiftletcare.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final Realtime realtime;
  private final String origins;

  public WebSocketConfig(Realtime realtime, @Value("${app.origins}") String origins) {
    this.realtime = realtime;
    this.origins = origins;
  }

  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(realtime, "/ws/devices/*").setAllowedOrigins(origins.split(","));
  }
}
