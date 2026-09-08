package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayCapability;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayCapabilityInspector;

/** Strict local reader for Format 15 and capability-only readers for formats 1 through 14. */
public final class Format15ArchiveReader {
    private Format15ArchiveReader() {
    }

    /** Reads one local archive without contacting any source or materializing unbounded data. */
    public static Format15Archive read(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("Diagnostic archive path is required");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return read(input);
        }
    }

    /** Reads a ZIP stream under the same entry, path, and byte limits as the path API. */
    public static Format15Archive read(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Diagnostic archive input is required");
        }
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (artifacts.size() >= Format15Safety.MAX_ARTIFACTS + 1) {
                    throw new Format15ArchiveException("Diagnostic archive has too many entries");
                }
                String name;
                try {
                    name = Format15Safety.name(entry.getName(), true);
                } catch (IllegalArgumentException error) {
                    throw new Format15ArchiveException("Diagnostic archive contains an unsafe member", error);
                }
                if (entry.isDirectory() || !names.add(name)) {
                    throw new Format15ArchiveException("Diagnostic archive contains a directory or duplicate member");
                }
                byte[] bytes = readBounded(zip, Format15Safety.MAX_ARTIFACT_BYTES);
                total += bytes.length;
                if (total > Format15Safety.MAX_TOTAL_BYTES) {
                    throw new Format15ArchiveException("Diagnostic archive exceeds the total size limit");
                }
                if (isTextMember(name)) {
                    try {
                        Format15Safety.requireSafeText(new String(bytes, StandardCharsets.UTF_8));
                    } catch (IllegalArgumentException error) {
                        throw new Format15ArchiveException("Diagnostic archive contains credential-bearing text", error);
                    }
                }
                if (name.equals("replay-manifest.json") || name.equals("manifest.json")) {
                    artifacts.put(name, Format15Artifact.archiveMember(name, bytes));
                } else {
                    try {
                        artifacts.put(name, Format15Artifact.archiveMember(name, bytes));
                    } catch (IllegalArgumentException error) {
                        throw new Format15ArchiveException("Diagnostic archive contains invalid content", error);
                    }
                }
                zip.closeEntry();
            }
        } catch (Format15ArchiveException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new Format15ArchiveException("Diagnostic archive could not be read", exception);
        }
        return validateAndBuild(artifacts);
    }

    private static Format15Archive validateAndBuild(Map<String, Format15Artifact> members)
        throws Format15ArchiveException {
        Format15Artifact format15Manifest = members.get("replay-manifest.json");
        Format15Artifact legacyManifest = members.get("manifest.json");
        Map<String, Object> manifest = null;
        int version = 1;
        if (format15Manifest != null) {
            manifest = parseObject(format15Manifest, "replay-manifest.json");
            version = integer(manifest, "formatVersion");
            if (version != 15) {
                throw new Format15ArchiveException("Format-15 manifest declares an incompatible version");
            }
            verifyFormat15Members(members, manifest);
        } else if (legacyManifest != null) {
            manifest = parseObject(legacyManifest, "manifest.json");
            version = integer(manifest, "formatVersion");
            if (version < 1 || version > 14) {
                throw new Format15ArchiveException("Legacy diagnostic manifest declares an invalid version");
            }
        }
        Set<String> capabilityNames = new HashSet<>(members.keySet());
        ReplayCapability capability;
        try {
            capability = ReplayCapabilityInspector.inspect(version, capabilityNames);
        } catch (IllegalArgumentException error) {
            throw new Format15ArchiveException("Diagnostic replay capability inventory is invalid", error);
        }
        String build = stringOrEmpty(manifest, "buildIdentity");
        String source = stringOrEmpty(manifest, "sourceIdentityHash");
        String parameter = stringOrEmpty(manifest, "parameterHash");
        Map<String, Format15Artifact> withoutManifest = new LinkedHashMap<>(members);
        withoutManifest.remove("replay-manifest.json");
        withoutManifest.remove("manifest.json");
        return new Format15Archive(version, build, source, parameter, withoutManifest, capability);
    }

    private static void verifyFormat15Members(Map<String, Format15Artifact> members,
        Map<String, Object> manifest) throws Format15ArchiveException {
        Object value = manifest.get("artifacts");
        if (!(value instanceof List<?> list) || list.size() != members.size() - 1) {
            throw new Format15ArchiveException("Format-15 manifest artifact inventory is incomplete");
        }
        Set<String> listed = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new Format15ArchiveException("Format-15 manifest artifact row is invalid");
            }
            String name = requiredString(map, "name");
            int size = requiredInteger(map, "size");
            String sha = requiredString(map, "sha256");
            Format15Artifact artifact = members.get(name);
            if (artifact == null || !listed.add(name) || artifact.bytes().length != size
                || !artifact.sha256().equals(sha)) {
                throw new Format15ArchiveException("Format-15 artifact checksum or size mismatch");
            }
        }
        if (!listed.equals(members.keySet().stream()
            .filter(name -> !name.equals("replay-manifest.json")).collect(java.util.stream.Collectors.toSet()))) {
            throw new Format15ArchiveException("Format-15 manifest does not cover every artifact");
        }
        Format15Safety.requiredHash(requiredString(manifest, "sourceIdentityHash"), "sourceIdentityHash");
        Format15Safety.requiredHash(requiredString(manifest, "parameterHash"), "parameterHash");
    }

    private static boolean isTextMember(String name) {
        return name.endsWith(".json") || name.endsWith(".csv") || name.endsWith(".txt")
            || name.endsWith(".osm") || name.endsWith(".log");
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() > maximum - count) {
                throw new Format15ArchiveException("Diagnostic archive member exceeds the per-file limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Map<String, Object> parseObject(Format15Artifact artifact, String name)
        throws Format15ArchiveException {
        try {
            Object value = new JsonParser(new ByteArrayInputStream(artifact.bytes())).parse();
            if (!(value instanceof Map<?, ?> map)) {
                throw new Format15ArchiveException(name + " is not a JSON object");
            }
            @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) map;
            return result;
        } catch (Format15ArchiveException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new Format15ArchiveException(name + " is malformed", exception);
        }
    }

    private static int integer(Map<String, Object> map, String key) throws Format15ArchiveException {
        Object value = map.get(key);
        if (!(value instanceof Number number) || number.intValue() != number.doubleValue()) {
            throw new Format15ArchiveException("Diagnostic manifest field is missing: " + key);
        }
        return number.intValue();
    }

    private static String stringOrEmpty(Map<String, Object> map, String key) throws Format15ArchiveException {
        Object value = map.get(key);
        if (value == null) {
            return "";
        }
        return requiredString(map, key);
    }

    private static String requiredString(Map<?, ?> map, String key) throws Format15ArchiveException {
        Object value = map.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new Format15ArchiveException("Diagnostic manifest field is missing: " + key);
        }
        return string;
    }

    private static int requiredInteger(Map<?, ?> map, String key) throws Format15ArchiveException {
        Object value = map.get(key);
        if (!(value instanceof Number number) || number.intValue() != number.doubleValue() || number.intValue() < 0) {
            throw new Format15ArchiveException("Diagnostic manifest integer is invalid: " + key);
        }
        return number.intValue();
    }

    /** Small strict JSON parser for the writer-owned manifest; it rejects duplicate keys. */
    private static final class JsonParser {
        private final String text;
        private int position;

        JsonParser(InputStream input) throws IOException {
            this.text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        Object parse() {
            Object value = value();
            whitespace();
            if (position != text.length()) {
                throw new IllegalArgumentException("Trailing JSON data");
            }
            return value;
        }

        private Object value() {
            whitespace();
            if (position >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return switch (text.charAt(position)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) {
                return result;
            }
            while (true) {
                whitespace();
                String key = string();
                if (result.containsKey(key)) {
                    throw new IllegalArgumentException("Duplicate JSON key");
                }
                whitespace();
                expect(':');
                Object value = value();
                result.put(key, value);
                whitespace();
                if (take('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) {
                return result;
            }
            while (true) {
                result.add(value());
                whitespace();
                if (take(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char character = text.charAt(position++);
                if (character == '"') {
                    return result.toString();
                }
                if (character == '\\') {
                    if (position >= text.length()) {
                        throw new IllegalArgumentException("Unfinished JSON escape");
                    }
                    char escaped = text.charAt(position++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append((char) Integer.parseInt(takeFour(), 16));
                        default -> throw new IllegalArgumentException("Invalid JSON escape");
                    }
                } else if (character < 0x20) {
                    throw new IllegalArgumentException("Control character in JSON string");
                } else {
                    result.append(character);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private String takeFour() {
            if (position + 4 > text.length()) {
                throw new IllegalArgumentException("Incomplete JSON unicode escape");
            }
            return text.substring(position, position += 4);
        }

        private Object number() {
            int start = position;
            while (position < text.length() && "-+0123456789.eE".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            String value = text.substring(start, position);
            if (!value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                throw new IllegalArgumentException("Invalid JSON number");
            }
            return value.contains(".") || value.contains("e") || value.contains("E")
                ? Double.valueOf(value) : Long.valueOf(value);
        }

        private Object literal(String literal, Object value) {
            if (!text.startsWith(literal, position)) {
                throw new IllegalArgumentException("Invalid JSON literal");
            }
            position += literal.length();
            return value;
        }

        private void whitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private boolean take(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) {
                throw new IllegalArgumentException("Expected JSON character: " + expected);
            }
        }
    }
}
