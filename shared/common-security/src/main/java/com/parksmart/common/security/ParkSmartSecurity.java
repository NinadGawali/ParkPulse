package com.parksmart.common.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;

/**
 * Applies the standard stateless JWT resource-server setup. Each service still declares its own
 * {@code authorizeHttpRequests} rules (public vs. role-protected paths), for example:
 *
 * <pre>{@code
 * http.authorizeHttpRequests(a -> a
 *         .requestMatchers(HttpMethod.GET, "/api/v1/listings/*").permitAll()
 *         .anyRequest().authenticated());
 * ParkSmartSecurity.apply(http, handlers);
 * return http.build();
 * }</pre>
 *
 * Health probes under {@code /actuator/health/**} are always public.
 */
public final class ParkSmartSecurity {

  private ParkSmartSecurity() {}

  public static HttpSecurity apply(HttpSecurity http, ProblemSecurityHandlers handlers)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(e ->
            e.authenticationEntryPoint(handlers.authenticationEntryPoint())
                .accessDeniedHandler(handlers.accessDeniedHandler()))
        .oauth2ResourceServer(rs ->
            rs.authenticationEntryPoint(handlers.authenticationEntryPoint())
                .accessDeniedHandler(handlers.accessDeniedHandler())
                .jwt(
                    jwt -> jwt.jwtAuthenticationConverter(ParkSmartJwt.authenticationConverter())));
  }

  /** Paths every service leaves open (health probes). Put these first in the rules. */
  public static String[] publicInfrastructurePaths() {
    return new String[] {"/actuator/health", "/actuator/health/**"};
  }
}
