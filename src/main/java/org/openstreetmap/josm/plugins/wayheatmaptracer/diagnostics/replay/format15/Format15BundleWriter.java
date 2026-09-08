package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes deterministic, atomically replaced Format-15 diagnostic archives. */
public final class Format15BundleWriter {
    private Format15BundleWriter() {
    }

    /** Writes a bundle with zero timestamps, sorted entries, and a writer-owned manifest. */
    public static void write(Format15Bundle bundle, Path target) throws IOException {
        if (bundle == null || target == null || target.getFileName() == null) {
            throw new IllegalArgumentException("Format-15 bundle and target are required");
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Format-15 target has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".format15-", ".tmp");
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                List<String> names = new ArrayList<>(bundle.artifactNames());
                names.add("replay-manifest.json");
                names.sort(String::compareTo);
                for (String name : names) {
                    byte[] bytes = name.equals("replay-manifest.json")
                        ? manifest(bundle).getBytes(StandardCharsets.UTF_8)
                        : bundle.artifact(name).bytes();
                    putStored(output, name, bytes);
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String manifest(Format15Bundle bundle) {
        StringBuilder json = new StringBuilder("{\"formatVersion\":15,\"buildIdentity\":");
        json.append(quote(bundle.buildIdentity())).append(",\"sourceIdentityHash\":")
            .append(quote(bundle.sourceIdentityHash())).append(",\"parameterHash\":")
            .append(quote(bundle.parameterHash())).append(",\"artifacts\":[");
        boolean first = true;
        for (String name : new java.util.TreeSet<>(bundle.artifactNames())) {
            Format15Artifact artifact = bundle.artifact(name);
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"name\":").append(quote(name)).append(",\"size\":")
                .append(artifact.bytes().length).append(",\"sha256\":")
                .append(quote(artifact.sha256())).append('}');
        }
        return json.append("]}\n").toString();
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                result.append('\\');
            }
            if (character < 0x20) {
                result.append(String.format("\\u%04x", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.append('"').toString();
    }

    private static void putStored(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }
}
