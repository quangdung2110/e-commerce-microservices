# syntax=docker/dockerfile:1

FROM golang:1.25-alpine AS builder

WORKDIR /app

RUN apk add --no-cache ca-certificates

COPY observability /observability
COPY services/user-service/go.mod services/user-service/go.sum ./
RUN go mod download

COPY services/user-service/ .

RUN --mount=type=cache,target=/root/.cache/go-build CGO_ENABLED=0 GOOS=linux go build -p 2 -trimpath -ldflags '-w -s' -o user-service ./cmd/server

FROM alpine:3.20

RUN apk --no-cache add ca-certificates tzdata wget

WORKDIR /app

COPY --from=builder /app/user-service .
COPY --from=builder /app/configs ./configs

RUN adduser -D -g '' appuser
USER appuser

# 8082 is the public API, 8083 the cluster-internal checkout snapshot API.
# The internal port must never be published through the gateway.
EXPOSE 8082 8083

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8082/health/ready >/dev/null || exit 1

ENTRYPOINT ["./user-service"]
CMD ["-config", "./configs/application.yaml"]
