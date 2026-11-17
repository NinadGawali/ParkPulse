package com.parksmart.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests: one Postgres+PostGIS and one Kafka container shared by every test
 * class (singleton containers), real HTTP stack via MockMvc.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.7").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("identity");

  @ServiceConnection static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");

  static {
    POSTGRES.start();
    KAFKA.start();
  }

  protected static final String PASSWORD = "correct horse 42";

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper json;
  @Autowired protected JdbcClient jdbc;

  protected static String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  protected ResultActions postJson(String path, Object body) throws Exception {
    return mvc.perform(
        post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
  }

  protected ResultActions postJson(String path, Object body, String accessToken) throws Exception {
    return mvc.perform(
        authed(post(path), accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  protected static MockHttpServletRequestBuilder authed(
      MockHttpServletRequestBuilder request, String accessToken) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
  }

  protected UUID register(String email) throws Exception {
    String body =
        postJson("/api/v1/auth/register", new Register(email, PASSWORD, "2026-01"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(body).get("id").asText());
  }

  protected JsonNode login(String email, String password) throws Exception {
    String body =
        postJson("/api/v1/auth/login", new Login(email, password))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body);
  }

  /** Registers, verifies the email, and logs in. Returns the login response. */
  protected JsonNode activeUser(String email) throws Exception {
    UUID id = register(email);
    String token = login(email, PASSWORD).get("accessToken").asText();
    postJson("/api/v1/auth/email/verify", new Code(latestCode(id, "EMAIL_VERIFY")), token)
        .andExpect(status().isNoContent());
    return login(email, PASSWORD);
  }

  /** Reads the code Identity would have delivered (the relay is off in tests). */
  protected String latestCode(UUID accountId, String purpose) {
    return jdbc.sql(
            """
            SELECT payload->'payload'->>'code' FROM outbox_events
            WHERE event_type = 'VerificationCodeIssued'
              AND payload->'payload'->>'accountId' = :id
              AND payload->'payload'->>'purpose' = :purpose
            ORDER BY created_at DESC, id DESC LIMIT 1
            """)
        .param("id", accountId.toString())
        .param("purpose", purpose)
        .query(String.class)
        .single();
  }

  protected long countEvents(UUID accountId, String eventType) {
    return jdbc.sql(
            """
            SELECT count(*) FROM outbox_events
            WHERE event_type = :type AND payload->'payload'->>'accountId' = :id
            """)
        .param("type", eventType)
        .param("id", accountId.toString())
        .query(Long.class)
        .single();
  }

  protected record Register(String email, String password, String acceptedTermsVersion) {}

  protected record Login(String email, String password) {}

  protected record Refresh(String refreshToken) {}

  protected record Code(String code) {}

  protected record Phone(String phone) {}
}
