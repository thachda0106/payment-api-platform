# Phase 9 — Local Development Environment

> **Duration**: 2-3 days | **Prerequisites**: Phases 1-8 (conceptual understanding)
>
> **Goal**: Build a complete observability stack locally using Docker Compose, with instrumentation examples for Spring Boot, NestJS, FastAPI, and Go.

---

## 9.1 Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        localhost:3000                             │
│                    ┌───────────────┐                              │
│                    │    Grafana    │                              │
│                    │ (unified UI)  │                              │
│                    └───┬───┬───┬───┘                              │
│                        │   │   │                                  │
│          ┌─────────────┼───┼───┼─────────────┐                   │
│          │             │   │   │             │                   │
│    ┌─────▼─────┐ ┌─────▼───▼───▼─────┐ ┌────▼──────────┐        │
│    │ Prometheus│ │     Jaeger        │ │  OpenSearch    │        │
│    │ :9090     │ │ :16686 (query)    │ │  :9200         │        │
│    │           │ │ :14250 (collector)│ │                │        │
│    └─────▲─────┘ └────────▲──────────┘ └────▲───────────┘        │
│          │                │                  │                    │
│          │    ┌───────────▼──────────────────▼──┐                 │
│          │    │       OTel Collector            │                 │
│          │    │  :4317 (gRPC)  :4318 (HTTP)     │                 │
│          │    │  :8889 (Prometheus metrics)     │                 │
│          │    └───────▲─────────────────────────┘                 │
│          │            │ OTLP                                      │
│          │            │                                           │
│    ┌─────┴────────────┴──────────────────────┐                   │
│    │          Application Services            │                   │
│    │                                          │                   │
│    │  Spring Boot :8081  │  Go :8082          │                   │
│    │  FastAPI :8083      │  NestJS :8084      │                   │
│    └──────────────────────────────────────────┘                   │
└──────────────────────────────────────────────────────────────────┘
```

**Data flow:**
1. Services send OTLP (traces + metrics + logs) to Collector at `localhost:4317`
2. Collector exports metrics to Prometheus, traces to Jaeger, logs to OpenSearch
3. Prometheus also scrapes Collector's own Prometheus endpoint (`:8889/metrics`)
4. Grafana queries all three backends

---

## 9.2 Folder Structure

```
observability-local/
├── docker-compose.yml
├── otel-collector/
│   └── config.yaml
├── grafana/
│   ├── datasources/
│   │   └── datasources.yml
│   └── dashboards/
│       └── service-overview.json
├── services/
│   ├── spring-boot/
│   │   ├── Dockerfile
│   │   ├── build.gradle
│   │   └── src/main/java/.../PaymentController.java
│   ├── go/
│   │   ├── Dockerfile
│   │   ├── go.mod
│   │   └── main.go
│   ├── fastapi/
│   │   ├── Dockerfile
│   │   ├── requirements.txt
│   │   └── main.py
│   └── nestjs/
│       ├── Dockerfile
│       ├── package.json
│       └── src/main.ts
└── prometheus/
    └── prometheus.yml
```

---

## 9.3 Docker Compose

```yaml
version: '3.8'

services:
  # ============================================================
  # OTel Collector — receives OTLP, exports to backends
  # ============================================================
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.96.0
    command: ["--config=/etc/otel-collector-config.yaml"]
    volumes:
      - ./otel-collector/config.yaml:/etc/otel-collector-config.yaml
    ports:
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP
      - "8889:8889"   # Prometheus metrics (scraped by Prometheus)
    depends_on:
      - jaeger
      - opensearch

  # ============================================================
  # Prometheus — scrapes Collector metrics, queries via Grafana
  # ============================================================
  prometheus:
    image: prom/prometheus:v2.49.1
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--web.console.libraries=/usr/share/prometheus/console_libraries'
      - '--web.console.templates=/usr/share/prometheus/consoles'
      - '--web.enable-remote-write-receiver'
    ports:
      - "9090:9090"

  # ============================================================
  # Jaeger — trace storage + UI
  # ============================================================
  jaeger:
    image: jaegertracing/all-in-one:1.53
    environment:
      - COLLECTOR_OTLP_ENABLED=true
      - SPAN_STORAGE_TYPE=badger
      - BADGER_EPHEMERAL=false
      - BADGER_DIRECTORY_VALUE=/badger/data
      - BADGER_DIRECTORY_KEY=/badger/key
    volumes:
      - jaeger_data:/badger
    ports:
      - "16686:16686"  # Jaeger UI
      - "14250:14250"  # gRPC (used by OTel Collector)
      - "4317"         # OTLP gRPC (not used — Collector handles OTLP)
    expose:
      - "14250"

  # ============================================================
  # OpenSearch — log storage + search
  # ============================================================
  opensearch:
    image: opensearchproject/opensearch:2.12.0
    environment:
      - discovery.type=single-node
      - bootstrap.memory_lock=true
      - "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m"
      - "DISABLE_SECURITY_PLUGIN=true"
    ulimits:
      memlock:
        soft: -1
        hard: -1
        nofile:
          soft: 65536
          hard: 65536
    volumes:
      - opensearch_data:/usr/share/opensearch/data
    ports:
      - "9200:9200"

  # ============================================================
  # Grafana — unified visualization
  # ============================================================
  grafana:
    image: grafana/grafana:10.3.3
    volumes:
      - ./grafana/datasources:/etc/grafana/provisioning/datasources
      - ./grafana/dashboards:/etc/grafana/provisioning/dashboards
      - grafana_data:/var/lib/grafana
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_AUTH_ANONYMOUS_ENABLED=false
    ports:
      - "3000:3000"
    depends_on:
      - prometheus
      - jaeger
      - opensearch

  # ============================================================
  # Application Services
  # ============================================================
  spring-boot-app:
    build: ./services/spring-boot
    ports:
      - "8081:8080"
    environment:
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
      - OTEL_SERVICE_NAME=payment-service-spring
      - OTEL_METRICS_EXPORTER=otlp
      - OTEL_LOGS_EXPORTER=otlp

  go-app:
    build: ./services/go
    ports:
      - "8082:8080"
    environment:
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
      - OTEL_SERVICE_NAME=ledger-service-go
      - OTEL_METRICS_EXPORTER=otlp
      - OTEL_LOGS_EXPORTER=otlp

  fastapi-app:
    build: ./services/fastapi
    ports:
      - "8083:8000"
    environment:
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
      - OTEL_SERVICE_NAME=fraud-service-python
      - OTEL_METRICS_EXPORTER=otlp
      - OTEL_LOGS_EXPORTER=otlp

  nestjs-app:
    build: ./services/nestjs
    ports:
      - "8084:3000"
    environment:
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
      - OTEL_SERVICE_NAME=notification-service-node
      - OTEL_METRICS_EXPORTER=otlp
      - OTEL_LOGS_EXPORTER=otlp

volumes:
  prometheus_data:
  jaeger_data:
  opensearch_data:
  grafana_data:
```

---

## 9.4 Collector Configuration

```yaml
# otel-collector/config.yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:
    timeout: 10s
    send_batch_size: 8192

  memory_limiter:
    check_interval: 1s
    limit_mib: 256

  # Derive RED metrics from spans
  spanmetrics:
    metrics_exporter: prometheus

connectors:
  spanmetrics:

exporters:
  # The Collector's own metrics (for Prometheus to scrape)
  prometheus:
    endpoint: 0.0.0.0:8889

  # Send traces to Jaeger
  otlp/jaeger:
    endpoint: jaeger:14250
    tls:
      insecure: true

  # Send logs to OpenSearch
  opensearch/logs:
    http:
      endpoint: http://opensearch:9200
    logs_index: otel-logs

  # Debug exporter (prints to stdout — useful for development)
  debug:
    verbosity: basic

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [otlp/jaeger, spanmetrics, debug]

    metrics:
      receivers: [otlp, spanmetrics]
      processors: [memory_limiter, batch]
      exporters: [prometheus, debug]

    logs:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [opensearch/logs, debug]
```

---

## 9.5 Prometheus Configuration

```yaml
# prometheus/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  # Scrape the OTel Collector's own metrics
  - job_name: 'otel-collector'
    static_configs:
      - targets: ['otel-collector:8889']

  # Scrape application metrics (if exposing Prometheus endpoints)
  - job_name: 'spring-boot'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['spring-boot-app:8080']

  - job_name: 'nestjs'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['nestjs-app:3000']
```

---

## 9.6 Grafana Data Sources

```yaml
# grafana/datasources/datasources.yml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false

  - name: Jaeger
    type: jaeger
    access: proxy
    url: http://jaeger:16686
    editable: false

  - name: OpenSearch
    type: grafana-opensearch-datasource
    access: proxy
    url: http://opensearch:9200
    editable: false
    jsonData:
      database: otel-logs
      timeField: "@timestamp"
      logMessageField: body
      logLevelField: severity_text
```

---

## 9.7 Instrumentation Examples

### 9.7.1 Spring Boot (Java)

**build.gradle:**

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.2.2'
    id 'io.spring.dependency-management' version '1.1.4'
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // OpenTelemetry
    implementation platform('io.opentelemetry:opentelemetry-bom:1.34.1')
    implementation 'io.opentelemetry:opentelemetry-api'
    implementation 'io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter'

    // Micrometer → OTel bridge (for @Timed, etc.)
    implementation 'io.micrometer:micrometer-registry-otlp'
}
```

**PaymentController.java:**

```java
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final Tracer tracer;
    private final Meter meter;
    private final LongCounter paymentCounter;

    public PaymentController(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("payment-service");
        this.meter = openTelemetry.getMeter("payment-service");
        this.paymentCounter = meter
            .counterBuilder("payments.processed")
            .setDescription("Number of processed payments")
            .build();
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestBody PaymentRequest request) {

        Span span = tracer.spanBuilder("create-payment")
            .setAttribute("payment.amount", request.getAmount())
            .setAttribute("payment.currency", request.getCurrency())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Simulate processing
            Thread.sleep((long) (Math.random() * 200));

            paymentCounter.add(1, Attributes.of(
                AttributeKey.stringKey("status"), "success"
            ));

            span.setStatus(StatusCode.OK);
            return ResponseEntity.ok(new PaymentResponse("pay_" + UUID.randomUUID()));
        } catch (Exception e) {
            paymentCounter.add(1, Attributes.of(
                AttributeKey.stringKey("status"), "error"
            ));
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### 9.7.2 Go

**main.go:**

```go
package main

import (
    "context"
    "encoding/json"
    "fmt"
    "log"
    "math/rand"
    "net/http"
    "time"

    "go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
    "go.opentelemetry.io/otel"
    "go.opentelemetry.io/otel/attribute"
    "go.opentelemetry.io/otel/codes"
    "go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
    "go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetricgrpc"
    "go.opentelemetry.io/otel/metric"
    "go.opentelemetry.io/otel/sdk/metric"
    "go.opentelemetry.io/otel/sdk/trace"
)

var (
    tracer         otel.Tracer
    meter          metric.Meter
    paymentCounter metric.Int64Counter
)

func initOTel(ctx context.Context) (*trace.TracerProvider, *metric.MeterProvider, error) {
    // Trace exporter
    traceExporter, err := otlptracegrpc.New(ctx,
        otlptracegrpc.WithEndpoint("otel-collector:4317"),
        otlptracegrpc.WithInsecure(),
    )
    if err != nil {
        return nil, nil, err
    }

    tp := trace.NewTracerProvider(
        trace.WithBatcher(traceExporter),
        trace.WithResource(resource.NewWithAttributes(
            semconv.ServiceName("ledger-service-go"),
        )),
    )
    otel.SetTracerProvider(tp)

    // Metric exporter
    metricExporter, err := otlpmetricgrpc.New(ctx,
        otlpmetricgrpc.WithEndpoint("otel-collector:4317"),
        otlpmetricgrpc.WithInsecure(),
    )
    if err != nil {
        return nil, nil, err
    }

    mp := metric.NewMeterProvider(
        metric.WithReader(metric.NewPeriodicReader(metricExporter)),
    )
    otel.SetMeterProvider(mp)

    return tp, mp, nil
}

func processLedger(w http.ResponseWriter, r *http.Request) {
    ctx := r.Context()
    ctx, span := tracer.Start(ctx, "process-ledger-entry")
    defer span.End()

    // Simulate DB write
    delay := time.Duration(50+rand.Intn(150)) * time.Millisecond
    time.Sleep(delay)

    span.SetAttributes(
        attribute.String("ledger.operation", "debit"),
        attribute.Float64("ledger.amount", 100.0),
    )

    paymentCounter.Add(ctx, 1, metric.WithAttributes(
        attribute.String("status", "success"),
    ))

    span.SetStatus(codes.Ok, "Ledger entry processed")
    w.WriteHeader(http.StatusOK)
    json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func main() {
    ctx := context.Background()
    tp, mp, err := initOTel(ctx)
    if err != nil {
        log.Fatal(err)
    }
    defer tp.Shutdown(ctx)
    defer mp.Shutdown(ctx)

    tracer = otel.Tracer("ledger-service-go")
    meter = otel.Meter("ledger-service-go")
    paymentCounter, _ = meter.Int64Counter("ledger.entries.processed")

    // Auto-instrument HTTP server
    mux := http.NewServeMux()
    mux.HandleFunc("/ledger", processLedger)
    handler := otelhttp.NewHandler(mux, "ledger-service")

    log.Println("Ledger service listening on :8080")
    log.Fatal(http.ListenAndServe(":8080", handler))
}
```

### 9.7.3 FastAPI (Python)

**main.py:**

```python
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn
import random
import time
import uuid

from opentelemetry import trace, metrics
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.resources import Resource, SERVICE_NAME
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor

# Initialize OTel
resource = Resource.create({SERVICE_NAME: "fraud-service-python"})

# Traces
tracer_provider = TracerProvider(resource=resource)
tracer_provider.add_span_processor(
    BatchSpanProcessor(OTLPSpanExporter(endpoint="otel-collector:4317", insecure=True))
)
trace.set_tracer_provider(tracer_provider)

# Metrics
metric_reader = PeriodicExportingMetricReader(
    OTLPMetricExporter(endpoint="otel-collector:4317", insecure=True)
)
meter_provider = MeterProvider(resource=resource, metric_readers=[metric_reader])
metrics.set_meter_provider(meter_provider)

tracer = trace.get_tracer(__name__)
meter = metrics.get_meter(__name__)
fraud_check_counter = meter.create_counter(
    "fraud.checks.processed",
    description="Number of fraud checks processed"
)

app = FastAPI()

# Auto-instrument FastAPI
FastAPIInstrumentor.instrument_app(app)

class FraudCheckRequest(BaseModel):
    payment_id: str
    amount: float
    user_id: str

class FraudCheckResponse(BaseModel):
    passed: bool
    risk_score: float

@app.post("/fraud/check", response_model=FraudCheckResponse)
async def check_fraud(request: FraudCheckRequest):
    with tracer.start_as_current_span("fraud-check") as span:
        span.set_attribute("payment.amount", request.amount)
        span.set_attribute("user.id", request.user_id)

        # Simulate fraud check
        time.sleep(random.uniform(0.01, 0.2))
        risk_score = random.random()
        passed = risk_score < 0.95

        fraud_check_counter.add(1, {
            "passed": str(passed).lower()
        })

        span.set_attribute("fraud.risk_score", risk_score)
        span.set_attribute("fraud.passed", passed)

        if risk_score > 0.98:
            span.set_status(trace.StatusCode.ERROR, "High risk transaction blocked")

        return FraudCheckResponse(passed=passed, risk_score=risk_score)

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
```

### 9.7.4 NestJS (TypeScript)

**main.ts:**

```typescript
import { NestFactory } from '@nestjs/core';
import { Controller, Post, Body, Module } from '@nestjs/common';
import { NodeSDK } from '@opentelemetry/sdk-node';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-grpc';
import { OTLPMetricExporter } from '@opentelemetry/exporter-metrics-otlp-grpc';
import { getNodeAutoInstrumentations } from '@opentelemetry/auto-instrumentations-node';
import { PeriodicExportingMetricReader } from '@opentelemetry/sdk-metrics';
import { Resource } from '@opentelemetry/resources';
import { SemanticResourceAttributes } from '@opentelemetry/semantic-conventions';
import { trace, metrics, SpanStatusCode } from '@opentelemetry/api';

// Initialize OTel SDK
const sdk = new NodeSDK({
  resource: new Resource({
    [SemanticResourceAttributes.SERVICE_NAME]: 'notification-service-node',
  }),
  traceExporter: new OTLPTraceExporter({
    url: 'http://otel-collector:4317',
  }),
  metricReader: new PeriodicExportingMetricReader({
    exporter: new OTLPMetricExporter({
      url: 'http://otel-collector:4317',
    }),
  }),
  instrumentations: [getNodeAutoInstrumentations()],
});

sdk.start();

const tracer = trace.getTracer('notification-service');
const meter = metrics.getMeter('notification-service');
const notificationCounter = meter.createCounter('notifications.sent');

class SendNotificationDto {
  userId: string;
  message: string;
  channel: string;
}

@Controller('notifications')
class NotificationController {
  @Post()
  async send(@Body() dto: SendNotificationDto) {
    const span = tracer.startSpan('send-notification');
    span.setAttribute('notification.channel', dto.channel);
    span.setAttribute('user.id', dto.userId);

    try {
      // Simulate sending
      await new Promise(r => setTimeout(r, 10 + Math.random() * 90));

      notificationCounter.add(1, { channel: dto.channel, status: 'success' });
      span.setStatus({ code: SpanStatusCode.OK });
      return { status: 'sent', channel: dto.channel };
    } catch (error) {
      notificationCounter.add(1, { channel: dto.channel, status: 'error' });
      span.recordException(error);
      span.setStatus({ code: SpanStatusCode.ERROR });
      throw error;
    } finally {
      span.end();
    }
  }
}

@Module({
  controllers: [NotificationController],
})
class AppModule {}

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  await app.listen(3000);
}
bootstrap();
```

---

## 9.8 Verification

After `docker-compose up`:

| Component | URL | What to Verify |
|-----------|-----|---------------|
| Grafana | http://localhost:3000 | Login admin/admin. Check datasources are green. |
| Prometheus | http://localhost:9090 | Execute `up` — should show collector and apps. |
| Jaeger | http://localhost:16686 | Search for traces by service name. |
| OpenSearch | http://localhost:9200 | `GET /_cat/indices` — should show `otel-logs` index. |

**Smoke test:**

Generate traffic:
```bash
# Load generation script
while true; do
  curl -X POST http://localhost:8081/payments \
    -H "Content-Type: application/json" \
    -d '{"amount": 100, "currency": "USD"}'
  curl -X POST http://localhost:8083/fraud/check \
    -H "Content-Type: application/json" \
    -d '{"payment_id": "pay_123", "amount": 100, "user_id": "42"}'
  sleep 0.1
done
```

Verify in Jaeger: Traces appear with spans from Spring Boot → Collector → Jaeger.
Verify in Prometheus: `rate(payments_processed[5m])` returns data.
Verify in OpenSearch: Logs from all services indexed under `otel-logs`.

---

**Next: Phase 10 — Kubernetes Deployment**
