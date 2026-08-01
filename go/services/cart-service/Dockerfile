# ---- Build Stage ----
FROM golang:1.22-alpine AS builder

RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -ldflags="-s -w -extldflags '-static'" \
    -o /app/cart-service \
    ./cmd/server

# ---- Runtime Stage (Alpine) ----
FROM alpine:3.19

RUN apk add --no-cache ca-certificates tzdata wget

WORKDIR /app

COPY --from=builder /app/cart-service /app/cart-service

COPY configs/ /app/configs/

RUN addgroup -S nonroot && adduser -S nonroot -G nonroot
USER nonroot:nonroot

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["wget", "-q", "-O-", "http://localhost:8080/healthz"]

ENTRYPOINT ["/app/cart-service"]
