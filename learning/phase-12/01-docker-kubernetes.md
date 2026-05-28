# Module 01 — Docker & Kubernetes

## 1.1 Docker: Not Just a Tool, It's Isolation

Docker is three Linux kernel features combined: **Namespaces** (isolate what a process sees), **Cgroups** (limit what a process uses), **UnionFS/Overlay2** (layer filesystem for efficient images).

### Namespaces

| Namespace | Isolates | Payment Relevance |
|-----------|----------|-------------------|
| PID | Process IDs (container sees PID 1) | Kill container = kill namespaced process |
| NET | Network interfaces, routing | Each container has its own network stack |
| MNT | Mount points | Container can't see host filesystem |
| UTS | Hostname | Container has its own hostname |
| IPC | Inter-process communication | Isolates shared memory, semaphores |
| USER | User/group IDs | Root in container ≠ root on host |
| CGROUP | Resource limits (CPU, memory) | Prevent noisy neighbor |

### Multi-Stage Builds

```dockerfile
# Stage 1: Build
FROM golang:1.22 AS builder
COPY . . && go build -o /app

# Stage 2: Runtime (minimal)
FROM scratch
COPY --from=builder /app /app
ENTRYPOINT ["/app"]
# Result: <10MB image, zero vulnerabilities, fast pull
```

### Non-Root User

```dockerfile
RUN addgroup -S app && adduser -S app -G app
USER app
```
NEVER run containers as root. PCI DSS requirement. Also basic security hygiene.

## 1.2 Kubernetes: The Cluster OS

Kubernetes is a declarative cluster operating system. You declare DESIRED STATE (YAML). Kubernetes reconciles CURRENT → DESIRED via control loop.

### Architecture

```
Control Plane:                          Worker Nodes:
┌──────────────────────┐               ┌──────────────────┐
│ API Server           │               │ kubelet (agent)  │
│ etcd (state store)   │               │ kube-proxy (net) │
│ Scheduler            │               │ Container Runtime│
│ Controller Manager   │               └──────────────────┘
└──────────────────────┘
```

### Pod: The Atomic Unit

A pod is a group of one or more containers sharing network namespace and storage. Pods are ephemeral — they can die and be replaced.

```yaml
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: payment-service
    image: ghcr.io/payment-api/payment:latest
    ports: [{containerPort: 8080}]
    resources:
      requests: {memory: "256Mi", cpu: "500m"}
      limits: {memory: "512Mi", cpu: "1000m"}
    livenessProbe:       # Is the process alive?
      httpGet: {path: /actuator/health/liveness, port: 8080}
      initialDelaySeconds: 30
    readinessProbe:      # Is it ready for traffic?
      httpGet: {path: /actuator/health/readiness, port: 8080}
      initialDelaySeconds: 10
```

### Deployment: Manage Replicas

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate: {maxUnavailable: 1, maxSurge: 1}
  selector: {matchLabels: {app: payment}}
  template: # Pod spec above
```

### Service: Stable Network Identity

```yaml
apiVersion: v1
kind: Service
spec:
  selector: {app: payment}
  ports: [{port: 8080, targetPort: 8080}]
  type: ClusterIP  # Internal only
---
# For external access:
apiVersion: networking.k8s.io/v1
kind: Ingress
spec:
  rules:
  - host: api.payment.vn
    http:
      paths:
      - path: /v1/payments
        pathType: Prefix
        backend: {service: {name: payment, port: {number: 8080}}}
```

### HPA: Autoscaling

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef: {name: payment-deployment}
  minReplicas: 3
  maxReplicas: 20
  metrics:
  - type: Resource
    resource: {name: cpu, target: {type: Utilization, averageUtilization: 70}}
  - type: Object  # Scale on Kafka consumer lag
    object:
      metric: {name: kafka_consumer_lag}
      target: {type: Value, value: "10000"}
```

### Key Kubernetes Patterns for Payment Platform

| Pattern | Why |
|---------|-----|
| **Deployment** | Stateless services (Payment, Fraud, Notification) |
| **StatefulSet** | Stateful services needing stable identity (not needed — use AWS managed) |
| **NetworkPolicy** | Service-to-service firewall: "Only Payment can call Fraud" |
| **PodDisruptionBudget** | Max unavailable during voluntary disruptions |
| **Resource Quotas** | Per-namespace limits to prevent resource exhaustion |
| **ConfigMap + Secrets** | Externalize configuration, inject as env vars or files |

## 1.3 Exercises

### Ex 1.1 — Docker from Scratch
Build a minimal container runtime using Linux namespaces and cgroups (no Docker). Demonstrate PID, NET, and MNT namespace isolation.

### Ex 1.2 — K8s Deployment
Deploy the Payment Service to a local kind/minikube cluster. Configure: 3 replicas, liveness/readiness probes, resource limits, HPA, Service, Ingress. Verify rolling update.

### Ex 1.3 — HPA Autoscaling
Load test the Payment Service (autocannon, 100 concurrent connections). Observe HPA scale from 3→10 replicas. Observe scale-down after load stops.

## 1.4 Self-Assessment

- [ ] Can explain what happens when `docker run` is called (namespaces, cgroups, UnionFS)
- [ ] Can write a multi-stage Dockerfile for each language (Java/Python/Node/Go)
- [ ] Can deploy a service to Kubernetes with probes, limits, and HPA
- [ ] Understand the pod lifecycle: Init → Running → Terminating
- [ ] Know the difference between liveness (restart) and readiness (remove from Service)
