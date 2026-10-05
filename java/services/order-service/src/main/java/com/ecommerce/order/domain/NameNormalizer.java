package com.ecommerce.order.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pinned search normalizer v1 (data.md §3.3, ADR-017/020): Unicode NFKD, remove combining
 * marks, fold đ/Đ to d (NFKD does not decompose them) and lowercase with Locale.ROOT. The
 * same function writes {@code snapshot_name_search} and normalizes the query.
 */
public final class NameNormalizer {

    public static final int VERSION = 1;

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private NameNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        return stripped.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }
}
