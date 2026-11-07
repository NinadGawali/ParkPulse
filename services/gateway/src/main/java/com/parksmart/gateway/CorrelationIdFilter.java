package com.parksmart.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Accepts a safe client {@code X-Correlation-Id} or creates one, forwards it to the service and
 * returns it to the client. Runs as a WebFilter (before security) so 401s carry it too.
 */
@Component
class CorrelationIdFilter implements WebFilter, GlobalFilter, Ordered {

  static final String HEADER = "X-Correlation-Id";
  static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
  private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
    String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : newId();
    exchange.getAttributes().put(ATTRIBUTE, id);
    exchange.getResponse().getHeaders().set(HEADER, id);
    ServerHttpRequest request =
        exchange.getRequest().mutate().headers(h -> h.set(HEADER, id)).build();
    return chain.filter(exchange.mutate().request(request).build());
  }

  /** Re-applies the ID on the proxied request (route filters may rebuild headers). */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String id = exchange.getAttribute(ATTRIBUTE);
    if (id == null) {
      return chain.filter(exchange);
    }
    ServerHttpRequest request =
        exchange.getRequest().mutate().headers(h -> h.set(HEADER, id)).build();
    return chain.filter(exchange.mutate().request(request).build());
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }
}
