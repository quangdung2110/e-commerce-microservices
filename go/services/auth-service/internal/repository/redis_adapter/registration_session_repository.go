package redis_adapter

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"go.uber.org/zap"
)

// RegistrationSessionRepository manages registration sessions in Redis
type RegistrationSessionRepository struct {
	client *redis.Client
	logger *zap.Logger
}

// NewRegistrationSessionRepository creates new repository
func NewRegistrationSessionRepository(client *redis.Client, logger *zap.Logger) repository.RegistrationSessionRepository {
	return &RegistrationSessionRepository{
		client: client,
		logger: logger,
	}
}

// Create creates a new registration session
func (r *RegistrationSessionRepository) Create(ctx context.Context, email string, ttl time.Duration) (*entity.RegistrationSession, error) {
	sessionID := uuid.New().String()
	now := time.Now().UTC()

	session := &entity.RegistrationSession{
		SessionID:     sessionID,
		Email:         &email,
		EmailVerified: false,
		CurrentStep:   1,
		CreatedAt:     now.Unix(),
		ExpiresAt:     now.Add(ttl).Unix(),
	}

	key := r.buildKey(sessionID)
	data, err := json.Marshal(session)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal session: %w", err)
	}

	if err := r.client.Set(ctx, key, data, ttl).Err(); err != nil {
		return nil, fmt.Errorf("failed to store session: %w", err)
	}

	r.logger.Debug("Registration session created",
		zap.String("session_id", sessionID),
		zap.String("email", email))

	return session, nil
}

// Get retrieves a registration session
func (r *RegistrationSessionRepository) Get(ctx context.Context, sessionID string) (*entity.RegistrationSession, error) {
	key := r.buildKey(sessionID)

	data, err := r.client.Get(ctx, key).Result()
	if err == redis.Nil {
		return nil, fmt.Errorf("registration session not found or expired")
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get session: %w", err)
	}

	var session entity.RegistrationSession
	if err := json.Unmarshal([]byte(data), &session); err != nil {
		return nil, fmt.Errorf("failed to unmarshal session: %w", err)
	}

	return &session, nil
}

// Update updates a registration session
func (r *RegistrationSessionRepository) Update(ctx context.Context, session *entity.RegistrationSession) error {
	key := r.buildKey(session.SessionID)

	data, err := json.Marshal(session)
	if err != nil {
		return fmt.Errorf("failed to marshal session: %w", err)
	}

	// Get remaining TTL
	ttl, err := r.client.TTL(ctx, key).Result()
	if err != nil || ttl < 0 {
		ttl = 30 * time.Minute
	}

	if err := r.client.Set(ctx, key, data, ttl).Err(); err != nil {
		return fmt.Errorf("failed to update session: %w", err)
	}

	return nil
}

// Delete removes a registration session
func (r *RegistrationSessionRepository) Delete(ctx context.Context, sessionID string) error {
	key := r.buildKey(sessionID)

	if err := r.client.Del(ctx, key).Err(); err != nil {
		return fmt.Errorf("failed to delete session: %w", err)
	}

	return nil
}

// buildKey builds Redis key for registration session
func (r *RegistrationSessionRepository) buildKey(sessionID string) string {
	return fmt.Sprintf("registration:session:%s", sessionID)
}
