package port

import "context"
import "errors"

// HealthChecker defines the interface for health checks used in the health handler.
type HealthChecker interface {
	Ping(ctx context.Context) error
}

// Sentinel errors for port-level (infrastructure) errors.
// These complement domain/errors.go with errors specific to
// infrastructure adapters (Redis, PostgreSQL, Kafka, gRPC).
var (
	// Redis-related errors
	ErrRedisConnectionFailed = errors.New("redis connection failed")
	ErrRedisTimeout          = errors.New("redis operation timed out")
	ErrLuaScriptFailed       = errors.New("lua script execution failed")
	ErrNegativeCacheNotFound  = errors.New("negative cache key not found")

	// PostgreSQL-related errors
	ErrDBConnectionFailed = errors.New("database connection failed")
	ErrDBQueryFailed       = errors.New("database query failed")
	ErrDBTransactionFailed  = errors.New("database transaction failed")
	ErrDBNoRows            = errors.New("database returned no rows")

	// Kafka-related errors
	ErrKafkaPublishFailed  = errors.New("failed to publish message to kafka")
	ErrKafkaProducerClosed = errors.New("kafka producer is closed")

	// gRPC-related errors
	ErrGrpcConnectionFailed = errors.New("grpc connection failed")
	ErrGrpcTimeout          = errors.New("grpc call timed out")
	ErrGrpcProductNotFound  = errors.New("product not found via grpc")

	// Domain-port-specific errors (errors returned by Lua scripts)
	ErrCartFull               = errors.New("cart has reached maximum SKU capacity")
	ErrQuantityInvalid        = errors.New("quantity invalid (zero or negative)")
	ErrCannotDecrementNonExisting = errors.New("cannot decrement quantity of non-existing item")
	ErrStepInvalid            = errors.New("step must be non-zero integer")
)
