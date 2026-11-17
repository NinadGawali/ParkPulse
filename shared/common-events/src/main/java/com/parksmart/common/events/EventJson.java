package com.parksmart.common.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Serializes envelopes to JSON and back. Money ({@link BigDecimal}) is written as a string to avoid
 * float rounding; timestamps are ISO-8601 UTC; unknown fields are ignored (tolerant reader).
 */
public final class EventJson {

  private static final ObjectMapper MAPPER = createMapper();

  private EventJson() {}

  /** The configured mapper; do not reconfigure it. */
  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static String serialize(EventEnvelope<?> envelope) {
    try {
      return MAPPER.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      throw new EventSerializationException("Cannot serialize " + envelope.eventType(), e);
    }
  }

  /** Parses an envelope, resolving the payload record from its eventType and version. */
  public static EventEnvelope<EventPayload> deserialize(String json) {
    try {
      JsonNode root = MAPPER.readTree(json);
      String type = requiredText(root, "eventType");
      int version = root.path("version").asInt(0);
      EventDefinition definition =
          EventCatalog.find(type, version)
              .orElseThrow(() ->
                  new EventSerializationException("Unknown event " + type + " v" + version, null));
      JsonNode payloadNode = root.get("payload");
      if (payloadNode == null || !payloadNode.isObject()) {
        throw new EventSerializationException("Missing envelope field payload", null);
      }
      EventPayload payload = MAPPER.treeToValue(payloadNode, definition.payloadClass());
      return new EventEnvelope<>(
          UUID.fromString(requiredText(root, "eventId")),
          type,
          version,
          Instant.parse(requiredText(root, "occurredAt")),
          requiredText(root, "correlationId"),
          requiredText(root, "producer"),
          payload);
    } catch (JsonProcessingException | RuntimeException e) {
      if (e instanceof EventSerializationException ese) {
        throw ese;
      }
      throw new EventSerializationException("Cannot deserialize event", e);
    }
  }

  private static String requiredText(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || !node.isTextual()) {
      throw new EventSerializationException("Missing envelope field " + field, null);
    }
    return node.asText();
  }

  private static ObjectMapper createMapper() {
    JsonMapper mapper =
        JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    mapper
        .configOverride(BigDecimal.class)
        .setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING));
    return mapper;
  }

  /** Thrown when an event cannot be (de)serialized; consumers route these to the DLT. */
  public static final class EventSerializationException extends RuntimeException {
    public EventSerializationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
