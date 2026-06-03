"""
OpenTelemetry tracing setup for Python services.

Uses OTLP gRPC exporter to send spans to the collector.
Auto-instruments FastAPI with standard HTTP spans and W3C trace context propagation.
"""

import logging
from fastapi import FastAPI
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.resources import Resource, SERVICE_NAME, SERVICE_VERSION, SERVICE_NAMESPACE, DEPLOYMENT_ENVIRONMENT
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.trace.sampling import ALWAYS_ON

_logger = logging.getLogger(__name__)


def setup_telemetry(
    app: FastAPI,
    service_name: str,
    service_version: str,
    exporter_endpoint: str,
    environment: str = "local",
) -> TracerProvider:
    """
    Initialize OpenTelemetry SDK with OTLP gRPC exporter and FastAPI auto-instrumentation.

    Args:
        app: FastAPI application instance
        service_name: Name for this service (e.g., "fraud-service")
        service_version: Version string (e.g., "0.1.0")
        exporter_endpoint: OTLP gRPC endpoint (e.g., "http://otel-collector:4317")
        environment: Deployment environment (e.g., "local", "staging", "production")

    Returns:
        TracerProvider — caller is responsible for calling shutdown() on exit.
    """
    resource = Resource.create({
        SERVICE_NAME: service_name,
        SERVICE_VERSION: service_version,
        SERVICE_NAMESPACE: "payment-api",
        DEPLOYMENT_ENVIRONMENT: environment,
    })

    # Strip http:// prefix for gRPC if present
    grpc_endpoint = exporter_endpoint
    if grpc_endpoint.startswith("http://"):
        grpc_endpoint = grpc_endpoint[len("http://"):]
    elif grpc_endpoint.startswith("https://"):
        grpc_endpoint = grpc_endpoint[len("https://"):]

    exporter = OTLPSpanExporter(endpoint=grpc_endpoint, insecure=True)
    processor = BatchSpanProcessor(exporter)

    provider = TracerProvider(
        resource=resource,
        sampler=ALWAYS_ON,
        active_span_processor=processor,
    )
    trace.set_tracer_provider(provider)

    # Auto-instrument FastAPI
    FastAPIInstrumentor.instrument_app(app)

    _logger.info("OTel tracing initialized: service=%s endpoint=%s", service_name, grpc_endpoint)
    return provider
