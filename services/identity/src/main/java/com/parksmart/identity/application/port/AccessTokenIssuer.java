package com.parksmart.identity.application.port;

import com.parksmart.identity.domain.Account;

/** Signs short-lived RS256 access tokens. */
public interface AccessTokenIssuer {

  IssuedAccessToken issue(Account account);

  record IssuedAccessToken(String value, long expiresInSeconds) {}
}
