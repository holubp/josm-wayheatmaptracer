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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Original Legacy state inventory, independent of retained-context row padding. */
public final class LegacyEmissionDomain {
    public enum Kind { OBSERVED_PEAK, OBSERVED_SCALAR_MODE, BASE_CARRY, INTERVAL_TARGET }

    /** Refusal to truncate an original native state family. */
    public static final class StateFamilyLimitException extends RuntimeException {
        private final int originalRow;
        private final int familySize;
        private final int limit;
        public StateFamilyLimitException(int originalRow, int familySize) {
            this(originalRow, familySize, 96);
        }
        public StateFamilyLimitException(int originalRow, int familySize, int limit) {
            super("Original Legacy row state family exceeds " + limit);
            this.originalRow = originalRow; this.familySize = familySize; this.limit = limit;
        }
        public int originalRow() { return originalRow; }
        public int familySize() { return familySize; }
        public int limit() { return limit; }
    }

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
    private final ExperimentalMeasuredModeInventory measuredModes;

    public LegacyEmissionDomain(LegacyEmissionBinding binding, List<CrossSectionProfile> suppressedProfiles,
            List<Double> seedOffsets, EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
        this(binding, suppressedProfiles, seedOffsets, budget, owner, null);
    }

    /** Explicit revised experimental inventory; the old constructor remains native-only. */
    public LegacyEmissionDomain(LegacyEmissionBinding binding, List<CrossSectionProfile> suppressedProfiles,
            List<Double> seedOffsets, EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner,
            ExperimentalMeasuredModeInventory measuredModes) {
        this.binding = Objects.requireNonNull(binding);
        this.measuredModes = measuredModes;
        if (measuredModes != null) measuredModes.requireBinding(binding);
        Objects.requireNonNull(suppressedProfiles); Objects.requireNonNull(seedOffsets);
        Objects.requireNonNull(budget); Objects.requireNonNull(owner);
        long peaks = 0;
        for (var observation : binding.observations()) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            peaks = Math.addExact(peaks, observation.producerPeaks().size());
        }
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(Math.addExact(512, Math.addExact(
            Math.multiplyExact(512L, binding.observations().size()), Math.multiplyExact(256L, peaks))));
        this.suppressedProfiles = List.copyOf(suppressedProfiles);
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
            var producerOwners = new IdentityHashMap<CrossSectionPeak, LegacyEmissionBinding.PeakOwner>();
            for (var peakOwner : observation.producerPeaks()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                producerOwners.put(peakOwner.peak(), peakOwner);
            }
            var states = new ArrayList<State>();
            for (CrossSectionPeak peak : profile.peaks()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                var producerOwner = producerOwners.get(peak);
                if (producerOwner == null)
                    throw new IllegalArgumentException("Suppression created an unowned Legacy peak");
                int nativeId = producerOwner.originalPeakId();
                idByPeak.put(peak, nativeId);
                if (isSamplerFallback(profile))
                    continue; // Sampler fallback peak is not a native chosen state.
                var evaluatorState = new NativeConnectorEvaluator.RowState(
                    observation.retainedObservationIndex(), nativeId, peak.offsetPx(),
                    observation.sourceClass(), observation.sourceClass() == NativeConnectorEvaluator.SourceClass.OBSERVED
                        ? producerOwner.measurementPeakOwner() : null);
                states.add(new State(row, nativeId, Kind.OBSERVED_PEAK, origin++, peak.offsetPx(), peak,
                    evaluatorState));
            }
            if (states.size() > budget.stateFamilyLimit())
                throw new StateFamilyLimitException(row, states.size(), budget.stateFamilyLimit());
            rows.add(List.copyOf(states));
        }
        // Append after constructing every old state so all old origin IDs remain unchanged.
        if (measuredModes != null) {
            for (int row = 0; row < rows.size(); row++) {
                var originals = rows.get(row);
                for (var state : originals) measuredModes.aliasNative(state.evaluatorState(), row);
                var expanded = new ArrayList<State>(originals);
                int nextId = binding.observations().get(row).producerPeaks().stream()
                    .mapToInt(LegacyEmissionBinding.PeakOwner::originalPeakId).max().orElse(-1) + 1;
                for (var measured : measuredModes.ownersAt(row)) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    boolean represented = false;
                    for (var state : originals) {
                        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                        if (measuredModes.ownerFor(state.evaluatorState()) == measured
                                && ExperimentalMeasuredModeInventory.same(state.offsetRasterPixels(),
                                    measured.centerRasterOffset())) represented = true;
                    }
                    if (represented) continue;
                    if (expanded.size() >= budget.stateFamilyLimit()) {
                        var failure = new StateFamilyLimitException(row, expanded.size() + 1, budget.stateFamilyLimit());
                        measuredModes.recordTerminal(failure);
                        throw failure;
                    }
                    budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                    owner.reserve(512);
                    var evaluatorState = measuredModes.issueCenter(measured, nextId++);
                    expanded.add(new State(row, evaluatorState.nativeStateId(), Kind.OBSERVED_SCALAR_MODE,
                        origin++, evaluatorState.offsetRasterPixels(), null, evaluatorState));
                }
                rows.set(row, List.copyOf(expanded));
            }
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
    public ExperimentalMeasuredModeInventory measuredModes() { return measuredModes; }
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
        var profile = binding.observations().get(state.originalRow()).measurementProfile();
        var anchor = profile.anchorScreen(); var normal = profile.normalScreen();
        return new RasterPoint(anchor.x + normal.x * state.offsetRasterPixels(),
            anchor.y + normal.y * state.offsetRasterPixels());
    }

    public MetricPoint metricPoint(State state, OptionalEmissionContext context) {
        var point = rasterPoint(state);
        return context.metricAt(point);
    }
}
