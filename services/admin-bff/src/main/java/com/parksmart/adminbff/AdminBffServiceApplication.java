package com.parksmart.adminbff;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AdminBffServiceApplication {

  public static void main(String[] args) {
    // All services run in UTC; the host zone must not leak into logs or timestamps.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(AdminBffServiceApplication.class, args);
  }
}
