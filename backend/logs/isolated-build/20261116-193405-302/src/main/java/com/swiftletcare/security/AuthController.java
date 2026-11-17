package com.swiftletcare.security;

import com.swiftletcare.api.ApiError;
import com.swiftletcare.management.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
  private final UserRepository users;
  private final BCryptPasswordEncoder passwords;
  private final JwtEncoder encoder;
  private final boolean secure;

  public AuthController(
      UserRepository users,
      BCryptPasswordEncoder passwords,
      JwtEncoder encoder,
      @Value("${app.secure-cookie:false}") boolean secure) {
    this.users = users;
    this.passwords = passwords;
    this.encoder = encoder;
    this.secure = secure;
  }

  public record Login(@NotBlank @Email String email, @NotBlank @Size(max = 200) String password) {}

  @PostMapping("/api/v1/auth/login")
  public Map<String, Object> login(@Valid @RequestBody Login login, HttpServletResponse response) {
    var u =
        users
            .findByEmailAndActiveTrue(login.email())
            .filter(x -> passwords.matches(login.password(), x.passwordHash))
            .orElseThrow(() -> new ApiError.Failure(401, "UNAUTHORIZED", "Invalid credentials"));
    var now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer("swiftletcare")
            .subject(u.id.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .build();
    var token =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from("SC_WS", token)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path("/ws")
            .maxAge(Duration.ofSeconds(900))
            .build()
            .toString());
    return Map.of("accessToken", token, "tokenType", "Bearer", "expiresIn", 900);
  }
}
