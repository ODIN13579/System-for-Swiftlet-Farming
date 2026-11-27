package com.swiftletcare.management;

import jakarta.persistence.*;

@Entity
@Table(name = "zones", schema = "swiftletcare")
public class Zone {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long houseId;
  public String name;
  public String floorLabel;
  public Double areaM2;
  public boolean active;
}
