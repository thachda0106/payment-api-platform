# Phase 12 — Infrastructure Design (IaC)

## MoMo-like Payment API Platform

> **Document Status**: Draft v1.0
> **Last Updated**: 2026-05-20
> **Classification**: CONFIDENTIAL — Internal Use Only
> **Audience**: Platform Engineers, SRE, DevOps, Security Team
> **Input**: Phase 11 — Technology Selection (v1.0); Phase 06 — High-Level Architecture (v6.0); Phase 05 — Security Architecture (v2.0)
> **Author Level**: Principal Platform Engineer
> **Approval Gate**: 🧪 Quality Gate (Automated CI Checks + Peer Review)

---

## Table of Contents

1. [Goal & Scope](#1-goal--scope)
2. [Key Decisions](#2-key-decisions)
3. [Documents Produced](#3-documents-produced)
4. [Architecture Artifacts](#4-architecture-artifacts)
   - [4.1 AWS Account & Environment Strategy](#41-aws-account--environment-strategy)
   - [4.2 VPC & Network Design](#42-vpc--network-design)
   - [4.3 EKS Cluster Design](#43-eks-cluster-design)
   - [4.4 Database — Aurora PostgreSQL](#44-database--aurora-postgresql)
   - [4.5 Cache — ElastiCache Redis](#45-cache--elasticache-redis)
   - [4.6 Messaging — MSK Kafka](#46-messaging--msk-kafka)
   - [4.7 Search — OpenSearch](#47-search--opensearch)
   - [4.8 S3 — Storage & Archival](#48-s3--storage--archival)
   - [4.9 IAM — Roles, Policies, IRSA](#49-iam--roles-policies-irsa)
   - [4.10 DNS, TLS & CDN](#410-dns-tls--cdn)
   - [4.11 Terraform Module Structure](#411-terraform-module-structure)
5. [Example Deliverables](#5-example-deliverables)
6. [Key Questions](#6-key-questions)
7. [Implementation Tasks](#7-implementation-tasks)
8. [Common Mistakes](#8-common-mistakes)
9. [KPIs & Exit Criteria](#9-kpis--exit-criteria)
10. [Connection to Next Phase](#10-connection-to-next-phase)

---

## 1. Goal & Scope

### 1.1 Goal

Design the complete AWS infrastructure footprint — networking, compute, data stores, messaging, IAM, DNS, and CDN — as fully parameterized Terraform modules. Every resource is defined in code, peer-reviewed via Atlantis, and deployed through a GitOps pipeline before any application code is written.

### 1.2 Scope

- **AWS accounts**: 4 (Prod, Staging, Sandbox, Shared Services)
- **Regions**: 2 (ap-southeast-1 active, ap-southeast-3 passive DR)
- **VPCs**: 2 per region (Services + Data), with Transit Gateway
- **EKS clusters**: 1 per environment per region
- **Aurora clusters**: 3 databases (financial_core, payment, idempotency)
- **MSK cluster**: 1 per region (3-broker, 12 partitions/topic)
- **ElastiCache**: 1 per region (cluster mode, 3 shards)
- **OpenSearch**: 1 domain per region
- **Terraform**: ~15 modules, 4 environments, Atlantis PR workflow

### 1.3 Input Alignment

| Upstream Phase | Infrastructure Dependency |
|---------------|--------------------------|
| **Phase 11 — Technology Selection** | AWS, EKS, Aurora, MSK, ElastiCache, OpenSearch, Terraform, ArgoCD |
| **Phase 06 — High-Level Architecture** | Multi-region Active-Passive (§12), service mesh (§7), network zones (§11) |
| **Phase 05 — Security Architecture** | VPC boundaries (§10), WAF (§8), IAM roles, security groups, encryption at rest (§7), mTLS (§6) |
| **Phase 09 — Event Schema** | Kafka topic topology, partition counts, replication factor (§4.2) |
| **Phase 10 — System Flows** | Network hop budgets (< 5ms WAF, < 2ms Gateway), CDN edge termination |

---

## 2. Key Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D01 | **One AWS account per environment** | Blast radius isolation. Staging misconfiguration cannot affect Prod. Billing separation for FinOps. |
| D02 | **Separate VPCs for Services and Data** | Network-level isolation between compute (EKS) and data stores (Aurora, MSK, ElastiCache). Security groups enforce least-privilege access. |
| D03 | **EKS with managed node groups** | AWS handles node provisioning, scaling, patching. Karpenter for intelligent pod scheduling and bin-packing. |
| D04 | **Aurora I/O-Optimized for financial core** | Predictable pricing for I/O-intensive ledger workloads. Standard for payment_db, idempotency_db. |
| D05 | **MSK with KRaft (no Zookeeper)** | Kafka 3.7 KRaft mode reduces operational complexity. 3-broker minimum with multi-AZ placement. |
| D06 | **IRSA (IAM Roles for Service Accounts) everywhere** | Pod-level AWS permissions — no static credentials. Finer-grained than node-level IAM roles. |
| D07 | **Atlantis for Terraform PR workflow** | `atlantis plan` on PR open, `atlantis apply` on PR merge. Enforces peer review + policy checks before infrastructure changes. |

---

## 3. Documents Produced

| Document | Location | Status |
|----------|----------|--------|
| **Infrastructure Design Reference** | `docs/stages/C-platform-infrastructure/12-infrastructure-design.md` (this document) | ✅ v1.0 |
| **Infrastructure Modules Reference** | `docs/cross-cutting/infrastructure/infrastructure-modules.md` | 🚧 Pending |
| **Cost Model** | `docs/cross-cutting/infrastructure/cost-model.md` | 🚧 Pending (Phase 21) |

---

## 4. Architecture Artifacts

### 4.1 AWS Account & Environment Strategy

```
AWS Organization
├── Management Account (billing only, no resources)
├── Shared Services Account
│   ├── ECR (container registry)
│   ├── Route 53 (DNS hosting)
│   ├── CI/CD runners (GitHub Actions self-hosted)
│   └── Atlantis (Terraform automation)
├── Production Account (ap-southeast-1 active, ap-southeast-3 passive DR)
│   ├── EKS Prod
│   ├── Aurora Prod (Multi-AZ)
│   ├── MSK Prod
│   ├── ElastiCache Prod
│   └── OpenSearch Prod
├── Staging Account (ap-southeast-1 only)
│   ├── EKS Staging (50% scale of Prod)
│   ├── Aurora Staging (Single-AZ)
│   └── MSK Staging (3-broker, same config)
└── Sandbox Account (ap-southeast-1 only)
    ├── EKS Sandbox (minimal, auto-scaled-to-zero)
    └── Aurora Sandbox (serverless v2)
```

**Environment Sizing**:

| Environment | EKS Nodes | Aurora Instance | MSK Brokers | Purpose |
|------------|-----------|-----------------|-------------|---------|
| **Prod** | 6 × `m6i.xlarge` (min) | `db.r6g.xlarge` | 3 × `kafka.m7g.large` | Live traffic |
| **Staging** | 3 × `m6i.large` | `db.r6g.large` | 3 × `kafka.t3.small` | Integration testing |
| **Sandbox** | Fargate only | Serverless v2 (0.5–2 ACU) | — (shared staging) | Developer testing |

---

### 4.2 VPC & Network Design

#### 4.2.1 Per-Region VPC Topology

```
Region: ap-southeast-1
├── VPC: Services (10.0.0.0/16)
│   ├── Public Subnets (10.0.0.0/20, 10.0.16.0/20, 10.0.32.0/20)
│   │   └── ALB/NLB, NAT Gateways, Bastion
│   ├── Private Subnets — Compute (10.0.64.0/19, 10.0.96.0/19, 10.0.128.0/19)
│   │   └── EKS Worker Nodes + Fargate
│   └── Private Subnets — Internal (10.0.160.0/19, 10.0.192.0/19, 10.0.224.0/19)
│       └── Kong Gateway Pods, Istio Waypoints
│
├── VPC: Data (10.1.0.0/16)
│   ├── Private Subnets — Database (10.1.0.0/20, 10.1.16.0/20, 10.1.32.0/20)
│   │   └── Aurora PostgreSQL (Multi-AZ)
│   ├── Private Subnets — Cache (10.1.48.0/20, 10.1.64.0/20, 10.1.80.0/20)
│   │   └── ElastiCache Redis (cluster mode)
│   ├── Private Subnets — Messaging (10.1.96.0/20, 10.1.112.0/20, 10.1.128.0/20)
│   │   └── MSK Kafka brokers
│   └── Private Subnets — Search (10.1.144.0/20, 10.1.160.0/20, 10.1.176.0/20)
│       └── OpenSearch data nodes
│
├── Transit Gateway
│   ├── Services VPC ↔ Data VPC
│   └── Services VPC ↔ Shared Services VPC (ECR, Route 53)
│
└── VPC Endpoints (Gateway + Interface)
    ├── S3 Gateway Endpoint
    ├── DynamoDB Gateway Endpoint
    ├── ECR Interface Endpoint
    ├── STS Interface Endpoint
    ├── Secrets Manager Interface Endpoint
    └── KMS Interface Endpoint
```

#### 4.2.2 Security Groups (Ingress Rules)

| Source | Destination | Port | Protocol | Purpose |
|--------|-------------|------|----------|---------|
| ALB (public) | Kong Gateway (EKS) | 8443 | TCP | API traffic (TLS-terminated) |
| Kong Gateway | Payment Service (EKS) | 8080 | TCP | API routing |
| EKS Compute Subnets | Aurora Subnets | 5432 | TCP | PostgreSQL |
| EKS Compute Subnets | ElastiCache Subnets | 6379 | TCP | Redis |
| EKS Compute Subnets | MSK Subnets | 9094, 9096 | TCP | Kafka (PLAINTEXT, TLS) |
| EKS Compute Subnets | OpenSearch Subnets | 443 | TCP | OpenSearch API |

#### 4.2.3 Cross-Region Connectivity

```
ap-southeast-1 (Active)                     ap-southeast-3 (Passive DR)
┌──────────────────────┐                   ┌──────────────────────┐
│ EKS Prod             │                   │ EKS DR (stopped)     │
│ Aurora (writer)      │─── Async Repl ──► │ Aurora (replica)     │
│ MSK (active)         │─── MirrorMaker2 ─►│ MSK (passive)        │
│ ElastiCache (active) │                   │ ElastiCache (empty)  │
└──────────────────────┘                   └──────────────────────┘
         │                                           │
         └──────────── Route 53 ─────────────────────┘
              (failover: ap-southeast-1 → ap-southeast-3)
```

---

### 4.3 EKS Cluster Design

#### 4.3.1 Cluster Configuration

```hcl
# Terraform — EKS Cluster
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "20.0"

  cluster_name    = "payment-platform-prod"
  cluster_version = "1.30"

  # VPC
  vpc_id     = module.services_vpc.vpc_id
  subnet_ids = module.services_vpc.private_compute_subnets

  # Authentication
  authentication_mode = "API_AND_CONFIG_MAP"
  
  # Access
  cluster_endpoint_public_access  = false  # Private only
  cluster_endpoint_private_access = true

  # Add-ons
  cluster_addons = {
    coredns    = { most_recent = true }
    kube-proxy = { most_recent = true }
    vpc-cni    = { most_recent = true }
    aws-ebs-csi-driver = { most_recent = true }
    eks-pod-identity-agent = { most_recent = true }
  }
}
```

#### 4.3.2 Node Groups

| Node Group | Instance Type | Min | Max | Labels | Purpose |
|------------|--------------|-----|-----|--------|---------|
| `services` | `m6i.xlarge` (4 vCPU, 16GB) | 6 | 20 | `workload=services` | All microservices |
| `gateway` | `c6i.xlarge` (4 vCPU, 8GB) | 2 | 6 | `workload=gateway` | Kong Gateway pods |
| `system` | `m6i.large` (2 vCPU, 8GB) | 2 | 4 | `workload=system` | ArgoCD, OTel collector, Fluent Bit |

#### 4.3.3 Karpenter (Autoscaling)

```yaml
# Karpenter NodePool — burst capacity via Spot
apiVersion: karpenter.sh/v1beta1
kind: NodePool
spec:
  template:
    spec:
      requirements:
        - key: "karpenter.sh/capacity-type"
          operator: In
          values: ["spot", "on-demand"]
        - key: "node.kubernetes.io/instance-type"
          operator: In
          values: ["m6i.xlarge", "m6i.2xlarge", "c6i.xlarge"]
      nodeClassRef:
        name: default
  limits:
    cpu: "200"
  disruption:
    consolidationPolicy: WhenUnderutilized
    consolidateAfter: 5m
```

#### 4.3.4 Pod-to-AWS IAM (IRSA)

| Service Account | AWS IAM Role | Permissions |
|----------------|-------------|-------------|
| `payment-service` | `payment-service-role` | `secretsmanager:GetSecretValue` (DB creds), `kms:Decrypt` |
| `webhook-sender` | `webhook-sender-role` | `secretsmanager:GetSecretValue` (webhook secrets) |
| `search-indexer` | `search-indexer-role` | `es:ESHttpPut`, `es:ESHttpPost` (OpenSearch) |
| `archive-sink` | `archive-sink-role` | `s3:PutObject` (event archival) |
| `debezium-connect` | `debezium-connect-role` | `rds:Describe*`, `secretsmanager:GetSecretValue` |

---

### 4.4 Database — Aurora PostgreSQL

#### 4.4.1 Cluster Topology

```
Aurora Cluster: financial-core-prod
├── Writer Instance (ap-southeast-1a)
│   └── db.r6g.xlarge (4 vCPU, 32GB)
├── Reader Instance (ap-southeast-1b)
│   └── db.r6g.xlarge
├── Reader Instance (ap-southeast-1c)
│   └── db.r6g.large (fallback, analytics)
└── DR Replica (ap-southeast-3a)
    └── db.r6g.xlarge (async replication)

Aurora Cluster: payment-prod
├── Writer Instance (ap-southeast-1a)
│   └── db.r6g.large (2 vCPU, 16GB)
├── Reader Instance (ap-southeast-1b)
│   └── db.r6g.large

Aurora Cluster: idempotency-prod
├── Writer Instance (ap-southeast-1a)
│   └── db.r6g.large (2 vCPU, 16GB)
└── Reader Instance (ap-southeast-1b)
    └── db.r6g.large
```

#### 4.4.2 Parameter Group (Critical Settings)

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| `max_connections` | 500 | Scaled to instance memory (32GB ÷ 64MB per conn) |
| `statement_timeout` | 30s | Hard limit — prevents runaway queries |
| `idle_in_transaction_session_timeout` | 60s | Prevents idle transactions holding locks |
| `log_min_duration_statement` | 100ms | Log slow queries for optimization |
| `log_statement` | `ddl` | Log schema changes for audit |
| `shared_preload_libraries` | `pg_stat_statements, pgoutput` | Query analytics + logical replication |
| `rds.logical_replication` | `1` | Required for Debezium CDC |

#### 4.4.3 Backup & PITR

| Setting | Value |
|---------|-------|
| Backup retention | 35 days |
| Point-in-time recovery | Enabled (5-minute granularity) |
| Backup window | 03:00–04:00 SGT |
| Snapshot export to S3 | Daily (for DR cross-region copy) |
| Deletion protection | Enabled |

---

### 4.5 Cache — ElastiCache Redis

#### 4.5.1 Cluster Configuration

```hcl
resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = "payment-platform-prod"
  description          = "Redis for idempotency, rate limiting, sessions"

  engine         = "redis"
  engine_version = "7.1"
  node_type      = "cache.r6g.large"

  num_cache_clusters = 3  # 1 primary + 2 replicas
  multi_az_enabled   = true
  automatic_failover_enabled = true

  cluster_mode {
    replicas_per_node_group = 1
    num_node_groups         = 2  # 2 shards × 2 nodes = 4 total
  }

  # Encryption
  at_rest_encryption_enabled  = true
  transit_encryption_enabled  = true
  auth_token                  = random_password.redis_auth.result

  # Maintenance
  maintenance_window          = "sun:04:00-sun:06:00"
  snapshot_retention_limit    = 7
  snapshot_window             = "03:00-04:00"
}
```

#### 4.5.2 Use Case Mapping

| Use Case | Eviction Policy | Max Memory | Key Pattern |
|----------|----------------|------------|-------------|
| Idempotency cache | `volatile-lru` | 25% | `idempotency:*` |
| Rate limiting | `noeviction` | 15% | `ratelimit:*` |
| JWT sessions | `volatile-lru` | 20% | `session:*` |
| API key cache | `volatile-lru` | 10% | `apikey:*` |
| Circuit breakers | `noeviction` | 5% | `circuit:*` |

---

### 4.6 Messaging — MSK Kafka

#### 4.6.1 Cluster Configuration

```hcl
resource "aws_msk_cluster" "kafka" {
  cluster_name           = "payment-platform-prod"
  kafka_version          = "3.7"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = "kafka.m7g.large"
    client_subnets  = module.data_vpc.private_messaging_subnets
    security_groups = [aws_security_group.msk.id]
    storage_info {
      ebs_storage_info {
        volume_size = 500  # GB per broker
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
    encryption_at_rest_kms_key_arn = aws_kms_key.msk.arn
  }

  configuration_info {
    arn      = aws_msk_configuration.kafka.arn
    revision = aws_msk_configuration.kafka.latest_revision
  }
}
```

#### 4.6.2 Broker Configuration

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| `auto.create.topics.enable` | `false` | Topics created via Terraform only |
| `default.replication.factor` | `3` | Survive 2 broker failures |
| `min.insync.replicas` | `2` | Durability guarantee |
| `unclean.leader.election.enable` | `false` | No data loss on leader election |
| `log.retention.hours` | `168` | 7 days (financial topics) |
| `log.segment.bytes` | `536870912` | 512MB segments |
| `num.partitions` | `12` | Default for new topics |

#### 4.6.3 Topic Provisioning

All topics provisioned via Terraform (Phase 09 catalog):

```hcl
# Example: payments.payment.succeeded
resource "kafka_topic" "payment_succeeded" {
  name               = "payments.payment.succeeded"
  partitions         = 12
  replication_factor = 3
  
  config = {
    "retention.ms"          = "604800000"  # 7 days
    "cleanup.policy"        = "delete"
    "compression.type"      = "zstd"
    "min.insync.replicas"   = "2"
    "max.message.bytes"     = "1048576"    # 1MB
  }
}
```

---

### 4.7 Search — OpenSearch

#### 4.7.1 Domain Configuration

```hcl
resource "aws_opensearch_domain" "search" {
  domain_name    = "payment-platform-prod"
  engine_version = "OpenSearch_2.11"

  cluster_config {
    instance_type            = "r6g.large.search"
    instance_count           = 3
    zone_awareness_enabled   = true
    dedicated_master_enabled = true
    dedicated_master_type    = "m6g.large.search"
    dedicated_master_count   = 3
  }

  ebs_options {
    ebs_enabled = true
    volume_size = 100  # GB
    volume_type = "gp3"
  }

  encrypt_at_rest {
    enabled    = true
    kms_key_id = aws_kms_key.opensearch.arn
  }

  node_to_node_encryption {
    enabled = true
  }

  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

  advanced_security_options {
    enabled                        = true
    internal_user_database_enabled = false
    master_user_options {
      master_user_arn = aws_iam_role.opensearch_master.arn
    }
  }

  # Access policy
  access_policies = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { AWS = "*" }  # Fine-grained via IAM roles
        Action    = "es:*"
        Resource  = "arn:aws:es:ap-southeast-1:*:domain/payment-platform-prod/*"
        Condition = {
          IpAddress = {
            "aws:SourceIp" = module.services_vpc.private_compute_cidrs
          }
        }
      }
    ]
  })
}
```

---

### 4.8 S3 — Storage & Archival

| Bucket | Purpose | Lifecycle | Encryption |
|--------|---------|-----------|:--:|
| `payment-platform-artifacts` | Terraform state, Lambda packages | None (versioned) | ✅ KMS |
| `payment-platform-events-archive` | Kafka → S3 Parquet archive | Transition to Glacier after 1 year. Delete after 7 years. | ✅ KMS |
| `payment-platform-db-snapshots` | Aurora snapshot export | Delete after 90 days | ✅ KMS |
| `payment-platform-logs` | ALB access logs, CloudTrail | Transition to IA after 30 days, Glacier after 90 days | ✅ KMS |
| `payment-platform-backups` | Manual DB backups, config backups | Delete after 35 days | ✅ KMS |

**Bucket Policy — Block Public Access**: All buckets have `block_public_acls = true`, `block_public_policy = true`, `ignore_public_acls = true`, `restrict_public_buckets = true`.

---

### 4.9 IAM — Roles, Policies, IRSA

#### 4.9.1 Key IAM Roles

| Role | Trust Entity | Purpose |
|------|-------------|---------|
| `payment-service-role` | EKS Pod (`payment-service` SA) | DB credentials fetch, KMS decrypt |
| `debezium-connect-role` | EKS Pod (`debezium-connect` SA) | Aurora WAL access, RDS describe |
| `kafka-connect-s3-role` | EKS Pod (`kafka-connect` SA) | S3 PutObject for event archival |
| `atlantis-role` | EKS Pod (`atlantis` SA) | Terraform plan/apply, limited to approved modules |
| `gh-actions-role` | GitHub OIDC Provider | ECR push, EKS kubectl, ArgoCD sync |
| `monitoring-role` | EKS Pod (`otel-collector` SA) | CloudWatch metrics push, X-Ray traces |

#### 4.9.2 IRSA Example

```hcl
# EKS Pod → IAM Role mapping
module "payment_service_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  
  role_name = "payment-service-prod"
  role_policy_arns = {
    secrets_manager = aws_iam_policy.secrets_read.arn
    kms_decrypt     = aws_iam_policy.kms_decrypt.arn
  }

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["payments:payment-service"]
    }
  }
}
```

---

### 4.10 DNS, TLS & CDN

| Component | Implementation |
|-----------|---------------|
| **DNS** | Route 53 hosted zone: `payments.platform.com` |
| **CDN** | CloudFront distribution with AWS WAF |
| **TLS** | ACM certificate (RSA 2048), auto-renewed |
| **Internal DNS** | Route 53 private hosted zones (`.internal`) |
| **Service Discovery** | Kubernetes `Service` + CoreDNS (no Consul/Envoy DNS) |

**DNS Records**:

| Record | Type | Target | Purpose |
|--------|------|--------|---------|
| `api.payments.platform.com` | A (Alias) | CloudFront → ALB | Public API |
| `api-sandbox.payments.platform.com` | A (Alias) | CloudFront → ALB (sandbox) | Sandbox API |
| `schema-registry.internal` | CNAME | Kong Internal LB | Schema Registry (internal only) |
| `grafana.payments.platform.com` | A | ALB (auth via OIDC) | Monitoring dashboards |
| `argocd.internal` | CNAME | ALB (internal only) | GitOps UI |

---

### 4.11 Terraform Module Structure

```
terraform/
├── modules/
│   ├── vpc/
│   │   ├── main.tf              # VPC, subnets, NAT, TGW, VPC endpoints
│   │   ├── outputs.tf
│   │   └── variables.tf
│   ├── eks/
│   │   ├── main.tf              # EKS cluster, node groups, add-ons
│   │   ├── irsa.tf              # IAM Roles for Service Accounts
│   │   ├── karpenter.tf         # Karpenter NodePool + NodeClass
│   │   ├── outputs.tf
│   │   └── variables.tf
│   ├── aurora/
│   │   ├── main.tf              # Aurora cluster, instances, parameter group
│   │   ├── outputs.tf
│   │   └── variables.tf
│   ├── elasticache/
│   │   ├── main.tf
│   │   ├── outputs.tf
│   │   └── variables.tf
│   ├── msk/
│   │   ├── main.tf              # MSK cluster, configuration, topics
│   │   ├── topics.tf            # All Kafka topics (Phase 09 catalog)
│   │   ├── outputs.tf
│   │   └── variables.tf
│   ├── opensearch/
│   │   ├── main.tf
│   │   └── variables.tf
│   ├── s3/
│   │   ├── main.tf              # All buckets with policies
│   │   └── variables.tf
│   ├── security/
│   │   ├── main.tf              # Security groups, KMS keys, IAM roles
│   │   ├── kms.tf
│   │   ├── iam.tf
│   │   └── waf.tf
│   ├── dns/
│   │   ├── main.tf              # Route 53, ACM, CloudFront
│   │   └── variables.tf
│   └── monitoring/
│       ├── main.tf              # Grafana, Prometheus (via Helm provider)
│       └── variables.tf
├── environments/
│   ├── prod/
│   │   ├── main.tf              # Calls all modules with prod config
│   │   ├── terraform.tfvars
│   │   └── backend.tf           # S3 backend
│   ├── staging/
│   │   ├── main.tf
│   │   ├── terraform.tfvars
│   │   └── backend.tf
│   ├── sandbox/
│   │   ├── main.tf
│   │   ├── terraform.tfvars
│   │   └── backend.tf
│   └── shared-services/
│       ├── main.tf
│       ├── terraform.tfvars
│       └── backend.tf
├── atlantis.yaml                 # Atlantis repo config
└── terragrunt.hcl                # (Optional) DRY config
```

**Module Call Pattern**:

```hcl
# environments/prod/main.tf
module "vpc_services" {
  source = "../../modules/vpc"
  
  name       = "services-prod"
  cidr_block = "10.0.0.0/16"
  
  public_subnets       = ["10.0.0.0/20", "10.0.16.0/20", "10.0.32.0/20"]
  compute_subnets      = ["10.0.64.0/19", "10.0.96.0/19", "10.0.128.0/19"]
  internal_subnets     = ["10.0.160.0/19", "10.0.192.0/19", "10.0.224.0/19"]
  
  enable_nat_gateway   = true
  enable_vpn_gateway   = false
  
  tags = {
    Environment = "prod"
    ManagedBy   = "Terraform"
  }
}

module "eks" {
  source = "../../modules/eks"
  
  cluster_name    = "payment-platform-prod"
  cluster_version = "1.30"
  vpc_id          = module.vpc_services.vpc_id
  subnet_ids      = module.vpc_services.compute_subnet_ids
  
  node_groups = {
    services = { instance_type = "m6i.xlarge", min = 6, max = 20 }
    gateway  = { instance_type = "c6i.xlarge", min = 2, max = 6 }
    system   = { instance_type = "m6i.large",  min = 2, max = 4 }
  }
}
```

---

## 5. Example Deliverables

### 5.1 Atlantis PR Workflow

```bash
# PR opened: "Add MSK topic for payment.succeeded"
# Atlantis comments on PR:

atlantis plan
```
```
Terraform used the selected providers to generate the following execution plan:

  # module.msk.kafka_topic.payment_succeeded will be created
  + resource "kafka_topic" "payment_succeeded" {
      + name               = "payments.payment.succeeded"
      + partitions         = 12
      + replication_factor = 3
      + config = {
          "retention.ms" = "604800000"
        }
    }

Plan: 1 to add, 0 to change, 0 to destroy.
```

```bash
# Reviewer comments: atlantis apply
# Atlantis applies the change
```

---

## 6. Key Questions

| # | Question | Answer |
|---|----------|--------|
| Q1 | Why separate VPCs for Services and Data? | Network isolation between compute and data planes. A compromised pod cannot reach the database without explicit security group rules. No transitive routing via Transit Gateway without explicit route table entries. |
| Q2 | How are cross-AZ data transfer costs managed? | Aurora: cross-AZ replication is included in the instance price (no additional data transfer cost). MSK: cross-AZ consumer traffic is billable — consumers are placed in the same AZ as their partition leader via rack-aware consumer configuration. Redis: cross-AZ replication traffic is billable but minimal for cache workloads. |
| Q3 | What happens if Terraform state is corrupted? | S3 backend with versioning + DynamoDB locking. State is never edited manually. `terraform state pull` recovers the last good version. Cross-region S3 replication for DR. |
| Q4 | How are EKS node upgrades handled? | Blue/Green: provision new node group with updated AMI + k8s version → cordon old nodes → drain pods → terminate old node group. Managed by Karpenter `drift` detection. |

---

## 7. Implementation Tasks

### P0 — Critical Path

- [ ] **T01**: Create Terraform module for VPC (services + data, TGW, VPC endpoints).
- [ ] **T02**: Create Terraform module for EKS (cluster, node groups, Karpenter, IRSA).
- [ ] **T03**: Create Terraform module for Aurora (financial_core, payment, idempotency clusters).
- [ ] **T04**: Create Terraform module for MSK (cluster, configuration, topics from Phase 09).
- [ ] **T05**: Create Terraform module for ElastiCache and OpenSearch.
- [ ] **T06**: Set up Atlantis with GitHub webhook for Terraform PR workflow.
- [ ] **T07**: Provision shared-services account (ECR, Route 53, Atlantis, CI runners).

### P1 — Before Phase 17 (Vertical Slice)

- [ ] **T08**: Provision staging environment (all modules, 50% scale).
- [ ] **T09**: Provision sandbox environment (minimal, serverless Aurora).
- [ ] **T10**: Configure cross-region DR: Aurora async replication, MSK MirrorMaker 2.
- [ ] **T11**: Configure CloudFront + WAF for API Gateway ingress.

### P2 — Before Phase 25 (Production Readiness)

- [ ] **T12**: Provision production environment (full scale, Multi-AZ everything).
- [ ] **T13**: Load-test infrastructure: verify EKS autoscaling, Aurora failover, MSK broker failure recovery.
- [ ] **T14**: DR drill: failover from ap-southeast-1 to ap-southeast-3, validate RPO < 30s.

---

## 8. Common Mistakes

| Mistake | Consequence | Prevention |
|---------|-------------|-----------|
| **Single-AZ for data stores** | AZ failure → complete outage | All data stores are Multi-AZ with automatic failover. |
| **Public subnets for databases** | Aurora exposed to internet → data breach | Data VPC has no public subnets. Only private subnets. |
| **Hardcoded IPs in security groups** | IP changes → rules break | Use security group references, not IPs. |
| **Manual Terraform state edits** | State drift → apply destroys resources | State is immutable. Use `terraform import` or `terraform mv`. |
| **No deletion protection** | Accidental `terraform destroy` wipes databases | `deletion_protection = true` on Aurora, ElastiCache, OpenSearch. |
| **Kafka topics created manually** | Topic config not version-controlled → drift | `auto.create.topics.enable = false`. All topics in Terraform. |

---

## 9. KPIs & Exit Criteria

| # | Criterion | Target | Measurement |
|---|-----------|--------|-------------|
| K01 | IaC coverage | 100% of AWS resources defined in Terraform | `terraform plan` shows no manual resources |
| K02 | Module reusability | All 10+ Terraform modules callable from all environments | Code review |
| K03 | Atlantis workflow | 100% of infrastructure changes via PR + `atlantis apply` | GitHub audit log |
| K04 | Multi-AZ coverage | All data stores (Aurora, MSK, ElastiCache, OpenSearch) are Multi-AZ | AWS Console / Terraform |
| K05 | Encryption at rest | All data stores and S3 buckets encrypted with KMS CMK | AWS Config rule |
| K06 | Private networking | 0 public subnets in Data VPC. 0 public endpoints on databases | AWS Config rule |
| K07 | DR configuration | Aurora cross-region replica + MSK MirrorMaker 2 configured | Terraform state |

**Exit Gate**: All K01–K07 must be ✅ before Quality Gate approval.

---

## 10. Connection to Next Phase

| Downstream Phase | How Infrastructure Design Connects |
|-----------------|------------------------|
| **Phase 13 — Platform Core** | The `@app/core` Go library uses IRSA for AWS credential fetching, connects to Aurora/Redis/Kafka via security group-approved endpoints. Infrastructure here provides the environment those libraries target. |
| **Phase 15 — Developer Platform** | Docker Compose provisions local equivalents (PostgreSQL, Redis, Kafka, OpenSearch) on developer machines, mimicking the production infrastructure topology. |
| **Phase 16 — CI/CD** | GitHub Actions workflow uses OIDC to assume `gh-actions-role`. ArgoCD syncs EKS from Git. Atlantis manages Terraform. All three depend on the IAM roles and EKS cluster provisioned here. |
| **Phase 17 — Vertical Slice** | The first E2E flow runs on the staging EKS cluster, connecting to staging Aurora and MSK. Infrastructure must be fully provisioned before this phase. |
| **Phase 24 — Multi-Region DR** | DR runbooks reference the cross-region infrastructure (Aurora replica, MSK MirrorMaker 2, Route 53 failover) designed here. |

---

### 🛑 APPROVAL GATE → 🧪 Quality Gate (Automated + Peer Review)

**Checklist**:

- [ ] All 10+ Terraform modules exist and are parameterized per environment
- [ ] Atlantis workflow configured: plan on PR open, apply on PR merge
- [ ] VPC design: separate Services and Data VPCs, private subnets for all data stores
- [ ] EKS clusters: private endpoint, IRSA enabled, Karpenter for autoscaling, managed node groups
- [ ] Aurora: Multi-AZ, I/O-Optimized for `financial_core_db`, encryption at rest, 35-day backup retention
- [ ] MSK: 3 brokers, KRaft mode, TLS encryption, topic provisioning in Terraform
- [ ] ElastiCache: cluster mode, Multi-AZ, encryption in transit + at rest
- [ ] OpenSearch: dedicated masters, encryption, IAM-based access policy
- [ ] S3: all buckets block public access, KMS encryption, lifecycle policies
- [ ] IAM: IRSA for all service accounts, least-privilege policies
- [ ] Cross-region DR: Aurora async replica, MSK MirrorMaker 2 configured
