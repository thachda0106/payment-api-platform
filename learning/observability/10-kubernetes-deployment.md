# Phase 10 — Kubernetes Deployment

> **Duration**: 1 week | **Prerequisites**: Phases 1-9, Kubernetes fundamentals (main curriculum Phase 12)
>
> **Goal**: Understand production deployment of the observability stack on Kubernetes, including Helm, Operators, and production considerations.

---

## 10.1 Kubernetes Observability Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Kubernetes Cluster                            │
│                                                                      │
│  ┌───────────────────────────────┐  ┌────────────────────────────┐ │
│  │   observability namespace     │  │    application namespaces   │ │
│  │                               │  │                             │ │
│  │  Prometheus (StatefulSet)     │  │  payment-ns                 │ │
│  │  Jaeger (Deployment)          │  │  ├── payment-service        │ │
│  │  OpenSearch (StatefulSet)     │  │  └── OTel Collector (side)  │ │
│  │  Grafana (Deployment)         │  │                             │ │
│  │  Alertmanager (StatefulSet)   │  │  auth-ns                    │ │
│  │  OTel Gateway (Deployment)    │  │  ├── auth-service           │ │
│  │                               │  │  └── OTel Collector (side)  │ │
│  └───────────────────────────────┘  │                             │ │
│                                      │  kube-system                │ │
│                                      │  ├── OTel Collector (DS)   │ │
│                                      │  └── Node Exporter (DS)    │ │
│                                      └────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

**Three-layer collection architecture:**

1. **DaemonSet Collectors** (kube-system): One per node. Collects node-level telemetry (kubelet, container runtime, node metrics). Lightweight batching only.

2. **Gateway Collectors** (observability namespace): Centralized. Tail sampling, attribute processing, k8s metadata enrichment. Horizontally scalable behind a Service.

3. **Application Collectors (optional)**: Sidecar per pod or shared per namespace. Reduces latency for tail sampling by processing locally.

---

## 10.2 Deploying with Helm

### 10.2.1 OpenTelemetry Collector (Helm)

```bash
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
helm install otel-collector open-telemetry/opentelemetry-collector \
  --namespace observability \
  --values otel-values.yaml
```

**otel-values.yaml — DaemonSet + Gateway:**

```yaml
mode: daemonset

config:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
        http:
          endpoint: 0.0.0.0:4318

  processors:
    batch:
      timeout: 10s
      send_batch_size: 8192
    memory_limiter:
      check_interval: 1s
      limit_mib: 256

  exporters:
    otlp/gateway:
      endpoint: "otel-collector-gateway.observability:4317"
      tls:
        insecure: true

  service:
    pipelines:
      traces:
        receivers: [otlp]
        processors: [memory_limiter, batch]
        exporters: [otlp/gateway]
      metrics:
        receivers: [otlp]
        processors: [memory_limiter, batch]
        exporters: [otlp/gateway]
      logs:
        receivers: [otlp]
        processors: [memory_limiter, batch]
        exporters: [otlp/gateway]

resources:
  limits:
    cpu: 500m
    memory: 512Mi
  requests:
    cpu: 200m
    memory: 256Mi
```

```bash
# Gateway deployment (separate Helm release)
helm install otel-collector-gateway open-telemetry/opentelemetry-collector \
  --namespace observability \
  --values otel-gateway-values.yaml
```

**otel-gateway-values.yaml:**

```yaml
mode: deployment
replicaCount: 3

config:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317

  processors:
    batch:
      timeout: 10s
      send_batch_size: 8192
    memory_limiter:
      check_interval: 1s
      limit_mib: 1024
    tail_sampling:
      decision_wait: 30s
      num_traces: 100000
      policies:
        - name: keep-errors
          type: status_code
          status_code: {status_codes: [ERROR]}
        - name: keep-slow
          type: latency
          latency: {threshold_ms: 5000}
        - name: sample
          type: probabilistic
          probabilistic: {sampling_percentage: 10}
    k8sattributes:
      extract:
        metadata: [k8s.pod.name, k8s.namespace.name, k8s.deployment.name]
    resource:
      attributes:
        - key: k8s.cluster.name
          value: production-cluster
          action: upsert

  exporters:
    prometheusremotewrite:
      endpoint: http://prometheus.observability:9090/api/v1/write
    otlp/jaeger:
      endpoint: jaeger-collector.observability:14250
      tls:
        insecure: true
    opensearch:
      http:
        endpoint: https://opensearch.observability:9200

  service:
    pipelines:
      traces:
        receivers: [otlp]
        processors: [memory_limiter, k8sattributes, tail_sampling, resource, batch]
        exporters: [otlp/jaeger]
      metrics:
        receivers: [otlp]
        processors: [memory_limiter, k8sattributes, resource, batch]
        exporters: [prometheusremotewrite]
      logs:
        receivers: [otlp]
        processors: [memory_limiter, k8sattributes, resource, batch]
        exporters: [opensearch]

autoscaling:
  enabled: true
  minReplicas: 3
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70

resources:
  limits:
    cpu: 2000m
    memory: 2048Mi
  requests:
    cpu: 1000m
    memory: 1024Mi
```

### 10.2.2 Prometheus (kube-prometheus-stack)

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install prometheus prometheus-community/kube-prometheus-stack \
  --namespace observability \
  --values prometheus-values.yaml
```

**Key components deployed:**
- Prometheus Operator (manages Prometheus custom resources)
- Prometheus (StatefulSet with PVC)
- Alertmanager (StatefulSet)
- Grafana (Deployment)
- Node Exporter (DaemonSet)
- kube-state-metrics (Deployment — K8s object metrics)

**prometheus-values.yaml (production considerations):**

```yaml
prometheus:
  prometheusSpec:
    retention: 30d
    retentionSize: 100GB
    storageSpec:
      volumeClaimTemplate:
        spec:
          storageClassName: gp3
          accessModes: ["ReadWriteOnce"]
          resources:
            requests:
              storage: 100Gi
    resources:
      requests:
        cpu: 2000m
        memory: 8Gi
      limits:
        cpu: 4000m
        memory: 16Gi
    externalLabels:
      cluster: production-cluster
      region: us-east-1

    # Remote write to Thanos/Mimir for long-term storage
    remoteWrite:
      - url: "http://thanos-receive.observability:19291/api/v1/write"

alertmanager:
  alertmanagerSpec:
    storage:
      volumeClaimTemplate:
        spec:
          storageClassName: gp3
          accessModes: ["ReadWriteOnce"]
          resources:
            requests:
              storage: 10Gi

grafana:
  adminPassword: ""  # Use Kubernetes secret
  persistence:
    enabled: true
    size: 10Gi
    storageClassName: gp3
  ingress:
    enabled: true
    hosts:
      - grafana.example.com
    tls:
      - hosts:
          - grafana.example.com
```

### 10.2.3 Jaeger (jaeger-operator)

```bash
helm repo add jaegertracing https://jaegertracing.github.io/helm-charts
helm install jaeger-operator jaegertracing/jaeger-operator \
  --namespace observability
```

**Create a Jaeger instance via CRD:**

```yaml
# jaeger-production.yaml
apiVersion: jaegertracing.io/v1
kind: Jaeger
metadata:
  name: production-jaeger
  namespace: observability
spec:
  strategy: production
  storage:
    type: elasticsearch
    options:
      es:
        server-urls: https://opensearch.observability:9200
        index-prefix: jaeger
        use-aliases: true
    elasticsearch:
      nodeCount: 3
      storage:
        storageClassName: gp3
        size: 500Gi
      resources:
        requests:
          cpu: 2000m
          memory: 4Gi
        limits:
          cpu: 4000m
          memory: 8Gi
  collector:
    replicas: 3
    resources:
      requests:
        cpu: 1000m
        memory: 2Gi
      limits:
        cpu: 2000m
        memory: 4Gi
  query:
    replicas: 2
  ingress:
    enabled: true
    hosts:
      - jaeger.example.com
```

**Jaeger Operator manages:**
- Creates and configures Collector Deployment
- Creates and configures Query Deployment
- Provisions Elasticsearch/OpenSearch if configured
- Handles rolling updates and version management
- Manages sampling strategy ConfigMap (hot-reloaded by SDKs)

### 10.2.4 OpenSearch (opensearch-operator)

```bash
helm repo add opensearch https://opensearch-project.github.io/helm-charts
helm install opensearch opensearch/opensearch \
  --namespace observability \
  --values opensearch-values.yaml
```

**opensearch-values.yaml:**

```yaml
replicas: 3
singleNode: false

config:
  opensearch.yml: |
    cluster.name: observability-cluster
    network.host: 0.0.0.0
    discovery.seed_hosts: opensearch-master-headless.observability
    bootstrap.memory_lock: true
    plugins.security.disabled: true  # Use service mesh mTLS instead

masterService: opensearch-cluster-master
master:
  replicas: 3
  persistence:
    size: 20Gi
    storageClass: gp3
  resources:
    requests:
      cpu: 500m
      memory: 2Gi
    limits:
      cpu: 1000m
      memory: 4Gi

data:
  replicas: 3
  persistence:
    size: 500Gi
    storageClass: gp3
  resources:
    requests:
      cpu: 2000m
      memory: 16Gi
    limits:
      cpu: 4000m
      memory: 32Gi

ingest:
  replicas: 2
  resources:
    requests:
      cpu: 1000m
      memory: 4Gi
    limits:
      cpu: 2000m
      memory: 8Gi
```

---

## 10.3 Production Considerations

### 10.3.1 Storage

**All stateful components need PersistentVolumes:**

| Component | Storage Type | Size Guideline | Reason |
|-----------|-------------|----------------|--------|
| Prometheus | Block (EBS/gp3) | 100-500 GB | Sequential write WAL + compressed blocks |
| Jaeger (ES backend) | Block (EBS/gp3) | 500 GB-2 TB | High write volume, needs IOPS |
| OpenSearch Hot | NVMe local SSD or io2 EBS | 500 GB-2 TB | Write latency critical for indexing |
| OpenSearch Warm | HDD or gp3 | 2-10 TB | Capacity > performance |
| Grafana | Block (gp3) | 10-50 GB | Dashboard storage, low I/O |
| Alertmanager | Block (gp3) | 10-50 GB | Silences + nflog, low I/O |

### 10.3.2 Resource Limits

```yaml
# Anti-pattern: No resource limits
resources: {}  # Pod can consume unlimited CPU/memory

# Risk: One out-of-control Collector impacts all neighbors
# Fix: Always set limits AND requests

# Good pattern:
resources:
  requests:
    cpu: 1000m        # Guaranteed CPU
    memory: 2Gi       # Guaranteed memory
  limits:
    cpu: 2000m        # Burst cap
    memory: 4Gi       # OOM kill threshold
```

**Requests = Limits for stateful components** (Guaranteed QoS):
- Prometheus, OpenSearch, Jaeger storage
- Prevents eviction under memory pressure

**Requests < Limits for stateless components** (Burstable QoS):
- OTel Collector, Grafana, Jaeger Query
- Allows CPU bursting during traffic spikes

### 10.3.3 Network Policies

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: observability-ingress
  namespace: observability
spec:
  podSelector: {}  # All pods in observability namespace
  ingress:
    # Allow OTLP from all application namespaces
    - from:
        - namespaceSelector:
            matchLabels:
              observability: enabled
      ports:
        - protocol: TCP
          port: 4317   # OTLP gRPC
        - protocol: TCP
          port: 4318   # OTLP HTTP
    # Allow Grafana from ingress controller
    - from:
        - namespaceSelector:
            matchLabels:
              name: ingress-nginx
      ports:
        - port: 3000
```

### 10.3.4 Pod Anti-Affinity

```yaml
affinity:
  podAntiAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
      - labelSelector:
          matchLabels:
            app: opensearch
        topologyKey: kubernetes.io/hostname
```

Prevents all OpenSearch data nodes from landing on the same physical node (defeating HA).

### 10.3.5 Probes

```yaml
# OTel Collector health check
livenessProbe:
  httpGet:
    path: /
    port: 13133   # Collector health check extension
  initialDelaySeconds: 10
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /
    port: 13133
  initialDelaySeconds: 5
  periodSeconds: 5

# Prometheus
livenessProbe:
  httpGet:
    path: /-/healthy
    port: 9090
  initialDelaySeconds: 30
  periodSeconds: 15

readinessProbe:
  httpGet:
    path: /-/ready
    port: 9090
  initialDelaySeconds: 30
  periodSeconds: 15
```

---

## 10.4 Kubernetes Observability Targets

### 10.4.1 Node-Level (Node Exporter via DaemonSet)

```
Per Node:
  ├── node_cpu_seconds_total         (CPU usage per mode)
  ├── node_memory_MemAvailable_bytes  (Available memory)
  ├── node_disk_read_bytes_total      (Disk I/O)
  ├── node_network_receive_bytes_total (Network I/O)
  ├── node_filesystem_avail_bytes     (Disk space)
  └── node_load1/5/15                (Load average)
```

**USE Method applied to nodes**: Utilization (CPU/memory%), Saturation (load vs CPU count, I/O queue), Errors (disk read errors, network drops).

### 10.4.2 Pod-Level (cAdvisor via kubelet)

```
Per Pod:
  ├── container_cpu_usage_seconds_total
  ├── container_memory_working_set_bytes
  ├── container_network_receive_bytes_total
  └── container_fs_usage_bytes
```

### 10.4.3 K8s Objects (kube-state-metrics)

```
Per Deployment/StatefulSet/DaemonSet/Node/Pod:
  ├── kube_deployment_status_replicas_available
  ├── kube_deployment_status_replicas_updated
  ├── kube_pod_status_phase
  ├── kube_node_status_condition
  └── kube_persistentvolume_status_phase
```

**Critical kube-state-metrics alerts:**
- `kube_deployment_status_replicas_available < spec.replicas` → Pod not available
- `kube_pod_status_phase{phase="Failed"} > 0` → CrashLoopBackOff
- `kube_node_status_condition{condition="Ready", status="false"} > 0` → Node down

### 10.4.4 Control Plane (API Server, etcd, scheduler)

```
Per Control Plane Component:
  ├── apiserver_request_duration_seconds_bucket  (API latency)
  ├── apiserver_request_total                     (API rate)
  ├── etcd_disk_backend_commit_duration_seconds   (etcd write latency)
  └── scheduler_scheduling_duration_seconds       (scheduling latency)
```

**Critical control plane alerts:**
- `apiserver_request_duration_seconds` p99 > 1s → API server degraded
- `etcd_disk_backend_commit_duration_seconds` p99 > 100ms → etcd disk slow (everything breaks)
- `etcd_server_has_leader == 0` → etcd leader election lost (cluster state frozen)

---

## 10.5 Scaling on Kubernetes

### 10.5.1 Horizontal Pod Autoscaler (HPA)

Stateless components scale horizontally:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: otel-gateway
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: otel-collector-gateway
  minReplicas: 3
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

**Stateful components (Prometheus, OpenSearch) don't auto-scale.** They require manual scaling with data rebalancing. Use VPA (Vertical Pod Autoscaler) for resizing, or plan for horizontal scaling upfront.

### 10.5.2 Cluster Autoscaler

As observability pods grow, the cluster must scale:

```
HPA increases OpenSearch replicas 3→5
  → 2 new pods pending (insufficient CPU on existing nodes)
  → Cluster Autoscaler detects pending pods
  → Adds new node to cluster
  → Pods schedule on new node
  → OpenSearch rebalances shards to new nodes
```

**Ensure node selectors/affinity separate observability from application workloads:**

```yaml
nodeSelector:
  workload: observability  # Dedicated node group for observability

tolerations:
  - key: observability
    operator: Equal
    value: "true"
    effect: NoSchedule
```

---

## 10.6 Common Misconceptions

### "Helm is just 'install and forget'"

Helm installs the initial state. Upgrades, rollbacks, and configuration changes require understanding the chart's upgrade behavior. StatefulSets with PVCs cannot be easily recreated. Read the chart's NOTES.txt and understand what changing a value does.

### "One Prometheus per cluster is always enough"

At 2M+ active series per cluster, vertical scaling caps out. Plan for horizontal scaling (Thanos, sharding) before you hit the limit. Retrofitting scaling after hitting the limit is an emergency.

### "I can just use emptyDir for observability storage"

emptyDir is deleted when the pod restarts. All metrics, traces, logs are lost. Use PersistentVolumes for ALL stateful components. Test PVC resizing and snapshot recovery.

---

## Interview Questions — Phase 10

1. **Design the deployment architecture for an observability stack on a 50-node Kubernetes cluster.**

   *Answer core points*: ns=observability. OTel Collector as DaemonSet (lightweight, batch-only) → OTel Gateway Deployment (3 replicas, HPA, tail sampling) → Jaeger + Prometheus + OpenSearch (StatefulSets with PVCs). Prometheus Operator via Helm for CRDs + Grafana + Alertmanager + kube-state-metrics + Node Exporter. NetworkPolicy restricting ingress to OTLP ports. Pod anti-affinity for HA. Dedicated node group for observability via nodeSelector.

2. **Why run the OTel Collector as a DaemonSet instead of only a Deployment?**

   *Answer core points*: DaemonSet provides local buffering per node (survives gateway outage), reduces network topology complexity (services send to localhost), and provides node-level metadata enrichment (hostname, node IP). Gateway provides centralized tail sampling (needs to see complete traces). Both layers serve different purposes.

3. **What Kubernetes probes should you configure for Prometheus and why?**

   *Answer core points*: Liveness: `/-/healthy` — Prometheus is running and internal checks pass. Readiness: `/-/ready` — Prometheus has finished WAL replay and is ready to accept queries. Without readiness, traffic routes to Prometheus before WAL replay completes, causing query failures during startup. Without liveness, a hung Prometheus process stays alive indefinitely.

4. **How do you handle PersistentVolume sizing for OpenSearch logs? What happens when it fills up?**

   *Answer core points*: Size based on daily log volume × retention days × replication factor. Monitor `node_filesystem_avail_bytes`. When disk > 85%, ILM should roll over and delete old indices. If disk > 90% (watermark), OpenSearch stops allocating shards to that node. If disk > 95%, all indices become read-only. Mitigation: resize PVC (if storage class supports it), increase retention aggressiveness, or add data nodes.

---

**Next: Phase 11 — AWS Deployment**
