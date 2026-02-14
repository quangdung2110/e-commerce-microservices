package common

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
)

// UserService defines interface for user-related operations (external service)
type UserService interface {
	GetUserByID(ctx context.Context, userID uuid.UUID) (*UserDTO, error)
	GetUserByEmail(ctx context.Context, email string) (*UserDTO, error)
	GetUserByPhone(ctx context.Context, phone string) (*UserDTO, error)
	GetUserByIdentifier(ctx context.Context, identifier string) (*UserDTO, error)
	GetUserByUsername(ctx context.Context, username string) (*UserDTO, error)
	CreateUser(ctx context.Context, req *CreateUserRequest) (*UserDTO, error)
	UpdateUserPassword(ctx context.Context, userID uuid.UUID, passwordHash string) error
	IsUserActive(ctx context.Context, userID uuid.UUID) (bool, error)
}

// NotificationService defines interface for notification operations (external service)
type NotificationService interface {
	SendEmailOTP(ctx context.Context, email string, otp string, expiresIn time.Duration) error
	SendSMSOTP(ctx context.Context, phone string, otp string, expiresIn time.Duration) error
	SendPasswordResetEmail(ctx context.Context, email string, resetToken string) error
	SendPasswordChangedNotification(ctx context.Context, email string) error
	SendWelcomeEmail(ctx context.Context, email string, name string) error
	SendManagedAccountCreationEmail(ctx context.Context, email string, username string, tempPassword string) error
}

// EventPublisher defines interface for publishing domain events
type EventPublisher interface {
	PublishUserLoggedIn(ctx context.Context, event *UserLoggedInEvent) error
	PublishUserLoggedOut(ctx context.Context, event *UserLoggedOutEvent) error
	PublishUserRegistered(ctx context.Context, event *UserRegisteredEvent) error
	PublishPasswordChanged(ctx context.Context, event *PasswordChangedEvent) error
	PublishPasswordResetRequested(ctx context.Context, event *PasswordResetRequestedEvent) error
	PublishManagedAccountCreated(ctx context.Context, event *ManagedAccountCreatedEvent) error
	PublishSuspiciousActivity(ctx context.Context, event *SuspiciousActivityEvent) error
}

// TokenGenerator defines interface for token generation
type TokenGenerator interface {
	GenerateAccessToken(claims *entity.TokenClaims) (string, error)
	GenerateRefreshToken() (string, error)
	ValidateAccessToken(token string) (*entity.TokenClaims, error)
}

// PasswordEncoder defines interface for password hashing and verification
type PasswordEncoder interface {
	Hash(password string) (string, error)
	Verify(password, hash string) (bool, error)
}
