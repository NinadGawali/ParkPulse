package com.parksmart.review;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReviewServiceApplication {

  public static void main(String[] args) {
    // All services run in UTC; the host zone must not leak into the DB session or logs.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(ReviewServiceApplication.class, args);
  }
}
