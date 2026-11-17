package com.parksmart.common.events.listing;

import java.time.Instant;
import java.util.UUID;

/** One-off unavailable window {@code [start, end)}. */
public record BlackoutData(UUID blackoutId, Instant start, Instant end) {}
