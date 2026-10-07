// Package constants holds contract-level literals shared by adapters and the
// domain. Values that operators tune live in configuration, not here.
package constants

// Trusted identity headers injected by the API Gateway. They are never accepted
// from a public client (contracts/conventions/http.md, docs/user/domain.md §6).
const (
	HeaderUserID       = "X-User-Id"
	HeaderUserRoles    = "X-User-Roles"
	HeaderRequestID    = "X-Request-Id"
	HeaderTraceparent  = "traceparent"
	HeaderRetryAfter   = "Retry-After"
	HeaderCacheControl = "Cache-Control"
	HeaderLocation     = "Location"
)

// ContentTypeProblem is the media type of every error response.
const ContentTypeProblem = "application/problem+json"

// ContentTypeMergePatch is the only accepted media type of PATCH /profile.
const ContentTypeMergePatch = "application/merge-patch+json"

// PrivateNoStore is required on every response carrying profile, address or
// preference data (contracts/http/user/v1/openapi.yaml).
const PrivateNoStore = "private, no-store"

// DisplayNameFallback names a profile whose owner has not filled in a name yet.
// It is used both by the `display_name` response field and by `sender_name` in
// USER_PROFILE_UPDATED, which the schema requires to be non-empty.
const DisplayNameFallback = "Người dùng"

// Field bounds taken from the OpenAPI schemas. They are duplicated here so the
// domain can reject an oversized value before it reaches PostgreSQL.
const (
	MaxNameLength          = 100
	MaxDisplayNameLength   = 201
	MaxRecipientNameLength = 200
	MaxAddressLineLength   = 500
	MaxAreaCodeLength      = 20
	MaxRequestIDLength     = 128
)

// AddressBookLimit caps how many addresses one profile may hold. The contract
// does not paginate the address book, so the list has to stay small.
const AddressBookLimit = 50
