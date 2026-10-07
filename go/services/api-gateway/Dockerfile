# =============================================================================
# Traefik Dockerfile
# =============================================================================
# API Gateway with Traefik v3.x
# Xac thuc JWT do service go/services/gateway-auth dam nhiem qua ForwardAuth;
# Traefik khong nap plugin nao.
# =============================================================================

FROM traefik:v3.7.1

# Install additional tools for debugging
RUN apk add --no-cache \
    curl \
    tzdata \
    ca-certificates

# Copy configuration
COPY config/ /etc/traefik/

# Create necessary directories
RUN mkdir -p /letsencrypt /certs && \
    chown -R non-root:non-root /letsencrypt /certs || true

# Default command
CMD ["--configfile=/etc/traefik/traefik.yml"]
