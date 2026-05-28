# Module 03 — Operations, KRaft, MirrorMaker 2 & Platform Design

## 3.1 KRaft — Kafka Without Zookeeper

Since Kafka 3.5 (production-ready), Kafka runs in KRaft mode — no Zookeeper. Controller quorum (3-5 nodes) uses Raft for metadata consensus. Metadata stored in `__cluster_metadata` topic.

**Benefits**: Single system to operate, better scalability (no ZK metadata bottleneck), simpler deployment. Migration: rolling upgrade, metadata migrates ZK→KRaft, decommission Zookeeper.

## 3.2 MirrorMaker 2 — Cross-Cluster DR

For multi-region: active cluster (ap-southeast-1) → MM2 replicates → standby cluster (ap-southeast-3).

MM2 is a Kafka Connect source connector. Reads from source, writes to target. Syncs: partition structure, consumer offsets, ACLs. Target topic naming: `source_cluster.source_topic`.

**Payment DR flow**: MM2 replicates all payment topics. If primary region fails → DNS flip to standby → consumers resume from MM2-synced offsets. Data loss window: MM2 latency + consumer checkpoint interval (minutes).

## 3.3 Operations

### Adding a Broker

```bash
kafka-reassign-partitions.sh --bootstrap-server localhost:9092 \
    --generate --topics-to-move-json-file topics.json \
    --broker-list "0,1,2,3"  # Include new broker
# Review plan, then --execute
```

### Replacing a Failed Broker

NEVER reuse a failed broker's `broker.id` unless its disks are intact. If disk lost: add NEW broker ID, reassign partitions. If `unclean.leader.election.enable=false` (default, safe), partitions without ISR are offline.

### Consumer Lag Diagnosis

```bash
kafka-consumer-groups --bootstrap-server localhost:9092 \
    --describe --group notification-service  # Shows LAG per partition
```

### Disk Failure

Use JBOD (not RAID). Multiple disks per broker. One disk fails → partitions on other disks continue. Recovery: remove failed disk from `log.dirs`, restart, reassign.

## 3.4 Payment Platform Kafka Design

### Configuration

```ini
# Broker
num.partitions = 12              # Default for payment topics
default.replication.factor = 3   # 3 copies (survives 2 failures)
min.insync.replicas = 2          # Durability: need 2 ack
unclean.leader.election.enable = false  # Safety over availability

# Retention
log.retention.hours = 168        # 7 days (payment topics)
log.retention.bytes = 107374182400  # 100GB per partition
log.segment.bytes = 1073741824   # 1GB segments
log.cleanup.policy = delete      # Time-based deletion (not compaction for most)

# Network
num.network.threads = 8
num.io.threads = 16
socket.send.buffer.bytes = 1048576
socket.receive.buffer.bytes = 1048576
```

### Monitoring & Alerting

| Metric | Warning | Critical |
|--------|:-------:|:--------:|
| Consumer lag | > 5,000 | > 50,000 |
| ISR shrink | < 3 | < 2 |
| Under-replicated partitions | > 0 for 1m | > 0 for 10m |
| Active controller count | != 1 | — |
| Offline partitions | > 0 | — |
| Broker disk usage | > 70% | > 85% |
| Network processor idle % | < 30% | < 10% |

## 3.5 Exercises

### Ex 3.1 — Broker Failure Recovery
Set up 3-broker cluster. Kill one broker. Observe: leader re-election, ISR change, consumer rebalance. Bring broker back. Observe catch-up.

### Ex 3.2 — Consumer Lag Drill
Produce at 10,000 msg/s. Consume at 1,000 msg/s. Observe lag grow. Scale consumer group from 1→4 consumers. Observe lag stabilize. Add partitions. Scale to 8 consumers.

### Ex 3.3 — Topic Design Exercise
Design the complete Kafka topic catalog for the payment platform. For each topic: name, partition count, replication factor, retention, partition key, producer, consumers.

## 3.6 Self-Assessment

- [ ] Can add a broker to a Kafka cluster and reassign partitions
- [ ] Understand KRaft architecture and why it replaces Zookeeper
- [ ] Can configure MirrorMaker 2 for cross-cluster replication
- [ ] Know the monitoring metrics that matter for payment workloads
- [ ] Can design a complete topic catalog for a distributed system
