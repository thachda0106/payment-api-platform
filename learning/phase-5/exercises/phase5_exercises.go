package main

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

// ═══════════════════════════════════════════════════════════════════════════
// 1.1 — Generic Stack
// ═══════════════════════════════════════════════════════════════════════════
type Stack[T any] struct {
	items []T
}

func (s *Stack[T]) Push(item T) { s.items = append(s.items, item) }
func (s *Stack[T]) Pop() (T, bool) {
	if len(s.items) == 0 { var zero T; return zero, false }
	item := s.items[len(s.items)-1]
	s.items = s.items[:len(s.items)-1]
	return item, true
}
func (s *Stack[T]) Peek() (T, bool) {
	if len(s.items) == 0 { var zero T; return zero, false }
	return s.items[len(s.items)-1], true
}
func (s *Stack[T]) IsEmpty() bool { return len(s.items) == 0 }
func (s *Stack[T]) Size() int { return len(s.items) }

// ═══════════════════════════════════════════════════════════════════════════
// 2.1 — Goroutine Pipeline
// ═══════════════════════════════════════════════════════════════════════════
func generate(nums ...int) <-chan int {
	out := make(chan int)
	go func() { for _, n := range nums { out <- n }; close(out) }()
	return out
}
func square(in <-chan int) <-chan int {
	out := make(chan int)
	go func() { for n := range in { out <- n * n }; close(out) }()
	return out
}
func printChan(in <-chan int, label string) {
	for n := range in { fmt.Printf("%s: %d\n", label, n) }
}

// ═══════════════════════════════════════════════════════════════════════════
// 2.2 — Worker Pool with Rate Limiting
// ═══════════════════════════════════════════════════════════════════════════
type Metrics struct {
	processed atomic.Int64
	errors    atomic.Int64
}

func workerPool(numWorkers int, ratePerSec int, jobs []int, ctx context.Context) *Metrics {
	m := &Metrics{}
	ticker := time.NewTicker(time.Second / time.Duration(ratePerSec))
	defer ticker.Stop()

	jobCh := make(chan int, len(jobs))
	for _, j := range jobs { jobCh <- j }
	close(jobCh)

	var wg sync.WaitGroup
	for w := 0; w < numWorkers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for job := range jobCh {
				select {
				case <-ctx.Done(): return
				case <-ticker.C:
					// Simulate work
					time.Sleep(10 * time.Millisecond)
					m.processed.Add(1)
				}
			}
		}()
	}
	wg.Wait()
	return m
}

// ═══════════════════════════════════════════════════════════════════════════
// MAIN
// ═══════════════════════════════════════════════════════════════════════════
func main() {
	fmt.Println("=== Phase 5 Exercises ===\n")

	// Ex 1.1: Generic Stack
	s := Stack[int]{}
	s.Push(1); s.Push(2); s.Push(3)
	if v, ok := s.Pop(); ok && v == 3 { fmt.Println("Ex 1.1: Stack — OK") }

	// Ex 2.1: Pipeline
	fmt.Print("Ex 2.1: Pipeline — ")
	printChan(square(generate(2, 3, 4)), "result")
	fmt.Println("OK")

	// Ex 2.2: Worker pool
	ctx := context.Background()
	m := workerPool(4, 10, make([]int, 20), ctx)
	fmt.Printf("Ex 2.2: Worker pool — processed=%d errors=%d OK\n", m.processed.Load(), m.errors.Load())
}
