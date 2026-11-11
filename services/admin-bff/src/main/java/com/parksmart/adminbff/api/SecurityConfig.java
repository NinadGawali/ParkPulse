package com.parksmart.adminbff.api;

import com.parksmart.common.security.ParkSmartSecurity;
import com.parksmart.common.security.ProblemSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything except health probes needs a valid token with the ADMIN role. The application layer
 * checks ADMIN again (hard rule 7), and every downstream service checks it a third time on the
 * forwarded token.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandlers handlers)
      throws Exception {
    http.authorizeHttpRequests(a ->
        a.requestMatchers(ParkSmartSecurity.publicInfrastructurePaths())
            .permitAll()
            .requestMatchers("/admin/v1/**")
            .hasRole("ADMIN")
            .anyRequest()
            .denyAll());
    return ParkSmartSecurity.apply(http, handlers).build();
  }
}
