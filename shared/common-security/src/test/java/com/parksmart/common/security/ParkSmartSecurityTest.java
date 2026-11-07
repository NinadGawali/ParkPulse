package com.parksmart.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    classes = ParkSmartSecurityTest.TestApp.class,
    properties = {
      "parksmart.security.jwt.issuer=https://identity.parksmart.test",
      "parksmart.security.jwt.audience=parksmart-api"
    })
@AutoConfigureMockMvc
class ParkSmartSecurityTest {

  static final String ISSUER = "https://identity.parksmart.test";
  static final RSAKey KEY = generateKey();
  static final RSAKey OTHER_KEY = generateKey();
  static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Autowired MockMvc mvc;

  @Test
  void anonymous_request_to_protected_endpoint_gets_401_problem() throws Exception {
    mvc.perform(get("/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/unauthorized"))
        .andExpect(header().string("WWW-Authenticate", "Bearer"));
  }

  @Test
  void public_endpoint_needs_no_token() throws Exception {
    mvc.perform(get("/public")).andExpect(status().isOk());
  }

  @Test
  void valid_token_exposes_current_account() throws Exception {
    mvc.perform(
            get("/me")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(token(KEY, ISSUER, "parksmart-api", List.of("DRIVER"), 300))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value(ACCOUNT.toString()))
        .andExpect(jsonPath("$.roles[0]").value("DRIVER"));
  }

  @Test
  void missing_role_gets_403_problem() throws Exception {
    mvc.perform(
            get("/host-only")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(token(KEY, ISSUER, "parksmart-api", List.of("DRIVER"), 300))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/forbidden"));
  }

  @Test
  void role_claim_grants_access() throws Exception {
    mvc.perform(
            get("/host-only")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(token(KEY, ISSUER, "parksmart-api", List.of("DRIVER", "HOST"), 300))))
        .andExpect(status().isOk());
  }

  @Test
  void wrong_issuer_is_rejected() throws Exception {
    mvc.perform(
            get("/me")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(
                        token(KEY, "https://evil.test", "parksmart-api", List.of("DRIVER"), 300))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void wrong_audience_is_rejected() throws Exception {
    mvc.perform(
            get("/me")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(token(KEY, ISSUER, "other-api", List.of("DRIVER"), 300))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expired_token_is_rejected() throws Exception {
    mvc.perform(
            get("/me")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(token(KEY, ISSUER, "parksmart-api", List.of("DRIVER"), -120))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void token_signed_with_another_key_is_rejected() throws Exception {
    mvc.perform(
            get("/me")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(token(OTHER_KEY, ISSUER, "parksmart-api", List.of("ADMIN"), 300))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unknown_roles_are_ignored() throws Exception {
    mvc.perform(
            get("/host-only")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(token(KEY, ISSUER, "parksmart-api", List.of("SUPERUSER", "host"), 300))))
        .andExpect(status().isForbidden());
  }

  static String token(
      RSAKey key, String issuer, String audience, List<String> roles, long ttlSeconds) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(ACCOUNT.toString())
            .issuedAt(now.minusSeconds(Math.max(0, -ttlSeconds) + 10))
            .expiresAt(now.plusSeconds(ttlSeconds))
            .claims(c -> c.putAll(Map.of(JwtClaims.ROLES, roles)))
            .build();
    NimbusJwtEncoder encoder =
        new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  static String bearer(String token) {
    return "Bearer " + token;
  }

  static RSAKey generateKey() {
    try {
      return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @SpringBootApplication
  @Import(TestController.class)
  static class TestApp {

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) throws Exception {
      NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
      decoder.setJwtValidator(ParkSmartJwt.validator(properties));
      return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandlers handlers)
        throws Exception {
      http.authorizeHttpRequests(a ->
          a.requestMatchers(ParkSmartSecurity.publicInfrastructurePaths())
              .permitAll()
              .requestMatchers("/public")
              .permitAll()
              .requestMatchers("/host-only")
              .hasRole("HOST")
              .anyRequest()
              .authenticated());
      return ParkSmartSecurity.apply(http, handlers).build();
    }
  }

  @RestController
  static class TestController {
    @GetMapping("/public")
    String publicEndpoint() {
      return "ok";
    }

    @GetMapping("/me")
    Map<String, Object> me() {
      AuthenticatedAccount account = CurrentAccount.require();
      return Map.of("accountId", account.accountId().toString(), "roles", account.roles());
    }

    @GetMapping("/host-only")
    String hostOnly() {
      return "ok";
    }
  }
}
