package com.parksmart.adminbff.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parksmart.adminbff.application.port.AdminCall;
import com.parksmart.adminbff.application.port.Downstream;
import com.parksmart.adminbff.domain.Facts;
import com.parksmart.common.web.CursorPage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** docs/openapi/listing.yaml: admin list, approve/reject, and GET of one listing (admin view). */
@Component
class HttpListings implements Downstream.Listings {

  private final ServiceClient client;

  HttpListings(RestClient.Builder builder, ObjectMapper json, DownstreamProperties props) {
    this.client = new ServiceClient("listing", builder, json, props.listingUrl(), props.timeout());
  }

  record SummaryBody(
      UUID id,
      String title,
      String status,
      String thumbnailUrl,
      String pricePerHour,
      String currency) {}

  record PageBody(List<SummaryBody> items, String nextCursor) {}

  record ListingBody(
      UUID id,
      UUID hostAccountId,
      String status,
      String title,
      String address,
      List<Object> photos,
      List<Object> availabilityRules,
      String rejectionReason,
      Instant updatedAt) {}

  record Decision(String reason) {}

  @Override
  public CursorPage<Facts.ListingRow> pendingReview(AdminCall call, String cursor, int limit) {
    PageBody page =
        cursor == null
            ? client.get(
                call,
                PageBody.class,
                "/admin/v1/listings?status=PENDING_REVIEW&limit={limit}",
                limit)
            : client.get(
                call,
                PageBody.class,
                "/admin/v1/listings?status=PENDING_REVIEW&limit={limit}&cursor={cursor}",
                limit,
                cursor);
    return client.convert(() ->
        new CursorPage<>(
            page.items().stream()
                .map(s ->
                    new Facts.ListingRow(
                        s.id(),
                        s.title(),
                        s.status(),
                        new BigDecimal(s.pricePerHour()),
                        s.currency(),
                        s.thumbnailUrl()))
                .toList(),
            page.nextCursor()));
  }

  @Override
  public Optional<Facts.Listing> find(AdminCall call, UUID listingId) {
    return client
        .find(call, ListingBody.class, "/api/v1/listings/{id}", listingId)
        .map(body -> client.convert(() -> facts(body)));
  }

  @Override
  public Facts.Listing approve(AdminCall call, UUID listingId, String reason) {
    ListingBody body =
        client.post(
            call,
            reason == null ? null : new Decision(reason),
            Map.of(),
            ListingBody.class,
            "/admin/v1/listings/{id}/approve",
            listingId);
    return client.convert(() -> facts(body));
  }

  @Override
  public Facts.Listing reject(AdminCall call, UUID listingId, String reason) {
    ListingBody body =
        client.post(
            call,
            new Decision(reason),
            Map.of(),
            ListingBody.class,
            "/admin/v1/listings/{id}/reject",
            listingId);
    return client.convert(() -> facts(body));
  }

  private static Facts.Listing facts(ListingBody b) {
    return new Facts.Listing(
        b.id(),
        b.hostAccountId(),
        b.title(),
        b.status(),
        b.address(),
        b.photos() == null ? 0 : b.photos().size(),
        b.availabilityRules() == null ? 0 : b.availabilityRules().size(),
        b.rejectionReason(),
        b.updatedAt());
  }
}
