package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.nio.charset.StandardCharsets;

/** Immutable, bounded, checksummed member of a Format-15 diagnostic archive. */
public final class Format15Artifact {
    private final String name;
    private final byte[] bytes;
    private final String sha256;

    private Format15Artifact(String name, byte[] bytes) {
        this(name, bytes, false);
    }

    private Format15Artifact(String name, byte[] bytes, boolean manifestAllowed) {
        this.name = Format15Safety.name(name, manifestAllowed);
        this.bytes = Format15Safety.copyBounded(bytes);
        if (isTextMember(this.name)) {
            Format15Safety.requireSafeText(new String(this.bytes, StandardCharsets.UTF_8));
        }
        this.sha256 = Format15Safety.sha256(this.bytes);
    }

    /** Creates an artifact from binary content and defensively copies its bytes. */
    public static Format15Artifact binary(String name, byte[] bytes) {
        return new Format15Artifact(name, bytes);
    }

    /** Creates a UTF-8 text artifact after rejecting credential-bearing content. */
    public static Format15Artifact text(String name, String text) {
        Format15Safety.requireSafeText(text);
        return new Format15Artifact(name, text.getBytes(StandardCharsets.UTF_8));
    }

    static Format15Artifact archiveMember(String name, byte[] bytes) {
        return new Format15Artifact(name, bytes, true);
    }

    private static boolean isTextMember(String name) {
        return name.endsWith(".json") || name.endsWith(".csv") || name.endsWith(".txt")
            || name.endsWith(".osm") || name.endsWith(".log");
    }


    /** Returns the normalized archive member name. */
    public String name() {
        return name;
    }

    /** Returns a defensive copy of the artifact bytes. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Returns the lowercase SHA-256 of the immutable content. */
    public String sha256() {
        return sha256;
    }
}
