package com.parksmart.review.application.port;

import com.parksmart.common.events.EventPayload;

/** Records an event in the transactional outbox; it is published to Kafka after commit. */
public interface EventOutbox {

  /** Must be called inside the transaction that made the state change. */
  void publish(EventPayload payload);
}
