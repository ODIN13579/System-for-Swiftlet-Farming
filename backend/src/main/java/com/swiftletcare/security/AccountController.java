
package com.swiftletcare.security;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.api.Views;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1")
public class AccountController {

    public record CreateAccount(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 200) String password,
        @NotNull @Pattern(regexp = "OWNER|ADMIN") String role,
        @Size(max = 150) String fullName,
        @Size(max = 30) String phone,
        @Positive Long houseId,
        @Pattern(regexp = "OWNER|ADMIN|VIEWER") String houseRole
    ) {}

    public record AccountView(
        long id,
        String email,
        String fullName,
        String phone,
        String role,
        boolean active
    ) {}

    public record AccountState(
        @NotNull Boolean active
    ) {}

    public record ChangePassword(
        @NotBlank String currentPassword,
        @NotBlank String newPassword
    ) {}

    private record ExistingAccount(
        long id, String role, boolean active
    ) {}

    private final JdbcTemplate db;
    private final Access access;
    private final BCryptPasswordEncoder passwords;
    private final boolean secureCookie;

    public AccountController(
        JdbcTemplate db,
        Access access,
        BCryptPasswordEncoder passwords,
        @Value("${app.secure-cookie:false}") boolean secureCookie
    ) {
        this.db = db;
        this.access = access;
        this.passwords = passwords;
        this.secureCookie = secureCookie;
    }

    private final org.springframework.jdbc.core.RowMapper<AccountView>
        accountMapper = (rs, row) -> new AccountView(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("full_name"),
            rs.getString("phone"),
            rs.getString("role"),
            rs.getBoolean("active")
        );

    private AccountView account(long id) {
        return db.query("""
            select id,email,full_name,phone,role,active
            from swiftletcare.users
            where id=?
            """, accountMapper, id)
            .stream().findFirst()
            .orElseThrow(ApiError::missing);
    }

    private static String optionalText(String value, int max) {
        if (value == null) return null;

        String cleaned = value.strip();

        if (cleaned.isEmpty()) return null;

        if (cleaned.length() > max ||
            cleaned.codePoints()
                .anyMatch(Character::isISOControl)) {
            throw ApiError.bad("Invalid profile field");
        }

        return cleaned;
    }

    private static String profileField(Object value, int max) {
        if (value == null) return null;

        if (!(value instanceof String text)) {
            throw ApiError.bad("Profile field must be text");
        }

        return optionalText(text, max);
    }

    private static void strongPassword(String password) {
        if (password == null
            || password.length() < 12
            || password.length() > 200
            || password.chars().noneMatch(Character::isLetter)
            || password.chars().noneMatch(Character::isDigit)) {
            throw ApiError.bad(
                "Password must contain 12-200 characters,"
                + " including letters and digits"
            );
        }
    }

    private void expireWsCookie(HttpServletResponse response) {
        response.addHeader(
            "Set-Cookie",
            ResponseCookie.from("SC_WS", "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path("/ws")
                .maxAge(Duration.ZERO)
                .build()
                .toString()
        );
    }

    // ===== GLOBAL ADMIN =====

    @PostMapping("/admin/users")
    @Transactional
    public ResponseEntity<AccountView> createAccount(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody CreateAccount input
    ) {
        access.requireGlobalAdmin(jwt);

        strongPassword(input.password());

        if ((input.houseId() == null)
                != (input.houseRole() == null)) {
            throw ApiError.bad(
                "houseId and houseRole must be supplied together"
            );
        }

        String email = input.email()
            .strip().toLowerCase(Locale.ROOT);

        // ON CONFLICT works with both the original email UNIQUE
        // constraint and the new case-insensitive index.
        var ids = db.queryForList("""
            insert into swiftletcare.users
                (email,password_hash,full_name,
                 phone,role,active)
            values(?,?,?,?,?,true)
            on conflict do nothing
            returning id
            """,
            Long.class,
            email,
            passwords.encode(input.password()),
            optionalText(input.fullName(), 150),
            optionalText(input.phone(), 30),
            input.role()
        );

        if (ids.isEmpty()) {
            throw new ApiError.Failure(
                409, "EMAIL_EXISTS",
                "Email is already registered"
            );
        }

        long id = ids.getFirst();

        if (input.houseId() != null) {
            Boolean validHouse = db.queryForObject("""
                select exists(
                    select 1 from swiftletcare.houses
                    where id=? and active
                )
                """,
                Boolean.class, input.houseId());

            if (!Boolean.TRUE.equals(validHouse)) {
                throw ApiError.bad("Active house required");
            }

            db.update("""
                insert into swiftletcare.house_memberships
                    (user_id,house_id,role,active,joined_at)
                values(?,?,?,true,clock_timestamp())
                """,
                id,
                input.houseId(),
                input.houseRole()
            );
        }

        return ResponseEntity.status(201).body(account(id));
    }

    @GetMapping("/admin/users")
    public Views.ListView<AccountView> listAccounts(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        access.requireGlobalAdmin(jwt);

        if (page < 0 || size < 1 || size > 100) {
            throw ApiError.bad("Invalid pagination");
        }

        long total = db.queryForObject(
            "select count(*) from swiftletcare.users",
            Long.class
        );

        var items = db.query("""
            select id,email,full_name,phone,role,active
            from swiftletcare.users
            order by id desc
            limit ? offset ?
            """,
            accountMapper,
            size,
            (long) page * size
        );

        return new Views.ListView<>(
            items,
            new Views.PageInfo(
                page, size, total,
                Math.toIntExact(Math.ceilDiv(total, size))
            )
        );
    }

    @PatchMapping("/admin/users/{id}/active")
    @Transactional
    public AccountView changeAccountState(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id,
        @Valid @RequestBody AccountState input
    ) {
        long adminId = access.requireGlobalAdmin(jwt);

        // Serialize changes to global admin accounts.
        db.queryForList(
            "select pg_advisory_xact_lock(613,13)"
        );

        var rows = db.query("""
            select id,role,active
            from swiftletcare.users
            where id=? for update
            """,
            (rs, row) -> new ExistingAccount(
                rs.getLong("id"),
                rs.getString("role"),
                rs.getBoolean("active")
            ),
            id
        );

        if (rows.isEmpty()) throw ApiError.missing();

        ExistingAccount target = rows.getFirst();

        if (!input.active() && id == adminId) {
            throw new ApiError.Failure(
                409, "SELF_DEACTIVATION",
                "Admin cannot deactivate their own account"
            );
        }

        if (!input.active()
            && target.active()
            && "ADMIN".equals(target.role())) {

            long remaining = db.queryForObject("""
                select count(*) from swiftletcare.users
                where active and role='ADMIN'
                """, Long.class);

            if (remaining <= 1) {
                throw new ApiError.Failure(
                    409, "LAST_ADMIN",
                    "Cannot deactivate the last global admin"
                );
            }
        }

        if (target.active() != input.active()) {
            db.update("""
                update swiftletcare.users
                set active=?,
                    token_version=token_version+1
                where id=?
                """,
                input.active(), id
            );
        }

        return account(id);
    }

    // ===== PROFILE =====

    @GetMapping("/me")
    public AccountView me(@AuthenticationPrincipal Jwt jwt) {
        return account(access.requireActiveAccount(jwt));
    }

    @PatchMapping("/me")
    @Transactional
    public AccountView updateProfile(
        @AuthenticationPrincipal Jwt jwt,
        @RequestBody Map<String, Object> fields
    ) {
        long id = access.requireActiveAccount(jwt);

        if (fields == null || fields.isEmpty()
            || !Set.of("fullName", "phone")
                .containsAll(fields.keySet())) {
            throw ApiError.bad(
                "Only fullName and phone can be updated"
            );
        }

        if (fields.containsKey("fullName")) {
            db.update("""
                update swiftletcare.users
                set full_name=? where id=?
                """,
                profileField(fields.get("fullName"), 150),
                id
            );
        }

        if (fields.containsKey("phone")) {
            db.update("""
                update swiftletcare.users
                set phone=? where id=?
                """,
                profileField(fields.get("phone"), 30),
                id
            );
        }

        return account(id);
    }

    // ===== PASSWORD AND TOKEN REVOCATION =====

    @PostMapping("/me/change-password")
    @Transactional
    public ResponseEntity<Void> changePassword(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ChangePassword input,
        HttpServletResponse response
    ) {
        long id = access.requireActiveAccount(jwt);

        strongPassword(input.newPassword());

        String currentHash = db.queryForObject("""
            select password_hash
            from swiftletcare.users
            where id=? for update
            """, String.class, id);

        if (!passwords.matches(
                input.currentPassword(), currentHash)) {
            throw new ApiError.Failure(
                401, "INVALID_PASSWORD",
                "Current password is incorrect"
            );
        }

        if (passwords.matches(
                input.newPassword(), currentHash)) {
            throw ApiError.bad(
                "New password must differ from current password"
            );
        }

        db.update("""
            update swiftletcare.users
            set password_hash=?,
                token_version=token_version+1
            where id=?
            """,
            passwords.encode(input.newPassword()), id
        );

        expireWsCookie(response);

        // Existing JWTs are now invalid, including this one.
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/logout")
    @Transactional
    public ResponseEntity<Void> logout(
        @AuthenticationPrincipal Jwt jwt,
        HttpServletResponse response
    ) {
        long id = access.requireActiveAccount(jwt);

        // Logout all sessions because there is no refresh-token
        // or per-device session store in the current system.
        db.update("""
            update swiftletcare.users
            set token_version=token_version+1
            where id=?
            """, id);

        expireWsCookie(response);

        return ResponseEntity.noContent().build();
    }
}
