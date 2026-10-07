# syntax=docker/dockerfile:1

FROM golang:1.25-alpine AS builder

WORKDIR /app

RUN apk add --no-cache ca-certificates

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags '-w -s' -o gateway-auth ./cmd/server

FROM alpine:3.20

RUN apk --no-cache add ca-certificates tzdata wget

WORKDIR /app

COPY --from=builder /app/gateway-auth .
COPY --from=builder /app/configs ./configs

RUN adduser -D -g '' appuser
USER appuser

# Chỉ Traefik gọi cổng này; không bao giờ publish ra ngoài.
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 -O /dev/null http://localhost:8080/health/live || exit 1

ENTRYPOINT ["./gateway-auth"]
CMD ["-config", "./configs/application.yaml"]
