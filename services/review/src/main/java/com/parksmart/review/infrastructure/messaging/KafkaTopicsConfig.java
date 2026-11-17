package com.parksmart.review.infrastructure.messaging;

import com.parksmart.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** The topic Review publishes to, and the dead-letter topic of the one it consumes. */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

  @Bean
  KafkaAdmin.NewTopics reviewTopics(
      @Value("${parksmart.kafka.partitions:3}") int partitions,
      @Value("${parksmart.kafka.replication-factor:1}") short replicas) {
    return new KafkaAdmin.NewTopics(
        topic(Topics.REVIEW, partitions, replicas),
        topic(Topics.deadLetterTopic(Topics.BOOKING), partitions, replicas));
  }

  private static NewTopic topic(String name, int partitions, short replicas) {
    return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
  }
}
