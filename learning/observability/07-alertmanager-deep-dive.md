# Phase 7 — Alertmanager Deep Dive

> **Duration**: 3-4 days | **Prerequisites**: Phases 1-4 (Prometheus metrics understanding)
>
> **Goal**: Understand the alert lifecycle, routing, grouping, deduplication, and how to prevent alert fatigue.

---

## 7.1 Why Alerting Exists

### 7.1.1 The Fundamental Problem

Observability systems generate DATA. Humans cannot watch data. They have jobs, sleep schedules, and finite attention.

**Alerting bridges the gap**: The system watches data continuously. When pre-defined conditions are met, it escalates to a human.

```
Telemetry → Alert Rules → Alertmanager → Human (on-call)
```

### 7.1.2 The Evolution of Alerting

| Era | Model | Problem |
|-----|-------|---------|
| 1990s | Nagios: `check_http` every 5 min | Only infrastructure-level, no application context |
| 2005 | PagerDuty: On-call schedules + escalation | Better routing, same alerts |
| 2015 | Prometheus + Alertmanager: Metric-based alerting | Complex conditions, no alert fatigue protection |
| 2020+ | SLO-based alerting: Alert on error budget burn | Stops noise; only pages when user impact exists |

**The key insight**: Alerts should fire when USER EXPERIENCE degrades, not when infrastructure metrics cross arbitrary thresholds.

- Page when `error_rate > 5%` (user-visible)
- Don't page when `cpu > 90%` (infrastructure concern — might not affect users)

---

## 7.2 The Alert Lifecycle

### 7.2.1 End-to-End Flow

```
Metric (Prometheus)
    ↓ Prometheus evaluates alert rules every N seconds
Alert Rule (expr + for + annotations)
    ↓ Rule condition is true
Pending Alert (waiting for `for` duration)
    ↓ `for` duration elapsed
Firing Alert
    ↓ Sent to Alertmanager via HTTP POST
Alertmanager Processing
    ├── Inhibition (suppress if parent alert fires)
    ├── Silencing (maintenance window scilence)
    ├── Grouping (group related alerts)
    ├── Routing (send to correct team)
    └── Deduplication (remove identical alerts)
    ↓
Notification Receiver (PagerDuty, Slack, Email, OpsGenie, webhook)
    ↓
On-Call Engineer Responds
    ↓
Alert Resolves (Prometheus rule no longer true)
    ↓
Resolution Notification (PagerDuty auto-resolve)
```

### 7.2.2 Alert States

```
┌──────────┐    rule evaluates true    ┌──────────┐
│ INACTIVE │──────────────────────────→│ PENDING  │
└──────────┘                           └─────┬────┘
     ↑                                        │
     │                               `for` duration elapsed
     │                                        │
     │                                  ┌─────▼────┐
     │    rule evaluates false          │ FIRING   │────→ Alertmanager
     └──────────────────────────────────│          │
                                        └──────────┘
```

**Inactive**: Rule condition is false. No alert.
**Pending**: Rule condition is true, but hasn't been true long enough (`for` duration not met). No notification sent.
**Firing**: Rule condition has been true for the `for` duration. Alert sent to Alertmanager.
**Resolved**: Rule condition became false. Resolution notification sent.

---

## 7.3 Alertmanager Architecture

### 7.3.1 Processing Pipeline

```
Alerts from Prometheus
    │
    ▼
┌──────────────┐
│   Inhibit    │  ← "If database is down, suppress 'connection pool full'"
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   Silence    │  ← "Maintenance window: silence all payment-service alerts"
└──────┬───────┘
       │
       ▼
┌──────────────┐
│    Route     │  ← "Send payment alerts to payment team's PagerDuty"
└──────┬───────┘
       │
       ▼
┌──────────────┐
│    Group     │  ← "Group all payment-service alerts into one notification"
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   Deduplicate│  ← "Same alert firing again? Don't send duplicate page"
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   Notify     │  ← PagerDuty / Slack / Email / OpsGenie / webhook
└──────────────┘
```

### 7.3.2 Routing

Routes determine WHERE alerts go based on labels:

```yaml
route:
  receiver: 'default-pagerduty'    # Default receiver
  group_by: ['alertname', 'cluster']  # Group alerts by these labels
  group_wait: 30s                  # Wait 30s before sending first notification (collect more alerts)
  group_interval: 5m               # Wait 5m between notifications for the same group
  repeat_interval: 4h              # Repeat notification every 4h if alert is still firing

  routes:
    # Payment team: severity=critical goes to PagerDuty
    - match:
        severity: critical
        team: payments
      receiver: 'payment-pagerduty'
      group_by: ['alertname', 'service']

    # All warning alerts go to Slack (don't wake people up)
    - match:
        severity: warning
      receiver: 'slack-warnings'

    # Database alerts have their own escalation
    - match_re:
        service: 'postgresql|redis|opensearch'
      receiver: 'dba-pagerduty'
      group_by: ['alertname']
```

**Routing tree**: Routes form a tree. An alert traverses the tree, matching the FIRST route whose `match` conditions apply. Child routes inherit parent configuration unless overridden.

### 7.3.3 Grouping

Grouping prevents the "alert storm" — 100 instances of the same service all triggering the same alert simultaneously.

```yaml
group_by: ['alertname', 'service']

Without grouping (group_by: []):
  Page 1: "HighErrorRate: payment-service (instance-1)"
  Page 2: "HighErrorRate: payment-service (instance-2)"
  ... 100 pages for 100 instances

With grouping (group_by: ['alertname', 'service']):
  1 notification: "5 alerts: HighErrorRate (payment-service)"
  [details show all 5 firing instances]
```

**`group_wait: 30s`**: When the first alert in a group fires, wait 30 seconds before sending the notification. This collects any additional alerts that fire in that window, bundling them into one notification.

**`group_interval: 5m`**: If NEW alerts are added to an already-firing group, wait 5 minutes before sending the update. Prevents notification spam from rolling deployments.

### 7.3.4 Inhibition

Inhibition suppresses alerts when a higher-priority alert is already firing.

```yaml
inhibit_rules:
  # If the database is down, suppress "connection pool exhausted" alerts
  - source_match:
      alertname: 'DatabaseDown'
      severity: 'critical'
    target_match_re:
      alertname: 'Database.*'
    equal: ['instance', 'datacenter']

  # If a node is unreachable, suppress all alerts from that node
  - source_match:
      alertname: 'NodeUnreachable'
    target_match_re:
      alertname: '.+'
    equal: ['instance']
```

**The rationale**: If `DatabaseDown` is firing, `DatabaseConnectionPoolExhausted` and `DatabaseSlowQueries` are symptoms, not root causes. Paging for symptoms distracts from fixing the root cause. Inhibition keeps focus.

### 7.3.5 Silencing

Silences are temporary, manual alert suppressions:

```yaml
# Maintenance window: 2024-01-15 02:00-04:00 UTC
silence:
  - matchers:
      - name: service
        value: payment-service
    startsAt: 2024-01-15T02:00:00Z
    endsAt: 2024-01-15T04:00:00Z
    comment: "Database migration — expected downtime"
```

**When to silence:**
- Planned maintenance (database upgrades, cluster scaling)
- Known issues with active fix in progress (silence until deployed)
- Deployments that cause expected metric spikes

**When NOT to silence:**
- "This alert is noisy, I'll silence it permanently" — fix the alert threshold instead
- Covering up a real problem that should be investigated

### 7.3.6 Deduplication

Alertmanager deduplicates alerts with identical labels. If Prometheus-A and Prometheus-B both send the same alert for the same instance, only one notification fires.

```
Prometheus-A → Alertmanager: HighErrorRate{service="payment", instance="i-1"}
Prometheus-B → Alertmanager: HighErrorRate{service="payment", instance="i-1"}
                                      ↑
                            Deduplicated: one notification
```

Deduplication key: the alert's labels (minus volatile labels like `__alert_id__`).

---

## 7.4 Notification Receivers

### 7.4.1 PagerDuty

```yaml
receivers:
  - name: 'payment-pagerduty'
    pagerduty_configs:
      - routing_key: 'abc123...'
        severity: 'critical'
        description: '{{ .CommonAnnotations.description }}'
        client: 'Prometheus'
        client_url: '{{ .ExternalURL }}'
        details:
          firing: '{{ .Alerts.Firing | len }}'
          resolved: '{{ .Alerts.Resolved | len }}'
```

### 7.4.2 Slack

```yaml
receivers:
  - name: 'slack-warnings'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/...'
        channel: '#alerts-warning'
        title: '{{ .GroupLabels.alertname }}'
        text: '{{ .CommonAnnotations.description }}'
```

### 7.4.3 Webhook (Custom Integration)

```yaml
receivers:
  - name: 'custom-webhook'
    webhook_configs:
      - url: 'https://incident-tool.example.com/api/alerts'
        send_resolved: true
```

**The webhook payload** (JSON sent to your endpoint):

```json
{
  "version": "4",
  "groupKey": "{}/{alertname=\"HighErrorRate\"}:{service=\"payment-service\"}",
  "status": "firing",
  "receiver": "payment-pagerduty",
  "groupLabels": {
    "alertname": "HighErrorRate",
    "service": "payment-service"
  },
  "commonLabels": {
    "alertname": "HighErrorRate",
    "severity": "critical",
    "service": "payment-service"
  },
  "commonAnnotations": {
    "summary": "Payment service error rate > 5%",
    "description": "Error rate is 12.4% for the last 5 minutes",
    "runbook": "https://wiki.example.com/runbooks/payment-high-error-rate"
  },
  "externalURL": "https://prometheus.example.com",
  "alerts": [
    {
      "status": "firing",
      "labels": {...},
      "annotations": {...},
      "startsAt": "2024-01-15T14:23:45Z",
      "endsAt": "0001-01-01T00:00:00Z",
      "generatorURL": "https://prometheus.example.com/graph?g0.expr=..."
    }
  ]
}
```

---

## 7.5 Alert Fatigue

### 7.5.1 The Problem

Alert fatigue is when alerts fire so frequently that on-call engineers stop paying attention. Every alert becomes "probably another false positive."

**Symptoms:**
- "I have 500 unread alert emails"
- "This alert has been firing for 3 months, nobody looks at it anymore"
- "I got paged at 3 AM for something that fixed itself in 2 minutes"
- Slacking a warning channel that nobody reads

### 7.5.2 Root Causes

| Cause | Example | Fix |
|-------|---------|-----|
| Thresholds too tight | `cpu > 60%` pages (normal) | Set to 85-90%, or use sustained threshold |
| No `for` clause | Every momentary spike pages | Add `for: 5m` |
| Too many alerts per service | 50 alerts per service | Consolidate; alert on SLO burn, not individual metrics |
| Alerting on symptoms, not causes | Alert on "high CPU" instead of "high error rate" | Page on user impact, notify on infra |
| No runbook | "What do I do about this alert?" | Every alert needs a runbook URL |
| Alerting on non-actionable metrics | "Redis hits 75% memory" | Alert when memory is projected to run out (predict_linear) |

### 7.5.3 The SLO-Based Approach

Instead of threshold-based alerting ("CPU > 80%"), alert on error budget burn rate:

```promql
# Error budget burn rate: how fast are we consuming our error budget?

# SLO: 99.9% availability (0.1% error budget)
# Alert when burn rate > 14.4 (will exhaust budget in 1 hour)

(
  sum(rate(http_requests_total{status=~"5.."}[1h]))
    /
  sum(rate(http_requests_total[1h]))
)
> 0.1 * 14.4   # SLO error budget * burn rate threshold
```

**Multi-window, multi-burn-rate alerting** (Google SRE approach):

| Burn Rate | Error Budget Consumed | Time to Exhaust | Alert Severity |
|-----------|----------------------|-----------------|---------------|
| 14.4x | 2% in 1 hour | 5 hours | Page (critical) |
| 1x | 2% in 2 days | 100 days | Notify (warning) |

```
Critical page:  (error_rate_1h > 14.4 × SLO) AND (error_rate_5m > 14.4 × SLO)
                → Burns 2% of error budget in 1 hour → PAGE

Warning notify: (error_rate_6h > 1 × SLO) AND (error_rate_30m > 1 × SLO)
                → Burns 2% of error budget in 2 days → TICKET/SLACK
```

**Why two windows**: The short window confirms the problem is ongoing (not a spike that already recovered). The long window confirms the burn rate is sustained (not a momentary blip). Both must be true.

---

## 7.6 Production Alerting Design

### 7.6.1 Alert Severity Taxonomy

| Severity | Meaning | Notification | Response Time | Example |
|----------|---------|--------------|---------------|---------|
| **Critical (P1)** | User-facing outage, revenue loss | Page (PagerDuty, phone) | 5 minutes | Payment processing down, 50% error rate |
| **Major (P2)** | Degraded service, partial impact | Page | 30 minutes | p99 latency > 5s, DB replication lag > 10s |
| **Minor (P3)** | Non-urgent issue, can wait | Slack/Email/Ticket | Next business day | Disk 80% full, cert expires in 7 days |
| **Warning (P4)** | Informational, no action needed | Slack/Email | None required | Deployment completed, new release detected |

### 7.6.2 Every Alert Must Include

1. **Summary**: One-line description of the problem
2. **Description**: Detailed explanation with current values
3. **Severity**: Critical/Warning/Info
4. **Runbook URL**: Link to step-by-step response procedure
5. **Dashboard URL**: Link to relevant Grafana dashboard
6. **Playbook URL**: Link to incident response playbook

### 7.6.3 Alert Review Process

Schedule a weekly alert review:
1. **Did every alert that fired require action?** If not, adjust threshold or remove.
2. **Were any incidents NOT caught by alerts?** If so, add an alert.
3. **Did any alert fire too late?** If so, adjust threshold to be more sensitive.
4. **Are any alerts permanently silenced?** If so, fix or remove them.

---

## 7.7 Common Misconceptions

### "More alerts = better coverage"

Every alert costs attention. 100 alerts covering every possible failure mode means 0 alerts get real attention. Coverage without precision is noise.

### "Alertmanager automatically fixes problems"

Alertmanager tells SOMEONE that something is wrong. It does not diagnose, fix, or prevent. It's a notification router, not a remediation engine.

### "All alerts should wake someone up"

Only user-impacting issues should page. Everything else should go to Slack/Email during business hours. Distinguish between "a human should know about this" vs "a human must wake up for this."

### "Alert on CPU/Memory/Disk because it's easy to set up"

Alert on user impact (error rate, latency) and use infra metrics for debugging dashboards. High CPU that causes no errors is not an emergency.

---

## Interview Questions — Phase 7

1. **Explain the Alertmanager processing pipeline. In what order are inhibition, silencing, grouping, and routing applied?**

   *Answer core points*: Inhibit → Silence → Route → Group → Deduplicate → Notify. Inhibition first (suppress symptoms when root cause is known), then silence (maintenance windows), then route (determine recipient), then group (bundle related), then dedup (remove duplicates), then notify.

2. **What is alert fatigue? How would you design an alerting system to prevent it?**

   *Answer core points*: Alert fatigue = so many alerts that engineers stop responding. Prevention: (1) Alert on user impact (error budget burn rate), not infra thresholds. (2) Use `for` clause to require sustained conditions. (3) SLO-based multi-window burn rate alerting. (4) Weekly alert review to remove noisy alerts. (5) Clear severity taxonomy (page only critical).

3. **How does grouping work in Alertmanager? Why is `group_wait` important?**

   *Answer core points*: Grouping bundles alerts with identical `group_by` labels into a single notification. `group_wait` delays the first notification to collect additional alerts that fire during the window. Without it, a rolling deployment triggering the same alert on 100 instances generates 100 individual pages.

4. **What's the difference between inhibition and silencing?**

   *Answer core points*: Inhibition is rule-based, automatic ("if DatabaseDown fires, suppress DatabaseConnectionPoolExhausted"). Silencing is manual, time-bounded ("silence payment-service alerts during 2am-4am maintenance window"). Inhibition captures causal relationships automatically.

5. **Why is SLO-based alerting superior to threshold-based alerting?**

   *Answer core points*: Threshold-based ("CPU > 80%") alerts on infrastructure symptoms that may not affect users. SLO-based ("error budget burning at 14.4x rate") alerts on user-impacting conditions with proportional urgency. SLO alerting asks "is the user experience degraded?" rather than "is this metric crossing an arbitrary line?"

---

**Next: Phase 8 — Grafana Deep Dive**
