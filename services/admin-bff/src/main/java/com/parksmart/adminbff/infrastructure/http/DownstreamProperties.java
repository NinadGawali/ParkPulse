package com.parksmart.adminbff.infrastructure.http;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code parksmart.downstream.*}: base URLs of the owning services and the connect/read timeout
 * applied to every call.
 */
@Validated
@ConfigurationProperties("parksmart.downstream")
public record DownstreamProperties(
    @NotNull Duration timeout,
    @NotNull URI listingUrl,
    @NotNull URI profileUrl,
    @NotNull URI bookingUrl,
    @NotNull URI paymentUrl,
    @NotNull URI reviewUrl) {}
