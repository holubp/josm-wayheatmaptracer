package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Length-prefixed canonical encoding helper for review and stale-state identities. */
final class CanonicalEncoder {
    private final MessageDigest digest;
    private final byte[] hexDoubleBuffer = new byte[32];

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

    /** Encodes exactly the ASCII spelling of Double.toHexString, without its regex work. */
    CanonicalEncoder fieldHexDouble(double number) {
        long bits = Double.doubleToRawLongBits(number);
        int exponent = (int) ((bits >>> 52) & 0x7ff);
        long fraction = bits & 0x000fffffffffffffL;
        if (exponent == 0x7ff) {
            return field(fraction != 0 ? "NaN" : bits < 0 ? "-Infinity" : "Infinity");
        }
        int length = 0;
        if (bits < 0) {
            hexDoubleBuffer[length++] = '-';
        }
        hexDoubleBuffer[length++] = '0';
        hexDoubleBuffer[length++] = 'x';
        hexDoubleBuffer[length++] = exponent == 0 ? (byte) '0' : (byte) '1';
        hexDoubleBuffer[length++] = '.';
        int fractionStart = length;
        for (int shift = 48; shift >= 0; shift -= 4) {
            int digit = (int) ((fraction >>> shift) & 0xf);
            hexDoubleBuffer[length++] = (byte) (digit < 10 ? '0' + digit : 'a' + digit - 10);
        }
        while (length > fractionStart + 1 && hexDoubleBuffer[length - 1] == '0') {
            length--;
        }
        hexDoubleBuffer[length++] = 'p';
        int binaryExponent = exponent == 0 ? (fraction == 0 ? 0 : -1022) : exponent - 1023;
        String exponentText = Integer.toString(binaryExponent);
        for (int index = 0; index < exponentText.length(); index++) {
            hexDoubleBuffer[length++] = (byte) exponentText.charAt(index);
        }
        digest.update(Integer.toString(length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(hexDoubleBuffer, 0, length);
        return this;
    }

    String sha256() {
        try {
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException exception) {
            throw new IllegalStateException("SHA-256 provider cannot snapshot digest state", exception);
        }
    }
}
