package com.parksmart.search.infrastructure.messaging;

import com.parksmart.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Search owns no topic; it declares the DLTs of the topics it consumes. */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

  @Bean
  KafkaAdmin.NewTopics searchDeadLetterTopics(
      @Value("${parksmart.kafka.partitions:3}") int partitions,
      @Value("${parksmart.kafka.replication-factor:1}") short replicas) {
    return new KafkaAdmin.NewTopics(
        dlt(Topics.LISTING, partitions, replicas),
        dlt(Topics.BOOKING, partitions, replicas),
        dlt(Topics.PAYMENT, partitions, replicas),
        dlt(Topics.REVIEW, partitions, replicas));
  }

  private static NewTopic dlt(String topic, int partitions, short replicas) {
    return TopicBuilder.name(Topics.deadLetterTopic(topic))
        .partitions(partitions)
        .replicas(replicas)
        .build();
  }
}
