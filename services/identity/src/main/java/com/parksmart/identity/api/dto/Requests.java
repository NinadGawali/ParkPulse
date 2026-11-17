package com.parksmart.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of docs/openapi/identity.yaml. */
public final class Requests {

  private Requests() {}

  public record RegisterRequest(
      @NotBlank @Email @Size(max = 255) String email,
      @NotBlank @Size(min = 10, max = 128) String password,
      @NotBlank @Size(max = 20) String acceptedTermsVersion) {

    @Override
    public String toString() {
      return "RegisterRequest[email=" + email + "]";
    }
  }

  public record LoginRequest(
      @NotBlank @Email @Size(max = 255) String email, @NotBlank @Size(max = 128) String password) {

    @Override
    public String toString() {
      return "LoginRequest[email=" + email + "]";
    }
  }

  public record RefreshRequest(@NotBlank @Size(min = 20, max = 512) String refreshToken) {

    @Override
    public String toString() {
      return "RefreshRequest[***]";
    }
  }

  public record VerifyCodeRequest(@NotBlank @Pattern(regexp = "^\\d{6}$") String code) {

    @Override
    public String toString() {
      return "VerifyCodeRequest[***]";
    }
  }

  public record PhoneVerificationRequest(
      @NotBlank
          @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be E.164, e.g. +919876543210")
          String phone) {}

  public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 255) String email) {}

  public record ResetPasswordRequest(
      @NotBlank @Size(min = 20, max = 512) String token,
      @NotBlank @Size(min = 10, max = 128) String newPassword) {

    @Override
    public String toString() {
      return "ResetPasswordRequest[***]";
    }
  }
}
