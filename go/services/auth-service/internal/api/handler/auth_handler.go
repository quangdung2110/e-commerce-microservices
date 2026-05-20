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
	authService *service.AuthService
	jwtService  *service.JWTService
	logger      *zap.Logger
}

func NewAuthHandler(authService *service.AuthService, jwtService *service.JWTService, logger *zap.Logger) *AuthHandler {
	return &AuthHandler{
		authService: authService,
		jwtService:  jwtService,
		logger:      logger.With(zap.String("handler", "AuthHandler")),
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
func (h *AuthHandler) Refresh(c *gin.Context) {
	refreshToken, err := c.Cookie(RefreshTokenCookieName)
	if err != nil || refreshToken == "" {
		h.logger.Warn("Refresh token missing from cookie")
		handleServiceError(c, apperrors.NewAppError("BAD_REQUEST", "Refresh token required", http.StatusBadRequest, nil))
		return
	}

	jti, _ := c.Get("jti")
	exp, _ := c.Get("exp")
	expTime, _ := exp.(time.Time)

	refreshResp, err := h.authService.RefreshTokens(
		c.Request.Context(),
		refreshToken,
		jti.(string),
		expTime,
	)
	if err != nil {
		h.logger.Info("Token refresh failed", zap.Error(err))
		handleServiceError(c, err)
		return
	}

	c.SetCookie(
		RefreshTokenCookieName,
		refreshResp.NewRefreshToken,
		int(time.Until(refreshResp.ExpiresAt).Seconds()),
		"/",
		"",
		true,
		true,
	)

	h.logger.Info("Tokens refreshed successfully")

	c.JSON(http.StatusOK, response.RefreshResponse{
		AccessToken: refreshResp.AccessToken,
		ExpiresIn:   int64(time.Until(refreshResp.ExpiresAt).Seconds()),
		TokenType:   "Bearer",
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
