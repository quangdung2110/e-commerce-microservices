# =============================================================================
# API Gateway Makefile
# =============================================================================

.PHONY: help build up down restart logs ps clean test lint fmt vet

# Variables
COMPOSE_FILE := docker-compose.yml
COMPOSE_PROJECT := api-gateway
ENV_FILE := .env

# Colors
RED    := \033[0;31m
GREEN  := \033[0;32m
YELLOW := \033[0;33m
NC     := \033[0m # No Color

# Default target
help:
	@echo "$(GREEN)API Gateway Management$(NC)"
	@echo ""
	@echo "Usage:"
	@echo "  make up           Start all services"
	@echo "  make down         Stop all services"
	@echo "  make restart      Restart all services"
	@echo "  make logs         Show logs (traefik)"
	@echo "  make logs-all     Show logs (all services)"
	@echo "  make ps           Show running containers"
	@echo "  make clean        Remove containers and volumes"
	@echo "  make build        Build Go plugins"
	@echo "  make test         Run tests"
	@echo "  make lint         Run linter"
	@echo "  make fmt          Format code"
	@echo ""
	@echo "Services:"
	@echo "  make traefik-up   Start Traefik only"
	@echo "  make traefik-logs Show Traefik logs"

# Check if .env exists
check-env:
	@if [ ! -f $(ENV_FILE) ]; then \
		echo "$(YELLOW)Warning: $(ENV_FILE) not found. Copy .env.example to $(ENV_FILE)$(NC)"; \
		cp .env.example $(ENV_FILE); \
	fi

# Start all services
up: check-env
	docker compose -f $(COMPOSE_FILE) up -d
	@echo "$(GREEN)Services started. Dashboard: http://localhost:8080$(NC)"

# Stop all services
down:
	docker compose -f $(COMPOSE_FILE) down

# Restart all services
restart: down up

# Show Traefik logs
logs:
	docker compose -f $(COMPOSE_FILE) logs -f traefik

# Show all logs
logs-all:
	docker compose -f $(COMPOSE_FILE) logs -f

# Show running containers
ps:
	docker compose -f $(COMPOSE_FILE) ps

# Clean up (remove containers and volumes)
clean:
	docker compose -f $(COMPOSE_FILE) down -v
	rm -rf plugins/*.so
	@echo "$(GREEN)Cleaned up containers and volumes$(NC)"

# Build Go plugins using Docker (for Windows users)
build-plugin: check-env
	@echo "$(GREEN)Building Go plugins using Docker...$(NC)"
	@mkdir -p plugins
	@docker compose run --rm go-builder
	@echo "$(GREEN)Plugin built successfully!$(NC)"

# Run unit tests using Docker
test-docker:
	@echo "$(GREEN)Running unit tests using Docker...$(NC)"
	@docker run --rm -v "$(PWD)/cmd/traefik-plugin:/app" -w /app golang:1.21 go test -v ./...

# Run integration tests
test-integration:
	@echo "$(GREEN)Running integration tests...$(NC)"
	@docker compose -f $(COMPOSE_FILE) --profile test up -d
	@sleep 5
	@# Run test scripts
	@chmod +x scripts/test-*.sh 2>/dev/null || true
	@./scripts/test-phase2.sh 2>/dev/null || echo "Run tests manually with Docker"
	@docker compose -f $(COMPOSE_FILE) --profile test down

# Start infrastructure services
infra-up:
	@echo "$(GREEN)Starting infrastructure services...$(NC)"
	docker compose -f $(COMPOSE_FILE) up -d redis jaeger
	@echo "$(GREEN)Waiting for services to be ready...$(NC)"
	@sleep 3
	@docker compose -f $(COMPOSE_FILE) ps

# Stop infrastructure services
infra-down:
	@echo "$(GREEN)Stopping infrastructure services...$(NC)"
	docker compose -f $(COMPOSE_FILE) stop redis jaeger

# Start test environment (infrastructure + auth mock)
test-up:
	@echo "$(GREEN)Starting test environment...$(NC)"
	docker compose -f $(COMPOSE_FILE) --profile test up -d
	@echo "$(GREEN)Test environment ready!$(NC)"
	@echo "  - Redis: localhost:6379"
	@echo "  - Jaeger: http://localhost:16686"
	@echo "  - Auth Mock: http://localhost:1080"

# Stop test environment
test-down:
	@echo "$(GREEN)Stopping test environment...$(NC)"
	docker compose -f $(COMPOSE_FILE) --profile test down

# Build Docker image
build: check-env
	docker build -t api-gateway:latest .

# Start Traefik only
traefik-up: check-env
	docker compose -f $(COMPOSE_FILE) up -d traefik
	@echo "$(GREEN)Traefik started. Dashboard: http://localhost:8080$(NC)"

# Stop Traefik only
traefik-down:
	docker compose -f $(COMPOSE_FILE) stop traefik

# Show Traefik logs
traefik-logs:
	docker compose -f $(COMPOSE_FILE) logs -f traefik

# Run tests
test:
	@cd cmd/traefik-plugin && go test -v ./...

# Run linter
lint:
	@cd cmd/traefik-plugin && golangci-lint run

# Format code
fmt:
	@cd cmd/traefik-plugin && go fmt ./...

# Vet code
vet:
	@cd cmd/traefik-plugin && go vet ./...

# Check Go version compatibility
check-version:
	@echo "Checking version compatibility..."
	@TRAEFIK_VERSION=$$(docker run --rm traefik:v3.7.1 --version 2>&1 | grep -oP 'v[\d.]+') && \
	GO_VERSION=$$(go version | grep -oP 'go[\d.]+') && \
	echo "Traefik: $$TRAEFIK_VERSION" && \
	echo "Go: $$GO_VERSION" && \
	if ! echo "$$TRAEFIK_VERSION" | grep -q "3.7"; then \
		echo "$(YELLOW)Warning: Plugin may not be compatible with this Traefik version$(NC)"; \
	fi

# Generate self-signed certificate for development
cert-dev:
	@mkdir -p certs
	openssl req -x509 -nodes -newkey rsa:2048 \
		-keyout certs/key.pem \
		-out certs/cert.pem \
		-subj "/CN=api.ecommerce.local" \
		-days 365
	@echo "$(GREEN)Self-signed certificate generated in certs/$(@)$(NC)"

# Validate docker-compose
validate:
	docker compose -f $(COMPOSE_FILE) config --quiet && \
		echo "$(GREEN)Docker Compose configuration is valid$(NC)"

# Show dashboard URL
dashboard:
	@echo "$(GREEN)Traefik Dashboard: http://localhost:8080$(NC)"
	@echo "$(GREEN)Jaeger UI: http://localhost:16686$(NC)"
	@echo "$(GREEN)Prometheus: http://localhost:9090$(NC)"
	@echo "$(GREEN)Grafana: http://localhost:3000 (admin/admin)$(NC)"

# Enter Traefik container
shell:
	docker exec -it api-gateway sh

# Enter Redis container
redis-shell:
	docker exec -it redis redis-cli -a ${REDIS_PASSWORD:-redis-password}

# Health check
health:
	@echo "Checking health..."
	@curl -s http://localhost:8080/ping > /dev/null && \
		echo "$(GREEN)Traefik is healthy$(NC)" || \
		echo "$(RED)Traefik is not responding$(NC)"

# Reload configuration (hot reload)
reload:
	docker exec api-gateway kill -HUP 1
	@echo "$(GREEN)Configuration reloaded$(NC)"

# Network info
network:
	@docker network inspect ecommerce-network --format '{{range .IPAM.Config}}Subnet: {{.Subnet}}{{end}}'

# Stats
stats:
	docker stats --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}" \
		$$(docker compose -f $(COMPOSE_FILE) ps -q)
