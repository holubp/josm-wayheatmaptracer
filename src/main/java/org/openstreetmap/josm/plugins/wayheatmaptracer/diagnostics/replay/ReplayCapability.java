package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay;

import java.util.List;
import java.util.Set;

/** Honest replay levels and missing prerequisites discovered without network access. */
public record ReplayCapability(int formatVersion, Set<ReplayLevel> supportedLevels,
    List<String> missingPrerequisites) {
    /** Copies capability state and rejects invalid format declarations. */
    public ReplayCapability {
        if (formatVersion < 1 || supportedLevels == null || missingPrerequisites == null
            || missingPrerequisites.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Replay capability declaration is inconsistent");
        }
        supportedLevels = Set.copyOf(supportedLevels);
        missingPrerequisites = List.copyOf(missingPrerequisites);
    }

    /** Returns whether this bundle can support the requested replay level. */
    public boolean supports(ReplayLevel level) {
        return supportedLevels.contains(level);
    }
}
