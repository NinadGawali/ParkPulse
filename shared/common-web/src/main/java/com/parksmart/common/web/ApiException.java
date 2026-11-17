package com.parksmart.common.web;

import org.springframework.http.HttpStatus;

/**
 * Thrown by application services for an expected failure that maps to an RFC 7807 response. {@code
 * slug} becomes the problem type {@code https://parksmart.dev/problems/<slug>}.
 */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String slug;
  private final String title;

  public ApiException(HttpStatus status, String slug, String title, String detail) {
    super(detail);
    this.status = status;
    this.slug = slug;
    this.title = title;
  }

  public static ApiException badRequest(String slug, String title, String detail) {
    return new ApiException(HttpStatus.BAD_REQUEST, slug, title, detail);
  }

  public static ApiException unauthorized(String detail) {
    return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Not authenticated", detail);
  }

  public static ApiException forbidden(String detail) {
    return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Not allowed", detail);
  }

  public static ApiException notFound(String what) {
    return new ApiException(
        HttpStatus.NOT_FOUND, "not-found", "Resource not found", what + " not found");
  }

  public static ApiException conflict(String slug, String title, String detail) {
    return new ApiException(HttpStatus.CONFLICT, slug, title, detail);
  }

  public static ApiException unprocessable(String slug, String title, String detail) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, slug, title, detail);
  }

  public static ApiException tooManyRequests(String detail) {
    return new ApiException(
        HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests", detail);
  }

  public HttpStatus status() {
    return status;
  }

  public String slug() {
    return slug;
  }

  public String title() {
    return title;
  }
}
