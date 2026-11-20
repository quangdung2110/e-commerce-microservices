// Package config_test covers the configuration rules that guard the internal
// listener (ADR-007).
package config_test

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/common/config"
)

// A 32-byte key, base64 encoded; only its length matters to the validator.
const testMasterKey = "ZGV2ZWxvcG1lbnQtb25seS0zMi1ieXRlLW1hc3RlciE="

// load reads the defaults plus a minimal file that supplies the only secret the
// defaults cannot (the master key is expanded from the environment in the real
// application.yaml), then applies the AUTH_ overrides in env.
func load(t *testing.T, env map[string]string) (*config.Config, error) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "application.yaml")
	require.NoError(t, os.WriteFile(path, []byte("keys:\n  master_key: \""+testMasterKey+"\"\n"), 0o600))
	for key, value := range env {
		t.Setenv(key, value)
	}
	return config.Load(path)
}

// Random-looking values a secret manager would supply: base64 of 32 bytes, and a
// signing key of 48 characters.
const (
	productionMasterKey  = "q3Jx9n2Lw8pZ5tV1cR7mK4bY6hD0sF3gA2eU8iO1lPk="
	productionSigningKey = "Zr8qT2wX6vN1mB4kL9pJ3hG7fD5sA0cE8uY2iO6tR1nQ4wVb"
)

func production(extra map[string]string) map[string]string {
	env := map[string]string{
		"AUTH_APP_ENV":              "production",
		"AUTH_KEYS_MASTER_KEY":      productionMasterKey,
		"AUTH_INTERNAL_SIGNING_KEY": productionSigningKey,
	}
	for key, value := range extra {
		env[key] = value
	}
	return env
}

// Production does not demand certificates any more: the mesh terminates mTLS and
// the application only keeps an allowlist plus the request signature.
func TestProductionDoesNotRequireInternalCertificates(t *testing.T) {
	cfg, err := load(t, production(nil))
	require.NoError(t, err)
	require.Equal(t, []string{"notification-service"}, cfg.Internal.AllowedCallers)
	require.Equal(t, productionSigningKey, cfg.Internal.SigningKey)
}

// Security review 2026-10-22, H1: outside development a missing, short,
// committed or placeholder secret stops the service at startup.
func TestOutsideDevelopmentWeakSecretsFailStartup(t *testing.T) {
	cases := map[string]struct {
		env  map[string]string
		want string
	}{
		"master key committed in docker-compose.yml": {
			map[string]string{"AUTH_KEYS_MASTER_KEY": "CU5K/J8X8Ys5MfXuFncgDtQ2YOSgDTnDqcQzj+IBpj0="},
			"keys.master_key (AUTH_KEYS_MASTER_KEY) is the development value committed to the repository",
		},
		"master key hiding a placeholder phrase": {
			map[string]string{"AUTH_KEYS_MASTER_KEY": testMasterKey}, // "development-only-32-byte-master!"
			"keys.master_key (AUTH_KEYS_MASTER_KEY) looks like a placeholder",
		},
		"missing signing key": {
			map[string]string{"AUTH_INTERNAL_SIGNING_KEY": ""},
			"internal.signing_key (AUTH_INTERNAL_SIGNING_KEY) is required outside development",
		},
		"short signing key": {
			map[string]string{"AUTH_INTERNAL_SIGNING_KEY": "Zr8qT2wX6vN1mB4k"},
			"internal.signing_key (AUTH_INTERNAL_SIGNING_KEY) must be at least 32 bytes",
		},
		"placeholder signing key": {
			map[string]string{"AUTH_INTERNAL_SIGNING_KEY": "please-change_me-before-going-live-0123456789"},
			"looks like a placeholder",
		},
		"committed development signing key": {
			map[string]string{"AUTH_INTERNAL_SIGNING_KEY": "local-dev-auth-internal-signing-key-not-a-secret"},
			"is the development value committed to the repository",
		},
		"staging is not development": {
			map[string]string{"AUTH_APP_ENV": "staging", "AUTH_INTERNAL_SIGNING_KEY": ""},
			"internal.signing_key (AUTH_INTERNAL_SIGNING_KEY) is required outside development",
		},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			_, err := load(t, production(tc.env))
			require.ErrorContains(t, err, tc.want)
		})
	}
}

// Development keeps the local stack running with the committed values and
// without a signing key.
func TestDevelopmentAcceptsDevelopmentSecrets(t *testing.T) {
	cfg, err := load(t, map[string]string{
		"AUTH_APP_ENV":         "development",
		"AUTH_KEYS_MASTER_KEY": "CU5K/J8X8Ys5MfXuFncgDtQ2YOSgDTnDqcQzj+IBpj0=",
	})
	require.NoError(t, err)
	require.Empty(t, cfg.Internal.SigningKey)
}

// H2: Auth reads the client from X-Real-IP set by a trusted gateway; the list
// comes from the environment (docker-compose.yml, deploy/k8s).
func TestTrustedProxiesComeFromTheEnvironment(t *testing.T) {
	cfg, err := load(t, map[string]string{"AUTH_SECURITY_TRUSTED_PROXIES": "10.89.3.2/32"})
	require.NoError(t, err)
	require.Equal(t, []string{"10.89.3.2/32"}, cfg.Security.TrustedProxies)
	require.Equal(t, []string{"X-Real-IP"}, cfg.Security.ClientIPHeaders)
}

func TestInternalCallersCanBeOverriddenFromTheEnvironment(t *testing.T) {
	cfg, err := load(t, map[string]string{"AUTH_INTERNAL_ALLOWED_CALLERS": "notification-service,ops-tool"})
	require.NoError(t, err)
	require.Equal(t, []string{"notification-service", "ops-tool"}, cfg.Internal.AllowedCallers)
}

// The internal API returns recipient addresses and secrets, so an enabled
// listener with an empty allowlist is a misconfiguration, not a closed mode.
func TestAnEnabledInternalListenerNeedsAnAllowlist(t *testing.T) {
	_, err := load(t, map[string]string{"AUTH_INTERNAL_ALLOWED_CALLERS": " "})
	require.ErrorContains(t, err, "internal.allowed_callers")
}

func TestADisabledInternalListenerNeedsNoAllowlist(t *testing.T) {
	_, err := load(t, map[string]string{
		"AUTH_INTERNAL_ENABLED":         "false",
		"AUTH_INTERNAL_ALLOWED_CALLERS": " ",
	})
	require.NoError(t, err)
}

func TestInternalAndPublicListenersCannotShareAPort(t *testing.T) {
	_, err := load(t, map[string]string{"AUTH_INTERNAL_PORT": "8080"})
	require.ErrorContains(t, err, "internal.port must differ from app.port")
}
