.PHONY: build run test test-integration vet fmt lint clean migrate-up migrate-down docker-build docker-go

BIN        := bin/notification-service
CTL        := bin/notifctl
CONFIG     := ./configs/application.yaml
DB_URL     ?= postgres://postgres:postgres@localhost:5432/notification_db?sslmode=disable

# The dev machine may have no Go toolchain: `make docker-go CMD="test ./..."`
# runs any go command inside golang:1.25 (scripts/go.sh).
docker-go:
	bash scripts/go.sh $(CMD)

build:
	go build -o $(BIN) ./cmd/server
	go build -o $(CTL) ./cmd/notifctl

run:
	NOTIFICATION_APP_ROLE=all go run ./cmd/server -config $(CONFIG)

# Unit + golden + contract tests; no Docker needed.
test:
	go test -race -cover ./internal/...

# Integration suite: testcontainers (Postgres, Redis, Kafka, Mailpit, LocalStack).
test-integration:
	go test -tags=integration -count=1 -timeout 30m ./test/integration/... ./internal/...

vet:
	go vet ./...

fmt:
	gofmt -l -w .

lint:
	golangci-lint run ./...

# Update golden files after a deliberate template change (12-render.md §8).
golden-update:
	go test ./internal/templates/ -update

migrate-up:
	go run ./cmd/notifctl migrate up --dsn "$(DB_URL)"

migrate-down:
	go run ./cmd/notifctl migrate down --dsn "$(DB_URL)"

clean:
	rm -rf bin/
	go clean

docker-build:
	docker build -t notification-service:latest .
