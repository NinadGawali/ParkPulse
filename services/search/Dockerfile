# syntax=docker/dockerfile:1.7
# Build from the repo root:  docker build -f services/search/Dockerfile -t parksmart/search .

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY shared ./shared
COPY tools ./tools
COPY services ./services
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/search -am package -DskipTests -Dspotless.check.skip=true \
 && java -Djarmode=tools -jar services/search/target/search-service-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
EXPOSE 8086
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8086/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
