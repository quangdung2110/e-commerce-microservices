package v1_handler

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	v1 "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/authentication"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
)

// AuthenticationHandler handles authentication-related HTTP requests
type AuthenticationHandler struct {
	loginUsecase        *authentication.LoginUsecase
	logoutUsecase       *authentication.LogoutUsecase
	refreshTokenUsecase *authentication.RefreshTokenUsecase
	logger              *zap.Logger
}

// NewAuthenticationHandler creates new authentication handler
func NewAuthenticationHandler(
	loginUsecase *authentication.LoginUsecase,
	logoutUsecase *authentication.LogoutUsecase,
	refreshTokenUsecase *authentication.RefreshTokenUsecase,
	logger *zap.Logger,
) *AuthenticationHandler {
	return &AuthenticationHandler{
		loginUsecase:        loginUsecase,
		logoutUsecase:       logoutUsecase,
		refreshTokenUsecase: refreshTokenUsecase,
		logger:              logger,
	}
}

// Login handles user login
// @Summary User login
// @Description Authenticate user and create session
// @Tags authentication
// @Accept json
// @Produce json
// @Param request body dto.LoginRequest true "Login request"
// @Success 200 {object} dto.LoginResponse
// @Failure 400 {object} v1.ErrorResponse
// @Failure 401 {object} v1.ErrorResponse
// @Failure 429 {object} v1.ErrorResponse
// @Router /api/v1/auth/login [post]
func (h *AuthenticationHandler) Login(c *gin.Context) {
	var req dto.LoginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request body", err)
		return
	}

	// Get request context
	reqCtx := extractRequestContext(c)

	// Execute login
	resp, err := h.loginUsecase.Execute(c.Request.Context(), &req, reqCtx)
	if err != nil {
		h.handleAuthError(c, err)
		return
	}

	// Set refresh token in HTTP-only cookie
	c.SetCookie(
		"refresh_token",
		resp.RefreshToken,
		int((90 * 24 * time.Hour).Seconds()), // 90 days
		"/",
		"",
		true, // Secure (HTTPS only in production)
		true, // HttpOnly
	)

	// Remove refresh token from response body (already in cookie)
	resp.RefreshToken = ""

	v1.SendSuccessResponse(c, http.StatusOK, resp)
}

// Logout handles user logout (current session)
// @Summary Logout current session
// @Description Revoke current session and blacklist access token
// @Tags authentication
// @Accept json
// @Produce json
// @Success 200 {object} v1.SuccessResponse
// @Failure 401 {object} v1.ErrorResponse
// @Router /api/v1/auth/logout [post]
func (h *AuthenticationHandler) Logout(c *gin.Context) {
	// Get user ID and session ID from context (set by auth middleware)
	userID, exists := c.Get("user_id")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "User ID not found", nil)
		return
	}

	sessionID, exists := c.Get("session_id")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "Session ID not found", nil)
		return
	}

	jti, exists := c.Get("jti")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "JTI not found", nil)
		return
	}

	// Get client IP
	clientIP := c.ClientIP()

	// Execute logout
	req := &dto.LogoutRequest{
		SessionID: sessionID.(uuid.UUID),
		JTI:       jti.(string),
	}

	if err := h.logoutUsecase.Execute(c.Request.Context(), userID.(uuid.UUID), req, clientIP); err != nil {
		h.handleAuthError(c, err)
		return
	}

	// Clear refresh token cookie
	c.SetCookie("refresh_token", "", -1, "/", "", true, true)

	v1.SendSuccessResponse(c, http.StatusOK, gin.H{
		"message": "Logged out successfully",
	})
}

// LogoutAll handles logout from all devices
// @Summary Logout all sessions
// @Description Revoke all user sessions except current one
// @Tags authentication
// @Accept json
// @Produce json
// @Success 200 {object} v1.SuccessResponse
// @Failure 401 {object} v1.ErrorResponse
// @Router /api/v1/auth/logout-all [post]
func (h *AuthenticationHandler) LogoutAll(c *gin.Context) {
	// Get user ID from context
	userID, exists := c.Get("user_id")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "User ID not found", nil)
		return
	}

	// Get client IP
	clientIP := c.ClientIP()

	err := h.logoutUsecase.ExecuteAll(c.Request.Context(), userID.(uuid.UUID), clientIP)
	if err != nil {
		h.handleAuthError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, gin.H{
		"message": "Logged out from all devices successfully",
	})
}

// RefreshToken handles access token refresh
// @Summary Refresh access token
// @Description Get new access token using refresh token
// @Tags authentication
// @Accept json
// @Produce json
// @Success 200 {object} dto.RefreshTokenResponse
// @Failure 401 {object} v1.ErrorResponse
// @Router /api/v1/auth/refresh [post]
func (h *AuthenticationHandler) RefreshToken(c *gin.Context) {
	// Try to get refresh token from cookie first
	refreshToken, err := c.Cookie("refresh_token")
	if err != nil {
		// Fallback to request body
		var req dto.RefreshTokenRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_REQUEST", "Refresh token not found", err)
			return
		}
		refreshToken = req.RefreshToken
	}

	// Get request context
	reqCtx := extractRequestContext(c)

	// Execute refresh
	refreshReq := &dto.RefreshTokenRequest{RefreshToken: refreshToken}
	resp, err := h.refreshTokenUsecase.Execute(c.Request.Context(), refreshReq, reqCtx)
	if err != nil {
		h.handleAuthError(c, err)
		return
	}

	// Set new refresh token in cookie
	c.SetCookie(
		"refresh_token",
		resp.RefreshToken,
		int((90 * 24 * time.Hour).Seconds()),
		"/",
		"",
		true,
		true,
	)

	// Remove refresh token from response body
	resp.RefreshToken = ""

	v1.SendSuccessResponse(c, http.StatusOK, resp)
}

// handleAuthError handles authentication-related errors
func (h *AuthenticationHandler) handleAuthError(c *gin.Context, err error) {
	h.logger.Error("Authentication error", zap.Error(err))

	// Check if it's an AppError
	if appErr := serr.GetAppError(err); appErr != nil {
		v1.SendErrorResponse(c, appErr.HTTPStatus, appErr.Code, appErr.Message, err)
		return
	}

	// Default to internal server error
	v1.SendErrorResponse(c, http.StatusInternalServerError, "INTERNAL_ERROR", "An error occurred", err)
}
