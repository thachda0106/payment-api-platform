# Mini Projects — Framework APIs

## FastAPI — Fraud Check API

**Run**: `uvicorn fraud_check_api:app --reload`
**Test**: `curl -X POST http://localhost:8000/fraud/check -H "Content-Type: application/json" -d '{"user_id":"U1","amount":5000000}'`

## Chi — Settlement Batch API

**Run**: `go run settlement_batch_api.go`
**Test**: `curl -X POST http://localhost:8080/v1/settlement/batch -d '{"period":"2026-05-01"}'`

## NestJS-style — Notification API

**Run**: `npx tsx notification_api.ts`
**Test**: `curl -X POST http://localhost:3000/notifications -H "Content-Type: application/json" -d '{"userId":"U1","channel":"push","title":"Payment","body":"100K VND"}'`

## Acceptance Criteria

| API | Tests |
|-----|-------|
| Fraud Check | POST valid request → 200 with score + decision. POST missing amount → 422 |
| Settlement Batch | POST valid period → 202 with settlement totals. GET /health → UP |
| Notification | POST valid notification → 201 with queued status. GET /notifications/:id → returns status |
