package com.parksmart.common.web;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** Access to the request correlation ID, stored in the logging MDC under {@link #MDC_KEY}. */
public final class CorrelationId {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  private CorrelationId() {}

  /** The current request's correlation ID, or {@code null} outside a request. */
  public static String current() {
    return MDC.get(MDC_KEY);
  }

  /** The current ID, or a new one when there is none (e.g. scheduled jobs). */
  public static String currentOrNew() {
    String id = current();
    return id != null ? id : newId();
  }

  /** Accepts a client-supplied ID only if it is safe to log; otherwise generates one. */
  public static String sanitize(String candidate) {
    return candidate != null && VALID.matcher(candidate).matches() ? candidate : newId();
  }

  public static String newId() {
    return UUID.randomUUID().toString();
  }
}
