package com.parksmart.adminbff.application;

import com.parksmart.adminbff.domain.Lookup;
import com.parksmart.common.web.ApiException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Runs secondary lookups of a composed view in parallel and classifies their outcome. A rejected
 * token (401) still fails the whole request; anything else that goes wrong only makes the view
 * partial.
 */
@Component
class ParallelLookups {

  private static final Logger log = LoggerFactory.getLogger(ParallelLookups.class);

  private final Executor executor;

  ParallelLookups(@Qualifier("downstreamExecutor") Executor executor) {
    this.executor = executor;
  }

  <T> CompletableFuture<Lookup<T>> lookup(String what, Supplier<Optional<T>> call) {
    Map<String, String> mdc = MDC.getCopyOfContextMap();
    return CompletableFuture.supplyAsync(
        () -> {
          if (mdc != null) {
            MDC.setContextMap(mdc); // keeps the correlation ID on log lines
          }
          try {
            return classify(what, call);
          } finally {
            MDC.clear();
          }
        },
        executor);
  }

  static <T> Lookup<T> classify(String what, Supplier<Optional<T>> call) {
    try {
      return Lookup.ofNullable(call.get().orElse(null));
    } catch (ApiException e) {
      if (e.status() == HttpStatus.UNAUTHORIZED) {
        throw e;
      }
      log.warn("Lookup of {} failed with {} {}", what, e.status().value(), e.slug());
      return Lookup.failed();
    } catch (RuntimeException e) {
      log.warn("Lookup of {} failed: {}", what, e.getClass().getSimpleName());
      return Lookup.failed();
    }
  }

  /** Waits for a future and rethrows what it failed with (only 401s get here). */
  static <T> T join(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException cause) {
        throw cause;
      }
      throw e;
    }
  }
}
