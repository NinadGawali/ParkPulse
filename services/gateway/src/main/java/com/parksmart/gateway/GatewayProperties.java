package com.parksmart.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code parksmart.gateway.*}.
 *
 * @param jwt expected issuer/audience and where to fetch Identity's keys
 * @param allowedOrigins CORS origins for browser clients (empty = no browser access)
 * @param trustForwardedFor use the first X-Forwarded-For hop as client IP (only behind a trusted
 *     load balancer such as the ALB)
 */
@ConfigurationProperties("parksmart.gateway")
public record GatewayProperties(Jwt jwt, List<String> allowedOrigins, boolean trustForwardedFor) {

  public GatewayProperties {
    if (jwt == null || jwt.issuer() == null || jwt.jwksUri() == null) {
      throw new IllegalStateException("parksmart.gateway.jwt.issuer and jwks-uri must be set");
    }
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }

  public record Jwt(String issuer, String audience, String jwksUri) {
    public Jwt {
      audience = audience == null || audience.isBlank() ? "parksmart-api" : audience;
    }
  }
}
