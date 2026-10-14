package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CanonicalEncoderTest {
    @Test
    void hexadecimalDoubleFieldsPreserveJava17CanonicalBytes() {
        long[] edges = {0L, Long.MIN_VALUE, 1L, Long.MIN_VALUE | 1L,
            0x000fffffffffffffL, 0x0010000000000000L, 0x3ff0000000000000L,
            0x3ff0000000000001L, 0x7fefffffffffffffL, 0x7ff0000000000000L,
            0xfff0000000000000L, 0x7ff0000000000001L, 0xfff8000000000001L};
        for (long bits : edges) {
            assertHexEncoding(bits);
        }
        Random random = new Random(0x022caL);
        for (int index = 0; index < 20000; index++) {
            assertHexEncoding(random.nextLong());
        }
    }

    @Test
    void repeatedHexadecimalFieldsPreserveFramingAndDigestSnapshots() {
        CanonicalEncoder expected = new CanonicalEncoder().field("žlutý");
        CanonicalEncoder actual = new CanonicalEncoder().field("žlutý");
        for (double value : new double[] {0.0, -0.0, Double.MIN_VALUE, 0.125,
                Double.MAX_VALUE, Double.NaN, Double.NEGATIVE_INFINITY}) {
            expected.field(true).field(Double.toHexString(value)).field("cell-support").field(false);
            actual.field(true).fieldHexDouble(value).field("cell-support").field(false);
            assertEquals(expected.sha256(), actual.sha256());
            assertEquals(expected.sha256(), actual.sha256());
        }
    }

    private static void assertHexEncoding(long bits) {
        double value = Double.longBitsToDouble(bits);
        assertEquals(new CanonicalEncoder().field(Double.toHexString(value)).sha256(),
            new CanonicalEncoder().fieldHexDouble(value).sha256(),
            () -> "Raw bits: " + Long.toHexString(bits));
    }

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
