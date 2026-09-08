package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Derives replay claims solely from validated archive member names and format version. */
public final class ReplayCapabilityInspector {
    private static final Set<String> FORMAT_15_SCALAR = Set.of(
        "trace-request.json", "evidence-frame.json", "solver-summary.json", "posterior-profiles.csv");
    private static final Set<String> FORMAT_15_FINAL = Set.of(
        "path-alternatives.csv", "local-defects.csv", "refit-intervals.csv", "validation.json");
    private static final Set<String> FORMAT_15_EDIT = Set.of(
        "junction-proposals.json", "edit-plan.json", "attempt-lineage.json", "replay-manifest.json");

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
        if (formatVersion < 1 || members == null
            || members.stream().anyMatch(name -> name == null || name.isBlank()
                || name.startsWith("/") || name.contains("../"))) {
            throw new IllegalArgumentException("Replay member inventory is invalid");
        }
        EnumSet<ReplayLevel> levels = EnumSet.noneOf(ReplayLevel.class);
        List<String> missing = new ArrayList<>();
        if (formatVersion >= 15) {
            addLevel(FORMAT_15_SCALAR, members, ReplayLevel.SCALAR_INFERENCE, levels, missing);
            if (levels.contains(ReplayLevel.SCALAR_INFERENCE)
                && members.stream().anyMatch(ReplayCapabilityInspector::isFrozenRaster)) {
                levels.add(ReplayLevel.RASTER_INFERENCE);
            } else if (levels.contains(ReplayLevel.SCALAR_INFERENCE)) {
                missing.add("RASTER_INFERENCE:frozen-raster-artifact");
            }
            if (levels.contains(ReplayLevel.SCALAR_INFERENCE)) {
                addLevel(FORMAT_15_FINAL, members, ReplayLevel.FINAL_GEOMETRY, levels, missing);
            }
            if (levels.contains(ReplayLevel.FINAL_GEOMETRY)) {
                addLevel(FORMAT_15_EDIT, members, ReplayLevel.FULL_EDIT_PLAN, levels, missing);
            }
        } else {
            if (members.contains("diagnostics.json") && members.contains("profile-intensity.csv")) {
                levels.add(ReplayLevel.SCALAR_INFERENCE);
            } else {
                missing.add("SCALAR_INFERENCE:diagnostics.json,profile-intensity.csv");
            }
            if (levels.contains(ReplayLevel.SCALAR_INFERENCE)
                && members.stream().anyMatch(ReplayCapabilityInspector::isFrozenRaster)) {
                levels.add(ReplayLevel.RASTER_INFERENCE);
            } else {
                missing.add("RASTER_INFERENCE:frozen-raster-artifact");
            }
            if (members.contains("original-segment.osm") && members.contains("candidate-previews.osm")) {
                levels.add(ReplayLevel.FINAL_GEOMETRY);
            } else {
                missing.add("FINAL_GEOMETRY:original-segment.osm,candidate-previews.osm");
            }
            missing.add("FULL_EDIT_PLAN:format-15-edit-plan-and-complete-network-closure");
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

    private static boolean isFrozenRaster(String name) {
        return name.equals("rendered-layer-capture.png")
            || name.startsWith("tiles/") && name.endsWith(".png")
            || name.startsWith("evidence/") && name.endsWith(".bin");
    }
}
