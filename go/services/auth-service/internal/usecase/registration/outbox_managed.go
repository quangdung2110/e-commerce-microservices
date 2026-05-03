package registration

import (
	"context"
	"fmt"
	"strings"

	"go.uber.org/zap"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/infrastructure/event"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/utils"
)

// ManagedAccountUsecaseWithOutbox handles MANAGED account creation using Outbox Pattern
type ManagedAccountUsecaseWithOutbox struct {
	userService         common.UserService
	notificationService common.NotificationService
	outboxPublisher    *event.OutboxPublisher
	eventRegistry      *event.EventRegistry
	passwordEncoder    common.PasswordEncoder
	dbPool             *pgxpool.Pool
	logger             *zap.Logger
}

// NewManagedAccountUsecaseWithOutbox creates new managed account usecase with outbox pattern
func NewManagedAccountUsecaseWithOutbox(
	userService common.UserService,
	notificationService common.NotificationService,
	outboxPublisher *event.OutboxPublisher,
	eventRegistry *event.EventRegistry,
	passwordEncoder common.PasswordEncoder,
	dbPool *pgxpool.Pool,
	logger *zap.Logger,
) *ManagedAccountUsecaseWithOutbox {
	return &ManagedAccountUsecaseWithOutbox{
		userService:         userService,
		notificationService: notificationService,
		outboxPublisher:    outboxPublisher,
		eventRegistry:      eventRegistry,
		passwordEncoder:    passwordEncoder,
		dbPool:             dbPool,
		logger:             logger,
	}
}

// CreateManagedAccount creates a managed account using Outbox Pattern
// Events are inserted into the outbox table within the same transaction as the user creation
func (uc *ManagedAccountUsecaseWithOutbox) CreateManagedAccount(ctx context.Context, req *dto.CreateManagedAccountRequest, creator *common.UserDTO) (*dto.CreateManagedAccountResponse, error) {
	// Step 1: Validate creator permission
	if err := validateCreatorPermission(creator, req.Role); err != nil {
		uc.logger.Warn("Unauthorized managed account creation attempt",
			zap.String("creator_id", creator.UserID.String()),
			zap.String("creator_role", string(creator.Role)),
			zap.String("target_role", string(req.Role)))
		return nil, err
	}

	// Step 2: Validate username uniqueness
	username := strings.ToLower(strings.TrimSpace(req.Username))
	user, err := uc.userService.GetUserByUsername(ctx, username)
	if err != nil {
		uc.logger.Error("Failed to check username existence", zap.Error(err))
	}
	if user != nil {
		return nil, serr.ErrUsernameAlreadyExists
	}

	// Step 3: Generate temporary password
	var passwordHash string
	var generatedPassword string

	tempPass, err := utils.GenerateTemporaryPassword()
	if err != nil {
		return nil, fmt.Errorf("failed to generate temporary password: %w", err)
	}
	hash, err := uc.passwordEncoder.Hash(tempPass)
	if err != nil {
		return nil, fmt.Errorf("failed to hash password: %w", err)
	}
	passwordHash = hash
	generatedPassword = tempPass

	// Step 4: Begin transaction for atomic operation
	tx, err := uc.dbPool.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() {
		if r := recover(); r != nil {
			_ = tx.Rollback(ctx)
			panic(r)
		}
	}()

	// Step 5: Create user
	parentID := creator.UserID
	createUserReq := &common.CreateUserRequest{
		Username:     &username,
		FullName:     req.FullName,
		PasswordHash: passwordHash,
		Role:         req.Role,
		AccountType:  entity.AccountTypeManaged,
		ParentID:     &parentID,
	}

	user, err = uc.userService.CreateUser(ctx, createUserReq)
	if err != nil {
		_ = tx.Rollback(ctx)
		uc.logger.Error("Failed to create managed user", zap.Error(err))
		return nil, err
	}

	// Step 6: Insert event into outbox (same transaction as user creation)
	// This ensures atomicity: if user creation succeeds but event insert fails, both are rolled back
	outboxEvent := uc.eventRegistry.BuildManagedAccountCreatedEvent(
		user.UserID,
		creator.UserID,
		string(user.Role),
		username,
	)

	if err := uc.outboxPublisher.PublishEvent(ctx, tx, outboxEvent); err != nil {
		_ = tx.Rollback(ctx)
		uc.logger.Error("Failed to insert outbox event", zap.Error(err))
		return nil, fmt.Errorf("failed to insert outbox event: %w", err)
	}

	// Step 7: Commit transaction (user + event)
	if err := tx.Commit(ctx); err != nil {
		uc.logger.Error("Failed to commit transaction", zap.Error(err))
		return nil, fmt.Errorf("failed to commit transaction: %w", err)
	}

	// Step 8: Send notification (outside transaction - best effort)
	if err := uc.sendManagedAccountCreationNotification(ctx, creator.Email, username, generatedPassword); err != nil {
		uc.logger.Error("Failed to send managed account notification", zap.Error(err))
		// Don't fail the operation - notification is not critical
	}

	uc.logger.Info("Managed account created with outbox pattern",
		zap.String("user_id", user.UserID.String()),
		zap.String("username", username),
		zap.String("creator_id", creator.UserID.String()),
		zap.String("role", string(req.Role)),
		zap.String("event_id", outboxEvent.ID.String()))

	return &dto.CreateManagedAccountResponse{
		Success:           true,
		Username:          username,
		Role:              req.Role,
		TemporaryPassword: generatedPassword,
		Message:           "Managed account created successfully",
	}, nil
}

// ==================== HELPER METHODS ====================

func (uc *ManagedAccountUsecaseWithOutbox) sendManagedAccountCreationNotification(ctx context.Context, email, username string, password string) error {
	uc.logger.Info("Sending managed account credentials",
		zap.String("email", email),
		zap.String("username", username))

	return uc.notificationService.SendManagedAccountCreationEmail(ctx, email, username, password)
}
