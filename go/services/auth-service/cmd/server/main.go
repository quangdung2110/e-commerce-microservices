// Command server runs the Auth Service: the public HTTP API, the internal
// notification-delivery and notification-contact API and the scheduled workers.
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/handler"
	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/api/router"
	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/common/keybuilder"
	"github.com/ecommerce/auth-service/internal/common/logging"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
	"github.com/ecommerce/auth-service/internal/infrastructure/crypto"
	"github.com/ecommerce/auth-service/internal/infrastructure/kafka"
	"github.com/ecommerce/auth-service/internal/infrastructure/postgres"
	redisadapter "github.com/ecommerce/auth-service/internal/infrastructure/redis"
	"github.com/ecommerce/auth-service/internal/workers"
	accountharddelete "github.com/ecommerce/auth-service/internal/workers/account_hard_delete"
	autounlock "github.com/ecommerce/auth-service/internal/workers/auto_unlock"
	keyretirement "github.com/ecommerce/auth-service/internal/workers/key_retirement"
	keyrotation "github.com/ecommerce/auth-service/internal/workers/key_rotation"
	refreshtokencleanup "github.com/ecommerce/auth-service/internal/workers/refresh_token_cleanup"
)

func main() {
	configPath := flag.String("config", "", "path to the configuration file")
	flag.Parse()

	if err := run(*configPath); err != nil {
		log.Fatalf("auth-service: %v", err)
	}
}

func run(configPath string) error {
	cfg, err := config.Load(configPath)
	if err != nil {
		return err
	}

	logger, err := logging.New(cfg.Logging.Level, cfg.Logging.Format, cfg.App.Name)
	if err != nil {
		return fmt.Errorf("build logger: %w", err)
	}
	defer func() { _ = logger.Sync() }()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	logger.Info("starting auth-service",
		zap.String("env", cfg.App.Env),
		zap.Int("port", cfg.App.Port),
	)

	// Infrastructure.
	pool, err := postgres.NewPool(ctx, cfg.Database)
	if err != nil {
		return err
	}
	defer pool.Close()

	redisClient, err := redisadapter.NewClient(ctx, cfg.Redis)
	if err != nil {
		return err
	}
	defer func() { _ = redisClient.Close() }()

	systemClock := clock.System{}
	keys := keybuilder.New(cfg.Redis.RevocationPrefix, cfg.Redis.StatePrefix)

	keyCipher, err := crypto.NewKeyCipher(cfg.Keys)
	if err != nil {
		return err
	}

	// Repositories and adapters.
	accounts := postgres.NewAccountRepository(pool)
	refreshTokens := postgres.NewRefreshTokenRepository(pool)
	jwtKeys := postgres.NewJwtKeyRepository(pool)
	txManager := postgres.NewTxManager(pool)

	revocation := redisadapter.NewRevocationStore(redisClient, keys)
	limiter := redisadapter.NewRateLimiter(redisClient, keys)
	otps := redisadapter.NewOtpStore(redisClient, keys)
	resets := redisadapter.NewResetTokenStore(redisClient, keys)
	deliveries := redisadapter.NewDeliveryStore(redisClient, keys, systemClock)
	jwksNotifier := redisadapter.NewJwksNotifier(redisClient, keys)
	locker := redisadapter.NewLocker(redisClient, keys)

	hasher := crypto.NewArgon2Hasher(cfg.Security.Argon2)
	keyGenerator := crypto.NewRSAKeyGenerator(cfg.Keys.RSABits, keyCipher)
	signer := crypto.NewSigner(jwtKeys, keyCipher, systemClock, cfg.JWT.KeyCacheTTL)

	publisher, closePublisher, err := newPublisher(cfg.Kafka, logger)
	if err != nil {
		return err
	}
	defer closePublisher()

	// Domain services.
	notifier := service.NewNotifier(deliveries, publisher, systemClock, logger, cfg.Notification.DeliveryTTL)

	tokenService := service.NewTokenService(signer, systemClock, service.TokenConfig{
		Issuer:    cfg.JWT.Issuer,
		Audience:  cfg.JWT.Audience,
		AccessTTL: cfg.JWT.AccessTokenTTL,
		ClockSkew: cfg.JWT.ClockSkew,
	})
	jwksService := service.NewJwksService(signer)
	revoker := service.NewRevoker(refreshTokens, revocation, systemClock, cfg.JWT.AccessTokenTTL, cfg.JWT.ClockSkew)

	passwordPolicy := vo.NewPasswordPolicy(cfg.Security.PasswordPolicy.MinLength, cfg.Security.PasswordPolicy.MaxLength)
	otpPolicy := service.OtpPolicy{
		TTL:          cfg.OTP.TTL,
		MaxAttempts:  cfg.OTP.MaxAttempts,
		ResendLimit:  cfg.OTP.ResendLimit,
		ResendWindow: cfg.OTP.ResendWindow,
	}
	sessionPolicy := service.SessionPolicy{
		SlidingTTL:  cfg.Refresh.SlidingTTL,
		AbsoluteTTL: cfg.Refresh.AbsoluteTTL,
	}
	lockoutPolicy := entity.LockoutPolicy{
		MaxFailedAttempts: cfg.Security.Lockout.MaxFailedAttempts,
		LockDuration:      cfg.Security.Lockout.LockDuration,
		CounterResetAfter: cfg.Security.Lockout.CounterResetAfter,
	}

	registrationService := service.NewRegistrationService(
		accounts, otps, hasher, notifier, systemClock, passwordPolicy, otpPolicy, logger)

	authenticationService := service.NewAuthenticationService(
		accounts, refreshTokens, hasher, limiter, tokenService, notifier, systemClock,
		lockoutPolicy, sessionPolicy, toRule(cfg.Security.RateLimit.LoginPerAccount), logger)

	sessionService := service.NewSessionService(
		accounts, refreshTokens, tokenService, revoker, systemClock, sessionPolicy, logger)

	passwordService := service.NewPasswordService(
		accounts, resets, hasher, limiter, revoker, notifier, systemClock, passwordPolicy,
		service.ResetPolicy{TTL: cfg.PasswordReset.TTL, URLTemplate: cfg.PasswordReset.URLTemplate},
		toRule(cfg.Security.RateLimit.OtpPerAccount), logger)

	emailChangeService := service.NewEmailChangeService(
		accounts, otps, hasher, revoker, notifier, systemClock, otpPolicy, logger)

	deletionService := service.NewAccountDeletionService(
		accounts, refreshTokens, hasher, revoker, notifier, txManager, systemClock, logger)

	maintenanceService := service.NewMaintenanceService(accounts, refreshTokens, notifier, systemClock, logger)

	keyLifecycleService := service.NewKeyLifecycleService(
		jwtKeys, keyGenerator, signer, jwksNotifier, systemClock,
		service.KeyPolicy{
			RotationInterval: cfg.Keys.RotationInterval,
			RetirementDelay:  cfg.Keys.RetirementDelay,
		}, logger)

	deliveryService := service.NewDeliveryService(deliveries)
	contactService := service.NewContactService(accounts)

	// A service without a signing key cannot issue tokens, so the first key is
	// created before the listener opens.
	if err := keyLifecycleService.EnsureActiveKey(ctx); err != nil {
		return fmt.Errorf("ensure an active signing key: %w", err)
	}

	// HTTP layer.
	cookieSettings := httpx.NewCookieSettings(cfg.Refresh)

	healthHandler := handler.NewHealthHandler(2 * time.Second)
	healthHandler.AddProbe("postgres", pool.Ping)
	healthHandler.AddProbe("redis", func(probeCtx context.Context) error {
		return redisClient.Ping(probeCtx).Err()
	})
	healthHandler.AddProbe("signing-key", func(probeCtx context.Context) error {
		_, err := signer.PublicKeys(probeCtx)
		return err
	})

	publicEngine := router.NewPublic(router.PublicDeps{
		Config:       cfg,
		Logger:       logger,
		Limiter:      limiter,
		Registration: handler.NewRegistrationHandler(registrationService),
		Session:      handler.NewSessionHandler(authenticationService, sessionService, cookieSettings),
		Password:     handler.NewPasswordHandler(passwordService, cookieSettings),
		Account:      handler.NewAccountHandler(emailChangeService, deletionService, cookieSettings),
		Jwks:         handler.NewJwksHandler(jwksService, cfg.JWT.JwksCacheControl),
		Health:       healthHandler,
	})

	publicServer := &http.Server{
		Addr:         fmt.Sprintf("%s:%d", cfg.App.Host, cfg.App.Port),
		Handler:      publicEngine,
		ReadTimeout:  cfg.Server.ReadTimeout,
		WriteTimeout: cfg.Server.WriteTimeout,
		IdleTimeout:  cfg.Server.IdleTimeout,
	}

	var internalServer *http.Server
	if cfg.Internal.Enabled {
		internalEngine := router.NewInternal(router.InternalDeps{
			Config:   cfg,
			Logger:   logger,
			Limiter:  limiter,
			Delivery: handler.NewDeliveryHandler(deliveryService),
			Contact:  handler.NewContactHandler(contactService),
			Health:   healthHandler,
		})
		internalServer = &http.Server{
			Addr:         fmt.Sprintf("%s:%d", cfg.Internal.Host, cfg.Internal.Port),
			Handler:      internalEngine,
			ReadTimeout:  cfg.Server.ReadTimeout,
			WriteTimeout: cfg.Server.WriteTimeout,
			IdleTimeout:  cfg.Server.IdleTimeout,
		}
		if cfg.Internal.MutualTLSEnabled() {
			tlsConfig, err := newMutualTLSConfig(cfg.Internal)
			if err != nil {
				return err
			}
			internalServer.TLSConfig = tlsConfig
		}
	}

	// Workers.
	runner := workers.NewRunner(locker, cfg.Workers.LockTTL, logger)
	runner.Register(refreshtokencleanup.New(maintenanceService, cfg.Workers.RefreshTokenCleanup, cfg.Workers.RefreshTokenRetention))
	runner.Register(autounlock.New(maintenanceService, cfg.Workers.AutoUnlock))
	runner.Register(accountharddelete.New(maintenanceService, cfg.Workers.AccountHardDelete))
	runner.Register(keyrotation.New(keyLifecycleService, cfg.Workers.KeyRotation))
	runner.Register(keyretirement.New(keyLifecycleService, cfg.Workers.KeyRetirement))
	runner.Start(ctx)

	serverErrors := make(chan error, 2)

	go func() {
		logger.Info("public API listening", zap.String("addr", publicServer.Addr))
		if err := publicServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErrors <- fmt.Errorf("public server: %w", err)
		}
	}()

	if internalServer != nil {
		go func() {
			logger.Info("internal API listening",
				zap.String("addr", internalServer.Addr),
				zap.Bool("mtls", internalServer.TLSConfig != nil),
			)
			var err error
			if internalServer.TLSConfig != nil {
				err = internalServer.ListenAndServeTLS(cfg.Internal.TLSCertFile, cfg.Internal.TLSKeyFile)
			} else {
				err = internalServer.ListenAndServe()
			}
			if err != nil && !errors.Is(err, http.ErrServerClosed) {
				serverErrors <- fmt.Errorf("internal server: %w", err)
			}
		}()
	}

	var runErr error
	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received")
	case runErr = <-serverErrors:
		logger.Error("server stopped unexpectedly", zap.Error(runErr))
		stop()
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.Server.ShutdownTimeout)
	defer cancel()

	if err := publicServer.Shutdown(shutdownCtx); err != nil {
		logger.Error("public server shutdown failed", zap.Error(err))
	}
	if internalServer != nil {
		if err := internalServer.Shutdown(shutdownCtx); err != nil {
			logger.Error("internal server shutdown failed", zap.Error(err))
		}
	}

	runner.Wait()
	logger.Info("auth-service stopped")
	return runErr
}

// newPublisher returns the Kafka publisher, or a logging stand-in when the
// broker is disabled for a local run.
func newPublisher(cfg config.KafkaConfig, logger *zap.Logger) (port.EventPublisher, func(), error) {
	if !cfg.Enabled {
		logger.Warn("kafka is disabled, identity events will not be published")
		return kafka.NewLoggingPublisher(logger), func() {}, nil
	}

	publisher, err := kafka.NewPublisher(cfg, logger)
	if err != nil {
		return nil, nil, err
	}
	return publisher, func() {
		if err := publisher.Close(); err != nil {
			logger.Error("could not close the Kafka producer", zap.Error(err))
		}
	}, nil
}

// newMutualTLSConfig requires and verifies a client certificate signed by the
// configured CA, which is how the internal API authenticates Notification
// Service.
func newMutualTLSConfig(cfg config.InternalConfig) (*tls.Config, error) {
	caPEM, err := os.ReadFile(cfg.ClientCAFile)
	if err != nil {
		return nil, fmt.Errorf("read internal client CA: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		return nil, fmt.Errorf("internal client CA does not contain a certificate")
	}

	return &tls.Config{
		MinVersion: tls.VersionTLS12,
		ClientAuth: tls.RequireAndVerifyClientCert,
		ClientCAs:  pool,
	}, nil
}

func toRule(rule config.RateLimitRule) port.RateLimitRule {
	return port.RateLimitRule{Limit: rule.Limit, Window: rule.Window}
}
