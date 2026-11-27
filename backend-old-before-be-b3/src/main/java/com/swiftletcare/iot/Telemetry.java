package com.swiftletcare.iot;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "telemetry", schema = "swiftletcare")
public class Telemetry {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long deviceId;
  public String eventId;
  public Instant timestamp;
  public Instant receivedAt;
  public Double temperature;
  public Double humidity;
  public Double co2;
  public Double light;
  public String sensorStatus;
}
