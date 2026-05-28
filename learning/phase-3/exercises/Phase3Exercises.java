// Phase 3 Exercises — Java Deep Dive
// Compile: javac Phase3Exercises.java
// Run: java Phase3Exercises

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class Phase3Exercises {

    // ═══════════════════════════════════════════════════════════════════════
    // EX 1.1 — Sealed Payment States
    // ═══════════════════════════════════════════════════════════════════════
    sealed interface PaymentState permits Pending, Authorized, Completed, Failed {}
    record Pending() implements PaymentState {}
    record Authorized(long authTime) implements PaymentState {}
    record Completed(long completedAt) implements PaymentState {}
    record Failed(String reason) implements PaymentState {}

    static PaymentState processPayment(PaymentState state, boolean fraudPasses) {
        return switch (state) {
            case Pending p -> fraudPasses ? new Authorized(System.currentTimeMillis()) : new Failed("FRAUD_BLOCK");
            case Authorized a -> Math.random() > 0.1 ? new Completed(System.currentTimeMillis()) : new Failed("LEDGER_ERROR");
            case Completed c -> c;
            case Failed f -> f;
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EX 2.1 — Thread-Safe Wallet (3 implementations)
    // ═══════════════════════════════════════════════════════════════════════

    // Version A: synchronized
    static class SyncWallet {
        private long balance;
        synchronized boolean debit(long amount) { if (balance>=amount){balance-=amount;return true;} return false; }
        synchronized void credit(long amount) { balance+=amount; }
        synchronized long balance() { return balance; }
    }

    // Version B: ReentrantLock
    static class LockWallet {
        private long balance;
        private final ReentrantLock lock = new ReentrantLock();
        boolean debit(long amount) { lock.lock(); try { if(balance>=amount){balance-=amount;return true;} return false; } finally { lock.unlock(); } }
        void credit(long amount) { lock.lock(); try { balance+=amount; } finally { lock.unlock(); } }
        long balance() { lock.lock(); try { return balance; } finally { lock.unlock(); } }
    }

    // Version C: AtomicLong (optimistic)
    static class AtomicWallet {
        private final AtomicLong balance = new AtomicLong();
        boolean debit(long amount) {
            long current, updated;
            do { current=balance.get(); if(current<amount)return false; updated=current-amount; }
            while(!balance.compareAndSet(current, updated));
            return true;
        }
        void credit(long amount) { balance.addAndGet(amount); }
        long balance() { return balance.get(); }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EX 2.2 — Payment Pipeline with CompletableFuture
    // ═══════════════════════════════════════════════════════════════════════

    static record FraudResult(int score, String decision) {}
    static record FeeResult(long amount) {}
    static record PipelineResult(String status, long elapsedMs) {}

    static FraudResult fraudCheck(long amount) { sleep(20+ (long)(Math.random()*30)); return new FraudResult((int)(Math.random()*100), "ALLOW"); }
    static FeeResult feeCalc(long amount) { sleep(10+ (long)(Math.random()*20)); return new FeeResult((long)(amount*0.015)); }
    static void ledgerWrite(long amount) { sleep(30+ (long)(Math.random()*50)); }

    static PipelineResult pipelineSync(long amount) {
        long t0=System.currentTimeMillis();
        FraudResult fraud = fraudCheck(amount);
        FeeResult fee = feeCalc(amount);
        ledgerWrite(amount - fee.amount());
        return new PipelineResult("COMPLETED", System.currentTimeMillis()-t0);
    }

    static PipelineResult pipelineAsync(long amount) throws Exception {
        long t0=System.currentTimeMillis();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CompletableFuture<FraudResult> fraud = CompletableFuture.supplyAsync(()->fraudCheck(amount), pool);
        CompletableFuture<FeeResult> fee = CompletableFuture.supplyAsync(()->feeCalc(amount), pool);
        fraud.thenCombine(fee, (f,fe)->{ ledgerWrite(amount-fe.amount()); return null; }).get();
        pool.close();
        return new PipelineResult("COMPLETED", System.currentTimeMillis()-t0);
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN — Run all tests
    // ═══════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {
        System.out.println("=== Phase 3 Exercises ===\n");

        // Ex 1.1 test
        assert processPayment(new Pending(), true) instanceof Authorized;
        assert processPayment(new Pending(), false) instanceof Failed;
        System.out.println("Ex 1.1: Sealed payment states — OK");

        // Ex 2.1 test
        for (Object wallet : new Object[]{new SyncWallet(), new LockWallet(), new AtomicWallet()}) {
            if (wallet instanceof SyncWallet w) { w.credit(100000); assert w.debit(30000); assert w.balance()==70000; }
            else if (wallet instanceof LockWallet w) { w.credit(100000); assert w.debit(30000); assert w.balance()==70000; }
            else { AtomicWallet w = (AtomicWallet)wallet; w.credit(100000); assert w.debit(30000); assert w.balance()==70000; }
        }
        System.out.println("Ex 2.1: Thread-safe wallets — OK");

        // Ex 2.2 test
        PipelineResult sync = pipelineSync(100000);
        PipelineResult async = pipelineAsync(100000);
        System.out.printf("Ex 2.2: Sync=%dms Async=%dms — OK%n", sync.elapsedMs(), async.elapsedMs());

        System.out.println("\nAll exercises passed!");
    }
}
