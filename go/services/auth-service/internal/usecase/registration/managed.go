package registration

import (
	"context"
	"fmt"
	"strings"
	"time"

	"go.uber.org/zap"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/utils"
)

// ManagedAccountUsecase handles MANAGED account creation by admins/sellers
type ManagedAccountUsecase struct {
	userService         common.UserService
	notificationService common.NotificationService
	eventPublisher      common.EventPublisher
	passwordEncoder     common.PasswordEncoder
	logger              *zap.Logger
}

// NewManagedAccountUsecase creates new managed account usecase
func NewManagedAccountUsecase(
	userService common.UserService,
	notificationService common.NotificationService,
	eventPublisher common.EventPublisher,
	passwordEncoder common.PasswordEncoder,
	logger *zap.Logger,
) *ManagedAccountUsecase {
	return &ManagedAccountUsecase{
		userService:         userService,
		notificationService: notificationService,
		eventPublisher:      eventPublisher,
		passwordEncoder:     passwordEncoder,
		logger:              logger,
	}
}

// CreateManagedAccount creates a managed account (by admin or seller admin)
func (uc *ManagedAccountUsecase) CreateManagedAccount(ctx context.Context, req *dto.CreateManagedAccountRequest, creator *common.UserDTO) (*dto.CreateManagedAccountResponse, error) {
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

	// Step 3: Validate email uniqueness (if provided)
	// var email *string
	// if req.Email != nil && *req.Email != "" {
	// 	emailLower := strings.ToLower(strings.TrimSpace(*req.Email))
	// 	email = &emailLower

	// 	exists, err := uc.userService.CheckEmailExists(ctx, *email)
	// 	if err != nil {
	// 		uc.logger.Error("Failed to check email existence", zap.Error(err))
	// 	}
	// 	if exists {
	// 		return nil, serr.ErrEmailAlreadyExists
	// 	}
	// }

	// Step 4: Validate phone uniqueness (if provided)
	// var phone *string
	// if req.PhoneNumber != nil && *req.PhoneNumber != "" {
	// 	phoneClean := strings.TrimSpace(*req.PhoneNumber)
	// 	phone = &phoneClean

	// 	exists, err := uc.userService.CheckPhoneExists(ctx, *phone)
	// 	if err != nil {
	// 		uc.logger.Error("Failed to check phone existence", zap.Error(err))
	// 	}
	// 	if exists {
	// 		return nil, serr.ErrPhoneAlreadyExists
	// 	}
	// }

	// Step 5: Generate temporary password if not provided
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

	// Step 6: Create user
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
		uc.logger.Error("Failed to create managed user", zap.Error(err))
		return nil, err
	}

	// Step 7: Send notification with credentials (send to the account creator)
	if err := uc.sendManagedAccountCreationNotification(ctx, creator.Email, username, generatedPassword); err != nil {
		uc.logger.Error("Failed to send managed account notification", zap.Error(err))
	}

	// Step 8: Publish event
	if err := uc.publishManagedAccountCreatedEvent(ctx, user.UserID, creator.UserID, user.Role); err != nil {
		uc.logger.Error("Failed to publish managed account created event", zap.Error(err))
	}

	uc.logger.Info("Managed account created",
		zap.String("user_id", user.UserID.String()),
		zap.String("username", username),
		zap.String("creator_id", creator.UserID.String()),
		zap.String("role", string(req.Role)))

	return &dto.CreateManagedAccountResponse{
		Success:           true,
		Username:          username,
		Role:              req.Role,
		TemporaryPassword: generatedPassword,
		Message:           "Managed account created successfully",
	}, nil
}

// ==================== HELPER METHODS ====================

func validateCreatorPermission(creator *common.UserDTO, targetRole entity.UserRole) error {
	// System admins can create any role (more specific only create supporter platform)
	if creator.Role == entity.RolePlatformAdmin {
		if targetRole != entity.RolePlatformSupporter {
			return serr.ErrUnauthorized.WithDetails("platform admin can only create platform supporter accounts")
		}
		return nil
	}

	// Regular admins can create staff and customers
	// if creator.Role == entity.RoleAdmin {
	// 	if targetRole == entity.RolePlatformAdmin || targetRole == entity.RoleAdmin {
	// 		return serr.ErrUnauthorized.WithDetails("admin cannot create other admin accounts")
	// 	}
	// 	return nil
	// }

	// Seller admins can only create seller staff
	if creator.Role == entity.RoleSellerOwner {
		if targetRole != entity.RoleSellerStaff {
			return serr.ErrUnauthorized.WithDetails("seller admin can only create seller staff accounts")
		}
		return nil
	}

	return serr.ErrUnauthorized.WithDetails("insufficient permission to create managed accounts")
}

func (uc *ManagedAccountUsecase) publishManagedAccountCreatedEvent(ctx context.Context, userID, creatorID uuid.UUID, role entity.UserRole) error {
	event := &common.ManagedAccountCreatedEvent{
		UserID:    userID,
		CreatorID: creatorID,
		Role:      string(role),
		Timestamp: time.Now(),
	}
	return uc.eventPublisher.PublishManagedAccountCreated(ctx, event)
}

func (uc *ManagedAccountUsecase) sendManagedAccountCreationNotification(ctx context.Context, email, username string, password string) error {

	uc.logger.Info("Sending managed account credentials",
		zap.String("email", email),
		zap.String("username", username))

	// TODO: Implement actual email sending via notification service
	return uc.notificationService.SendManagedAccountCreationEmail(ctx, email, username, password)
}
