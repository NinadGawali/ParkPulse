package com.parksmart.adminbff.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of docs/openapi/admin-bff.yaml. */
public final class Requests {

  private Requests() {}

  /** Optional note on approve. */
  public record ModerationNote(@Size(min = 1, max = 500) String reason) {}

  /** Required reason on reject (shown to the host). */
  public record ModerationReason(@NotBlank @Size(max = 500) String reason) {}

  public record HideReason(@NotBlank @Size(max = 255) String reason) {}

  public record Refund(
      @NotNull @Pattern(regexp = "\\d{1,8}(\\.\\d{1,2})?", message = "must be a decimal amount")
          String amount,
      @NotBlank @Size(max = 255) String reason) {}
}
