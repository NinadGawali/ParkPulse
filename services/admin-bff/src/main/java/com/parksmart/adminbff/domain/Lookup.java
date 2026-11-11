package com.parksmart.adminbff.domain;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Outcome of a secondary downstream lookup in a composed view: found, known to be absent (404), or
 * failed (unreachable, slow, failing). A failed lookup makes the view {@code partial}; an absent
 * one does not.
 */
public sealed interface Lookup<T> {

  record Found<T>(T value) implements Lookup<T> {
    public Found {
      Objects.requireNonNull(value, "value");
    }
  }

  record Absent<T>() implements Lookup<T> {}

  record Failed<T>() implements Lookup<T> {}

  static <T> Lookup<T> found(T value) {
    return new Found<>(value);
  }

  static <T> Lookup<T> absent() {
    return new Absent<>();
  }

  static <T> Lookup<T> failed() {
    return new Failed<>();
  }

  /** {@code Optional}-style: absent when {@code value} is null. */
  static <T> Lookup<T> ofNullable(T value) {
    return value == null ? absent() : found(value);
  }

  default T orNull() {
    return this instanceof Found<T> f ? f.value() : null;
  }

  default boolean isFailed() {
    return this instanceof Failed<T>;
  }

  /**
   * Treats a found value that does not belong to the view (e.g. a payment intent of another
   * booking) as a failed lookup: inconsistent data is never shown as if it were correct.
   */
  default Lookup<T> requireThat(Predicate<T> belongs) {
    return this instanceof Found<T> f && !belongs.test(f.value()) ? Lookup.failed() : this;
  }
}
