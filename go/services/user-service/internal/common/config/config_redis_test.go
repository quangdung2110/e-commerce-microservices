package config

import "testing"

// The shared Redis requires a password (mesh ADR-002): configs/application.yaml
// reads it from REDIS_PASSWORD, which docker-compose and deploy/k8s set.
func TestShippedApplicationYAMLReadsTheRedisPassword(t *testing.T) {
	t.Setenv("REDIS_PASSWORD", "redis-secret-from-env")
	t.Setenv("DB_PASSWORD", "postgres")

	cfg, err := Load("../../../configs/application.yaml")
	if err != nil {
		t.Fatalf("configs/application.yaml: %v", err)
	}
	if cfg.Redis.Password != "redis-secret-from-env" {
		t.Fatalf("redis.password = %q, want the REDIS_PASSWORD value", cfg.Redis.Password)
	}
}
