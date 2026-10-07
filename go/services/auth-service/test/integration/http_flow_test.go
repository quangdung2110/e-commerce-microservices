//go:build integration

package integration_test

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

const (
	flowEmail    = "Flow.Owner@Example.com"
	flowPassword = "correct horse battery"
	flowNewPass  = "another correct horse"
)

// accessTokenResponse mirrors the AccessTokenResponse schema of the contract.
type accessTokenResponse struct {
	AccessToken string `json:"access_token"`
	TokenType   string `json:"token_type"`
	ExpiresIn   int    `json:"expires_in"`
	SessionID   string `json:"session_id"`
}

// register performs WF-AUTH-001 up to an ACTIVE account and returns its id.
//
// Deliveries of earlier steps are cleared first, so reading the code back is
// unambiguous when a test registers more than once.
func (a *testApp) register(t *testing.T, address, password string) uuid.UUID {
	t.Helper()
	a.clearDeliveries(t)

	created := a.post(t, "/api/v1/auth/registrations", map[string]any{
		"email": address, "password": password, "confirm_password": password,
	}, nil)
	require.Equal(t, http.StatusAccepted, created.Status, created.Body)

	code := a.codeFor(t, constants.TemplateRegistrationVerification)
	verified := a.post(t, "/api/v1/auth/email-verifications", map[string]any{
		"email": address, "otp": code,
	}, nil)
	require.Equal(t, http.StatusNoContent, verified.Status, verified.Body)

	return a.accountID(t, strings.ToLower(address))
}

// login performs WF-AUTH-002 and returns the token response.
func (a *testApp) login(t *testing.T, address, password string) accessTokenResponse {
	t.Helper()

	resp := a.post(t, "/api/v1/auth/sessions", map[string]any{
		"email": address, "password": password, "device_name": "Laptop",
	}, nil)
	require.Equal(t, http.StatusOK, resp.Status, resp.Body)

	var body accessTokenResponse
	resp.JSON(t, &body)
	return body
}

// identityOf builds the headers the gateway injects after verifying a token.
func identityOf(accountID uuid.UUID, roles ...vo.Role) map[string]string {
	names := make([]string, 0, len(roles))
	for _, role := range roles {
		names = append(names, role.String())
	}
	if len(names) == 0 {
		names = append(names, vo.RoleUser.String())
	}
	return map[string]string{
		"X-User-Id":    accountID.String(),
		"X-User-Roles": strings.Join(names, ","),
	}
}

// codeFor reads a one-time code out of the delivery state, which is where the
// plaintext lives until Notification resolves it.
func (a *testApp) codeFor(t *testing.T, template string) string {
	t.Helper()
	data := a.deliveryData(t, template)
	code, ok := data["otp"]
	require.True(t, ok, "the %s delivery carries no code", template)
	require.Len(t, code, constants.OtpLength)
	return code
}

// resetLinkToken extracts the opaque token from the reset URL fragment.
func (a *testApp) resetLinkToken(t *testing.T) string {
	t.Helper()

	data := a.deliveryData(t, constants.TemplatePasswordReset)
	link, ok := data["reset_url"]
	require.True(t, ok, "the reset delivery carries no link")
	require.True(t, strings.HasPrefix(link, "https://app.test/reset-password#token="),
		"the token travels in the fragment so it never reaches a server log")

	index := strings.Index(link, "#token=")
	return link[index+len("#token="):]
}

// clearDeliveries drops the delivery state of previous steps.
func (a *testApp) clearDeliveries(t *testing.T) {
	t.Helper()
	ctx := context.Background()

	keys, err := testRedis.Keys(ctx, "auth:delivery:*").Result()
	require.NoError(t, err)
	if len(keys) == 0 {
		return
	}
	require.NoError(t, testRedis.Del(ctx, keys...).Err())
}

// deliveryData returns the render data of a delivery of the given template.
func (a *testApp) deliveryData(t *testing.T, template string) map[string]string {
	t.Helper()
	ctx := context.Background()

	keys, err := testRedis.Keys(ctx, "auth:delivery:*").Result()
	require.NoError(t, err)
	require.NotEmpty(t, keys, "no notification delivery was stored")

	for _, key := range keys {
		raw, err := testRedis.Get(ctx, key).Bytes()
		require.NoError(t, err)

		var record struct {
			Template     string            `json:"template"`
			TemplateData map[string]string `json:"template_data"`
			Recipient    string            `json:"recipient_email"`
		}
		require.NoError(t, json.Unmarshal(raw, &record))
		if record.Template == template {
			return record.TemplateData
		}
	}
	t.Fatalf("no %s delivery was stored", template)
	return nil
}

// deliveryRecipient reports which address a notice was addressed to.
func (a *testApp) deliveryRecipient(t *testing.T, template string) string {
	t.Helper()
	ctx := context.Background()

	keys, err := testRedis.Keys(ctx, "auth:delivery:*").Result()
	require.NoError(t, err)

	for _, key := range keys {
		raw, err := testRedis.Get(ctx, key).Bytes()
		require.NoError(t, err)

		var record struct {
			Template  string `json:"template"`
			Recipient string `json:"recipient_email"`
		}
		require.NoError(t, json.Unmarshal(raw, &record))
		if record.Template == template {
			return record.Recipient
		}
	}
	t.Fatalf("no %s delivery was stored", template)
	return ""
}

func (a *testApp) accountID(t *testing.T, normalizedEmail string) uuid.UUID {
	t.Helper()

	var raw string
	require.NoError(t, testPool.QueryRow(context.Background(),
		"SELECT id::text FROM auth.accounts WHERE email_normalized = $1", normalizedEmail).Scan(&raw))

	id, err := uuid.Parse(raw)
	require.NoError(t, err)
	return id
}

func denylisted(t *testing.T, jti string) bool {
	t.Helper()
	return testRedis.Exists(context.Background(), "auth:revocation:denylist:"+jti).Val() == 1
}

func revocationMarker(t *testing.T, accountID uuid.UUID) string {
	t.Helper()
	return testRedis.Get(context.Background(),
		"auth:revocation:user:"+accountID.String()+":revoked_before").Val()
}

// WF-AUTH-001: registration is accepted, the code is delivered out of band, and
// the account only becomes usable after verification.
func TestHTTPRegistrationAndVerification(t *testing.T) {
	app := newTestApp(t)

	created := app.post(t, "/api/v1/auth/registrations", map[string]any{
		"email": flowEmail, "password": flowPassword, "confirm_password": flowPassword,
	}, nil)
	require.Equal(t, http.StatusAccepted, created.Status)
	require.Contains(t, created.Body, vo.AccountPendingVerification.String())

	// The code reaches the delivery state, never the response.
	require.NotContains(t, created.Body, "otp")
	require.Equal(t, flowEmail, app.deliveryRecipient(t, constants.TemplateRegistrationVerification))

	// A pending account cannot sign in yet.
	pending := app.post(t, "/api/v1/auth/sessions", map[string]any{
		"email": flowEmail, "password": flowPassword,
	}, nil)
	require.Equal(t, http.StatusForbidden, pending.Status)
	require.Equal(t, apperr.CodeAccountNotVerified, pending.Code(t))

	// A wrong code is rejected without revealing anything.
	wrong := app.post(t, "/api/v1/auth/email-verifications", map[string]any{
		"email": flowEmail, "otp": "000000",
	}, nil)
	require.Equal(t, http.StatusUnprocessableEntity, wrong.Status)
	require.Equal(t, apperr.CodeOtpInvalid, wrong.Code(t))

	code := app.codeFor(t, constants.TemplateRegistrationVerification)
	verified := app.post(t, "/api/v1/auth/email-verifications", map[string]any{
		"email": flowEmail, "otp": code,
	}, nil)
	require.Equal(t, http.StatusNoContent, verified.Status)

	// Verifying twice is a conflict, so no second identity event is emitted.
	replay := app.post(t, "/api/v1/auth/email-verifications", map[string]any{
		"email": flowEmail, "otp": code,
	}, nil)
	require.Equal(t, http.StatusConflict, replay.Status)
}

func TestHTTPRegistrationValidation(t *testing.T) {
	app := newTestApp(t)

	cases := []struct {
		name    string
		payload map[string]any
		status  int
		code    string
	}{
		{
			name:    "unknown field",
			payload: map[string]any{"email": flowEmail, "password": flowPassword, "confirm_password": flowPassword, "roles": []string{"ADMIN"}},
			status:  http.StatusBadRequest,
			code:    apperr.CodeValidationFailed,
		},
		{
			name:    "confirmation mismatch",
			payload: map[string]any{"email": flowEmail, "password": flowPassword, "confirm_password": flowPassword + "!"},
			status:  http.StatusBadRequest,
			code:    apperr.CodeValidationFailed,
		},
		{
			name:    "password too short",
			payload: map[string]any{"email": flowEmail, "password": "short", "confirm_password": "short"},
			status:  http.StatusBadRequest,
			code:    apperr.CodeValidationFailed,
		},
		{
			name:    "malformed email",
			payload: map[string]any{"email": "not-an-email", "password": flowPassword, "confirm_password": flowPassword},
			status:  http.StatusBadRequest,
			code:    apperr.CodeValidationFailed,
		},
	}

	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			resp := app.post(t, "/api/v1/auth/registrations", testCase.payload, nil)
			require.Equal(t, testCase.status, resp.Status, resp.Body)
			require.Equal(t, testCase.code, resp.Code(t))
			require.Equal(t, "application/problem+json", resp.Headers.Get("Content-Type"))
		})
	}

	// The same identity in another case is still a duplicate.
	app.register(t, flowEmail, flowPassword)
	duplicate := app.post(t, "/api/v1/auth/registrations", map[string]any{
		"email": strings.ToLower(flowEmail), "password": flowPassword, "confirm_password": flowPassword,
	}, nil)
	require.Equal(t, http.StatusConflict, duplicate.Status)
	require.Equal(t, apperr.CodeEmailAlreadyRegistered, duplicate.Code(t))
}

// WF-AUTH-002 and WF-AUTH-003 over HTTP, including the cookie contract.
func TestHTTPLoginAndRefresh(t *testing.T) {
	app := newTestApp(t)
	accountID := app.register(t, flowEmail, flowPassword)

	resp := app.post(t, "/api/v1/auth/sessions", map[string]any{
		"email": flowEmail, "password": flowPassword, "device_name": "Laptop",
	}, nil)
	require.Equal(t, http.StatusOK, resp.Status)
	require.Equal(t, constants.NoStore, resp.Headers.Get("Cache-Control"))

	var tokens accessTokenResponse
	resp.JSON(t, &tokens)
	require.NotEmpty(t, tokens.AccessToken)
	require.Equal(t, "Bearer", tokens.TokenType)
	require.Equal(t, 900, tokens.ExpiresIn)
	require.NotEmpty(t, tokens.SessionID)

	// The refresh token exists only in the cookie, with the required attributes.
	setCookie := resp.Headers.Get("Set-Cookie")
	require.Contains(t, setCookie, "auth_refresh=")
	require.Contains(t, setCookie, "HttpOnly")
	require.Contains(t, setCookie, "SameSite=Strict")
	require.Contains(t, setCookie, "Path=/api/v1/auth/sessions")
	require.NotContains(t, resp.Body, "refresh_token")
	firstCookie := strings.SplitN(strings.Split(setCookie, ";")[0], "=", 2)[1]

	// Rotation returns a new pair inside the same session.
	refreshed := app.post(t, "/api/v1/auth/sessions/refresh", nil, nil)
	require.Equal(t, http.StatusOK, refreshed.Status, refreshed.Body)

	var rotated accessTokenResponse
	refreshed.JSON(t, &rotated)
	require.Equal(t, tokens.SessionID, rotated.SessionID)
	require.NotEqual(t, tokens.AccessToken, rotated.AccessToken)

	// ADR-002: the consumed cookie is dead, and the failure clears it.
	replay := app.postWithoutJar(t, "/api/v1/auth/sessions/refresh", "auth_refresh="+firstCookie)
	require.Equal(t, http.StatusUnauthorized, replay.Status)
	require.Equal(t, apperr.CodeRefreshTokenInvalid, replay.Code(t))
	require.Contains(t, replay.Headers.Get("Set-Cookie"), "auth_refresh=;")

	// A request without any cookie is refused the same way.
	missing := app.postWithoutJar(t, "/api/v1/auth/sessions/refresh", "")
	require.Equal(t, http.StatusUnauthorized, missing.Status)

	// The session listing shows one active session and no token material.
	listed := app.get(t, "/api/v1/auth/sessions", identityOf(accountID))
	require.Equal(t, http.StatusOK, listed.Status)
	require.Contains(t, listed.Body, tokens.SessionID)
	require.Contains(t, listed.Body, "Laptop")
	require.NotContains(t, listed.Body, "token")

	// Identity headers are required; the endpoint is not open.
	anonymous := app.get(t, "/api/v1/auth/sessions", nil)
	require.Equal(t, http.StatusUnauthorized, anonymous.Status)
	require.Equal(t, apperr.CodeUnauthorized, anonymous.Code(t))

	// A malformed cursor is a request error rather than a silent first page.
	badCursor := app.get(t, "/api/v1/auth/sessions?cursor=not-a-cursor", identityOf(accountID))
	require.Equal(t, http.StatusBadRequest, badCursor.Status)

	badLimit := app.get(t, "/api/v1/auth/sessions?limit=500", identityOf(accountID))
	require.Equal(t, http.StatusBadRequest, badLimit.Status)
}

// AUTH-BR-004 over HTTP: the threshold locks the account and the response tells
// the client when to come back.
func TestHTTPLoginLockout(t *testing.T) {
	app := newTestApp(t)
	accountID := app.register(t, flowEmail, flowPassword)

	for i := 0; i < 2; i++ {
		failed := app.post(t, "/api/v1/auth/sessions", map[string]any{
			"email": flowEmail, "password": "wrong password here",
		}, nil)
		require.Equal(t, http.StatusUnauthorized, failed.Status)
		require.Equal(t, apperr.CodeInvalidCredentials, failed.Code(t))
	}

	locked := app.post(t, "/api/v1/auth/sessions", map[string]any{
		"email": flowEmail, "password": "wrong password here",
	}, nil)
	require.Equal(t, http.StatusLocked, locked.Status)
	require.Equal(t, apperr.CodeAccountLocked, locked.Code(t))
	require.NotEmpty(t, locked.Headers.Get("Retry-After"))

	// The correct password does not help while the lock holds.
	stillLocked := app.post(t, "/api/v1/auth/sessions", map[string]any{
		"email": flowEmail, "password": flowPassword,
	}, nil)
	require.Equal(t, http.StatusLocked, stillLocked.Status)

	// The worker returns the account to ACTIVE once the window elapsed.
	app.clock.Advance(16 * time.Minute)
	unlocked, err := app.maintenance.UnlockExpired(context.Background(), 100)
	require.NoError(t, err)
	require.Equal(t, 1, unlocked)
	require.Equal(t, flowEmail, app.deliveryRecipient(t, constants.TemplateAccountUnlocked))

	app.login(t, flowEmail, flowPassword)
	require.NotEqual(t, uuid.Nil, accountID)
}

// WF-AUTH-004: revoking one session ends that session only, and the access token
// of that device is denylisted for the gateway.
func TestHTTPSessionRevocation(t *testing.T) {
	app := newTestApp(t)
	accountID := app.register(t, flowEmail, flowPassword)

	// Two devices. The shared jar ends up holding the cookie of the second one,
	// which is the session the caller is "currently" using.
	remote := app.login(t, flowEmail, flowPassword)
	current := app.login(t, flowEmail, flowPassword)
	require.NotEqual(t, remote.SessionID, current.SessionID)

	// Revoking a remote device must not sign the caller out: no cookie of that
	// session is presented, so its access token is not denylisted.
	remoteJti := uuid.NewString()
	remoteHeaders := identityOf(accountID)
	remoteHeaders["X-Token-Jti"] = remoteJti
	revokedRemote := app.deleteWithoutJar(t, "/api/v1/auth/sessions/"+remote.SessionID, remoteHeaders)
	require.Equal(t, http.StatusNoContent, revokedRemote.Status)
	require.False(t, denylisted(t, remoteJti),
		"the caller keeps its own access token when revoking another device")

	// A session that is not the caller's is reported as not found rather than
	// forbidden, so the endpoint cannot probe other accounts.
	foreign := app.delete(t, "/api/v1/auth/sessions/"+uuid.NewString(), remoteHeaders)
	require.Equal(t, http.StatusNotFound, foreign.Status)
	require.Equal(t, apperr.CodeSessionNotFound, foreign.Code(t))

	// Revoking the session the caller is using denylists the forwarded jti and
	// clears the cookie: the refresh cookie identifies the current session.
	currentJti := uuid.NewString()
	currentHeaders := identityOf(accountID)
	currentHeaders["X-Token-Jti"] = currentJti
	revokedCurrent := app.delete(t, "/api/v1/auth/sessions/"+current.SessionID, currentHeaders)
	require.Equal(t, http.StatusNoContent, revokedCurrent.Status)
	require.True(t, denylisted(t, currentJti))
	require.Contains(t, revokedCurrent.Headers.Get("Set-Cookie"), "auth_refresh=;")

	// Revoking twice stays successful.
	again := app.delete(t, "/api/v1/auth/sessions/"+current.SessionID, currentHeaders)
	require.Equal(t, http.StatusNoContent, again.Status)

	// The refresh cookie no longer works.
	dead := app.post(t, "/api/v1/auth/sessions/refresh", nil, nil)
	require.Equal(t, http.StatusUnauthorized, dead.Status)
}

// When the gateway forwards the session id from the sid claim, Auth uses it
// instead of the cookie to recognise the caller's current session.
func TestHTTPRevocationUsesTheForwardedSessionID(t *testing.T) {
	app := newTestApp(t)
	accountID := app.register(t, flowEmail, flowPassword)
	current := app.login(t, flowEmail, flowPassword)

	jti := uuid.NewString()
	headers := identityOf(accountID)
	headers["X-Token-Jti"] = jti
	headers["X-User-Session-Id"] = current.SessionID

	revoked := app.deleteWithoutJar(t, "/api/v1/auth/sessions/"+current.SessionID, headers)
	require.Equal(t, http.StatusNoContent, revoked.Status)
	require.True(t, denylisted(t, jti))
}

func TestHTTPRevokeAllSessions(t *testing.T) {
	app := newTestApp(t)
	accountID := app.register(t, flowEmail, flowPassword)
	app.login(t, flowEmail, flowPassword)

	revoked := app.delete(t, "/api/v1/auth/sessions", identityOf(accountID))
	require.Equal(t, http.StatusNoContent, revoked.Status)
	require.Contains(t, revoked.Headers.Get("Set-Cookie"), "auth_refresh=;")

	// The gateway-wide marker is written so tokens already in flight stop too.
	require.NotEmpty(t, revocationMarker(t, accountID))

	listed := app.get(t, "/api/v1/auth/sessions", identityOf(accountID))
	require.Equal(t, http.StatusOK, listed.Status)
	require.Contains(t, listed.Body, `"sessions":[]`)
}

// WF-AUTH-005 over HTTP.
func TestHTTPPasswordChange(t *testing.T) {
	app := newTestApp(t)
	accountID := app.register(t, flowEmail, flowPassword)
	app.login(t, flowEmail, flowPassword)
	identity := identityOf(accountID)

	wrongCurrent := app.put(t, "/api/v1/auth/password", map[string]any{
		"current_password": "not the password",
		"new_password":     flowNewPass,
		"confirm_password": flowNewPass,
	}, identity)
	require.Equal(t, http.StatusUnauthorized, wrongCurrent.Status)
	require.Equal(t, apperr.CodeInvalidCredentials, wrongCurrent.Code(t))

	reuse := app.put(t, "/api/v1/auth/password", map[string]any{
		"current_password": flowPassword,
		"new_password":     flowPassword,
		"confirm_password": flowPassword,
	}, identity)
	require.Equal(t, http.StatusUnprocessableEntity, reuse.Status)
	require.Equal(t, apperr.CodePasswordPolicy, reuse.Code(t))

	changed := app.put(t, "/api/v1/auth/password", map[string]any{
		"current_password": flowPassword,
		"new_password":     flowNewPass,
		"confirm_password": flowNewPass,
	}, identity)
	require.Equal(t, http.StatusNoContent, changed.Status)
	require.Contains(t, changed.Headers.Get("Set-Cookie"), "auth_refresh=;")

	// AUTH-BR-003: every session is revoked and the owner is notified.
	require.NotEmpty(t, revocationMarker(t, accountID))
	require.Equal(t, flowEmail, app.deliveryRecipient(t, constants.TemplatePasswordChanged))

	dead := app.post(t, "/api/v1/auth/sessions/refresh", nil, nil)
	require.Equal(t, http.StatusUnauthorized, dead.Status)

	// The old credential is gone, the new one works.
	old := app.post(t, "/api/v1/auth/sessions", map[string]any{
		"email": flowEmail, "password": flowPassword,
	}, nil)
	require.Equal(t, http.StatusUnauthorized, old.Status)
	app.login(t, flowEmail, flowNewPass)
}

func TestHTTPPasswordReset(t *testing.T) {
	app := newTestApp(t)
	accountID := app.register(t, flowEmail, flowPassword)
	app.login(t, flowEmail, flowPassword)

	// An unknown address gets the same answer, so the endpoint cannot enumerate.
	unknown := app.post(t, "/api/v1/auth/password-reset-requests", map[string]any{
		"email": "nobody@example.com",
	}, nil)
	require.Equal(t, http.StatusAccepted, unknown.Status)

	requested := app.post(t, "/api/v1/auth/password-reset-requests", map[string]any{
		"email": flowEmail,
	}, nil)
	require.Equal(t, http.StatusAccepted, requested.Status)
	require.Empty(t, requested.Body)

	token := app.resetLinkToken(t)

	// A token of the right shape that was never issued is rejected.
	unknownToken := app.post(t, "/api/v1/auth/password-resets", map[string]any{
		"reset_token":      strings.Repeat("a", 43),
		"new_password":     flowNewPass,
		"confirm_password": flowNewPass,
	}, nil)
	require.Equal(t, http.StatusUnprocessableEntity, unknownToken.Status)
	require.Equal(t, apperr.CodeResetTokenInvalid, unknownToken.Code(t))

	completed := app.post(t, "/api/v1/auth/password-resets", map[string]any{
		"reset_token":      token,
		"new_password":     flowNewPass,
		"confirm_password": flowNewPass,
	}, nil)
	require.Equal(t, http.StatusNoContent, completed.Status)
	require.NotEmpty(t, revocationMarker(t, accountID))

	// AUTH-BR-005: the link is single use.
	reused := app.post(t, "/api/v1/auth/password-resets", map[string]any{
		"reset_token":      token,
		"new_password":     "yet another password",
		"confirm_password": "yet another password",
	}, nil)
	require.Equal(t, http.StatusUnprocessableEntity, reused.Status)

	app.login(t, flowEmail, flowNewPass)
}

// WF-AUTH-007: the identity only moves after the owner proves both the password
// and control of the new mailbox.
func TestHTTPEmailChange(t *testing.T) {
	app := newTestApp(t)
	accountID := app.register(t, flowEmail, flowPassword)
	app.login(t, flowEmail, flowPassword)
	identity := identityOf(accountID)

	const newEmail = "moved.owner@example.com"

	wrongPassword := app.post(t, "/api/v1/auth/email-change-requests", map[string]any{
		"current_password": "not the password", "new_email": newEmail,
	}, identity)
	require.Equal(t, http.StatusUnauthorized, wrongPassword.Status)

	// AUTH-BR-008: the new address must be free system-wide.
	taken := app.post(t, "/api/v1/auth/email-change-requests", map[string]any{
		"current_password": flowPassword, "new_email": flowEmail,
	}, identity)
	require.Equal(t, http.StatusConflict, taken.Status)
	require.Equal(t, apperr.CodeEmailAlreadyRegistered, taken.Code(t))

	requested := app.post(t, "/api/v1/auth/email-change-requests", map[string]any{
		"current_password": flowPassword, "new_email": newEmail,
	}, identity)
	require.Equal(t, http.StatusAccepted, requested.Status, requested.Body)

	// The code goes to the address being claimed, not to the current one.
	require.Equal(t, newEmail, app.deliveryRecipient(t, constants.TemplateEmailChangeVerification))
	code := app.codeFor(t, constants.TemplateEmailChangeVerification)

	// A code issued for one address cannot confirm another.
	wrongAddress := app.post(t, "/api/v1/auth/email-changes/confirmations", map[string]any{
		"new_email": "someone.else@example.com", "otp": code,
	}, identity)
	require.Equal(t, http.StatusUnprocessableEntity, wrongAddress.Status)
	require.Equal(t, apperr.CodeOtpInvalid, wrongAddress.Code(t))

	confirmed := app.post(t, "/api/v1/auth/email-changes/confirmations", map[string]any{
		"new_email": newEmail, "otp": code,
	}, identity)
	require.Equal(t, http.StatusNoContent, confirmed.Status)
	require.Contains(t, confirmed.Headers.Get("Set-Cookie"), "auth_refresh=;")

	// Every session is revoked and the previous address is warned.
	require.NotEmpty(t, revocationMarker(t, accountID))
	require.Equal(t, flowEmail, app.deliveryRecipient(t, constants.TemplateEmailChanged))

	// The old address no longer signs in; the new one does.
	old := app.post(t, "/api/v1/auth/sessions", map[string]any{
		"email": flowEmail, "password": flowPassword,
	}, nil)
	require.Equal(t, http.StatusUnauthorized, old.Status)
	app.login(t, newEmail, flowPassword)
}

// WF-AUTH-006 over HTTP, including the admin path and its authorization.
func TestHTTPAccountDeletion(t *testing.T) {
	app := newTestApp(t)
	accountID := app.register(t, flowEmail, flowPassword)
	app.login(t, flowEmail, flowPassword)
	identity := identityOf(accountID)

	wrongPassword := app.post(t, "/api/v1/auth/accounts/me/deletion-requests", map[string]any{
		"current_password": "not the password",
	}, identity)
	require.Equal(t, http.StatusUnauthorized, wrongPassword.Status)

	deleted := app.post(t, "/api/v1/auth/accounts/me/deletion-requests", map[string]any{
		"current_password": flowPassword,
	}, identity)
	require.Equal(t, http.StatusAccepted, deleted.Status, deleted.Body)
	require.Contains(t, deleted.Headers.Get("Set-Cookie"), "auth_refresh=;")

	var accepted struct {
		Status          string    `json:"status"`
		HardDeleteAfter time.Time `json:"hard_delete_after"`
	}
	deleted.JSON(t, &accepted)
	require.Equal(t, vo.AccountDeleted.String(), accepted.Status)
	require.WithinDuration(t, app.clock.Now().AddDate(0, 0, 90), accepted.HardDeleteAfter, time.Minute)

	// DELETED is terminal.
	again := app.post(t, "/api/v1/auth/accounts/me/deletion-requests", map[string]any{
		"current_password": flowPassword,
	}, identity)
	require.Equal(t, http.StatusConflict, again.Status)

	// The address is free again immediately, and the new account is a new
	// identity rather than a restore.
	reused := app.register(t, flowEmail, flowPassword)
	require.NotEqual(t, accountID, reused)

	// Admin deletion requires the ADMIN role the gateway forwards.
	withoutRole := app.post(t, "/api/v1/auth/admin/accounts/"+reused.String()+"/deletion-requests", nil,
		identityOf(reused))
	require.Equal(t, http.StatusForbidden, withoutRole.Status)
	require.Equal(t, apperr.CodeForbidden, withoutRole.Code(t))

	adminIdentity := identityOf(uuid.New(), vo.RoleUser, vo.RoleAdmin)
	unknownTarget := app.post(t, "/api/v1/auth/admin/accounts/"+uuid.NewString()+"/deletion-requests", nil, adminIdentity)
	require.Equal(t, http.StatusNotFound, unknownTarget.Status)

	adminDeleted := app.post(t, "/api/v1/auth/admin/accounts/"+reused.String()+"/deletion-requests", nil, adminIdentity)
	require.Equal(t, http.StatusAccepted, adminDeleted.Status, adminDeleted.Body)

	var reason string
	require.NoError(t, testPool.QueryRow(context.Background(),
		"SELECT deletion_reason::text FROM auth.accounts WHERE id = $1::uuid", reused.String()).Scan(&reason))
	require.Equal(t, vo.DeletionAdminDeletion.String(), reason)
}

func TestHTTPJwksAndProbes(t *testing.T) {
	app := newTestApp(t)

	jwks := app.get(t, "/.well-known/jwks.json", nil)
	require.Equal(t, http.StatusOK, jwks.Status)
	require.Equal(t, "public, max-age=600", jwks.Headers.Get("Cache-Control"))

	var document struct {
		Keys []struct {
			Kty string `json:"kty"`
			Kid string `json:"kid"`
			Use string `json:"use"`
			Alg string `json:"alg"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	jwks.JSON(t, &document)
	require.Len(t, document.Keys, 1)
	require.Equal(t, "RSA", document.Keys[0].Kty)
	require.Equal(t, "sig", document.Keys[0].Use)
	require.Equal(t, "RS256", document.Keys[0].Alg)
	require.NotEmpty(t, document.Keys[0].N)

	// AUTH-BR-006: no private material is ever published.
	require.NotContains(t, jwks.Body, "PRIVATE")
	require.NotContains(t, jwks.Body, "\"d\"")

	require.Equal(t, http.StatusOK, app.get(t, "/health/live", nil).Status)
	require.Equal(t, http.StatusOK, app.get(t, "/health/ready", nil).Status)
	require.Equal(t, http.StatusOK, app.get(t, "/health/startup", nil).Status)
}

func TestHTTPRoutingErrors(t *testing.T) {
	app := newTestApp(t)

	missing := app.get(t, "/api/v1/auth/does-not-exist", nil)
	require.Equal(t, http.StatusNotFound, missing.Status)
	require.Equal(t, "application/problem+json", missing.Headers.Get("Content-Type"))
	require.Equal(t, apperr.CodeNotFound, missing.Code(t))

	wrongMethod := app.get(t, "/api/v1/auth/registrations", nil)
	require.Equal(t, http.StatusMethodNotAllowed, wrongMethod.Status)
	require.Equal(t, apperr.CodeMethodNotAllowed, wrongMethod.Code(t))

	// Every problem carries the fields the shared schema requires.
	var problem struct {
		Type     string `json:"type"`
		Title    string `json:"title"`
		Status   int    `json:"status"`
		Detail   string `json:"detail"`
		Instance string `json:"instance"`
		Code     string `json:"code"`
	}
	missing.JSON(t, &problem)
	require.NotEmpty(t, problem.Type)
	require.NotEmpty(t, problem.Title)
	require.Equal(t, http.StatusNotFound, problem.Status)
	require.NotEmpty(t, problem.Detail)
	require.Equal(t, "/api/v1/auth/does-not-exist", problem.Instance)
	require.NotEmpty(t, problem.Code)
}

// The identity event of a completed verification is the one User Service needs.
func TestVerificationEventShape(t *testing.T) {
	envelope := event.NewUserVerifiedSuccess(uuid.New(), []string{"USER"}, time.Now().UTC(), event.Metadata{})

	require.Equal(t, "1.0", envelope.SpecVersion)
	require.Equal(t, event.TypeUserVerifiedSuccess, envelope.Type)
	require.Equal(t, "/services/auth", envelope.Source)
	require.Equal(t, "application/json", envelope.DataContentType)
	require.True(t, strings.HasPrefix(envelope.Subject, "accounts/"))
	require.Equal(t, event.ChannelIdentity, envelope.Channel)

	// The payload carries identifiers only: no email, no code, no token.
	payload, err := json.Marshal(envelope)
	require.NoError(t, err)
	require.NotContains(t, strings.ToLower(string(payload)), "@")
	require.NotContains(t, strings.ToLower(string(payload)), "password")
}

// The internal contact endpoint follows the account through its lifecycle:
// unverified after registration, verified once the OTP is consumed, and gone
// (404, not 409) once AUTH-BR-007 anonymized the row.
func TestHTTPInternalNotificationContact(t *testing.T) {
	app := newTestApp(t)
	app.clearDeliveries(t)

	created := app.post(t, "/api/v1/auth/registrations", map[string]any{
		"email": flowEmail, "password": flowPassword, "confirm_password": flowPassword,
	}, nil)
	require.Equal(t, http.StatusAccepted, created.Status, created.Body)
	accountID := app.accountID(t, strings.ToLower(flowEmail))
	path := "/internal/v1/auth/users/" + accountID.String() + "/notification-contact"

	type contact struct {
		UserID        string  `json:"user_id"`
		Email         string  `json:"email"`
		EmailVerified bool    `json:"email_verified"`
		Locale        *string `json:"locale"`
	}

	pending := app.getInternal(t, path)
	require.Equal(t, http.StatusOK, pending.Status, pending.Body)
	require.Equal(t, "no-store", pending.Headers.Get("Cache-Control"))
	var unverified contact
	pending.JSON(t, &unverified)
	require.Equal(t, accountID.String(), unverified.UserID)
	require.Equal(t, flowEmail, unverified.Email, "the address is returned as the owner supplied it")
	require.False(t, unverified.EmailVerified)
	require.Nil(t, unverified.Locale)
	require.Contains(t, pending.Body, `"locale":null`, "locale is serialized as null, not omitted")

	verified := app.post(t, "/api/v1/auth/email-verifications", map[string]any{
		"email": flowEmail, "otp": app.codeFor(t, constants.TemplateRegistrationVerification),
	}, nil)
	require.Equal(t, http.StatusNoContent, verified.Status, verified.Body)

	active := app.getInternal(t, path)
	require.Equal(t, http.StatusOK, active.Status, active.Body)
	var confirmed contact
	active.JSON(t, &confirmed)
	require.True(t, confirmed.EmailVerified)

	// The internal listener is not the public one: the public routes are absent.
	require.Equal(t, http.StatusNotFound, app.getInternal(t, "/api/v1/auth/sessions").Status)

	unknown := app.getInternal(t, "/internal/v1/auth/users/"+uuid.NewString()+"/notification-contact")
	require.Equal(t, http.StatusNotFound, unknown.Status)
	require.Equal(t, apperr.CodeAccountNotFound, unknown.Code(t))

	malformed := app.getInternal(t, "/internal/v1/auth/users/not-a-uuid/notification-contact")
	require.Equal(t, http.StatusBadRequest, malformed.Status)
	require.Equal(t, apperr.CodeValidationFailed, malformed.Code(t))

	app.login(t, flowEmail, flowPassword)
	deleted := app.post(t, "/api/v1/auth/accounts/me/deletion-requests", map[string]any{
		"current_password": flowPassword,
	}, identityOf(accountID))
	require.Equal(t, http.StatusAccepted, deleted.Status, deleted.Body)

	gone := app.getInternal(t, path)
	require.Equal(t, http.StatusNotFound, gone.Status, gone.Body)
	require.Equal(t, apperr.CodeAccountNotFound, gone.Code(t))
	require.NotContains(t, gone.Body, "@", "a refusal never carries an address")
}
