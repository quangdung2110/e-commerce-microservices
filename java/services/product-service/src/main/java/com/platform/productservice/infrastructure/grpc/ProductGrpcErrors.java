package com.platform.productservice.infrastructure.grpc;

import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import com.google.rpc.RetryInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code google.rpc.Status} that carries {@link ErrorInfo} for every error Product itself returns
 * (contracts ADR-006). {@code description} stays human text for logs; callers read {@code reason}.
 */
public final class ProductGrpcErrors {

    /** Wait advertised with a retryable {@code UNAVAILABLE}, the same default as HTTP {@code Retry-After} (ADR-005). */
    static final long RETRY_DELAY_SECONDS = 2;

    private ProductGrpcErrors() {
    }

    /** The error as an exception, for {@code StreamObserver.onError} or an advice handler. */
    public static StatusRuntimeException exception(ProductGrpcError error, String message) {
        return exception(error, message, Map.of(), null);
    }

    /**
     * @param violationField request field named in {@code BadRequest.field_violations}; used for
     *                       {@code INVALID_ARGUMENT} and ignored otherwise
     */
    public static StatusRuntimeException exception(ProductGrpcError error, String message,
                                                   Map<String, String> extraMetadata, String violationField) {
        return StatusProto.toStatusRuntimeException(rpcStatus(error, message, extraMetadata, violationField));
    }

    /** The error as a status plus trailers, for {@code ServerCall.close}. */
    public static Result close(ProductGrpcError error, String message) {
        StatusRuntimeException e = exception(error, message);
        Metadata trailers = e.getTrailers() == null ? new Metadata() : e.getTrailers();
        return new Result(e.getStatus(), trailers);
    }

    public record Result(Status status, Metadata trailers) {
    }

    static com.google.rpc.Status rpcStatus(ProductGrpcError error, String message,
                                           Map<String, String> extraMetadata, String violationField) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("retryable", String.valueOf(error.retryable()));
        metadata.putAll(extraMetadata);

        com.google.rpc.Status.Builder status = com.google.rpc.Status.newBuilder()
                .setCode(error.status().value())
                .setMessage(message)
                .addDetails(Any.pack(ErrorInfo.newBuilder()
                        .setDomain(ProductGrpcError.DOMAIN)
                        .setReason(error.name())
                        .putAllMetadata(metadata)
                        .build()));
        if (error.status() == Status.Code.INVALID_ARGUMENT && violationField != null) {
            status.addDetails(Any.pack(BadRequest.newBuilder()
                    .addFieldViolations(BadRequest.FieldViolation.newBuilder()
                            .setField(violationField)
                            .setDescription(message))
                    .build()));
        }
        if (error.status() == Status.Code.UNAVAILABLE) {
            status.addDetails(Any.pack(RetryInfo.newBuilder()
                    .setRetryDelay(Duration.newBuilder().setSeconds(RETRY_DELAY_SECONDS))
                    .build()));
        }
        return status.build();
    }
}
