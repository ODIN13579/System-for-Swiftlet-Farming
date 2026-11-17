package com.swiftletcare.iot;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.security.Access;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlCommands {
  private static final Set<String> KINDS = Set.of("FAN", "MIST", "HEATER", "AMPLIFIER");

  public record Request(String action, Boolean targetState, String targetMode) {
    void validate() {
      boolean valid = "SET_MODE".equals(action) && modeValid(targetMode) && targetState == null
          || "SET_STATE".equals(action) && targetState != null && targetMode == null;
      if (!valid) throw ApiError.bad("Send SET_MODE with targetMode or SET_STATE with targetState");
    }
  }

  record DeviceTarget(long id, String code, boolean eligible) {}

  record Pending(String commandId, String topic, Map<String, Object> payload) {}

  record Ack(String device_id, String command_id, String actuator, String status, String reason,
      String originalStatus, String originalReason, String mode, String state, String gpio_state,
      Boolean safetyOverride, String timestamp) {}

  record State(String device_id, String mode, String actuator, String state, String gpio_state,
      Map<String, String> actuators, Boolean safetyOverride, String timestamp, String boot_id) {}

  private static final String ONLINE = """
      d.enabled and d.type='ESP32' and z.active and h.active and d.online
      and d.last_seen_at > clock_timestamp() - (? * interval '1 millisecond')
      """;

  private final JdbcTemplate jdbc;
  private final Access access;
  private final boolean enabled;
  private final long heartbeatMs;

  public ControlCommands(JdbcTemplate jdbc, Access access,
      @Value("${app.mqtt.control-commands-enabled:false}") boolean enabled,
      @Value("${app.heartbeat-timeout-ms:6000}") long heartbeatMs) {
    this.jdbc = jdbc;
    this.access = access;
    this.enabled = enabled;
    this.heartbeatMs = heartbeatMs;
  }

  boolean enabled() {
    return enabled;
  }

  static ApiError.Failure unavailable() {
    return new ApiError.Failure(503, "BROKER_UNAVAILABLE", "MQTT control is disabled or disconnected");
  }

  @Transactional
  public ControlReadController.CommandView create(Jwt jwt, long actuatorId, Request request,
      boolean brokerReady) {
    request.validate();
    var targets = jdbc.query("""
        select d.id,d.device_id,(%s) as eligible
        from swiftletcare.actuators a join swiftletcare.devices d on d.id=a.device_id
        join swiftletcare.zones z on z.id=d.zone_id join swiftletcare.houses h on h.id=z.house_id
        where a.id=? for share of h,z for update of d
        """.formatted(ONLINE), (rs, n) -> new DeviceTarget(rs.getLong("id"), rs.getString("device_id"),
            rs.getBoolean("eligible")), heartbeatMs, actuatorId);
    if (targets.isEmpty()) throw ApiError.missing();
    var device = targets.getFirst();
    access.requireDevice(jwt, device.id(), Access.Permission.WRITE);
    long user = access.requireActiveAccount(jwt);
    if (!enabled || !brokerReady) throw unavailable();
    if (!device.eligible()) throw conflict("DEVICE_OFFLINE", "ESP32 is offline or disabled");
    if (device.code().isBlank() || device.code().chars().anyMatch(c -> c == '/' || c == '+' || c == '#'))
      throw conflict("INVALID_DEVICE_CODE", "Device code cannot be used in an MQTT topic");

    boolean busy = Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(select 1 from swiftletcare.control_commands c
        join swiftletcare.actuators a on a.id=c.actuator_id
        where a.device_id=? and c.expires_at>clock_timestamp()
          and (c.status in ('PENDING','SENT')
            or (c.status='FAILED' and c.failure_reason in ('ACK_TIMEOUT','PUBLISH_FAILED'))))
        """, Boolean.class, device.id()));
    if (busy) throw conflict("COMMAND_IN_PROGRESS", "Wait for the current ESP32 command");

    if ("SET_STATE".equals(request.action())) {
      var actuator = jdbc.queryForMap("""
          select kind,mode,safety_override,coalesce(state_received_at >
            clock_timestamp()-interval '8 seconds',false) as fresh
          from swiftletcare.actuators where id=? and device_id=?
          """, actuatorId, device.id());
      if (!Boolean.TRUE.equals(actuator.get("fresh")))
        throw conflict("ACTUATOR_STATE_UNKNOWN", "Wait for a recent ESP32 state report");
      if (!"MANUAL".equals(actuator.get("mode")))
        throw conflict("ACTUATOR_NOT_IN_MANUAL_MODE", "Switch ESP32 to MANUAL first");
      if ("FAN".equals(actuator.get("kind")) && Boolean.FALSE.equals(request.targetState())
          && !Boolean.FALSE.equals(actuator.get("safety_override")))
        throw conflict("SAFETY_OVERRIDE", "ESP32 safety override prevents turning the fan off");
    }

    return jdbc.queryForObject("""
        insert into swiftletcare.control_commands
          (actuator_id,requested_by,command_id,action,target_state,target_mode,expires_at)
        values (?,?,?,?,?,?,date_trunc('second',clock_timestamp())+interval '15 seconds')
        returning *
        """, ControlReadController::readCommand, actuatorId, user, UUID.randomUUID().toString(),
        request.action(), request.targetState(), request.targetMode());
  }

  List<String> pending() {
    return jdbc.queryForList("""
        select command_id from swiftletcare.control_commands
        where status='PENDING' and expires_at>clock_timestamp() order by id limit 32
        """, String.class);
  }

  @Transactional
  public Pending claim(String commandId) {
    // Lock in the same order as create/state so lifecycle and state changes serialize.
    var targets = jdbc.query("""
        select h.id as house_id,z.id as zone_id,d.device_id,
          (%s) as eligible,a.kind,c.action,c.target_state,c.target_mode,c.expires_at
        from swiftletcare.control_commands c join swiftletcare.actuators a on a.id=c.actuator_id
        join swiftletcare.devices d on d.id=a.device_id join swiftletcare.zones z on z.id=d.zone_id
        join swiftletcare.houses h on h.id=z.house_id
        where c.command_id=? and c.status='PENDING' for share of h,z for update of d
        """.formatted(ONLINE), (rs, n) -> {
      if (!rs.getBoolean("eligible")) return null;
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("command_id", commandId);
      payload.put("action", rs.getString("action"));
      payload.put("actuator", rs.getString("kind"));
      payload.put("expiresAt", rs.getTimestamp("expires_at").toInstant().toString());
      if ("SET_MODE".equals(rs.getString("action"))) payload.put("targetMode", rs.getString("target_mode"));
      else payload.put("targetState", rs.getBoolean("target_state") ? "ON" : "OFF");
      String prefix = "swiftletcare/" + rs.getLong("house_id") + "/" + rs.getLong("zone_id")
          + "/" + rs.getString("device_id");
      return new Pending(commandId, prefix + "/command", payload);
    }, heartbeatMs, commandId);
    if (targets.isEmpty()) return null;
    if (targets.getFirst() == null) {
      jdbc.update("""
          update swiftletcare.control_commands set status='FAILED',failure_reason='DEVICE_OFFLINE'
          where command_id=? and status='PENDING' and expires_at>clock_timestamp()
          """, commandId);
      return null;
    }
    int changed = jdbc.update("""
        update swiftletcare.control_commands set status='SENT',sent_at=clock_timestamp()
        where command_id=? and status='PENDING' and expires_at>clock_timestamp()
        """, commandId);
    return changed == 1 ? targets.getFirst() : null;
  }

  void publishFailed(String commandId) {
    jdbc.update("""
        update swiftletcare.control_commands set status='FAILED',failure_reason='PUBLISH_FAILED'
        where command_id=? and status='SENT'
        """, commandId);
  }

  @Scheduled(fixedDelay = 500)
  public void expire() {
    if (!enabled) return;
    jdbc.update("""
        update swiftletcare.control_commands
        set status=case when status='PENDING' then 'EXPIRED' else 'FAILED' end,
            failure_reason=case when status='PENDING' then 'COMMAND_EXPIRED' else 'ACK_TIMEOUT' end
        where (status='PENDING' and expires_at<=clock_timestamp())
           or (status='SENT' and least(expires_at,sent_at+interval '10 seconds')<=clock_timestamp())
        """);
  }

  void acceptAck(long house, long zone, String code, Ack ack) {
    if (!code.equals(ack.device_id()) || !uuidValid(ack.command_id())
        || !kindValid(ack.actuator())) return;
    boolean duplicate = "DUPLICATE".equals(ack.status());
    String result = duplicate ? ack.originalStatus() : ack.status();
    if (!"APPLIED".equals(result) && !"REJECTED".equals(result)) return;
    String reason = duplicate ? ack.originalReason() : ack.reason();
    if (reason == null || reason.isBlank() || reason.length() > 80) return;
    boolean applied = "APPLIED".equals(result);
    if (applied && !duplicate && (!modeValid(ack.mode()) || !stateValid(ack.state())
        || !(("ON".equals(ack.state()) ? "HIGH" : "LOW").equals(ack.gpio_state())))) return;
    jdbc.update("""
        update swiftletcare.control_commands c set status=?,ack_at=clock_timestamp(),failure_reason=?
        from swiftletcare.actuators a join swiftletcare.devices d on d.id=a.device_id
        join swiftletcare.zones z on z.id=d.zone_id join swiftletcare.houses h on h.id=z.house_id
        where c.actuator_id=a.id and c.command_id=? and c.status='SENT'
          and least(c.expires_at,c.sent_at+interval '10 seconds')>clock_timestamp()
          and d.device_id=? and z.id=? and h.id=? and a.kind=?
          and d.enabled and d.type='ESP32' and z.active and h.active
          and (not ? or (c.action='SET_MODE' and c.target_mode=?)
            or (c.action='SET_STATE' and c.target_state=? and ?='MANUAL'))
        """, applied ? "ACKNOWLEDGED" : "REJECTED", applied ? null : reason,
        ack.command_id(), code, zone, house, ack.actuator(), applied && !duplicate,
        ack.mode(), "ON".equals(ack.state()), ack.mode());
  }

  @Transactional
  public void acceptState(long house, long zone, String code, State report) {
    if (!code.equals(report.device_id()) || !modeValid(report.mode())
        || report.safetyOverride() == null) return;
    Map<String, String> states = report.actuators();
    if (states == null) {
      if ((report.actuator() != null && !"FAN".equals(report.actuator()))
          || !stateValid(report.state())) return;
      states = Map.of("FAN", report.state());
    }
    if (states.isEmpty() || states.entrySet().stream()
        .anyMatch(e -> !kindValid(e.getKey()) || !stateValid(e.getValue()))) return;
    var devices = jdbc.queryForList("""
        select d.id from swiftletcare.devices d join swiftletcare.zones z on z.id=d.zone_id
        join swiftletcare.houses h on h.id=z.house_id where d.device_id=? and z.id=? and h.id=?
        and d.enabled and d.type='ESP32' and z.active and h.active for share of h,z for update of d
        """, Long.class, code, zone, house);
    if (devices.isEmpty()) return;
    long device = devices.getFirst();
    // Mode is shared by the ESP32, even when an older firmware only reports FAN.
    jdbc.update("""
        update swiftletcare.actuators set mode=?,manual_until=null,safety_override=?
        where device_id=? and (mode is distinct from ? or safety_override is distinct from ?)
        """, report.mode(), report.safetyOverride(), device, report.mode(), report.safetyOverride());
    for (var state : states.entrySet()) {
      jdbc.update("""
          update swiftletcare.actuators set "on"=?,state_received_at=clock_timestamp()
          where device_id=? and kind=?
          """, "ON".equals(state.getValue()), device, state.getKey());
    }
  }

  private static boolean modeValid(String mode) {
    return "AUTO".equals(mode) || "MANUAL".equals(mode);
  }

  private static boolean kindValid(String kind) {
    return kind != null && KINDS.contains(kind);
  }

  private static boolean stateValid(String state) {
    return "ON".equals(state) || "OFF".equals(state);
  }

  private static boolean uuidValid(String id) {
    if (id == null || id.length() != 36) return false;
    try { return UUID.fromString(id).toString().equals(id); }
    catch (IllegalArgumentException e) { return false; }
  }

  private static ApiError.Failure conflict(String code, String message) {
    return new ApiError.Failure(409, code, message);
  }
}
