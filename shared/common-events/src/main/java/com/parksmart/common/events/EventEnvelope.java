package com.parksmart.common.events;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire format of every ParkSmart event.
 *
 * @param eventId unique per event; consumers de-duplicate on it
 * @param eventType e.g. {@code BookingConfirmed}
 * @param version payload schema version
 * @param occurredAt when the state change happened (UTC)
 * @param correlationId request correlation ID propagated from the gateway (never null; generated if
 *     the originating request had none)
 * @param producer name of the publishing service, e.g. {@code booking-service}
 * @param payload the event body
 */
public record EventEnvelope<T extends EventPayload>(
    UUID eventId,
    String eventType,
    int version,
    Instant occurredAt,
    String correlationId,
    String producer,
    T payload) {

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(producer, "producer");
    Objects.requireNonNull(payload, "payload");
  }

  /** Wraps a payload registered in {@link EventCatalog}, stamping a new event ID and time. */
  public static <T extends EventPayload> EventEnvelope<T> wrap(
      T payload, String correlationId, String producer, Clock clock) {
    EventDefinition definition = EventCatalog.forPayload(payload.getClass());
    return new EventEnvelope<>(
        UUID.randomUUID(),
        definition.type(),
        definition.version(),
        clock.instant(),
        correlationId,
        producer,
        payload);
  }

  public String topic() {
    return EventCatalog.forPayload(payload.getClass()).topic();
  }

  public String partitionKey() {
    return payload.partitionKey();
  }
}
