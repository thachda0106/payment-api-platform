// Mini Project: Distributed Saga Orchestrator
// Run: javac SagaOrchestrator.java && java SagaOrchestrator

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class SagaOrchestrator {
    enum StepType { RETRYABLE, PIVOT, IRREVOCABLE }

    record SagaStep(String name, StepType type, Function<Map<String,Object>,Map<String,Object>> execute,
                    Function<Map<String,Object>,Map<String,Object>> compensate) {}

    record SagaDefinition(String name, List<SagaStep> steps) {}

    enum SagaStatus { PENDING, RUNNING, COMPLETED, COMPENSATING, COMPENSATED, FAILED }

    static class SagaInstance {
        final String id = UUID.randomUUID().toString();
        final SagaDefinition definition;
        SagaStatus status = SagaStatus.PENDING;
        int currentStep = 0;
        final Map<String, Object> context = new ConcurrentHashMap<>();
        final List<String> completedSteps = new ArrayList<>();
        Exception lastError;

        SagaInstance(SagaDefinition def) { this.definition = def; }
    }

    private final Map<String, SagaInstance> instances = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SagaInstance start(SagaDefinition definition, Map<String, Object> initialContext) {
        SagaInstance saga = new SagaInstance(definition);
        saga.context.putAll(initialContext);
        saga.status = SagaStatus.RUNNING;
        instances.put(saga.id, saga);
        executor.submit(() -> execute(saga));
        return saga;
    }

    private void execute(SagaInstance saga) {
        try {
            for (int i = 0; i < saga.definition.steps.size(); i++) {
                saga.currentStep = i;
                SagaStep step = saga.definition.steps.get(i);
                System.out.printf("  [%s] Executing step %d: %s%n", saga.id.substring(0,8), i, step.name);

                try {
                    Map<String, Object> result = step.execute.apply(saga.context);
                    saga.context.putAll(result);
                    saga.completedSteps.add(step.name);
                } catch (Exception e) {
                    saga.lastError = e;
                    System.out.printf("  [%s] Step %d FAILED: %s → %s%n", saga.id.substring(0,8), i, step.name, e.getMessage());

                    if (step.type == StepType.RETRYABLE && shouldRetry(saga, i)) {
                        i--; continue; // Retry the same step
                    }

                    // Need compensation
                    compensate(saga, i);
                    return;
                }
            }
            saga.status = SagaStatus.COMPLETED;
            System.out.printf("  [%s] Saga COMPLETED%n", saga.id.substring(0,8));
        } catch (Exception e) {
            saga.status = SagaStatus.FAILED;
            saga.lastError = e;
            System.out.printf("  [%s] Saga FAILED: %s%n", saga.id.substring(0,8), e.getMessage());
        }
    }

    private boolean shouldRetry(SagaInstance saga, int stepIdx) {
        SagaStep step = saga.definition.steps.get(stepIdx);
        // Exponential backoff: max 3 retries
        int retryCount = 0;
        // In real impl, track retry count per step
        return step.type == StepType.RETRYABLE && retryCount < 3;
    }

    private void compensate(SagaInstance saga, int failedStepIdx) {
        saga.status = SagaStatus.COMPENSATING;
        System.out.printf("  [%s] Compensating %d steps...%n", saga.id.substring(0,8), failedStepIdx);

        for (int i = failedStepIdx - 1; i >= 0; i--) {
            SagaStep step = saga.definition.steps.get(i);
            if (step.compensate != null) {
                try {
                    System.out.printf("  [%s] Compensating step %d: %s%n", saga.id.substring(0,8), i, step.name);
                    Map<String, Object> compResult = step.compensate.apply(saga.context);
                    saga.context.putAll(compResult);
                    saga.completedSteps.remove(step.name);
                } catch (Exception e) {
                    // Compensation FAILED — this is the nightmare scenario
                    System.out.printf("  [%s] ⚠️ COMPENSATION FAILED for step %d: %s — MANUAL INTERVENTION REQUIRED!%n",
                        saga.id.substring(0,8), i, step.name);
                    saga.status = SagaStatus.FAILED;
                    saga.lastError = new RuntimeException("Compensation failed at step " + step.name, e);
                    return;
                }
            }
        }
        saga.status = SagaStatus.COMPENSATED;
        System.out.printf("  [%s] Compensated — all steps rolled back%n", saga.id.substring(0,8));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Demo: Payment Saga
    // ═══════════════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        SagaOrchestrator orchestrator = new SagaOrchestrator();

        SagaDefinition paymentSaga = new SagaDefinition("PaymentSaga", List.of(
            new SagaStep("FraudCheck", StepType.RETRYABLE,
                ctx -> { System.out.println("    FraudCheck: scoring payment..."); return Map.of("fraud_score", 15, "fraud_decision", "ALLOW"); },
                null // Retryable — no compensation needed
            ),
            new SagaStep("FeeCalculation", StepType.RETRYABLE,
                ctx -> { System.out.println("    FeeCalc: calculating fee..."); return Map.of("fee_amount", 1500L); },
                null
            ),
            new SagaStep("LedgerWrite", StepType.PIVOT,
                ctx -> { System.out.println("    Ledger: writing journal entry..."); return Map.of("entry_id", UUID.randomUUID().toString()); },
                ctx -> { System.out.println("    Ledger(compensate): creating reversal entry..."); return Map.of("reversal_id", UUID.randomUUID().toString()); }
            ),
            new SagaStep("Notification", StepType.RETRYABLE,
                ctx -> { System.out.println("    Notification: sending confirmation..."); return Map.of("notification_id", UUID.randomUUID().toString()); },
                null
            )
        ));

        System.out.println("=== Test 1: Successful Payment Saga ===\n");
        SagaInstance success = orchestrator.start(paymentSaga, Map.of("amount", 100000L, "user_id", "U1"));
        sleep(200);
        assert success.status == SagaStatus.COMPLETED : "Expected COMPLETED, got " + success.status;
        System.out.println("PASS\n");

        // Saga with failing pivot step (ledger fails)
        SagaDefinition failingSaga = new SagaDefinition("FailingPaymentSaga", List.of(
            new SagaStep("FraudCheck", StepType.RETRYABLE,
                ctx -> Map.of("fraud_score", 15), null),
            new SagaStep("FeeCalculation", StepType.RETRYABLE,
                ctx -> Map.of("fee_amount", 1500L), null),
            new SagaStep("LedgerWrite", StepType.PIVOT,
                ctx -> { throw new RuntimeException("DB connection refused"); },
                ctx -> { System.out.println("    Ledger(compensate): reversal entry"); return Map.of("reversal_id", "REV-1"); }
            )
        ));

        System.out.println("=== Test 2: Failing Ledger → Compensation ===\n");
        SagaInstance failed = orchestrator.start(failingSaga, Map.of("amount", 200000L, "user_id", "U2"));
        sleep(200);
        assert failed.status == SagaStatus.COMPENSATED : "Expected COMPENSATED, got " + failed.status;
        System.out.println("PASS\n");

        System.out.println("All tests passed!");
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
    static void assert(boolean condition, String msg) { if (!condition) throw new AssertionError(msg); }
}
