package com.parksmart.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Real gateway + real Redis; upstream services and Identity's JWKS are a local stub server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIT {

  static final String ISSUER = "https://identity.parksmart.test";
  static final RSAKey KEY = generateKey();
  static final RSAKey OTHER_KEY = generateKey();
  static final Map<String, Map<String, List<String>>> RECEIVED = new ConcurrentHashMap<>();
  static final HttpServer UPSTREAM = startUpstream();

  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.6.8-alpine")).withExposedPorts(6379);

  static {
    REDIS.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String upstream = "http://localhost:" + UPSTREAM.getAddress().getPort();
    for (String service :
        List.of("IDENTITY", "PROFILE", "LISTING", "BOOKING", "PAYMENT", "SEARCH")) {
      registry.add(service + "_URI", () -> upstream);
    }
    registry.add("IDENTITY_ISSUER", () -> ISSUER);
    registry.add("REDIS_HOST", REDIS::getHost);
    registry.add("REDIS_PASSWORD", () -> "");
  }

  @AfterAll
  static void stopUpstream() {
    UPSTREAM.stop(0);
  }

  @Autowired WebTestClient client;

  @Test
  void public_endpoint_is_proxied_without_a_token_and_gets_a_correlation_id() {
    client
        .post()
        .uri("/api/v1/auth/refresh")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Correlation-Id")
        .expectHeader()
        .exists("X-Content-Type-Options");
    Map<String, List<String>> headers = RECEIVED.get("/api/v1/auth/refresh");
    assertThat(headers).containsKey("X-correlation-id");
  }

  @Test
  void client_correlation_id_is_forwarded_and_unsafe_ones_replaced() {
    client
        .get()
        .uri("/api/v1/bookings/" + UUID.randomUUID())
        .header("Authorization", "Bearer " + token(KEY, ISSUER, List.of("DRIVER")))
        .header("X-Correlation-Id", "client-abc")
        .exchange()
        .expectHeader()
        .valueEquals("X-Correlation-Id", "client-abc");

    client
        .post()
        .uri("/api/v1/auth/refresh")
        .header("X-Correlation-Id", "bad id with spaces")
        .exchange()
        .expectHeader()
        .value("X-Correlation-Id", value -> assertThat(value).doesNotContain(" "));
  }

  @Test
  void protected_endpoint_without_token_is_rejected_at_the_edge() {
    RECEIVED.remove("/api/v1/accounts/me");
    client
        .get()
        .uri("/api/v1/accounts/me")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("https://parksmart.dev/problems/unauthorized");
    assertThat(RECEIVED).doesNotContainKey("/api/v1/accounts/me");
  }

  @Test
  void valid_token_is_forwarded_to_the_service() {
    String token = token(KEY, ISSUER, List.of("DRIVER"));
    client
        .get()
        .uri("/api/v1/accounts/me")
        .header("Authorization", "Bearer " + token)
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(RECEIVED.get("/api/v1/accounts/me").get("Authorization"))
        .containsExactly("Bearer " + token);
  }

  @Test
  void forged_or_foreign_tokens_are_rejected() {
    client
        .get()
        .uri("/api/v1/accounts/me")
        .header("Authorization", "Bearer " + token(OTHER_KEY, ISSUER, List.of("ADMIN")))
        .exchange()
        .expectStatus()
        .isUnauthorized();
    client
        .get()
        .uri("/api/v1/accounts/me")
        .header("Authorization", "Bearer " + token(KEY, "https://evil.test", List.of("ADMIN")))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void internal_paths_are_never_routed() {
    client
        .get()
        .uri("/internal/v1/listings/x/booking-quote")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    client
        .get()
        .uri("/internal/v1/listings/x/booking-quote")
        .header("Authorization", "Bearer " + token(KEY, ISSUER, List.of("ADMIN")))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(RECEIVED.keySet()).noneMatch(path -> path.startsWith("/internal"));
  }

  @Test
  void login_is_rate_limited_per_ip() {
    int allowed = 0;
    HttpStatus last = null;
    for (int i = 0; i < 7; i++) {
      last =
          HttpStatus.valueOf(
              client
                  .post()
                  .uri("/api/v1/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .bodyValue("{}")
                  .exchange()
                  .returnResult(String.class)
                  .getStatus()
                  .value());
      if (last == HttpStatus.OK) {
        allowed++;
      }
    }
    assertThat(allowed).isEqualTo(5);
    assertThat(last).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void health_is_public() {
    client.get().uri("/actuator/health").exchange().expectStatus().isOk();
  }

  static String token(RSAKey key, String issuer, List<String> roles) {
    try {
      Instant now = Instant.now();
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .audience("parksmart-api")
              .subject(UUID.randomUUID().toString())
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(300)))
              .claim("roles", roles)
              .build();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .keyID(key.getKeyID())
                  .type(JOSEObjectType.JWT)
                  .build(),
              claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static RSAKey generateKey() {
    try {
      return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Echo server: records request headers per path; also serves the JWKS. */
  static HttpServer startUpstream() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      byte[] jwks = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      server.createContext(
          "/",
          exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if (path.equals("/.well-known/jwks.json")) {
              body = jwks;
            } else {
              RECEIVED.put(path, Map.copyOf(exchange.getRequestHeaders()));
              body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
