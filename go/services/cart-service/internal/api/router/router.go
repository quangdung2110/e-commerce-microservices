package router

import (
	"net/http"

	"github.com/gorilla/mux"
	"go.uber.org/zap"

	"github.com/ecommerce-platform/go/services/cart-service/internal/api/handler"
	"github.com/ecommerce-platform/go/services/cart-service/internal/api/middleware"
)

// Router sets up the HTTP routes
type Router struct {
	mux          *mux.Router
	cartHandler  *handler.CartHandler
	healthHandler *handler.HealthHandler
	middleware   *middleware.Middleware
}

// NewRouter creates a new Router
func NewRouter(
	cartHandler *handler.CartHandler,
	healthHandler *handler.HealthHandler,
	logger *zap.Logger,
) *Router {
	return &Router{
		mux:          mux.NewRouter(),
		cartHandler:  cartHandler,
		healthHandler: healthHandler,
		middleware:   middleware.NewMiddleware(logger),
	}
}

// setupRoutes configures all routes
func (r *Router) setupRoutes() {
	// Apply middleware to all routes
	r.mux.Use(r.middleware.RecoveryMiddleware)
	r.mux.Use(r.middleware.RequestIDMiddleware)
	r.mux.Use(r.middleware.LoggingMiddleware)
	r.mux.Use(r.middleware.CORSMiddleware)

	// Health endpoints
	r.mux.HandleFunc("/health/live", r.healthHandler.Live).Methods(http.MethodGet)
	r.mux.HandleFunc("/health/ready", r.healthHandler.Ready).Methods(http.MethodGet)

	// API v1 routes
	v1 := r.mux.PathPrefix("/api/v1").Subrouter()

	// Cart routes
	carts := v1.PathPrefix("/carts").Subrouter()
	carts.HandleFunc("/items", r.cartHandler.AddItem).Methods(http.MethodPost)
	carts.HandleFunc("/mine", r.cartHandler.GetCart).Methods(http.MethodGet)
	carts.HandleFunc("/items", r.cartHandler.DeleteItems).Methods(http.MethodDelete)
}

// Handler returns the http.Handler
func (r *Router) Handler() http.Handler {
	r.setupRoutes()
	return r.mux
}
