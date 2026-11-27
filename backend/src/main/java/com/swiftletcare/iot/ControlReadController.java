package com.swiftletcare.iot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.api.DeviceController;
import com.swiftletcare.api.Views;
import com.swiftletcare.security.Access;

/** Reads actuator state and command status. */
@RestController
@RequestMapping("/api/v1")
public class ControlReadController {
  public record ActuatorView(long id,long deviceId,String kind,String mode,boolean on,Instant manualUntil,
      Instant stateReceivedAt,boolean stateFresh,Boolean safetyOverride,boolean canControl) {}
  public record CommandView(long id,String commandId,long actuatorId,long requestedBy,String action,
      Boolean targetState,String targetMode,String status,Instant requestedAt,Instant sentAt,
      Instant ackAt,Instant expiresAt,String failureReason) {}
  private final JdbcTemplate jdbc;
  private final Access access;

  public ControlReadController(JdbcTemplate jdbc, Access access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  @GetMapping("/devices/{deviceId}/actuators")
  public Views.ListView<ActuatorView> actuators(@AuthenticationPrincipal Jwt jwt,@PathVariable long deviceId,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    DeviceController.page(page,size);
    access.requireDevice(jwt,deviceId,Access.Permission.READ);
    long total=jdbc.queryForObject("select count(*) from swiftletcare.actuators where device_id=?",Long.class,deviceId);
    boolean canControl=access.canDevice(jwt,deviceId,Access.Permission.WRITE);
    var rows=jdbc.query("""
        select *, coalesce(state_received_at > clock_timestamp()-interval '8 seconds',false) as state_fresh
        from swiftletcare.actuators where device_id=? order by id limit ? offset ?
        """,
        (rs,n)->new ActuatorView(rs.getLong("id"),rs.getLong("device_id"),rs.getString("kind"),
            rs.getString("mode"),rs.getBoolean("on"),time(rs,"manual_until"),time(rs,"state_received_at"),
            rs.getBoolean("state_fresh"),(Boolean)rs.getObject("safety_override"),canControl),deviceId,size,(long)page*size);
    return new Views.ListView<>(rows,new Views.PageInfo(page,size,total,(int)((total+size-1)/size)));
  }

  @GetMapping("/commands/{commandId}")
  public CommandView command(@AuthenticationPrincipal Jwt jwt,@PathVariable String commandId) {
    if(commandId.isBlank() || commandId.length()>150) throw ApiError.bad("Invalid command ID");
    var device=jdbc.queryForList("select a.device_id from swiftletcare.control_commands c join swiftletcare.actuators a on a.id=c.actuator_id where c.command_id=?",Long.class,commandId);
    if(device.isEmpty()) throw ApiError.missing();
    access.requireDevice(jwt,device.getFirst(),Access.Permission.READ);
    var rows=jdbc.query("select * from swiftletcare.control_commands where command_id=?",
        ControlReadController::readCommand,commandId);
    if(rows.isEmpty()) throw ApiError.missing();
    return rows.getFirst();
  }

  static CommandView readCommand(ResultSet rs, int n) throws SQLException {
    return new CommandView(
        rs.getLong("id"),rs.getString("command_id"),rs.getLong("actuator_id"),rs.getLong("requested_by"),
        rs.getString("action"),(Boolean)rs.getObject("target_state"),rs.getString("target_mode"),rs.getString("status"),
        time(rs,"requested_at"),time(rs,"sent_at"),time(rs,"ack_at"),time(rs,"expires_at"),rs.getString("failure_reason"));
  }

  private static Instant time(ResultSet rs,String name) throws SQLException {
    var ts=rs.getTimestamp(name); return ts==null?null:ts.toInstant();
  }
}
