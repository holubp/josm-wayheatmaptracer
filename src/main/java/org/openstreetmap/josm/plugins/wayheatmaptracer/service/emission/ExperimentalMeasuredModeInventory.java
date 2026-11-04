package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
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
    private record Alias(Owner owner) { }
    private final IdentityHashMap<NativeConnectorEvaluator.RowState, Alias> aliases;

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
            boolean nativeKnown = supportsAffineChart(input.context())
                && input.context().resolution().nativePitchMeters().isPresent();
            for (var pitch : input.context().resolution().spatialPitchSamples()) {
                work(1); nativeKnown &= pitch.nativePitchMeters().isPresent();
            }
            for (var observation : binding.observations()) {
                work(1);
                boolean fallback = observation.profile().peaks().stream().allMatch(peak -> peak.syntheticCenter()
                    && peak.supportWidthPx() <= 0 && peak.prominence() <= 0);
                work(observation.profile().peaks().size());
                if (!nativeKnown || fallback || observation.sourceClass() != NativeConnectorEvaluator.SourceClass.OBSERVED) {
                    rows.add(List.of()); extractions.add(null); rowSamples.add(List.of()); continue;
                }
                var profile = observation.measurementProfile();
                double metricPitch = normalPitch(input.context(), observation.retainedObservationIndex());
                double nativePitch = input.context().nativePitchAtSourceChainage(
                    input.context().sourceChainageMeters(observation.retainedObservationIndex()));
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
    public boolean belongsTo(SampledEmissionInput other) { input.requireOpenAndUnchanged(); return input == other; }
    void requireBinding(LegacyEmissionBinding other) {
        input.requireOpenAndUnchanged();
        if (binding != other) throw new IllegalArgumentException("Measured inventory belongs to another source binding");
    }
    void recordTerminal(RuntimeException failure) { input.recordTerminalExecutionFailure(failure); }

    /** A revised domain publishes aliases and their accounting atomically after source revalidation. */
    final class Publication implements AutoCloseable {
        private final AttemptMemoryLedger.Owner scope;
        private final List<NativeConnectorEvaluator.RowState> bound;
        private boolean committed;
        private boolean closed;
        Publication() {
            scope = memory.child("domain-alias-publication");
            try {
                budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                var listReservation = scope.reserve(128);
                bound = new ArrayList<>();
                listReservation.adopt(bound);
            } catch (RuntimeException failure) { scope.close(); throw failure; }
        }
        void bind(NativeConnectorEvaluator.RowState state, Owner owner) {
            if (closed || committed) throw new IllegalStateException("Measured publication is closed");
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
            var reservation = scope.reserve(192); work(1);
            if (aliases.containsKey(state)) throw new IllegalArgumentException("Duplicate measured state alias");
            var alias = new Alias(owner);
            reservation.adopt(alias);
            bound.add(state); aliases.put(state, alias);
        }
        void commit() {
            input.requireOpenAndUnchanged();
            scope.transferTo(memory); committed = true;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (!committed) for (var state : bound) aliases.remove(state);
            scope.close();
        }
    }

    Publication beginPublication(LegacyEmissionBinding binding) {
        try { requireBinding(binding); return new Publication(); }
        catch (RuntimeException failure) {
            if (terminal(failure)) input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    /** Retains a unique evidence alias without changing the old native state or peak. */
    void aliasNative(NativeConnectorEvaluator.RowState state, int originalRow, Publication publication) {
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
        Double exactScalar = null;
        for (Owner owner : ownersAt(originalRow)) {
            work(1);
            double offset = state.offsetRasterPixels() * owner.metersPerRasterOffset;
            // A unique raw high-core interval is stronger than nearest-center/sigma matching.
            var samples = observation.measurementProfile().intensitySamples();
            double left = samples.get(owner.core.leftAdjacentSampleIndex()).offsetPx() * owner.metersPerRasterOffset;
            double right = samples.get(owner.core.rightAdjacentSampleIndex()).offsetPx() * owner.metersPerRasterOffset;
            boolean insideOwnedCore = false;
            if (offset > left && offset < right) {
                for (int sample = owner.core.firstHighCoreSampleIndex(); sample <= owner.core.lastHighCoreSampleIndex(); sample++) {
                    work(1);
                    if (same(offset, samples.get(sample).offsetPx() * owner.metersPerRasterOffset)) insideOwnedCore = true;
                }
                if (!insideOwnedCore) {
                    if (exactScalar == null) exactScalar = actualScalarAt(state);
                    insideOwnedCore = Double.isFinite(exactScalar)
                        && exactScalar >= owner.receipt.rawCore().orElseThrow().threshold();
                }
            }
            if (insideOwnedCore) {
                if (matched != null) return;
                matched = owner;
            }
        }
        if (matched != null) publication.bind(state, matched);
    }

    private double actualScalarAt(NativeConnectorEvaluator.RowState state) {
        var fact = input.context().observations().get(state.row());
        var anchor = fact.location().rasterPoint(); var normal = fact.normal();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        try (var scratch = memory.reserve(40_960)) {
            var raster = new RasterPoint(anchor.x() + normal.x() * state.offsetRasterPixels(),
                anchor.y() + normal.y() * state.offsetRasterPixels());
            var metric = input.context().metricAt(raster);
            if (!input.context().decisionRegion().containsSegment(metric, metric, CancellationProbe.NONE,
                    count -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, count), memory)
                    || !input.context().evidenceRegion().containsSegment(metric, metric, CancellationProbe.NONE,
                        count -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, count), memory)) return Double.NaN;
            var point = input.context().nativeGrid().toGrid(raster);
            var result = BilinearSegmentIntegral.evaluate(input.context().nativeGrid(), point, point,
                CancellationProbe.NONE, () -> budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1));
            return result.mean().orElse(Double.NaN);
        }
    }

    /** Issues only the exact accepted center; the opaque owner cannot issue another position. */
    NativeConnectorEvaluator.RowState issueCenter(Owner owner, int nativeStateId, Publication publication) {
        requireOwned(owner, owner.originalRow);
        var state = new NativeConnectorEvaluator.RowState(owner.retainedRow, nativeStateId,
            owner.centerRasterOffset(), NativeConnectorEvaluator.SourceClass.OBSERVED, null);
        publication.bind(state, owner);
        return state;
    }

    public Owner ownerFor(NativeConnectorEvaluator.RowState state) {
        requireLive(); work(1); var alias = aliases.get(state); return alias == null ? null : alias.owner();
    }

    public void requireOwned(Owner owner, int originalRow) {
        input.requireOpenAndUnchanged(); work(1);
        if (owner == null || owner.issuer != this || owner.originalRow != originalRow
                || binding.observations().get(originalRow).retainedObservationIndex() != owner.retainedRow)
            throw new IllegalArgumentException("Measured mode owner belongs to another source or row");
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
        return context.metricNormalPitchAtRow(retainedRow);
    }

    /** The reviewed chart proof covers exact detached affine inputs only. */
    static boolean supportsAffineChart(OptionalEmissionContext context) {
        return context.origin() != OptionalEmissionContext.Origin.MANAGED_CAPTURE;
    }
}
