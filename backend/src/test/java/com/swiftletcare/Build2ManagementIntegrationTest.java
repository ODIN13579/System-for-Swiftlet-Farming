package com.swiftletcare;

import static org.assertj.core.api.Assertions.*;
import com.swiftletcare.security.Access;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="DB_TEST_ENABLED",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod=SpringBootTest.UseMainMethod.ALWAYS, properties={
      "app.jwt-secret=build104-test-secret-at-least-32-bytes",
      "app.ai-service-key=build104-test-service-key-at-least-24", "app.mqtt.enabled=false"})
class Build2ManagementIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtEncoder encoder;
  @Autowired JsonMapper json;
  @Autowired Access access;
  @Autowired com.swiftletcare.realtime.Realtime realtime;
  @LocalServerPort int port;
  long house,zone,user,foreignHouse;
  String token;
  final HttpClient http=HttpClient.newHttpClient();

  @BeforeEach void fixture() {
    String suffix=UUID.randomUUID().toString();
    house=jdbc.queryForObject("insert into swiftletcare.houses(name,active) values('B2 fixture',true) returning id",Long.class);
    foreignHouse=jdbc.queryForObject("insert into swiftletcare.houses(name,active) values('B2 foreign',true) returning id",Long.class);
    zone=jdbc.queryForObject("insert into swiftletcare.zones(house_id,name,active) values(?,'B2 zone',true) returning id",Long.class,house);
    user=jdbc.queryForObject("insert into swiftletcare.users(email,password_hash,role,active) values(?,'unused','OWNER',true) returning id",Long.class,suffix+"@test.invalid");
    jdbc.update("insert into swiftletcare.house_memberships(user_id,house_id,role,active,joined_at) values(?,?,'OWNER',true,now())",user,house);
    token=encode(user,Instant.now().plusSeconds(900));
  }

  String encode(long id,Instant expiry) {
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("swiftletcare").subject(Long.toString(id)).issuedAt(Instant.now().minusSeconds(900))
            .expiresAt(expiry).build())).getTokenValue();
  }
  HttpResponse<String> call(String method,String path,String body,String auth) throws Exception {
    var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json");
    if(auth!=null) b.header("Authorization","Bearer "+auth);
    return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
  void role(String value) { jdbc.update("update swiftletcare.house_memberships set role=? where user_id=?",value,user); }

  Map<String,Object> config(int version,double humidityOn) {
    var p=new HashMap<String,Object>();
    p.put("humidityOn",humidityOn); p.put("humidityOff",78); p.put("tempMin",20); p.put("tempMax",31);
    p.put("tempHysteresis",1); p.put("co2Min",400); p.put("co2Max",2000); p.put("lightMin",0); p.put("lightMax",100);
    p.put("telemetryIntervalSec",5); p.put("heartbeatIntervalSec",2); p.put("version",version); return p;
  }

  @Test void configStorageVersionsPermissionsAndNoOp() throws Exception {
    String path="/zones/"+zone+"/config";
    assertThat(call("GET",path,null,token).statusCode()).isEqualTo(404);
    assertThat(call("PUT",path,json.writeValueAsString(config(0,74)),null).statusCode()).isEqualTo(401);
    var first=call("PUT",path,json.writeValueAsString(config(0,74)),token);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(json.readTree(first.body()).get("version").asInt()).isEqualTo(1);
    assertThat(json.readTree(first.body()).get("syncStatus").asText()).isEqualTo("UNKNOWN");
    var noOp=call("PUT",path,json.writeValueAsString(config(1,74)),token);
    assertThat(noOp.body()).isEqualTo(first.body());
    assertThat(call("PUT",path,json.writeValueAsString(config(0,74)),token).statusCode()).isEqualTo(409);
    role("VIEWER");
    assertThat(call("GET",path,null,token).statusCode()).isEqualTo(200);
    assertThat(call("PUT",path,json.writeValueAsString(config(1,73)),token).statusCode()).isEqualTo(403);
    role("ADMIN");
    var changed=call("PUT",path,json.writeValueAsString(config(1,73)),token);
    assertThat(changed.statusCode()).isEqualTo(200);
    assertThat(json.readTree(changed.body()).get("version").asInt()).isEqualTo(2);
    long foreign=jdbc.queryForObject("insert into swiftletcare.zones(house_id,name,active) values(?,'foreign config',true) returning id",Long.class,foreignHouse);
    assertThat(call("PUT","/zones/"+foreign+"/config",json.writeValueAsString(config(0,74)),token).statusCode()).isEqualTo(403);
    jdbc.update("update swiftletcare.house_memberships set active=false where user_id=?",user);
    assertThat(call("GET",path,null,token).statusCode()).isEqualTo(403);
    jdbc.update("update swiftletcare.users set role='ADMIN' where id=?",user);
    assertThat(call("PUT","/zones/"+foreign+"/config",json.writeValueAsString(config(0,74)),token).statusCode()).isEqualTo(200);
    jdbc.update("update swiftletcare.users set active=false where id=?",user);
    assertThat(call("GET",path,null,token).statusCode()).isEqualTo(403);
  }

  @Test void configInvalidInputNeverChangesSavedVersion() throws Exception {
    String path="/zones/"+zone+"/config";
    assertThat(call("PUT",path,json.writeValueAsString(config(0,74)),token).statusCode()).isEqualTo(200);
    var bad=new ArrayList<Map<String,Object>>();
    for(var entry:Map.<String,Object>of("humidityOn",78,"humidityOff",101,"tempMin",31,"tempMax",61,
        "tempHysteresis",0,"co2Max",5001,"co2Min",2500,"lightMax",100001,"lightMin",101).entrySet()) {
      var p=config(1,74); p.put(entry.getKey(),entry.getValue()); bad.add(p);
    }
    for(var entry:Map.of("telemetryIntervalSec",0,"heartbeatIntervalSec",3,"version",-1).entrySet()) {
      var p=config(1,74); p.put(entry.getKey(),entry.getValue()); bad.add(p);
    }
    var missing=config(1,74); missing.remove("version"); bad.add(missing);
    var extra=config(1,74); extra.put("unexpected",true); bad.add(extra);
    for(var p:bad) assertThat(call("PUT",path,json.writeValueAsString(p),token).statusCode()).as(p.toString()).isEqualTo(400);
    assertThat(jdbc.queryForObject("select version from swiftletcare.zone_configs where zone_id=?",Integer.class,zone)).isEqualTo(1);
  }

  @Test void concurrentConfigUpdatesHaveOneWinner() throws Exception {
    String path="/zones/"+zone+"/config";
    assertThat(call("PUT",path,json.writeValueAsString(config(0,74)),token).statusCode()).isEqualTo(200);
    var start=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var a=pool.submit(()->{start.await();return call("PUT",path,json.writeValueAsString(config(1,72)),token).statusCode();});
      var b=pool.submit(()->{start.await();return call("PUT",path,json.writeValueAsString(config(1,73)),token).statusCode();});
      start.countDown();
      assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
    }
    assertThat(jdbc.queryForObject("select version from swiftletcare.zone_configs where zone_id=?",Integer.class,zone)).isEqualTo(2);
  }

  @Test void houseCreationIsAtomicAndRestrictedToGlobalAdmin() throws Exception {
    var body=Map.of("name","created-"+UUID.randomUUID(),"address","test","initialZone",Map.of("name","initial","areaM2",100));
    assertThat(call("POST","/houses",json.writeValueAsString(body),token).statusCode()).isEqualTo(403);
    jdbc.update("update swiftletcare.users set role='ADMIN' where id=?",user);
    long before=jdbc.queryForObject("select count(*) from swiftletcare.houses",Long.class);
    assertThat(call("POST","/houses","{\"name\":\"bad\",\"address\":\"test\",\"initialZone\":{\"name\":\" \"}}",token).statusCode()).isEqualTo(400);
    assertThat(jdbc.queryForObject("select count(*) from swiftletcare.houses",Long.class)).isEqualTo(before);
    var created=call("POST","/houses",json.writeValueAsString(body),token);
    assertThat(created.statusCode()).isEqualTo(201);
    long id=json.readTree(created.body()).get("id").asLong();
    assertThat(jdbc.queryForObject("select count(*) from swiftletcare.zones where house_id=?",Long.class,id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select role from swiftletcare.house_memberships where user_id=? and house_id=?",String.class,user,id)).isEqualTo("OWNER");
    String suffix=UUID.randomUUID().toString().replace("-","");
    String trigger="b2_house_"+suffix;
    String failedName="rollback-"+suffix;
    jdbc.execute("create function swiftletcare."+trigger+"() returns trigger language plpgsql as $$ begin if NEW.name='"+failedName+"' then raise exception 'test commit failure'; end if; return NEW; end $$");
    try {
      jdbc.execute("create constraint trigger "+trigger+" after insert on swiftletcare.houses deferrable initially deferred for each row execute function swiftletcare."+trigger+"()");
      long zoneCount=jdbc.queryForObject("select count(*) from swiftletcare.zones",Long.class);
      long memberCount=jdbc.queryForObject("select count(*) from swiftletcare.house_memberships",Long.class);
      var failed=Map.of("name",failedName,"address","test","initialZone",Map.of("name","rollback zone"));
      assertThat(call("POST","/houses",json.writeValueAsString(failed),token).statusCode()).isEqualTo(503);
      assertThat(jdbc.queryForObject("select count(*) from swiftletcare.houses where name=?",Long.class,failedName)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from swiftletcare.zones",Long.class)).isEqualTo(zoneCount);
      assertThat(jdbc.queryForObject("select count(*) from swiftletcare.house_memberships",Long.class)).isEqualTo(memberCount);
    } finally {
      jdbc.execute("drop trigger if exists "+trigger+" on swiftletcare.houses");
      jdbc.execute("drop function swiftletcare."+trigger+"()");
    }
  }

  @Test void houseOwnerCanDisableAndReactivateWithoutDeletingHistory() throws Exception {
    long camera=jdbc.queryForObject("insert into swiftletcare.devices(zone_id,device_id,name,type,enabled) values(?,?,'history','CAMERA',true) returning id",Long.class,zone,"CAM-"+UUID.randomUUID());
    long count=jdbc.queryForObject("insert into swiftletcare.count_logs(device_id,period_start,period_end,entry_count,exit_count,detect_count,aggregation_type,processed_at) values(?,'2026-10-01T00:00:00Z','2026-10-01T01:00:00Z',1,0,1,'HOURLY','2026-10-01T01:00:00Z') returning id",Long.class,camera);
    role("ADMIN");
    assertThat(call("PATCH","/houses/"+house,"{\"active\":false}",token).statusCode()).isEqualTo(403);
    role("OWNER");
    assertThat(call("PATCH","/houses/"+foreignHouse,"{\"active\":false}",token).statusCode()).isEqualTo(403);
    assertThat(call("PATCH","/houses/"+house,"{\"active\":false}",token).statusCode()).isEqualTo(200);
    assertThat(call("POST","/houses/"+house+"/zones","{\"name\":\"blocked\"}",token).statusCode()).isEqualTo(403);
    assertThat(jdbc.queryForObject("select count(*) from swiftletcare.count_logs where id=?",Long.class,count)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select enabled from swiftletcare.devices where id=?",Boolean.class,camera)).isTrue();
    assertThat(call("PATCH","/houses/"+house,"{\"active\":true,\"description\":null}",token).statusCode()).isEqualTo(200);
    assertThat(call("GET","/devices/"+camera+"/count-logs?from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z",null,token).statusCode()).isEqualTo(200);
    assertThat(call("DELETE","/houses/"+house,null,token).statusCode()).isEqualTo(405);
  }

  @Test void actuatorAndCommandReadsRespectHouseScope() throws Exception {
    long device=jdbc.queryForObject("insert into swiftletcare.devices(zone_id,device_id,name,type,enabled) values(?,?,'control','ESP32',true) returning id",Long.class,zone,"ESP-"+UUID.randomUUID());
    long actuator=jdbc.queryForObject("insert into swiftletcare.actuators(device_id,kind,mode,\"on\") values(?,'FAN','AUTO',false) returning id",Long.class,device);
    String command=UUID.randomUUID().toString();
    jdbc.update("insert into swiftletcare.control_commands(actuator_id,requested_by,command_id,action,target_mode,expires_at) values(?,?,?,'SET_MODE','MANUAL',now()+interval '15 seconds')",actuator,user,command);
    role("VIEWER");
    var result=call("GET","/commands/"+command,null,token);
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(json.readTree(result.body()).get("status").asText()).isEqualTo("PENDING");
    assertThat(call("GET","/devices/"+device+"/actuators",null,token).body()).contains("FAN");
    assertThat(call("GET","/commands/not-found",null,token).statusCode()).isEqualTo(404);
    jdbc.update("update swiftletcare.house_memberships set house_id=? where user_id=?",foreignHouse,user);
    assertThat(call("GET","/commands/"+command,null,token).statusCode()).isEqualTo(403);
    assertThat(call("GET","/devices/"+device+"/actuators",null,token).statusCode()).isEqualTo(403);
    jdbc.update("update swiftletcare.users set role='ADMIN' where id=?",user);
    assertThat(call("GET","/commands/"+command,null,token).statusCode()).isEqualTo(200);
  }

  @Test void authenticationAndLiveHouseRoles() throws Exception {
    String path="/zones/"+zone;
    assertThat(call("GET",path,null,null).statusCode()).isEqualTo(401);
    assertThat(call("GET",path,null,"invalid").statusCode()).isEqualTo(401);
    assertThat(call("GET",path,null,encode(user,Instant.now().minusSeconds(120))).statusCode()).isEqualTo(401);
    assertThat(call("GET",path,null,token).statusCode()).isEqualTo(200);
    role("VIEWER");
    assertThat(call("GET",path,null,token).statusCode()).isEqualTo(200);
    assertThat(call("PATCH",path,"{\"name\":\"denied\"}",token).statusCode()).isEqualTo(403);
    role("ADMIN");
    assertThat(call("PATCH",path,"{\"name\":\"allowed\"}",token).statusCode()).isEqualTo(200);
    jdbc.update("update swiftletcare.house_memberships set active=false where user_id=?",user);
    assertThat(call("GET",path,null,token).statusCode()).isEqualTo(403);
    jdbc.update("update swiftletcare.house_memberships set active=true where user_id=?",user);
    jdbc.update("update swiftletcare.users set active=false where id=?",user);
    assertThat(call("GET",path,null,token).statusCode()).isEqualTo(403);
    assertThat(call("GET","/houses",null,token).statusCode()).isEqualTo(403);
  }

  @Test void onlyActiveGlobalAdminGrantsCrossHouseAccess() throws Exception {
    assertThat(call("GET","/houses/"+foreignHouse,null,token).statusCode()).isEqualTo(403);
    role("ADMIN");
    assertThat(call("POST","/houses/"+foreignHouse+"/zones","{\"name\":\"denied\"}",token).statusCode()).isEqualTo(403);
    jdbc.update("update swiftletcare.users set role='ADMIN' where id=?",user);
    jdbc.update("update swiftletcare.house_memberships set active=false where user_id=?",user);
    assertThat(call("GET","/houses/"+foreignHouse,null,token).statusCode()).isEqualTo(200);
    assertThat(call("POST","/houses/"+foreignHouse+"/zones","{\"name\":\"global admin zone\"}",token).statusCode()).isEqualTo(201);
    var jwt=Jwt.withTokenValue("test").header("alg","HS256").subject(Long.toString(user)).expiresAt(Instant.now().plusSeconds(60)).build();
    assertThat(access.canHouse(jwt,foreignHouse,Access.Permission.READ)).isTrue();
    assertThat(access.canHouse(jwt,house,Access.Permission.WRITE)).isTrue();
    assertThat(call("GET","/houses?size=100",null,token).body()).contains("B2 foreign");
    jdbc.update("update swiftletcare.users set active=false where id=?",user);
    assertThat(call("GET","/houses/"+foreignHouse,null,token).statusCode()).isEqualTo(403);
    assertThat(call("GET","/houses",null,token).statusCode()).isEqualTo(403);
  }

  @Test void deviceValidationAndDisablePreservesHistory() throws Exception {
    String path="/zones/"+zone+"/devices";
    assertThat(call("POST",path,"{\"deviceId\":\"bad/+\",\"name\":\"x\",\"type\":\"ESP32\"}",token).statusCode()).isEqualTo(400);
    String body=json.writeValueAsString(Map.of("deviceId","CAM-"+UUID.randomUUID(),"name","camera","type","CAMERA"));
    var created=call("POST",path,body,token);
    assertThat(created.statusCode()).isEqualTo(201);
    long device=json.readTree(created.body()).get("id").asLong();
    assertThat(call("POST",path,body,token).statusCode()).isEqualTo(409);
    jdbc.update("insert into swiftletcare.count_logs(device_id,period_start,period_end,entry_count,exit_count,detect_count,aggregation_type,processed_at) values(?,'2026-10-01T00:00:00Z','2026-10-01T01:00:00Z',1,0,1,'HOURLY','2026-10-01T01:00:00Z')",device);
    assertThat(call("PATCH","/devices/"+device,"{\"enabled\":false}",token).statusCode()).isEqualTo(200);
    var countBody=json.writeValueAsString(Map.of("deviceId",device,"periodStart","2026-10-02T00:00:00Z",
        "periodEnd","2026-10-02T01:00:00Z","entryCount",1,"exitCount",0,"detectCount",1,
        "aggregationType","HOURLY","processedAt","2026-10-02T01:00:00Z"));
    var aiRequest=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/internal/ai/count-logs"))
        .header("Content-Type","application/json").header("X-Service-Key","build104-test-service-key-at-least-24")
        .POST(HttpRequest.BodyPublishers.ofString(countBody)).build();
    assertThat(http.send(aiRequest,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    assertThat(jdbc.queryForObject("select count(*) from swiftletcare.count_logs where device_id=?",Long.class,device)).isEqualTo(1);
    assertThat(call("GET","/devices/"+device+"/count-logs?from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z",null,token).statusCode()).isEqualTo(200);
    assertThat(call("PATCH","/devices/"+device,"{\"zoneId\":1}",token).statusCode()).isEqualTo(400);
    assertThat(call("PATCH","/devices/"+device,"{\"enabled\":null}",token).statusCode()).isEqualTo(400);
    assertThat(call("DELETE","/devices/"+device,null,token).statusCode()).isEqualTo(405);
  }

  @Test void websocketRechecksMembershipBeforeDelivery() throws Exception {
    long device=jdbc.queryForObject("insert into swiftletcare.devices(zone_id,device_id,name,type,enabled) values(?,?,'WS','CAMERA',true) returning id",
        Long.class,zone,"WS-"+UUID.randomUUID());
    var messages=new LinkedBlockingQueue<String>();
    var closed=new CompletableFuture<Integer>();
    var socket=http.newWebSocketBuilder().header("Authorization","Bearer "+token)
        .buildAsync(URI.create("ws://localhost:"+port+"/ws/devices/"+device),new WebSocket.Listener() {
          public void onOpen(WebSocket ws) { ws.request(1); }
          public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last) {
            messages.add(text.toString()); ws.request(1); return null;
          }
          public CompletionStage<?> onClose(WebSocket ws,int code,String reason) {
            closed.complete(code); return null;
          }
        }).get(5,TimeUnit.SECONDS);
    try {
      // A handshake may finish before afterConnectionEstablished registers the session.
      String received=null;
      for(int i=0;i<20 && received==null;i++) {
        realtime.publish(device,"test.allowed",Map.of("id",device));
        received=messages.poll(100,TimeUnit.MILLISECONDS);
      }
      assertThat(received).contains("test.allowed");
      jdbc.update("update swiftletcare.house_memberships set active=false where user_id=?",user);
      realtime.publish(device,"test.forbidden",Map.of("id",device));
      assertThat(closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
      assertThat(messages.poll(150,TimeUnit.MILLISECONDS)).isNull();
    } finally { socket.abort(); }
  }

  @Test void concurrentZonePatchesPreserveBothChangesAndCanReactivate() throws Exception {
    var start=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var a=pool.submit(() -> {start.await();return call("PATCH","/zones/"+zone,"{\"name\":\"concurrent\"}",token);});
      var b=pool.submit(() -> {start.await();return call("PATCH","/zones/"+zone,"{\"floorLabel\":\"floor\"}",token);});
      start.countDown();
      assertThat(a.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(b.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
    var row=jdbc.queryForMap("select name,floor_label from swiftletcare.zones where id=?",zone);
    assertThat(row.get("name")).isEqualTo("concurrent");
    assertThat(row.get("floor_label")).isEqualTo("floor");
    assertThat(call("PATCH","/zones/"+zone,"{\"active\":false}",token).statusCode()).isEqualTo(200);
    assertThat(call("POST","/zones/"+zone+"/devices","{\"deviceId\":\"disabled-zone\",\"name\":\"x\",\"type\":\"ESP32\"}",token).statusCode()).isEqualTo(403);
    assertThat(call("PATCH","/zones/"+zone,"{\"active\":true,\"floorLabel\":null,\"areaM2\":null}",token).statusCode()).isEqualTo(200);
    assertThat(call("PATCH","/zones/"+zone,"{\"areaM2\":-1}",token).statusCode()).isEqualTo(400);
  }
}
