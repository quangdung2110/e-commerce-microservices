package external

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
)

// UserServiceClient implements UserService interface
type UserServiceClient struct {
	baseURL    string
	httpClient *http.Client
	logger     *zap.Logger
}

// NewUserServiceClient creates new user service client
func NewUserServiceClient(baseURL string, logger *zap.Logger) common.UserService {
	return &UserServiceClient{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
		logger: logger,
	}
}

// GetUserByIdentifier retrieves user by email/phone/username
func (c *UserServiceClient) GetUserByIdentifier(ctx context.Context, identifier string) (*common.UserDTO, error) {
	url := fmt.Sprintf("%s/api/v1/users/by-identifier?identifier=%s", c.baseURL, identifier)

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		c.logger.Error("Failed to get user by identifier", zap.Error(err))
		return nil, serr.ErrUserServiceError
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, serr.ErrUserNotFound
	}

	if resp.StatusCode != http.StatusOK {
		return nil, serr.ErrUserServiceError
	}

	var result struct {
		Success bool            `json:"success"`
		Data    *common.UserDTO `json:"data"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("failed to decode response: %w", err)
	}

	return result.Data, nil
}

// GetUserByID retrieves user by ID
func (c *UserServiceClient) GetUserByID(ctx context.Context, userID uuid.UUID) (*common.UserDTO, error) {
	url := fmt.Sprintf("%s/api/v1/users/%s", c.baseURL, userID.String())

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		c.logger.Error("Failed to get user by ID", zap.Error(err))
		return nil, serr.ErrUserServiceError
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, serr.ErrUserNotFound
	}

	if resp.StatusCode != http.StatusOK {
		return nil, serr.ErrUserServiceError
	}

	var result struct {
		Success bool            `json:"success"`
		Data    *common.UserDTO `json:"data"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("failed to decode response: %w", err)
	}

	return result.Data, nil
}

// CreateUser creates a new user
func (c *UserServiceClient) CreateUser(ctx context.Context, req *common.CreateUserRequest) (*common.UserDTO, error) {
	url := fmt.Sprintf("%s/api/v1/users", c.baseURL)

	jsonData, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewBuffer(jsonData))
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		c.logger.Error("Failed to create user", zap.Error(err))
		return nil, serr.ErrUserServiceError
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	if resp.StatusCode == http.StatusConflict {
		// User already exists
		return nil, serr.ErrEmailAlreadyExists
	}

	if resp.StatusCode != http.StatusCreated {
		c.logger.Error("Failed to create user",
			zap.Int("status", resp.StatusCode),
			zap.String("body", string(body)))
		return nil, serr.ErrUserServiceError
	}

	var result struct {
		Success bool            `json:"success"`
		Data    *common.UserDTO `json:"data"`
	}

	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("failed to decode response: %w", err)
	}

	return result.Data, nil
}

// UpdateUserPassword updates user password
func (c *UserServiceClient) UpdateUserPassword(ctx context.Context, userID uuid.UUID, newPasswordHash string) error {
	url := fmt.Sprintf("%s/api/v1/users/%s/password", c.baseURL, userID.String())

	payload := map[string]string{
		"password_hash": newPasswordHash,
	}

	jsonData, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("failed to marshal request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, "PUT", url, bytes.NewBuffer(jsonData))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		c.logger.Error("Failed to update password", zap.Error(err))
		return serr.ErrUserServiceError
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return serr.ErrUserServiceError
	}

	return nil
}

// GetUserByEmail retrieves user by email
func (c *UserServiceClient) GetUserByEmail(ctx context.Context, email string) (*common.UserDTO, error) {
	return c.GetUserByIdentifier(ctx, email)
}

// GetUserByPhone retrieves user by phone
func (c *UserServiceClient) GetUserByPhone(ctx context.Context, phone string) (*common.UserDTO, error) {
	return c.GetUserByIdentifier(ctx, phone)
}

// GetUserByUsername retrieves user by username
func (c *UserServiceClient) GetUserByUsername(ctx context.Context, username string) (*common.UserDTO, error) {
	return c.GetUserByIdentifier(ctx, username)
}

// IsUserActive checks if user is active
func (c *UserServiceClient) IsUserActive(ctx context.Context, userID uuid.UUID) (bool, error) {
	user, err := c.GetUserByID(ctx, userID)
	if err != nil {
		return false, err
	}
	return user.Active, nil
}

// LockUserAccount locks a user account (not implemented in external client - handled by Auth DB directly)
func (c *UserServiceClient) LockUserAccount(ctx context.Context, userID uuid.UUID, lockedAt time.Time) error {
	// This is a stub - lock/unlock is handled by Auth Service directly via its own DB
	c.logger.Info("LockUserAccount called on external client (stub)", zap.String("user_id", userID.String()))
	return nil
}

// UnlockUserAccount unlocks a user account (not implemented in external client - handled by Auth DB directly)
func (c *UserServiceClient) UnlockUserAccount(ctx context.Context, userID uuid.UUID) error {
	// This is a stub - lock/unlock is handled by Auth Service directly via its own DB
	c.logger.Info("UnlockUserAccount called on external client (stub)", zap.String("user_id", userID.String()))
	return nil
}
