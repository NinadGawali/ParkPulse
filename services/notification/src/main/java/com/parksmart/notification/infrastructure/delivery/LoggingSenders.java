package com.parksmart.notification.infrastructure.delivery;

import com.parksmart.notification.application.port.Senders.EmailSender;
import com.parksmart.notification.application.port.Senders.SmsSender;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Local/test stand-ins for SES and an SMS provider: they "send" by logging. Destinations are
 * masked; bodies (which can hold one-time codes) are logged only with dev-log-bodies=true, which
 * the local profile sets and aws forbids. Both are refused under the aws profile.
 */
@Configuration(proxyBeanMethods = false)
class LoggingSenders {

  private static final Logger log = LoggerFactory.getLogger(LoggingSenders.class);

  @Bean
  @ConditionalOnProperty(name = "parksmart.notification.email", havingValue = "log")
  EmailSender loggingEmailSender(
      Environment env, @Value("${parksmart.notification.dev-log-bodies:false}") boolean bodies) {
    refuseOnAws(env, "email");
    return (to, subject, body) ->
        log.info("EMAIL to {}: {}{}", mask(to), subject, bodies ? " | DEV ONLY body: " + body : "");
  }

  @Bean
  @ConditionalOnProperty(name = "parksmart.notification.sms", havingValue = "log")
  SmsSender loggingSmsSender(
      Environment env, @Value("${parksmart.notification.dev-log-bodies:false}") boolean bodies) {
    refuseOnAws(env, "sms");
    return (to, body) ->
        log.info("SMS to {}{}", mask(to), bodies ? " | DEV ONLY body: " + body : "");
  }

  static void refuseOnAws(Environment env, String channel) {
    if (Arrays.asList(env.getActiveProfiles()).contains("aws")) {
      throw new IllegalStateException(
          "The logging " + channel + " sender is not allowed with the aws profile");
    }
  }

  /** a***@example.com, +91******4321 */
  static String mask(String destination) {
    if (destination == null || destination.length() < 4) {
      return "***";
    }
    int at = destination.indexOf('@');
    if (at > 0) {
      return destination.charAt(0) + "***" + destination.substring(at);
    }
    return destination.substring(0, 3)
        + "*".repeat(Math.max(0, destination.length() - 7))
        + destination.substring(destination.length() - 4);
  }
}
