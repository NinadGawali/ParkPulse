package com.parksmart.gateway;

import java.net.InetSocketAddress;
import java.security.Principal;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Keys for the Redis token-bucket rate limiter (limits are set per route in application.yml).
 * Authenticated requests are limited per account, anonymous ones per client IP.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

  @Bean
  @Primary
  KeyResolver principalOrIpKeyResolver(GatewayProperties properties) {
    return exchange ->
        exchange
            .getPrincipal()
            .map(Principal::getName)
            .map(name -> "account:" + name)
            .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange, properties)));
  }

  @Bean
  KeyResolver ipKeyResolver(GatewayProperties properties) {
    return exchange -> Mono.just("ip:" + clientIp(exchange, properties));
  }

  static String clientIp(ServerWebExchange exchange, GatewayProperties properties) {
    if (properties.trustForwardedFor()) {
      String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
        return forwarded.split(",")[0].trim();
      }
    }
    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
    return remote == null || remote.getAddress() == null
        ? "unknown"
        : remote.getAddress().getHostAddress();
  }
}
