// Package service implements access-token verification for gateway-auth.
package service

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"

	"github.com/ecommerce/gateway-auth/internal/domain"
	"github.com/ecommerce/gateway-auth/internal/domain/port"
)

// Identity is what a verified access token establishes. It maps one-to-one to
// the four identity headers of runtime-contract section 6.1.
type Identity struct {
	Subject   string   // sub → X-User-Id
	Roles     []string // roles → X-User-Roles
	SessionID string   // sid → X-User-Session-Id
	TokenID   string   // jti → X-Token-Jti
}

// VerifierConfig is the part of the auth contract the verifier enforces.
type VerifierConfig struct {
	Issuer            string
	Audience          string
	AllowedAlgorithms []string
	Leeway            time.Duration
}

// contractClaims is the exact access-token claim set (runtime-contract section 6):
// a token carrying anything else was not minted under this contract.
var contractClaims = map[string]bool{
	"iss": true, "aud": true, "sub": true, "jti": true, "sid": true,
	"roles": true, "iat": true, "nbf": true, "exp": true,
}

// Verifier validates Bearer access tokens.
type Verifier struct {
	keys        port.KeySource
	revocations port.RevocationStore
	parser      *jwt.Parser
}

// NewVerifier builds a verifier. now is injectable for tests.
func NewVerifier(cfg VerifierConfig, keys port.KeySource, revocations port.RevocationStore, now func() time.Time) *Verifier {
	if now == nil {
		now = time.Now
	}
	parser := jwt.NewParser(
		// Algorithm comes from configuration, never from the token header alone.
		jwt.WithValidMethods(cfg.AllowedAlgorithms),
		jwt.WithIssuer(cfg.Issuer),
		jwt.WithAudience(cfg.Audience),
		jwt.WithExpirationRequired(),
		jwt.WithIssuedAt(),
		jwt.WithLeeway(cfg.Leeway),
		jwt.WithTimeFunc(now),
	)
	return &Verifier{keys: keys, revocations: revocations, parser: parser}
}

// Verify checks the Authorization header value and returns the identity.
func (v *Verifier) Verify(ctx context.Context, authorization string) (Identity, error) {
	raw, err := bearerToken(authorization)
	if err != nil {
		return Identity{}, err
	}

	claims := jwt.MapClaims{}
	_, err = v.parser.ParseWithClaims(raw, claims, func(token *jwt.Token) (any, error) {
		kid, _ := token.Header["kid"].(string)
		if kid == "" {
			return nil, fmt.Errorf("%w: missing kid", domain.ErrInvalidToken)
		}
		key, err := v.keys.PublicKey(ctx, kid)
		if errors.Is(err, domain.ErrUnknownKey) {
			return nil, fmt.Errorf("%w: %v", domain.ErrInvalidToken, err)
		}
		return key, err
	})
	if err != nil {
		// A dependency failure while resolving the key must not look like a
		// bad token: the client did nothing wrong and must not be logged out.
		if errors.Is(err, domain.ErrDependencyUnavailable) {
			return Identity{}, err
		}
		return Identity{}, fmt.Errorf("%w: %v", domain.ErrInvalidToken, err)
	}

	identity, issuedAt, err := identityFrom(claims)
	if err != nil {
		return Identity{}, err
	}

	revoked, err := v.revocations.IsRevoked(ctx, identity.Subject, identity.TokenID, issuedAt)
	if err != nil {
		return Identity{}, err
	}
	if revoked {
		return Identity{}, domain.ErrRevokedToken
	}
	return identity, nil
}

func bearerToken(authorization string) (string, error) {
	scheme, token, ok := strings.Cut(strings.TrimSpace(authorization), " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") {
		return "", domain.ErrMissingCredentials
	}
	token = strings.TrimSpace(token)
	if token == "" {
		return "", domain.ErrMissingCredentials
	}
	return token, nil
}

// identityFrom enforces the claim set the parser does not: exact membership,
// presence of every claim and the shape of the custom ones.
func identityFrom(claims jwt.MapClaims) (Identity, time.Time, error) {
	invalid := func(reason string) (Identity, time.Time, error) {
		return Identity{}, time.Time{}, fmt.Errorf("%w: %s", domain.ErrInvalidToken, reason)
	}

	for name := range claims {
		if !contractClaims[name] {
			return invalid("unexpected claim " + name)
		}
	}
	for name := range contractClaims {
		if _, ok := claims[name]; !ok {
			return invalid("missing claim " + name)
		}
	}

	subject, _ := claims["sub"].(string)
	if _, err := uuid.Parse(subject); err != nil {
		return invalid("sub is not a UUID")
	}
	jti, _ := claims["jti"].(string)
	if strings.TrimSpace(jti) == "" {
		return invalid("jti is empty")
	}
	sid, _ := claims["sid"].(string)
	if _, err := uuid.Parse(sid); err != nil {
		return invalid("sid is not a UUID")
	}

	rawRoles, ok := claims["roles"].([]any)
	if !ok {
		return invalid("roles is not an array")
	}
	roles := make([]string, 0, len(rawRoles))
	for _, raw := range rawRoles {
		role, ok := raw.(string)
		// A comma inside a role would split into two roles in X-User-Roles.
		if !ok || role == "" || strings.ContainsAny(role, ", ") {
			return invalid("roles contains an invalid entry")
		}
		roles = append(roles, role)
	}

	issuedAt, err := claims.GetIssuedAt()
	if err != nil || issuedAt == nil {
		return invalid("iat is not a number")
	}

	return Identity{Subject: subject, Roles: roles, SessionID: sid, TokenID: jti}, issuedAt.Time, nil
}
