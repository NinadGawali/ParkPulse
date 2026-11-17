package com.parksmart.adminbff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.parksmart.common.security.JwtProperties;
import com.parksmart.common.security.ParkSmartJwt;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Base for integration tests: the full HTTP stack via MockMvc, tokens signed by a test key, and
 * every downstream service played by one in-process {@link StubServices} (no Docker needed).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.TestSupport.class)
abstract class IntegrationTest {

  static final RSAKey KEY = generateKey();
  static final String ISSUER = "https://identity.parksmart.test";
  static final StubServices STUB = new StubServices();

  @DynamicPropertySource
  static void downstreams(DynamicPropertyRegistry registry) {
    for (String service : List.of("listing", "profile", "booking", "payment", "review")) {
      registry.add("parksmart.downstream." + service + "-url", STUB::baseUrl);
    }
    registry.add("parksmart.downstream.timeout", () -> "600ms");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestSupport {

    @Bean
    JwtDecoder testJwtDecoder() throws Exception {
      NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
      decoder.setJwtValidator(
          ParkSmartJwt.validator(new JwtProperties(ISSUER, "parksmart-api", null)));
      return decoder;
    }
  }

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper json;

  @BeforeEach
  void resetStub() {
    STUB.reset();
  }

  protected record User(UUID id, String token) {}

  protected static User admin() {
    return user("ADMIN");
  }

  protected static User user(String... roles) {
    UUID id = UUID.randomUUID();
    return new User(id, token(id, Arrays.asList(roles)));
  }

  protected ResultActions get(String path, User user) throws Exception {
    return mvc.perform(authed(MockMvcRequestBuilders.get(path), user));
  }

  protected ResultActions postJson(String path, String body, User user) throws Exception {
    return mvc.perform(
        authed(MockMvcRequestBuilders.post(path), user)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  protected ResultActions perform(MockHttpServletRequestBuilder request, User user)
      throws Exception {
    return mvc.perform(authed(request, user));
  }

  protected JsonNode body(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private static MockHttpServletRequestBuilder authed(
      MockHttpServletRequestBuilder request, User user) {
    return user == null
        ? request
        : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
  }

  static String token(UUID subject, List<String> roles) {
    try {
      Instant now = Instant.now();
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer(ISSUER)
              .audience("parksmart-api")
              .subject(subject.toString())
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(600)))
              .jwtID(UUID.randomUUID().toString())
              .claim("roles", roles)
              .build();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .keyID(KEY.getKeyID())
                  .type(JOSEObjectType.JWT)
                  .build(),
              claims);
      jwt.sign(new RSASSASigner(KEY));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static RSAKey generateKey() {
    try {
      return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
