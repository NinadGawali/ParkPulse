package com.parksmart.identity.api;

import com.parksmart.common.security.ParkSmartSecurity;
import com.parksmart.common.security.ProblemSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandlers handlers)
      throws Exception {
    http.authorizeHttpRequests(a ->
        a.requestMatchers(ParkSmartSecurity.publicInfrastructurePaths())
            .permitAll()
            .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json")
            .permitAll()
            .requestMatchers(
                HttpMethod.POST,
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/password/forgot",
                "/api/v1/auth/password/reset")
            .permitAll()
            .requestMatchers("/api/v1/**")
            .authenticated()
            .anyRequest()
            .denyAll());
    return ParkSmartSecurity.apply(http, handlers).build();
  }
}
