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

// Production does not demand certificates any more: the mesh terminates mTLS and
// the application only keeps an allowlist.
func TestProductionDoesNotRequireInternalCertificates(t *testing.T) {
	cfg, err := load(t, map[string]string{"AUTH_APP_ENV": "production"})
	require.NoError(t, err)
	require.Equal(t, []string{"notification-service"}, cfg.Internal.AllowedCallers)
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
