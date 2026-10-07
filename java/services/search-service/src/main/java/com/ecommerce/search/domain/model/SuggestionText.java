package com.ecommerce.search.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Normalisation and identity of a suggestion, following {@code docs/search/data.md §1.2}
 * exactly.
 *
 * <p>{@code text_normalized} is the internal comparison key; {@code text} stays the display
 * form. The order of the steps matters and is fixed: Unicode NFC, locale-independent
 * lower-casing, ICU-style folding including Vietnamese diacritics, trim, then collapse
 * runs of whitespace. Punctuation is preserved.
 */
public final class SuggestionText {

    /** NUL, the separator the identity spec mandates between the four key parts. */
    private static final String NUL = "\0";

    private SuggestionText() {
    }

    /** @return the normalised comparison key, empty when nothing survives normalisation */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String lowered = nfc.toLowerCase(Locale.ROOT);
        String folded = fold(lowered);
        return folded.trim().replaceAll("\\s+", " ");
    }

    /**
     * Strips combining marks the way {@code icu_folding} does, and maps the Vietnamese
     * {@code đ} explicitly — it is a distinct letter rather than a d with a diacritic, so
     * decomposition alone would leave it untouched.
     */
    private static String fold(String lowered) {
        String decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return decomposed.replace('đ', 'd').replace('Đ', 'D');
    }

    /**
     * Deterministic id: SHA-256 over {@code type}, {@code scope}, {@code language} and
     * {@code text_normalized}, joined with a NUL separator in that order and encoded as
     * UTF-8. Because {@code type} is part of the key, a {@code PRODUCT} and a
     * {@code CATEGORY} suggestion with the same text stay two entries
     * ({@code SEARCH-BR-014}).
     */
    public static String suggestionId(String type, String scope, String language, String textNormalized) {
        String joined = String.join(NUL, type, scope, language, textNormalized);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE specification", e);
        }
    }
}
