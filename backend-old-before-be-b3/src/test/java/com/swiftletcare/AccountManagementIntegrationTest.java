
package com.swiftletcare;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(
    named = "DB_TEST_ENABLED", matches = "true"
)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {
        "app.jwt-secret=build104-test-secret-at-least-32-bytes",
        "app.ai-service-key=build104-test-service-key-at-least-24",
        "app.mqtt.enabled=false"
    }
)
class AccountManagementIntegrationTest {

    @Autowired JdbcTemplate db;
    @Autowired BCryptPasswordEncoder bcrypt;
    @Autowired JsonMapper json;

    @LocalServerPort int port;

    @Autowired JwtDecoder decoder;

    final HttpClient http = HttpClient.newHttpClient();

    String adminEmail;
    String ownerEmail;
    String adminToken;
    String ownerToken;
    long adminId;

    static final String PASSWORD = "InitialPass123!";

    HttpResponse<String> request(
        String method,
        String path,
        Object body,
        String token
    ) throws Exception {

        var builder = HttpRequest.newBuilder(
            URI.create(
                "http://localhost:" + port + "/api/v1" + path
            )
        ).header("Content-Type", "application/json");

        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }

        return http.send(
            builder.method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(body)
                    )
            ).build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    String login(String email, String password)
        throws Exception {

        var response = request(
            "POST",
            "/auth/login",
            Map.of(
                "email", email,
                "password", password
            ),
            null
        );

        assertThat(response.statusCode())
            .as(response.body())
            .isEqualTo(200);

        return json.readTree(response.body())
            .get("accessToken").asText();
    }

    long createSeed(String email, String role) {
        return db.queryForObject("""
            insert into swiftletcare.users
                (email,password_hash,role,active)
            values(?,?,?,true)
            returning id
            """,
            Long.class,
            email, bcrypt.encode(PASSWORD), role
        );
    }

    @BeforeEach
    void setup() throws Exception {
        adminEmail = "admin-" + UUID.randomUUID()
            + "@example.test";

        ownerEmail = "owner-" + UUID.randomUUID()
            + "@example.test";

        adminId = createSeed(adminEmail, "ADMIN");
        createSeed(ownerEmail, "OWNER");

        adminToken = login(adminEmail, PASSWORD);
        ownerToken = login(ownerEmail, PASSWORD);
    }

    @Test
    void onlyGlobalAdminCanCreateAndDeactivate()
        
        throws Exception {
        
        long ownerId = Long.parseLong(
            decoder.decode(ownerToken).getSubject()
        );
        String ownerRole = db.queryForObject("""
            SELECT role
            FROM swiftletcare.users
            WHERE id = ?
            """,
            String.class,
            ownerId
        );

        assertThat(ownerRole).isEqualTo("OWNER");
        assertThat(ownerToken).isNotEqualTo(adminToken);

        String email = "new-" + UUID.randomUUID()
            + "@example.test";

        var input = Map.of(
            "email", email,
            "password", "StrongPass123!",
            "role", "OWNER",
            "fullName", "New Account"
        );
        
        var denied = request(
            "POST",
            "/admin/users",
            input,
            ownerToken
        );

        assertThat(denied.statusCode())
            .as("OWNER must not create users. Response: "
                + denied.body())
            .isEqualTo(403);

        var created = request(
            "POST", "/admin/users", input, adminToken
        );

        assertThat(created.statusCode())
            .as(created.body())
            .isEqualTo(201);

        long id = json.readTree(created.body())
            .get("id").asLong();

        assertThat(request(
            "POST", "/admin/users", input, adminToken
        ).statusCode()).isEqualTo(409);

        assertThat(request(
            "GET", "/admin/users", null, adminToken
        ).statusCode()).isEqualTo(200);

        String newToken = login(email, "StrongPass123!");

        assertThat(request(
            "PATCH",
            "/admin/users/" + id + "/active",
            Map.of("active", false),
            adminToken
        ).statusCode()).isEqualTo(200);

        // Old JWT must be rejected immediately.
        assertThat(request(
            "GET", "/me", null, newToken
        ).statusCode()).isEqualTo(401);

        assertThat(request(
            "POST",
            "/auth/login",
            Map.of(
                "email", email,
                "password", "StrongPass123!"
            ),
            null
        ).statusCode()).isEqualTo(401);

        // Re-enable. An old JWT must remain revoked.
        assertThat(request(
            "PATCH",
            "/admin/users/" + id + "/active",
            Map.of("active", true),
            adminToken
        ).statusCode()).isEqualTo(200);

        assertThat(request(
            "GET", "/me", null, newToken
        ).statusCode()).isEqualTo(401);

        assertThat(login(email, "StrongPass123!"))
            .isNotBlank();
    }

    @Test
    void profileCannotModifyRoleAndPasswordRevokesTokens()
        throws Exception {

        assertThat(request(
            "PATCH",
            "/me",
            Map.of(
                "fullName", "Updated Owner",
                "phone", "0900000000"
            ),
            ownerToken
        ).statusCode()).isEqualTo(200);

        var profile = request(
            "GET", "/me", null, ownerToken
        );

        assertThat(profile.statusCode()).isEqualTo(200);

        assertThat(profile.body())
            .contains("Updated Owner", "0900000000");

        // Prevent mass assignment of privileged fields.
        assertThat(request(
            "PATCH",
            "/me",
            Map.of("role", "ADMIN"),
            ownerToken
        ).statusCode()).isEqualTo(400);

        assertThat(request(
            "POST",
            "/me/change-password",
            Map.of(
                "currentPassword", "IncorrectPass123!",
                "newPassword", "UpdatedPass123!"
            ),
            ownerToken
        ).statusCode()).isEqualTo(401);

        assertThat(request(
            "POST",
            "/me/change-password",
            Map.of(
                "currentPassword", PASSWORD,
                "newPassword", "UpdatedPass123!"
            ),
            ownerToken
        ).statusCode()).isEqualTo(204);

        assertThat(request(
            "GET", "/me", null, ownerToken
        ).statusCode()).isEqualTo(401);

        assertThat(request(
            "POST",
            "/auth/login",
            Map.of(
                "email", ownerEmail,
                "password", PASSWORD
            ),
            null
        ).statusCode()).isEqualTo(401);

        String newToken = login(
            ownerEmail, "UpdatedPass123!"
        );

        assertThat(request(
            "GET", "/me", null, newToken
        ).statusCode()).isEqualTo(200);
    }

    @Test
    void logoutRevokesAllExistingSessions()
        throws Exception {

        String token1 = login(ownerEmail, PASSWORD);
        String token2 = login(ownerEmail, PASSWORD);

        assertThat(request(
            "POST", "/auth/logout", null, token1
        ).statusCode()).isEqualTo(204);

        assertThat(request(
            "GET", "/me", null, token1
        ).statusCode()).isEqualTo(401);

        assertThat(request(
            "GET", "/me", null, token2
        ).statusCode()).isEqualTo(401);

        assertThat(request(
            "GET", "/me", null,
            login(ownerEmail, PASSWORD)
        ).statusCode()).isEqualTo(200);
    }

    @Test
    void adminCannotDisableOwnAccount()
        throws Exception {

        assertThat(request(
            "PATCH",
            "/admin/users/" + adminId + "/active",
            Map.of("active", false),
            adminToken
        ).statusCode()).isEqualTo(409);

        assertThat(request(
            "GET", "/me", null, adminToken
        ).statusCode()).isEqualTo(200);
    }

    @Test
    void validatesRoleAndCreatesHouseViewerMembership()
        throws Exception {

        long house = db.queryForObject("""
            insert into swiftletcare.houses(name,active)
            values('B3-13 viewer house',true)
            returning id
            """, Long.class);

        String email = "viewer-" + UUID.randomUUID()
            + "@example.test";

        assertThat(request(
            "POST", "/admin/users",
            Map.of(
                "email", email,
                "password", "StrongPass123!",
                "role", "VIEWER"
            ),
            adminToken
        ).statusCode()).isEqualTo(400);

        var created = request(
            "POST", "/admin/users",
            Map.of(
                "email", email,
                "password", "StrongPass123!",
                "role", "OWNER",
                "houseId", house,
                "houseRole", "VIEWER"
            ),
            adminToken
        );

        assertThat(created.statusCode())
            .as(created.body())
            .isEqualTo(201);

        long newUser = json.readTree(created.body())
            .get("id").asLong();

        assertThat(db.queryForObject("""
            select role
            from swiftletcare.house_memberships
            where user_id=? and house_id=?
            """, String.class, newUser, house))
            .isEqualTo("VIEWER");

        String viewerToken = login(
            email, "StrongPass123!"
        );

        assertThat(request(
            "GET", "/houses/" + house, null, viewerToken
        ).statusCode()).isEqualTo(200);

        // Viewer must not be able to create a Zone.
        assertThat(request(
            "POST", "/houses/" + house + "/zones",
            Map.of("name", "Unauthorized zone"),
            viewerToken
        ).statusCode()).isEqualTo(403);
    }

}
