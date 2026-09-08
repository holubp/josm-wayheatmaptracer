package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Immutable before/after geometry inventory for all ways changed by a replayed edit plan. */
public final class Format15EditPlan {
    private final Map<String, String> originalWays;
    private final Map<String, String> proposedWays;

    /** Requires exactly matching way identities and defensive copies of safe serialized states. */
    public Format15EditPlan(Map<String, String> originalWays, Map<String, String> proposedWays) {
        if (originalWays == null || proposedWays == null || originalWays.isEmpty()
            || !originalWays.keySet().equals(proposedWays.keySet())) {
            throw new IllegalArgumentException("Edit plan must cover the same non-empty ways before and after");
        }
        originalWays.forEach((key, value) -> {
            requireWayState(key, value);
            requireWayState(key, proposedWays.get(key));
        });
        if (originalWays.equals(proposedWays)) {
            throw new IllegalArgumentException("Edit plan contains no geometry change");
        }
        this.originalWays = Map.copyOf(originalWays);
        this.proposedWays = Map.copyOf(proposedWays);
    }

    /** Returns every way identity represented in both before and after states. */
    public Set<String> changedWayKeys() {
        return Set.copyOf(new TreeSet<>(originalWays.keySet()));
    }

    /** Returns the immutable original way-state map. */
    public Map<String, String> originalWays() {
        return originalWays;
    }

    /** Returns the immutable proposed way-state map. */
    public Map<String, String> proposedWays() {
        return proposedWays;
    }

    /** Serializes both complete way inventories into the dedicated edit-plan artifact. */
    public Format15Artifact asArtifact() {
        StringBuilder json = new StringBuilder("{\"originalWays\":{");
        appendMap(json, originalWays);
        json.append("},\"proposedWays\":{");
        appendMap(json, proposedWays);
        json.append("}}\n");
        return Format15Artifact.text("edit-plan.json", json.toString());
    }

    private static void requireWayState(String key, String value) {
        if (key == null || key.isBlank() || value == null) {
            throw new IllegalArgumentException("Edit plan way state is incomplete");
        }
        Format15Safety.requireSafeText(key);
        Format15Safety.requireSafeText(value);
    }

    private static void appendMap(StringBuilder json, Map<String, String> values) {
        boolean first = true;
        for (String key : new TreeSet<>(values.keySet())) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(key)).append(':').append(quote(values.get(key)));
        }
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
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
}
