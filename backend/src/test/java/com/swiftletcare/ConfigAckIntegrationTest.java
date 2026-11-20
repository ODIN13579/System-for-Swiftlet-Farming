package com.swiftletcare;

import static org.assertj.core.api.Assertions.assertThat;
import com.swiftletcare.iot.DeviceConfigSync;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="DB_TEST_ENABLED",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
  "CONFIG_SYNC_ENABLED=true","app.mqtt.url=${TEST_MQTT_URL:tcp://localhost:51884}",
  "app.mqtt.client-id=b250-config-backend","app.jwt-secret=build104-test-secret-at-least-32-bytes",
  "app.ai-service-key=build104-test-service-key-at-least-24"})
class ConfigAckIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired DeviceConfigSync sync;
  @Autowired JsonMapper json;
  @Test void configChangesFromPendingToAppliedAfterMatchingDeviceAck() throws Exception {
    long h=jdbc.queryForObject("insert into swiftletcare.houses(name,active) values('B250',true) returning id",Long.class);
    long z=jdbc.queryForObject("insert into swiftletcare.zones(house_id,name,active) values(?,'B250',true) returning id",Long.class,h);
    String code="ESP-"+UUID.randomUUID(), base="swiftletcare/"+h+"/"+z+"/"+code;
    jdbc.update("insert into swiftletcare.devices(zone_id,device_id,name,type,enabled) values(?,?,'B250','ESP32',true)",z,code);
    jdbc.update("insert into swiftletcare.zone_configs(zone_id,version,humidity_on,humidity_off,temp_min,temp_max) values(?,2,70,80,25,31) on conflict(zone_id) do update set version=2,humidity_on=70,humidity_off=80,temp_min=25,temp_max=31",z);
    var q=new LinkedBlockingQueue<String>();
    var mqtt=new MqttClient(System.getenv().getOrDefault("TEST_MQTT_URL","tcp://localhost:51884"),"probe-"+UUID.randomUUID(),new MemoryPersistence());
    try {
      mqtt.connect();
      mqtt.subscribe(base+"/cfg",1,(t,m)->q.offer(new String(m.getPayload(),StandardCharsets.UTF_8)));
      sync.queueForZone(z,2);
      String msg=q.poll(20,TimeUnit.SECONDS);
      assertThat(msg).isNotNull();
      assertThat(json.readTree(msg).get("version").asInt()).isEqualTo(2);
      assertThat(sync.statusForZone(z,2)).isEqualTo("PENDING");
      mqtt.publish(base+"/cfg_ack",json.writeValueAsBytes(Map.of("device_id",code,"version",2,"status","APPLIED","reason","OK","timestamp",Instant.now().toString())),1,false);
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
      while(System.nanoTime()<deadline && !"APPLIED".equals(sync.statusForZone(z,2))) Thread.sleep(100);
      assertThat(sync.statusForZone(z,2)).isEqualTo("APPLIED");
    } finally { if(mqtt.isConnected()) mqtt.disconnect(); mqtt.close(); }
  }
}
