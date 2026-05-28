"""Mini Project — Settlement Batch API (Chi)
Run: go run settlement_batch_api.go
Test: curl -X POST http://localhost:8080/v1/settlement/batch -d '{"period":"2026-05-01"}'
"""
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
)

type BatchRequest struct {
	Period string `json:"period"`
}
type BatchResponse struct {
	BatchID    string              `json:"batch_id"`
	Status     string              `json:"status"`
	Settlement map[string]int64    `json:"settlement"`
	Processed  int                 `json:"processed"`
	DurationMs float64             `json:"duration_ms"`
}

type SettlementService struct {
	mu sync.Mutex
}

func (s *SettlementService) ProcessBatch(ctx context.Context, period string) (*BatchResponse, error) {
	start := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()

	// Simulate: query DB for completed payments in period, aggregate by merchant
	time.Sleep(100 * time.Millisecond)
	settlement := map[string]int64{
		"MOMOMART":   rand.Int63n(50_000_000),
		"TECHSTORE":  rand.Int63n(30_000_000),
		"COFFEESHOP": rand.Int63n(10_000_000),
	}
	processed := 500 + rand.Intn(500)

	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	return &BatchResponse{
		BatchID:    fmt.Sprintf("BATCH-%d", time.Now().Unix()),
		Status:     "COMPLETED",
		Settlement: settlement,
		Processed:  processed,
		DurationMs: float64(time.Since(start).Microseconds()) / 1000.0,
	}, nil
}

func main() {
	svc := &SettlementService{}
	r := chi.NewRouter()

	// Middleware
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(middleware.Timeout(30 * time.Second))

	// Health
	r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "UP", "service": "settlement-api"})
	})

	// API
	r.Route("/v1/settlement", func(r chi.Router) {
		r.Post("/batch", func(w http.ResponseWriter, r *http.Request) {
			var req BatchRequest
			if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest); return
			}
			batch, err := svc.ProcessBatch(r.Context(), req.Period)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError); return
			}
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusAccepted)
			json.NewEncoder(w).Encode(batch)
		})
	})

	// Graceful shutdown
	srv := &http.Server{Addr: ":8080", Handler: r}
	go func() {
		log.Println("Settlement API listening on :8080")
		if err := srv.ListenAndServe(); err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("Shutting down...")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	srv.Shutdown(ctx)
	log.Println("Server stopped")
}
