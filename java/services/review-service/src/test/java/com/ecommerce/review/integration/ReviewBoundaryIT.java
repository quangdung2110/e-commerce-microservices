package com.ecommerce.review.integration;

import com.ecommerce.review.domain.MediaType;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.service.MediaAssetService;
import com.ecommerce.review.service.ReviewCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Regression coverage of the real HTTP boundary and storage bytes after attachment. */
class ReviewBoundaryIT extends AbstractReviewIT {
    @Autowired MediaAssetService media;
    @Autowired ReviewCommandService reviews;
    @LocalServerPort int port;

    @Test
    void signedConditionalPutMustNotOverwriteVerifiedAttachedBytes() throws Exception {
        UUID buyer=UUID.randomUUID();
        UUID eligibility=grantEntitlement("qa-overwrite","qa-product","qa-sku",buyer);
        byte[] original="AAAA".getBytes(), replacement="BBBB".getBytes();
        var issued=media.issueUploadInstruction(buyer,new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE,"image/jpeg",4L,null));
        assertThat(issued.upload().headers()).containsEntry("If-None-Match","*");
        upload(issued,original);
        reviews.create(buyer,new CommandDtos.CreateReviewRequest(eligibility,5,null,List.of(issued.assetId())));
        var request=HttpRequest.newBuilder(URI.create(issued.upload().url())).PUT(HttpRequest.BodyPublishers.ofByteArray(replacement));
        issued.upload().headers().forEach((name,value)->{if(!name.equalsIgnoreCase("Content-Length"))request.header(name,value);});
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
        String key=jdbc.sql("SELECT object_key FROM review_media_assets WHERE id=?").param(issued.assetId()).query(String.class).single();
        byte[] actual=s3.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.storage().bucket()).key(key).build()).asByteArray();
        System.out.println("QA_BOUNDARY conditional_header_signed=true second_put_status="+response.statusCode()+" attached_bytes_changed="+!Arrays.equals(actual,original));
        assertThat(response.statusCode()).isEqualTo(412);
        assertThat(actual).isEqualTo(original);
        var stripped = HttpRequest.newBuilder(URI.create(issued.upload().url()))
                .header("Content-Type", "image/jpeg").PUT(HttpRequest.BodyPublishers.ofByteArray(replacement));
        assertThat(HttpClient.newHttpClient().send(stripped.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isIn(400, 403);
        assertThat(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.storage().bucket()).key(key).build())
                .asByteArray()).isEqualTo(original);
    }

    @Test
    void fractionalRatingMustBeRejectedWithoutClaimingEligibility() throws Exception {
        UUID buyer=UUID.randomUUID(),eligibility=grantEntitlement("qa-fraction","qa-product","qa-sku",buyer);
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/reviews"))
            .header("Content-Type","application/json").header("X-User-Id",buyer.toString())
            .POST(HttpRequest.BodyPublishers.ofString("{\"eligibility_id\":\""+eligibility+"\",\"rating\":1.5}"));
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
        System.out.println("QA_BOUNDARY fractional_rating_http="+response.statusCode()+" eligibility_status="+eligibilityStatus(eligibility));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(eligibilityStatus(eligibility).name()).isEqualTo("PENDING");
    }

    @Test
    void numericStringsAndFractionalSizesCannotConsumeRightsOrUploadQuota() throws Exception {
        UUID buyer = UUID.randomUUID(), eligibility = grantEntitlement("strict-number", "p", "sku", buyer);
        HttpClient client = HttpClient.newHttpClient();
        for (String value : List.of("1.0", "\"1\"", "1.5")) {
            String body = "{\"eligibility_id\":\"" + eligibility + "\",\"rating\":" + value + "}";
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/reviews"))
                    .header("Content-Type", "application/json").header("X-User-Id", buyer.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
        }
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/reviews/media/upload-urls"))
                .header("Content-Type", "application/json").header("X-User-Id", buyer.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"media_type\":\"IMAGE\",\"content_type\":\"image/png\",\"byte_size\":1.5}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(eligibilityStatus(eligibility).name()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT count(*) FROM review_media_assets").query(Long.class).single()).isZero();
    }
}
