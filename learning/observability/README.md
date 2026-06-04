# Observability Engineering — Complete Curriculum

> **Purpose**: Master observability from first principles to production architecture at Senior/Staff Engineer level.
>
> **Audience**: Backend Engineers aspiring to Senior/Staff/Principal roles who need to understand observability internals, not just tooling.
>
> **Teaching Philosophy**: Concepts first, internals second, implementation third, production fourth. Trade-offs always. No YAML before understanding.
>
> **Prerequisites**: Phase 0-2 of the main curriculum (Computer Science, OS/Networking, Databases).

---

## Architecture We Will Master

```
                    ┌──────────────────────────────────────┐
                    │           Grafana (Unified UI)        │
                    │   Metrics │ Traces │ Logs │ Alerts   │
                    └──┬──────────┬──────────┬─────────┬───┘
                       │          │          │         │
              ┌────────┼──────────┼──────────┼─────────┼────────┐
              │  ┌─────▼──────┐ ┌▼──────────▼┐ ┌──────▼──────┐ │
              │  │ Prometheus │ │   Jaeger    │ │ OpenSearch  │ │
              │  │  (Metrics) │ │  (Traces)   │ │   (Logs)    │ │
              │  └─────▲──────┘ └──────▲──────┘ └──────▲──────┘ │
              │        │               │               │        │
              │        │    ┌──────────▼──────────┐    │        │
              │        │    │ OTel Collector      │    │        │
              │        │    │ Receivers/Processors│    │        │
              │        │    │ Exporters/Connectors│    │        │
              │        │    └──────────▲──────────┘    │        │
              │        │               │               │        │
              └────────┼───────────────┼───────────────┼────────┘
                       │               │               │
              ┌────────▼───────────────▼───────────────▼────────┐
              │          OpenTelemetry SDK (Per Service)         │
              │     Spring Boot │ NestJS │ FastAPI │ Go         │
              └──────────────────────┬──────────────────────────┘
                                     │
                          ┌──────────▼──────────┐
                          │     Applications     │
                          │  REST │ gRPC │ Kafka │
                          │  DB Queries │ Redis  │
                          └─────────────────────┘
```

---

## Phase Summary

| # | Phase | Focus | Core Mental Model |
|---|-------|-------|-------------------|
| 1 | Observability Foundations | Why observability exists | Monitoring ≠ Observability |
| 2 | OpenTelemetry Deep Dive | Unified telemetry standard | Context Propagation |
| 3 | OTel Collector Internals | Pipeline architecture | Receiver → Processor → Exporter |
| 4 | Prometheus Deep Dive | Time-series database internals | Pull model + TSDB |
| 5 | Jaeger Deep Dive | Distributed tracing storage | Span indexing + query |
| 6 | OpenSearch Deep Dive | Log storage and search | Inverted index + ILM |
| 7 | Alertmanager Deep Dive | Alert lifecycle management | Route → Group → Silence |
| 8 | Grafana Deep Dive | Unified visualization | Data source correlation |
| 9 | Local Development | Docker Compose stack | Full stack on workstation |
| 10 | Kubernetes Deployment | Production on K8s | Helm + Operators |
| 11 | AWS Deployment | Cloud-native architecture | Managed vs self-hosted |
| 12 | Incident Response | Real troubleshooting | Alert → Metric → Trace → Log |
| 13 | Senior Backend Engineers | What to instrument | RED + USE application |
| 14 | Staff Engineer Level | Platform architecture | 1000-service design |

---

## How to Use This Curriculum

1. **Read sequentially.** Each phase builds on all previous phases.
2. **Understand the "WHY" before the "HOW".** Every section starts with motivation.
3. **Draw diagrams.** For every data flow described, draw it yourself.
4. **Read the source.** When we discuss Prometheus TSDB, open the Prometheus source code.
5. **Interview questions** at the end of each phase test understanding, not memorization.

---

## What This Curriculum Is NOT

- NOT a "how to install Prometheus" guide
- NOT a YAML copy-paste collection
- NOT a Grafana dashboard click-through tutorial
- NOT Docker Compose-first (we deploy to production, not laptops)

This curriculum teaches you to DESIGN, DEPLOY, OPERATE, and EVOLVE observability platforms. Not to use them.

---

## Learning Outcomes

By the end of this curriculum, you will be able to:

1. Design an observability architecture from scratch for any system size
2. Explain OpenTelemetry's data model and why OpenTracing/OpenCensus failed
3. Understand Prometheus TSDB internals and diagnose high cardinality
4. Deploy and scale Jaeger for millions of spans/minute
5. Design OpenSearch clusters with hot/warm/cold tiering
6. Write alert rules that don't cause alert fatigue
7. Correlate metrics → traces → logs to find root causes
8. Estimate costs for self-hosted vs managed observability on AWS
9. Pass Senior/Staff observability interview questions
10. Make architecture decisions with justified trade-off reasoning

---

**Start with Phase 1: Observability Foundations.**
