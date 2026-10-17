package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.Map;

/** Bounded test-tool bridge to the existing strict duplicate-key-rejecting JSON parser. */
public final class ObservationManifestJson {
    private ObservationManifestJson() { }

    public static Map<String, Object> parse(byte[] bytes) throws Format15ArchiveException {
        if (bytes.length > 65_536) throw new IllegalArgumentException("Manifest byte limit");
        int depth = 0;
        boolean string = false;
        boolean escaped = false;
        for (byte value : bytes) {
            if (string) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') string = false;
            } else if (value == '"') string = true;
            else if (value == '{' || value == '[') {
                if (++depth > 16) throw new IllegalArgumentException("Manifest depth limit");
            } else if (value == '}' || value == ']') depth--;
        }
        return Format15ArchiveReader.parseObject(bytes, "observation manifest");
    }
}
