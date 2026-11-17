package com.swiftletcare.management;

public interface UserRepository
    extends org.springframework.data.jpa.repository.JpaRepository<User, Long> {
  java.util.Optional<User> findByEmailAndActiveTrue(String email);
}
