package com.parksmart.notification.infrastructure.messaging;

import com.parksmart.common.events.Topics;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * DLTs of every consumed topic. The private topic's DLT keeps the source's 24-hour retention (it
 * can hold one-time codes).
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

  @Bean
  KafkaAdmin.NewTopics notificationDeadLetterTopics(
      @Value("${parksmart.kafka.partitions:3}") int partitions,
      @Value("${parksmart.kafka.replication-factor:1}") short replicas) {
    List<NewTopic> topics =
        new java.util.ArrayList<>(
            List.of(
                    Topics.IDENTITY_ACCOUNT,
                    Topics.PROFILE,
                    Topics.BOOKING,
                    Topics.PAYMENT,
                    Topics.PAYOUT,
                    Topics.LISTING,
                    Topics.REVIEW)
                .stream()
                .map(t ->
                    TopicBuilder.name(Topics.deadLetterTopic(t))
                        .partitions(partitions)
                        .replicas(replicas)
                        .build())
                .toList());
    topics.add(
        TopicBuilder.name(Topics.deadLetterTopic(Topics.IDENTITY_PRIVATE))
            .partitions(partitions)
            .replicas(replicas)
            .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(1).toMillis()))
            .build());
    return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
  }
}
