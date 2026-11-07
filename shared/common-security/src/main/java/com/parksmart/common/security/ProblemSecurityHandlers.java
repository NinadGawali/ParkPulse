package com.parksmart.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parksmart.common.web.Problems;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Writes 401/403 from the security filter chain as RFC 7807 problems. */
public final class ProblemSecurityHandlers {

  private final ObjectMapper objectMapper;

  public ProblemSecurityHandlers(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public AuthenticationEntryPoint authenticationEntryPoint() {
    return (request, response, ex) ->
        write(
            response,
            Problems.of(
                HttpStatus.UNAUTHORIZED,
                "unauthorized",
                "Not authenticated",
                "A valid access token is required"));
  }

  public AccessDeniedHandler accessDeniedHandler() {
    return (request, response, ex) ->
        write(
            response,
            Problems.of(
                HttpStatus.FORBIDDEN,
                "forbidden",
                "Not allowed",
                "You may not perform this action"));
  }

  private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    if (problem.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
      response.setHeader("WWW-Authenticate", "Bearer");
    }
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
