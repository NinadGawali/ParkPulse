package com.parksmart.adminbff.application;

import com.parksmart.adminbff.application.port.AdminCall;
import com.parksmart.common.security.AuthenticatedAccount;
import com.parksmart.common.security.CurrentAccount;
import com.parksmart.common.security.Role;
import com.parksmart.common.web.CorrelationId;

/** The ADMIN check every use case starts with (403 problem for anyone else). */
final class Admins {

  private Admins() {}

  static AdminCall require() {
    AuthenticatedAccount admin = CurrentAccount.requireRole(Role.ADMIN);
    return new AdminCall(admin.accessToken(), CorrelationId.currentOrNew());
  }
}
