package com.swiftletcare.management;

import jakarta.persistence.*;

@Entity
@Table(name = "houses", schema = "swiftletcare")
public class House {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public String name;
  public String address;
  public String description;
  public boolean active;
}
