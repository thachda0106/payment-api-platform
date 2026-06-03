#!/bin/bash
# scaffold-java.sh — Generate new Java Spring Boot service
# Usage: bash scripts/scaffold-java.sh <service-name>
# Example: bash scripts/scaffold-java.sh payment-service

set -euo pipefail

NAME="${1:-}"
if [ -z "$NAME" ]; then
    echo "Usage: scaffold-java.sh <service-name>"
    echo "Example: scaffold-java.sh payment-service"
    exit 1
fi

SERVICE_DIR="services/java/$NAME"
PACKAGE_NAME=$(echo "$NAME" | tr '-' '.')
CLASS_NAME=$(echo "$NAME" | sed -E 's/(^|-)([a-z])/\U\2/g')

echo "Scaffolding Java service: $NAME"

# Create directory structure
mkdir -p "$SERVICE_DIR/src/main/java/com/paymentapi/$PACKAGE_NAME"
mkdir -p "$SERVICE_DIR/src/main/java/com/paymentapi/$PACKAGE_NAME/config"
mkdir -p "$SERVICE_DIR/src/main/resources"
mkdir -p "$SERVICE_DIR/src/test/java/com/paymentapi/$PACKAGE_NAME"
mkdir -p "$SERVICE_DIR/docs/adr"

# ─── pom.xml ────────────────────────────────────────────────────────────
cat > "$SERVICE_DIR/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.0</version>
        <relativePath/>
    </parent>
    <groupId>com.paymentapi</groupId>
    <artifactId>$NAME</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>$CLASS_NAME</name>
    <properties><java.version>21</java.version></properties>
    <dependencies>
        <dependency>
            <groupId>com.paymentapi</groupId>
            <artifactId>platform-libs</artifactId>
            <version>0.1.0</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build><plugins><plugin><groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
POM

# ─── Application class ──────────────────────────────────────────────────
cat > "$SERVICE_DIR/src/main/java/com/paymentapi/$PACKAGE_NAME/${CLASS_NAME}Application.java" <<JAVA
package com.paymentapi.$PACKAGE_NAME;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.paymentapi")
public class ${CLASS_NAME}Application {
    public static void main(String[] args) {
        SpringApplication.run(${CLASS_NAME}Application.class, args);
    }
}
JAVA

# ─── Config ─────────────────────────────────────────────────────────────
cat > "$SERVICE_DIR/src/main/java/com/paymentapi/$PACKAGE_NAME/config/${CLASS_NAME}Properties.java" <<JAVA
package com.paymentapi.$PACKAGE_NAME.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "$NAME")
public class ${CLASS_NAME}Properties {
    // Service-specific configuration goes here (Phase 7)
}
JAVA

# ─── application.yml ────────────────────────────────────────────────────
cat > "$SERVICE_DIR/src/main/resources/application.yml" <<YML
spring:
  application:
    name: $NAME
  datasource:
    url: \${DATABASE_URL:}
    hikari:
      maximum-pool-size: \${DB_MAX_POOL_SIZE:10}
  kafka:
    bootstrap-servers: \${KAFKA_BOOTSTRAP_SERVERS:}
    consumer:
      group-id: \${KAFKA_CONSUMER_GROUP:$NAME}

platform:
  server:
    port: \${SERVER_PORT:8080}
    host: \${SERVER_HOST:0.0.0.0}
  database:
    url: \${DATABASE_URL:}
  kafka:
    bootstrap-servers: \${KAFKA_BOOTSTRAP_SERVERS:}
    consumer-group: \${KAFKA_CONSUMER_GROUP:$NAME}
  logging:
    level: \${LOG_LEVEL:info}
    format: \${LOG_FORMAT:json}
  otel:
    service-name: \${OTEL_SERVICE_NAME:$NAME}
    service-version: \${SERVICE_VERSION:0.1.0}
    exporter-endpoint: \${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4317}

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
YML

# ─── Test ───────────────────────────────────────────────────────────────
cat > "$SERVICE_DIR/src/test/java/com/paymentapi/$PACKAGE_NAME/ProbesTest.java" <<JAVA
package com.paymentapi.$PACKAGE_NAME;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProbesTest {
    @Autowired MockMvc mvc;

    @Test void livenessReturns200() throws Exception {
        mvc.perform(get("/liveness")).andExpect(status().isOk());
    }
}
JAVA

# ─── ADR-0001 ───────────────────────────────────────────────────────────
cat > "$SERVICE_DIR/docs/adr/ADR-0001-${NAME}-architecture.md" <<ADR
# ADR-0001: ${CLASS_NAME} Architecture

## Status
Accepted

## Context
The ${CLASS_NAME} is part of the Payment API Platform microservices ecosystem.

## Decision
- **Language**: Java 21
- **Framework**: Spring Boot 3.3 with platform-libs
- **Tracing**: OTel Java Agent → gRPC to otel-collector → Jaeger
- **Health**: /liveness, /readiness, /startup (cached, TTL 5s)
- **Metrics**: Prometheus at /actuator/prometheus
- **Logging**: Structured JSON with traceId, spanId, requestId

## Consequences
- Probe endpoints provided by platform-libs
- Config validated at startup (fail-fast)
- Architecture fitness tests enforce package boundaries
ADR

# ─── README ─────────────────────────────────────────────────────────────
cat > "$SERVICE_DIR/README.md" <<MD
# $CLASS_NAME

## Quick Start
\`\`\`bash
cd services/java/$NAME
mvn spring-boot:run
\`\`\`

## Endpoints
| Path | Description |
|------|-------------|
| /liveness | Always 200 |
| /readiness | 200 or 503 based on deps |
| /startup | 503 until first successful readiness |
| /actuator/prometheus | Prometheus metrics |

## Env Vars
See docker-compose.yml for the full list. Required: OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_SERVICE_NAME.
MD

echo "✅ Service scaffolded: $SERVICE_DIR"
echo "   cd $SERVICE_DIR && mvn spring-boot:run"
