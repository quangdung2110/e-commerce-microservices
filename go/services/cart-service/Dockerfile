# ---- Build Stage ----
FROM golang:1.25-alpine AS builder

RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /app

COPY observability /observability
COPY services/cart-service/go.mod services/cart-service/go.sum ./
RUN go mod download

COPY services/cart-service/ .

RUN --mount=type=cache,target=/root/.cache/go-build CGO_ENABLED=0 GOOS=linux go build -p 2 \
    -ldflags="-s -w" \
    -o /app/cart-service \
    ./cmd/server

# ---- Runtime Stage ----
FROM alpine:3.19

RUN apk add --no-cache ca-certificates tzdata wget

WORKDIR /app

COPY --from=builder /app/cart-service /app/cart-service
COPY services/cart-service/configs/ /app/configs/

RUN addgroup -S nonroot && adduser -S nonroot -G nonroot
USER nonroot:nonroot

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["wget", "-q", "-O-", "http://localhost:8080/health/live"]

ENTRYPOINT ["/app/cart-service"]
