package com.parksmart.common.web;

import java.util.List;
import java.util.function.Function;

/** Response shape for cursor-paginated lists: {@code {items, nextCursor}}. */
public record CursorPage<T>(List<T> items, String nextCursor) {

  public CursorPage {
    items = List.copyOf(items);
  }

  public <R> CursorPage<R> map(Function<T, R> mapper) {
    return new CursorPage<>(items.stream().map(mapper).toList(), nextCursor);
  }
}
