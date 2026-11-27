
package com.swiftletcare.realtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.swiftletcare.security.Access;
import com.swiftletcare.security.Access.Permission;

import tools.jackson.databind.json.JsonMapper;

@Component
public class HouseAlertRealtime extends TextWebSocketHandler {

  private static final String PREFIX = "/ws/houses/";

  private final Map<String, WebSocketSession> sessions =
      new ConcurrentHashMap<>();

  private final Access access;
  private final JsonMapper json;

  public HouseAlertRealtime(Access access, JsonMapper json) {
    this.access = access;
    this.json = json;
  }

  private long houseId(WebSocketSession session) {
    var uri = session.getUri();

    if (uri == null || !uri.getPath().startsWith(PREFIX)) {
      throw new IllegalArgumentException("Invalid House WebSocket URL");
    }

    return Long.parseLong(uri.getPath().substring(PREFIX.length()));
  }

  private boolean allowed(WebSocketSession session) {
    try {
      return session.getPrincipal() instanceof JwtAuthenticationToken auth
          && access.canHouse(
              auth.getToken(),
              houseId(session),
              Permission.READ
          );
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session)
      throws Exception {

    if (!allowed(session)) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }

    sessions.put(
        session.getId(),
        new ConcurrentWebSocketSessionDecorator(session, 3000, 65536)
    );
  }

  @Override
  public void afterConnectionClosed(
      WebSocketSession session,
      CloseStatus status
  ) {
    sessions.remove(session.getId());
  }

  @Override
  public void handleTransportError(
      WebSocketSession session,
      Throwable error
  ) throws Exception {
    sessions.remove(session.getId());

    if (session.isOpen()) {
      session.close(CloseStatus.SERVER_ERROR);
    }
  }

  public void publish(long targetHouseId, String type, Object data) {
    final String payload;

    try {
      payload = json.writeValueAsString(
          Map.of("type", type, "data", data)
      );
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid alert event", e);
    }

    for (var entry : sessions.entrySet()) {
      var session = entry.getValue();

      try {
        if (!allowed(session)) {
          session.close(CloseStatus.POLICY_VIOLATION);
          sessions.remove(entry.getKey());
          continue;
        }

        if (houseId(session) != targetHouseId) {
          continue;
        }

        synchronized (session) {
          session.sendMessage(new TextMessage(payload));
        }
      } catch (Exception e) {
        sessions.remove(entry.getKey());

        try {
          session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
          // The session is already unusable.
        }
      }
    }
  }

  public void publishAfterCommit(
      long houseId,
      String type,
      Object data
  ) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {

      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              publish(houseId, type, data);
            }
          }
      );
    } else {
      publish(houseId, type, data);
    }
  }
}
