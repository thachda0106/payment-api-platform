# Mini Project — Platform Infrastructure

## Goal

Design and document the complete platform infrastructure for the Payment API Platform — from VPC to Kubernetes to databases to observability — using Terraform modules and Kubernetes manifests.

## Deliverables

### 1. Infrastructure Diagram (ASCII)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        AWS (ap-southeast-1)                         │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │ VPC (10.0.0.0/16)                                              │ │
│  │                                                                │ │
│  │  ┌──────────────────┐  ┌──────────────────┐                   │ │
│  │  │ Public Subnets    │  │ Private Subnets   │                   │ │
│  │  │ (3 AZs)           │  │ (3 AZs)           │                   │ │
│  │  │                   │  │                    │                   │ │
│  │  │  ALB (Ingress)   │  │  EKS (K8s)        │                   │ │
│  │  │  Bastion          │  │  Aurora PG        │                   │ │
│  │  └──────────────────┘  │  MSK (Kafka)      │                   │ │
│  │                         │  ElastiCache       │                   │ │
│  │                         │  OpenSearch        │                   │ │
│  │                         └────────────────────┘                   │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ Shared Services: KMS, Secrets Manager, S3, Route53, ACM, IAM   │  │
│  └───────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

### 2. Terraform Module Structure

```
terraform/
├── modules/
│   ├── vpc/          (VPC, subnets, NAT, VPC endpoints)
│   ├── eks/          (EKS cluster, node groups, IRSA roles)
│   ├── aurora/       (Aurora PostgreSQL, parameter groups, subnet groups)
│   ├── msk/          (MSK cluster, configuration, security groups)
│   ├── elasticache/  (Redis cluster, parameter groups)
│   ├── opensearch/   (OpenSearch domain, ISM policies)
│   └── security/     (KMS keys, Secrets Manager, security groups)
├── environments/
│   ├── prod/         (Production: larger instances, multi-AZ, DR)
│   ├── staging/      (Staging: mirror of prod, smaller instances)
│   └── dev/          (Development: single-AZ, minimal)
└── backend.tf        (S3 + DynamoDB state)
```

### 3. Kubernetes Namespace Layout

```
payment-prod/
├── financial-core/    (Java)  — 3 pods, HPA 3-10, 512Mi/1000m
├── payment-service/   (Java)  — 5 pods, HPA 3-20, 512Mi/1000m
├── refund-service/    (Java)  — 2 pods, HPA 2-5
├── fx-service/        (Java)  — 2 pods, HPA 2-5
├── fraud-service/     (Python)— 3 pods, HPA 3-10, 256Mi/500m
├── notification/      (Node)  — 3 pods, HPA 3-10
├── settlement/        (Go)    — 2 pods, HPA 2-8
├── monitoring/        (Grafana, Prometheus, Jaeger)
└── ingress/           (Istio Gateway)
```

### 4. Key AWS Resource Sizing

| Resource | prod | staging | dev |
|----------|------|---------|-----|
| EKS Node (m6i.xlarge) | 10-20 nodes | 3 nodes | 1 node (m6i.large) |
| Aurora (db.r6g.xlarge) | 2 instances (Multi-AZ) | 1 instance | db.t4g.medium |
| MSK (kafka.m5.large) | 6 brokers | 3 brokers | — (use in-cluster Kafka) |
| ElastiCache (cache.m6g.large) | 3 nodes (cluster) | 1 node | — (in-memory) |
| OpenSearch (m6g.large.search) | 3 nodes | 1 node | — (Elasticsearch container) |

### 5. Acceptance Criteria

1. Terraform plan shows all resources with correct sizing
2. Kubernetes namespaces and resource quotas defined
3. Network topology: public subnets (ALB only) + private subnets (everything else)
4. IRSA configured for pod-level AWS access (no hardcoded credentials)
5. Multi-AZ for all stateful services in production
6. Encryption at rest enabled (KMS) for Aurora, MSK, S3
