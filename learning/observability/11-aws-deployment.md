# Phase 11 — AWS Deployment

> **Duration**: 1 week | **Prerequisites**: Phases 1-10, AWS fundamentals
>
> **Goal**: Design production observability architecture on AWS, compare managed vs self-hosted services, and estimate costs.

---

## 11.1 Deployment Model Comparison

| Model | Observability Stack Runs On | Control | Ops Burden | Cost |
|-------|---------------------------|---------|------------|------|
| **EC2 Self-Managed** | EC2 instances, you manage everything | Full | High | Low-medium |
| **ECS Fargate** | ECS tasks, AWS manages containers | Medium | Medium | Medium |
| **EKS** | Kubernetes on EC2/Fargate | Full (K8s) | Medium-high | Medium-high |
| **AWS Managed Services** | AWS manages the service | Low | Low | High |

---

## 11.2 AWS Managed vs Self-Hosted Comparison

### 11.2.1 Metrics

| | Amazon Managed Prometheus (AMP) | Self-Hosted Prometheus on EC2/EKS |
|---|---|---|
| **Setup** | AWS creates workspace | Deploy Helm chart + configure PVC |
| **Scaling** | Automatic (AWS managed) | Manual (vertical or Thanos horizontal) |
| **High Availability** | Multi-AZ by default | Requires 2+ replicas + multi-AZ node placement |
| **Retention** | 150 days (included) | Depends on EBS volume size |
| **Query** | Same PromQL, same Grafana | Same, plus local admin |
| **Cost** | ~$0.30 per 10M samples ingested | EC2 + EBS costs (predictable) |
| **Limits** | 240K samples/sec ingestion | Hardware limits (~10M active series) |
| **Remote Write** | Accepts Prometheus remote write | Can be configured |

**AMP cost analysis:**

```
Medium system: 50 services × 500 metrics × 1 sample/15s
= 50 × 500 × 4/minute = 100,000 samples/minute ≈ 1,667 samples/sec
= 1,667 × 86,400 × 30 = 4.3 billion samples/month

Cost: 4.3B × $0.03/10M = $12.90/month  (very cheap)

Large system: 500 services × 2,000 metrics × 1/15s
= 500 × 2000 × 4/min = 4,000,000 samples/min ≈ 66,667/s
= 66,667 × 86,400 × 30 = 172.8 billion samples/month

Cost: 172.8B × $0.03/10M = $518/month
+ self-hosted Thanos for long-term: $200/month (S3 storage)
Total: ~$718/month
```

### 11.2.2 Traces

| | AWS X-Ray | Self-Hosted Jaeger |
|---|---|---|
| **Protocol** | X-Ray SDK (proprietary) | OTLP (open standard) |
| **Sampling** | Fixed rate or reservoir | Full head + tail sampling control |
| **Storage** | 30 days included | Configurable retention |
| **Search** | Basic (trace ID, annotation) | Rich (any span tag, full text) |
| **Integration** | Native AWS service maps | OpenTelemetry ecosystem |
| **Cost** | $5 per 1M traces recorded | EC2 + EBS/OpenSearch costs |
| **Vendor lock-in** | High (X-Ray format) | Low (OTLP standard) |

**X-Ray cost trap**: X-Ray charges per TRACE RECORDED, not per span. A trace with 50 spans costs the same as a trace with 1 span. This incentivizes... keeping traces shallow. Jaeger/OpenTelemetry has no such incentive.

### 11.2.3 Logs

| | CloudWatch Logs | Self-Hosted OpenSearch |
|---|---|---|
| **Ingestion** | $0.50/GB | Included (just EC2 + EBS) |
| **Storage** | $0.03/GB-month | EBS cost (~$0.08/GB-month gp3) |
| **Query** | CloudWatch Logs Insights | OpenSearch Query DSL (powerful) |
| **Retention** | Configurable per log group | ILM: hot/warm/cold/delete |
| **Search Speed** | Slow (scan-based) | Fast (inverted index) |
| **Integration** | Native AWS metrics/alarms | OpenSearch Dashboards + Grafana |
| **Cost at Scale** | Very high | Predictable, lower |

**CloudWatch Logs cost trap at scale:**

```
100 GB/day logs × 30 days = 3 TB/month

CloudWatch: 3 TB × $0.50/GB ingest = $1,500
           + 3 TB × $0.03/GB storage = $90
           = $1,590/month

OpenSearch: 3 × i3en.xlarge = $1,200/month (storage included)
           = $1,200/month (and better search)
```

### 11.2.4 AWS OpenSearch Service vs Self-Hosted

| | AWS OpenSearch Service | Self-Hosted OpenSearch on EC2 |
|---|---|---|
| **Setup** | Console/CloudFormation | Deploy and configure cluster |
| **Patching** | AWS managed (minor versions) | You manage |
| **Scaling** | Add nodes (or use UltraWarm) | Add EC2 instances, rebalance |
| **Backup** | Automated snapshots to S3 | Manual or custom automation |
| **VPC** | Native VPC integration | Manual VPC + security groups |
| **IAM** | Fine-grained IAM access control | Custom auth (basic/internal) |
| **Cost** | ~30% premium over EC2 | Raw EC2 costs |

**When AWS Managed makes sense**: Small/medium teams with limited ops bandwidth. The premium pays for operational simplicity.

**When self-hosted makes sense**: Large scale (the 30% premium is significant), custom configurations (plugins, JVM tuning), multi-AZ with custom placement.

---

## 11.3 EKS Architecture (Recommended)

### 11.3.1 Architecture Diagram

```
                                    ┌──────────────────────────┐
                                    │       Route 53            │
                                    │  grafana.example.com      │
                                    └───────────┬──────────────┘
                                                │
                                    ┌───────────▼──────────────┐
                                    │    AWS ALB (Ingress)      │
                                    │    TLS termination        │
                                    └───┬──────────┬───────────┘
                                        │          │
                    ┌───────────────────┼──────────┼───────────────────┐
                    │                   │          │                   │
                    │        EKS Cluster (us-east-1)                   │
                    │                                                   │
                    │  ┌──────────────────────────────────────────┐   │
                    │  │         observability namespace            │   │
                    │  │                                            │   │
                    │  │  ┌────────────┐  ┌──────────────────┐    │   │
                    │  │  │  Grafana   │  │   Alertmanager   │    │   │
                    │  │  │ (Deploy)   │  │  (StatefulSet)   │    │   │
                    │  │  └────────────┘  └──────────────────┘    │   │
                    │  │                                            │   │
                    │  │  ┌──────────────────────────────────┐    │   │
                    │  │  │  Prometheus (StatefulSet, 2 reps) │    │   │
                    │  │  │  Remote Write → AMP (150d ret)    │    │   │
                    │  │  └──────────────────────────────────┘    │   │
                    │  │                                            │   │
                    │  │  ┌────────────┐  ┌──────────────────┐    │   │
                    │  │  │ OTel Gateway│  │  Jaeger (Deploy)   │    │   │
                    │  │  │ (Deploy, HA)│  │  Storage:         │    │   │
                    │  │  └────────────┘  │  AWS OpenSearch Svc│    │   │
                    │  │                  └──────────────────┘    │   │
                    │  └──────────────────────────────────────────┘   │
                    │                                                   │
                    │  ┌──────────────────────────────────────────┐   │
                    │  │         kube-system namespace              │   │
                    │  │  ┌────────────┐  ┌──────────────────┐    │   │
                    │  │  │ OTel Coll. │  │  Node Exporter   │    │   │
                    │  │  │ (DaemonSet)│  │  (DaemonSet)     │    │   │
                    │  │  └────────────┘  └──────────────────┘    │   │
                    │  └──────────────────────────────────────────┘   │
                    │                                                   │
                    │  ┌──────────────────────────────────────────┐   │
                    │  │         app namespace                     │   │
                    │  │  ┌────────────────┐                      │   │
                    │  │  │Payment Service │ → OTel SDK (OTLP)    │   │
                    │  │  │(+ Sidecar Col)│                      │   │
                    │  │  └────────────────┘                      │   │
                    │  └──────────────────────────────────────────┘   │
                    │                                                   │
                    └───────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
            ┌───────▼──────┐ ┌─────▼─────┐ ┌───────▼──────┐
            │  AWS Managed │ │ Amazon    │ │   AWS MSK    │
            │  Prometheus  │ │ OpenSearch│ │   (Kafka)    │
            │  (AMP)       │ │ Service   │ │              │
            └──────────────┘ └───────────┘ └──────────────┘
```

### 11.3.2 Why Remote Write to AMP

Prometheus on EKS handles 15s scrape and short-term alerting. Remote Write to AMP provides:
- **Long-term retention**: 150 days without managing 150 days of EBS
- **High availability**: AMP is multi-AZ without you managing it
- **Regional aggregation**: Multiple EKS clusters remote-write to one AMP workspace

---

## 11.4 Network Design

### 11.4.1 VPC Architecture

```
VPC (10.0.0.0/16)
├── Public Subnets (ALB, NAT Gateway)
│   └── ALB → Ingress → Grafana/Jaeger UI
├── Private Subnet Tier 1 (EKS worker nodes)
│   └── Application pods + Observability pods
├── Private Subnet Tier 2 (AWS Managed Services)
│   └── Amazon Managed Prometheus endpoints
│   └── AWS OpenSearch Service endpoints
│   └── RDS (Grafana database)
└── VPC Endpoints (avoid NAT Gateway for AWS API calls)
    ├── ecr.api (pull images)
    ├── ecr.dkr (pull image layers)
    ├── sts (IAM roles)
    └── s3 (Thanos block storage, backup)
```

### 11.4.2 Security Groups

```
SG: otel-collector
  Inbound: OTLP gRPC (4317) from VPC CIDR
           OTLP HTTP (4318) from VPC CIDR
           Health Check (13133) from ALB SG

SG: prometheus
  Inbound: HTTP (9090) from Grafana SG + ALB SG
  No public access. No direct app access.

SG: opensearch
  Inbound: HTTPS (9200) from OTel Collector SG + Jaeger Collector SG
  VPC-only. Never public.

SG: grafana
  Inbound: HTTP (3000) from ALB SG
  No direct pod access. All traffic through ALB.
```

---

## 11.5 Observability for AWS Services

### 11.5.1 RDS (PostgreSQL)

```
Metrics:
  ├── CPUUtilization, DatabaseConnections, FreeableMemory
  ├── ReadIOPS, WriteIOPS, ReadLatency, WriteLatency
  ├── DiskQueueDepth (saturation)
  └── ReplicaLag (read replica)

Enhanced Monitoring (1s granularity):
  ├── OS processes, CPU per process
  ├── Memory per process
  └── File system usage

Default CloudWatch: 60s granularity, free
Enhanced Monitoring: 1s granularity, CloudWatch Logs cost
```

**Key RDS alerts:**
- `DatabaseConnections / max_connections > 0.8` → Connection pool near exhaustion
- `ReplicaLag > 60s` → Read replica stale
- `FreeableMemory < 256 MB` → Risk of OOM
- `DiskQueueDepth > 10` → Storage I/O saturated

### 11.5.2 ElastiCache (Redis)

```
Metrics:
  ├── CacheHitRate (hits / (hits + misses))
  ├── Evictions (memory pressure)
  ├── CurrConnections
  ├── CPUUtilization (single-threaded → one core max)
  └── ReplicationLag

Key Redis alerts:
  ├── Evictions > 0 → Memory full, dropping keys
  ├── CacheHitRate < 0.8 → Low cache effectiveness
  └── ReplicationLag > 10s → Failover will lose data
```

### 11.5.3 MSK (Kafka)

```
Metrics:
  ├── ActiveControllerCount (should be 1)
  ├── UnderReplicatedPartitions (should be 0)
  ├── OfflinePartitions (should be 0)
  ├── BytesInPerSec, BytesOutPerSec
  └── KafkaDataLogsDiskUsed (storage)

Open Monitoring (Prometheus via JMX Exporter):
  ├── Consumer lag per group/topic/partition
  ├── Producer request rate, error rate
  └── Broker request handler idle ratio
```

**Key MSK alerts:**
- `ActiveControllerCount != 1` → Cluster instability
- `UnderReplicatedPartitions > 0` → Data loss risk
- `OfflinePartitions > 0` → Data unavailable

### 11.5.4 ALB (Application Load Balancer)

```
Metrics:
  ├── RequestCount, HTTPCode_Target_2XX/4XX/5XX
  ├── TargetResponseTime (p50, p95, p99)
  ├── RejectedConnectionCount (saturation)
  └── HealthyHostCount, UnHealthyHostCount

Key ALB alerts:
  ├── HTTPCode_Target_5XX / RequestCount > 0.01 → 1% error rate
  ├── TargetResponseTime p99 > SLO threshold
  └── HealthyHostCount == 0 → All targets unhealthy
```

---

## 11.6 Cost Optimization

### 11.6.1 Small System (10 services, 100 GB logs/day)

```
Self-Hosted on EKS:
  OpenSearch:    3× t3.medium ($108)     = $108/month
  Prometheus:    1× t3.large ($60)       = $60/month
  Jaeger:       1× t3.medium ($36)        = $36/month
  Grafana:      1× t3.small ($18)         = $18/month
  OTel Collectors: DaemonSet (shared)     = $0/month
  EBS Storage:  300 GB gp3 ($24)          = $24/month
  ─────────────────────────────────
  Estimated Total: ~$246/month

AWS Managed (hybrid):
  AMP:          4.3B samples/month         = $13/month   (metrics)
  OpenSearch Svc: 1× t3.medium.search ($74)= $74/month   (logs)
  X-Ray:        100M traces/month          = $500/month  (traces - OUCH)
  Grafana:      EC2 t3.small ($18)         = $18/month
  ─────────────────────────────────
  Estimated Total: ~$605/month
```

### 11.6.2 Medium System (50 services, 500 GB logs/day)

```
Self-Hosted on EKS:
  OpenSearch:    3× i3en.xlarge ($900) + 3x i3en.xlarge ($900,warm) = $1,800/month
  Prometheus:    2× r5.xlarge ($400)      = $400/month
  Jaeger:       2× c5.xlarge ($300)        = $300/month
  Grafana:      2× t3.medium ($72)         = $72/month
  Alertmanager: 2× t3.small ($36)          = $36/month
  Thanos:       S3 ($50) + EC2 ($100)      = $150/month
  OTel Gateways: 3× c5.large ($255)        = $255/month
  EBS Storage:  3 TB gp3 ($240)            = $240/month
  ─────────────────────────────────
  Estimated Total: ~$3,253/month

AWS Managed (hybrid):
  AMP:          43B samples/month           = $129/month
  OpenSearch Svc: 6× i3en.xlarge ($3,486)  = $3,486/month
  Jaeger:       Self-hosted ($300)          = $300/month
  Grafana:      Grafana Cloud or self-host  = $36/month
  ─────────────────────────────────
  Estimated Total: ~$3,951/month
```

### 11.6.3 Large System (500 services, 2 TB logs/day)

```
Self-Hosted on EKS:
  OpenSearch:    5× i3en.3xlarge (hot) + 5× i3en.xlarge (warm) = $8,300/month
  Prometheus:    Thanos Receive (5× r5.2xlarge)                  = $2,500/month
  Jaeger:       5× c5.2xlarge ($1,500)                           = $1,500/month
  Grafana:      2× t3.medium ($72)                                = $72/month
  Alertmanager: 2× t3.small ($36)                                 = $36/month
  Thanos:       S3 ($300) + Querier ($300)                        = $600/month
  OTel:         DaemonSet (shared) + 10× Gateway ($1,700)         = $1,700/month
  EBS:          10 TB gp3 ($800)                                   = $800/month
  ─────────────────────────────────
  Estimated Total: ~$15,508/month

AWS Managed:
  AMP:          430B samples/month         = $1,290/month
  OpenSearch Svc: 10× i3en.3xlarge         = $17,400/month
  Jaeger:       Self-hosted ($1,500)       = $1,500/month
  Grafana:      Managed Grafana ($500)     = $500/month
  ─────────────────────────────────
  Estimated Total: ~$20,690/month
```

**Key cost insight**: AWS managed services charge per unit (sample, trace, GB). At large scale, self-hosted is significantly cheaper. The crossover point is around 50-100 services.

---

## 11.7 Common Misconceptions

### "CloudWatch can replace Prometheus"

CloudWatch is a general-purpose AWS monitoring service. Prometheus is a purpose-built TSDB with PromQL. CloudWatch cannot:
- Compute `histogram_quantile()` across instances
- Handle high-cardinality metrics (cost-prohibitive)
- Integrate with OTel ecosystem natively
- Run recording rules and alert rules with PromQL expressiveness

### "X-Ray is equivalent to Jaeger"

X-Ray uses a proprietary data format and SDK. Jaeger uses OTLP (open standard). X-Ray traces cannot be exported to other backends. Jaeger traces can be exported to ANY OTLP-compatible backend. X-Ray is simpler to set up; Jaeger is more powerful and portable.

### "AWS OpenSearch Service is always worth the premium"

At small scale (< $500/month), the managed premium is acceptable for ops simplicity. At large scale (> $5,000/month), the 30% premium represents thousands of dollars that could fund the ops work to self-manage.

---

## Interview Questions — Phase 11

1. **Compare Amazon Managed Prometheus vs self-hosted Prometheus on EKS. When would you choose each?**

   *Answer core points*: AMP: zero ops, 150-day retention, automatic scaling, pay-per-sample. Self-hosted: predictable cost, full configuration control, unlimited cardinality (within hardware limits). Choose AMP when: small/medium teams, variable metric volume, want to minimize ops. Choose self-hosted when: large/predictable volume, need custom configurations (remote read, specific retention tuning), cost-sensitive at scale.

2. **Design the network architecture for an EKS observability stack. What goes in public subnets vs private subnets?**

   *Answer core points*: ALB and NAT Gateway in public subnets. All observability components (Prometheus, OpenSearch, Jaeger, Grafana, Collectors) in private subnets. AWS managed services via VPC endpoints (no NAT traversal). Only Grafana/Jaeger UI exposed via ALB. No service directly accessible from internet. Security groups: least-privilege, service-to-service only.

3. **Why is CloudWatch Logs expensive at scale compared to self-hosted OpenSearch?**

   *Answer core points*: CloudWatch charges per GB ingested ($0.50/GB) plus per GB stored ($0.03/GB). At 500 GB/day, ingest alone is $7,500/month. Self-hosted OpenSearch on i3en instances includes NVMe storage in the EC2 price. At 500 GB/day, 6× i3en.xlarge ($1,800/month) handles ingestion + 7-day retention. The per-GB pricing model breaks down at scale.

4. **What's the cost crossover point where self-hosted becomes cheaper than AWS managed services?**

   *Answer core points*: Around 40-60 services or $2,000-3,000/month. Below this, the managed premium is small and the ops savings are large. Above this, the per-unit pricing of managed services (AMP samples, OpenSearch instance premiums, X-Ray traces) grows faster than self-hosted EC2 costs. The crossover is earlier for logs (per GB pricing) than for metrics (per sample pricing).

---

**Next: Phase 12 — Incident Response**
