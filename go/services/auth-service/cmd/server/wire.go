package server

import (
	"context"
	"fmt"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	authConfig "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/config"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	pgDb "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/infrastructure/database/postgres"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/infrastructure/event"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/infrastructure/external"
	pgAdapter "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/repository/pg_adapter"
	redisAdapter "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/repository/redis_adapter"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/authentication"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/password"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/registration"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/security"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/session"
)

// Dependencies holds all application dependencies
type Dependencies struct {
	// Usecases
	RegistrationUsecase   *registration.PersonalRegistrationUsecase
	ManagedAccountUsecase *registration.ManagedAccountUsecase
	LoginUsecase          *authentication.LoginUsecase
	LogoutUsecase         *authentication.LogoutUsecase
	RefreshTokenUsecase   *authentication.RefreshTokenUsecase
	PasswordResetUsecase  *password.ResetUsecase
	PasswordChangeUsecase *password.ChangeUsecase
	SessionManagerUsecase *session.ManagerUsecase

	// Repositories (for middleware)
	SessionRepo   repository.SessionRepository
	RateLimitRepo repository.RateLimitRepository
	BlacklistRepo repository.BlacklistRepository

	// Security components (for middleware)
	TokenGenerator common.TokenGenerator

	// Database connection
	DB *pgDb.DB
}

// InitializeDependencies initializes all application dependencies
func InitializeDependencies(cfg *authConfig.Config, redisClient *redis.Client, logger *zap.Logger) (*Dependencies, error) {
	// ========================= DATABASE CONNECTION =========================

	// Create PostgreSQL connection
	dbCfg := &pgDb.Config{
		Host:              cfg.Database.Host,
		Port:              cfg.Database.Port,
		User:              cfg.Database.User,
		Password:          cfg.Database.Password,
		DBName:            cfg.Database.Name,
		SSLMode:           cfg.Database.SSLMode,
		MaxConns:          25,
		MinConns:          5,
		MaxConnLifetime:   5 * 60 * 1000000000, // 5 minutes in nanoseconds
		MaxConnIdleTime:   1 * 60 * 1000000000, // 1 minute
		HealthCheckPeriod: 1 * 60 * 1000000000, // 1 minute
	}

	db, err := pgDb.NewDB(context.Background(), dbCfg, logger)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to database: %w", err)
	}

	// ========================= INFRASTRUCTURE LAYER =========================

	// Repositories (Redis-based)
	otpRepo := redisAdapter.NewRedisOTPRepository(redisClient, logger)
	registrationSessionRepo := redisAdapter.NewRegistrationSessionRepository(redisClient, logger)
	rateLimitRepo := redisAdapter.NewRedisRateLimitRepository(redisClient, logger)
	refreshTokenRepo := redisAdapter.NewRedisRefreshTokenRepository(redisClient, logger)
	blacklistRepo := redisAdapter.NewRedisBlacklistRepository(redisClient, logger)

	// Repositories (PostgreSQL-based)
	sessionRepo := pgAdapter.NewPostgresSessionRepository(db.Pool(), logger)
	loginAttemptRepo := pgAdapter.NewPostgresLoginAttemptRepository(db.Pool(), logger)
	passwordResetRepo := pgAdapter.NewPostgresPasswordResetTokenRepository(db.Pool(), logger)

	// Security components
	passwordEncoder := security.NewBcryptPasswordEncoder(cfg.Security.BcryptCost)
	tokenGenerator := security.NewJWTTokenGenerator(
		cfg.JWT.AccessTokenSecret,
		cfg.JWT.RefreshTokenSecret,
		cfg.JWT.Issuer,
	)

	// External service clients
	userServiceClient := external.NewUserServiceClient(cfg.ExternalServices.UserServiceURL, logger)
	notificationServiceClient := external.NewNotificationServiceClient(cfg.ExternalServices.NotificationServiceURL, logger)

	// Event publisher
	var eventPublisher common.EventPublisher
	if len(cfg.Kafka.Brokers) > 0 && cfg.Kafka.Brokers[0] != "" {
		publisher, err := event.NewKafkaEventPublisher(cfg.Kafka.Brokers, "auth-events", logger)
		if err != nil {
			logger.Warn("Failed to initialize Kafka publisher, using NoOp", zap.Error(err))
			eventPublisher = event.NewNoOpEventPublisher(logger)
		} else {
			eventPublisher = publisher
			logger.Info("Kafka event publisher initialized")
		}
	} else {
		eventPublisher = event.NewNoOpEventPublisher(logger)
		logger.Info("Using NoOp event publisher (Kafka disabled)")
	}

	// ========================= USECASE LAYER =========================

	// Registration usecases
	registrationUsecase := registration.NewPersonalRegistrationUsecase(
		otpRepo,
		registrationSessionRepo,
		rateLimitRepo,
		userServiceClient,
		notificationServiceClient,
		eventPublisher,
		passwordEncoder,
		tokenGenerator,
		logger,
	)

	managedAccountUsecase := registration.NewManagedAccountUsecase(
		userServiceClient,
		notificationServiceClient,
		eventPublisher,
		passwordEncoder,
		logger,
	)

	// Authentication usecases
	loginUsecase := authentication.NewLoginUsecase(
		sessionRepo,
		loginAttemptRepo,
		rateLimitRepo,
		refreshTokenRepo,
		userServiceClient,
		eventPublisher,
		tokenGenerator,
		passwordEncoder,
		logger,
	)

	logoutUsecase := authentication.NewLogoutUsecase(
		sessionRepo,
		blacklistRepo,
		refreshTokenRepo,
		eventPublisher,
		logger,
	)

	refreshTokenUsecase := authentication.NewRefreshTokenUsecase(
		sessionRepo,
		refreshTokenRepo,
		userServiceClient,
		eventPublisher,
		tokenGenerator,
		logger,
	)

	// Password usecases
	passwordResetUsecase := password.NewResetUsecase(
		passwordResetRepo,
		sessionRepo,
		refreshTokenRepo,
		rateLimitRepo,
		userServiceClient,
		notificationServiceClient,
		eventPublisher,
		passwordEncoder,
		logger,
	)

	passwordChangeUsecase := password.NewChangeUsecase(
		sessionRepo,
		refreshTokenRepo,
		rateLimitRepo,
		userServiceClient,
		eventPublisher,
		passwordEncoder,
		logger,
	)

	// Session usecase
	sessionManagerUsecase := session.NewManagerUsecase(
		sessionRepo,
		refreshTokenRepo,
		eventPublisher,
		logger,
	)

	return &Dependencies{
		RegistrationUsecase:   registrationUsecase,
		ManagedAccountUsecase: managedAccountUsecase,
		LoginUsecase:          loginUsecase,
		LogoutUsecase:         logoutUsecase,
		RefreshTokenUsecase:   refreshTokenUsecase,
		PasswordResetUsecase:  passwordResetUsecase,
		PasswordChangeUsecase: passwordChangeUsecase,
		SessionManagerUsecase: sessionManagerUsecase,
		SessionRepo:           sessionRepo,
		RateLimitRepo:         rateLimitRepo,
		BlacklistRepo:         blacklistRepo,
		TokenGenerator:        tokenGenerator,
		DB:                    db,
	}, nil
}
