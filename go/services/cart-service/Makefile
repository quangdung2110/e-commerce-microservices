.PHONY: all build run test test-unit test-coverage lint fmt deps generate docker-build docker-up docker-down clean help

BINARY_NAME=cart-service
DOCKER_IMAGE=cart-service:local

# Canonical contract sources. The Lua scripts and the Product proto both live
# outside this service; the targets below copy or generate from them so the
# service never becomes the source of truth for either.
REPO_ROOT=../../..
CONTRACTS_DIR=$(REPO_ROOT)/contracts
LUA_SOURCE_DIR=$(REPO_ROOT)/docs/cart/scripts
LUA_TARGET_DIR=internal/infrastructure/cartredis/scripts
PB_DIR=internal/infrastructure/product/pb
PB_MODULE=cart-service/$(PB_DIR)

all: build

build:
	go build -o bin/$(BINARY_NAME) ./cmd/server

run:
	go run ./cmd/server -config configs/application.yaml

test:
	go test -race -coverprofile=coverage.out ./...

test-unit:
	go test -race -short ./...

test-coverage: test
	go tool cover -html=coverage.out -o coverage.html

lint:
	golangci-lint run ./...

fmt:
	go fmt ./...
	go vet ./...

deps:
	go mod download
	go mod tidy

## sync-lua copies the canonical Lua scripts from docs/cart/scripts.
sync-lua:
	cp $(LUA_SOURCE_DIR)/upsert_item.lua $(LUA_TARGET_DIR)/
	cp $(LUA_SOURCE_DIR)/remove_items.lua $(LUA_TARGET_DIR)/
	cp $(LUA_SOURCE_DIR)/mark_abandoned.lua $(LUA_TARGET_DIR)/
	cp $(LUA_SOURCE_DIR)/purge_cart.lua $(LUA_TARGET_DIR)/

## check-lua fails when the embedded scripts have drifted from the canonical ones.
check-lua:
	diff -u $(LUA_SOURCE_DIR)/upsert_item.lua $(LUA_TARGET_DIR)/upsert_item.lua
	diff -u $(LUA_SOURCE_DIR)/remove_items.lua $(LUA_TARGET_DIR)/remove_items.lua
	diff -u $(LUA_SOURCE_DIR)/mark_abandoned.lua $(LUA_TARGET_DIR)/mark_abandoned.lua
	diff -u $(LUA_SOURCE_DIR)/purge_cart.lua $(LUA_TARGET_DIR)/purge_cart.lua

## generate regenerates the Product gRPC stubs from the canonical proto.
generate:
	rm -rf $(PB_DIR)
	mkdir -p $(PB_DIR)
	protoc -I $(CONTRACTS_DIR)/grpc -I $(CONTRACTS_DIR)/shared/proto \
		--go_out=$(PB_DIR) --go_opt=module=$(PB_MODULE) \
		--go_opt=Mecommerce/product/v1/product_service.proto=$(PB_MODULE)/productv1 \
		--go_opt=Mecommerce/common/v1/money.proto=$(PB_MODULE)/commonv1 \
		--go-grpc_out=$(PB_DIR) --go-grpc_opt=module=$(PB_MODULE) \
		--go-grpc_opt=Mecommerce/product/v1/product_service.proto=$(PB_MODULE)/productv1 \
		--go-grpc_opt=Mecommerce/common/v1/money.proto=$(PB_MODULE)/commonv1 \
		ecommerce/product/v1/product_service.proto ecommerce/common/v1/money.proto

docker-build:
	docker build -t $(DOCKER_IMAGE) .

docker-up:
	docker compose up -d

docker-down:
	docker compose down -v

health:
	curl -s http://localhost:8080/health/ready

clean:
	rm -rf bin/
	rm -f coverage.out coverage.html

help:
	@grep -E '^##' $(MAKEFILE_LIST) | sed 's/## //'
