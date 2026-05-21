.PHONY: build run test lint clean migrate docker

# Build
build:
	go build -o bin/auth-service ./cmd/server

# Run
run:
	go run ./cmd/server -config ./configs/application.yaml

# Test
test:
	go test -v -race -cover ./...

test-unit:
	go test -v -short ./...

test-integration:
	go test -v -tags=integration ./test/integration/...

# Lint
lint:
	golangci-lint run ./...

lint-fix:
	golangci-lint run --fix ./...

# Database
migrate-up:
	migrate -path ./internal/infrastructure/postgres/migrations -database "postgres://postgres:postgres@localhost:5432/auth_db?sslmode=disable" up

migrate-down:
	migrate -path ./internal/infrastructure/postgres/migrations -database "postgres://postgres:postgres@localhost:5432/auth_db?sslmode=disable" down

# Clean
clean:
	rm -rf bin/
	go clean

# Docker
docker-build:
	docker build -t auth-service:latest .

docker-run:
	docker-compose up -d

# Generate mocks
mocks:
	mockery --dir=./internal/domain/port --all --output=./test/mocks

# Dependencies
deps:
	go mod download
	go mod tidy
