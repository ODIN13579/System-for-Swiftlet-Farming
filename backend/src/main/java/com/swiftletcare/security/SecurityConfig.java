package com.swiftletcare.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
  @Bean
  SecretKeySpec jwtKey(@Value("${app.jwt-secret}") String secret) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
    return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  JwtEncoder encoder(SecretKeySpec key) {
    return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
  }

  
  @Bean
  JwtDecoder decoder(SecretKeySpec key, JdbcTemplate jdbc) {
      var decoder = NimbusJwtDecoder.withSecretKey(key)
          .macAlgorithm(MacAlgorithm.HS256)
          .build();

      OAuth2TokenValidator<Jwt> accountValidator = jwt -> {
          try {
              long accountId = Long.parseLong(jwt.getSubject());
              Object claim = jwt.getClaims().get("token_version");

              // Legacy test tokens issued before B3-13 use version 0.
              long tokenVersion = claim == null
                  ? 0L
                  : claim instanceof Number n
                      ? n.longValue() : -1L;

              if (accountId > 0 && tokenVersion >= 0) {
                  var versions = jdbc.queryForList("""
                      select token_version
                      from swiftletcare.users
                      where id=? and active
                      """, Long.class, accountId);

                  if (versions.size() == 1
                          && versions.getFirst() == tokenVersion) {
                      return OAuth2TokenValidatorResult.success();
                  }
              }
          } catch (RuntimeException ignored) {
              // Fail closed on malformed, revoked or unverifiable tokens.
          }

          return OAuth2TokenValidatorResult.failure(
              new OAuth2Error(
                  "invalid_token",
                  "Account inactive or JWT revoked",
                  null
              )
          );
      };

      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer("swiftletcare"),
              accountValidator
          )
      );

      return decoder;
  }


  @Bean
  BCryptPasswordEncoder passwords() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UrlBasedCorsConfigurationSource corsConfigurationSource(@Value("${app.origins}") String origins) {
    var c = new CorsConfiguration();
    c.setAllowedOrigins(Arrays.asList(origins.split(",")));
    c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "OPTIONS"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Service-Key"));
    c.setAllowCredentials(true);
    var s = new UrlBasedCorsConfigurationSource();
    s.registerCorsConfiguration("/**", c);
    return s;
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity h, UrlBasedCorsConfigurationSource corsConfigurationSource) throws Exception {
    var resolver = new DefaultBearerTokenResolver();
    h.csrf(c -> c.disable())
        .cors(c -> c.configurationSource(corsConfigurationSource))
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))        
        .authorizeHttpRequests(a -> a
          .requestMatchers(
              "/actuator/health/**",
              "/api/v1/auth/login",
              "/api/v1/internal/ai/count-logs"
          ).permitAll()
          // Service key is validated by AiPredatorController.
          .requestMatchers(
              HttpMethod.POST,
              "/api/ai/snapshots",
              "/api/ai/events"
          ).permitAll()
          // Snapshot GET is intentionally NOT public.
          .anyRequest().authenticated()
        )
        .oauth2ResourceServer(
            o ->
                o.bearerTokenResolver(
                        r -> {
                          String token = resolver.resolve(r);
                          if (token == null
                              && r.getRequestURI().startsWith("/ws/")
                              && r.getCookies() != null)
                            for (var c : r.getCookies())
                              if (c.getName().equals("SC_WS")) return c.getValue();
                          return token;
                        })
                    .jwt(j -> {})
                    .authenticationEntryPoint(
                        (r, s, e) -> {
                          s.setStatus(401);
                          s.setContentType("application/json");
                          s.getWriter()
                              .write(
                                  "{\"code\":\"UNAUTHORIZED\",\"message\":\"Valid JWT"
                                      + " required\",\"timestamp\":\""
                                      + java.time.Instant.now()
                                      + "\",\"path\":\""
                                      + r.getRequestURI()
                                      + "\"}");
                        }))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (r, s, x) -> {
                      s.setStatus(403);
                      s.setContentType("application/json");
                      s.getWriter()
                          .write(
                              "{\"code\":\"ACCESS_DENIED\",\"message\":\"Access"
                                  + " denied\",\"timestamp\":\""
                                  + java.time.Instant.now()
                                  + "\",\"path\":\""
                                  + r.getRequestURI()
                                  + "\"}");
                    }));
    return h.build();
  }
}
