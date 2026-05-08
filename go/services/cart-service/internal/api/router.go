package api

import (
	"net/http"

	"github.com/gorilla/mux"
	"go.uber.org/zap"
)

// Router sets up the HTTP routes
type Router struct {
	mux          *mux.Router
	cartHandler  *CartHandler
	healthHandler *HealthHandler
	middleware   *Middleware
}

// NewRouter creates a new Router
func NewRouter(deps *Dependencies, logger *zap.Logger) *Router {
	r := &Router{
		mux:          mux.NewRouter(),
		cartHandler:  NewCartHandler(deps),
		healthHandler: NewHealthHandler(deps),
		middleware:   NewMiddleware(logger),
	}

	r.setupRoutes()
	return r
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
	return r.mux
}
