package com.parksmart.adminbff;

import java.util.UUID;

/** Downstream response bodies shaped like the owning services' contracts. */
final class Fixtures {

  private Fixtures() {}

  static String summary(UUID id, String title) {
    return """
    {"id":"%s","title":"%s","status":"PENDING_REVIEW","thumbnailUrl":null,
     "pricePerHour":"60.00","currency":"INR"}\
    """
        .formatted(id, title);
  }

  static String page(String nextCursor, String... items) {
    return "{\"items\":["
        + String.join(",", items)
        + "],\"nextCursor\":"
        + (nextCursor == null ? "null" : "\"" + nextCursor + "\"")
        + "}";
  }

  static String listing(UUID id, UUID host, String status) {
    return """
    {"id":"%s","hostAccountId":"%s","status":"%s","title":"Covered spot",
     "description":null,"address":"12 MG Road, Pune","location":{"lat":18.5,"lng":73.8},
     "timeZone":"Asia/Kolkata","pricePerHour":"60.00","currency":"INR",
     "maxVehicleType":"SUV","amenities":[],"instantBook":true,
     "cancellationPolicy":"MODERATE","bufferMinutes":15,
     "photos":[{"id":"%s","url":"https://cdn.test/a.jpg","sortOrder":0,"status":"READY"},
               {"id":"%s","url":"https://cdn.test/b.jpg","sortOrder":1,"status":"READY"}],
     "availabilityRules":[{"dayOfWeek":"MONDAY","startTime":"08:00","endTime":"20:00"}],
     "blackouts":[],"rejectionReason":null,
     "createdAt":"2030-01-01T09:00:00Z","updatedAt":"2030-01-02T09:00:00Z"}\
    """
        .formatted(id, host, status, UUID.randomUUID(), UUID.randomUUID());
  }

  /** Includes fields the BFF must never pass on (as if Profile ever leaked them). */
  static String profile(UUID account, String name) {
    return """
    {"accountId":"%s","displayName":"%s","photoUrl":null,"ratingAvg":"4.50","ratingCount":4,
     "email":"leak@example.com","phone":"+919999999999"}\
    """
        .formatted(account, name);
  }

  static String booking(UUID id, UUID listing, UUID driver, UUID host) {
    return """
    {"id":"%s","listingId":"%s","driverAccountId":"%s","hostAccountId":"%s",
     "vehicleId":"%s","status":"CONFIRMED",
     "startTime":"2030-02-01T10:00:00Z","endTime":"2030-02-01T12:00:00Z",
     "pricePerHour":"60.00","totalAmount":"120.00","currency":"INR","instantBook":true,
     "cancellationPolicy":"MODERATE","bufferMinutes":15,"paymentDueBy":null,
     "hostDecisionDueBy":null,"refundAmount":null,
     "createdAt":"2030-01-20T10:00:00Z","updatedAt":"2030-01-20T10:05:00Z"}\
    """
        .formatted(id, listing, driver, host, UUID.randomUUID());
  }

  static String intent(UUID id, UUID booking, String status, String refunded) {
    return """
    {"id":"%s","bookingId":"%s","amount":"120.00","currency":"INR","status":"%s",
     "failureCode":null,"refundedAmount":"%s",
     "createdAt":"2030-01-20T10:00:00Z","updatedAt":"2030-01-20T10:06:00Z"}\
    """
        .formatted(id, booking, status, refunded);
  }

  static String review(UUID id) {
    return """
    {"id":"%s","bookingId":"%s","direction":"DRIVER_TO_LISTING","targetType":"LISTING",
     "targetId":"%s","rating":1,"comment":"call me on +919999999999",
     "createdAt":"2030-01-20T10:00:00Z"}\
    """
        .formatted(id, UUID.randomUUID(), UUID.randomUUID());
  }

  static String problem(int status, String slug, String detail) {
    return """
    {"type":"https://parksmart.dev/problems/%s","title":"Downstream title","status":%d,
     "detail":"%s"}\
    """
        .formatted(slug, status, detail);
  }
}
