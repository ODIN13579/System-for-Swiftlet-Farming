package com.swiftletcare.management;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "devices", schema = "swiftletcare")
public class Device {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long zoneId;
  public String deviceId;
  public String name;
  public String type;
  public boolean enabled;
  public boolean online;
  public Instant lastSeenAt;
}
