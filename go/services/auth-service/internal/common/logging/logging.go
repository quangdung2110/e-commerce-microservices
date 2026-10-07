// Package logging builds the service logger and carries a request-scoped logger
// through context. Telemetry rules come from docs/auth/operations.md: correlation
// identifiers and key identifiers are logged, credentials and raw email are not.
package logging

import (
	"context"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type contextKey struct{}

// New builds a logger from the configured level and format.
func New(level, format, service string) (*zap.Logger, error) {
	cfg := zap.NewProductionConfig()
	if format == "console" {
		cfg = zap.NewDevelopmentConfig()
	}
	cfg.EncoderConfig.TimeKey = "timestamp"
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
	return logger.With(zap.String("service", service)), nil
}

// WithContext stores a request-scoped logger.
func WithContext(ctx context.Context, logger *zap.Logger) context.Context {
	return context.WithValue(ctx, contextKey{}, logger)
}

// FromContext returns the request-scoped logger, or a no-op logger when the
// context carries none.
func FromContext(ctx context.Context) *zap.Logger {
	if logger, ok := ctx.Value(contextKey{}).(*zap.Logger); ok && logger != nil {
		return logger
	}
	return zap.NewNop()
}

// RedactEmail keeps just enough of an address to correlate reports without
// writing the full identifier into a log line.
func RedactEmail(email string) string {
	if email == "" {
		return ""
	}
	at := -1
	for i, r := range email {
		if r == '@' {
			at = i
			break
		}
	}
	if at <= 0 {
		return "***"
	}
	local := email[:at]
	domain := email[at:]
	if len(local) <= 1 {
		return "*" + domain
	}
	return local[:1] + "***" + domain
}
