# syntax=docker/dockerfile:1

# Build stage: Azul Zulu JDK 21 + Maven (Maven is copied from the official Maven image, only the JDK matters for the result)
# To go back to Temurin: --build-arg BUILD_JDK=maven:3.9-eclipse-temurin-21 --build-arg RUN_JRE=eclipse-temurin:21-jre
ARG BUILD_JDK=azul-zulu:21
ARG RUN_JRE=azul-zulu:21-jre

# The build stage runs once on the build machine's own platform: the WAR/JAR is the same for amd64 and arm64,
# so a multi-platform image build does not compile (and download from Maven Central) twice, and arm64 is not emulated.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS maven-tool

FROM --platform=$BUILDPLATFORM ${BUILD_JDK} AS build
COPY --from=maven-tool /usr/share/maven /usr/share/maven
ENV MAVEN_HOME=/usr/share/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"
# Maven Central answers "429 Too Many Requests" when many builds download at once: retry up to 10 times, 10 s apart (Maven 3.9 reads MAVEN_ARGS)
ENV MAVEN_ARGS="-Daether.connector.http.retryHandler.count=10 -Daether.connector.http.retryHandler.interval=10000"
WORKDIR /build
COPY pom.xml .
# quarkus:go-offline also fetches the build-time (deployment) artifacts that Quarkus resolves itself during package
RUN mvn -q -B dependency:go-offline quarkus:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# Run stage: Azul Zulu JRE 21 only, non-root user
FROM ${RUN_JRE}
RUN useradd --system --create-home --uid 1001 clinic && mkdir -p /data && chown clinic:clinic /data
WORKDIR /app
COPY --from=build --chown=clinic:clinic /build/target/quarkus-app/ /app/
USER clinic
ENV QUARKUS_DATASOURCE_JDBC_URL="jdbc:h2:file:/data/clinic;DB_CLOSE_DELAY=-1"
# Ollama on the host; llm.env (see compose.yaml) overrides this
ENV LLM_BASE_URL="http://host.docker.internal:11434"
VOLUME /data
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
