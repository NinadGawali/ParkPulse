package com.parksmart.notification;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests: one Postgres+PostGIS and one Kafka container shared by every test
 * class, real HTTP stack via MockMvc, tokens signed by a test key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.TestSupport.class)
public abstract class IntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.7").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("notification");

  @ServiceConnection static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");

  static final RSAKey KEY = generateKey();
  static final String ISSUER = "https://identity.parksmart.test";

  static {
    POSTGRES.start();
    KAFKA.start();
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
  @Autowired protected JdbcClient jdbc;

  /** A caller with a fresh account ID. */
  protected record User(UUID id, String token) {}

  protected static User driver() {
    return user("DRIVER");
  }

  protected static User host() {
    return user("DRIVER", "HOST");
  }

  protected static User user(String... roles) {
    UUID id = UUID.randomUUID();
    return new User(id, token(id, Arrays.asList(roles)));
  }

  protected ResultActions get(String path, User user) throws Exception {
    return mvc.perform(authed(MockMvcRequestBuilders.get(path), user));
  }

  protected ResultActions delete(String path, User user) throws Exception {
    return mvc.perform(authed(MockMvcRequestBuilders.delete(path), user));
  }

  protected ResultActions post(String path, User user) throws Exception {
    return mvc.perform(authed(MockMvcRequestBuilders.post(path), user));
  }

  protected ResultActions postJson(String path, Object body, User user) throws Exception {
    return mvc.perform(
        authed(MockMvcRequestBuilders.post(path), user)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  protected ResultActions putJson(String path, Object body, User user) throws Exception {
    return mvc.perform(
        authed(MockMvcRequestBuilders.put(path), user)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
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

  /** Outbox events of a type whose payload field {@code field} equals {@code value}. */
  protected List<JsonNode> events(String eventType, String field, Object value) {
    return jdbc
        .sql(
            """
            SELECT payload::text FROM outbox_events
            WHERE event_type = :type AND payload->'payload'->>:field = :value
            ORDER BY created_at, id
            """)
        .param("type", eventType)
        .param("field", field)
        .param("value", value.toString())
        .query(String.class)
        .list()
        .stream()
        .map(this::readTree)
        .toList();
  }

  private JsonNode readTree(String text) {
    try {
      return json.readTree(text);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
