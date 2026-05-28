package main

import (
	"context"
	"encoding/csv"
	"fmt"
	"log"
	"net/http"
	_ "net/http/pprof"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// Payment represents a single payment transaction for settlement.
type Payment struct {
	ID         string
	MerchantID string
	Amount     int64
	Currency   string
	Status     string
}

// SettlementResult holds aggregated settlement per merchant.
type SettlementResult struct {
	MerchantID    string
	TotalAmount   int64
	TxCount       int64
	FeeAmount     int64
	NetAmount     int64  // TotalAmount - FeeAmount
}

// SettlementEngine processes payment batches concurrently.
type SettlementEngine struct {
	workers      int
	feePct       float64 // Percentage fee (e.g., 0.015 = 1.5%)
	processed    atomic.Int64
	errors       atomic.Int64
	totalAmount  atomic.Int64
}

func NewSettlementEngine(workers int, feePct float64) *SettlementEngine {
	return &SettlementEngine{workers: workers, feePct: feePct}
}

// Settle processes a batch of payments and returns merchant settlements.
func (e *SettlementEngine) Settle(ctx context.Context, payments []Payment) map[string]*SettlementResult {
	type job struct{ idx int; payment Payment }
	type result struct {
		merchantID string
		amount     int64
		fee        int64
	}

	jobs := make(chan job, len(payments))
	results := make(chan result, len(payments))

	// Start workers
	var wg sync.WaitGroup
	for w := 0; w < e.workers; w++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			for j := range jobs {
				select {
				case <-ctx.Done(): return
				default:
				}
				// Simulate per-payment processing (validation, enrichment)
				time.Sleep(1 * time.Millisecond)
				fee := int64(float64(j.payment.Amount) * e.feePct)
				results <- result{j.payment.MerchantID, j.payment.Amount, fee}
				e.processed.Add(1)
				e.totalAmount.Add(j.payment.Amount)
			}
		}(w)
	}

	// Send jobs
	for i, p := range payments { jobs <- job{i, p} }
	close(jobs)

	// Close results when workers done
	go func() { wg.Wait(); close(results) }()

	// Aggregate results by merchant
	settlements := make(map[string]*SettlementResult)
	for r := range results {
		s, ok := settlements[r.merchantID]
		if !ok { s = &SettlementResult{MerchantID: r.merchantID}; settlements[r.merchantID] = s }
		s.TotalAmount += r.amount
		s.TxCount++
		s.FeeAmount += r.fee
		s.NetAmount = s.TotalAmount - s.FeeAmount
	}
	return settlements
}

func (e *SettlementEngine) Metrics() map[string]any {
	return map[string]any{
		"processed":   e.processed.Load(),
		"errors":      e.errors.Load(),
		"totalAmount": e.totalAmount.Load(),
	}
}

// readCSV reads payments from a CSV file. Format: id,merchant_id,amount,currency,status
func readCSV(path string) ([]Payment, error) {
	f, err := os.Open(path)
	if err != nil { return nil, err }
	defer f.Close()

	var payments []Payment
	r := csv.NewReader(f)
	records, err := r.ReadAll()
	if err != nil { return nil, err }

	for i, rec := range records {
		if i == 0 { continue } // Skip header
		if len(rec) < 4 { continue }
		amount, _ := strconv.ParseInt(rec[2], 10, 64)
		status := "COMPLETED"
		if len(rec) > 4 { status = rec[4] }
		payments = append(payments, Payment{
			ID: rec[0], MerchantID: rec[1], Amount: amount, Currency: rec[3], Status: status,
		})
	}
	return payments, nil
}

// ═══════════════════════════════════════════════════════════════════════════
// MAIN
// ═══════════════════════════════════════════════════════════════════════════
func main() {
	// Create sample CSV if not exists
	if _, err := os.Stat("payments.csv"); os.IsNotExist(err) {
		createSampleCSV("payments.csv", 10000)
	}

	// pprof server
	go func() { http.ListenAndServe(":6060", nil) }()

	// Graceful shutdown
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	// Load payments
	payments, err := readCSV("payments.csv")
	if err != nil { log.Fatal(err) }

	// Settle
	engine := NewSettlementEngine(8, 0.015) // 8 workers, 1.5% fee
	fmt.Printf("Processing %d payments with %d workers...\n", len(payments), engine.workers)

	start := time.Now()
	go func() {
		<-quit
		fmt.Println("\nShutting down...")
		cancel()
	}()

	settlements := engine.Settle(ctx, payments)
	elapsed := time.Since(start)

	// Print results
	fmt.Printf("\n=== Settlement Results (%v) ===\n", elapsed.Round(time.Millisecond))
	fmt.Printf("%-15s %12s %8s %12s %12s\n", "Merchant", "Total", "Count", "Fees", "Net")
	fmt.Println("---------------------------------------------------------------")
	for _, s := range settlements {
		fmt.Printf("%-15s %12d %8d %12d %12d\n", s.MerchantID, s.TotalAmount, s.TxCount, s.FeeAmount, s.NetAmount)
	}

	m := engine.Metrics()
	fmt.Printf("\nMetrics: processed=%v totalAmount=%v\n", m["processed"], m["totalAmount"])
	fmt.Printf("Throughput: %.0f tx/s\n", float64(len(payments))/elapsed.Seconds())
	fmt.Println("\nSettlement engine is running. Access pprof at http://localhost:6060/debug/pprof/")
	select {} // Keep running for pprof
}

func createSampleCSV(path string, count int) {
	f, _ := os.Create(path)
	defer f.Close()
	w := csv.NewWriter(f)
	w.Write([]string{"id", "merchant_id", "amount", "currency", "status"})
	merchants := []string{"MOMOMART", "TECHSTORE", "COFFEESHOP", "ENTERPRISE", "MARKETPLACE"}
	for i := 0; i < count; i++ {
		w.Write([]string{
			fmt.Sprintf("PAY-%06d", i),
			merchants[i%len(merchants)],
			fmt.Sprintf("%d", 10000+int64(i*137)%990000),
			"VND",
			"COMPLETED",
		})
	}
	w.Flush()
	fmt.Printf("Created sample CSV with %d payments\n", count)
}
