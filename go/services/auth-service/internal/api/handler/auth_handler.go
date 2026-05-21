package handler

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/dto/request"
	"github.com/ecommerce/auth-service/internal/api/dto/response"
	"github.com/ecommerce/auth-service/internal/api/dto/svc"
	apperrors "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

const (
	RefreshTokenCookieName = "refresh_token"
)

type AuthHandler struct {
	authService    *service.AuthService
	refreshService *service.RefreshService
	jwtService     *service.JWTService
	logger         *zap.Logger
}

func NewAuthHandler(
	authService *service.AuthService,
	refreshService *service.RefreshService,
	jwtService *service.JWTService,
	logger *zap.Logger,
) *AuthHandler {
	return &AuthHandler{
		authService:    authService,
		refreshService: refreshService,
		jwtService:     jwtService,
		logger:         logger.With(zap.String("handler", "AuthHandler")),
	}
}

// Login handles POST /api/v1/auth/public/login
func (h *AuthHandler) Login(c *gin.Context) {
	var req request.LoginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		handleServiceError(c, apperrors.NewAppError("BAD_REQUEST", err.Error(), http.StatusBadRequest, nil))
		return
	}

	ipAddress := c.ClientIP()
	deviceInfo := c.GetHeader("User-Agent")
	deviceID := c.GetHeader("X-Device-ID")

	if deviceID == "" {
		deviceID = uuid.New().String()
	} else {
		if _, err := uuid.Parse(deviceID); err != nil {
			h.logger.Warn("Invalid device ID format received, generating new one")
			deviceID = uuid.New().String()
		}
	}

	loginResp, err := h.authService.Login(c.Request.Context(), &svc.LoginRequest{
		Email:      req.Email,
		Password:   req.Password,
		DeviceID:   deviceID,
		DeviceInfo: deviceInfo,
		IPAddress:  ipAddress,
	})
	if err != nil {
		h.logger.Info("Login failed",
			zap.String("email", maskEmailForLog(req.Email)),
			zap.String("ip", ipAddress),
			zap.String("error", err.Error()),
		)
		handleServiceError(c, err)
		return
	}

	refreshToken, expiresAt, err := h.authService.GetRefreshTokenForCookie(c.Request.Context(), loginResp.User.ID)
	if err != nil {
		h.logger.Error("Failed to generate refresh token",
			zap.String("user_id", loginResp.User.ID),
			zap.Error(err),
		)
		handleServiceError(c, apperrors.ErrInternal)
		return
	}

	c.SetCookie(
		RefreshTokenCookieName,
		refreshToken,
		int(time.Until(expiresAt).Seconds()),
		"/",
		"",
		true,
		true,
	)

	h.logger.Info("Login successful",
		zap.String("user_id", loginResp.User.ID),
		zap.String("device_id", deviceID),
	)

	c.JSON(http.StatusOK, response.LoginResponse{
		AccessToken: loginResp.AccessToken,
		ExpiresIn:   loginResp.ExpiresIn,
		TokenType:   loginResp.TokenType,
		User: response.UserResponse{
			ID:     loginResp.User.ID,
			Email:  loginResp.User.Email,
			Roles:  loginResp.User.Roles,
			Status: loginResp.User.Status,
		},
	})
}

// Logout handles POST /api/v1/auth/logout
func (h *AuthHandler) Logout(c *gin.Context) {
	userID, exists := c.Get("user_id")
	if !exists {
		handleServiceError(c, apperrors.ErrUnauthorized)
		return
	}

	jti, _ := c.Get("jti")
	exp, _ := c.Get("exp")
	expTime, _ := exp.(time.Time)

	refreshToken, _ := c.Cookie(RefreshTokenCookieName)

	err := h.authService.Logout(c.Request.Context(), &svc.LogoutRequest{
		UserID:         userID.(string),
		RefreshToken:   refreshToken,
		AccessTokenJTI: jti.(string),
		AccessTokenExp: expTime,
	})
	if err != nil {
		h.logger.Error("Logout failed",
			zap.String("user_id", userID.(string)),
			zap.Error(err),
		)
		handleServiceError(c, err)
		return
	}

	c.SetCookie(
		RefreshTokenCookieName,
		"",
		-1,
		"/",
		"",
		true,
		true,
	)

	h.logger.Info("User logged out",
		zap.String("user_id", userID.(string)),
	)

	c.JSON(http.StatusOK, response.MessageResponse{
		Message: "Logged out successfully",
	})
}

// Refresh handles POST /api/v1/auth/refresh
// Uses RefreshService with Grace Period logic
func (h *AuthHandler) Refresh(c *gin.Context) {
	// Get refresh token from cookie or header
	refreshToken := c.GetHeader("X-Refresh-Token")
	if refreshToken == "" {
		refreshToken, _ = c.Cookie(RefreshTokenCookieName)
	}

	if refreshToken == "" {
		h.logger.Warn("Refresh token missing")
		handleServiceError(c, apperrors.NewAppError("BAD_REQUEST", "Refresh token required", http.StatusBadRequest, nil))
		return
	}

	// Get current access token info for denylist
	accessTokenJTI, exists := c.Get("jti")
	if !exists || accessTokenJTI == "" {
		h.logger.Warn("Access token JTI missing")
		handleServiceError(c, apperrors.ErrUnauthorized)
		return
	}

	accessTokenExp, exists := c.Get("exp")
	if !exists {
		h.logger.Warn("Access token expiry missing")
		handleServiceError(c, apperrors.ErrUnauthorized)
		return
	}
	expTime, ok := accessTokenExp.(time.Time)
	if !ok {
		h.logger.Warn("Invalid access token expiry format")
		handleServiceError(c, apperrors.ErrInternal)
		return
	}

	// Use RefreshService with Grace Period
	refreshResp, err := h.refreshService.RefreshTokens(
		c.Request.Context(),
		refreshToken,
		accessTokenJTI.(string),
		expTime,
	)
	if err != nil {
		h.logger.Info("Token refresh failed", zap.Error(err))
		handleServiceError(c, err)
		return
	}

	// Update refresh token cookie
	c.SetCookie(
		RefreshTokenCookieName,
		refreshResp.NewRefreshToken,
		int(time.Until(time.Now().Add(time.Duration(refreshResp.ExpiresIn)*time.Second)).Seconds()),
		"/",
		"",
		true,
		true,
	)

	h.logger.Info("Tokens refreshed successfully with Grace Period")

	c.JSON(http.StatusOK, response.RefreshResponse{
		AccessToken: refreshResp.AccessToken,
		ExpiresIn:   refreshResp.ExpiresIn,
		TokenType:   refreshResp.TokenType,
	})
}

// RevokeSession handles POST /api/v1/auth/revoke-session
// Revokes a specific session/device
func (h *AuthHandler) RevokeSession(c *gin.Context) {
	userID, exists := c.Get("user_id")
	if !exists {
		handleServiceError(c, apperrors.ErrUnauthorized)
		return
	}

	var req request.RevokeSessionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		handleServiceError(c, apperrors.NewAppError("BAD_REQUEST", err.Error(), http.StatusBadRequest, nil))
		return
	}

	err := h.refreshService.RevokeAllUserTokens(c.Request.Context(), userID.(string), req.Reason)
	if err != nil {
		h.logger.Error("Failed to revoke session",
			zap.String("user_id", userID.(string)),
			zap.Error(err),
		)
		handleServiceError(c, err)
		return
	}

	c.JSON(http.StatusOK, response.MessageResponse{
		Message: "Session revoked successfully",
	})
}

func maskEmailForLog(email string) string {
	if len(email) < 4 {
		return "***"
	}
	atIndex := -1
	for i, c := range email {
		if c == '@' {
			atIndex = i
			break
		}
	}
	if atIndex <= 1 {
		return email[:1] + "***"
	}
	return email[:2] + "***" + email[atIndex:]
}
