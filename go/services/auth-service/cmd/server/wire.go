package server

import (
	"context"
	"fmt"
	"time"

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
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/jwks"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/password"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/registration"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/security"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/session"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/infrastructure/redis_pubsub"
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

	// Audit Service
	AuditService *authentication.AuditService

	// JWKS Service
	JwksService *jwks.Service

	// Kafka Consumer (Module 8)
	KafkaConsumer *event.KafkaConsumer
	ConsumerHandler *event.ConsumerHandler

	// Outbox Publisher (Module 7)
	OutboxPublisher *event.OutboxPublisher
	EventRegistry   *event.EventRegistry

	// Repositories (for middleware)
	SessionRepo   repository.SessionRepository
	RateLimitRepo repository.RateLimitRepository
	BlacklistRepo repository.BlacklistRepository

	// Security components (for middleware)
	TokenGenerator common.TokenGenerator

	// Distributed Lock
	DistributedLockRepo repository.DistributedLockRepository

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
	graceCache := redisAdapter.NewGraceCacheRepository(redisClient)
	revokeBeforeCache := redisAdapter.NewRevokeBeforeCacheRepository(redisClient)
	distributedLockRepo := redisAdapter.NewDistributedLockRepository(redisClient)

	// Repositories (PostgreSQL-based)
	sessionRepo := pgAdapter.NewPostgresSessionRepository(db.Pool(), logger)
	loginAttemptRepo := pgAdapter.NewPostgresLoginAttemptRepository(db.Pool(), logger)
	passwordResetRepo := pgAdapter.NewPostgresPasswordResetTokenRepository(db.Pool(), logger)
	auditRepo := pgAdapter.NewPostgresAuditRepository(db.Pool(), logger)
	jwtKeyRepo := pgAdapter.NewPostgresJwtKeyRepository(db.Pool())
	outboxRepo := pgAdapter.NewPostgresOutboxRepository(db.Pool())

	// Security components
	passwordEncoder := security.NewBcryptPasswordEncoder(cfg.Security.BcryptCost)

	// JWKS Service (required for token generation)
	var jwksService *jwks.Service
	var jwksCache *jwks.Cache
	var jwksPublisher *redis_pubsub.Publisher

	if cfg.JWKS.Enabled {
		// Initialize JWKS cache
		jwksCache, err = jwks.NewCache(10 * time.Minute)
		if err != nil {
			logger.Warn("Failed to create JWKS cache, continuing without L1 cache", zap.Error(err))
		}

		// Initialize Redis Pub/Sub publisher
		jwksPublisher = redis_pubsub.NewPublisher(redisClient)

		// Create JWKS service
		jwksService = jwks.NewService(jwtKeyRepo, cfg.JWKS, logger)

		// Initialize with cache and pub/sub
		if err := jwksService.InitializeWithCacheAndPubsub(jwksCache, jwksPublisher); err != nil {
			logger.Warn("Failed to initialize JWKS service with cache and pub/sub", zap.Error(err))
		}

		// Generate initial key if none exists
		_, _, err := jwksService.GetActivePrivateKey(context.Background())
		if err != nil {
			logger.Warn("No active JWKS key found, will be generated on first use", zap.Error(err))
		}
	}

	// Token Generator using JWKS
	tokenGenerator := security.NewRS256TokenGenerator(
		jwksService,
		cfg.JWT.Issuer,
		time.Duration(cfg.JWT.AccessTokenExpiry)*time.Minute,
		logger,
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

	// ========================= OUTBOX PUBLISHER (Module 7) =========================
	outboxPublisher := event.NewOutboxPublisher(db.Pool(), logger)
	eventRegistry := event.NewEventRegistry()

	// ========================= AUDIT SERVICE =========================
	auditService := authentication.NewAuditService(auditRepo, logger)

	// ========================= KAFKA CONSUMER (Module 8) =========================
	var kafkaConsumer *event.KafkaConsumer
	var consumerHandler *event.ConsumerHandler

	if len(cfg.Kafka.Brokers) > 0 && cfg.Kafka.Brokers[0] != "" {
		adminTopic := cfg.Kafka.AdminEventsTopic
		if adminTopic == "" {
			adminTopic = "t.admin.events"
		}

		kafkaConsumer, err = event.NewKafkaConsumer(
			cfg.Kafka.Brokers,
			cfg.Kafka.ConsumerGroup,
			adminTopic,
			logger,
		)
		if err != nil {
			logger.Warn("Failed to create Kafka consumer, admin events will not be processed", zap.Error(err))
		} else {
			// Create consumer handler with dependencies
			consumerDeps := &event.ConsumerDependencies{
				SessionRepo:  sessionRepo,
				UserService:  userServiceClient,
				CacheRepo:    revokeBeforeCache,
				AuditService: auditService,
				Logger:       logger,
			}

			consumerHandler = event.NewConsumerHandler(kafkaConsumer, consumerDeps, logger)
			consumerHandler.RegisterDefaultHandlers()

			logger.Info("Kafka consumer for admin events initialized",
				zap.String("topic", adminTopic),
				zap.String("group_id", cfg.Kafka.ConsumerGroup))
		}
	} else {
		logger.Info("Kafka consumer disabled (no brokers configured)")
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
		auditService,
		logger,
	)

	logoutUsecase := authentication.NewLogoutUsecase(
		sessionRepo,
		blacklistRepo,
		refreshTokenRepo,
		revokeBeforeCache,
		eventPublisher,
		auditService,
		logger,
	)

	refreshTokenUsecase := authentication.NewRefreshTokenUsecase(
		sessionRepo,
		graceCache,
		revokeBeforeCache,
		blacklistRepo,
		eventPublisher,
		tokenGenerator,
		userServiceClient,
		auditService,
		logger,
	)

	// Password usecases
	passwordResetUsecase := password.NewResetUsecase(
		passwordResetRepo,
		sessionRepo,
		refreshTokenRepo,
		revokeBeforeCache,
		rateLimitRepo,
		userServiceClient,
		notificationServiceClient,
		eventPublisher,
		outboxRepo,
		passwordEncoder,
		auditService,
		logger,
	)

	passwordChangeUsecase := password.NewChangeUsecase(
		sessionRepo,
		refreshTokenRepo,
		revokeBeforeCache,
		rateLimitRepo,
		userServiceClient,
		eventPublisher,
		passwordEncoder,
		auditService,
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
		AuditService:         auditService,
		JwksService:           jwksService,
		KafkaConsumer:         kafkaConsumer,
		ConsumerHandler:       consumerHandler,
		OutboxPublisher:       outboxPublisher,
		EventRegistry:         eventRegistry,
		SessionRepo:           sessionRepo,
		RateLimitRepo:         rateLimitRepo,
		BlacklistRepo:         blacklistRepo,
		TokenGenerator:        tokenGenerator,
		DistributedLockRepo:   distributedLockRepo,
		DB:                    db,
	}, nil
}
