# Build stage: JDK 21 + Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# Run stage: JRE 21 only, non-root user
FROM eclipse-temurin:21-jre
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
