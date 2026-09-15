package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Derives replay claims solely from validated archive member names and format version. */
public final class ReplayCapabilityInspector {
    private static final Set<String> FORMAT_15_SCALAR = Set.of(
        "frozen-input.bin", "frozen-input-identities.json", "trace-request.json", "evidence-frame.json");

    private ReplayCapabilityInspector() {
    }

    /**
     * Inspects a prevalidated member inventory without opening files or acquiring missing data.
     *
     * @param formatVersion declared debug format
     * @param members normalized archive member names
     * @return supported levels and exact missing prerequisites
     */
    public static ReplayCapability inspect(int formatVersion, Set<String> members) {
        if (formatVersion < 1 || members == null || members.stream().anyMatch(name -> name == null || name.isBlank()
                || name.startsWith("/") || name.contains("../"))) {
            throw new IllegalArgumentException("Replay member inventory is invalid");
        }
        EnumSet<ReplayLevel> levels = EnumSet.noneOf(ReplayLevel.class);
        List<String> missing = new ArrayList<>();
        if (formatVersion == 15) {
            addLevel(FORMAT_15_SCALAR, members, ReplayLevel.SCALAR_INFERENCE, levels, missing);
            if (levels.contains(ReplayLevel.SCALAR_INFERENCE)) {
                levels.add(ReplayLevel.FINAL_GEOMETRY);
            } else {
                missing.add("FINAL_GEOMETRY:frozen-production-input");
            }
            missing.add("RASTER_INFERENCE:actual-frozen-raster-preprocessing-not-implemented");
            missing.add("FULL_EDIT_PLAN:actual-plan-and-command-not-implemented");
        } else {
            missing.add("SCALAR_INFERENCE:format-15-frozen-production-input");
            missing.add("FINAL_GEOMETRY:format-15-frozen-production-input");
            missing.add("RASTER_INFERENCE:actual-frozen-raster-preprocessing-not-implemented");
            missing.add("FULL_EDIT_PLAN:actual-plan-and-command-not-implemented");
        }
        return new ReplayCapability(formatVersion, levels, missing);
    }

    private static void addLevel(Set<String> required, Set<String> members, ReplayLevel level,
        EnumSet<ReplayLevel> levels, List<String> missing) {
        if (members.containsAll(required)) {
            levels.add(level);
            return;
        }
        List<String> absent = required.stream().filter(name -> !members.contains(name)).sorted().toList();
        missing.add(level.name() + ':' + String.join(",", absent));
    }

}
