package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;

/** Original Legacy state inventory, independent of retained-context row padding. */
public final class LegacyEmissionDomain {
    public enum Kind { OBSERVED_PEAK, BASE_CARRY, INTERVAL_TARGET }

    /** Native state identity uses the original row, never the retained context index. */
    public record State(int originalRow, int nativeStateId, Kind kind, int offsetOriginId,
            double offsetRasterPixels, CrossSectionPeak peakOwner,
            NativeConnectorEvaluator.RowState evaluatorState) {
        public State {
            if (originalRow < 0 || nativeStateId < 0 || kind == null || offsetOriginId < 0
                    || !Double.isFinite(offsetRasterPixels) || evaluatorState == null
                    || (kind == Kind.OBSERVED_PEAK) != (peakOwner != null))
                throw new IllegalArgumentException("Legacy native state identity is incomplete");
        }
    }

    /** A seed is numeric memory, not an observation or measured point. */
    public record Seed(int id, double offsetRasterPixels) {
        public Seed {
            if (id < 0 || !Double.isFinite(offsetRasterPixels))
                throw new IllegalArgumentException("Legacy seed is invalid");
        }
    }

    private final LegacyEmissionBinding binding;
    private final List<CrossSectionProfile> suppressedProfiles;
    private final List<Seed> seeds;
    private final List<List<State>> observed;
    private final Map<CrossSectionPeak, Integer> originalPeakIdentity;
    private final int nextOriginId;

    public LegacyEmissionDomain(LegacyEmissionBinding binding, List<CrossSectionProfile> suppressedProfiles,
            List<Double> seedOffsets, EmissionWorkBudget budget) {
        this.binding = Objects.requireNonNull(binding);
        this.suppressedProfiles = List.copyOf(Objects.requireNonNull(suppressedProfiles));
        Objects.requireNonNull(seedOffsets); Objects.requireNonNull(budget);
        if (this.suppressedProfiles.size() != binding.observations().size() || seedOffsets.isEmpty())
            throw new IllegalArgumentException("Legacy suppression and seed inventory must cover original N rows");
        var seedList = new ArrayList<Seed>(seedOffsets.size());
        for (int i = 0; i < seedOffsets.size(); i++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            seedList.add(new Seed(i, seedOffsets.get(i)));
        }
        seeds = List.copyOf(seedList);
        var idByPeak = new IdentityHashMap<CrossSectionPeak, Integer>();
        var rows = new ArrayList<List<State>>(this.suppressedProfiles.size());
        int origin = seeds.size();
        for (int row = 0; row < this.suppressedProfiles.size(); row++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var observation = binding.observations().get(row);
            var profile = this.suppressedProfiles.get(row);
            if (profile.samplingAnchor() != observation.profile().samplingAnchor()
                    || profile.normalScreen() != observation.profile().normalScreen())
                throw new IllegalArgumentException("Suppressed Legacy shell differs from original producer row");
            var producerIds = new IdentityHashMap<CrossSectionPeak, Integer>();
            for (var owner : observation.producerPeaks()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                producerIds.put(owner.peak(), owner.originalPeakId());
            }
            var states = new ArrayList<State>();
            for (CrossSectionPeak peak : profile.peaks()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                Integer nativeId = producerIds.get(peak);
                if (nativeId == null)
                    throw new IllegalArgumentException("Suppression created an unowned Legacy peak");
                idByPeak.put(peak, nativeId);
                if (isSamplerFallback(profile))
                    continue; // Sampler fallback peak is not a native chosen state.
                var evaluatorState = new NativeConnectorEvaluator.RowState(
                    observation.retainedObservationIndex(), nativeId, peak.offsetPx(),
                    observation.sourceClass(), observation.sourceClass() == NativeConnectorEvaluator.SourceClass.OBSERVED
                        ? peak : null);
                states.add(new State(row, nativeId, Kind.OBSERVED_PEAK, origin++, peak.offsetPx(), peak,
                    evaluatorState));
            }
            if (states.size() > 96)
                throw new IllegalArgumentException("Legacy native state family exceeds 96 states at original row " + row);
            rows.add(List.copyOf(states));
        }
        observed = List.copyOf(rows);
        originalPeakIdentity = idByPeak;
        nextOriginId = origin;
    }

    public int rowCount() { return observed.size(); }
    public List<Seed> seeds() { return seeds; }
    public List<State> observedAt(int originalRow) { return observed.get(originalRow); }
    public CrossSectionProfile suppressedProfile(int originalRow) { return suppressedProfiles.get(originalRow); }
    public LegacyEmissionBinding binding() { return binding; }
    public boolean unsupported(int originalRow) {
        return observed.get(originalRow).isEmpty();
    }

    private static boolean isSamplerFallback(CrossSectionProfile profile) {
        return profile.peaks().isEmpty() || profile.peaks().stream().allMatch(peak ->
            peak.syntheticCenter() && peak.supportWidthPx() <= 0.0 && peak.prominence() <= 0.0
                && peak.maxProfileIntensity() <= 0.0);
    }
    public int originalPeakId(CrossSectionPeak peak) {
        Integer id = originalPeakIdentity.get(peak);
        if (id == null) throw new IllegalArgumentException("Peak is not in the original Legacy inventory");
        return id;
    }

    /** Expands a genuine carry only for the incoming origin that reaches this row. */
    public State carry(int row, int originId, double offset, EmissionWorkBudget budget) {
        if (!unsupported(row) || originId < 0 || originId >= nextOriginId || !Double.isFinite(offset))
            throw new IllegalArgumentException("Legacy carry requires a reached unsupported row and exact origin");
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
        var observation = binding.observations().get(row);
        int nativeId = Math.addExact(1_000_000, originId);
        return new State(row, nativeId, Kind.BASE_CARRY, originId, offset, null,
            new NativeConnectorEvaluator.RowState(observation.retainedObservationIndex(), nativeId, offset,
                observation.sourceClass(), null));
    }

    /** Native unsupported fallback for one actual anchored interval. */
    public State intervalTarget(int row, int leftRow, int rightRow, State left, State right,
            EmissionWorkBudget budget) {
        if (!unsupported(row) || !(leftRow < row && row < rightRow)
                || left.originalRow() != leftRow || right.originalRow() != rightRow)
            throw new IllegalArgumentException("Interval target is outside its exact anchor pair");
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
        double value = left.offsetRasterPixels() + (right.offsetRasterPixels() - left.offsetRasterPixels())
            * (row - leftRow) / (rightRow - leftRow);
        int nativeId = Math.addExact(2_000_000, Math.addExact(Math.multiplyExact(leftRow, rowCount()), rightRow));
        var observation = binding.observations().get(row);
        return new State(row, nativeId, Kind.INTERVAL_TARGET, nativeId, value, null,
            new NativeConnectorEvaluator.RowState(observation.retainedObservationIndex(), nativeId, value,
                observation.sourceClass(), null));
    }

    public RasterPoint rasterPoint(State state) {
        var profile = binding.observations().get(state.originalRow()).profile();
        var anchor = profile.anchorScreen(); var normal = profile.normalScreen();
        return new RasterPoint(anchor.x + normal.x * state.offsetRasterPixels(),
            anchor.y + normal.y * state.offsetRasterPixels());
    }

    public MetricPoint metricPoint(State state, OptionalEmissionContext context) {
        var point = rasterPoint(state);
        return context.rasterTransform().pixelCenterToMetric(point.x(), point.y());
    }
}
