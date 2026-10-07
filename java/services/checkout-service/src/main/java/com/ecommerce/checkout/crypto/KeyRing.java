package com.ecommerce.checkout.crypto;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Versioned secrets parsed from {@code v1:<base64>,v2:<base64>}. The active version signs and
 * encrypts new data; the older versions stay readable until every record that used them has
 * expired (data.md, "Nguyen tac").
 */
public final class KeyRing {

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9_-]{1,16}$");

    private final Map<String, byte[]> keys;
    private final String activeVersion;

    private KeyRing(Map<String, byte[]> keys, String activeVersion) {
        this.keys = keys;
        this.activeVersion = activeVersion;
    }

    /**
     * @param spec        {@code version:base64} pairs separated by commas
     * @param active      version used for new data; must be present in {@code spec}
     * @param minBytes    minimum decoded key length
     * @param exactBytes  required decoded length, or {@code 0} when only the minimum applies
     */
    public static KeyRing parse(String spec, String active, int minBytes, int exactBytes) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new IllegalArgumentException("key entry must look like version:base64");
            }
            String version = trimmed.substring(0, colon);
            if (!VERSION.matcher(version).matches()) {
                throw new IllegalArgumentException("invalid key version");
            }
            byte[] key;
            try {
                key = Base64.getDecoder().decode(trimmed.substring(colon + 1));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("key " + version + " is not valid base64", e);
            }
            if (key.length < minBytes || (exactBytes > 0 && key.length != exactBytes)) {
                throw new IllegalArgumentException("key " + version + " has the wrong length");
            }
            if (keys.put(version, key) != null) {
                throw new IllegalArgumentException("duplicate key version " + version);
            }
        }
        if (!keys.containsKey(active)) {
            throw new IllegalArgumentException("active key version " + active + " is not configured");
        }
        return new KeyRing(Map.copyOf(keys), active);
    }

    public String activeVersion() {
        return activeVersion;
    }

    public byte[] key(String version) {
        byte[] key = keys.get(version);
        if (key == null) {
            throw new IllegalArgumentException("unknown key version " + version);
        }
        return key;
    }

    public boolean has(String version) {
        return keys.containsKey(version);
    }

    /** Active version first, then every other configured version. */
    public List<String> versionsActiveFirst() {
        List<String> versions = new ArrayList<>();
        versions.add(activeVersion);
        keys.keySet().stream().filter(version -> !version.equals(activeVersion)).sorted().forEach(versions::add);
        return versions;
    }
}
