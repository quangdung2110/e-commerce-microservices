// Package api builds the two HTTP servers of the service: the public inbox
// listener (behind the gateway) and the cluster-internal listener (status,
// ops, metrics, health), which terminates mTLS itself when certificates are
// configured.
package api

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
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

// NewInternalServer builds the internal listener. When internal.tls_* and
// client_ca_file are all set the server requires and verifies a client
// certificate, and the ServiceIdentity middleware reads the peer CN; the
// public timeouts are reused because the two listeners serve similar-sized
// requests.
func NewInternalServer(cfg *config.Config, handler http.Handler) (*http.Server, error) {
	srv := &http.Server{
		Addr:              net.JoinHostPort(cfg.Internal.Host, strconv.Itoa(cfg.Internal.Port)),
		Handler:           handler,
		ReadTimeout:       cfg.Server.ReadTimeout,
		ReadHeaderTimeout: cfg.Server.ReadTimeout,
		WriteTimeout:      cfg.Server.WriteTimeout,
		IdleTimeout:       cfg.Server.IdleTimeout,
		MaxHeaderBytes:    1 << 20,
	}
	if !cfg.Internal.MutualTLSEnabled() {
		return srv, nil
	}
	tlsConfig, err := mutualTLSConfig(cfg.Internal)
	if err != nil {
		return nil, err
	}
	srv.TLSConfig = tlsConfig
	return srv, nil
}

func mutualTLSConfig(in config.InternalConfig) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(in.TLSCertFile, in.TLSKeyFile)
	if err != nil {
		return nil, fmt.Errorf("internal listener: load server certificate: %w", err)
	}
	caPEM, err := os.ReadFile(in.ClientCAFile)
	if err != nil {
		return nil, fmt.Errorf("internal listener: read client CA: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("internal listener: client CA file holds no certificate")
	}
	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		ClientCAs:    pool,
		ClientAuth:   tls.RequireAndVerifyClientCert,
		MinVersion:   tls.VersionTLS12,
	}, nil
}

// Serve runs the server until it is closed; a TLS-configured server serves
// TLS with the certificates already loaded. http.ErrServerClosed is not an
// error.
func Serve(srv *http.Server, logger *zap.Logger, name string) error {
	if logger != nil {
		logger.Info("http listener starting", zap.String("listener", name), zap.String("addr", srv.Addr),
			zap.Bool("tls", srv.TLSConfig != nil))
	}
	var err error
	if srv.TLSConfig != nil {
		err = srv.ListenAndServeTLS("", "")
	} else {
		err = srv.ListenAndServe()
	}
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
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
