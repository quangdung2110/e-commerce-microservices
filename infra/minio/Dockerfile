# Official prebuilt community images are no longer distributed. Build the pinned
# release from the upstream archive; the digest prevents a moved tag changing it.
FROM golang:1.26.11-bookworm AS build
ADD --checksum=sha256:be6d0bd3696c3a13a35f02d3a0280b64319c67918b4501c5c3d87f96d000085c https://codeload.github.com/minio/minio/tar.gz/refs/tags/RELEASE.2025-10-15T17-29-55Z /tmp/minio.tar.gz
RUN mkdir /src && tar -xzf /tmp/minio.tar.gz -C /src --strip-components=1
WORKDIR /src
# Keep the upstream server release while applying fixes to dependencies in its binary.
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go get github.com/apache/thrift@v0.24.0 github.com/buger/jsonparser@v1.1.2 \
        github.com/go-jose/go-jose/v4@v4.1.6 github.com/klauspost/compress@v1.18.7 \
        github.com/prometheus/prometheus@v0.311.3 github.com/rabbitmq/amqp091-go@v1.13.0 \
        go.opentelemetry.io/otel/sdk@v1.44.0 golang.org/x/crypto@v0.58.0 \
        golang.org/x/net@v0.62.0 golang.org/x/text@v0.43.0 google.golang.org/grpc@v1.83.2 \
    && CGO_ENABLED=0 go build -mod=mod -trimpath -o /out/minio .

FROM alpine:3.23
COPY --from=build /out/minio /usr/bin/minio
EXPOSE 9000 9001
ENTRYPOINT ["/usr/bin/minio"]
CMD ["server", "/data", "--console-address", ":9001"]
