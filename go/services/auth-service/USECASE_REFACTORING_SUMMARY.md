# Usecase Refactoring Complete ✅

## What Was Done

Successfully refactored all usecase files into feature-based directories following Clean Architecture principles.

## New Directory Structure

```
internal/usecase/
├── authentication/
│   ├── dto/
│   │   └── auth_dto.go              ✅ LoginRequest, LoginResponse, RefreshTokenRequest, LogoutRequest
│   ├── login.go                      ✅ LoginUsecase with all login logic & helpers
│   ├── logout.go                     ✅ LogoutUsecase (Logout, LogoutAll)
│   └── refresh.go                    ✅ RefreshTokenUsecase (Token refresh logic)
│
├── registration/
│   ├── dto/
│   │   └── registration_dto.go       ✅ All registration DTOs (personal & managed)
│   ├── personal.go                   ✅ PersonalRegistrationUsecase (5-step flow)
│   └── managed.go                    ✅ ManagedAccountUsecase (Admin creates accounts)
│
├── password/
│   ├── dto/
│   │   └── password_dto.go           ✅ ForgotPassword, ResetPassword, ChangePassword DTOs
│   ├── reset.go                      ✅ ResetUsecase (Forgot/Reset password flow)
│   └── change.go                     ✅ ChangeUsecase (Change password for authenticated users)
│
├── session/
│   ├── dto/
│   │   └── session_dto.go            ✅ SessionInfo, ListSessionsResponse, ImpossibleTravelAlert
│   └── manager.go                    ✅ ManagerUsecase (List, Revoke, Impossible Travel Detection)
│
└── common/
    └── interfaces.go                 ✅ Shared service interfaces (UserService, NotificationService, etc.)
```

## Files Created

### Authentication Feature (3 files + DTOs)
1. **authentication/login.go** (380 lines)
   - `LoginUsecase` with `Execute()` method
   - Helper methods: `checkRateLimit()`, `incrementRateLimit()`, `resetRateLimit()`, `recordFailedAttempt()`, `generateTokenPair()`, `createSession()`, `publishLoginEvent()`, `getRestrictionMessage()`
   - Rate limiting, IP blocking, 2FA check, session creation

2. **authentication/logout.go** (114 lines)
   - `LogoutUsecase` with `Execute()` and `ExecuteAll()` methods
   - Revokes sessions and deletes refresh tokens
   - Publishes logout events

3. **authentication/refresh.go** (183 lines)
   - `RefreshTokenUsecase` with `Execute()` method
   - Token rotation with grace period
   - Token theft detection & security alerts

4. **authentication/dto/auth_dto.go** (79 lines)
   - Request DTOs: `LoginRequest`, `RefreshTokenRequest`, `LogoutRequest`, `LogoutAllRequest`, `Verify2FARequest`
   - Response DTOs: `LoginResponse`, `RefreshTokenResponse`, `Verify2FAResponse`, `RestrictionWarning`

### Registration Feature (2 files + DTOs)
5. **registration/personal.go** (452 lines)
   - `PersonalRegistrationUsecase` with 5 methods:
     - `StartRegistration()` - Email OTP send
     - `VerifyEmailOTP()` - Email verification
     - `AddPhoneNumber()` - Phone OTP send
     - `VerifyPhoneOTP()` - Phone verification
     - `CompleteRegistration()` - Create user & generate tokens
   - Helper functions: `generateOTPCode()`, `validatePasswordStrength()`, `generateTokenPair()`, `maskEmail()`, `maskPhone()`

6. **registration/managed.go** (248 lines)
   - `ManagedAccountUsecase` with `CreateManagedAccount()` method
   - Permission validation (System Admin, Admin, Seller Admin)
   - Temporary password generation
   - Notification sending

7. **registration/dto/registration_dto.go** (106 lines)
   - Personal registration DTOs (5 steps): `StartRegistrationRequest/Response`, `VerifyEmailOTPRequest/Response`, `AddPhoneNumberRequest/Response`, `VerifyPhoneOTPRequest/Response`, `CompleteRegistrationRequest/Response`
   - Managed account DTOs: `CreateManagedAccountRequest`, `CreateManagedAccountResponse`

### Password Feature (2 files + DTOs)
8. **password/reset.go** (225 lines)
   - `ResetUsecase` with 3 methods:
     - `ForgotPassword()` - Initiate reset flow, send email
     - `ResetPassword()` - Reset using token
     - `ValidateResetToken()` - Check token validity
   - Token generation with SHA-256 hashing
   - Session & refresh token revocation on password reset

9. **password/change.go** (119 lines)
   - `ChangeUsecase` with `ChangePassword()` method
   - Verifies old password
   - Validates new password strength
   - Revokes other sessions (optional keep current)

10. **password/dto/password_dto.go** (38 lines)
    - Reset DTOs: `ForgotPasswordRequest/Response`, `ResetPasswordRequest/Response`, `ValidateResetTokenResponse`
    - Change DTOs: `ChangePasswordRequest/Response`

### Session Feature (1 file + DTOs)
11. **session/manager.go** (278 lines)
    - `ManagerUsecase` with 6 methods:
      - `ListSessions()` - Get all active sessions
      - `RevokeSession()` - Revoke specific session
      - `RevokeAllSessions()` - Revoke all except current
      - `DetectImpossibleTravel()` - Detect suspicious location changes
      - `GetSessionInfo()` - Get single session details
      - `UpdateSessionActivity()` - Update last active time
    - Helper methods: `calculateDistance()` (Haversine formula), `degreesToRadians()`

12. **session/dto/session_dto.go** (39 lines)
    - `SessionInfo`, `ListSessionsResponse`, `RevokeSessionRequest`, `RevokeSessionResponse`, `ImpossibleTravelAlert`

## Key Improvements

### 1. **Clear Separation of Concerns**
- Each feature has its own directory
- Related DTOs stay with their feature
- Helper functions colocated with business logic

### 2. **Single Responsibility**
- Each usecase file handles ONE specific feature
- No mixing of authentication, registration, password logic
- Easy to understand and maintain

### 3. **Proper Imports**
- All usecases import from `common` package for shared types
- DTOs reference feature-specific types
- No circular dependencies

### 4. **Clean Architecture Compliance**
- Usecases depend on repository interfaces (from `domain/repository`)
- Usecases depend on service interfaces (from `usecase/common`)
- Infrastructure details abstracted away

### 5. **Scalability**
- Easy to add new features (just create new directory)
- Easy to find code (organized by feature)
- Team members can work on different features without conflicts

## Usage Pattern

### Old Way (Before Refactoring)
```go
// Everything in one file
authUC := usecase.NewAuthenticationUsecase(...)
authUC.Login(...)
authUC.RefreshToken(...)
authUC.Logout(...)
```

### New Way (After Refactoring)
```go
// Specialized usecases
loginUC := authentication.NewLoginUsecase(...)
loginUC.Execute(ctx, req, reqCtx)

refreshUC := authentication.NewRefreshTokenUsecase(...)
refreshUC.Execute(ctx, req, reqCtx)

logoutUC := authentication.NewLogoutUsecase(...)
logoutUC.Execute(ctx, userID, req)
```

## Next Steps

1. **Update Dependency Injection** (`cmd/wire.go`)
   - Import new feature packages
   - Instantiate specialized usecases
   - Wire dependencies

2. **Update HTTP Handlers**
   - Import feature-specific usecases
   - Update handler constructors
   - Update handler methods to use new usecases

3. **Delete Old Files** (After verifying everything works)
   - `usecase/authentication.go`
   - `usecase/registration.go`
   - `usecase/registration_managed.go`
   - `usecase/password.go`
   - `usecase/session.go`
   - `usecase/dto/*.go` (move to common if needed)

4. **Update Tests**
   - Update test imports
   - Update mock interfaces
   - Test each feature usecase independently

5. **Update Documentation**
   - API documentation
   - Developer onboarding docs
   - Architecture diagrams

## File Size Comparison

| Feature | Old (1 file) | New (Split) | Benefit |
|---------|--------------|-------------|---------|
| Authentication | 523 lines | login.go (380) + logout.go (114) + refresh.go (183) = 677 lines | Separated concerns, easier to navigate |
| Registration | 467 lines | personal.go (452) + managed.go (248) = 700 lines | Personal vs Managed clearly separated |
| Password | 263 lines | reset.go (225) + change.go (119) = 344 lines | Reset flow vs Change flow separated |
| Session | 278 lines | manager.go (278) | Single focused file |
| DTOs | Mixed in each | Feature-specific dto/ folders | DTOs colocated with features |

## Benefits Achieved

✅ **Better Organization** - Features in separate directories
✅ **Easier Navigation** - Find code by feature name
✅ **Reduced Complexity** - Smaller, focused files
✅ **Better Testing** - Test each feature independently
✅ **Team Scalability** - Multiple developers can work on different features
✅ **Clear Dependencies** - Import only what you need
✅ **Maintainability** - Changes localized to specific features
✅ **Clean Architecture** - Proper layer separation maintained

## Command to Verify Structure

```bash
# Navigate to usecase directory
cd go/services/auth-service/internal/usecase

# List structure
tree -L 3

# Expected output:
# .
# ├── authentication/
# │   ├── dto/
# │   │   └── auth_dto.go
# │   ├── login.go
# │   ├── logout.go
# │   └── refresh.go
# ├── registration/
# │   ├── dto/
# │   │   └── registration_dto.go
# │   ├── personal.go
# │   └── managed.go
# ├── password/
# │   ├── dto/
# │   │   └── password_dto.go
# │   ├── reset.go
# │   └── change.go
# ├── session/
# │   ├── dto/
# │   │   └── session_dto.go
# │   └── manager.go
# └── common/
#     └── interfaces.go
```

## Notes

- All helper functions are kept in the same file as their usecase (not separate helpers.go)
- DTOs specific to a feature are in that feature's dto/ subdirectory
- Common types (UserDTO, RequestContext, DeviceInfo) remain in `common/interfaces.go`
- Repository interfaces imported from `domain/repository`
- Service interfaces imported from `usecase/common`

---
**Refactoring Status**: ✅ **COMPLETE**
**Next Step**: Update dependency injection (wire.go) and HTTP handlers
