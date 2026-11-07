package com.parksmart.identity.infrastructure.outbox;

import com.parksmart.common.events.Topics;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares the topics Identity owns (auto-creation is off in every environment). */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

  @Bean
  NewTopic identityAccountTopic(
      @Value("${parksmart.kafka.partitions:3}") int partitions,
      @Value("${parksmart.kafka.replication-factor:1}") short replicas) {
    return TopicBuilder.name(Topics.IDENTITY_ACCOUNT)
        .partitions(partitions)
        .replicas(replicas)
        .build();
  }

  /** Codes and contact details: kept for one day only. */
  @Bean
  NewTopic identityPrivateTopic(
      @Value("${parksmart.kafka.partitions:3}") int partitions,
      @Value("${parksmart.kafka.replication-factor:1}") short replicas) {
    return TopicBuilder.name(Topics.IDENTITY_PRIVATE)
        .partitions(partitions)
        .replicas(replicas)
        .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(1).toMillis()))
        .build();
  }
}
