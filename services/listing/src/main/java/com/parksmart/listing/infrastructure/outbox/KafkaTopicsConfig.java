package com.parksmart.listing.infrastructure.outbox;

import com.parksmart.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares the topic Listing owns and the dead-letter topics of the topics it consumes
 * (auto-creation is off in every environment). DLTs keep the source partition count.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

  @Bean
  KafkaAdmin.NewTopics listingTopics(
      @Value("${parksmart.kafka.partitions:3}") int partitions,
      @Value("${parksmart.kafka.replication-factor:1}") short replicas) {
    return new KafkaAdmin.NewTopics(
        topic(Topics.LISTING, partitions, replicas),
        topic(Topics.deadLetterTopic(Topics.IDENTITY_ACCOUNT), partitions, replicas),
        topic(Topics.deadLetterTopic(Topics.PROFILE), partitions, replicas),
        topic(Topics.deadLetterTopic(Topics.BOOKING), partitions, replicas),
        topic(Topics.deadLetterTopic(Topics.PAYMENT), partitions, replicas));
  }

  private static NewTopic topic(String name, int partitions, short replicas) {
    return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
  }
}
