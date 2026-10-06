package com.ecommerce.review.storage;

import com.ecommerce.review.config.ReviewProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The S3-compatible adapter (MinIO locally, any S3 API in a deployed environment).
 *
 * <p>The upload signature pins {@code Content-Type} and {@code Content-Length}, and it
 * carries {@code If-None-Match: *} so the storage server refuses to overwrite an
 * existing object. That is what makes the metadata Review verifies before attachment
 * still describe the bytes it attaches: without it a caller could re-upload different
 * content between the check and the attach (ADR-011).
 */
@Component
public class S3ReviewObjectStorage implements ReviewObjectStorage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ReviewObjectStorage(S3Client client, S3Presigner presigner, ReviewProperties properties) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = properties.storage().bucket();
    }

    @Override
    public UploadInstruction presignUpload(String objectKey, String contentType, long byteSize, Duration ttl) {
        try {
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType)
                    .contentLength(byteSize)
                    .overrideConfiguration(o -> o.putHeader("If-None-Match", "*"))
                    .build();
            var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .putObjectRequest(put)
                    .build());

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", contentType);
            headers.put("Content-Length", Long.toString(byteSize));
            headers.put("If-None-Match", "*");
            return new UploadInstruction("PUT", presigned.url().toString(), headers,
                    presigned.expiration());
        } catch (S3Exception | software.amazon.awssdk.core.exception.SdkClientException e) {
            throw new StorageUnavailableException("could not sign a review media upload", e);
        }
    }

    @Override
    public Optional<StoredObject> head(String objectKey) {
        try {
            HeadObjectResponse response = client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(objectKey).build());
            return Optional.of(new StoredObject(
                    response.contentType(),
                    response.contentLength() == null ? 0L : response.contentLength(),
                    normalizeEtag(response.eTag())));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new StorageUnavailableException("could not read review media metadata", e);
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            throw new StorageUnavailableException("could not read review media metadata", e);
        }
    }

    @Override
    public String presignDownload(String objectKey, Duration ttl) {
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
                            .build())
                    .url().toString();
        } catch (S3Exception | software.amazon.awssdk.core.exception.SdkClientException e) {
            throw new StorageUnavailableException("could not sign a review media download", e);
        }
    }

    @Override
    public DeleteOutcome delete(String objectKey) {
        try {
            if (head(objectKey).isEmpty()) {
                return DeleteOutcome.NOT_FOUND;
            }
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return DeleteOutcome.DELETED;
        } catch (NoSuchKeyException e) {
            return DeleteOutcome.NOT_FOUND;
        } catch (S3Exception | software.amazon.awssdk.core.exception.SdkClientException e) {
            throw new StorageUnavailableException("could not delete review media object", e);
        }
    }

    /** S3 quotes ETags; the stored checksum is compared as a plain hex string. */
    private static String normalizeEtag(String etag) {
        if (etag == null) {
            return null;
        }
        return etag.replace("\"", "");
    }
}
