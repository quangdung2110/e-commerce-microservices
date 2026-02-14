# Makefile for Golang microservices

.PHONY: all build test clean install run-inventory run-shipping run-cart lint fmt help

# Variables
BINARY_DIR=bin
SERVICES=inventory-service shipping-service cart-service search-service notification-service chat-service

# Go build flags
LDFLAGS=-ldflags "-s -w"
GO_BUILD=go build $(LDFLAGS)

# Default target
all: install build

# Install dependencies
install:
	@echo "==> Installing dependencies..."
	@go mod download
	@go mod verify

# Build all services
build: $(SERVICES)

$(SERVICES):
	@echo "==> Building $@..."
	@mkdir -p $(BINARY_DIR)
	@$(GO_BUILD) -o $(BINARY_DIR)/$@ ./services/$@/cmd

# Build single service (usage: make build-service SERVICE=inventory-service)
build-service:
	@echo "==> Building $(SERVICE)..."
	@mkdir -p $(BINARY_DIR)
	@$(GO_BUILD) -o $(BINARY_DIR)/$(SERVICE) ./services/$(SERVICE)/cmd

# Test all packages
test:
	@echo "==> Running tests..."
	@go test -v -race -coverprofile=coverage.out ./...

# Test with coverage report
test-coverage: test
	@echo "==> Generating coverage report..."
	@go tool cover -html=coverage.out -o coverage.html
	@echo "Coverage report: coverage.html"

# Test shared packages only
test-pkg:
	@echo "==> Testing shared packages..."
	@go test -v -race ./pkg/...

# Run linter
lint:
	@echo "==> Running linter..."
	@golangci-lint run ./...

# Format code
fmt:
	@echo "==> Formatting code..."
	@gofmt -s -w .
	@goimports -w .

# Run specific services (for local development)
run-inventory:
	@echo "==> Running inventory-service..."
	@go run ./services/inventory-service/cmd

run-shipping:
	@echo "==> Running shipping-service..."
	@go run ./services/shipping-service/cmd

run-cart:
	@echo "==> Running cart-service..."
	@go run ./services/cart-service/cmd

run-search:
	@echo "==> Running search-service..."
	@go run ./services/search-service/cmd

run-notification:
	@echo "==> Running notification-service..."
	@go run ./services/notification-service/cmd

run-chat:
	@echo "==> Running chat-service..."
	@go run ./services/chat-service/cmd

# Clean build artifacts
clean:
	@echo "==> Cleaning build artifacts..."
	@rm -rf $(BINARY_DIR)
	@rm -f coverage.out coverage.html
	@go clean -cache

# Docker build (usage: make docker-build SERVICE=inventory-service)
docker-build:
	@echo "==> Building Docker image for $(SERVICE)..."
	@docker build -t $(SERVICE):latest -f services/$(SERVICE)/Dockerfile .

# Docker build all services
docker-build-all:
	@for service in $(SERVICES); do \
		echo "==> Building Docker image for $$service..."; \
		docker build -t $$service:latest -f services/$$service/Dockerfile .; \
	done

# Generate mocks (requires mockgen)
mocks:
	@echo "==> Generating mocks..."
	@go generate ./...

# Tidy dependencies
tidy:
	@echo "==> Tidying dependencies..."
	@go mod tidy

# Verify dependencies
verify:
	@echo "==> Verifying dependencies..."
	@go mod verify

# Help
help:
	@echo "Available targets:"
	@echo "  make install          - Install dependencies"
	@echo "  make build            - Build all services"
	@echo "  make build-service    - Build single service (SERVICE=name)"
	@echo "  make test             - Run all tests"
	@echo "  make test-coverage    - Run tests with coverage report"
	@echo "  make test-pkg         - Test shared packages only"
	@echo "  make lint             - Run linter"
	@echo "  make fmt              - Format code"
	@echo "  make clean            - Clean build artifacts"
	@echo "  make run-*            - Run specific service (run-inventory, run-shipping, etc.)"
	@echo "  make docker-build     - Build Docker image (SERVICE=name)"
	@echo "  make docker-build-all - Build Docker images for all services"
	@echo "  make tidy             - Tidy dependencies"
	@echo "  make verify           - Verify dependencies"
	@echo "  make help             - Show this help"
