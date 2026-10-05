# syntax=docker/dockerfile:1

FROM golang:1.25-alpine AS builder

WORKDIR /app

RUN apk add --no-cache ca-certificates

COPY go.mod go.sum ./
RUN go mod download

COPY . .

# One binary, many roles (docs/notification/13-development.md §1). Templates
# and the catalog are embedded, so the image carries no template files.
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags '-w -s' -o notification-service ./cmd/server \
 && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags '-w -s' -o notifctl ./cmd/notifctl

FROM alpine:3.20

RUN apk --no-cache add ca-certificates tzdata wget

WORKDIR /app

COPY --from=builder /app/notification-service /app/notifctl ./
COPY --from=builder /app/configs ./configs

RUN adduser -D -g '' appuser
USER appuser

# 8080 serves the inbox API behind the gateway; 8081 is the cluster-internal
# listener (status, ops, /metrics, health) and must never be published.
EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD wget --no-verbose --tries=1 -O /dev/null http://localhost:8081/livez || exit 1

ENTRYPOINT ["./notification-service"]
CMD ["-config", "./configs/application.yaml"]
