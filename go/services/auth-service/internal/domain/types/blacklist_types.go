package types

// BlacklistType represents the type of blacklist entry
type BlacklistType string

const (
	// TokenBlacklist indicates a token-specific blacklist entry
	TokenBlacklist BlacklistType = "token"
	// UserBlacklist indicates a user-wide blacklist entry
	UserBlacklist BlacklistType = "user"
)
