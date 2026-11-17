package com.parksmart.payment.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

/** Fails startup when the database URL does not demand verified TLS (aws profile). */
@Component
@ConditionalOnProperty("parksmart.require-db-tls")
class DatabaseTlsCheck {

  DatabaseTlsCheck(DataSourceProperties dataSource) {
    String url = dataSource.getUrl();
    if (url == null || !url.contains("sslmode=verify-full")) {
      throw new IllegalStateException(
          "Database URL must use sslmode=verify-full when parksmart.require-db-tls is true");
    }
  }
}
