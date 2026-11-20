package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// testNow is a fixed instant so every expiry in a test is deterministic.
var testNow = time.Date(2026, 8, 27, 12, 0, 0, 0, time.UTC)

const (
	testPassword    = "correct horse battery"
	testNewPassword = "another correct horse"
	testEmail       = "Owner@Example.com"
)

// harness wires the use cases against in-memory doubles.
type harness struct {
	clock      *clock.Fixed
	accounts   *fakeAccountRepo
	tokens     *fakeRefreshRepo
	otps       *fakeOtpStore
	resets     *fakeResetStore
	deliveries *fakeDeliveryStore
	revocation *fakeRevocation
	limiter    *fakeLimiter
	publisher  *fakePublisher
	signer     *fakeSigner
	keys       *fakeKeyRepo
	generator  *fakeKeyGenerator
	jwks       *fakeJwksNotifier

	registration *service.RegistrationService
	auth         *service.AuthenticationService
	sessions     *service.SessionService
	passwords    *service.PasswordService
	emailChanges *service.EmailChangeService
	deletions    *service.AccountDeletionService
	maintenance  *service.MaintenanceService
	keyLifecycle *service.KeyLifecycleService
	contacts     *service.ContactService
}

func newHarness(t *testing.T) *harness {
	t.Helper()

	logger := zap.NewNop()
	h := &harness{
		clock:      clock.NewFixed(testNow),
		accounts:   newAccountRepo(),
		tokens:     newRefreshRepo(),
		otps:       newOtpStore(),
		resets:     newResetStore(),
		deliveries: newDeliveryStore(),
		revocation: newRevocation(),
		limiter:    newLimiter(),
		publisher:  newPublisher(),
		signer:     newSigner(),
		keys:       newKeyRepo(),
		generator:  &fakeKeyGenerator{},
		jwks:       &fakeJwksNotifier{},
	}

	notifier := service.NewNotifier(h.deliveries, h.publisher, h.clock, logger, 30*time.Minute)
	tokenService := service.NewTokenService(h.signer, h.clock, service.TokenConfig{
		Issuer:    "https://auth.test",
		Audience:  "ecommerce-api",
		AccessTTL: 15 * time.Minute,
		ClockSkew: 30 * time.Second,
	})
	revoker := service.NewRevoker(h.tokens, h.revocation, h.clock, 15*time.Minute, 30*time.Second)

	policy := vo.NewPasswordPolicy(12, 128)
	otpPolicy := service.OtpPolicy{
		TTL:          5 * time.Minute,
		MaxAttempts:  3,
		ResendLimit:  3,
		ResendWindow: 15 * time.Minute,
	}
	sessionPolicy := service.SessionPolicy{
		SlidingTTL:  7 * 24 * time.Hour,
		AbsoluteTTL: 30 * 24 * time.Hour,
	}
	lockout := entity.LockoutPolicy{
		MaxFailedAttempts: 3,
		LockDuration:      15 * time.Minute,
		CounterResetAfter: time.Hour,
	}
	accountRule := port.RateLimitRule{Limit: 100, Window: time.Minute}

	h.registration = service.NewRegistrationService(h.accounts, h.otps, fakeHasher{}, notifier, h.clock, policy, otpPolicy, logger)
	h.auth = service.NewAuthenticationService(h.accounts, h.tokens, fakeHasher{}, h.limiter, tokenService, notifier,
		h.clock, lockout, sessionPolicy, accountRule, logger)
	h.sessions = service.NewSessionService(h.accounts, h.tokens, tokenService, revoker, notifier, h.clock, sessionPolicy, logger)
	h.passwords = service.NewPasswordService(h.accounts, h.resets, fakeHasher{}, h.limiter, revoker, notifier, h.clock,
		policy, service.ResetPolicy{TTL: 15 * time.Minute, URLTemplate: "https://app.test/reset#token={token}"},
		accountRule, logger)
	h.emailChanges = service.NewEmailChangeService(h.accounts, h.otps, fakeHasher{}, revoker, notifier, h.clock, otpPolicy, logger)
	h.deletions = service.NewAccountDeletionService(h.accounts, h.tokens, fakeHasher{}, revoker, notifier,
		fakeTxManager{}, h.clock, logger)
	h.maintenance = service.NewMaintenanceService(h.accounts, h.tokens, notifier, h.clock, logger)
	h.keyLifecycle = service.NewKeyLifecycleService(h.keys, h.generator, h.signer, h.jwks, h.clock,
		service.KeyPolicy{RotationInterval: 30 * 24 * time.Hour, RetirementDelay: 24 * time.Hour}, logger)
	h.contacts = service.NewContactService(h.accounts)

	return h
}

// registerAndVerify creates an ACTIVE account through the real flows, so the
// tests start from a state the service itself produced.
func (h *harness) registerAndVerify(t *testing.T) *entity.Account {
	t.Helper()
	ctx := context.Background()

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))

	account, err := h.accounts.GetByNormalizedEmail(ctx, "owner@example.com")
	require.NoError(t, err)
	require.Equal(t, vo.AccountPendingVerification, account.Status)

	code := h.otpCode(t, constants.TemplateRegistrationVerification)
	require.NoError(t, h.registration.VerifyEmail(ctx, testEmail, code))

	account, err = h.accounts.GetByNormalizedEmail(ctx, "owner@example.com")
	require.NoError(t, err)
	require.Equal(t, vo.AccountActive, account.Status)
	return account
}

// otpCode reads the code out of the notification delivery state, which is the
// only place the plaintext exists after issuance.
func (h *harness) otpCode(t *testing.T, template string) string {
	t.Helper()
	delivery := h.deliveries.byTemplate(template)
	require.NotNil(t, delivery, "no %s delivery was stored", template)
	code, ok := delivery.TemplateData["otp"]
	require.True(t, ok, "delivery carries no otp")
	return code
}

// login opens a session through the real login flow.
func (h *harness) login(t *testing.T) *service.LoginResult {
	t.Helper()
	result, err := h.auth.Login(context.Background(), service.LoginCommand{
		Email:      testEmail,
		Password:   testPassword,
		DeviceName: "Test Device",
		IPAddress:  "203.0.113.10",
		UserAgent:  "test-agent",
	})
	require.NoError(t, err)
	return result
}
