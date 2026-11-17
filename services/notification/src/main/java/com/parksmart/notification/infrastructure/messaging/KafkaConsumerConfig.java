package com.parksmart.notification.infrastructure.messaging;

import com.parksmart.common.events.EventJson.EventSerializationException;
import com.parksmart.common.events.Topics;
import com.parksmart.notification.domain.DomainException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Failed records are retried with backoff, then published to {@code <topic>.DLT} (same partition).
 * Records that cannot be parsed go to the DLT at once. Boot wires this handler into the listener
 * container factory.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfig {

  @Bean
  CommonErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, String> template,
      @Value("${parksmart.kafka.consumer.max-attempts:4}") int maxAttempts) {
    ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
    backOff.setMaxAttempts(maxAttempts - 1);
    // Explicit resolver: the ParkSmart convention is <topic>.DLT, same partition.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, ex) ->
                new TopicPartition(Topics.deadLetterTopic(record.topic()), record.partition()));
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(EventSerializationException.class, DomainException.class);
    return handler;
  }
}
