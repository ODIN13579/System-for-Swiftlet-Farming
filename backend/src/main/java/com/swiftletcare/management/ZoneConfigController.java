package com.swiftletcare.management;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.security.Access;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** V3 configuration storage. Device application is deliberately reported as UNKNOWN. */
@RestController
@RequestMapping("/api/v1/zones/{zoneId}/config")
public class ZoneConfigController {
  public record Input(Double humidityOn, Double humidityOff, Double tempMin, Double tempMax,
      Double tempHysteresis, Double co2Min, Double co2Max, Double lightMin, Double lightMax,
      @NotNull @Min(1) @Max(60) Integer telemetryIntervalSec,
      @NotNull @Min(2) @Max(2) Integer heartbeatIntervalSec,
      @NotNull @Min(0) Integer version) {}

  public record View(long id, long zoneId, Double humidityOn, Double humidityOff,
      Double tempMin, Double tempMax, Double tempHysteresis, Double co2Min, Double co2Max,
      Double lightMin, Double lightMax, int telemetryIntervalSec, int heartbeatIntervalSec,
      int version, Instant updatedAt, String syncStatus) {}

  private final JdbcTemplate jdbc;
  private final Access access;

  public ZoneConfigController(JdbcTemplate jdbc, Access access) {
    this.jdbc=jdbc; this.access=access;
  }

  @GetMapping
  public View get(@AuthenticationPrincipal Jwt jwt,@PathVariable long zoneId) {
    access.requireZone(jwt,zoneId,Access.Permission.READ);
    return find(zoneId);
  }

  @PutMapping
  @Transactional
  public View put(@AuthenticationPrincipal Jwt jwt,@PathVariable long zoneId,
      @Valid @RequestBody Input p) {
    // Same ancestor-before-resource lock order as management and ingestion.
    var houses=jdbc.queryForList("select house_id from swiftletcare.zones where id=?",Long.class,zoneId);
    if(houses.isEmpty()) throw ApiError.missing();
    jdbc.queryForList("select id from swiftletcare.houses where id=? for share",Long.class,houses.getFirst());
    jdbc.queryForList("select id from swiftletcare.zones where id=? for update",Long.class,zoneId);
    access.requireZone(jwt,zoneId,Access.Permission.WRITE);
    validate(p);
    var existing=jdbc.query("select * from swiftletcare.zone_configs where zone_id=?",this::map,zoneId);
    View old=existing.isEmpty()?null:existing.getFirst();
    if(p.version()!=(old==null?0:old.version())) throw versionConflict();
    if(old!=null && unchanged(old,p)) return old;
    if(old!=null && old.version()==Integer.MAX_VALUE)
      throw new ApiError.Failure(409,"VERSION_EXHAUSTED","Configuration version cannot increase");
    var args=new ArrayList<Object>(Arrays.asList(p.humidityOn(),p.humidityOff(),p.tempMin(),p.tempMax(),
        p.tempHysteresis(),p.co2Min(),p.co2Max(),p.lightMin(),p.lightMax(),p.telemetryIntervalSec(),p.heartbeatIntervalSec()));
    if(old==null) {
      args.add(zoneId);
      jdbc.update("""
          insert into swiftletcare.zone_configs(humidity_on,humidity_off,temp_min,temp_max,temp_hysteresis,
          co2_min,co2_max,light_min,light_max,telemetry_interval_sec,heartbeat_interval_sec,zone_id)
          values(?,?,?,?,?,?,?,?,?,?,?,?)
          """,args.toArray());
    } else {
      args.add(old.id()); args.add(old.version());
      int changed=jdbc.update("""
          update swiftletcare.zone_configs set humidity_on=?,humidity_off=?,temp_min=?,temp_max=?,temp_hysteresis=?,
          co2_min=?,co2_max=?,light_min=?,light_max=?,telemetry_interval_sec=?,heartbeat_interval_sec=?,
          version=version+1,updated_at=clock_timestamp() where id=? and version=?
          """,args.toArray());
      if(changed!=1) throw versionConflict();
    }
    // No MQTT publish until sync schema and proof-of-application contract are approved.
    return find(zoneId);
  }

  private View find(long zone) {
    var rows=jdbc.query("select * from swiftletcare.zone_configs where zone_id=?",this::map,zone);
    if(rows.isEmpty()) throw ApiError.missing();
    return rows.getFirst();
  }

  private View map(ResultSet rs,int row) throws SQLException {
    return new View(rs.getLong("id"),rs.getLong("zone_id"),(Double)rs.getObject("humidity_on"),
        (Double)rs.getObject("humidity_off"),(Double)rs.getObject("temp_min"),(Double)rs.getObject("temp_max"),
        (Double)rs.getObject("temp_hysteresis"),(Double)rs.getObject("co2_min"),(Double)rs.getObject("co2_max"),
        (Double)rs.getObject("light_min"),(Double)rs.getObject("light_max"),rs.getInt("telemetry_interval_sec"),
        rs.getInt("heartbeat_interval_sec"),rs.getInt("version"),rs.getTimestamp("updated_at").toInstant(),"UNKNOWN");
  }

  static void validate(Input p) {
    range(p.humidityOn(),0,100); range(p.humidityOff(),0,100);
    range(p.tempMin(),-10,60); range(p.tempMax(),-10,60);
    range(p.co2Min(),0,5000); range(p.co2Max(),0,5000);
    range(p.lightMin(),0,100000); range(p.lightMax(),0,100000);
    if(p.tempHysteresis()!=null && (!Double.isFinite(p.tempHysteresis()) || p.tempHysteresis()<=0))
      throw ApiError.bad("tempHysteresis must be finite and positive");
    if(p.humidityOn()!=null && p.humidityOff()!=null && p.humidityOn()>=p.humidityOff()
        || p.tempMin()!=null && p.tempMax()!=null && p.tempMin()>=p.tempMax()
        || p.co2Min()!=null && p.co2Max()!=null && p.co2Min()>p.co2Max()
        || p.lightMin()!=null && p.lightMax()!=null && p.lightMin()>p.lightMax())
      throw ApiError.bad("Invalid threshold ordering");
  }
  private static void range(Double value,double min,double max) {
    if(value!=null && (!Double.isFinite(value) || value<min || value>max))
      throw ApiError.bad("Configuration threshold outside allowed range");
  }
  private static boolean unchanged(View v,Input p) {
    Double[] old={v.humidityOn(),v.humidityOff(),v.tempMin(),v.tempMax(),v.tempHysteresis(),v.co2Min(),v.co2Max(),v.lightMin(),v.lightMax()};
    Double[] next={p.humidityOn(),p.humidityOff(),p.tempMin(),p.tempMax(),p.tempHysteresis(),p.co2Min(),p.co2Max(),p.lightMin(),p.lightMax()};
    for(int i=0;i<old.length;i++)
      if(old[i]==null?next[i]!=null:next[i]==null || old[i].doubleValue()!=next[i].doubleValue()) return false;
    return v.telemetryIntervalSec()==p.telemetryIntervalSec() && v.heartbeatIntervalSec()==p.heartbeatIntervalSec();
  }
  private static ApiError.Failure versionConflict() {
    return new ApiError.Failure(409,"VERSION_CONFLICT","Configuration changed; reload before updating");
  }
}
