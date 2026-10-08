package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class CanonicalEncoderTest {
    @Test
    void streamingHashPreservesOriginalLengthPrefixedUtf8Bytes() throws NoSuchAlgorithmException {
        CanonicalEncoder encoder = new CanonicalEncoder();
        StringBuilder originalEncoding = new StringBuilder();
        for (String field : new String[] {"", "ASCII", "žlutý", "\uD83D\uDE00", "\uD800", null}) {
            encoder.field(field);
            appendOriginalField(originalEncoding, field);
        }
        encoder.field(42L).field(true);
        appendOriginalField(originalEncoding, "42");
        appendOriginalField(originalEncoding, "true");

        assertEquals(originalHash(originalEncoding), encoder.sha256());
        assertEquals(originalHash(originalEncoding), encoder.sha256(), "hashing must not consume the state");
        encoder.field("after-hash");
        appendOriginalField(originalEncoding, "after-hash");
        assertEquals(originalHash(originalEncoding), encoder.sha256());
    }

    private static void appendOriginalField(StringBuilder encoded, String field) {
        String safe = field == null ? "" : field;
        encoded.append(safe.length()).append(':').append(safe);
    }

    private static String originalHash(StringBuilder encoded) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(encoded.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
