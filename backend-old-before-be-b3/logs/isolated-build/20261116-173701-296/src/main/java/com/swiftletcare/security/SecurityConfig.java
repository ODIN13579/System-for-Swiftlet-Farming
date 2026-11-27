package com.swiftletcare.security;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

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
  JwtDecoder decoder(SecretKeySpec key) {
    var d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    d.setJwtValidator(JwtValidators.createDefaultWithIssuer("swiftletcare"));
    return d;
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
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health/**",
                        "/api/v1/auth/login",
                        "/api/v1/internal/ai/count-logs")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
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
