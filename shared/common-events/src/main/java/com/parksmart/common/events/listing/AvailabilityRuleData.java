package com.parksmart.common.events.listing;

/** Weekly rule in the listing's time zone. Times are {@code HH:mm}; {@code 24:00} = end of day. */
public record AvailabilityRuleData(String dayOfWeek, String startTime, String endTime) {}
