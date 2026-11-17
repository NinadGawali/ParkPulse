package com.parksmart.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Problem-writing security handlers and, when a JWKS URI is configured, the JWT decoder. */
@AutoConfiguration(
    before = OAuth2ResourceServerAutoConfiguration.class,
    after = JacksonAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(JwtProperties.class)
public class CommonSecurityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ProblemSecurityHandlers problemSecurityHandlers(ObjectMapper objectMapper) {
    return new ProblemSecurityHandlers(objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(JwtDecoder.class)
  @ConditionalOnProperty("parksmart.security.jwt.jwks-uri")
  public JwtDecoder parkSmartJwtDecoder(JwtProperties properties) {
    return ParkSmartJwt.jwksDecoder(properties);
  }
}
