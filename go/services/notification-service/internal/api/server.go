// Package api builds the two HTTP servers of the service: the public inbox
// listener (behind the gateway) and the cluster-internal listener (status,
// ops, metrics, health). Neither terminates TLS: the mesh does (decisions.md
// D-21).
package api

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/config"
)

// NewPublicServer builds the public listener from server.* settings.
func NewPublicServer(cfg *config.Config, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              net.JoinHostPort(cfg.Server.Host, strconv.Itoa(cfg.Server.Port)),
		Handler:           handler,
		ReadTimeout:       cfg.Server.ReadTimeout,
		ReadHeaderTimeout: cfg.Server.ReadTimeout,
		WriteTimeout:      cfg.Server.WriteTimeout,
		IdleTimeout:       cfg.Server.IdleTimeout,
		MaxHeaderBytes:    1 << 20,
	}
}

// NewInternalServer builds the internal listener. The public timeouts are
// reused because the two listeners serve similar-sized requests.
func NewInternalServer(cfg *config.Config, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              net.JoinHostPort(cfg.Internal.Host, strconv.Itoa(cfg.Internal.Port)),
		Handler:           handler,
		ReadTimeout:       cfg.Server.ReadTimeout,
		ReadHeaderTimeout: cfg.Server.ReadTimeout,
		WriteTimeout:      cfg.Server.WriteTimeout,
		IdleTimeout:       cfg.Server.IdleTimeout,
		MaxHeaderBytes:    1 << 20,
	}
}

// Serve runs the server until it is closed. http.ErrServerClosed is not an
// error.
func Serve(srv *http.Server, logger *zap.Logger, name string) error {
	if logger != nil {
		logger.Info("http listener starting", zap.String("listener", name), zap.String("addr", srv.Addr))
	}
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// Shutdown drains the server within the timeout, then closes it hard.
func Shutdown(ctx context.Context, srv *http.Server, timeout time.Duration, logger *zap.Logger, name string) error {
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if logger != nil {
		logger.Info("http listener stopping", zap.String("listener", name))
	}
	if err := srv.Shutdown(ctx); err != nil {
		_ = srv.Close()
		return fmt.Errorf("%s: shutdown: %w", name, err)
	}
	return nil
}
