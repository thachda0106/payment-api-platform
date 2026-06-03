# ============================================================================
# Dockerfile.java — Java Spring Boot multi-stage build
# ============================================================================
# Build:  docker build -f docker/Dockerfile.java -t payment-api/financial-core:latest services/java/financial-core
# Run:    docker run -p 8080:8080 payment-api/financial-core:latest
# ============================================================================

# ─── Stage 1: Build ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper and pom first for dependency caching
COPY pom.xml ./
COPY libs/java/pom.xml /libs/java/pom.xml
RUN mvn dependency:go-offline -B -q 2>/dev/null || true

# Copy source and build
COPY src/ src/
COPY libs/ /libs/
RUN cd /libs/java && mvn install -DskipTests -B -q 2>/dev/null || true
RUN cd /app && mvn package -DskipTests -B -q

# Extract the JAR layers for efficient Docker caching
RUN java -Djarmode=tools -jar target/*.jar extract --destination extracted

# Download OTel Java Agent for auto-instrumentation
ARG OTEL_AGENT_VERSION=2.7.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# ─── Stage 2: Runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy extracted layers (enables Docker layer caching for unchanged deps)
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/spring-boot-loader/ ./
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
COPY --from=builder /app/extracted/application/ ./

# Copy OTel Java Agent
COPY --from=builder /app/opentelemetry-javaagent.jar /opentelemetry-javaagent.jar

USER appuser:appgroup

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
  CMD wget -qO- http://localhost:8080/liveness | grep -q ok || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -javaagent:/opentelemetry-javaagent.jar org.springframework.boot.loader.launch.JarLauncher"]
