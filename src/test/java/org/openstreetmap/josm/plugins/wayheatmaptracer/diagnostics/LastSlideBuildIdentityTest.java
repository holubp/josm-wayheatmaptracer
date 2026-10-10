package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LastSlideBuildIdentityTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 65535, 65536, 65537, 196731})
    void currentFileMatchesIndependentCompleteByteDigest(int length, @TempDir Path directory) throws Exception {
        byte[] bytes = content(length);
        Path jar = directory.resolve("plugin.jar");
        Files.write(jar, bytes);

        assertEquals(expected(bytes), LastSlideDebugBundle.buildIdentityFromPath(jar));
    }

    @Test
    void streamsCompleteFragmentedInputWithOneBoundedBufferAndNoWholeRead() throws Exception {
        byte[] bytes = content(4 * 65536 + 27);
        TrackingInput input = new TrackingInput(bytes);

        String identity = LastSlideDebugBundle.buildIdentityFromStream(
                input, MessageDigest.getInstance("SHA-256"));

        assertEquals(expected(bytes), identity);
        assertEquals(bytes.length, input.readBytes);
        assertEquals(1, input.closes);
        assertEquals(0, input.wholeReads, "The identity must not materialize the entire input");
        assertTrue(input.maximumOfferedBuffer <= 65536);
        assertEquals(1, input.distinctBuffers, "Every read must reuse the same bounded buffer");
    }

    @Test
    void sameSizeSameTimestampChangesAndReplacementAreReadFresh(@TempDir Path directory) throws Exception {
        Path jar = directory.resolve("plugin.jar");
        byte[] first = content(257);
        Files.write(jar, first);
        var timestamp = Files.getLastModifiedTime(jar);
        String original = LastSlideDebugBundle.buildIdentityFromPath(jar);
        assertEquals(expected(first), original);

        byte[] changed = first.clone();
        changed[128] ^= 0x55;
        Files.write(jar, changed);
        Files.setLastModifiedTime(jar, timestamp);
        assertEquals(expected(changed), LastSlideDebugBundle.buildIdentityFromPath(jar));
        assertNotEquals(original, LastSlideDebugBundle.buildIdentityFromPath(jar));

        byte[] replacement = first.clone();
        replacement[129] ^= 0x33;
        Path next = directory.resolve("replacement.jar");
        Files.write(next, replacement);
        Files.setLastModifiedTime(next, timestamp);
        Files.move(next, jar, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(expected(replacement), LastSlideDebugBundle.buildIdentityFromPath(jar));

        Files.delete(jar);
        assertEquals("development", LastSlideDebugBundle.buildIdentityFromPath(jar));
    }

    @Test
    void missingDirectoryAndDevelopmentClassesKeepFallback(@TempDir Path directory) {
        assertEquals("development", LastSlideDebugBundle.buildIdentityFromPath(directory.resolve("missing.jar")));
        assertEquals("development", LastSlideDebugBundle.buildIdentityFromPath(directory));
        assertEquals("development", LastSlideDebugBundle.buildIdentity());
    }

    @Test
    void partialReadFailureKeepsFallbackAndClosesInput() throws Exception {
        TrackingInput input = new TrackingInput(content(65537));
        input.failRead = true;

        assertEquals("development", LastSlideDebugBundle.buildIdentityFromStream(
                input, MessageDigest.getInstance("SHA-256")));
        assertEquals(1, input.closes);
        assertTrue(input.readBytes > 0, "The failure follows a real partial read");
    }

    @Test
    void closeFailureKeepsFallbackAfterCompleteRead() throws Exception {
        byte[] bytes = content(65537);
        TrackingInput input = new TrackingInput(bytes);
        input.failClose = true;

        assertEquals("development", LastSlideDebugBundle.buildIdentityFromStream(
                input, MessageDigest.getInstance("SHA-256")));
        assertEquals(bytes.length, input.readBytes);
        assertEquals(1, input.closes);
    }

    private static byte[] content(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) bytes[index] = (byte) (index * 31 + (index >>> 8));
        return bytes;
    }

    /** Independent historical full-byte digest; never calls the implementation's hashing helper. */
    private static String expected(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                .substring(0, 16);
    }

    private static final class TrackingInput extends InputStream {
        private final byte[] bytes;
        private byte[] lastBuffer;
        private int readBytes, closes, wholeReads, maximumOfferedBuffer, distinctBuffers;
        private boolean failRead, failClose;

        private TrackingInput(byte[] bytes) { this.bytes = bytes; }

        @Override public int read() { throw new AssertionError("Use the bounded block read"); }

        @Override public byte[] readAllBytes() throws IOException {
            wholeReads++;
            return super.readAllBytes();
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            maximumOfferedBuffer = Math.max(maximumOfferedBuffer, buffer.length);
            if (lastBuffer != buffer) { distinctBuffers++; lastBuffer = buffer; }
            if (failRead && readBytes > 0) throw new IOException("Synthetic read failure");
            if (readBytes == bytes.length) return -1;
            int count = Math.min(Math.min(length, 997), bytes.length - readBytes);
            System.arraycopy(bytes, readBytes, buffer, offset, count);
            readBytes += count;
            return count;
        }

        @Override public void close() throws IOException {
            closes++;
            if (failClose) throw new IOException("Synthetic close failure");
        }
    }
}
