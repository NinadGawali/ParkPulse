package com.parksmart.adminbff.application.port;

import java.util.Objects;

/**
 * What every downstream call carries: the admin's own access token (each service re-checks ADMIN
 * itself) and the request's correlation ID. Passed explicitly because composed views fan out to
 * other threads, where the request's security context and MDC are not available.
 */
public record AdminCall(String accessToken, String correlationId) {

  public AdminCall {
    Objects.requireNonNull(accessToken, "accessToken");
    Objects.requireNonNull(correlationId, "correlationId");
  }

  @Override
  public String toString() {
    // Never print the token.
    return "AdminCall[correlationId=" + correlationId + "]";
  }
}
