package com.parksmart.identity.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.identity.api.dto.Requests.ForgotPasswordRequest;
import com.parksmart.identity.api.dto.Requests.LoginRequest;
import com.parksmart.identity.api.dto.Requests.PhoneVerificationRequest;
import com.parksmart.identity.api.dto.Requests.RefreshRequest;
import com.parksmart.identity.api.dto.Requests.RegisterRequest;
import com.parksmart.identity.api.dto.Requests.ResetPasswordRequest;
import com.parksmart.identity.api.dto.Requests.VerifyCodeRequest;
import com.parksmart.identity.api.dto.Responses.AccountSummary;
import com.parksmart.identity.api.dto.Responses.TokenResponse;
import com.parksmart.identity.application.AuthService;
import com.parksmart.identity.application.PasswordResetService;
import com.parksmart.identity.application.RegistrationService;
import com.parksmart.identity.application.VerificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

  private final RegistrationService registration;
  private final AuthService auth;
  private final VerificationService verification;
  private final PasswordResetService passwordReset;

  AuthController(
      RegistrationService registration,
      AuthService auth,
      VerificationService verification,
      PasswordResetService passwordReset) {
    this.registration = registration;
    this.auth = auth;
    this.verification = verification;
    this.passwordReset = passwordReset;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  AccountSummary register(@Valid @RequestBody RegisterRequest request) {
    return AccountSummary.of(
        registration.register(request.email(), request.password(), request.acceptedTermsVersion()));
  }

  @PostMapping("/login")
  TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return TokenResponse.of(auth.login(request.email(), request.password()));
  }

  @PostMapping("/refresh")
  TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return TokenResponse.of(auth.refresh(request.refreshToken()));
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(@Valid @RequestBody RefreshRequest request) {
    auth.logout(CurrentAccount.require().accountId(), request.refreshToken());
  }

  @PostMapping("/email/verification-requests")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void requestEmailVerification() {
    verification.requestEmailVerification(CurrentAccount.require().accountId());
  }

  @PostMapping("/email/verify")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void verifyEmail(@Valid @RequestBody VerifyCodeRequest request) {
    verification.verifyEmail(CurrentAccount.require().accountId(), request.code());
  }

  @PostMapping("/phone/verification-requests")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void requestPhoneVerification(@Valid @RequestBody PhoneVerificationRequest request) {
    verification.requestPhoneVerification(CurrentAccount.require().accountId(), request.phone());
  }

  @PostMapping("/phone/verify")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void verifyPhone(@Valid @RequestBody VerifyCodeRequest request) {
    verification.verifyPhone(CurrentAccount.require().accountId(), request.code());
  }

  @PostMapping("/password/forgot")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
    passwordReset.forgotPassword(request.email());
  }

  @PostMapping("/password/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
    passwordReset.resetPassword(request.token(), request.newPassword());
  }
}
