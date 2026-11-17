package com.parksmart.profile.infrastructure.persistence;

import org.springframework.dao.DataIntegrityViolationException;

/** Identifies which named constraint a write violated. */
final class ConstraintViolations {

  private ConstraintViolations() {}

  static boolean violated(DataIntegrityViolationException e, String constraintName) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof org.hibernate.exception.ConstraintViolationException cve
          && constraintName.equalsIgnoreCase(cve.getConstraintName())) {
        return true;
      }
      if (t instanceof java.sql.SQLException sql
          && sql.getMessage() != null
          && sql.getMessage().contains(constraintName)) {
        return true;
      }
    }
    return false;
  }
}
