package config

import (
	"testing"

	goredis "github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const shippedConfig = "../../../configs/application.yaml"

func TestLoad_ShippedConfigIsValid(t *testing.T) {
	cfg, err := Load(shippedConfig)
	require.NoError(t, err)
	assert.Equal(t, []string{"RS256"}, cfg.JWT.AllowedAlgorithms)
	assert.Equal(t, "auth:revocation", cfg.Redis.RevocationPrefix)
}

func TestLoad_ContractEnvironmentOverrides(t *testing.T) {
	t.Setenv("AUTH_JWT_ISSUER", "https://issuer.test")
	t.Setenv("AUTH_JWT_AUDIENCE", "aud-test")
	t.Setenv("AUTH_JWT_ALLOWED_ALGORITHMS", "RS256, RS256")
	t.Setenv("AUTH_JWKS_URL", "http://auth.test/.well-known/jwks.json")
	t.Setenv("AUTH_REVOCATION_REDIS_URL", "redis://cache.test:6379/2")
	t.Setenv("GATEWAY_AUTH_SERVER_PORT", "9090")

	cfg, err := Load(shippedConfig)
	require.NoError(t, err)
	assert.Equal(t, "https://issuer.test", cfg.JWT.Issuer)
	assert.Equal(t, "aud-test", cfg.JWT.Audience)
	assert.Equal(t, []string{"RS256", "RS256"}, cfg.JWT.AllowedAlgorithms)
	assert.Equal(t, "http://auth.test/.well-known/jwks.json", cfg.JWKS.URL)
	assert.Equal(t, "redis://cache.test:6379/2", cfg.Redis.URL)
	assert.Equal(t, 9090, cfg.Server.Port)
}

// The shared Redis requires a password (mesh ADR-002); docker-compose and
// deploy/k8s pass it inside AUTH_REVOCATION_REDIS_URL, which main parses.
func TestLoad_RevocationURLCarriesTheRedisPassword(t *testing.T) {
	t.Setenv("AUTH_REVOCATION_REDIS_URL", "redis://:redis-secret-from-env@redis:6379/0")

	cfg, err := Load(shippedConfig)
	require.NoError(t, err)
	options, err := goredis.ParseURL(cfg.Redis.URL)
	require.NoError(t, err)
	assert.Equal(t, "redis-secret-from-env", options.Password)
	assert.Equal(t, "redis:6379", options.Addr)
}

func TestLoad_RefusesWeakAlgorithms(t *testing.T) {
	for _, algs := range []string{"none", "HS256", "RS256,none"} {
		t.Setenv("AUTH_JWT_ALLOWED_ALGORITHMS", algs)
		_, err := Load(shippedConfig)
		assert.Error(t, err, algs)
	}
}
