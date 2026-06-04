# Phase 6 — OpenSearch Deep Dive

> **Duration**: 1-2 weeks | **Prerequisites**: Phases 1-5
>
> **Goal**: Understand OpenSearch/Elasticsearch internals — how inverted indexes work, cluster architecture, log storage design, and ILM.

---

## 6.1 The Search Engine Model

### 6.1.1 Why Logs Fit a Search Engine

Logs have specific characteristics that map well to search engines:

| Log Characteristic | OpenSearch Feature |
|-------------------|-------------------|
| **Write-heavy** (append only) | Near-real-time indexing, LSM-like segments |
| **Text search** ("find ERROR lines") | Inverted index, full-text analysis |
| **Time-based** (most queries have time range) | Time-based indices, range filters |
| **Structured + unstructured** (JSON body + message text) | Multi-field mapping, text + keyword |
| **Retention/archival** (old logs less valuable) | ILM: hot → warm → cold → delete |
| **Read volume << Write volume** | Optimized for indexing, not query |

### 6.1.2 Why NOT a Relational Database

PostgreSQL storing logs:

```sql
SELECT * FROM logs
WHERE timestamp BETWEEN '2024-01-15 14:00' AND '2024-01-15 14:05'
  AND body ILIKE '%ERROR%'
  AND service = 'payment-service'
ORDER BY timestamp DESC;
```

**Problems:**
1. `ILIKE '%ERROR%'` → sequential scan (can't use B-tree index for substring match)
2. No relevance scoring (which ERROR is most important?)
3. Text analysis is application-level (no stemming, tokenization)
4. JSON fields require JSONB and GIN indexes, which add complexity

Search engines treat text search as a first-class operation.

---

## 6.2 The Inverted Index

### 6.2.1 How It Works

**Forward index** (your mental model): Document → Words

```
Doc 1: "Payment failed: insufficient funds in wallet"
Doc 2: "Payment processed successfully"
Doc 3: "Wallet balance updated: insufficient funds detected"
```

**Inverted index** (OpenSearch's model): Word → Documents

```
payment       → [Doc 1, Doc 2]
failed        → [Doc 1]
insufficient  → [Doc 1, Doc 3]
funds         → [Doc 1, Doc 3]
wallet        → [Doc 1, Doc 3]
processed     → [Doc 2]
successfully  → [Doc 2]
balance       → [Doc 3]
updated       → [Doc 3]
detected      → [Doc 3]
```

**Why this is fast**: A query for "insufficient funds" becomes:
1. Look up "insufficient" → [Doc 1, Doc 3]
2. Look up "funds" → [Doc 1, Doc 3]
3. Intersection: [Doc 1, Doc 3] ∩ [Doc 1, Doc 3] = [Doc 1, Doc 3]
4. Score by relevance (TF-IDF or BM25)
5. Return results

No scanning. No LIKE query. Direct dictionary lookup.

### 6.2.2 Text Analysis Pipeline

When a document is indexed, it goes through analysis:

```
Raw text: "Payment failed: INSUFFICIENT FUNDS in wallet #42"
    ↓
Character Filters: Remove HTML, replace patterns
    ↓
Tokenizer: Split into tokens
    ["Payment", "failed", "INSUFFICIENT", "FUNDS", "in", "wallet", "42"]
    ↓
Token Filters: Lowercase, stem, remove stop words
    ["payment", "fail", "insufficient", "fund", "wallet"]
    ↓
Indexed Terms: Stored in inverted index
```

**Why analysis matters**: Without it, a query for "FAILED" wouldn't match "failed" (case sensitivity). A query for "insufficient" wouldn't match "insufficiency" (no stemming). Analysis normalizes both indexed text AND query text to a common form.

### 6.2.3 keyword vs text

```
"service": {
  "type": "keyword"     ← Exact match. "payment-service" ≠ "Payment-Service"
}

"message": {
  "type": "text",       ← Analyzed. "Payment failed" tokenized to ["payment", "fail"]
  "fields": {
    "keyword": {
      "type": "keyword" ← Also keep raw version for exact matching
    }
  }
}
```

**Use keyword for**:
- Service names, hostnames, environment, log levels
- Anything you'll filer/exact-match/aggregate on

**Use text for**:
- Log messages, error descriptions, stack traces
- Anything you'll full-text search

---

## 6.3 OpenSearch Cluster Architecture

### 6.3.1 Nodes and Roles

```
┌─────────────────────────────────────────────────────────┐
│                  OpenSearch Cluster                      │
│                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Master Node  │  │ Master Node  │  │ Master Node  │  │
│  │ (eligible)   │  │ (eligible)   │  │ (eligible)   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│        │                                                  │
│  ┌─────▼──────────────────────────────────────────────┐ │
│  │                Data Nodes                            │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐         │ │
│  │  │Data Node │  │Data Node │  │Data Node │  ...    │ │
│  │  │ (hot)    │  │ (hot)    │  │ (warm)   │         │ │
│  │  └──────────┘  └──────────┘  └──────────┘         │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌──────────────┐  ┌──────────────┐                     │
│  │Ingest Node   │  │Coordinator   │                     │
│  │(pre-process) │  │(scatter/gath)│                     │
│  └──────────────┘  └──────────────┘                     │
└─────────────────────────────────────────────────────────┘
```

### 6.3.2 Master Nodes

Master nodes manage the cluster (not data):
- Create/delete indices
- Allocate shards to nodes
- Monitor node health
- Maintain cluster state

**Requirements**: 3 master-eligible nodes for quorum. Odd number to prevent split-brain.

**Split-brain scenario**: 2 master nodes, network partition between them. Each thinks it's the master. Both accept writes. Data diverges. When partition heals, there's no way to reconcile.

**Why odd number**: With 3 masters, quorum = 2. If one node is partitioned, the other 2 can still form a quorum. The partitioned node cannot (needs 2, has 1). No split-brain.

### 6.3.3 Data Nodes

Data nodes store data and handle search/aggregation:

| Tier | Hardware | Purpose | Example |
|------|----------|---------|---------|
| **Hot** | NVMe SSD | Recent, frequently queried data | Last 3 days |
| **Warm** | HDD | Less frequently queried | Day 4-30 |
| **Cold** | Object storage (S3) | Rarely queried, archive | Day 31-90 |

**Hot tier**:
- Fastest disks (NVMe)
- Most CPU (for indexing + search concurrently)
- Smaller capacity (high cost)

**Warm tier**:
- Slower disks (HDD)
- Less CPU (mostly reads, occasional index ops)
- Larger capacity (lower cost)

### 6.3.4 Index, Shard, Replica

```
Index: otel-logs-2024-01-15
├── Primary Shard 0 (on data-node-1)
│   ├── Replica Shard 0 (on data-node-2)
│   └── Replica Shard 0 (on data-node-3)
├── Primary Shard 1 (on data-node-2)
│   ├── Replica Shard 1 (on data-node-3)
│   └── Replica Shard 1 (on data-node-1)
├── Primary Shard 2 (on data-node-3)
│   ├── Replica Shard 2 (on data-node-1)
│   └── Replica Shard 2 (on data-node-2)
└── Primary Shard 3 (on data-node-1)
    ├── Replica Shard 3 (on data-node-2)
    └── Replica Shard 3 (on data-node-3)
```

**Shard**: A Lucene index instance. The fundamental unit of storage and parallelism.
**Primary shard**: The authoritative copy. All writes go to primaries.
**Replica shard**: A copy of a primary. Handles reads. Can be promoted to primary if the primary is lost.

**How many shards?**

```
Too few shards:  Can't parallelize writes/reads across nodes
Too many shards: Overhead per shard (heap memory, file handles, merge operations)

Rule of thumb: shard_size should be 10-50 GB
  Daily index with 100 GB/day → 5 primary shards (20 GB each)
  Daily index with 10 GB/day  → 1 primary shard (10 GB each)
```

**Write path (simplified):**

```
Indexing Request → Routing (hash doc_id → shard #)
                    ↓
               Primary Shard (write)
                    ↓
               Replication (sync to replicas)
                    ↓
               Acknowledge to client (when N/2+1 replicas confirm)
```

**Read path (simplified):**

```
Search Request → Coordinator Node
                    ↓
               Scatter: Send query to ALL shards (primary or replica)
                    ↓
               Gather: Collect results from all shards
                    ↓
               Merge: Sort, deduplicate, aggregate
                    ↓
               Return to client
```

### 6.3.5 Segments and Refresh

Within a shard, data is organized into segments (immutable Lucene indexes):

```
Shard
├── Segment 0 (immutable, on disk)
├── Segment 1 (immutable, on disk)
├── Segment 2 (immutable, on disk)
└── In-memory buffer (new writes)
        ↓
    Refresh (every 1s by default)
        ↓
    New Segment (immutable, in filesystem cache)
```

**The refresh problem**: Newly indexed data is NOT searchable until refreshed. Default refresh interval is 1s — new data is searchable after ~1 second.

**For log storage**: Increase refresh interval to 5-30 seconds. Logs don't need instant searchability. Reducing refresh frequency reduces segment creation, which reduces merge overhead.

```json
PUT /otel-logs-2024-01-15/_settings
{
  "index": {
    "refresh_interval": "30s"
  }
}
```

**Segment merge**: Many small segments → fewer large segments.

```
[Seg0][Seg1][Seg2][Seg3][Seg4]  (5 small segments, each 100MB)
        ↓ merge
[Seg0-2][Seg3-4]                (2 medium segments)
        ↓ merge
[Seg0-4]                        (1 large segment, 500MB)
```

Merging is I/O and CPU heavy but necessary:
- Too many segments → slow search (must check each segment)
- Too many segments → high memory (file handles per segment)
- Merged segments → faster search, lower memory, but write I/O during merge

---

## 6.4 Index Lifecycle Management (ILM)

### 6.4.1 The Lifecycle

```
Create → Hot → Warm → Cold → Delete
```

### 6.4.2 ILM Policy

```json
{
  "policy": {
    "phases": {
      "hot": {
        "min_age": "0ms",
        "actions": {
          "rollover": {
            "max_age": "1d",
            "max_size": "50gb",
            "max_docs": 100000000
          }
        }
      },
      "warm": {
        "min_age": "3d",
        "actions": {
          "forcemerge": {
            "max_num_segments": 1
          },
          "shrink": {
            "number_of_shards": 1
          },
          "allocate": {
            "require": {
              "data": "warm"
            }
          }
        }
      },
      "cold": {
        "min_age": "30d",
        "actions": {
          "allocate": {
            "require": {
              "data": "cold"
            }
          }
        }
      },
      "delete": {
        "min_age": "90d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
```

**Hot phase**:
- `rollover`: Create new index when current reaches 1 day OR 50GB OR 100M docs
- Index actively written to and queried

**Warm phase** (entered 3 days after rollover):
- `forcemerge`: Merge all segments into 1 (optimize for read, reduce memory)
- `shrink`: Reduce to 1 shard (data is smaller, fewer queries)
- `allocate`: Move to warm tier (HDD)

**Cold phase** (entered 30 days after rollover):
- `allocate`: Move to cold tier (cheapest storage)
- Index is read-only, rarely accessed

**Delete phase** (entered 90 days after rollover):
- `delete`: Drop the index entirely

### 6.4.3 Rollover Indices

Instead of time-based index creation (cron job at midnight), rollover indices create a new index when the current one exceeds a threshold:

```
Write alias: otel-logs (always points to the current write index)

otel-logs → otel-logs-000001

When otel-logs-000001 exceeds 50 GB:
otel-logs → otel-logs-000002  (new writes go here)
otel-logs-000001 enters warm phase
```

**Why rollover instead of time-based**: A service's log volume isn't constant. A Black Friday traffic spike could create a 200GB daily index (too big for a single shard). Rollover handles variable volume: when size/documents exceed threshold, roll over regardless of time.

---

## 6.5 Log Storage Design

### 6.5.1 Index Mapping for Logs

```json
{
  "mappings": {
    "properties": {
      "@timestamp": { "type": "date" },
      "level": { "type": "keyword" },
      "service": { "type": "keyword" },
      "trace_id": { "type": "keyword" },
      "span_id": { "type": "keyword" },
      "message": {
        "type": "text",
        "fields": {
          "keyword": { "type": "keyword", "ignore_above": 256 }
        }
      },
      "attributes": {
        "type": "object",
        "properties": {
          "payment_id": { "type": "keyword" },
          "user_id": { "type": "keyword" },
          "amount": { "type": "double" },
          "error_code": { "type": "keyword" }
        }
      }
    }
  }
}
```

**Mapping choices explained:**

| Field | Type | Reason |
|-------|------|--------|
| `@timestamp` | `date` | Time range queries, Grafana time filter |
| `level` | `keyword` | Exact match: `level:ERROR`. Aggregation: ERROR count by service |
| `service` | `keyword` | Filter by service name |
| `trace_id` | `keyword` | Jump from trace to logs (trace → log correlation) |
| `message` | `text` + `keyword` | Full-text search on log body + exact match on short messages |
| `attributes.*` | `object` | Nested attributes preserved, individual fields queryable |

### 6.5.2 Dynamic Mapping — The Silent Villain

OpenSearch automatically detects and maps new fields. This is convenient but dangerous for logs:

```json
// Your log emits a new attribute:
{"attributes": {"new_field": "some_value"}}

// OpenSearch automatically creates the mapping:
{"new_field": {"type": "text", "fields": {"keyword": {"type": "keyword"}}}}

// Next log entry, same field:
{"attributes": {"new_field": 42}}

// ERROR: Can't index integer into text field!
```

**Fix**: Disable dynamic mapping or use dynamic templates:

```json
{
  "mappings": {
    "dynamic_templates": [
      {
        "strings_as_keyword": {
          "match_mapping_type": "string",
          "mapping": {
            "type": "keyword"
          }
        }
      }
    ]
  }
}
```

Or for strict control:

```json
{
  "mappings": {
    "dynamic": "strict"  // Reject unknown fields
  }
}
```

### 6.5.3 Bulk Indexing

Logs should be indexed via the Bulk API:

```
POST /_bulk
{"index":{"_index":"otel-logs","_id":"..."}}
{"@timestamp":"...","level":"ERROR","message":"...","service":"payment-service"}
{"index":{"_index":"otel-logs","_id":"..."}}
{"@timestamp":"...","level":"INFO","message":"...","service":"auth-service"}
...
```

**Bulk API performance:**
- Single bulk request with 500-1000 documents is optimal
- Too small (< 100 docs): too many HTTP requests
- Too large (> 5000 docs): memory pressure on OpenSearch node

---

## 6.6 Scaling OpenSearch for Logs

### 6.6.1 Hardware Sizing

| Scale | Log volume/day | Nodes | Node spec | Storage | Retention |
|-------|---------------|-------|-----------|---------|-----------|
| Small | 10 GB | 3 (hot) | 4 vCPU, 16 GB RAM | 200 GB NVMe | 7 days |
| Medium | 100 GB | 3 (hot) + 3 (warm) | 8 vCPU, 32 GB RAM | 1 TB NVMe + 10 TB HDD | 30 days hot + 60 days warm |
| Large | 1 TB | 5 (hot) + 5 (warm) + 3 (cold) | 16 vCPU, 64 GB RAM | 5 TB NVMe + 50 TB HDD + S3 | 7 days hot + 30 warm + 365 cold |

### 6.6.2 JVM Heap Sizing

```
Heap = min(31 GB, 50% of available RAM)

Why 31 GB cap: Beyond 32 GB, JVM compressed OOPs (Object-Oriented Pointers) no longer work.
Pointers become 8 bytes instead of 4 bytes → ~30% more memory for the same data.
Set at 31 GB to stay under the 32 GB compressed OOP threshold.

Remaining 50% RAM: Used by Lucene for filesystem cache (off-heap).
```

### 6.6.3 Shard Strategy for Log Writes

**The primary shard is the write bottleneck.** All writes to a shard must go through its primary.

```
Write throughput:
  Small shard (< 10 GB): 10,000-20,000 docs/sec
  Large shard (< 50 GB): 5,000-10,000 docs/sec
```

**Formula**: `#_shards = required_write_throughput / single_shard_throughput`

```
50,000 log lines/sec → 5 primary shards (10,000 each)
```

**Replicas**: 1 replica per shard doubles storage but provides HA. For logs (write-heavy, data can be replayed), 0-1 replicas are often sufficient.

---

## 6.7 Common Misconceptions

### "OpenSearch replaces a relational database"

OpenSearch is a search engine (specialized OLAP). Don't use it for transactional data, payments, user accounts, or anything requiring ACID. It's for search, aggregation, and time-series log storage.

### "More shards = more parallelism = better performance"

More shards means more overhead per node (file handles, merge operations, heap). A node with 1000 shards performs worse than a node with 100 shards (even with the same data volume). Target 10-50 GB per shard.

### "Refresh interval should be 1s for real-time log visibility"

Logs do NOT need real-time visibility. Set refresh to 5-30s. The 1s default is for interactive search (e-commerce product search). Log search is human-led debugging, not real-time.

### "Store all logs for years"

90% of log value is in the first 7 days. After 30 days, only compliance-relevant logs need retention. Implement hot/warm/cold/delete aggressively.

---

## Interview Questions — Phase 6

1. **Explain how an inverted index works and why it makes text search fast.**

   *Answer core points*: Maps terms → document IDs. Query "ERROR payment" → look up "ERROR" → [docs], look up "payment" → [docs] → intersect → sorted by relevance. O(terms) lookup instead of O(docs) scan. Text analysis (lowercase, stemming) ensures query terms match indexed terms.

2. **How does the write path work in OpenSearch? What's the role of the refresh interval?**

   *Answer core points*: Document → routing (hash → shard) → primary shard (in-memory buffer) → refresh (1s default: flush buffer to segment, becomes searchable) → translog (WAL for durability) → flush (commit segment to disk). Refresh interval controls how quickly new data becomes searchable. For logs, increase to 5-30s to reduce segment churn.

3. **Design an ILM policy for logs with 7-day hot, 30-day warm, and 365-day cold retention. What's the rationale for each phase?**

   *Answer core points*: Hot (0-7d): NVMe, active indexing+search, rollover at 50GB. Warm (7-30d): HDD, forcemerge→1 segment, shrink→1 shard, read-optimized. Cold (30-365d): S3/cheapest storage, rarely accessed. Delete (365d+): drop index. Rationale: cost optimization — storage cost decreases with data age, matching query frequency.

4. **What causes "shard is too large" and how do you fix it?**

   *Answer core points*: Shard > 50GB causes slow recovery, rebalancing, and merge operations. Fix: (1) Increase number of primary shards on index creation (can't change after, must reindex), (2) Use rollover indices (new index = new shard config), (3) Target 10-50GB per shard based on write throughput.

5. **Why does OpenSearch need master nodes even though data nodes store all the data?**

   *Answer core points*: Master nodes manage cluster state (index creation, shard allocation, node membership). They coordinate the cluster without competing with data operations for resources. Separate master nodes prevent cluster state updates from being delayed by heavy indexing/search load. 3 master-eligible nodes for quorum.

---

**Next: Phase 7 — Alertmanager Deep Dive**
