# Module 02 — SLOs, Alerting & Incident Response

## 2.1 SLI, SLO, SLA

| Term | Definition | Example |
|------|-----------|---------|
| **SLI** | Service Level Indicator — measured metric | Success rate of `POST /v1/payments` (2xx responses) |
| **SLO** | Service Level Objective — target | 99.95% success rate over 30 days |
| **SLA** | Service Level Agreement — legal contract | "If success rate < 99.9%, customer gets 25% refund" |

### Payment Platform SLOs

| Service | SLI | SLO | Window |
|---------|-----|:---:|:------:|
| Payment API | Success rate (2xx) | 99.95% | 30 days |
| Payment API | P99 latency | < 500ms | 30 days |
| Fraud Service | Availability | 99.9% | 30 days |
| Financial Core | Durability (no lost entries) | 100% | Forever |
| Notification | Delivery within 5 min | 99.5% | 30 days |
| Transaction Read | Freshness (< 5s stale) | 99.9% | 30 days |

## 2.2 Error Budgets

**Error Budget** = 1 - SLO = allowed failures.

- SLO = 99.95% → Error Budget = 0.05% = 43.2 minutes of downtime per 30 days
- SLO = 99.9% → Error Budget = 0.1% = 43.8 minutes
- SLO = 99.99% → Error Budget = 0.01% = 4.38 minutes

### Error Budget Policy

- **Budget > 50% remaining**: Ship features freely
- **Budget 20-50%**: Slow down, focus on reliability
- **Budget < 20%**: STOP all feature work, fix reliability
- **Budget exhausted**: Escalate to VP Engineering

### Burn Rate Alerts

| Burn Rate | Consumption | Alert When |
|:---------:|:----------:|-----------|
| 14.4x | 2% of budget in 1 hour | Page on-call (critical) |
| 6x | 5% of budget in 6 hours | Page on-call (warning) |
| 1x | Entire budget over 30 days | Ticket (low urgency) |

```promql
# Error budget burn rate > 14.4 (consuming 2% in 1 hour)
(sum(rate(http_requests_total{status=~"5.."}[1h])) / sum(rate(http_requests_total[1h])))
  > (14.4 * 0.0005)  # 14.4 × error_budget_pct
```

## 2.3 Alerting

### Severity Levels

| Level | Meaning | Response | Notification |
|:-----:|---------|----------|-------------|
| **P0** | Revenue-impacting outage | Immediate (5 min ack, 30 min resolve) | Page + call |
| **P1** | Partial service degradation | Urgent (15 min ack, 2 hr resolve) | Page |
| **P2** | Non-critical issue | Normal (1 hr ack, next business day) | Ticket |
| **P3** | Cosmetic / informational | Low (next sprint) | Ticket |

### Alert Design Rules

1. **Every alert must be actionable**: If no human action is needed, it's a dashboard, not an alert.
2. **Alert on symptoms, not causes**: "Payment error rate > 5%" (symptom), not "CPU > 80%" (cause). CPU being high might be normal.
3. **Include runbook link**: Every alert annotation has a link to the remediation runbook.
4. **Avoid alert fatigue**: If an alert fires and no one cares, remove it. False positives train operators to ignore alerts.

## 2.4 Incident Response

### Lifecycle

```
Detection → Triage → Mitigate → Resolve → Postmortem
```

1. **Detection**: Alert fires. On-call engineer acknowledges within SLA.
2. **Triage**: Determine severity (P0-P3), impact (how many users affected?), scope (which services?).
3. **Mitigate**: STOP THE BLEEDING. Rollback the deploy. Divert traffic. Fail over to DR. Don't debug first — restore service first.
4. **Resolve**: Fix root cause. Deploy permanent fix. Verify recovery.
5. **Postmortem**: Blameless. What happened? Timeline. Root causes (5 Whys). Action items.

### Postmortem Template

```markdown
# Incident Postmortem: Payment API outage 2026-05-27

## Summary
Payment API returned 503 errors for 12 minutes (10:30-10:42 UTC).
Affected 15,000 payment attempts. No data loss. Financial Core not affected.

## Timeline (UTC)
- 10:28: Deployed v2.3.1 (connection pool config change)
- 10:30: PagerDuty alert: error rate > 5%
- 10:32: On-call acknowledged
- 10:35: Identified: max_connections set to 5 (should be 50)
- 10:38: Rollback initiated
- 10:42: Service recovered

## Root Cause
Configuration error: max_connections typo (5 instead of 50).

## 5 Whys
1. Why did error rate spike? → Connection pool exhausted
2. Why was pool exhausted? → max_connections set to 5
3. Why was it set to 5? → Human error in config PR (typo)
4. Why wasn't it caught? → No validation on connection pool config
5. Why no validation? → Config validation not part of CI pipeline

## Action Items
- [ ] Add config validation in CI (P0, owner: @alice, due: 2026-05-30)
- [ ] Add canary deployment with gradual traffic shift (P1, owner: @bob)
- [ ] Add connection pool saturation alert (P2, owner: @charlie)
```

## 2.5 Exercises

### Ex 2.1 — SLO Definition
Define SLOs for each payment platform service. Choose SLIs, set targets, justify each target. Calculate error budgets in minutes/month.

### Ex 2.2 — Alert Configuration
Write Prometheus alerting rules for: (a) payment error rate > 5% for 5 minutes, (b) P99 latency > 1s for 10 minutes, (c) error budget burn rate > 14.4. Include severity labels and runbook links.

### Ex 2.3 — Incident Simulation
Simulate: Payment Service returns 500 errors for 10 minutes. Write the postmortem: timeline, root cause (5 Whys), action items. Practice the incident commander role.

## 2.6 Self-Assessment

- [ ] Can define SLIs and SLOs for every service in the payment platform
- [ ] Understand error budgets and burn rate alerting
- [ ] Can write Prometheus alerting rules with appropriate thresholds and for duration
- [ ] Can lead an incident response: triage → mitigate → resolve → postmortem
- [ ] Know the difference between alerting on symptoms vs causes
