package com.swiftletcare.realtime;

import com.swiftletcare.security.Access;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

@Component
public class Realtime extends TextWebSocketHandler {
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final Access access;
  private final JsonMapper json;

  public Realtime(Access access, JsonMapper json) {
    this.access = access;
    this.json = json;
  }

  private long device(WebSocketSession s) {
    return Long.parseLong(s.getUri().getPath().substring("/ws/devices/".length()));
  }

  private boolean allowed(WebSocketSession s) {
    return s.getPrincipal() instanceof JwtAuthenticationToken a
        && access.canRead(a.getToken(), device(s));
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession s) throws Exception {
    try {
      System.out.println(
          "WS connected: id=" + s.getId()
          + ", uri=" + s.getUri()
          + ", principal=" + s.getPrincipal()
      );

      if (!allowed(s)) {
        System.out.println("WS rejected: not allowed");
        s.close(CloseStatus.POLICY_VIOLATION);
        return;
      }

      sessions.put(
          s.getId(),
          new org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator(
              s, 3000, 65536));

      System.out.println("WS session registered: " + s.getId());
    } catch (Exception e) {
      e.printStackTrace();
      s.close(CloseStatus.POLICY_VIOLATION);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    sessions.remove(s.getId());
  }

  public void publish(long device, String type, Object data) {
    System.out.println(
        "WS publish: device=" + device
        + ", type=" + type
        + ", sessions=" + sessions.size()
    );

    for (var s : sessions.values())
      try {
        if (!allowed(s)) {
          s.close(CloseStatus.POLICY_VIOLATION);
          sessions.remove(s.getId());
          continue;
        }
        if (device(s) == device)
          synchronized (s) {
            s.sendMessage(
                new TextMessage(json.writeValueAsString(Map.of("type", type, "data", data))));
          }
      } catch (Exception e) {
        sessions.remove(s.getId());
        try {
          s.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
        }
      }
  }
}
