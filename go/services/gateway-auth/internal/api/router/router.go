// Package router wires the HTTP endpoints.
package router

import (
	"net/http"

	"github.com/ecommerce/gateway-auth/internal/api/handler"
)

// New registers:
//   - /verify         Traefik ForwardAuth target. Traefik may call it with the
//     original method, so every method is accepted.
//   - /health/live    process liveness.
//   - /health/ready   Redis + JWKS readiness.
func New(verify *handler.VerifyHandler, health *handler.HealthHandler) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/verify", verify)
	mux.HandleFunc("GET /health/live", health.Live)
	mux.HandleFunc("GET /health/ready", health.Ready)
	return mux
}
