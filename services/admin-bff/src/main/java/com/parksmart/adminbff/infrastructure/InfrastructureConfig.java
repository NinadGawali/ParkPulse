package com.parksmart.adminbff.infrastructure;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InfrastructureConfig {

  /**
   * Runs the parallel downstream lookups of composed views. Virtual threads: the work is blocking
   * HTTP with short timeouts, so one cheap thread per lookup is enough.
   */
  @Bean(destroyMethod = "close")
  ExecutorService downstreamExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
