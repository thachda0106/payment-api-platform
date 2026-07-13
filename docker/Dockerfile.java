# ============================================================================
# Dockerfile.java — Java Spring Boot multi-stage build (context: repo root)
# ============================================================================
# Usage:
#   docker build -f docker/Dockerfile.java --build-arg SERVICE_PATH=services/java/financial-core -t payment-api/financial-core:latest .
#   docker compose build financial-core  (handled by docker-compose.yml)
# ============================================================================

ARG SERVICE_PATH

# ─── Stage 1: Dependencies (cached by pom) ──────────────────────────────────
# Using maven image so mvn, mvnw etc. are present.
FROM maven:3.9-eclipse-temurin-21-alpine AS deps
ARG SERVICE_PATH
WORKDIR /app

# Copy poms first for layer caching
COPY libs/java/pom.xml /libs/java/pom.xml
COPY ${SERVICE_PATH}/pom.xml /app/pom.xml
RUN cd /libs/java && mvn install -DskipTests -B -q 2>/dev/null || true
RUN cd /app && mvn dependency:go-offline -B -q 2>/dev/null || true

# ─── Stage 2: Build ─────────────────────────────────────────────────────────
FROM deps AS builder
ARG SERVICE_PATH

COPY libs/ /libs/
COPY ${SERVICE_PATH}/src/ /app/src/

RUN cd /libs/java && mvn install -DskipTests -B -q
RUN cd /app && mvn package -DskipTests -B -q

# OTel Java Agent
FROM maven:3.9-eclipse-temurin-21-alpine AS agent
ARG OTEL_AGENT_VERSION=2.7.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /opentelemetry-javaagent.jar

# ─── Stage 3: Runtime ───────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/*.jar /app/app.jar
COPY --from=agent /opentelemetry-javaagent.jar /opentelemetry-javaagent.jar

USER appuser:appgroup

ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
  CMD wget -qO- http://localhost:8080/liveness | grep -q ok || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -javaagent:/opentelemetry-javaagent.jar -jar /app/app.jar"]
