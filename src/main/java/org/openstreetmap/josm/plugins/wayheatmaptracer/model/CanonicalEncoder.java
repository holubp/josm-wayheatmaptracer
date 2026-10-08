package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Length-prefixed canonical encoding helper for review and stale-state identities. */
final class CanonicalEncoder {
    private final MessageDigest digest;

    CanonicalEncoder() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime has no SHA-256 implementation", exception);
        }
    }

    CanonicalEncoder field(String text) {
        String safe = text == null ? "" : text;
        digest.update(Integer.toString(safe.length()).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(safe.getBytes(StandardCharsets.UTF_8));
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
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException exception) {
            throw new IllegalStateException("SHA-256 provider cannot snapshot digest state", exception);
        }
    }
}
