package com.parksmart.profile.api;

import com.parksmart.common.security.ParkSmartSecurity;
import com.parksmart.common.security.ProblemSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Every API call needs a valid access token; roles and ownership are checked further in. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandlers handlers)
      throws Exception {
    http.authorizeHttpRequests(a ->
        a.requestMatchers(ParkSmartSecurity.publicInfrastructurePaths())
            .permitAll()
            .requestMatchers("/api/v1/**")
            .authenticated()
            .anyRequest()
            .denyAll());
    return ParkSmartSecurity.apply(http, handlers).build();
  }
}
