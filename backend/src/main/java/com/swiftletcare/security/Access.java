package com.swiftletcare.security;

import com.swiftletcare.api.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class Access {
  public enum Permission { READ, WRITE, OWNER }

  private final JdbcTemplate jdbc;

  public Access(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long requireActiveAccount(Jwt jwt) {
    long id;
    try {
      id = Long.parseLong(jwt.getSubject());
      requireAllowed(id > 0 && jwt.getExpiresAt() != null
          && jwt.getExpiresAt().isAfter(java.time.Instant.now()));
    } catch (NullPointerException | NumberFormatException e) {
      throw new ApiError.Failure(403, "ACCESS_DENIED", "Invalid account");
    }
    requireAllowed(Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from swiftletcare.users where id=? and active)",Boolean.class,id)));
    return id;
  }

  public boolean canRead(Jwt jwt, long device) {
    return allowed(jwt, "device", device, Permission.READ);
  }

  public long requireGlobalAdmin(Jwt jwt) {
    long id=requireActiveAccount(jwt);
    requireAllowed(Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from swiftletcare.users where id=? and active and role='ADMIN')",Boolean.class,id)));
    return id;
  }

  public void requireHouseLifecycle(Jwt jwt,long house) {
    requireAllowed(allowed(jwt,"house",house,Permission.OWNER,false));
  }

  public boolean canHouse(Jwt jwt, long house, Permission permission) {
    return allowed(jwt, "house", house, permission);
  }

  public void requireHouse(Jwt jwt, long house, Permission permission) {
    requireAllowed(canHouse(jwt, house, permission));
  }

  public void requireZone(Jwt jwt, long zone, Permission permission) {
    requireAllowed(allowed(jwt, "zone", zone, permission));
  }

  public void requireDevice(Jwt jwt, long device, Permission permission) {
    requireAllowed(allowed(jwt, "device", device, permission));
  }

  private boolean allowed(Jwt jwt, String resource, long id, Permission permission) {
    return allowed(jwt,resource,id,permission,true);
  }

  private boolean allowed(Jwt jwt, String resource, long id, Permission permission, boolean activeHouse) {
    if (jwt == null
        || jwt.getExpiresAt() == null
        || !jwt.getExpiresAt().isAfter(java.time.Instant.now())) return false;
    long user;
    try {
      user = Long.parseLong(jwt.getSubject());
      if (user <= 0 || id <= 0) return false;
    } catch (RuntimeException e) {
      return false;
    }
    // BUILD2 policy confirmed by the user: active global ADMIN spans all Houses.
    // Other users require an active membership; House ADMIN never becomes global ADMIN.
    // Resource SQL fragments are internal constants, never request input.
    String relation = switch (resource) {
      case "device" -> " join swiftletcare.zones z on z.house_id=h.id join swiftletcare.devices d on d.zone_id=z.id ";
      case "zone" -> " join swiftletcare.zones z on z.house_id=h.id ";
      case "house" -> " ";
      default -> throw new IllegalArgumentException("Unknown resource");
    };
    String target = switch (resource) {
      case "device" -> "d.id=? and z.active";
      case "zone" -> "z.id=? and z.active";
      default -> "h.id=?";
    };
    String role = switch (permission) {
      case READ -> "m.role in ('OWNER','ADMIN','VIEWER')";
      case WRITE -> "m.role in ('OWNER','ADMIN')";
      case OWNER -> "m.role='OWNER'";
    };
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from swiftletcare.houses h" + relation
                + "join swiftletcare.users u on u.id=?"
                + " left join swiftletcare.house_memberships m on m.house_id=h.id and m.user_id=u.id where " + target
                + " and u.active" + (activeHouse?" and h.active":"")
                + " and (u.role='ADMIN' or (m.active and " + role + ")))",
            Boolean.class,
            user,
            id));
  }

  public void require(Jwt jwt, long device) {
    requireDevice(jwt, device, Permission.READ);
  }

  private void requireAllowed(boolean allowed) {
    if (!allowed)
      throw new ApiError.Failure(403, "ACCESS_DENIED", "No active house membership");
  }
}
