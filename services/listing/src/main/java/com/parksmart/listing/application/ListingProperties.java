package com.parksmart.listing.application;

import com.parksmart.listing.domain.Values.PriceBounds;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code parksmart.listing.*}.
 *
 * @param minPricePerHour lowest hourly price a host may set
 * @param maxPricePerHour highest hourly price a host may set
 * @param uploadUrlTtl how long a presigned photo upload URL stays valid
 * @param maxPhotoBytes largest accepted photo
 */
@ConfigurationProperties("parksmart.listing")
public record ListingProperties(
    BigDecimal minPricePerHour,
    BigDecimal maxPricePerHour,
    Duration uploadUrlTtl,
    Long maxPhotoBytes) {

  public ListingProperties {
    if (minPricePerHour == null) {
      minPricePerHour = new BigDecimal("5.00");
    }
    if (maxPricePerHour == null) {
      maxPricePerHour = new BigDecimal("2000.00");
    }
    if (uploadUrlTtl == null) {
      uploadUrlTtl = Duration.ofMinutes(10);
    }
    if (maxPhotoBytes == null) {
      maxPhotoBytes = 10L * 1024 * 1024;
    }
  }

  public PriceBounds priceBounds() {
    return new PriceBounds(minPricePerHour, maxPricePerHour);
  }
}
