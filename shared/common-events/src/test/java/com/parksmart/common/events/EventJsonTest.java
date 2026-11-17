package com.parksmart.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.parksmart.common.events.booking.BookingRequested;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EventJsonTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);

  static Stream<EventDefinition> definitions() {
    return EventCatalog.ALL.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("definitions")
  void every_event_round_trips_through_json(EventDefinition definition) {
    EventPayload payload = EventSamples.sample(definition.payloadClass());
    EventEnvelope<EventPayload> envelope = EventEnvelope.wrap(payload, "corr-1", "test", CLOCK);

    String json = EventJson.serialize(envelope);
    EventEnvelope<EventPayload> parsed = EventJson.deserialize(json);

    assertThat(parsed).isEqualTo(envelope);
    assertThat(parsed.eventType()).isEqualTo(definition.type());
    assertThat(parsed.topic()).isEqualTo(definition.topic());
    assertThat(parsed.partitionKey()).isNotBlank();
  }

  @Test
  void money_is_written_as_a_string_and_time_as_iso8601() throws Exception {
    BookingRequested payload = EventSamples.sample(BookingRequested.class);
    JsonNode json =
        EventJson.mapper()
            .readTree(EventJson.serialize(EventEnvelope.wrap(payload, "c", "test", CLOCK)));

    assertThat(json.at("/payload/totalAmount").isTextual()).isTrue();
    assertThat(json.at("/payload/totalAmount").asText()).isEqualTo("123.45");
    assertThat(json.get("occurredAt").asText()).isEqualTo("2026-11-07T12:00:00Z");
  }

  @Test
  void unknown_payload_fields_are_ignored() {
    BookingRequested payload = EventSamples.sample(BookingRequested.class);
    String json = EventJson.serialize(EventEnvelope.wrap(payload, "c", "test", CLOCK));
    String withExtraField = json.replace("\"payload\":{", "\"payload\":{\"addedLater\":42,");

    assertThat(EventJson.deserialize(withExtraField).payload()).isEqualTo(payload);
  }

  @Test
  void unknown_event_type_is_rejected() {
    BookingRequested payload = EventSamples.sample(BookingRequested.class);
    String json =
        EventJson.serialize(EventEnvelope.wrap(payload, "c", "test", CLOCK))
            .replace("\"BookingRequested\"", "\"NoSuchEvent\"");

    assertThatThrownBy(() -> EventJson.deserialize(json))
        .isInstanceOf(EventJson.EventSerializationException.class)
        .hasMessageContaining("NoSuchEvent");
  }

  @Test
  void malformed_json_is_rejected_with_serialization_exception() {
    assertThatThrownBy(() -> EventJson.deserialize("{not json"))
        .isInstanceOf(EventJson.EventSerializationException.class);
    assertThatThrownBy(
            () -> EventJson.deserialize("{\"eventType\":\"BookingRequested\",\"version\":1}"))
        .isInstanceOf(EventJson.EventSerializationException.class);
  }
}
