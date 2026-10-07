package httpx

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/common/config"
)

// CookieSettings is the refresh-cookie contract: HttpOnly, Secure and
// SameSite=Strict, scoped to the session endpoints so it is never sent to an
// unrelated path.
type CookieSettings struct {
	Name     string
	Path     string
	Domain   string
	Secure   bool
	MaxAge   time.Duration
	SameSite http.SameSite
}

// NewCookieSettings derives the settings from the refresh configuration.
func NewCookieSettings(cfg config.RefreshConfig) CookieSettings {
	name := cfg.CookieName
	if name == "" {
		name = "auth_refresh"
	}
	path := cfg.CookiePath
	if path == "" {
		path = "/api/v1/auth/sessions"
	}
	return CookieSettings{
		Name:     name,
		Path:     path,
		Domain:   cfg.CookieDomain,
		Secure:   cfg.CookieSecure,
		MaxAge:   cfg.SlidingTTL,
		SameSite: http.SameSiteStrictMode,
	}
}

// SetRefreshCookie writes the opaque refresh token. The value never appears in a
// JSON body or a log line.
func SetRefreshCookie(c *gin.Context, settings CookieSettings, value string, expiresAt time.Time) {
	maxAge := int(time.Until(expiresAt).Seconds())
	if maxAge < 1 {
		maxAge = int(settings.MaxAge.Seconds())
	}

	http.SetCookie(c.Writer, &http.Cookie{
		Name:     settings.Name,
		Value:    value,
		Path:     settings.Path,
		Domain:   settings.Domain,
		MaxAge:   maxAge,
		Secure:   settings.Secure,
		HttpOnly: true,
		SameSite: settings.SameSite,
	})
}

// ClearRefreshCookie expires the cookie with the same attributes, which is what
// makes a browser actually drop it.
func ClearRefreshCookie(c *gin.Context, settings CookieSettings) {
	http.SetCookie(c.Writer, &http.Cookie{
		Name:     settings.Name,
		Value:    "",
		Path:     settings.Path,
		Domain:   settings.Domain,
		MaxAge:   -1,
		Secure:   settings.Secure,
		HttpOnly: true,
		SameSite: settings.SameSite,
	})
}

// RefreshCookie reads the presented refresh token, if any.
func RefreshCookie(c *gin.Context, settings CookieSettings) string {
	cookie, err := c.Request.Cookie(settings.Name)
	if err != nil || cookie == nil {
		return ""
	}
	return cookie.Value
}
