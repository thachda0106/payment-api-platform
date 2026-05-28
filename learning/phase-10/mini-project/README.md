# Mini Project — Distributed Saga Orchestrator

## Goal

Build a saga orchestrator that executes multi-step distributed transactions with automatic compensation on failure — the pattern used by the Payment Service to coordinate fraud check → fee calculation → ledger write → notification.

## What You Will Build

- **Saga Definition**: Declarative definition of steps with types (RETRYABLE, PIVOT, IRREVOCABLE)
- **Step Execution**: Sequential execution with context passing between steps
- **Compensation**: On step failure, execute compensating transactions in reverse order
- **Retry**: Retryable steps retry on failure (with backoff)
- **Compensation Failure Detection**: If compensation itself fails, flag as MANUAL INTERVENTION REQUIRED
- **State Tracking**: Saga status: PENDING → RUNNING → COMPLETED/COMPENSATING → COMPENSATED/FAILED

## Architecture

```
SagaOrchestrator.start(definition, context)
  │
  ├── for each step:
  │     ├── RETRYABLE → execute, retry on failure
  │     ├── PIVOT → execute, on failure: compensate ALL prior steps
  │     └── IRREVOCABLE → execute, on failure: abort (cannot compensate)
  │
  └── All steps succeed → COMPLETED
       Any step fails → compensate in reverse order → COMPENSATED
```

## Run

```bash
javac SagaOrchestrator.java && java SagaOrchestrator
```

## Acceptance Criteria

1. Successful saga (all steps pass) → COMPLETED
2. Ledger write fails → FraudCheck + FeeCalc are compensated → COMPENSATED
3. Compensation itself fails → FAILED with "MANUAL INTERVENTION REQUIRED"
4. Retryable step retries on transient failure (max 3x)
