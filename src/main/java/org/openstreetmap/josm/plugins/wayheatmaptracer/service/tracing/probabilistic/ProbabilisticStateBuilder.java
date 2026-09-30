package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Constructs a stable bounded full-profile lattice without consulting Corridor A tracks. */
public final class ProbabilisticStateBuilder {
    /**
     * Builds the complete admitted lateral lattice.
     *
     * @param profile immutable full scalar profile
     * @param maximumStates hard profile state budget
     * @return complete lattice or an explicit mandatory-state overflow
     */
    public StateSpaceBuildResult build(ProbabilisticProfile profile, int maximumStates) {
        return build(profile, maximumStates, ObservationFamily.ELEMENTARY);
    }

    /** Builds a lattice whose retained arrays and cells are charged to the attempt owner. */
    StateSpaceBuildResult build(ProbabilisticProfile profile, int maximumStates,
            AttemptMemoryLedger.Owner owner) {
        return build(profile, maximumStates, ObservationFamily.ELEMENTARY, owner);
    }

    /**
     * Builds one mutually exclusive evidence-family lattice.
     *
     * @param profile immutable full scalar profile
     * @param maximumStates hard profile state budget
     * @param family elementary or grouped-parent interpretation
     * @return complete lattice or an explicit mandatory-state overflow
     */
    public StateSpaceBuildResult build(ProbabilisticProfile profile, int maximumStates,
        ObservationFamily family) {
        return build(profile, maximumStates, family, null);
    }

    private StateSpaceBuildResult build(ProbabilisticProfile profile, int maximumStates,
        ObservationFamily family, AttemptMemoryLedger.Owner owner) {
        if (profile == null || family == null || maximumStates <= 0) {
            throw new IllegalArgumentException("State construction requires a profile and positive budget");
        }
        AttemptMemoryLedger.Owner temporaryOwner = owner == null ? null
                : owner.child("state-build-temporaries");
        try {
        int stateLimit = Math.min(maximumStates, 96);
        if (profile.exactAnchorOffsetMeters().isPresent()) {
            double anchor = profile.exactAnchorOffsetMeters().getAsDouble();
            List<ObservationComponent> components = components(profile, family, owner);
            LateralStateCell anchorCell = owner == null
                    ? new LateralStateCell(anchor, 1.0, true, true,
                        nearestBranch(profile, anchor, family))
                    : ProbabilisticInference.allocated(owner,
                        AttemptMemoryLedger.objectBytes(32), "state lattice",
                        () -> new LateralStateCell(anchor, 1.0, true, true,
                            nearestBranch(profile, anchor, family)));
            List<LateralStateCell> cells = owner == null ? List.of(anchorCell)
                    : ProbabilisticInference.allocated(owner,
                        ProbabilisticInference.listBytes(1), "state lattice",
                        () -> List.of(anchorCell));
            ProbabilisticStateLattice lattice = lattice(cells, components,
                    profile.sourcePitchMeters() * 0.5, false, owner);
            return new StateSpaceBuildResult(StateSpaceBuildResult.Status.COMPLETE, Optional.of(lattice),
                List.of(anchor), "exact-anchor");
        }

        double tolerance = 1e-6 * Math.max(profile.sourcePitchMeters(), 1.0);
        List<RequiredOffset> required = requiredOffsets(profile, tolerance, family,
                temporaryOwner);
        // The empty immutable list is a borrowed JVM singleton, not an attempt allocation.
        List<Double> retained = owner == null || required.isEmpty()
                ? required.stream().map(RequiredOffset::offset).toList()
                : ProbabilisticInference.allocated(owner,
                    ProbabilisticInference.listBytes(required.size())
                        + Math.multiplyExact(required.size(), AttemptMemoryLedger.objectBytes(8)),
                    "state lattice", () -> required.stream()
                        .map(RequiredOffset::offset).toList());
        if (required.size() > stateLimit) {
            return new StateSpaceBuildResult(StateSpaceBuildResult.Status.STATE_LIMIT, Optional.empty(),
                retained, "mandatory-state-count=" + required.size() + ", limit=" + stateLimit);
        }

        double desiredPitch = 0.5 * profile.sourcePitchMeters();
        int desiredGridCount = 1 + (int) Math.ceil(
            (profile.maximumOffsetMeters() - profile.minimumOffsetMeters()) / desiredPitch);
        boolean limited = desiredGridCount + required.size() > stateLimit;
        int gridCount = limited ? Math.max(2, stateLimit - required.size()) : desiredGridCount;
        List<Double> grid = regularGrid(profile.minimumOffsetMeters(),
                profile.maximumOffsetMeters(), gridCount, temporaryOwner);
        List<RequiredOffset> merged = temporaryOwner == null ? new ArrayList<>(required)
                : ProbabilisticInference.allocated(temporaryOwner,
                    ProbabilisticInference.listBytes(stateLimit)
                        + Math.multiplyExact(gridCount, AttemptMemoryLedger.objectBytes(16)),
                    "state lattice", () -> {
                        List<RequiredOffset> values = new ArrayList<>(stateLimit);
                        values.addAll(required);
                        return values;
                    });
        for (double offset : grid) {
            insertUnlessDuplicate(merged, new RequiredOffset(offset, false, false), tolerance);
        }
        while (merged.size() > stateLimit) {
            int removable = furthestRemovableInterior(merged, profile.minimumOffsetMeters(),
                profile.maximumOffsetMeters(), tolerance);
            if (removable < 0) {
                return new StateSpaceBuildResult(StateSpaceBuildResult.Status.STATE_LIMIT, Optional.empty(),
                    retained, "full-window state budget cannot retain mandatory positions");
            }
            merged.remove(removable);
            limited = true;
        }
        merged.sort(Comparator.comparingDouble(RequiredOffset::offset));
        List<LateralStateCell> cells = createCells(profile, merged, family, owner);
        double actualPitch = maximumAdjacentPitch(cells);
        ProbabilisticStateLattice lattice = lattice(cells, components(profile, family, owner),
            actualPitch, limited, owner);
        return new StateSpaceBuildResult(StateSpaceBuildResult.Status.COMPLETE, Optional.of(lattice),
            retained, limited ? "resolution-limited" : "native-half-pixel-grid");
        } finally {
            if (temporaryOwner != null) temporaryOwner.close();
        }
    }

    private static List<RequiredOffset> requiredOffsets(ProbabilisticProfile profile, double tolerance,
        ObservationFamily family, AttemptMemoryLedger.Owner owner) {
        int maximumOffsets = 0;
        for (ProbabilisticProfile.Mode mode : profile.modes()) {
            if (mode.groupedParent() == (family == ObservationFamily.GROUPED_PARENT)) {
                maximumOffsets = Math.addExact(maximumOffsets, 1
                        + mode.peakOffsetsMeters().size()
                        + mode.nestedCenterOffsetsMeters().size());
            }
        }
        int capacity = maximumOffsets;
        List<RequiredOffset> result = owner == null ? new ArrayList<>()
                : ProbabilisticInference.allocated(owner,
                    ProbabilisticInference.listBytes(capacity)
                        + Math.multiplyExact(capacity, AttemptMemoryLedger.objectBytes(16)),
                    "state lattice", () -> new ArrayList<>(capacity));
        for (ProbabilisticProfile.Mode mode : profile.modes()) {
            if (mode.groupedParent() != (family == ObservationFamily.GROUPED_PARENT)) {
                continue;
            }
            insertUnlessDuplicate(result, new RequiredOffset(mode.coreCenterMeters(), true, false), tolerance);
            mode.peakOffsetsMeters().forEach(offset -> insertUnlessDuplicate(result,
                new RequiredOffset(offset, true, false), tolerance));
            mode.nestedCenterOffsetsMeters().forEach(offset -> insertUnlessDuplicate(result,
                new RequiredOffset(offset, true, false), tolerance));
        }
        result.sort(Comparator.comparingDouble(RequiredOffset::offset));
        return result;
    }

    private static void insertUnlessDuplicate(List<RequiredOffset> values, RequiredOffset candidate,
        double tolerance) {
        for (int index = 0; index < values.size(); index++) {
            RequiredOffset existing = values.get(index);
            if (Math.abs(existing.offset() - candidate.offset()) <= tolerance) {
                if (candidate.exact() || candidate.mandatory() && !existing.mandatory()) {
                    values.set(index, candidate);
                }
                return;
            }
        }
        values.add(candidate);
    }

    private static List<Double> regularGrid(double minimum, double maximum, int count,
            AttemptMemoryLedger.Owner owner) {
        if (count <= 1) {
            return owner == null ? List.of(minimum)
                    : ProbabilisticInference.allocated(owner,
                        ProbabilisticInference.listBytes(1)
                            + AttemptMemoryLedger.objectBytes(8),
                        "state lattice", () -> List.of(minimum));
        }
        List<Double> result = owner == null ? new ArrayList<>(count)
                : ProbabilisticInference.allocated(owner,
                    ProbabilisticInference.listBytes(count)
                        + Math.multiplyExact(count, AttemptMemoryLedger.objectBytes(8)),
                    "state lattice", () -> new ArrayList<>(count));
        double pitch = (maximum - minimum) / (count - 1);
        for (int index = 0; index < count; index++) {
            result.add(index == count - 1 ? maximum : minimum + index * pitch);
        }
        return result;
    }

    private static int furthestRemovableInterior(List<RequiredOffset> values, double minimum,
        double maximum, double tolerance) {
        values.sort(Comparator.comparingDouble(RequiredOffset::offset));
        int selected = -1;
        double smallestNeighborGap = Double.POSITIVE_INFINITY;
        for (int index = 0; index < values.size(); index++) {
            RequiredOffset value = values.get(index);
            if (value.mandatory() || value.exact() || Math.abs(value.offset() - minimum) <= tolerance
                || Math.abs(value.offset() - maximum) <= tolerance) {
                continue;
            }
            double left = index == 0 ? Double.POSITIVE_INFINITY
                : value.offset() - values.get(index - 1).offset();
            double right = index == values.size() - 1 ? Double.POSITIVE_INFINITY
                : values.get(index + 1).offset() - value.offset();
            double gap = Math.min(left, right);
            if (gap < smallestNeighborGap) {
                smallestNeighborGap = gap;
                selected = index;
            }
        }
        return selected;
    }

    private static List<LateralStateCell> createCells(ProbabilisticProfile profile,
        List<RequiredOffset> offsets, ObservationFamily family,
        AttemptMemoryLedger.Owner owner) {
        AttemptMemoryLedger.Owner builder = owner == null ? null
                : owner.child("state-cell-builder");
        List<LateralStateCell> cells = owner == null ? new ArrayList<>(offsets.size())
                : ProbabilisticInference.allocated(builder,
                    ProbabilisticInference.listBytes(offsets.size()), "state lattice",
                    () -> new ArrayList<>(offsets.size()));
        for (int index = 0; index < offsets.size(); index++) {
            RequiredOffset current = offsets.get(index);
            double left = index == 0 ? profile.minimumOffsetMeters()
                : 0.5 * (offsets.get(index - 1).offset() + current.offset());
            double right = index == offsets.size() - 1 ? profile.maximumOffsetMeters()
                : 0.5 * (current.offset() + offsets.get(index + 1).offset());
            double width = Math.max(1e-12, right - left);
            LateralStateCell cell = owner == null
                    ? new LateralStateCell(current.offset(), width, current.exact(),
                        current.mandatory(), nearestBranch(profile, current.offset(), family))
                    : ProbabilisticInference.allocated(owner,
                        AttemptMemoryLedger.objectBytes(32), "state lattice",
                        () -> new LateralStateCell(current.offset(), width, current.exact(),
                            current.mandatory(), nearestBranch(profile, current.offset(), family)));
            cells.add(cell);
        }
        if (owner == null) return List.copyOf(cells);
        try {
            return ProbabilisticInference.allocated(owner,
                    ProbabilisticInference.listBytes(cells.size()), "state lattice",
                    () -> List.copyOf(cells));
        } finally {
            builder.close();
        }
    }

    private static String nearestBranch(ProbabilisticProfile profile, double offset,
        ObservationFamily family) {
        return profile.modes().stream()
            .filter(mode -> mode.groupedParent() == (family == ObservationFamily.GROUPED_PARENT))
            .min(Comparator
            .comparingDouble((ProbabilisticProfile.Mode mode) -> Math.abs(mode.coreCenterMeters() - offset))
            .thenComparing(ProbabilisticProfile.Mode::id)).map(ProbabilisticProfile.Mode::id)
            .orElse("unlocalized");
    }

    private static double maximumAdjacentPitch(List<LateralStateCell> cells) {
        if (cells.size() == 1) {
            return cells.get(0).quadratureWidthMeters();
        }
        double maximum = 0.0;
        for (int index = 1; index < cells.size(); index++) {
            maximum = Math.max(maximum, cells.get(index).offsetMeters() - cells.get(index - 1).offsetMeters());
        }
        return maximum;
    }

    private static List<ObservationComponent> components(ProbabilisticProfile profile,
        ObservationFamily family, AttemptMemoryLedger.Owner owner) {
        AttemptMemoryLedger.Owner workspace = owner == null ? null
                : owner.child("component-maps");
        Map<String, ProbabilisticProfile.Mode> modes = owner == null ? new LinkedHashMap<>()
                : ProbabilisticInference.allocated(workspace,
                    mapBytes(profile.modes().size()), "state lattice",
                    () -> new LinkedHashMap<>(Math.max(1, profile.modes().size() * 2)));
        profile.modes().stream()
            .filter(mode -> mode.groupedParent() == (family == ObservationFamily.GROUPED_PARENT))
            .sorted(Comparator.comparing(ProbabilisticProfile.Mode::id))
            .forEach(mode -> modes.putIfAbsent(mode.evidenceLineage(), mode));
        Map<String, ProbabilisticProfile.CensoredMode> censored = owner == null
                ? new LinkedHashMap<>()
                : ProbabilisticInference.allocated(workspace,
                    mapBytes(profile.censoredModes().size()), "state lattice",
                    () -> new LinkedHashMap<>(Math.max(1,
                        profile.censoredModes().size() * 2)));
        profile.censoredModes().stream().sorted(Comparator.comparing(ProbabilisticProfile.CensoredMode::id))
            .forEach(mode -> censored.putIfAbsent(mode.evidenceLineage(), mode));
        double maximumStrength = 0.0;
        double nonmissingTotal = 0.0;
        for (ProbabilisticProfile.Mode mode : modes.values()) {
            maximumStrength = Math.max(maximumStrength, mode.strength());
            nonmissingTotal += mode.strength();
        }
        for (ProbabilisticProfile.CensoredMode mode : censored.values()) {
            maximumStrength = Math.max(maximumStrength, mode.strength());
            nonmissingTotal += mode.strength();
        }
        double missingWeight = maximumStrength == 0.0 ? 1.0 : clamp(1.0 - maximumStrength, 0.02, 0.98);
        double available = 1.0 - missingWeight;
        AttemptMemoryLedger.Owner builder = owner == null ? null
                : owner.child("component-builder");
        int componentCapacity = modes.size() + censored.size() + 1;
        List<ObservationComponent> result = owner == null ? new ArrayList<>()
                : ProbabilisticInference.allocated(builder,
                    ProbabilisticInference.listBytes(componentCapacity), "state lattice",
                    () -> new ArrayList<>(componentCapacity));
        if (nonmissingTotal > 0.0) {
            for (ProbabilisticProfile.Mode mode : modes.values()) {
                double prior = available * mode.strength() / nonmissingTotal;
                result.add(component(owner, mode.id(), mode.evidenceLineage(),
                    ObservationComponent.Kind.MEASURED, prior, mode.groupedParent()));
            }
            for (ProbabilisticProfile.CensoredMode mode : censored.values()) {
                double prior = available * mode.strength() / nonmissingTotal;
                result.add(component(owner, mode.id(), mode.evidenceLineage(),
                    ObservationComponent.Kind.CENSORED, prior, false));
            }
        }
        result.add(component(owner, "missing", "missing:" + profile.profileIndex(),
            ObservationComponent.Kind.MISSING, missingWeight, false));
        if (owner == null) return List.copyOf(result);
        try {
            List<ObservationComponent> retained = ProbabilisticInference.allocated(owner,
                    ProbabilisticInference.listBytes(result.size()), "state lattice",
                    () -> List.copyOf(result));
            workspace.close();
            return retained;
        } finally {
            builder.close();
        }
    }

    private static long mapBytes(long entries) {
        return AttemptMemoryLedger.objectBytes(48)
                + AttemptMemoryLedger.referenceArrayBytes(hashTableCapacity(entries))
                + Math.multiplyExact(entries, AttemptMemoryLedger.objectBytes(48));
    }

    private static long hashTableCapacity(long entries) {
        long required = Math.max(16L, Math.multiplyExact(entries, 2L));
        long capacity = 16L;
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2L);
        return capacity;
    }

    private static ObservationComponent component(AttemptMemoryLedger.Owner owner,
            String id, String lineage, ObservationComponent.Kind kind, double prior,
            boolean groupedParent) {
        if (owner == null) return new ObservationComponent(id, lineage, kind, prior, groupedParent);
        return ProbabilisticInference.allocated(owner, AttemptMemoryLedger.objectBytes(32),
                "state lattice",
                () -> new ObservationComponent(id, lineage, kind, prior, groupedParent));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static ProbabilisticStateLattice lattice(List<LateralStateCell> cells,
            List<ObservationComponent> components, double actualPitch, boolean limited,
            AttemptMemoryLedger.Owner owner) {
        if (owner == null) {
            return new ProbabilisticStateLattice(cells, components, actualPitch, limited);
        }
        return ProbabilisticInference.allocated(owner, AttemptMemoryLedger.objectBytes(32),
                "state lattice",
                () -> new ProbabilisticStateLattice(cells, components, actualPitch, limited));
    }

    private record RequiredOffset(double offset, boolean mandatory, boolean exact) { }
}
