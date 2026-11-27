package com.swiftletcare.management;

import jakarta.persistence.*;

@Entity
@Table(name = "users", schema = "swiftletcare")
public class User {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public String email;
  public String passwordHash;
  public String fullName;
  public String phone;
  public String role;
  public boolean active;
}
