package com.parksmart.adminbff.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parksmart.adminbff.application.port.AdminCall;
import com.parksmart.adminbff.application.port.Downstream;
import com.parksmart.adminbff.domain.Facts;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** docs/openapi/review.yaml: admin hide. The review's comment is not carried. */
@Component
class HttpReviews implements Downstream.Reviews {

  private final ServiceClient client;

  HttpReviews(RestClient.Builder builder, ObjectMapper json, DownstreamProperties props) {
    this.client = new ServiceClient("review", builder, json, props.reviewUrl(), props.timeout());
  }

  record ReviewBody(
      UUID id, UUID bookingId, String direction, String targetType, UUID targetId, int rating) {}

  record HideBody(String reason) {}

  @Override
  public Facts.Review hide(AdminCall call, UUID reviewId, String reason) {
    ReviewBody b =
        client.post(
            call,
            new HideBody(reason),
            Map.of(),
            ReviewBody.class,
            "/admin/v1/reviews/{id}/hide",
            reviewId);
    return client.convert(() ->
        new Facts.Review(
            b.id(), b.bookingId(), b.direction(), b.targetType(), b.targetId(), b.rating()));
  }
}
