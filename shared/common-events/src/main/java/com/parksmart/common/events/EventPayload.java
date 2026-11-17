package com.parksmart.common.events;

/** Marker for event payload records. */
public interface EventPayload {

  /** Kafka record key: the ID of the aggregate the event is about (keeps per-aggregate order). */
  String partitionKey();
}
