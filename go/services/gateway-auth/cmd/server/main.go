// Command server runs gateway-auth: the edge JWT verifier Traefik calls through
// ForwardAuth (docs/gateway/design.md, runtime-contract.md section 6). It never
// issues tokens and holds no private key.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	goredis "github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"

	"github.com/ecommerce/gateway-auth/internal/api/handler"
	"github.com/ecommerce/gateway-auth/internal/api/router"
	"github.com/ecommerce/gateway-auth/internal/common/config"
	"github.com/ecommerce/gateway-auth/internal/common/keybuilder"
	"github.com/ecommerce/gateway-auth/internal/domain/service"
	"github.com/ecommerce/gateway-auth/internal/infrastructure/jwks"
	"github.com/ecommerce/gateway-auth/internal/infrastructure/redis"
)

func main() {
	configPath := flag.String("config", "./configs/application.yaml", "path to the YAML configuration")
	flag.Parse()

	if err := run(*configPath); err != nil {
		fmt.Fprintln(os.Stderr, "gateway-auth:", err)
		os.Exit(1)
	}
}

func run(configPath string) error {
	cfg, err := config.Load(configPath)
	if err != nil {
		return err
	}

	logger, err := newLogger(cfg.Logging.Level)
	if err != nil {
		return err
	}
	defer func() { _ = logger.Sync() }()

	redisOptions, err := goredis.ParseURL(cfg.Redis.URL)
	if err != nil {
		return fmt.Errorf("parse redis url: %w", err)
	}
	redisOptions.DialTimeout = cfg.Redis.Timeout
	redisOptions.ReadTimeout = cfg.Redis.Timeout
	redisOptions.WriteTimeout = cfg.Redis.Timeout
	redisClient := goredis.NewClient(redisOptions)
	defer func() { _ = redisClient.Close() }()

	keys := jwks.New(jwks.Options{
		URL:                cfg.JWKS.URL,
		RefreshInterval:    cfg.JWKS.RefreshInterval,
		FetchTimeout:       cfg.JWKS.FetchTimeout,
		MinRefreshInterval: cfg.JWKS.MinRefreshInterval,
		RetiredKeyGrace:    cfg.JWKS.RetiredKeyGrace,
		MaxStale:           cfg.JWKS.MaxStale,
		MaxKeys:            cfg.JWKS.MaxKeys,
	})
	revocations := redis.NewRevocationStore(redisClient, keybuilder.New(cfg.Redis.RevocationPrefix), cfg.Redis.Timeout)

	verifier := service.NewVerifier(service.VerifierConfig{
		Issuer:            cfg.JWT.Issuer,
		Audience:          cfg.JWT.Audience,
		AllowedAlgorithms: cfg.JWT.AllowedAlgorithms,
		Leeway:            cfg.JWT.Leeway,
	}, keys, revocations, time.Now)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go redis.SubscribeJWKSInvalidation(ctx, redisClient, cfg.Redis.JWKSInvalidateChannel, keys, logger)

	// Warm the key cache so the first protected request does not pay for it.
	// A failure here is not fatal: readiness stays DOWN and requests fail closed.
	if err := keys.Refresh(ctx); err != nil {
		logger.Warn("initial JWKS fetch failed", zap.Error(err))
	}

	health := handler.NewHealthHandler(cfg.Server.VerifyDeadline,
		func(ctx context.Context) bool { return revocations.Ping(ctx) == nil },
		keys.Ready,
	)
	server := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.Server.Port),
		Handler:           router.New(handler.NewVerifyHandler(verifier, cfg.Server.VerifyDeadline, logger), health),
		ReadHeaderTimeout: cfg.Server.ReadTimeout,
		ReadTimeout:       cfg.Server.ReadTimeout,
		WriteTimeout:      cfg.Server.WriteTimeout,
	}

	errs := make(chan error, 1)
	go func() {
		logger.Info("gateway-auth listening",
			zap.Int("port", cfg.Server.Port),
			zap.String("issuer", cfg.JWT.Issuer),
			zap.String("audience", cfg.JWT.Audience),
			zap.Strings("algorithms", cfg.JWT.AllowedAlgorithms),
		)
		errs <- server.ListenAndServe()
	}()

	select {
	case err := <-errs:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
		logger.Info("shutting down")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.Server.ShutdownTimeout)
	defer cancel()
	return server.Shutdown(shutdownCtx)
}

func newLogger(level string) (*zap.Logger, error) {
	var parsed zapcore.Level
	if err := parsed.UnmarshalText([]byte(level)); err != nil {
		return nil, fmt.Errorf("logging.level: %w", err)
	}
	cfg := zap.NewProductionConfig()
	cfg.Level = zap.NewAtomicLevelAt(parsed)
	cfg.EncoderConfig.TimeKey = "timestamp"
	cfg.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	cfg.InitialFields = map[string]any{"service": "gateway-auth"}
	return cfg.Build()
}
