package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared bounded-input and privacy checks for additive Format-15 artifacts. */
final class Format15Safety {
    static final int MAX_ARTIFACTS = 512;
    static final int MAX_ARTIFACT_BYTES = 16 * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 128L * 1024L * 1024L;

    private Format15Safety() {
    }

    static String name(String value, boolean manifestAllowed) {
        if (value == null || value.isBlank() || value.length() > 180 || value.startsWith("/")
            || value.startsWith("\\") || value.contains("\\") || value.contains("\u0000")
            || value.endsWith("/") || value.equals(".") || value.equals("..")
            || value.contains("../") || value.contains("/../") || value.contains("..\\")) {
            throw new IllegalArgumentException("Unsafe diagnostic artifact name");
        }
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Unsafe diagnostic artifact path");
            }
        }
        if (!manifestAllowed && (value.equals("replay-manifest.json") || value.equals("manifest.json"))) {
            throw new IllegalArgumentException("Replay manifest is writer-owned");
        }
        return value;
    }

    static byte[] copyBounded(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Diagnostic artifact exceeds the per-file limit");
        }
        return bytes.clone();
    }

    static void requireSafeText(String text) {
        if (text == null || text.length() > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Diagnostic text is missing or too large");
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String[] forbidden = {
            "cloudfront-key-pair-id", "cloudfront-policy", "cloudfront-signature",
            "_strava_idcf", "cookie:", "authorization:", "proxy-authorization:"
        };
        for (String marker : forbidden) {
            if (lower.contains(marker)) {
                throw new IllegalArgumentException("Credential-bearing diagnostic text is not exportable");
            }
        }
    }

    static void requireSafeExportedMetadata(String value) {
        requireSafeText(value);
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        boolean rooted = !value.isEmpty()
                && (value.charAt(0) == '/' || value.charAt(0) == '\\');
        boolean driveAbsolute = value.length() >= 3
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                        || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))
                && value.charAt(1) == ':'
                && (value.charAt(2) == '/' || value.charAt(2) == '\\');
        if (rooted || driveAbsolute || lower.startsWith("file:")
                || lower.contains("x-amz-signature=")
                || lower.contains("signature=") || lower.contains("policy=")) {
            throw new IllegalArgumentException(
                    "Replay metadata contains a private path or signed value");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for Format-15 diagnostics", exception);
        }
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String requiredHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hash");
        }
        return value;
    }
}
