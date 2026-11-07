package com.parksmart.notification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Clean-architecture rules from CLAUDE.md. */
@AnalyzeClasses(
    packages = "com.parksmart.notification",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domain_is_framework_free =
      noClasses()
          .that()
          .resideInAPackage("..notification.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "com.fasterxml..",
              "java.sql..",
              "com.parksmart.common..");

  @ArchTest
  static final ArchRule domain_does_not_know_outer_layers =
      noClasses()
          .that()
          .resideInAPackage("..notification.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..notification.application..",
              "..notification.infrastructure..",
              "..notification.api..");

  @ArchTest
  static final ArchRule application_does_not_use_infrastructure_or_api =
      noClasses()
          .that()
          .resideInAPackage("..notification.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..notification.infrastructure..", "..notification.api..");

  @ArchTest
  static final ArchRule api_does_not_use_infrastructure =
      noClasses()
          .that()
          .resideInAPackage("..notification.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..notification.infrastructure..");

  @ArchTest
  static final ArchRule no_other_service_code =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.parksmart.identity..",
              "com.parksmart.profile..",
              "com.parksmart.listing..",
              "com.parksmart.booking..",
              "com.parksmart.payment..",
              "com.parksmart.search..",
              "com.parksmart.review..",
              "com.parksmart.gateway..",
              "com.parksmart.adminbff..");

  /**
   * Booking persists with explicit SQL only (tstzrange, exclusion constraint, conditional updates).
   */
  @ArchTest
  static final ArchRule no_jpa =
      noClasses().should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..");
}
