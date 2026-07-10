/**
 * OpenTelemetry tracing setup for Node.js services.
 * Uses OTLP gRPC exporter to send spans to the collector.
 */

import { NodeSDK } from '@opentelemetry/sdk-node';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-grpc';
import { FastifyInstrumentation } from '@opentelemetry/instrumentation-fastify';
import { HttpInstrumentation } from '@opentelemetry/instrumentation-http';
import { Resource } from '@opentelemetry/resources';
import {
  ATTR_SERVICE_NAME,
  ATTR_SERVICE_VERSION,
  ATTR_SERVICE_NAMESPACE,
  ATTR_DEPLOYMENT_ENVIRONMENT_NAME,
} from '@opentelemetry/semantic-conventions';

let sdk: NodeSDK | null = null;

/**
 * Initialize the OpenTelemetry SDK.
 * Must be called BEFORE creating the Fastify server.
 */
export function initTelemetry(
  serviceName: string,
  exporterEndpoint: string,
  serviceVersion: string = '0.1.0',
  environment: string = 'local',
): NodeSDK {
  const resource = new Resource({
    [ATTR_SERVICE_NAME]: serviceName,
    [ATTR_SERVICE_VERSION]: serviceVersion,
    [ATTR_SERVICE_NAMESPACE]: 'payment-api',
    [ATTR_DEPLOYMENT_ENVIRONMENT_NAME]: environment,
  });

  // Strip http:// prefix for gRPC
  let grpcEndpoint = exporterEndpoint;
  if (grpcEndpoint.startsWith('http://')) {
    grpcEndpoint = grpcEndpoint.slice(7);
  }

  const exporter = new OTLPTraceExporter({
    url: `http://${grpcEndpoint}`,
  });

  sdk = new NodeSDK({
    resource,
    traceExporter: exporter,
    instrumentations: [
      new HttpInstrumentation(),
      new FastifyInstrumentation(),
    ],
  });

  sdk.start();

  // Graceful shutdown
  process.on('SIGTERM', async () => {
    if (sdk) {
      await sdk.shutdown();
    }
  });

  console.log(`OTel tracing initialized: service=${serviceName} endpoint=${grpcEndpoint}`);
  return sdk;
}

/** Get the active SDK instance for manual shutdown. */
export function getSDK(): NodeSDK | null {
  return sdk;
}
