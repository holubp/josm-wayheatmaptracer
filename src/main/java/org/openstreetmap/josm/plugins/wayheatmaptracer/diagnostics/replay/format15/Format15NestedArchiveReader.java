package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** Validates and locates one exact Format-15 bundle inside a bounded nested ZIP tree. */
final class Format15NestedArchiveReader {
    private static final long MAX_OUTER_BYTES = 96L * 1024L * 1024L;
    private static final int MAX_MEMBER_BYTES = 32 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 384L * 1024L * 1024L;
    private static final long MAX_LIVE_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ENTRIES_PER_ZIP = 20_000;
    private static final int MAX_TOTAL_ENTRIES = 100_000;
    private static final double MAX_RATIO = 200.0;

    private Format15NestedArchiveReader() {
    }

    static Format15Archive read(Format15CorpusManifest.Case expected) throws IOException {
        Path path = expected.sourcePath();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("corpus-input-not-regular");
        }
        long size = Files.size(path);
        if (size <= 0 || size > MAX_OUTER_BYTES) {
            throw new IllegalArgumentException("outer-byte-budget");
        }
        byte[] outer = Files.readAllBytes(path);
        if (outer.length != size) {
            throw new IllegalArgumentException("outer-changed-during-read");
        }
        if (!sha256(outer).equals(expected.outerSha256())) {
            throw new IllegalArgumentException("outer-sha256-mismatch");
        }
        Budget budget = new Budget(outer.length);
        Match match = new Match(expected);
        scan(outer, path.getFileName().toString(), 0, budget, match);
        if (match.archive == null) {
            throw new IllegalArgumentException("nested-bundle-missing");
        }
        if (match.matches != 1) {
            throw new IllegalArgumentException("nested-bundle-ambiguous");
        }
        return match.archive;
    }

    private static void scan(byte[] archiveBytes, String logicalName, int depth,
            Budget budget, Match match) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("nested-depth-budget");
        }
        matchCurrent(archiveBytes, logicalName, budget, match);
        Map<String, String> names = new HashMap<>();
        int localEntries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                localEntries++;
                budget.entries++;
                if (localEntries > MAX_ENTRIES_PER_ZIP
                        || budget.entries > MAX_TOTAL_ENTRIES) {
                    throw new IllegalArgumentException("archive-entry-budget");
                }
                String name = safeName(entry.getName());
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                boolean nested = name.toLowerCase(Locale.ROOT).endsWith(".zip");
                boolean text = isText(name);
                byte[] materialized = nested || text
                        ? readBounded(zip, text ? MAX_TEXT_BYTES : MAX_MEMBER_BYTES,
                                budget, true)
                        : readBounded(zip, MAX_MEMBER_BYTES, budget, false);
                String digest = materialized == null
                        ? budget.lastDigest : sha256(materialized);
                String prior = names.putIfAbsent(name, digest);
                if (prior != null && !prior.equals(digest)) {
                    throw new IllegalArgumentException("conflicting-duplicate-member");
                }
                long compressed = entry.getCompressedSize();
                long uncompressed = entry.getSize() >= 0
                        ? entry.getSize() : budget.lastMemberBytes;
                if (compressed > 0 && uncompressed / (double) compressed > MAX_RATIO) {
                    throw new IllegalArgumentException("archive-compression-ratio");
                }
                if (prior == null && text) {
                    validateText(materialized);
                }
                if (prior == null && nested) {
                    budget.retain(materialized.length);
                    try {
                        scan(materialized, logicalName + "!" + name,
                                depth + 1, budget, match);
                    } finally {
                        budget.release(materialized.length);
                    }
                }
                zip.closeEntry();
            }
        } catch (ZipException exception) {
            throw new IllegalArgumentException("malformed-nested-zip", exception);
        }
    }

    private static void matchCurrent(byte[] bytes, String logicalName, Budget budget,
            Match match) throws IOException {
        if (bytes.length != match.expected.byteSize()
                || !sha256(bytes).equals(match.expected.bundleSha256())) {
            return;
        }
        String expectedName = match.expected.bundleName();
        boolean redacted = expectedName.startsWith("<redacted-");
        if (!redacted && !expectedName.equals(logicalName)) {
            return;
        }
        match.matches++;
        if (match.matches > 1) {
            return;
        }
        if (budget.liveBytes > MAX_LIVE_BYTES - 2L * bytes.length) {
            throw new IllegalArgumentException("archive-materialization-budget");
        }
        match.archive = Format15ArchiveReader.read(new ByteArrayInputStream(bytes));
        long retained = match.archive.artifacts().values().stream()
                .mapToLong(artifact -> artifact.bytes().length).sum();
        budget.retain(retained);
    }

    private static byte[] readBounded(ZipInputStream input, int maximum,
            Budget budget, boolean retain) throws IOException {
        MessageDigest digest = digest();
        ByteArrayOutputStream output = retain ? new ByteArrayOutputStream() : null;
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            count += read;
            budget.expandedBytes += read;
            if (count > maximum || budget.expandedBytes > MAX_EXPANDED_BYTES
                    || retain && (count > MAX_LIVE_BYTES / 2
                            || budget.liveBytes > MAX_LIVE_BYTES - 2 * count)) {
                throw new IllegalArgumentException("archive-expansion-budget");
            }
            digest.update(buffer, 0, read);
            if (retain) {
                output.write(buffer, 0, read);
            }
        }
        budget.lastMemberBytes = count;
        budget.lastDigest = HexFormat.of().formatHex(digest.digest());
        return retain ? output.toByteArray() : null;
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > 1_024
                || value.indexOf('\0') >= 0 || value.startsWith("/")
                || value.startsWith("\\") || value.contains("\\")
                || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("unsafe-archive-member");
        }
        for (String part : value.split("/", -1)) {
            if (part.equals("..")) {
                throw new IllegalArgumentException("unsafe-archive-member");
            }
        }
        return value;
    }

    private static boolean isText(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !(lower.endsWith(".zip") || lower.endsWith(".png")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp")
                || lower.endsWith(".gz") || lower.endsWith(".bz2")
                || lower.endsWith(".xz") || lower.endsWith(".7z")
                || lower.endsWith(".jar") || lower.endsWith(".class")
                || lower.endsWith(".pbf") || lower.endsWith(".bin"));
    }

    private static void validateText(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            Format15Safety.requireSafeText(value);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("archive-text-not-utf8", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static final class Budget {
        private long entries;
        private long expandedBytes;
        private long liveBytes;
        private long lastMemberBytes;
        private String lastDigest;

        Budget(long initialLiveBytes) {
            liveBytes = initialLiveBytes;
        }

        void retain(long bytes) {
            if (bytes < 0 || liveBytes > MAX_LIVE_BYTES - bytes) {
                throw new IllegalArgumentException("archive-materialization-budget");
            }
            liveBytes += bytes;
        }

        void release(long bytes) {
            liveBytes -= bytes;
        }
    }

    private static final class Match {
        private final Format15CorpusManifest.Case expected;
        private int matches;
        private Format15Archive archive;

        Match(Format15CorpusManifest.Case expected) {
            this.expected = expected;
        }
    }
}
