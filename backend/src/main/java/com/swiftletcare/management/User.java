package com.swiftletcare.management;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    @Column(name = "token_version", nullable = false)
    public long tokenVersion;
}
