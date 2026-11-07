package com.parksmart.identity.api.dto;

import com.parksmart.identity.application.AccountView;
import com.parksmart.identity.application.Tokens;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies of docs/openapi/identity.yaml. */
public final class Responses {

  private Responses() {}

  public record TokenResponse(
      String accessToken, String refreshToken, String tokenType, long expiresIn) {

    public static TokenResponse of(Tokens tokens) {
      return new TokenResponse(
          tokens.accessToken(), tokens.refreshToken(), "Bearer", tokens.expiresInSeconds());
    }

    @Override
    public String toString() {
      return "TokenResponse[expiresIn=" + expiresIn + "]";
    }
  }

  public record AccountSummary(
      UUID id,
      String email,
      String phone,
      boolean emailVerified,
      boolean phoneVerified,
      String status,
      List<String> roles,
      Instant createdAt) {

    public static AccountSummary of(AccountView view) {
      return new AccountSummary(
          view.id(),
          view.email(),
          view.phone(),
          view.emailVerified(),
          view.phoneVerified(),
          view.status().name(),
          view.roles().stream().map(Enum::name).toList(),
          view.createdAt());
    }
  }
}
