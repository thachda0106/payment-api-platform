module github.com/payment-api/settlement-service

go 1.22

require (
	github.com/go-chi/chi/v5 v5.1.0
	github.com/confluentinc/confluent-kafka-go/v2 v2.4.0
	github.com/lib/pq v1.10.9
	go.opentelemetry.io/otel v1.27.0
	go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc v1.27.0
	go.opentelemetry.io/otel/sdk v1.27.0
)
