package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Length-prefixed canonical encoding helper for review and stale-state identities. */
final class CanonicalEncoder {
    private final StringBuilder value = new StringBuilder();

    CanonicalEncoder field(String text) {
        String safe = text == null ? "" : text;
        value.append(safe.length()).append(':').append(safe);
        return this;
    }

    CanonicalEncoder field(long number) {
        return field(Long.toString(number));
    }

    CanonicalEncoder field(boolean flag) {
        return field(Boolean.toString(flag));
    }

    String sha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime has no SHA-256 implementation", exception);
        }
    }
}
