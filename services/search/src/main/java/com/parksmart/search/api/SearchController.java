package com.parksmart.search.api;

import com.parksmart.common.web.Cursor;
import com.parksmart.common.web.CursorPage;
import com.parksmart.common.web.Problems;
import com.parksmart.search.application.SearchService;
import com.parksmart.search.application.SearchService.Hit;
import com.parksmart.search.application.port.ListingSearchIndex.Indexed;
import com.parksmart.search.domain.DomainException;
import com.parksmart.search.domain.SearchQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, read-only search (docs/openapi/search.yaml). */
@RestController
class SearchController {

  private final SearchService search;

  SearchController(SearchService search) {
    this.search = search;
  }

  record Location(double lat, double lng) {}

  record Result(
      UUID listingId,
      String title,
      String address,
      Location location,
      Double distanceKm,
      String timeZone,
      String pricePerHour,
      String currency,
      String estimatedTotal,
      String maxVehicleType,
      List<String> amenities,
      boolean instantBook,
      String cancellationPolicy,
      int bufferMinutes,
      String thumbnailUrl,
      String ratingAvg,
      int ratingCount,
      Instant lastUpdatedFromEventAt) {

    static Result of(Indexed i, BigDecimal estimate) {
      return new Result(
          i.listingId(),
          i.title(),
          i.address(),
          new Location(i.lat(), i.lng()),
          i.distanceKm() == null ? null : Math.round(i.distanceKm() * 1000) / 1000.0,
          i.timeZone(),
          i.pricePerHour().toPlainString(),
          i.currency(),
          estimate == null ? null : estimate.toPlainString(),
          i.maxVehicleType(),
          i.amenities(),
          i.instantBook(),
          i.cancellationPolicy(),
          i.bufferMinutes(),
          i.thumbnailUrl(),
          i.ratingAvg() == null ? null : i.ratingAvg().toPlainString(),
          i.ratingCount(),
          i.lastEventAt());
    }
  }

  @GetMapping("/api/v1/search/listings")
  CursorPage<Result> search(
      @RequestParam double lat,
      @RequestParam double lng,
      @RequestParam(defaultValue = "5") double radiusKm,
      @RequestParam(required = false) Instant startTime,
      @RequestParam(required = false) Instant endTime,
      @RequestParam(required = false) String vehicleType,
      @RequestParam(required = false) String maxPricePerHour,
      @RequestParam(required = false) String amenities,
      @RequestParam(required = false) Boolean instantBook,
      @RequestParam(defaultValue = "distance") String sort,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    SearchQuery query =
        new SearchQuery(
            lat,
            lng,
            radiusKm,
            startTime,
            endTime,
            vehicleType == null ? null : SearchQuery.vehicleRank(vehicleType),
            price(maxPricePerHour),
            amenities(amenities),
            instantBook,
            sort(sort),
            Cursor.limit(limit));
    CursorPage<Hit> page = search.search(query, cursor);
    return page.map(h -> Result.of(h.listing(), h.estimatedTotal()));
  }

  @GetMapping("/api/v1/search/listings/{listingId}")
  Result get(@PathVariable UUID listingId) {
    return Result.of(search.get(listingId), null);
  }

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> invalid(DomainException e) {
    return ResponseEntity.badRequest()
        .body(Problems.of(HttpStatus.BAD_REQUEST, e.code(), "Invalid search", e.getMessage()));
  }

  private static BigDecimal price(String value) {
    if (value == null) {
      return null;
    }
    if (!value.matches("\\d{1,8}(\\.\\d{1,2})?")) {
      throw new DomainException("invalid-search", "maxPricePerHour must be a money amount");
    }
    return new BigDecimal(value);
  }

  private static Set<String> amenities(String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    if (csv.length() > 200) {
      throw new DomainException("invalid-search", "too many amenities");
    }
    return Arrays.stream(csv.split(","))
        .map(s -> s.strip().toUpperCase(Locale.ROOT))
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }

  private static SearchQuery.Sort sort(String value) {
    try {
      return SearchQuery.Sort.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new DomainException("invalid-search", "sort must be distance or price");
    }
  }
}
