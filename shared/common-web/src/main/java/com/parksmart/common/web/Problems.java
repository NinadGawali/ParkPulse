package com.parksmart.common.web;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds ParkSmart problem details with the standard type URI and correlation ID. */
public final class Problems {

  public static final String TYPE_BASE = "https://parksmart.dev/problems/";

  private Problems() {}

  public static ProblemDetail of(HttpStatusCode status, String slug, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(TYPE_BASE + slug));
    problem.setTitle(title);
    String correlationId = CorrelationId.current();
    if (correlationId != null) {
      problem.setProperty("correlationId", correlationId);
    }
    return problem;
  }
}
