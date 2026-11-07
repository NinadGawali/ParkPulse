package com.parksmart.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Edge authentication. Public endpoints pass without a token; everything else needs a valid
 * Identity-issued RS256 token. Services validate the token again (defence in depth) and enforce
 * roles and ownership themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {

  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http, CorrelationIdFilter correlationIdFilter) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .cors(cors -> {})
        .addFilterAt(correlationIdFilter, SecurityWebFiltersOrder.FIRST)
        .authorizeExchange(a ->
            a.pathMatchers("/actuator/health", "/actuator/health/**")
                .permitAll()
                .pathMatchers("/internal/**")
                .denyAll()
                .pathMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll()
                .pathMatchers(HttpMethod.GET, "/.well-known/jwks.json")
                .permitAll()
                .pathMatchers(
                    HttpMethod.POST,
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/password/forgot",
                    "/api/v1/auth/password/reset")
                .permitAll()
                .pathMatchers(HttpMethod.GET, "/api/v1/listings/*", "/api/v1/search/**")
                .permitAll()
                .pathMatchers("/api/v1/**", "/admin/v1/**")
                .authenticated()
                .anyExchange()
                .denyAll())
        .exceptionHandling(e ->
            e.authenticationEntryPoint((exchange, ex) ->
                    problem(
                        exchange,
                        HttpStatus.UNAUTHORIZED,
                        "unauthorized",
                        "Not authenticated",
                        "A valid access token is required"))
                .accessDeniedHandler((exchange, ex) ->
                    problem(
                        exchange,
                        HttpStatus.NOT_FOUND,
                        "not-found",
                        "Resource not found",
                        "No such resource")))
        .oauth2ResourceServer(rs ->
            rs.authenticationEntryPoint((exchange, ex) ->
                    problem(
                        exchange,
                        HttpStatus.UNAUTHORIZED,
                        "unauthorized",
                        "Not authenticated",
                        "A valid access token is required"))
                .jwt(jwt -> {}))
        .build();
  }

  @Bean
  ReactiveJwtDecoder jwtDecoder(GatewayProperties properties) {
    NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwt().jwksUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(validator(properties.jwt()));
    return decoder;
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(properties.allowedOrigins());
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(
        List.of(
            "Authorization", "Content-Type", "Idempotency-Key", "If-Match", "X-Correlation-Id"));
    cors.setExposedHeaders(List.of("ETag", "Location", "Retry-After", "X-Correlation-Id"));
    cors.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    return source;
  }

  /** Same rules as shared/common-security's ParkSmartJwt (that module is servlet-only). */
  static OAuth2TokenValidator<Jwt> validator(GatewayProperties.Jwt jwt) {
    OAuth2TokenValidator<Jwt> audience =
        token ->
            token.getAudience() != null && token.getAudience().contains(jwt.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Wrong audience", null));
    OAuth2TokenValidator<Jwt> subject =
        token -> {
          try {
            UUID.fromString(token.getSubject());
            return OAuth2TokenValidatorResult.success();
          } catch (RuntimeException e) {
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Subject must be an account ID", null));
          }
        };
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(jwt.issuer()), audience, subject);
  }

  static Mono<Void> problem(
      ServerWebExchange exchange, HttpStatus status, String slug, String title, String detail) {
    var response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    if (status == HttpStatus.UNAUTHORIZED) {
      response.getHeaders().set("WWW-Authenticate", "Bearer");
    }
    String correlationId = exchange.getAttribute(CorrelationIdFilter.ATTRIBUTE);
    String body =
        "{\"type\":\"https://parksmart.dev/problems/%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"%s}"
            .formatted(
                slug,
                title,
                status.value(),
                detail,
                correlationId == null ? "" : ",\"correlationId\":\"" + correlationId + "\"");
    DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }
}
