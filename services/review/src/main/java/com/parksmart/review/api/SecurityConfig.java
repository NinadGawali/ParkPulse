package com.parksmart.review.api;

import com.parksmart.common.security.ParkSmartSecurity;
import com.parksmart.common.security.ProblemSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every call needs a valid access token except reading a single listing (published listings are
 * public). Roles and ownership are checked in the application layer.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandlers handlers)
      throws Exception {
    http.authorizeHttpRequests(a ->
        a.requestMatchers(ParkSmartSecurity.publicInfrastructurePaths())
            .permitAll()
            .requestMatchers(
                org.springframework.http.HttpMethod.GET,
                "/api/v1/reviews",
                "/api/v1/reviews/summary")
            .permitAll()
            .requestMatchers("/api/v1/**", "/admin/v1/**")
            .authenticated()
            .anyRequest()
            .denyAll());
    return ParkSmartSecurity.apply(http, handlers).build();
  }
}
