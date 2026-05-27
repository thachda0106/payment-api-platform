# ============================================================================
# Dockerfile.java — Java Spring Boot multi-stage build
# ============================================================================
# Build:  docker build -f docker/Dockerfile.java -t payment-api/{svc}:latest services/java/{svc}
# Run:    docker run -p 8080:8080 payment-api/{svc}:latest
# ============================================================================

# ─── Stage 1: Build ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper and pom first for dependency caching
COPY pom.xml mvnw* ./
COPY .mvn .mvn
RUN if [ -f mvnw ]; then chmod +x mvnw && ./mvnw dependency:go-offline -B -q; \
    else mvn dependency:go-offline -B -q; fi

# Copy source and build
COPY src/ src/
RUN if [ -f mvnw ]; then ./mvnw package -DskipTests -B -q; \
    else mvn package -DskipTests -B -q; fi

# Extract the JAR layers for efficient Docker caching
RUN java -Djarmode=tools -jar target/*.jar extract --destination extracted

# ─── Stage 2: Runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy extracted layers (enables Docker layer caching for unchanged deps)
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/spring-boot-loader/ ./
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
COPY --from=builder /app/extracted/application/ ./

USER appuser:appgroup

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
