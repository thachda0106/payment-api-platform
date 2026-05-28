# Module 03 — Terraform, AWS & Multi-Region

## 3.1 Terraform

### Core Concepts

```hcl
# Provider
provider "aws" { region = "ap-southeast-1" }

# Resource
resource "aws_db_instance" "financial_core" {
  identifier     = "financial-core-db"
  engine         = "aurora-postgresql"
  engine_version = "16.3"
  instance_class = "db.r6g.xlarge"
  storage_encrypted = true
}

# Variables
variable "environment" { default = "production" }

# Outputs
output "db_endpoint" { value = aws_db_instance.financial_core.endpoint }

# State: remote state in S3 (CRITICAL — never store locally!)
terraform {
  backend "s3" {
    bucket = "payment-terraform-state"
    key    = "production/terraform.tfstate"
    region = "ap-southeast-1"
    dynamodb_table = "terraform-locks"  # Prevents concurrent applies
  }
}
```

### Module Structure

```hcl
module "vpc"     { source = "./modules/vpc" }
module "eks"     { source = "./modules/eks";     vpc_id = module.vpc.id }
module "aurora"  { source = "./modules/aurora";  vpc_id = module.vpc.id; subnet_ids = module.vpc.private_subnets }
module "msk"     { source = "./modules/msk";     vpc_id = module.vpc.id }
module "redis"   { source = "./modules/redis" }
```

### Terraform Workflow

```bash
terraform init      # Download providers, initialize backend
terraform plan      # Show what will change (NO changes applied)
terraform apply     # Apply changes (requires approval)
terraform destroy   # Tear down everything (CAREFUL!)
```

## 3.2 AWS Services for Payment Platform

| Service | Use | Why AWS Managed |
|---------|-----|----------------|
| **EKS** | Kubernetes | Managed control plane. No etcd to maintain. |
| **Aurora PostgreSQL** | Financial databases | 3x throughput vs RDS, auto-scaling storage, <1s failover |
| **MSK** | Kafka | Managed brokers, KRaft mode, automatic patching |
| **ElastiCache** | Redis | Managed, cluster mode, auto failover |
| **OpenSearch** | Search/Analytics | Managed, ISM for retention |
| **KMS** | Encryption keys | HSM-backed, envelope encryption |
| **Secrets Manager** | Credentials | Auto rotation, IAM integration |
| **S3** | Backups, WAL archives | 11 9s durability |
| **Route53** | DNS | Latency-based routing, health checks |
| **ACM** | TLS certificates | Auto-renewal, free |
| **IAM + IRSA** | Pod-level permissions | Pods get IAM roles, no credentials in code |

### IRSA (IAM Roles for Service Accounts)

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::123456:role/payment-service-role
```
Pods using this ServiceAccount automatically get temporary AWS credentials — no hardcoded keys.

## 3.3 Multi-Region Architecture

```
Region A (ap-southeast-1 — Active)     Region B (ap-southeast-3 — Standby)
┌─────────────────────────┐           ┌─────────────────────────┐
│ EKS → Payment Services  │           │ EKS → Payment Services  │ (Standby)
│ Aurora (Primary)        │──────────▶│ Aurora (Replica)        │ (Aurora Global DB)
│ MSK (Primary)           │──────────▶│ MSK (MM2 Target)        │ (MirrorMaker 2)
│ ElastiCache             │           │ ElastiCache             │ (Separate)
│ Route53 (Active)        │           │ Route53 (Failover)      │
└─────────────────────────┘           └─────────────────────────┘
```

**Active-Passive**: All traffic to Region A. Region B is warm standby. Failover: DNS switch (Route53) → < 5 minutes.

**Data Replication**:
- **Aurora Global Database**: Physical replication to standby region. < 1 second lag typically.
- **MSK MirrorMaker 2**: Replicates Kafka topics. Minutes of lag acceptable.
- **ElastiCache**: Separate cluster. Rebuild cache on failover (or replicate with Redis replication).

### RPO/RTO Targets

| Scenario | RPO (Data Loss) | RTO (Time to Recover) |
|----------|:---------------:|:---------------------:|
| Single AZ failure | 0 | < 60 seconds (Multi-AZ auto) |
| Single region failure | < 1 second (Aurora) | < 5 minutes (DNS flip) |
| Data corruption | 0 (PITR) | < 1 hour (restore from backup) |

## 3.4 Exercises

### Ex 3.1 — Terraform Module
Write a Terraform module that provisions an EKS cluster with managed node group. Use remote state in S3. Apply and verify the cluster exists via `kubectl`.

### Ex 3.2 — IRSA Setup
Create an IAM role for the Payment Service. Configure IRSA on the service account. Verify the pod can access S3 without hardcoded credentials.

### Ex 3.3 — Multi-Region Diagram
Draw the multi-region architecture for the payment platform. Show: data replication paths, DNS configuration, failover procedure. Estimate RPO and RTO.

## 3.5 Self-Assessment

- [ ] Can write a Terraform module with providers, resources, variables, and outputs
- [ ] Understand why Terraform state must be stored remotely (S3 + DynamoDB lock)
- [ ] Can explain how IRSA provides pod-level AWS permissions
- [ ] Can design a multi-region architecture with RPO/RTO targets
- [ ] Know the managed AWS service equivalent for each open-source component
