---
description: Scaffold a new ParkSmart service module from the standard template
argument-hint: <service-name, e.g. identity | profile | listing | booking | payment | search | review | notification | gateway | admin-bff>
---

Scaffold the service `$ARGUMENTS` as a Maven module at `services/$ARGUMENTS/`. Package root: `com.parksmart.<name>` (strip hyphens, e.g. `admin-bff` -> `adminbff`).

Stop and tell me if `services/$ARGUMENTS/` already exists or the name is not one of the ten services in docs/ParkSmart-Services-and-Entities.md.

Create:

1. `services/$ARGUMENTS/pom.xml` — parent `com.parksmart:parksmart-parent`, artifactId `$ARGUMENTS-service`. Dependencies (versions from the parent/Boot BOM): spring-boot-starter-web, -actuator, -validation, -security, -oauth2-resource-server, -jdbc, flyway-core + flyway-database-postgresql, postgresql, spring-kafka, common-web, common-security, common-events. Test: spring-boot-starter-test, spring-security-test, spring-boot-testcontainers, testcontainers postgresql + kafka + junit-jupiter, archunit-junit5. Copy the structure of services/identity (the reference service). Gateway already exists. admin-bff has no JDBC/Flyway. Add spring-boot-maven-plugin.
2. Packages under `src/main/java/com/parksmart/<pkg>/`: `domain/`, `application/`, `infrastructure/`, `api/`, each with a `package-info.java` stating its rule, plus `<Name>ServiceApplication.java`.
3. `src/main/resources/application.yml` — every host, port, credential as `${ENV_VAR}` placeholders (no defaults for secrets); Flyway enabled; actuator health/info/prometheus exposed with liveness/readiness probes; `logging.structured.format.console: ecs`; correlation ID in MDC. Plus `application-local.yml` (localhost ports from .env.example), `application-test.yml`, `application-aws.yml` (expects Secrets Manager-injected env vars only).
4. `src/main/resources/db/migration/V1__init.sql` — header comment only; the real schema comes from the db-migration agent.
5. `<Name>ServiceApplication.main` sets `TimeZone.setDefault(UTC)` first. `src/test/java/.../ArchitectureTest.java` — ArchUnit rules: `domain` depends on no `org.springframework..`, `jakarta.persistence..`, `com.fasterxml..`; `api` does not access `infrastructure`; no dependency on any other `com.parksmart.<other-service>..` package.
6. `src/test/java/.../<Name>ServiceApplicationTests.java` — context loads using Testcontainers `postgis/postgis:17-3.7` via `@ServiceConnection`.
7. `Dockerfile` — multi-stage (eclipse-temurin:21-jdk build, eclipse-temurin:21-jre runtime), layered jar, non-root user, `HEALTHCHECK` on `/actuator/health`, `EXPOSE` the service port.
8. Add `<module>services/$ARGUMENTS</module>` to the root pom.xml modules list.
9. Add the service to the module map in CLAUDE.md and to docs/PROGRESS.md.

Then run `./mvnw -pl services/$ARGUMENTS -am verify`. If green, commit as `chore: scaffold $ARGUMENTS service module` (no attribution trailers). Report the files created and the build result.
