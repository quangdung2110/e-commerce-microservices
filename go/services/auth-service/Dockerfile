# syntax=docker/dockerfile:1

FROM golang:1.25-alpine AS builder

WORKDIR /app

RUN apk add --no-cache ca-certificates

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags '-w -s' -o auth-service ./cmd/server

FROM alpine:3.20

RUN apk --no-cache add ca-certificates tzdata wget

WORKDIR /app

COPY --from=builder /app/auth-service .
COPY --from=builder /app/configs ./configs

RUN adduser -D -g '' appuser
USER appuser

# 8080 is the public API, 8081 the cluster-internal notification delivery API.
# The internal port must never be published through the gateway.
EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    # Khong dung --spider: no gui HEAD, ma route /health/live chi dang ky GET
    # nen gin tra 405 va container luon bi danh dau unhealthy.
    CMD wget --no-verbose --tries=1 -O /dev/null http://localhost:8080/health/live || exit 1

ENTRYPOINT ["./auth-service"]
CMD ["-config", "./configs/application.yaml"]
