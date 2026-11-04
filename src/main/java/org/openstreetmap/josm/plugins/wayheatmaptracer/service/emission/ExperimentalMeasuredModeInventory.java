package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** Input-owned accepted measurements for the explicitly revised experimental policy only. */
public final class ExperimentalMeasuredModeInventory {
    public static final String POLICY_ID = "legacy-measured-affinity-v1";

    /** Opaque accepted receipt issuer; callers cannot manufacture observed ownership. */
    public static final class Owner {
        private final ExperimentalMeasuredModeInventory issuer;
        private final int originalRow;
        private final int retainedRow;
        private final AcceptedModeThresholdReceipt receipt;
        private final RawCoreReceipt core;
        private final FlankReceipt flank;
        private final double metersPerRasterOffset;
        private final double nativePitchMeters;

        private Owner(ExperimentalMeasuredModeInventory issuer, int originalRow, int retainedRow,
                AcceptedModeThresholdReceipt receipt, RawCoreReceipt core, FlankReceipt flank,
                double metersPerRasterOffset, double nativePitchMeters) {
            this.issuer = issuer; this.originalRow = originalRow; this.retainedRow = retainedRow;
            this.receipt = receipt; this.core = core; this.flank = flank;
            this.metersPerRasterOffset = metersPerRasterOffset; this.nativePitchMeters = nativePitchMeters;
        }
        public int originalRow() { issuer.requireLive(); return originalRow; }
        public int retainedRow() { issuer.requireLive(); return retainedRow; }
        public AcceptedModeThresholdReceipt receipt() { issuer.requireLive(); return receipt; }
        public RawCoreReceipt rawCore() { issuer.requireLive(); return core; }
        public FlankReceipt flanks() { issuer.requireLive(); return flank; }
        public double centerRasterOffset() { issuer.requireLive(); return receipt.mode().centerMeters() / metersPerRasterOffset; }
        public double nativePitchMeters() { issuer.requireLive(); return nativePitchMeters; }
        public double metersPerRasterOffset() { issuer.requireLive(); return metersPerRasterOffset; }
    }

    private final SampledEmissionInput input;
    private final LegacyEmissionBinding binding;
    private final EmissionWorkBudget budget;
    private final AttemptMemoryLedger.Owner memory;
    private final List<List<Owner>> rows;
    private final List<AcceptedThresholdExtraction> extractions;
    private final List<List<Sample>> rowSamples;
    private final IdentityHashMap<NativeConnectorEvaluator.RowState, Owner> aliases;

    ExperimentalMeasuredModeInventory(SampledEmissionInput input) {
        this.input = input;
        this.binding = input.legacyBinding().orElseThrow();
        this.budget = input.workBudget();
        this.memory = input.openExecutionOwner(POLICY_ID);
        try {
            reserve(512L + 96L * binding.observations().size());
            rows = new ArrayList<>(binding.observations().size());
            extractions = new ArrayList<>(binding.observations().size());
            rowSamples = new ArrayList<>(binding.observations().size());
            aliases = new IdentityHashMap<>();
            var extractor = new LocalScalarProfileExtractor();
            for (var observation : binding.observations()) {
                work(1);
                if (observation.sourceClass() != NativeConnectorEvaluator.SourceClass.OBSERVED) {
                    rows.add(List.of()); extractions.add(null); rowSamples.add(List.of()); continue;
                }
                var profile = observation.measurementProfile();
                double metricPitch = normalPitch(input.context(), observation.retainedObservationIndex());
                double nativePitch = input.context().resolution().effectivePitchMetersAt(
                    input.context().profileChainage().sourceOriginGroundMeters()
                        + profile.samplingAnchor().cumulativeGroundDistanceMeters());
                if (!Double.isFinite(metricPitch) || metricPitch <= 0 || !Double.isFinite(nativePitch)
                        || nativePitch <= 0) throw new IllegalArgumentException("Measured mode pitch is unavailable");
                reserve(64L + 64L * profile.intensitySamples().size());
                var samples = new ArrayList<Sample>(profile.intensitySamples().size());
                for (var sample : profile.intensitySamples()) {
                    work(1);
                    samples.add(new Sample(sample.offsetPx() * metricPitch, sample.nativeIntensity(), sample.insideRaster()));
                }
                var frozenSamples = List.copyOf(samples);
                rowSamples.add(frozenSamples);
                if (samples.isEmpty()) { rows.add(List.of()); extractions.add(null); continue; }
                var extraction = extractor.extractDetailedWithAcceptedThresholds(frozenSamples, nativePitch,
                    EvidenceModelParameters.Localization.defaults(), memory, CancellationProbe.NONE,
                    count -> budget.charge(EmissionWorkBudget.Operation.MODE_EXTRACTION, count));
                extractions.add(extraction);
                reserve(64L + 192L * extraction.acceptedReceipts().size());
                var owners = new ArrayList<Owner>(extraction.acceptedReceipts().size());
                for (var receipt : extraction.acceptedReceipts()) {
                    work(1);
                    RawCoreReceipt core = null;
                    FlankReceipt flank = null;
                    for (var candidate : extraction.result().rawCoreReceipts()) {
                        work(1); if (candidate.mode() == receipt.mode()) core = candidate;
                    }
                    for (var candidate : extraction.result().flankReceipts()) {
                        work(1); if (candidate.mode() == receipt.mode()) flank = candidate;
                    }
                    if (receipt.rawCore().isEmpty() || core == null || flank == null
                            || !flank.hasTwoSidedValidOutwardContinuation()
                            || !core.leftAdjacentObservedValid() || !core.leftAdjacentBelowCore()
                            || !core.rightAdjacentObservedValid() || !core.rightAdjacentBelowCore()) continue;
                    owners.add(new Owner(this, observation.originalRow(), observation.retainedObservationIndex(),
                        receipt, core, flank, metricPitch, nativePitch));
                }
                rows.add(List.copyOf(owners));
            }
        } catch (RuntimeException failure) {
            memory.close();
            if (terminal(failure)) input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    /** Complete locally measured owners; no unavailable row is upgraded to OBSERVED. */
    public List<Owner> ownersAt(int originalRow) { requireLive(); work(1); return rows.get(originalRow); }
    public AcceptedThresholdExtraction extractionAt(int originalRow) { requireLive(); work(1); return extractions.get(originalRow); }
    public List<Sample> samplesAt(int originalRow) { requireLive(); work(1); return rowSamples.get(originalRow); }
    public boolean belongsTo(SampledEmissionInput other) { requireLive(); return input == other; }
    void requireBinding(LegacyEmissionBinding other) {
        requireLive();
        if (binding != other) throw new IllegalArgumentException("Measured inventory belongs to another source binding");
    }
    void recordTerminal(RuntimeException failure) { input.recordTerminalExecutionFailure(failure); }

    /** Retains a unique evidence alias without changing the old native state or peak. */
    void aliasNative(NativeConnectorEvaluator.RowState state, int originalRow) {
        requireLive();
        var observation = binding.observations().get(originalRow);
        if (state.row() != observation.retainedObservationIndex()
                || state.sourceClass() != NativeConnectorEvaluator.SourceClass.OBSERVED) return;
        boolean nativeOwned = false;
        for (var peak : observation.producerPeaks()) {
            work(1);
            if (peak.originalPeakId() == state.nativeStateId() && peak.measurementPeakOwner() == state.peakOwner()
                    && same(peak.peak().offsetPx(), state.offsetRasterPixels())) nativeOwned = true;
        }
        if (!nativeOwned) throw new IllegalArgumentException("Measured alias has no original native peak owner");
        Owner matched = null;
        for (Owner owner : ownersAt(originalRow)) {
            work(1);
            double offset = state.offsetRasterPixels() * owner.metersPerRasterOffset;
            // A unique raw high-core interval is stronger than nearest-center/sigma matching.
            var samples = observation.measurementProfile().intensitySamples();
            double left = samples.get(owner.core.firstHighCoreSampleIndex()).offsetPx() * owner.metersPerRasterOffset;
            double right = samples.get(owner.core.lastHighCoreSampleIndex()).offsetPx() * owner.metersPerRasterOffset;
            if (offset >= left && offset <= right) {
                if (matched != null) return;
                matched = owner;
            }
        }
        if (matched != null) bind(state, matched);
    }

    /** Issues only the exact accepted center; the opaque owner cannot issue another position. */
    NativeConnectorEvaluator.RowState issueCenter(Owner owner, int nativeStateId) {
        requireOwned(owner, owner.originalRow);
        var state = new NativeConnectorEvaluator.RowState(owner.retainedRow, nativeStateId,
            owner.centerRasterOffset(), NativeConnectorEvaluator.SourceClass.OBSERVED, null);
        bind(state, owner);
        return state;
    }

    public Owner ownerFor(NativeConnectorEvaluator.RowState state) {
        requireLive(); work(1); return aliases.get(state);
    }

    public void requireOwned(Owner owner, int originalRow) {
        requireLive(); work(1);
        if (owner == null || owner.issuer != this || owner.originalRow != originalRow
                || binding.observations().get(originalRow).retainedObservationIndex() != owner.retainedRow)
            throw new IllegalArgumentException("Measured mode owner belongs to another source or row");
    }

    private void bind(NativeConnectorEvaluator.RowState state, Owner owner) {
        reserve(192); work(1);
        if (aliases.put(state, owner) != null) throw new IllegalArgumentException("Duplicate measured state alias");
    }
    private void requireLive() { input.requireRetainedOpen(); if (memory.isClosed()) throw new IllegalStateException("Measured inventory is closed"); }
    private void work(long count) { budget.charge(EmissionWorkBudget.Operation.PREPARATION, count); }
    private void reserve(long bytes) { budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1); memory.reserve(bytes); }
    static boolean same(double a, double b) { return Double.doubleToLongBits(a) == Double.doubleToLongBits(b); }
    static boolean terminal(RuntimeException failure) {
        return failure instanceof EmissionWorkBudget.LimitExceededException
            || failure instanceof AttemptMemoryLedger.ResourceLimitException
            || failure instanceof java.util.concurrent.CancellationException
            || failure instanceof LegacyEmissionDomain.StateFamilyLimitException;
    }

    /** Physical displacement of one original raster-normal offset, without virtual-scale substitution. */
    static double normalPitch(OptionalEmissionContext context, int retainedRow) {
        var normal = context.observations().get(retainedRow).normal();
        RasterMetricTransform transform = context.rasterTransform();
        double scale = transform.axisUnit() == RasterMetricTransform.AxisUnit.SOURCE_PIXEL
            ? transform.rasterPixelsPerSourcePixel() : 1;
        return Math.hypot((normal.x() * transform.xAxisEastMetersPerSourcePixel()
                + normal.y() * transform.yAxisEastMetersPerSourcePixel()) / scale,
            (normal.x() * transform.xAxisNorthMetersPerSourcePixel()
                + normal.y() * transform.yAxisNorthMetersPerSourcePixel()) / scale);
    }
}
