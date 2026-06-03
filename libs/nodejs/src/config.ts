/**
 * Typed, validated, modular configuration from environment variables.
 *
 * Mandatory (always validated):
 *   server (port, host), logging (level, format), otel (exporterEndpoint, serviceName, serviceVersion)
 *
 * Optional (validated only when configured):
 *   database (url, maxPoolSize, minIdle), kafka (bootstrapServers, consumerGroup), redis (url)
 *
 * All config is loaded from environment variables: SERVER_PORT, DATABASE_URL,
 * KAFKA_BOOTSTRAP_SERVERS, REDIS_URL, OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_SERVICE_NAME,
 * LOG_LEVEL, LOG_FORMAT.
 */

import { z } from 'zod';

// ─── Schemas ─────────────────────────────────────────────────────────────

const serverSchema = z.object({
  port: z.coerce.number().int().min(1).max(65535).default(8080),
  host: z.string().default('0.0.0.0'),
});

const loggingSchema = z.object({
  level: z.enum(['debug', 'info', 'warn', 'error']).default('info'),
  format: z.enum(['json', 'text']).default('json'),
});

const otelSchema = z.object({
  exporterEndpoint: z.string().min(1, 'OTEL_EXPORTER_OTLP_ENDPOINT is required'),
  serviceName: z.string().min(1, 'OTEL_SERVICE_NAME is required'),
  serviceVersion: z.string().default('0.1.0'),
});

const databaseSchema = z.object({
  url: z.string().min(1),
  maxPoolSize: z.coerce.number().int().min(1).default(10),
  minIdle: z.coerce.number().int().min(0).default(2),
});

const kafkaSchema = z.object({
  bootstrapServers: z.string().min(1),
  consumerGroup: z.string().default('default'),
});

const redisSchema = z.object({
  url: z.string().min(1),
});

const baseSchema = z.object({
  server: serverSchema.default({}),
  logging: loggingSchema.default({}),
  otel: otelSchema,
});

// ─── Type ────────────────────────────────────────────────────────────────

export interface PlatformConfig {
  server: z.infer<typeof serverSchema>;
  logging: z.infer<typeof loggingSchema>;
  otel: z.infer<typeof otelSchema>;
  database: z.infer<typeof databaseSchema> | null;
  kafka: z.infer<typeof kafkaSchema> | null;
  redis: z.infer<typeof redisSchema> | null;
}

// ─── Loader ──────────────────────────────────────────────────────────────

function hasVar(name: string): boolean {
  return !!process.env[name];
}

/**
 * Load and validate configuration from environment variables.
 * Fails fast with a clear error message if mandatory config is missing.
 */
export function loadConfig(): PlatformConfig {
  const raw = {
    server: {
      port: process.env.SERVER_PORT,
      host: process.env.SERVER_HOST,
    },
    logging: {
      level: process.env.LOG_LEVEL,
      format: process.env.LOG_FORMAT,
    },
    otel: {
      exporterEndpoint: process.env.OTEL_EXPORTER_OTLP_ENDPOINT,
      serviceName: process.env.OTEL_SERVICE_NAME,
      serviceVersion: process.env.SERVICE_VERSION || '0.1.0',
    },
  };

  const base = baseSchema.parse(raw);

  const config: PlatformConfig = {
    server: base.server,
    logging: base.logging,
    otel: base.otel,
    database: null,
    kafka: null,
    redis: null,
  };

  // Optional modules — only parse if env var is set
  if (hasVar('DATABASE_URL')) {
    config.database = databaseSchema.parse({
      url: process.env.DATABASE_URL,
      maxPoolSize: process.env.DB_MAX_POOL_SIZE || 10,
      minIdle: process.env.DB_MIN_IDLE || 2,
    });
  }

  if (hasVar('KAFKA_BOOTSTRAP_SERVERS')) {
    config.kafka = kafkaSchema.parse({
      bootstrapServers: process.env.KAFKA_BOOTSTRAP_SERVERS,
      consumerGroup: process.env.KAFKA_CONSUMER_GROUP || 'default',
    });
  }

  if (hasVar('REDIS_URL')) {
    config.redis = redisSchema.parse({
      url: process.env.REDIS_URL,
    });
  }

  return config;
}
