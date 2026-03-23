# Auth Service Design Document

## 1. Overview
The Auth service is the central and authoritative piece for validating identities across the platform. It handles login (password, social, OTP), registration, password resets, and token issuing.

## 2. Technology Choice
- **Language**: Go / Java (Spring Boot)
- **Database**: PostgreSQL (for users, credentials, role mapping)
- **Cache**: Redis / KeyDB (for JWT blacklist, active sessions, OTP flow cache, rate-limits)
- **Security**: Argon2 or BCrypt for password hashing; asymmetric RSA for JWT signing.

## 3. Core Capabilities
- Single Sign-On (SSO) integration.
- RBAC (Role-Based Access Control).
- Two-Factor Authentication (MFA).

## 4. Workflows
- **Login Flow**: Validate credential in DB -> Issue short-lived JWT Access Token & long-lived Refresh Token -> Store Session Info in Redis.
- **Token Invalidation**: Add JWT JTI to Redis blacklist until expiration time to forcibly end a session.

## 5. Caching and Storage Schema
- Postgres `credentials`: `user_id(PK)`, `password_hash`, `salt`, `is_mfa_enabled`, `failed_attempts`
- Redis `blacklist:(jti)`: Exists with TTL equal to the JWT expiration.
