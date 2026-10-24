package config

import (
	"encoding/base64"
	"fmt"
	"strings"
)

// Secrets committed to the repository for local development (docker-compose.yml,
// deploy/k8s, this package). Outside development a deployment that still
// carries one of them refuses to start: everyone with the repository holds them.
var committedDevelopmentSecrets = []string{
	devHMACKey,
	// docker-compose.yml x-internal-signing-key (Auth internal API, mesh ADR-004)
	"local-dev-auth-internal-signing-key-not-a-secret",
}

// placeholderMarkers are fragments of the values a template or a .env example
// ships with. A real secret is random and never contains them. The list is the
// same as Auth's (go/services/auth-service/internal/common/config/secrets.go).
var placeholderMarkers = []string{
	"change_me", "change-me", "changeme", "change-in-prod", "change_in_prod",
	"local", "dev-", "dev_", "development", "example", "placeholder", "not-a-secret",
}

// secretProblem reports why value cannot be a secret outside development: empty,
// shorter than minBytes, a committed development value or a placeholder. A
// base64 value is checked again after decoding. It returns "" when acceptable.
func secretProblem(name, value string, minBytes int) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return name + " is required outside development"
	}
	for _, known := range committedDevelopmentSecrets {
		if value == known {
			return name + " is the development value committed to the repository"
		}
	}
	candidates := []string{value}
	if decoded, err := base64.StdEncoding.DecodeString(value); err == nil {
		candidates = append(candidates, string(decoded))
	}
	for _, candidate := range candidates {
		lower := strings.ToLower(candidate)
		for _, marker := range placeholderMarkers {
			if strings.Contains(lower, marker) {
				return fmt.Sprintf("%s looks like a placeholder (contains %q)", name, marker)
			}
		}
	}
	if len(value) < minBytes {
		return fmt.Sprintf("%s must be at least %d bytes", name, minBytes)
	}
	return ""
}
