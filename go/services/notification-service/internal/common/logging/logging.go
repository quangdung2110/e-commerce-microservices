// Package logging builds the service logger and carries a scoped logger through
// context. Rules from docs/notification/11-privacy.md §3 and 14-observability.md
// §6: identifiers are logged, recipient addresses, template_data, rendered
// content and secrets never are.
package logging

import (
	"context"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type contextKey struct{}

// New builds a logger from the configured level and format.
func New(level, format, service, role string) (*zap.Logger, error) {
	cfg := zap.NewProductionConfig()
	if format == "console" {
		cfg = zap.NewDevelopmentConfig()
	}
	cfg.EncoderConfig.TimeKey = "ts"
	cfg.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder

	parsed, err := zapcore.ParseLevel(level)
	if err != nil {
		return nil, err
	}
	cfg.Level = zap.NewAtomicLevelAt(parsed)

	logger, err := cfg.Build()
	if err != nil {
		return nil, err
	}
	return logger.With(zap.String("service", service), zap.String("role", role)), nil
}

// WithContext stores a scoped logger.
func WithContext(ctx context.Context, logger *zap.Logger) context.Context {
	return context.WithValue(ctx, contextKey{}, logger)
}

// FromContext returns the scoped logger, or a no-op logger when the context
// carries none.
func FromContext(ctx context.Context) *zap.Logger {
	if logger, ok := ctx.Value(contextKey{}).(*zap.Logger); ok && logger != nil {
		return logger
	}
	return zap.NewNop()
}

// Audit returns a logger for the ops audit stream (14-observability.md §6).
func Audit(logger *zap.Logger) *zap.Logger {
	return logger.With(zap.Bool("audit", true))
}
