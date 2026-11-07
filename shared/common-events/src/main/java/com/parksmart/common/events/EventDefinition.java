package com.parksmart.common.events;

import java.util.Objects;

/** Registration of one event type/version: where it is published and which record carries it. */
public record EventDefinition(
    String type, int version, String topic, Class<? extends EventPayload> payloadClass) {

  public EventDefinition {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(payloadClass, "payloadClass");
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1");
    }
  }

  /** Resource path of the payload JSON Schema, relative to the classpath root. */
  public String schemaResource() {
    return "event-schemas/" + topic + "/" + type + ".v" + version + ".schema.json";
  }
}
