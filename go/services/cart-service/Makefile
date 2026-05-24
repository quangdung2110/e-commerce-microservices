.PHONY: all build test lint clean run docker-build docker-up docker-down

BINARY_NAME=cart-service
DOCKER_IMAGE=cart-service:latest

all: build

build:
	go build -o bin/$(BINARY_NAME) ./cmd/server

test:
	go test -v -race -coverprofile=coverage.out ./...

test-unit:
	go test -v -race -short ./...

test-integration:
	go test -v -race -tags=integration ./test/integration/...

test-coverage: test
	go tool cover -html=coverage.out -o coverage.html

lint:
	golangci-lint run ./...

lint-fix:
	golangci-lint run --fix ./...

generate:
	cd proto && protoc --go_out=../internal/infrastructure/grpc --go-grpc_out=../internal/infrastructure/grpc *.proto
	cd proto && protoc --grpc-web_out=../internal/infrastructure/grpc --js_out=import_style=commonjs:../internal/infrastructure/grpc *.proto

migrate-create:
	@read -p "Migration name: " name && \
	migrate create -ext .up.sql -dir internal/infrastructure/postgres/migrations -seq $$name

migrate-up:
	migrate -path internal/infrastructure/postgres/migrations -database "postgres://$(DB_USER):$(DB_PASSWORD)@$(DB_HOST):$(DB_PORT)/$(DB_NAME)?sslmode=disable" up

migrate-down:
	migrate -path internal/infrastructure/postgres/migrations -database "postgres://$(DB_USER):$(DB_PASSWORD)@$(DB_HOST):$(DB_PORT)/$(DB_NAME)?sslmode=disable" down

docker-build:
	docker build -t $(DOCKER_IMAGE) .

docker-up:
	docker-compose up -d

docker-down:
	docker-compose down -v

docker-logs:
	docker-compose logs -f

run:
	go run ./cmd/server/main.go

dev:
	air -c .air.toml

deps:
	go mod download
	go mod tidy

security:
	govulncheck ./...

health:
	curl -s http://localhost:8080/healthz | jq .

proto-install:
	@which protoc || (echo "Install protoc: https://grpc.io/docs/protoc-installation/" && exit 1)
	@which protoc-gen-go || go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
	@which protoc-gen-go && go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

clean:
	rm -rf bin/
	rm -f coverage.out coverage.html
	docker system prune -f
