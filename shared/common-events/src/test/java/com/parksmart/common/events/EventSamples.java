package com.parksmart.common.events;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Builds a fully populated sample instance of any event record, for round-trip tests. */
final class EventSamples {

  private EventSamples() {}

  static <T> T sample(Class<T> type) {
    if (!type.isRecord()) {
      throw new IllegalArgumentException(type + " is not a record");
    }
    RecordComponent[] components = type.getRecordComponents();
    Object[] args = Arrays.stream(components).map(c -> value(c.getGenericType())).toArray();
    Class<?>[] types =
        Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
    try {
      Constructor<T> constructor = type.getDeclaredConstructor(types);
      return constructor.newInstance(args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot build sample " + type.getName(), e);
    }
  }

  private static Object value(Type genericType) {
    if (genericType instanceof ParameterizedType parameterized
        && parameterized.getRawType() == List.class) {
      return List.of(value(parameterized.getActualTypeArguments()[0]));
    }
    Class<?> type = (Class<?>) genericType;
    if (type == String.class) {
      return "sample";
    }
    if (type == UUID.class) {
      return UUID.fromString("00000000-0000-4000-8000-000000000001");
    }
    if (type == Instant.class) {
      return Instant.parse("2026-11-07T10:15:30Z");
    }
    if (type == LocalDate.class) {
      return LocalDate.parse("2026-11-07");
    }
    if (type == BigDecimal.class) {
      return new BigDecimal("123.45");
    }
    if (type == int.class) {
      return 7;
    }
    if (type == double.class) {
      return 18.5204;
    }
    if (type == boolean.class) {
      return true;
    }
    if (type.isRecord()) {
      return sample(type);
    }
    throw new IllegalArgumentException("No sample value for " + type);
  }
}
