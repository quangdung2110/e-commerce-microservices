# Makefile for User Service

.PHONY: help build run test test-coverage test-unit lint fmt docker-build docker-up docker-down migrate clean

# Variables
APP_NAME := user-service
COMPLIANCE_APP_NAME := user-service-compliance
DOCKER_IMAGE := $(APP_NAME):latest
DOCKER_COMPLIANCE_IMAGE := $(COMPLIANCE_APP_NAME):latest
GO := go
GOFLAGS := -v
BUILD_DIR := ./build
MIGRATION_DIR := ./migrations

# Default target
help: ## Show this help message
	@echo "User Service - Makefile Commands"
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@echo "Targets:"
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

## Build
build: ## Build main server binary
	@echo "Building $(APP_NAME)..."
	@mkdir -p $(BUILD_DIR)
	$(GO) build $(GOFLAGS) -o $(BUILD_DIR)/$(APP_NAME) ./cmd/server

build-compliance: ## Build compliance worker binary
	@echo "Building $(COMPLIANCE_APP_NAME)..."
	@mkdir -p $(BUILD_DIR)
	$(GO) build $(GOFLAGS) -o $(BUILD_DIR)/$(COMPLIANCE_APP_NAME) ./cmd/server/compliance

build-all: build build-compliance ## Build both server and compliance worker

## Run
run: ## Run the main server locally
	@echo "Starting $(APP_NAME)..."
	$(GO) run ./cmd/server

run-compliance: ## Run the compliance worker locally
	@echo "Starting $(COMPLIANCE_APP_NAME)..."
	$(GO) run ./cmd/server/compliance

## Testing
test: ## Run all tests
	@echo "Running tests..."
	$(GO) test $(GOFLAGS) ./...

test-unit: ## Run unit tests only (exclude integration)
	@echo "Running unit tests..."
	$(GO) test $(GOFLAGS) -short ./...

test-coverage: ## Run tests with coverage report
	@echo "Running tests with coverage..."
	$(GO) test -coverprofile=coverage.out ./...
	$(GO) tool cover -html=coverage.out -o coverage.html
	@echo "Coverage report generated: coverage.html"
	@rm coverage.out

test-watch: ## Run tests in watch mode
	@echo "Running tests in watch mode..."
	$(GO) test -watch ./...

## Code Quality
fmt: ## Format code
	@echo "Formatting code..."
	$(GO) fmt ./...

lint: ## Run linters
	@echo "Running linters..."
	golangci-lint run ./...
	@echo "Lint complete"

lint-fix: ## Run linters with auto-fix
	@echo "Running linters with auto-fix..."
	golangci-lint run --fix ./...

vet: ## Run go vet
	@echo "Running go vet..."
	$(GO) vet ./...

## Dependencies
deps: ## Download dependencies
	@echo "Downloading dependencies..."
	$(GO) mod download
	$(GO) mod tidy

deps-update: ## Update dependencies
	@echo "Updating dependencies..."
	$(GO) get -u ./...
	$(GO) mod tidy

## Database
migrate: ## Run database migrations
	@echo "Running migrations..."
	@for f in $(MIGRATION_DIR)/*.up.sql; do \
		echo "Applying: $$f"; \
		psql -h localhost -U postgres -d user_service -f "$$f" || true; \
	done

migrate-down: ## Rollback last migration
	@echo "Rolling back migrations..."
	@for f in $(shell ls $(MIGRATION_DIR)/*.down.sql | sort -r); do \
		echo "Rolling back: $$f"; \
		psql -h localhost -U postgres -d user_service -f "$$f" || true; \
	done

migrate-create NAME=: ## Create a new migration (e.g., make migrate-create NAME=add_phone)
	@if [ -z "$(NAME)" ] || [ "$(NAME)" = ":" ]; then \
		echo "Usage: make migrate-create NAME=your_migration_name"; \
		exit 1; \
	fi
	@TIMESTAMP=$$(date +%Y%m%d%H%M%S); \
	echo "-- Migration: $(NAME)" > $(MIGRATION_DIR)/$$TIMESTAMP$(NAME).up.sql; \
	echo "-- Rollback: $(NAME)" > $(MIGRATION_DIR)/$$TIMESTAMP$(NAME).down.sql; \
	echo "Created migration files: $$TIMESTAMP$(NAME).up.sql and .down.sql"

## Docker
docker-build: ## Build Docker image
	@echo "Building Docker image..."
	docker build -t $(DOCKER_IMAGE) .

docker-build-compliance: ## Build compliance worker Docker image
	@echo "Building compliance worker Docker image..."
	docker build -f Dockerfile.compliance -t $(DOCKER_COMPLIANCE_IMAGE) .

docker-build-all: docker-build docker-build-compliance ## Build all Docker images

docker-up: ## Start all services with Docker Compose
	@echo "Starting services with Docker Compose..."
	docker-compose up -d

docker-down: ## Stop all services
	@echo "Stopping services..."
	docker-compose down

docker-down-volumes: ## Stop and remove volumes
	@echo "Stopping and removing volumes..."
	docker-compose down -v

docker-restart: docker-down docker-up ## Restart all services

docker-logs: ## Show logs from all services
	docker-compose logs -f

docker-logs-service SERVICE=: ## Show logs from specific service (e.g., make docker-logs-service SERVICE=user-service)
	@if [ -z "$(SERVICE)" ] || [ "$(SERVICE)" = ":" ]; then \
		echo "Usage: make docker-logs-service SERVICE=user-service"; \
		exit 1; \
	fi
	docker-compose logs -f $(SERVICE)

## Development
dev-deps: ## Install development dependencies
	@echo "Installing development tools..."
	go install github.com/golangci-lint/cmd/golangci-lint@latest
	go install github.com/pressly/goose/cmd/goose@latest

## Clean
clean: ## Remove build artifacts
	@echo "Cleaning build artifacts..."
	rm -rf $(BUILD_DIR)
	rm -f coverage.html

clean-all: clean ## Remove all generated files including coverage
	@echo "Cleaning all generated files..."
	rm -f coverage.html

## Generate
generate: ## Run code generators
	@echo "Running code generators..."
	@# Add your code generation commands here
	@# e.g., go generate ./...

## Multi-architecture build
docker-buildx: ## Build multi-architecture Docker image
	@echo "Building multi-architecture Docker image..."
	docker buildx build --platform linux/amd64,linux/arm64 -t $(DOCKER_IMAGE) --push .

## Release
release: build-all ## Create release artifacts
	@echo "Creating release artifacts..."
	@mkdir -p release
	cp $(BUILD_DIR)/$(APP_NAME) release/
	cp $(BUILD_DIR)/$(COMPLIANCE_APP_NAME) release/
	@echo "Release artifacts created in ./release"
