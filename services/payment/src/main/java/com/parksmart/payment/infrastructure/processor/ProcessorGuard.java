package com.parksmart.payment.infrastructure.processor;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Refuses to start with the fake processor under the aws profile (it confirms free bookings). */
@Component
class ProcessorGuard {

  ProcessorGuard(Environment env, @Value("${parksmart.payment.processor}") String processor) {
    check(Arrays.asList(env.getActiveProfiles()).contains("aws"), processor);
  }

  static void check(boolean aws, String processor) {
    if (aws && "fake".equalsIgnoreCase(processor)) {
      throw new IllegalStateException(
          "parksmart.payment.processor=fake is not allowed with the aws profile");
    }
  }
}
