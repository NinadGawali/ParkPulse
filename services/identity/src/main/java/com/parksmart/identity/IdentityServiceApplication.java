package com.parksmart.identity;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IdentityServiceApplication {

  public static void main(String[] args) {
    // All services run in UTC; the host zone must not leak into the DB session or logs.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(IdentityServiceApplication.class, args);
  }
}
