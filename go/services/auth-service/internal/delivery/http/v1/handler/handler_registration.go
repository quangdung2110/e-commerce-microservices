package v1_handler

import (
	"net/http"

	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	v1 "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/registration"
)

// RegistrationHandler handles registration requests (multi-step flow + managed accounts)
type RegistrationHandler struct {
	registrationUsecase *registration.PersonalRegistrationUsecase
	managedUsecase      *registration.ManagedAccountUsecase
	logger              *zap.Logger
}

// NewRegistrationHandler creates new registration handler
func NewRegistrationHandler(
	registrationUsecase *registration.PersonalRegistrationUsecase,
	managedUsecase *registration.ManagedAccountUsecase,
	logger *zap.Logger,
) *RegistrationHandler {
	return &RegistrationHandler{
		registrationUsecase: registrationUsecase,
		managedUsecase:      managedUsecase,
		logger:              logger,
	}
}

// ========================= USER REGISTRATION =========================

// StartRegistration godoc
// @Summary      Start registration with email
// @Description  Step 1: User provides email, system sends OTP
// @Tags         registration
// @Accept       json
// @Produce      json
// @Param        request body dto.StartRegistrationRequest true "Start registration request"
// @Success      200 {object} dto.StartRegistrationResponse
// @Failure      400 {object} ErrorResponse
// @Failure      409 {object} ErrorResponse "Email already exists"
// @Failure      429 {object} ErrorResponse "Rate limit exceeded"
// @Router       /api/v1/register/start [post]
func (h *RegistrationHandler) StartRegistration(c *gin.Context) {
	var req dto.StartRegistrationRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_INPUT", err.Error(), err)
		return
	}

	reqCtx := extractRequestContext(c)

	resp, err := h.registrationUsecase.StartRegistration(c.Request.Context(), &req, reqCtx)
	if err != nil {
		h.handleRegistrationError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, resp)
}

// VerifyEmailOTP godoc
// @Summary      Verify email OTP
// @Description  Step 2: Verify OTP code sent to email
// @Tags         registration
// @Accept       json
// @Produce      json
// @Param        request body dto.VerifyEmailOTPRequest true "Verify email OTP request"
// @Success      200 {object} dto.VerifyEmailOTPResponse
// @Failure      400 {object} ErrorResponse
// @Failure      404 {object} ErrorResponse "OTP not found or expired"
// @Router       /api/v1/register/verify-email [post]
func (h *RegistrationHandler) VerifyEmailOTP(c *gin.Context) {
	var req dto.VerifyEmailOTPRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_INPUT", err.Error(), err)
		return
	}

	resp, err := h.registrationUsecase.VerifyEmailOTP(c.Request.Context(), &req)
	if err != nil {
		h.handleRegistrationError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, resp)
}

// AddPhoneNumber godoc
// @Summary      Add phone number
// @Description  Step 3: User provides phone number, system sends OTP
// @Tags         registration
// @Accept       json
// @Produce      json
// @Param        request body dto.AddPhoneNumberRequest true "Add phone number request"
// @Success      200 {object} dto.AddPhoneNumberResponse
// @Failure      400 {object} ErrorResponse
// @Failure      409 {object} ErrorResponse "Phone already exists"
// @Failure      429 {object} ErrorResponse "Rate limit exceeded"
// @Router       /api/v1/register/add-phone [post]
func (h *RegistrationHandler) AddPhoneNumber(c *gin.Context) {
	var req dto.AddPhoneNumberRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_INPUT", err.Error(), err)
		return
	}

	reqCtx := extractRequestContext(c)

	resp, err := h.registrationUsecase.AddPhoneNumber(c.Request.Context(), &req, reqCtx)
	if err != nil {
		h.handleRegistrationError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, resp)
}

// VerifyPhoneOTP godoc
// @Summary      Verify phone OTP
// @Description  Step 4: Verify OTP code sent to phone
// @Tags         registration
// @Accept       json
// @Produce      json
// @Param        request body dto.VerifyPhoneOTPRequest true "Verify phone OTP request"
// @Success      200 {object} dto.VerifyPhoneOTPResponse
// @Failure      400 {object} ErrorResponse
// @Failure      404 {object} ErrorResponse "OTP not found or expired"
// @Router       /api/v1/register/verify-phone [post]
func (h *RegistrationHandler) VerifyPhoneOTP(c *gin.Context) {
	var req dto.VerifyPhoneOTPRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_INPUT", err.Error(), err)
		return
	}

	resp, err := h.registrationUsecase.VerifyPhoneOTP(c.Request.Context(), &req)
	if err != nil {
		h.handleRegistrationError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, resp)
}

// CompleteRegistration godoc
// @Summary      Complete registration
// @Description  Step 5: Set password and create account
// @Tags         registration
// @Accept       json
// @Produce      json
// @Param        request body dto.CompleteRegistrationRequest true "Complete registration request"
// @Success      201 {object} dto.CompleteRegistrationResponse
// @Failure      400 {object} ErrorResponse
// @Failure      404 {object} ErrorResponse "Registration session not found or expired"
// @Router       /api/v1/register/complete [post]
func (h *RegistrationHandler) CompleteRegistration(c *gin.Context) {
	var req dto.CompleteRegistrationRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_INPUT", err.Error(), err)
		return
	}

	reqCtx := extractRequestContext(c)

	resp, err := h.registrationUsecase.CompleteRegistration(c.Request.Context(), &req, reqCtx)
	if err != nil {
		h.handleRegistrationError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusCreated, resp)
}

// ========================= MANAGED ACCOUNT CREATION =========================

// CreateManagedAccount godoc
// @Summary      Create managed account
// @Description  Admin or seller admin creates a managed account (username-based, no OTP)
// @Tags         registration,admin
// @Security     BearerAuth
// @Accept       json
// @Produce      json
// @Param        request body dto.CreateManagedAccountRequest true "Create managed account request"
// @Success      201 {object} dto.CreateManagedAccountResponse
// @Failure      400 {object} ErrorResponse
// @Failure      401 {object} ErrorResponse "Unauthorized"
// @Failure      403 {object} ErrorResponse "Insufficient permission"
// @Failure      409 {object} ErrorResponse "Username already exists"
// @Router       /api/v1/admin/managed-accounts [post]
func (h *RegistrationHandler) CreateManagedAccount(c *gin.Context) {
	var req dto.CreateManagedAccountRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_INPUT", err.Error(), err)
		return
	}

	// Get creator info from context (set by AuthMiddleware)
	userIDStr, exists := c.Get("user_id")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "User ID not found in context", nil)
		return
	}
	userID, err := uuid.Parse(userIDStr.(string))
	if err != nil {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "Invalid user ID", nil)
		return
	}

	userRoleStr, _ := c.Get("user_role")
	accountTypeStr, _ := c.Get("account_type")

	creator := &common.UserDTO{
		UserID:      userID,
		Role:        entity.UserRole(userRoleStr.(string)),
		AccountType: entity.AccountType(accountTypeStr.(string)),
	}

	resp, err := h.managedUsecase.CreateManagedAccount(c.Request.Context(), &req, creator)
	if err != nil {
		h.handleRegistrationError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusCreated, resp)
}

// ========================= HELPER FUNCTIONS =========================

func extractRequestContext(c *gin.Context) *common.RequestContext {
	ipAddress := c.ClientIP()
	userAgent := c.GetHeader("User-Agent")

	deviceID := c.GetHeader("X-Device-ID")
	var deviceIDPtr *string
	if deviceID != "" {
		deviceIDPtr = &deviceID
	}

	return &common.RequestContext{
		DeviceInfo: entity.DeviceInfo{
			DeviceID:  deviceIDPtr,
			IPAddress: ipAddress,
			UserAgent: userAgent,
		},
		Timestamp: time.Now(),
	}
}

// handleError converts service errors to HTTP responses
func (h *RegistrationHandler) handleRegistrationError(c *gin.Context, err error) {
	// Check if it's an AppError
	if appErr := serr.GetAppError(err); appErr != nil {
		v1.SendErrorResponse(c, appErr.HTTPStatus, appErr.Code, appErr.Message, appErr)
		return
	}
	// Default to internal server error
	v1.SendErrorResponse(c, http.StatusInternalServerError, "INTERNAL_ERROR", "An internal error occurred", err)
}
