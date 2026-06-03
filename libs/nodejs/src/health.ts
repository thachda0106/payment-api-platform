/**
 * Kubernetes-style probe endpoints with cached dependency registry.
 *
 * Registers Fastify routes:
 *   - GET /liveness   → always 200
 *   - GET /readiness  → 200 if all deps OK, 503 otherwise (cached, TTL 5s)
 *   - GET /startup    → 503 until first successful readiness, then 200 permanently
 */

import { FastifyPluginAsync } from 'fastify';
import { randomUUID } from 'crypto';

// ─── Types ───────────────────────────────────────────────────────────────

export enum DependencyStatus {
  OK = 'ok',
  DOWN = 'down',
  UNUSED = 'unused',
}

export interface CheckResult {
  status: DependencyStatus;
  latencyMs: number;
  lastChecked: string; // ISO-8601
}

type CheckFn = () => Promise<boolean> | boolean;

interface ProbeResponse {
  status: string;
  service: string;
  version: string;
  timestamp: string;
  uptime: number;
  checks?: Record<string, CheckResult>;
}

// ─── Cached Registry ─────────────────────────────────────────────────────

export class CachedDependencyRegistry {
  private checks: Map<string, CheckFn> = new Map();
  private cache: Map<string, { result: CheckResult; time: number }> = new Map();
  private ttlMs: number;

  constructor(ttlSeconds: number = 5) {
    this.ttlMs = ttlSeconds * 1000;
  }

  register(name: string, checkFn: CheckFn): void {
    this.checks.set(name, checkFn);
  }

  async getStatuses(): Promise<Record<string, CheckResult>> {
    const now = Date.now();
    const results: Record<string, CheckResult> = {};

    for (const [name, checkFn] of this.checks) {
      const cached = this.cache.get(name);
      if (cached && (now - cached.time) < this.ttlMs) {
        results[name] = cached.result;
        continue;
      }

      const start = performance.now();
      let healthy: boolean;
      try {
        healthy = await checkFn();
      } catch {
        healthy = false;
      }
      const latencyMs = Math.round((performance.now() - start) * 100) / 100;

      const result: CheckResult = {
        status: healthy ? DependencyStatus.OK : DependencyStatus.DOWN,
        latencyMs,
        lastChecked: new Date().toISOString(),
      };
      this.cache.set(name, { result, time: now });
      results[name] = result;
    }

    return results;
  }

  invalidate(): void {
    this.cache.clear();
  }
}

// ─── Fastify Plugin ──────────────────────────────────────────────────────

interface HealthPluginOptions {
  serviceName: string;
  version: string;
  registry: CachedDependencyRegistry;
}

const startTime = Date.now();

function makeResponse(status: string, service: string, version: string, checks?: Record<string, CheckResult>): ProbeResponse {
  return {
    status,
    service,
    version,
    timestamp: new Date().toISOString(),
    uptime: Math.round((Date.now() - startTime) / 10) / 100,
    checks,
  };
}

export const healthPlugin: FastifyPluginAsync<HealthPluginOptions> = async (app, opts) => {
  const { serviceName, version, registry } = opts;

  // GET /liveness — always 200
  app.get('/liveness', async () => {
    return makeResponse('ok', serviceName, version);
  });

  // GET /readiness — 200 or 503 based on deps
  app.get('/readiness', async (request, reply) => {
    const statuses = await registry.getStatuses();
    const allHealthy = Object.values(statuses).every(
      s => s.status === DependencyStatus.OK || s.status === DependencyStatus.UNUSED,
    );

    if (!allHealthy) reply.code(503);
    return makeResponse(allHealthy ? 'ok' : 'not_ready', serviceName, version, statuses);
  });

  // GET /startup — 503 until first successful readiness
  let started = false;
  app.get('/startup', async (request, reply) => {
    const statuses = await registry.getStatuses();
    const allHealthy = Object.values(statuses).every(
      s => s.status === DependencyStatus.OK || s.status === DependencyStatus.UNUSED,
    );
    if (allHealthy) started = true;

    if (!started) reply.code(503);
    return makeResponse(started ? 'ok' : 'not_ready', serviceName, version, statuses);
  });
};
