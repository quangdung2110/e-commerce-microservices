package com.ecommerce.search.domain.model;

import com.ecommerce.search.common.SearchException;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/**
 * Signed product cursor: snapshot PIT + product offset + normalised query (ADR-020).
 *
 * <p>Generation 2 belongs to the SKU grouping and carries only the offset. Generation 3 belongs
 * to the {@code product_cards} projection (ADR-027) and also carries the {@code search_after} sort
 * values of the last Product served; its PIT is bound to a {@code product_cards} index, so a page
 * always continues on the path that produced it.
 */
public final class SearchCursor {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final ObjectMapper mapper;
    private final byte[] key;

    public SearchCursor(ObjectMapper mapper, String secret) {
        this.mapper = mapper;
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("cursor secret must have at least 32 characters");
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param after sort values of the last Product served by {@code product_cards}, or {@code null}
     *              for a SKU grouping cursor
     */
    public record Position(String pitId, int offset, String categoryId, List<Object> after) {
        public Position(String pitId, int offset) { this(pitId, offset, null, null); }
        public Position(String pitId, int offset, String categoryId) { this(pitId, offset, categoryId, null); }
        public Position {
            after = after == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(after));
        }
        public boolean productCards() { return after != null; }
    }
    private record Payload(int v, String query, String pitId, int offset, String categoryId, List<Object> after) {}

    public String encode(SearchQuery query, Position position) {
        try {
            String body = ENCODER.encodeToString(mapper.writeValueAsBytes(new Payload(position.productCards() ? 3 : 2,
                    query.fingerprintSource(), position.pitId(), position.offset(), position.categoryId(), position.after())));
            return body + "." + ENCODER.encodeToString(sign(body));
        } catch (Exception e) { throw new IllegalStateException("cursor could not be encoded", e); }
    }

    public Position decode(SearchQuery query, String cursor) {
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), DECODER.decode(parts[1]))) throw new IllegalArgumentException();
            Payload p = mapper.readValue(DECODER.decode(parts[0]), Payload.class);
            boolean generation = (p.v() == 2 && p.after() == null) || (p.v() == 3 && p.after() != null && !p.after().isEmpty());
            if (!generation || !query.fingerprintSource().equals(p.query()) || p.pitId() == null || p.pitId().isBlank() || p.offset() <= 0) throw new IllegalArgumentException();
            return new Position(p.pitId(), p.offset(), p.categoryId(), p.after());
        } catch (Exception e) {
            throw SearchException.invalidCursor("invalid, incompatible or changed-query cursor; restart pagination");
        }
    }

    private byte[] sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    }
}
