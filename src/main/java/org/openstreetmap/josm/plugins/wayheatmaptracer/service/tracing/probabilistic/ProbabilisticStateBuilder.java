package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        if (profile == null || family == null || maximumStates <= 0) {
            throw new IllegalArgumentException("State construction requires a profile and positive budget");
        }
        int stateLimit = Math.min(maximumStates, 96);
        if (profile.exactAnchorOffsetMeters().isPresent()) {
            double anchor = profile.exactAnchorOffsetMeters().getAsDouble();
            List<ObservationComponent> components = components(profile, family);
            ProbabilisticStateLattice lattice = new ProbabilisticStateLattice(
                List.of(new LateralStateCell(anchor, 1.0, true, true, nearestBranch(profile, anchor, family))),
                components, profile.sourcePitchMeters() * 0.5, false);
            return new StateSpaceBuildResult(StateSpaceBuildResult.Status.COMPLETE, Optional.of(lattice),
                List.of(anchor), "exact-anchor");
        }

        double tolerance = 1e-6 * Math.max(profile.sourcePitchMeters(), 1.0);
        List<RequiredOffset> required = requiredOffsets(profile, tolerance, family);
        List<Double> retained = required.stream().map(RequiredOffset::offset).toList();
        if (required.size() > stateLimit) {
            return new StateSpaceBuildResult(StateSpaceBuildResult.Status.STATE_LIMIT, Optional.empty(),
                retained, "mandatory-state-count=" + required.size() + ", limit=" + stateLimit);
        }

        double desiredPitch = 0.5 * profile.sourcePitchMeters();
        int desiredGridCount = 1 + (int) Math.ceil(
            (profile.maximumOffsetMeters() - profile.minimumOffsetMeters()) / desiredPitch);
        boolean limited = desiredGridCount + required.size() > stateLimit;
        int gridCount = limited ? Math.max(2, stateLimit - required.size()) : desiredGridCount;
        List<Double> grid = regularGrid(profile.minimumOffsetMeters(), profile.maximumOffsetMeters(), gridCount);
        List<RequiredOffset> merged = new ArrayList<>(required);
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
        List<LateralStateCell> cells = createCells(profile, merged, family);
        double actualPitch = maximumAdjacentPitch(cells);
        ProbabilisticStateLattice lattice = new ProbabilisticStateLattice(cells, components(profile, family),
            actualPitch, limited);
        return new StateSpaceBuildResult(StateSpaceBuildResult.Status.COMPLETE, Optional.of(lattice),
            retained, limited ? "resolution-limited" : "native-half-pixel-grid");
    }

    private static List<RequiredOffset> requiredOffsets(ProbabilisticProfile profile, double tolerance,
        ObservationFamily family) {
        List<RequiredOffset> result = new ArrayList<>();
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

    private static List<Double> regularGrid(double minimum, double maximum, int count) {
        if (count <= 1) {
            return List.of(minimum);
        }
        List<Double> result = new ArrayList<>(count);
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
        List<RequiredOffset> offsets, ObservationFamily family) {
        List<LateralStateCell> cells = new ArrayList<>(offsets.size());
        for (int index = 0; index < offsets.size(); index++) {
            RequiredOffset current = offsets.get(index);
            double left = index == 0 ? profile.minimumOffsetMeters()
                : 0.5 * (offsets.get(index - 1).offset() + current.offset());
            double right = index == offsets.size() - 1 ? profile.maximumOffsetMeters()
                : 0.5 * (current.offset() + offsets.get(index + 1).offset());
            double width = Math.max(1e-12, right - left);
            cells.add(new LateralStateCell(current.offset(), width, current.exact(), current.mandatory(),
                nearestBranch(profile, current.offset(), family)));
        }
        return List.copyOf(cells);
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
        ObservationFamily family) {
        Map<String, ProbabilisticProfile.Mode> modes = new LinkedHashMap<>();
        profile.modes().stream()
            .filter(mode -> mode.groupedParent() == (family == ObservationFamily.GROUPED_PARENT))
            .sorted(Comparator.comparing(ProbabilisticProfile.Mode::id))
            .forEach(mode -> modes.putIfAbsent(mode.evidenceLineage(), mode));
        Map<String, ProbabilisticProfile.CensoredMode> censored = new LinkedHashMap<>();
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
        List<ObservationComponent> result = new ArrayList<>();
        if (nonmissingTotal > 0.0) {
            for (ProbabilisticProfile.Mode mode : modes.values()) {
                result.add(new ObservationComponent(mode.id(), mode.evidenceLineage(),
                    ObservationComponent.Kind.MEASURED, available * mode.strength() / nonmissingTotal,
                    mode.groupedParent()));
            }
            for (ProbabilisticProfile.CensoredMode mode : censored.values()) {
                result.add(new ObservationComponent(mode.id(), mode.evidenceLineage(),
                    ObservationComponent.Kind.CENSORED, available * mode.strength() / nonmissingTotal, false));
            }
        }
        result.add(new ObservationComponent("missing", "missing:" + profile.profileIndex(),
            ObservationComponent.Kind.MISSING, missingWeight, false));
        return List.copyOf(result);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record RequiredOffset(double offset, boolean mandatory, boolean exact) { }
}
