package com.parksmart.adminbff.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parksmart.adminbff.application.port.AdminCall;
import com.parksmart.adminbff.application.port.Downstream;
import com.parksmart.adminbff.domain.Facts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** docs/openapi/profile.yaml: the public profile only (no contact data, vehicles or payouts). */
@Component
class HttpProfiles implements Downstream.Profiles {

  private final ServiceClient client;

  HttpProfiles(RestClient.Builder builder, ObjectMapper json, DownstreamProperties props) {
    this.client = new ServiceClient("profile", builder, json, props.profileUrl(), props.timeout());
  }

  record PublicProfileBody(
      UUID accountId, String displayName, String photoUrl, String ratingAvg, Integer ratingCount) {}

  @Override
  public Optional<Facts.Party> find(AdminCall call, UUID accountId) {
    return client
        .find(call, PublicProfileBody.class, "/api/v1/profiles/{id}", accountId)
        .map(b ->
            client.convert(() ->
                new Facts.Party(
                    b.accountId(),
                    b.displayName(),
                    b.photoUrl(),
                    b.ratingAvg(),
                    b.ratingCount() == null ? 0 : b.ratingCount())));
  }
}
