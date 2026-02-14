package external

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
)

// NotificationServiceClient implements NotificationService interface
type NotificationServiceClient struct {
	baseURL    string
	httpClient *http.Client
	logger     *zap.Logger
}

// NewNotificationServiceClient creates new notification service client
func NewNotificationServiceClient(baseURL string, logger *zap.Logger) common.NotificationService {
	return &NotificationServiceClient{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
		logger: logger,
	}
}

// SendEmailOTP sends OTP via email
func (c *NotificationServiceClient) SendEmailOTP(ctx context.Context, email string, otp string, expiresIn time.Duration) error {
	url := fmt.Sprintf("%s/api/v1/notifications/otp/email", c.baseURL)

	payload := map[string]interface{}{
		"email":      email,
		"code":       otp,
		"expires_in": int(expiresIn.Seconds()),
	}

	return c.sendRequest(ctx, url, payload)
}

// SendSMSOTP sends OTP via SMS
func (c *NotificationServiceClient) SendSMSOTP(ctx context.Context, phone string, otp string, expiresIn time.Duration) error {
	url := fmt.Sprintf("%s/api/v1/notifications/otp/sms", c.baseURL)

	payload := map[string]interface{}{
		"phone":      phone,
		"code":       otp,
		"expires_in": int(expiresIn.Seconds()),
	}

	return c.sendRequest(ctx, url, payload)
}

// SendPasswordResetEmail sends password reset link
func (c *NotificationServiceClient) SendPasswordResetEmail(ctx context.Context, email string, resetToken string) error {
	url := fmt.Sprintf("%s/api/v1/notifications/password-reset", c.baseURL)

	payload := map[string]interface{}{
		"email":       email,
		"reset_token": resetToken,
	}

	return c.sendRequest(ctx, url, payload)
}

// SendPasswordChangedNotification sends password changed notification
func (c *NotificationServiceClient) SendPasswordChangedNotification(ctx context.Context, email string) error {
	url := fmt.Sprintf("%s/api/v1/notifications/password-changed", c.baseURL)

	payload := map[string]interface{}{
		"email": email,
	}

	return c.sendRequest(ctx, url, payload)
}

// SendWelcomeEmail sends welcome email
func (c *NotificationServiceClient) SendWelcomeEmail(ctx context.Context, email string, name string) error {
	url := fmt.Sprintf("%s/api/v1/notifications/welcome", c.baseURL)

	payload := map[string]interface{}{
		"email": email,
		"name":  name,
	}

	return c.sendRequest(ctx, url, payload)
}

// SendManagedAccountCreationEmail sends managed account creation email
func (c *NotificationServiceClient) SendManagedAccountCreationEmail(ctx context.Context, email string, username string, tempPassword string) error {
	url := fmt.Sprintf("%s/api/v1/notifications/managed-account", c.baseURL)

	payload := map[string]interface{}{
		"email":         email,
		"username":      username,
		"temp_password": tempPassword,
	}

	return c.sendRequest(ctx, url, payload)
}

// sendRequest is a helper method to send HTTP requests
func (c *NotificationServiceClient) sendRequest(ctx context.Context, url string, payload map[string]interface{}) error {
	jsonData, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("failed to marshal request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewBuffer(jsonData))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		c.logger.Error("Failed to send notification", zap.Error(err), zap.String("url", url))
		return serr.ErrNotificationServiceError
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusAccepted {
		c.logger.Error("Notification service returned error",
			zap.Int("status", resp.StatusCode),
			zap.String("url", url))
		return serr.ErrNotificationServiceError
	}

	c.logger.Debug("Notification sent successfully", zap.String("url", url))
	return nil
}
