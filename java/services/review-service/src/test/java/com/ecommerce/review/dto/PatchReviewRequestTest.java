package com.ecommerce.review.dto;

import com.ecommerce.review.common.ReviewException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three states the PATCH contract needs per field: absent preserves, explicit null
 * clears, and an empty array removes every attachment.
 */
class PatchReviewRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anAbsentContentFieldIsNotAClear() {
        PatchReviewRequest request = parse("{\"rating\": 4}");

        assertThat(request.rating().present()).isTrue();
        assertThat(request.rating().value()).isEqualTo(4);
        // The distinction this whole type exists for: a rating-only edit must not wipe
        // the review body.
        assertThat(request.content().present()).isFalse();
        assertThat(request.mediaAssetIds().present()).isFalse();
    }

    @Test
    void anExplicitNullContentClearsIt() {
        PatchReviewRequest request = parse("{\"content\": null}");

        assertThat(request.content().present()).isTrue();
        assertThat(request.content().value()).isNull();
    }

    @Test
    void anEmptyMediaArrayRemovesEveryAttachment() {
        PatchReviewRequest request = parse("{\"media_asset_ids\": []}");

        assertThat(request.mediaAssetIds().present()).isTrue();
        assertThat(request.mediaAssetIds().value()).isEmpty();
    }

    @Test
    void aSuppliedMediaListReplacesTheAttachments() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PatchReviewRequest request = parse(
                "{\"media_asset_ids\": [\"" + first + "\", \"" + second + "\"]}");

        assertThat(request.mediaAssetIds().value()).isEqualTo(List.of(first, second));
    }

    @Test
    void anEmptyBodyIsRejected() {
        assertThatThrownBy(() -> parse("{}"))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("at least one field");
    }

    /** The contract declares additionalProperties: false. */
    @Test
    void anUnknownFieldIsRejected() {
        assertThatThrownBy(() -> parse("{\"author_name\": \"someone\"}"))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("author_name");
    }

    @Test
    void blankContentIsNotAcceptableText() {
        assertThatThrownBy(() -> parse("{\"content\": \"   \"}"))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("non-blank");
    }

    @Test
    void ratingStaysWithinOneToFive() {
        assertThatThrownBy(() -> parse("{\"rating\": 6}"))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("between 1 and 5");
    }

    @Test
    void moreThanNineAttachmentsIsRejected() {
        StringBuilder body = new StringBuilder("{\"media_asset_ids\": [");
        for (int i = 0; i < 10; i++) {
            body.append(i == 0 ? "\"" : ", \"").append(UUID.randomUUID()).append('"');
        }
        body.append("]}");

        assertThatThrownBy(() -> parse(body.toString()))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("at most 9 attachments");
    }

    @Test
    void duplicateAssetIdsAreRejected() {
        UUID duplicated = UUID.randomUUID();
        assertThatThrownBy(() -> parse(
                "{\"media_asset_ids\": [\"" + duplicated + "\", \"" + duplicated + "\"]}"))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("unique");
    }

    private PatchReviewRequest parse(String json) {
        try {
            return PatchReviewRequest.from(mapper.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("test fixture is not JSON", e);
        }
    }
}
