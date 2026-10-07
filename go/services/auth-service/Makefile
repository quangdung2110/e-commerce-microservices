.PHONY: build run test test-unit test-integration test-all fmt vet lint clean deps migrate-up migrate-down docker-build docker-up docker-down

BIN        := bin/auth-service
CONFIG     := ./configs/application.yaml
MIGRATIONS := ./migrations
DB_URL     ?= postgres://postgres:postgres@localhost:5432/auth_db?sslmode=disable

build:
	go build -o $(BIN) ./cmd/server

run:
	go run ./cmd/server -config $(CONFIG)

# The default suite needs no Docker: the use cases run against in-memory doubles.
test:
	go test -race -cover ./...

test-unit:
	go test -race ./test/unit/...

# The integration suite starts PostgreSQL and Redis with testcontainers, so it
# needs a running Docker daemon. It covers the SQL, the Lua scripts, the crypto
# adapters and the HTTP surface.
test-integration:
	go test -tags=integration -count=1 -timeout 15m ./test/integration/...

test-all: test test-integration

fmt:
	gofmt -l -w .

vet:
	go vet ./...

lint:
	golangci-lint run ./...

deps:
	go mod download
	go mod tidy

# Requires golang-migrate. The same SQL also seeds a local Postgres container
# through docker-compose.
migrate-up:
	migrate -path $(MIGRATIONS) -database "$(DB_URL)" up

migrate-down:
	migrate -path $(MIGRATIONS) -database "$(DB_URL)" down 1

clean:
	rm -rf bin/
	go clean

docker-build:
	docker build -t auth-service:latest .

docker-up:
	docker compose up -d

docker-down:
	docker compose down
