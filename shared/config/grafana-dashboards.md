# Grafana Dashboard Configuration

## Dashboard 1: RED Dashboard (per service)

### Panels

| Row | Panel | Query | Visual |
|-----|-------|-------|--------|
| **Overview** | Request Rate | `rate(http_requests_total{job="$service"}[5m])` | Graph |
| | Error Rate | `rate(http_requests_total{job="$service",status=~"5.."}[5m]) / rate(http_requests_total{job="$service"}[5m])` | Stat (red if > 1%) |
| | p95 Latency | `histogram_quantile(0.95, rate(http_request_duration_seconds_bucket{job="$service"}[5m]))` | Stat + Graph |
| | p99 Latency | `histogram_quantile(0.99, rate(http_request_duration_seconds_bucket{job="$service"}[5m]))` | Stat |
| | Availability | `sum(up{job="$service"}) / count(up{job="$service"}) * 100` | Stat (green if > 99.9%) |
| **Throughput** | Requests/sec (by status) | `sum(rate(http_requests_total{job="$service"}[5m])) by (status)` | Stacked graph |
| **Latency** | Percentile distribution | `histogram_quantile(0.50, ...)`, `0.95`, `0.99` | Multi-line graph |

Variables: `$service` (multi-select: payment-service, fraud-service, financial-core, notification-service)

---

## Dashboard 2: Kafka Dashboard

| Row | Panel | Query |
|-----|-------|-------|
| **Overview** | Consumer Lag (all groups) | `kafka_consumergroup_lag` | Table |
| | Messages/sec | `rate(kafka_server_brokertopicmetrics_messagesin_total[5m])` | Graph |
| | DLQ Messages | `kafka_topic_partition_current_offset{topic="payment-events-dlq"}` | Stat |
| **Per Consumer** | Lag by partition | `kafka_consumergroup_lag` by partition | Heatmap |
| | Consumer throughput | `rate(kafka_consumer_fetch_manager_records_consumed_rate[5m])` | Graph |

---

## Dashboard 3: Business Dashboard

| Row | Panel | Query |
|-----|-------|-------|
| **Payments** | Payments Created/min | `rate(payment_created_total[5m]) * 60` | Stat |
| | Payments Approved/min | `rate(payment_approved_total[5m]) * 60` | Stat |
| | Payments Rejected/min | `rate(payment_rejected_total[5m]) * 60` | Stat |
| | Approval Rate | `payment_approved_total / payment_created_total * 100` | Gauge |
| **Notifications** | Notifications Sent/min | `rate(notification_sent_total[5m]) * 60` | Stat |
| | Notification Success Rate | `notification_sent_total / notification_attempted_total * 100` | Gauge |
| **Ledger** | Balance Delta | `ledger_balance_invariant` (should be 0) | Stat |
| | Journal Entries/min | `rate(journal_entries_created_total[5m]) * 60` | Stat |

---

## Grafana Provisioning

Add to `docker-compose.yml` grafana service:

```yaml
grafana:
  volumes:
    - ./shared/config/grafana-dashboards:/etc/grafana/provisioning/dashboards:ro
    - ./shared/config/grafana-datasources.yml:/etc/grafana/provisioning/datasources/datasources.yml:ro
  environment:
    GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH: /etc/grafana/provisioning/dashboards/red-dashboard.json
```
