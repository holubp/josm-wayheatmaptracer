package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Stable, compact presentation of repeated quality findings or plan reason labels. */
public final class FindingSummary {
    private FindingSummary() {
        // Utility class.
    }

    /**
     * Groups identical labels in first-occurrence order without changing the source diagnostics.
     *
     * @param labels user-visible finding labels
     * @return comma-separated labels with occurrence counts, or {@code none} when empty
     */
    public static String summarize(List<String> labels) {
        Objects.requireNonNull(labels, "labels");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String label : labels) {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Finding labels must be present");
            }
            counts.merge(label, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return "none";
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " × " + entry.getValue())
                .collect(Collectors.joining(", "));
    }
}
