# =============================================================================
# Traefik Dockerfile
# =============================================================================
# API Gateway with Traefik v3.x
# Note: Go plugins require CGO and are complex to build in Docker
# For development, we use Traefik without custom plugins
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
RUN mkdir -p /letsencrypt /certs /plugins && \
    chown -R non-root:non-root /letsencrypt /certs /plugins || true

# Default command
CMD ["--configfile=/etc/traefik/traefik.yml"]
