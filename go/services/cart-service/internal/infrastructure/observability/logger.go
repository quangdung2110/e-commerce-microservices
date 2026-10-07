package observability

import (
	"fmt"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

// LogConfig configures the structured logger.
type LogConfig struct {
	Level  string
	Format string
	Output string
}

// NewLogger builds the application logger.
func NewLogger(cfg LogConfig) (*zap.Logger, error) {
	level, err := zapcore.ParseLevel(cfg.Level)
	if err != nil {
		return nil, fmt.Errorf("parse log level %q: %w", cfg.Level, err)
	}

	zapCfg := zap.NewProductionConfig()
	zapCfg.Level = zap.NewAtomicLevelAt(level)
	zapCfg.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	if cfg.Format == "console" {
		zapCfg.Encoding = "console"
	}
	if cfg.Output != "" {
		zapCfg.OutputPaths = []string{cfg.Output}
	}

	logger, err := zapCfg.Build()
	if err != nil {
		return nil, fmt.Errorf("build logger: %w", err)
	}
	return logger, nil
}
