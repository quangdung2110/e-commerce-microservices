package redis

import (
	"context"
	"fmt"
	"math/rand"
	"sync"
	"time"

	"cart-service/internal/common/keybuilder"

	"github.com/redis/go-redis/v9"
)

// RedisConfig holds all configuration for the Redis client.
// This mirrors the top-level `redis` block in application.yaml.
type RedisConfig struct {
	Addrs         []string `mapstructure:"addrs"`
	Password      string   `mapstructure:"password"`
	DB            int      `mapstructure:"db"`
	PoolSize      int      `mapstructure:"pool_size"`
	MinIdleConns  int      `mapstructure:"min_idle_conns"`
	ReadTimeout   string   `mapstructure:"read_timeout"`
	WriteTimeout  string   `mapstructure:"write_timeout"`
	NumShards     int      `mapstructure:"num_shards"`
}

// Client is the high-level Redis client wrapper.
// It wraps a single-node or cluster go-redis client and provides
// application-specific methods. For Redis Cluster in production,
// use NewClusterClient instead.
type Client struct {
	rdb redis.UniversalClient
}

// ShardManager distributes users across Redis stream shards.
// It uses CRC32 modulo to deterministically assign users to shards,
// ensuring that all workers processing the same user's events land on
// the same shard (consistent hashing).
//
// The shard count is configured via redis.num_shards and should match
// the number of outbox_relay workers for optimal throughput.
type ShardManager struct {
	numShards uint32
	kb        *keybuilder.KeyBuilder
}

// NewShardManager creates a ShardManager with the given shard count.
func NewShardManager(numShards int) *ShardManager {
	return &ShardManager{
		numShards: uint32(numShards),
		kb:        keybuilder.New(uint32(numShards)),
	}
}

// NumShards returns the number of configured shards.
func (sm *ShardManager) NumShards() uint32 {
	return sm.numShards
}

// KeyBuilder returns the KeyBuilder instance.
func (sm *ShardManager) KeyBuilder() *keybuilder.KeyBuilder {
	return sm.kb
}

// ShardForUser returns the shard index for a given user.
func (sm *ShardManager) ShardForUser(userID string) uint32 {
	return sm.kb.StreamShardByUserID(userID)
}

// EnsureConsumerGroups creates consumer groups on all stream shards.
// This must be called before starting the outbox_relay workers.
// groupName is the consumer group name (e.g. "cart_relay_group").
func (sm *ShardManager) EnsureConsumerGroups(ctx context.Context, client *Client, groupName string) error {
	for i := uint32(0); i < sm.numShards; i++ {
		streamKey := sm.kb.StreamShard(i)
		err := client.Raw().XGroupCreateMkStream(ctx, streamKey, groupName, "0").Err()
		if err != nil && !isConsumerGroupExistsErr(err) {
			return fmt.Errorf("create consumer group %s on %s: %w", groupName, streamKey, err)
		}
	}
	return nil
}

// isConsumerGroupExistsErr checks if the error indicates the consumer group already exists.
func isConsumerGroupExistsErr(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	return len(s) >= 44 && (s == "BUSYGROUP Consumer Group name already exists" ||
		(len(s) > 44 && containsSubstring(s, "BUSYGROUP Consumer Group name already exists")))
}

func containsSubstring(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}

// NewClient creates a new Redis client from the given configuration.
// It creates a single-node redis.Client (suitable for local dev and
// single-node Redis). For Redis Cluster, replace with NewClusterClient.
func NewClient(cfg RedisConfig) (*Client, error) {
	readTimeout, err := parseDuration(cfg.ReadTimeout)
	if err != nil {
		return nil, fmt.Errorf("invalid read_timeout: %w", err)
	}
	writeTimeout, err := parseDuration(cfg.WriteTimeout)
	if err != nil {
		return nil, fmt.Errorf("invalid write_timeout: %w", err)
	}

	if len(cfg.Addrs) == 0 {
		return nil, fmt.Errorf("no Redis addresses provided")
	}

	// For single-node: use the first address.
	// For multi-node (sentinel/cluster): use NewFailoverClient or NewClusterClient.
	addr := cfg.Addrs[0]
	if len(cfg.Addrs) > 1 {
		// Multiple addresses given — shuffle and pick a random one for dev.
		// In production, use cluster or sentinel client.
		r := rand.New(rand.NewSource(time.Now().UnixNano())) //nolint:gosec
		addr = cfg.Addrs[r.Intn(len(cfg.Addrs))]
	}

	poolSize := cfg.PoolSize
	if poolSize == 0 {
		poolSize = 100
	}
	minIdle := cfg.MinIdleConns
	if minIdle == 0 {
		minIdle = 20
	}

	rdb := redis.NewClient(&redis.Options{
		Addr:         addr,
		Password:     cfg.Password,
		DB:           cfg.DB,
		PoolSize:     poolSize,
		MinIdleConns: minIdle,
		ReadTimeout:  readTimeout,
		WriteTimeout: writeTimeout,
	})

	return &Client{rdb: rdb}, nil
}

// NewClusterClient creates a Redis Cluster client.
// Use this in production when Redis is deployed in cluster mode.
func NewClusterClient(cfg RedisConfig) (*Client, error) {
	if len(cfg.Addrs) == 0 {
		return nil, fmt.Errorf("no Redis addresses provided")
	}

	readTimeout, err := parseDuration(cfg.ReadTimeout)
	if err != nil {
		return nil, fmt.Errorf("invalid read_timeout: %w", err)
	}
	writeTimeout, err := parseDuration(cfg.WriteTimeout)
	if err != nil {
		return nil, fmt.Errorf("invalid write_timeout: %w", err)
	}

	poolSize := cfg.PoolSize
	if poolSize == 0 {
		poolSize = 100
	}

	rdb := redis.NewClusterClient(&redis.ClusterOptions{
		Addrs:        cfg.Addrs,
		Password:     cfg.Password,
		PoolSize:     poolSize,
		ReadTimeout:  readTimeout,
		WriteTimeout: writeTimeout,
	})

	return &Client{rdb: rdb}, nil
}

// Ping checks connectivity to Redis by executing PING.
// Returns nil if Redis is reachable.
func (c *Client) Ping(ctx context.Context) error {
	return c.rdb.Ping(ctx).Err()
}

// Close closes the underlying Redis connection.
func (c *Client) Close() error {
	return c.rdb.Close()
}

// Raw returns the underlying go-redis client for advanced operations
// that are not wrapped by this package.
func (c *Client) Raw() redis.UniversalClient {
	return c.rdb
}

// Pipeline returns a new pipeline for batching commands.
func (c *Client) Pipeline() redis.Pipeliner {
	return c.rdb.Pipeline()
}

// HealthCheck performs a health check and returns status information.
func (c *Client) HealthCheck(ctx context.Context) HealthStatus {
	start := time.Now()
	err := c.Ping(ctx)
	latency := time.Since(start)

	status := HealthStatus{
		CheckedAt: time.Now(),
		LatencyMs: latency.Milliseconds(),
	}
	if err != nil {
		status.Healthy = false
		status.Error = err.Error()
	} else {
		status.Healthy = true
	}
	return status
}

// HealthStatus holds the result of a Redis health check.
type HealthStatus struct {
	Healthy   bool      `json:"healthy"`
	LatencyMs int64     `json:"latency_ms"`
	Error     string    `json:"error,omitempty"`
	CheckedAt time.Time `json:"checked_at"`
}

// HealthCheckShard performs a health check on a specific shard.
// Uses the ShardManager to determine which address to ping.
func (c *Client) HealthCheckShard(ctx context.Context, sm *ShardManager, userID string) HealthStatus {
	// For single-node: same as regular health check
	// For cluster: would need to resolve which node owns the slot
	return c.HealthCheck(ctx)
}

// parseDuration parses a duration string (e.g., "3s") into time.Duration.
func parseDuration(s string) (time.Duration, error) {
	if s == "" {
		return 3 * time.Second, nil // default
	}
	return time.ParseDuration(s)
}

// RingManager manages a ring of Redis clients for sharded operations.
// Use this when you have multiple Redis nodes that don't form a cluster
// (e.g., Redis with hash tags routing).
// In practice, if you use Redis Cluster with hash tags, use NewClusterClient.
// This is provided for completeness.
type RingManager struct {
	clients []*Client
	sm      *ShardManager
	mu      sync.RWMutex
}

// NewRingManager creates a RingManager from a list of addresses.
// Each address gets its own redis.Ring client.
func NewRingManager(addrs []string, cfg RedisConfig) (*RingManager, error) {
	if len(addrs) == 0 {
		return nil, fmt.Errorf("no addresses provided")
	}

	clients := make([]*Client, 0, len(addrs))
	for _, addr := range addrs {
		// Override addrs to single address for each ring node
		singleCfg := cfg
		singleCfg.Addrs = []string{addr}
		c, err := NewClient(singleCfg)
		if err != nil {
			// Close already-created clients
			for _, c := range clients {
				_ = c.Close()
			}
			return nil, fmt.Errorf("failed to create client for %s: %w", addr, err)
		}
		clients = append(clients, c)
	}

	return &RingManager{
		clients: clients,
		sm:      NewShardManager(len(clients)),
	}, nil
}

// ClientForUser returns the Redis client for a given user.
// Uses ShardManager to determine which shard the user belongs to.
func (rm *RingManager) ClientForUser(userID string) *Client {
	shard := rm.sm.ShardForUser(userID)
	rm.mu.RLock()
	defer rm.mu.RUnlock()
	if int(shard) < len(rm.clients) {
		return rm.clients[shard]
	}
	return rm.clients[0]
}

// Close closes all Redis clients in the ring.
func (rm *RingManager) Close() error {
	rm.mu.Lock()
	defer rm.mu.Unlock()
	var lastErr error
	for _, c := range rm.clients {
		if err := c.Close(); err != nil {
			lastErr = err
		}
	}
	return lastErr
}
