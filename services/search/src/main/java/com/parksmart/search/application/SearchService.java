package com.parksmart.search.application;

import com.parksmart.common.web.ApiException;
import com.parksmart.common.web.CursorPage;
import com.parksmart.search.application.port.ListingSearchIndex;
import com.parksmart.search.application.port.ListingSearchIndex.After;
import com.parksmart.search.application.port.ListingSearchIndex.Indexed;
import com.parksmart.search.domain.Availability;
import com.parksmart.search.domain.Availability.Rule;
import com.parksmart.search.domain.SearchQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a search: the index filters geo, price, vehicle, amenities, blackouts and known bookings in
 * SQL; weekly rules (time-zone aware) are checked here. Pages keep fetching until they are full, so
 * a page is only short at the end of the results.
 */
@Service
public class SearchService {

  private static final int MAX_BATCHES = 10;

  private final ListingSearchIndex index;

  public SearchService(ListingSearchIndex index) {
    this.index = index;
  }

  /** A search result plus the estimated total for the window, if any. */
  public record Hit(Indexed listing, BigDecimal estimatedTotal) {}

  @Transactional(readOnly = true)
  public CursorPage<Hit> search(SearchQuery query, String cursor) {
    After after = decode(cursor, query.sort());
    int batch = Math.max(20, query.limit() * 2);
    List<Hit> hits = new ArrayList<>();
    After last = after;
    for (int round = 0; round < MAX_BATCHES; round++) {
      List<Indexed> candidates = index.candidates(query, last, batch);
      Map<UUID, List<Rule>> rules =
          query.hasWindow()
              ? index.rules(candidates.stream().map(Indexed::listingId).toList())
              : Map.of();
      for (Indexed c : candidates) {
        last = new After(c.distanceKm(), c.pricePerHour(), c.listingId());
        if (query.hasWindow()
            && !Availability.covers(
                rules.getOrDefault(c.listingId(), List.of()),
                ZoneId.of(c.timeZone()),
                query.start(),
                query.end())) {
          continue;
        }
        hits.add(new Hit(c, estimate(c, query)));
        if (hits.size() == query.limit()) {
          boolean more =
              candidates.indexOf(c) < candidates.size() - 1 || candidates.size() == batch;
          return new CursorPage<>(hits, more ? encode(last, query.sort()) : null);
        }
      }
      if (candidates.size() < batch) {
        return new CursorPage<>(hits, null);
      }
    }
    // Many listings in a row did not fit the window: return what we have and let the client go on.
    return new CursorPage<>(hits, encode(last, query.sort()));
  }

  @Transactional(readOnly = true)
  public Indexed get(UUID listingId) {
    return index.findVisible(listingId).orElseThrow(() -> ApiException.notFound("Listing"));
  }

  private static BigDecimal estimate(Indexed c, SearchQuery q) {
    if (!q.hasWindow()) {
      return null;
    }
    long minutes = Duration.between(q.start(), q.end()).toMinutes();
    return c.pricePerHour()
        .multiply(BigDecimal.valueOf(minutes))
        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
  }

  // ---------------------------------------------------------------- cursor

  static String encode(After a, SearchQuery.Sort sort) {
    String raw =
        sort.name() + "~" + a.distanceKm() + "~" + a.price().toPlainString() + "~" + a.listingId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static After decode(String cursor, SearchQuery.Sort sort) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("~");
      if (parts.length != 4 || !parts[0].equals(sort.name())) {
        throw new IllegalArgumentException("cursor for another sort");
      }
      double distance = Double.parseDouble(parts[1]);
      if (!(distance >= 0 && distance <= SearchQuery.MAX_RADIUS_KM)) {
        throw new IllegalArgumentException("distance out of range");
      }
      return new After(distance, new BigDecimal(parts[2]), UUID.fromString(parts[3]));
    } catch (RuntimeException e) {
      throw ApiException.badRequest("invalid-cursor", "Invalid cursor", "The cursor is malformed");
    }
  }
}
