package temporal

import (
	"fmt"

	"go.temporal.io/sdk/client"
	"go.uber.org/zap"
)

// TemporalClient wraps Temporal SDK client
type TemporalClient struct {
	client    client.Client
	namespace string
	logger    *zap.Logger
}

// TemporalConfig holds Temporal connection configuration
type TemporalConfig struct {
	HostPort  string
	Namespace string
	Logger    *zap.Logger
}

// NewTemporalClient creates a new Temporal client
func NewTemporalClient(config TemporalConfig) (*TemporalClient, error) {
	if config.Logger == nil {
		config.Logger, _ = zap.NewProduction()
	}

	if config.Namespace == "" {
		config.Namespace = "default"
	}

	// Create Temporal client
	c, err := client.Dial(client.Options{
		HostPort:  config.HostPort,
		Namespace: config.Namespace,
		Logger:    NewTemporalZapLogger(config.Logger),
	})
	if err != nil {
		return nil, fmt.Errorf("failed to create Temporal client: %w", err)
	}

	config.Logger.Info("Connected to Temporal",
		zap.String("hostPort", config.HostPort),
		zap.String("namespace", config.Namespace))

	return &TemporalClient{
		client:    c,
		namespace: config.Namespace,
		logger:    config.Logger,
	}, nil
}

// GetClient returns the underlying Temporal client
func (tc *TemporalClient) GetClient() client.Client {
	return tc.client
}

// GetNamespace returns the configured namespace
func (tc *TemporalClient) GetNamespace() string {
	return tc.namespace
}

// Close closes the Temporal client connection
func (tc *TemporalClient) Close() {
	tc.logger.Info("Closing Temporal client")
	tc.client.Close()
}

// TemporalZapLogger adapts zap.Logger to Temporal's logger interface
type TemporalZapLogger struct {
	logger *zap.Logger
}

// NewTemporalZapLogger creates a new Temporal-compatible logger
func NewTemporalZapLogger(logger *zap.Logger) *TemporalZapLogger {
	return &TemporalZapLogger{logger: logger}
}

func (l *TemporalZapLogger) Debug(msg string, keyvals ...interface{}) {
	l.logger.Sugar().Debugw(msg, keyvals...)
}

func (l *TemporalZapLogger) Info(msg string, keyvals ...interface{}) {
	l.logger.Sugar().Infow(msg, keyvals...)
}

func (l *TemporalZapLogger) Warn(msg string, keyvals ...interface{}) {
	l.logger.Sugar().Warnw(msg, keyvals...)
}

func (l *TemporalZapLogger) Error(msg string, keyvals ...interface{}) {
	l.logger.Sugar().Errorw(msg, keyvals...)
}
