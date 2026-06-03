package telemetry

import (
	"context"
	"go.opentelemetry.io/otel/attribute"
	"net/http"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

// HTTPMiddleware returns chi-compatible middleware that starts a span
// for every incoming HTTP request and propagates W3C trace context.
func HTTPMiddleware() func(http.Handler) http.Handler {
	propagator := otel.GetTextMapPropagator()
	tracer := otel.Tracer("")

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ctx := propagator.Extract(r.Context(), propagation.HeaderCarrier(r.Header))

			spanName := r.Method + " " + r.URL.Path
			ctx, span := tracer.Start(ctx, spanName,
				trace.WithSpanKind(trace.SpanKindServer),
				trace.WithAttributes(
					attribute.String("http.request.method", r.Method),
					attribute.String("http.route", r.URL.Path),
					attribute.String("url.full", r.URL.String()),
				),
			)
			defer span.End()

			// Propagate trace context to response headers
			propagator.Inject(ctx, propagation.HeaderCarrier(w.Header()))

			// Inject traceId and spanId into context for logging
			sc := span.SpanContext()
			ctx = contextWithTraceInfo(ctx, sc.TraceID().String(), sc.SpanID().String())

			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// contextKey type to avoid collisions.
type contextKey string

const (
	contextKeyTraceID contextKey = "traceId"
	contextKeySpanID  contextKey = "spanId"
)

func contextWithTraceInfo(ctx context.Context, traceID, spanID string) context.Context {
	ctx = context.WithValue(ctx, contextKeyTraceID, traceID)
	ctx = context.WithValue(ctx, contextKeySpanID, spanID)
	return ctx
}

// TraceIDFromContext extracts traceId from context.
func TraceIDFromContext(ctx context.Context) string {
	if v, ok := ctx.Value(contextKeyTraceID).(string); ok {
		return v
	}
	return ""
}

// SpanIDFromContext extracts spanId from context.
func SpanIDFromContext(ctx context.Context) string {
	if v, ok := ctx.Value(contextKeySpanID).(string); ok {
		return v
	}
	return ""
}
