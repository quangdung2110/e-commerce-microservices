package svc

import "time"

type LoginRequest struct {
	Email      string
	Password   string
	DeviceID   string
	DeviceInfo string
	IPAddress  string
}

type LoginResponse struct {
	AccessToken string
	ExpiresIn  int64
	TokenType  string
	User       *UserInfo
}

type UserInfo struct {
	ID     string
	Email  string
	Roles  []string
	Status string
}

type LogoutRequest struct {
	UserID         string
	RefreshToken   string
	AccessTokenJTI string
	AccessTokenExp time.Time
}

type RefreshResponse struct {
	AccessToken     string
	NewRefreshToken string
	ExpiresAt       time.Time
	TokenType       string
}
