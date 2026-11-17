package com.parksmart.listing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Clean-architecture rules from CLAUDE.md. */
@AnalyzeClasses(
    packages = "com.parksmart.listing",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domain_is_framework_free =
      noClasses()
          .that()
          .resideInAPackage("..listing.domain..")
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
          .resideInAPackage("..listing.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..listing.application..", "..listing.infrastructure..", "..listing.api..");

  @ArchTest
  static final ArchRule application_does_not_use_infrastructure_or_api =
      noClasses()
          .that()
          .resideInAPackage("..listing.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..listing.infrastructure..", "..listing.api..");

  @ArchTest
  static final ArchRule api_does_not_use_infrastructure =
      noClasses()
          .that()
          .resideInAPackage("..listing.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..listing.infrastructure..");

  @ArchTest
  static final ArchRule no_other_service_code =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.parksmart.identity..",
              "com.parksmart.profile..",
              "com.parksmart.booking..",
              "com.parksmart.payment..",
              "com.parksmart.search..",
              "com.parksmart.review..",
              "com.parksmart.notification..",
              "com.parksmart.gateway..",
              "com.parksmart.adminbff..");

  /** JPA stays an infrastructure detail: entities never reach the API or the domain. */
  @ArchTest
  static final ArchRule entities_live_in_persistence =
      classes()
          .that()
          .areAnnotatedWith(jakarta.persistence.Entity.class)
          .should()
          .resideInAPackage("..listing.infrastructure.persistence..")
          .andShould()
          .notBePublic();
}
