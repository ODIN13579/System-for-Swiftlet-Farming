package com.swiftletcare.management;

import com.swiftletcare.api.*;
import com.swiftletcare.security.Access;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Management on the existing V2 schema. No physical deletes or device reassignment. */
@RestController
@RequestMapping("/api/v1")
public class ManagementController {
  public record ZoneInput(@NotBlank @Size(max=150) String name,
      @Size(max=50) String floorLabel, @PositiveOrZero Double areaM2) {}
  public record DeviceInput(
      @NotBlank @Size(max=100) @Pattern(regexp="[^/+#\\s]+") String deviceId,
      @NotBlank @Size(max=150) String name,
      @NotNull @Pattern(regexp="ESP32|CAMERA") String type, Boolean enabled) {}
  public record HouseInput(@NotBlank @Size(max=150) String name,
      @NotNull String address, String description, @NotNull @Valid ZoneInput initialZone) {}

  private final Access access;
  private final JdbcTemplate jdbc;
  private final HouseRepository houses;
  private final ZoneRepository zones;
  private final DeviceRepository devices;

  public ManagementController(Access access, JdbcTemplate jdbc, HouseRepository houses,
      ZoneRepository zones, DeviceRepository devices) {
    this.access=access; this.jdbc=jdbc; this.houses=houses; this.zones=zones; this.devices=devices;
  }

  @GetMapping("/houses")
  public Views.ListView<House> houses(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
    DeviceController.page(page,size);
    // Reuse the same live policy, including token expiry and inactive accounts.
    var permitted = jdbc.queryForList("select h.id from swiftletcare.houses h join swiftletcare.users u on u.id=? left join swiftletcare.house_memberships m on m.house_id=h.id and m.user_id=u.id where h.active and u.active and (u.role='ADMIN' or (m.active and m.role in ('OWNER','ADMIN','VIEWER'))) order by h.id",
        Long.class, access.requireActiveAccount(jwt)).stream().filter(id -> access.canHouse(jwt,id,Access.Permission.READ)).toList();
    var items=permitted.stream().skip((long)page*size).limit(size)
        .map(id -> houses.findById(id).orElseThrow(ApiError::missing)).toList();
    return new Views.ListView<>(items, new Views.PageInfo(page,size,permitted.size(),
        (int)(((long)permitted.size()+size-1)/size)));
  }

  @GetMapping("/houses/{houseId}")
  public House house(@AuthenticationPrincipal Jwt jwt, @PathVariable long houseId) {
    access.requireHouse(jwt,houseId,Access.Permission.READ);
    return houses.findById(houseId).orElseThrow(ApiError::missing);
  }

  @PostMapping("/houses")
  @Transactional
  public ResponseEntity<House> createHouse(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody HouseInput p) {
    long creator=access.requireGlobalAdmin(jwt);
    finiteArea(p.initialZone().areaM2());
    var h=new House(); h.name=p.name(); h.address=p.address(); h.description=p.description(); h.active=true;
    houses.saveAndFlush(h);
    var z=new Zone(); z.houseId=h.id; z.name=p.initialZone().name();
    z.floorLabel=p.initialZone().floorLabel(); z.areaM2=p.initialZone().areaM2(); z.active=true;
    zones.saveAndFlush(z);
    jdbc.update("insert into swiftletcare.house_memberships(user_id,house_id,role,active,joined_at) values(?,?,'OWNER',true,now())",creator,h.id);
    return ResponseEntity.status(201).body(h);
  }

  @PatchMapping("/houses/{houseId}")
  @Transactional
  public House updateHouse(@AuthenticationPrincipal Jwt jwt,@PathVariable long houseId,
      @RequestBody Map<String,Object> p) {
    fields(p,Set.of("name","address","description","active"));
    lockHouse(houseId);
    // Inactive House is accessible here only for an owner/global admin to re-enable it.
    access.requireHouseLifecycle(jwt,houseId);
    var h=houses.findById(houseId).orElseThrow(ApiError::missing);
    if(p.containsKey("name")) h.name=text(p,"name",150,false);
    if(p.containsKey("address")) {
      if(!(p.get("address") instanceof String)) throw ApiError.bad("Invalid address");
      h.address=(String)p.get("address");
    }
    if(p.containsKey("description")) {
      if(p.get("description")!=null && !(p.get("description") instanceof String)) throw ApiError.bad("Invalid description");
      h.description=(String)p.get("description");
    }
    if(p.containsKey("active")) h.active=bool(p,"active");
    return houses.saveAndFlush(h);
  }

  @GetMapping("/houses/{houseId}/zones")
  public Views.ListView<Zone> zones(@AuthenticationPrincipal Jwt jwt, @PathVariable long houseId,
      @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
    DeviceController.page(page,size);
    access.requireHouse(jwt,houseId,Access.Permission.READ);
    var ids=jdbc.queryForList("select id from swiftletcare.zones where house_id=? order by id limit ? offset ?",
        Long.class,houseId,size,(long)page*size);
    long total=jdbc.queryForObject("select count(*) from swiftletcare.zones where house_id=?",Long.class,houseId);
    return new Views.ListView<>(ids.stream().map(id -> zones.findById(id).orElseThrow(ApiError::missing)).toList(),
        new Views.PageInfo(page,size,total,(int)((total+size-1)/size)));
  }

  @GetMapping("/zones/{zoneId}")
  public Zone zone(@AuthenticationPrincipal Jwt jwt, @PathVariable long zoneId) {
    access.requireZone(jwt,zoneId,Access.Permission.READ);
    return zones.findById(zoneId).orElseThrow(ApiError::missing);
  }

  @PostMapping("/houses/{houseId}/zones")
  @Transactional
  public ResponseEntity<Zone> createZone(@AuthenticationPrincipal Jwt jwt, @PathVariable long houseId,
      @Valid @RequestBody ZoneInput p) {
    lockHouse(houseId);
    access.requireHouse(jwt,houseId,Access.Permission.WRITE);
    finiteArea(p.areaM2());
    var z=new Zone(); z.houseId=houseId; z.name=p.name(); z.floorLabel=p.floorLabel(); z.areaM2=p.areaM2(); z.active=true;
    return ResponseEntity.status(201).body(zones.saveAndFlush(z));
  }

  @PatchMapping("/zones/{zoneId}")
  @Transactional
  public Zone updateZone(@AuthenticationPrincipal Jwt jwt, @PathVariable long zoneId,
      @RequestBody Map<String,Object> p) {
    fields(p,Set.of("name","floorLabel","areaM2","active"));
    var z=zones.findById(zoneId).orElseThrow(ApiError::missing);
    lockHouse(z.houseId);
    lock("zones",zoneId);
    // House permission permits reactivation of an inactive Zone by its managers.
    access.requireHouse(jwt,z.houseId,Access.Permission.WRITE);
    // Refresh after the lock to avoid overwriting a concurrently committed patch.
    jdbcZone(z);
    if(p.containsKey("name")) z.name=text(p,"name",150,false);
    if(p.containsKey("floorLabel")) z.floorLabel=text(p,"floorLabel",50,true);
    if(p.containsKey("areaM2")) {
      Object v=p.get("areaM2");
      if(v!=null && !(v instanceof Number)) throw ApiError.bad("areaM2 must be a number");
      z.areaM2=v==null?null:((Number)v).doubleValue(); finiteArea(z.areaM2);
    }
    if(p.containsKey("active")) z.active=bool(p,"active");
    return zones.saveAndFlush(z);
  }

  @PostMapping("/zones/{zoneId}/devices")
  @Transactional
  public ResponseEntity<Views.DeviceView> createDevice(@AuthenticationPrincipal Jwt jwt, @PathVariable long zoneId,
      @Valid @RequestBody DeviceInput p) {
    var z=zones.findById(zoneId).orElseThrow(ApiError::missing);
    lockHouse(z.houseId); lock("zones",zoneId);
    access.requireZone(jwt,zoneId,Access.Permission.WRITE);
    var d=new Device(); d.zoneId=zoneId; d.deviceId=p.deviceId(); d.name=p.name(); d.type=p.type();
    d.enabled=p.enabled()==null || p.enabled(); d.online=false;
    // Native ON CONFLICT keeps a duplicate identifier from aborting a PostgreSQL transaction.
    var ids=jdbc.queryForList("insert into swiftletcare.devices(zone_id,device_id,name,type,enabled,online) values(?,?,?,?,?,false) on conflict(device_id) do nothing returning id",
        Long.class,d.zoneId,d.deviceId,d.name,d.type,d.enabled);
    if(ids.isEmpty()) throw new ApiError.Failure(409,"DEVICE_IDENTIFIER_EXISTS","Device identifier already registered");
    d.id=ids.getFirst();
    return ResponseEntity.status(201).body(Views.DeviceView.of(d));
  }

  @PatchMapping("/devices/{deviceId}")
  @Transactional
  public Views.DeviceView updateDevice(@AuthenticationPrincipal Jwt jwt, @PathVariable long deviceId,
      @RequestBody Map<String,Object> p) {
    fields(p,Set.of("name","enabled"));
    var d=devices.findById(deviceId).orElseThrow(ApiError::missing);
    var z=zones.findById(d.zoneId).orElseThrow(ApiError::missing);
    lockHouse(z.houseId); lock("zones",z.id); lock("devices",deviceId);
    access.requireZone(jwt,z.id,Access.Permission.WRITE);
    var current=jdbc.queryForMap("select name,enabled,online,last_seen_at from swiftletcare.devices where id=?",deviceId);
    d.name=(String)current.get("name"); d.enabled=(Boolean)current.get("enabled");
    d.online=(Boolean)current.get("online");
    var seen=(java.sql.Timestamp)current.get("last_seen_at"); d.lastSeenAt=seen==null?null:seen.toInstant();
    if(p.containsKey("name")) d.name=text(p,"name",150,false);
    if(p.containsKey("enabled")) {
      d.enabled=bool(p,"enabled");
      if(!d.enabled) d.online=false;
    }
    // Never overwrite last_seen_at with the value read before a concurrent heartbeat.
    jdbc.update("update swiftletcare.devices set name=?,enabled=?,online=? where id=?",d.name,d.enabled,d.online,d.id);
    return Views.DeviceView.of(d);
  }

  private void jdbcZone(Zone z) {
    var row=jdbc.queryForMap("select name,floor_label,area_m2,active from swiftletcare.zones where id=?",z.id);
    z.name=(String)row.get("name"); z.floorLabel=(String)row.get("floor_label");
    z.areaM2=(Double)row.get("area_m2"); z.active=(Boolean)row.get("active");
  }

  private void lockHouse(long id) { lock("houses",id); }
  private void lock(String table,long id) {
    // table is supplied only by this class.
    if(jdbc.queryForList("select id from swiftletcare."+table+" where id=? for update",Long.class,id).isEmpty())
      throw ApiError.missing();
  }
  private static void fields(Map<String,Object> p, Set<String> allowed) {
    if(!allowed.containsAll(p.keySet())) throw ApiError.bad("Unknown patch field");
  }
  private static String text(Map<String,Object> p,String key,int max,boolean nullable) {
    Object v=p.get(key);
    if(v==null && nullable) return null;
    if(!(v instanceof String s) || s.length()>max || (!nullable && s.isBlank())) throw ApiError.bad("Invalid "+key);
    return (String)v;
  }
  private static boolean bool(Map<String,Object> p,String key) {
    if(!(p.get(key) instanceof Boolean b)) throw ApiError.bad("Invalid "+key);
    return b;
  }
  private static void finiteArea(Double v) {
    if(v!=null && (!Double.isFinite(v) || v<0)) throw ApiError.bad("areaM2 must be finite and nonnegative");
  }
}
