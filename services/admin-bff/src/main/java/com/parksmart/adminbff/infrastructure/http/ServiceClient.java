package com.parksmart.adminbff.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parksmart.adminbff.application.port.AdminCall;
import com.parksmart.common.web.ApiException;
import com.parksmart.common.web.CorrelationId;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP access to one downstream service with the admin's token forwarded.
 *
 * <ul>
 *   <li>Connect and read timeouts; redirects are never followed (the token must only reach the
 *       configured service); a GET is retried once on a connection error or timeout.
 *   <li>Downstream 4xx problems pass through (status, ParkSmart type slug, title, detail); 401
 *       becomes a plain 401; anything else (5xx, timeouts, redirects, unreadable bodies) becomes
 *       503 {@code <service>-unavailable} with no downstream details.
 * </ul>
 */
public class ServiceClient {

  private static final Logger log = LoggerFactory.getLogger(ServiceClient.class);
  private static final String TYPE_BASE = "https://parksmart.dev/problems/";
  private static final Pattern SLUG = Pattern.compile("[a-z0-9-]{1,64}");
  private static final int MAX_TEXT = 500;

  private final String service;
  private final RestClient rest;
  private final ObjectMapper json;

  public ServiceClient(
      String service,
      RestClient.Builder builder,
      ObjectMapper json,
      URI baseUrl,
      Duration timeout) {
    this.service = service;
    this.json = json;
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(timeout);
    this.rest =
        builder
            .clone()
            .baseUrl(baseUrl.toString())
            .requestFactory(factory)
            .defaultStatusHandler(
                HttpStatusCode::is3xxRedirection,
                (request, response) -> {
                  throw new RedirectRefused();
                })
            .build();
  }

  /** GET that must find something; 404 passes through as a problem. */
  public <T> T get(AdminCall call, Class<T> type, String uri, Object... vars) {
    return find(call, type, uri, vars)
        .orElseThrow(() ->
            new ApiException(
                HttpStatus.NOT_FOUND, "not-found", "Resource not found", "No such resource"));
  }

  /** GET where 404 means "absent" rather than an error. */
  public <T> Optional<T> find(AdminCall call, Class<T> type, String uri, Object... vars) {
    try {
      return Optional.of(getOnce(call, type, uri, vars));
    } catch (ResourceAccessException first) {
      log.warn("{} GET failed ({}); retrying once", service, first.getClass().getSimpleName());
      try {
        return Optional.of(getOnce(call, type, uri, vars));
      } catch (ResourceAccessException second) {
        throw unavailable();
      } catch (NotFound e) {
        return Optional.empty();
      }
    } catch (NotFound e) {
      return Optional.empty();
    }
  }

  /** POST with an optional JSON body and extra headers. Never retried. */
  public <T> T post(
      AdminCall call,
      Object body,
      Map<String, String> headers,
      Class<T> type,
      String uri,
      Object... vars) {
    try {
      RestClient.RequestBodySpec spec =
          rest.post().uri(uri, vars).headers(h -> standardHeaders(h, call));
      headers.forEach(spec::header);
      if (body != null) {
        spec.contentType(MediaType.APPLICATION_JSON).body(body);
      }
      return require(spec.retrieve().body(type));
    } catch (RestClientResponseException e) {
      throw mapped(e);
    } catch (ResourceAccessException | RedirectRefused e) {
      log.warn("{} POST failed: {}", service, e.getClass().getSimpleName());
      throw unavailable();
    } catch (RestClientException e) {
      log.warn("{} POST response unreadable: {}", service, e.getClass().getSimpleName());
      throw unavailable();
    }
  }

  private <T> T getOnce(AdminCall call, Class<T> type, String uri, Object... vars) {
    try {
      return require(
          rest.get()
              .uri(uri, vars)
              .headers(h -> standardHeaders(h, call))
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .body(type));
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
        throw new NotFound();
      }
      throw mapped(e);
    } catch (ResourceAccessException e) {
      throw e; // retried by the caller
    } catch (RedirectRefused e) {
      log.warn("{} answered with a redirect; refused", service);
      throw unavailable();
    } catch (RestClientException e) {
      log.warn("{} GET response unreadable: {}", service, e.getClass().getSimpleName());
      throw unavailable();
    }
  }

  /**
   * Maps a downstream body to facts; a body that breaks the contract (missing IDs, bad money) is
   * the downstream's fault, so 503 rather than 500.
   */
  public <T> T convert(Supplier<T> mapping) {
    try {
      return mapping.get();
    } catch (RuntimeException e) {
      log.warn("{} response broke the contract: {}", service, e.getClass().getSimpleName());
      throw unavailable();
    }
  }

  private static void standardHeaders(HttpHeaders headers, AdminCall call) {
    headers.setBearerAuth(call.accessToken());
    headers.set(CorrelationId.HEADER, call.correlationId());
  }

  private <T> T require(T body) {
    if (body == null) {
      log.warn("{} returned an empty body", service);
      throw unavailable();
    }
    return body;
  }

  /** Body of a downstream RFC 7807 problem; anything else in it is ignored. */
  record DownstreamProblem(String type, String title, String detail) {}

  ApiException mapped(RestClientResponseException e) {
    int code = e.getStatusCode().value();
    if (code == HttpStatus.UNAUTHORIZED.value()) {
      return ApiException.unauthorized("The access token was rejected");
    }
    HttpStatus status = HttpStatus.resolve(code);
    if (status == null || !status.is4xxClientError() || status == HttpStatus.REQUEST_TIMEOUT) {
      log.warn("{} answered {}", service, code);
      return unavailable();
    }
    DownstreamProblem problem = parse(e.getResponseBodyAsByteArray());
    String slug = slugOf(problem.type(), status);
    String title = clean(problem.title(), status.getReasonPhrase());
    String detail = clean(problem.detail(), status.getReasonPhrase());
    return new ApiException(status, slug, title, detail);
  }

  private DownstreamProblem parse(byte[] body) {
    if (body == null || body.length == 0) {
      return new DownstreamProblem(null, null, null);
    }
    try {
      return json.readValue(body, DownstreamProblem.class);
    } catch (Exception ex) {
      return new DownstreamProblem(null, null, null);
    }
  }

  private static String slugOf(String type, HttpStatus status) {
    if (type != null && type.startsWith(TYPE_BASE)) {
      String slug = type.substring(TYPE_BASE.length());
      if (SLUG.matcher(slug).matches()) {
        return slug;
      }
    }
    return switch (status) {
      case BAD_REQUEST -> "bad-request";
      case FORBIDDEN -> "forbidden";
      case NOT_FOUND -> "not-found";
      case CONFLICT -> "conflict";
      case PRECONDITION_FAILED -> "precondition-failed";
      case UNPROCESSABLE_ENTITY -> "unprocessable";
      case TOO_MANY_REQUESTS -> "rate-limited";
      default -> "error";
    };
  }

  /** Bounded, control-character-free text (it ends up in admin screens and logs). */
  private static String clean(String text, String fallback) {
    if (text == null || text.isBlank()) {
      return fallback;
    }
    String cleaned = text.replaceAll("\\p{Cntrl}", " ").strip();
    return cleaned.length() > MAX_TEXT ? cleaned.substring(0, MAX_TEXT) : cleaned;
  }

  ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        service + "-unavailable",
        "Service unavailable",
        "The " + service + " service is not reachable; try again shortly");
  }

  /** 404 on a GET; turned into "absent" by {@link #find}. */
  private static final class NotFound extends RuntimeException {
    NotFound() {
      super(null, null, false, false);
    }
  }

  private static final class RedirectRefused extends RuntimeException {
    RedirectRefused() {
      super(null, null, false, false);
    }
  }
}
