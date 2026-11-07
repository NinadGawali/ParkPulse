package com.parksmart.payment.infrastructure.outbox;

import com.parksmart.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** The topics Payment owns and the DLT of the topic it consumes. */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

  @Bean
  KafkaAdmin.NewTopics paymentTopics(
      @Value("${parksmart.kafka.partitions:3}") int partitions,
      @Value("${parksmart.kafka.replication-factor:1}") short replicas) {
    return new KafkaAdmin.NewTopics(
        topic(Topics.PAYMENT, partitions, replicas),
        topic(Topics.PAYOUT, partitions, replicas),
        topic(Topics.deadLetterTopic(Topics.BOOKING), partitions, replicas));
  }

  private static NewTopic topic(String name, int partitions, short replicas) {
    return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
  }
}
