package com.swiftletcare.management;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.security.Access;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@RestController
public class HouseMembershipController {
  private static final Logger log = LoggerFactory.getLogger(HouseMembershipController.class);

  public record RoleUpdate(@NotNull @Pattern(regexp = "OWNER|ADMIN|VIEWER") String role) {}

  public record Membership(
      long id, long userId, long houseId, String role, boolean active, Instant joinedAt) {}

  private final Access access;
  private final JdbcTemplate db;

  public HouseMembershipController(Access access, JdbcTemplate db) {
    this.access = access;
    this.db = db;
  }

  @PatchMapping("/api/v1/houses/{houseId}/members/{membershipId}")
  @Transactional
  public Membership updateRole(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long houseId,
      @PathVariable long membershipId,
      @Valid @RequestBody RoleUpdate input
  ) {
    // Authorize before taking a House lock and recheck after any concurrent role change.
    access.requireHouseOwner(jwt, houseId);
    db.queryForList("select id from swiftletcare.houses where id=? for update", Long.class, houseId);
    access.requireHouseOwner(jwt, houseId);

    var membership = db.query("""
        select id,user_id,house_id,role,active,joined_at
        from swiftletcare.house_memberships
        where house_id=? and id=?
        for update
        """, (rs, row) -> new Membership(
        rs.getLong("id"), rs.getLong("user_id"), rs.getLong("house_id"),
        rs.getString("role"), rs.getBoolean("active"), rs.getTimestamp("joined_at").toInstant()
    ), houseId, membershipId).stream().findFirst().orElseThrow(ApiError::missing);

    if (membership.role().equals(input.role())) {
      return membership;
    }

    long actorId = Long.parseLong(jwt.getSubject());
    if (membership.userId() == actorId) {
      throw new ApiError.Failure(409, "SELF_ROLE_CHANGE", "Cannot change your own House role");
    }
    if (membership.active() && "OWNER".equals(membership.role())) {
      // An OWNER whose account is inactive cannot take over House administration.
      var otherOwners = db.queryForList("""
          select u.id
          from swiftletcare.house_memberships m
          join swiftletcare.users u on u.id=m.user_id
          where m.house_id=? and m.id<>? and m.role='OWNER' and m.active and u.active
          for share of u
          """, Long.class, houseId, membershipId);
      if (otherOwners.isEmpty()) {
        throw new ApiError.Failure(409, "LAST_HOUSE_OWNER", "House must retain an active OWNER");
      }
    }

    db.update("update swiftletcare.house_memberships set role=? where house_id=? and id=?",
        input.role(), houseId, membershipId);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        log.info("House membership role changed houseId={} membershipId={} userId={} actorId={} from={} to={}",
            houseId, membershipId, membership.userId(), actorId, membership.role(), input.role());
      }
    });
    return new Membership(membership.id(), membership.userId(), houseId, input.role(),
        membership.active(), membership.joinedAt());
  }
}
