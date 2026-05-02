package jwks

import (
	"context"
	"crypto/rsa"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
)

// ServiceInterface defines the interface for JWKS operations
type ServiceInterface interface {
	// GetActivePrivateKey returns the active private key for signing tokens
	GetActivePrivateKey(ctx context.Context) (*rsa.PrivateKey, string, error)

	// GetPublicKeys returns all active public keys for token verification
	GetPublicKeys(ctx context.Context) ([]*entity.JWK, error)

	// GetJWKS returns the JSON Web Key Set
	GetJWKS(ctx context.Context) (*entity.JWKS, error)

	// GetKeyByKid retrieves a key by its ID
	GetKeyByKid(ctx context.Context, kid string) (*entity.JwtKey, error)

	// InvalidateCache invalidates the in-memory key cache
	InvalidateCache()
}

// Ensure Service implements ServiceInterface
var _ ServiceInterface = (*Service)(nil)
