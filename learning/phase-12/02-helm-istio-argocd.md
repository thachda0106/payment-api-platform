# Module 02 — Helm, Istio & ArgoCD

## 2.1 Helm — The Package Manager for Kubernetes

Helm packages Kubernetes manifests into **charts** (reusable, versioned, configurable).

```yaml
# Chart.yaml
apiVersion: v2
name: payment-service
version: 1.0.0

# values.yaml (default configuration)
replicaCount: 3
image: {repository: ghcr.io/payment-api/payment, tag: latest}
resources: {limits: {cpu: 1000m, memory: 512Mi}}

# templates/deployment.yaml
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: {{ .Values.replicaCount }}
  template:
    spec:
      containers:
      - name: {{ .Chart.Name }}
        image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
```

```bash
helm install payment ./charts/payment-service -f values-prod.yaml
helm upgrade payment ./charts/payment-service --set replicaCount=5
helm rollback payment 1
```

## 2.2 Istio Ambient Mesh

Service mesh provides: mTLS, traffic management, observability — without application code changes. Ambient mesh (Istio 1.22+) eliminates sidecar proxies: uses ztunnel (per-node L4 proxy) + waypoint proxies (per-namespace L7).

### mTLS

Every inter-pod call is authenticated and encrypted. Istio provisions certificates automatically (via cert-manager). No code changes needed. PCI DSS requirement 4 (encrypt transmission).

### Traffic Management

```yaml
apiVersion: networking.istio.io/v1
kind: VirtualService
spec:
  hosts: [payment-service]
  http:
  - match: [{headers: {version: {exact: "v2"}}}]
    route: [{destination: {host: payment-service, subset: v2}}]
  - route: [{destination: {host: payment-service, subset: v1}, weight: 90},
            {destination: {host: payment-service, subset: v2}, weight: 10}]
```
Canary: 90% v1, 10% v2. Monitor v2 metrics. If healthy → increase v2 weight. If degraded → rollback.

### Circuit Breaking (via DestinationRule)

```yaml
apiVersion: networking.istio.io/v1
kind: DestinationRule
spec:
  host: fraud-service
  trafficPolicy:
    connectionPool: {tcp: {maxConnections: 100}}
    outlierDetection:
      consecutive5xxErrors: 5
      interval: 30s
      baseEjectionTime: 60s
```

## 2.3 ArgoCD — GitOps

Declarative GitOps: the Git repository is the single source of truth. ArgoCD continuously compares the cluster state with the desired state in Git and auto-syncs.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
spec:
  source:
    repoURL: https://github.com/payment-api/infra
    path: k8s/overlays/production
  destination:
    server: https://kubernetes.default.svc
    namespace: payment-prod
  syncPolicy:
    automated: {prune: true, selfHeal: true}
```

**Sync flow**: `Git Push → ArgoCD detects drift → Auto-sync → Apply manifests → Health check → Green`

### Canary Deployments (Argo Rollouts)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
spec:
  strategy:
    canary:
      steps:
      - setWeight: 5      # 5% to canary
      - pause: {duration: 10m}  # Wait 10 minutes, observe metrics
      - setWeight: 20     # 20%
      - pause: {duration: 10m}
      - setWeight: 100    # 100% — promotion complete
```

## 2.4 Exercises

### Ex 2.1 — Helm Chart
Create a Helm chart for the Payment Service. Support: replicaCount, image tag, resource limits, environment variables. Deploy with different values files (dev/staging/prod).

### Ex 2.2 — Istio Traffic Split
Deploy v1 and v2 of a service. Configure Istio VirtualService: 90% v1, 10% v2. Send 100 requests via curl. Verify distribution.

### Ex 2.3 — ArgoCD Sync
Set up ArgoCD to sync from a Git repository. Make a change to a manifest. Observe ArgoCD detect the drift and auto-sync.

## 2.5 Self-Assessment

- [ ] Can create and deploy a Helm chart
- [ ] Understand Istio's mTLS, VirtualService, and DestinationRule
- [ ] Can configure a canary deployment with Argo Rollouts
- [ ] Understand the GitOps flow: Git → ArgoCD → Cluster
