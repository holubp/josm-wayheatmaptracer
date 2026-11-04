package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.CensusGeometry.Interval.point;

/** Source-bound diagnostic P0 only. Never extracts modes, issues states, or supplies eligibility. */
public final class CurvedChartCensus {
    public static final String POLICY_ID = "curved-chart-authority-census-p0-v1";
    private CurvedChartCensus() { }
    /** Lowercase SHA-256 or an ASCII synthetic: label, at most 128 characters; provenance only. */
    public record Metadata(String expectedProfileArtifactHash, String actualProfileArtifactHash,
        List<Integer> savedFoldedPairs) {
        public Metadata { Objects.requireNonNull(expectedProfileArtifactHash); Objects.requireNonNull(actualProfileArtifactHash);
            Objects.requireNonNull(savedFoldedPairs);
            requireIdentifier(expectedProfileArtifactHash); requireIdentifier(actualProfileArtifactHash); }
    }
    private static void requireIdentifier(String value) {
        // Check length before any scan/comparison. Both retained identities fit the existing 4096-byte header.
        if (value.isEmpty() || value.length() > 128) throw new IllegalArgumentException("Artifact identifier exceeds its bounded syntax");
        if (value.length() == 64) {
            boolean hash = true;
            for (int i = 0; i < 64; i++) {
                char c = value.charAt(i); if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) { hash = false; break; }
            }
            if (hash) return;
        }
        if (!value.startsWith("synthetic:") || value.length() == 10)
            throw new IllegalArgumentException("Artifact identifier requires SHA-256 or an explicit synthetic label");
        for (int i = 10; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '-' || c == '_' || c == '.'))
                throw new IllegalArgumentException("Synthetic artifact label is outside its bounded ASCII syntax");
        }
    }
    public enum Completion { COMPLETE_DIAGNOSTIC, IDENTITY_MISMATCH, ORIGINAL_MAPPING_UNAVAILABLE }
    public enum Availability { UNAVAILABLE }
    public enum Family { POST_SUPPRESSION_ISSUED_STATES, SEED_ORIGINS, SUPPLEMENTARY_ACCEPTED_CENTERS,
        BASE_CARRIES, INTERVAL_TARGETS, PROTECTED_BOUNDARY_PORTS, MODE_PROOF_SUPPORT, MOVED_FINAL_PORTS }
    public enum EnvelopeKind { VALID_RUN_ENVELOPE, POSITIVE_RUN_ENVELOPE, UNAVAILABLE }
    public enum Boundary { PROFILE_EDGE, INVALID_SAMPLE, VALID_ZERO_SAMPLE }
    public enum Domain { PRODUCER_PORTS_ONLY, PRODUCER_PLUS_POSITIVE_RUN, PRODUCER_PLUS_VALID_RUN }
    public enum Status { CENTER_DOMAIN_ONLY, CONSERVATIVE_PLAN_REFUSED, UNAVAILABLE }
    public enum Reason { NONE, ROW_SOURCE_UNAVAILABLE, SAMPLES_UNAVAILABLE, SAMPLE_ORDER_OR_VALUE,
        UNKNOWN_GRID_MAPPING, DEGENERATE_CHORD, DEGENERATE_TRANSVERSE_DOMAIN, ARITHMETIC_UNAVAILABLE,
        PITCH_UNAVAILABLE, SPACING_UNRESOLVED, OUTSIDE_PHYSICAL_DOMAIN, ORIGINAL_ROUTE_REFUSED,
        ACQUIRED_SUPPORT_REFUSED, INVALID_CELL_SUPPORT }
    public record Bounds(double minimumX, double maximumX, double minimumY, double maximumY) { }
    public record ChartCertificate(RasterPoint capturedNativeOrigin, RasterPoint capturedRoundedChord,
        RasterPoint capturedPerpendicular, BigDecimal exactPositiveDeterminant) {
        public ChartCertificate {
            Objects.requireNonNull(capturedNativeOrigin); Objects.requireNonNull(capturedRoundedChord); Objects.requireNonNull(capturedPerpendicular);
            if (exactPositiveDeterminant == null || exactPositiveDeterminant.signum() <= 0)
                throw new IllegalArgumentException("Chart determinant is not strictly positive");
        }
        @Override public String toString() { return "P0Chart[exact-positive-dyadic]"; }
    }
    /** Exact object identities bind the captured generation/settings/phase opaquely, not as invented numeric metadata. */
    public record Source(OptionalEmissionContext contextOwner, NativeScalarGridView gridOwner,
        String sourceIdentity, String scalarContentHash, String profileArtifactHash, String requestIdentity, String contextPolicy,
        EvidenceFieldLineage scalarMapping, OptionalDouble producerUnitsPerGridPixel,
        int cropOriginX, int cropOriginY, int nativeWidth, int nativeHeight) {
        @Override public String toString() { return "P0Source[" + contextPolicy + "]"; }
    }
    /** Unsuppressed producer facts, not a claim to complete issued native state-family ownership. */
    public record ProducerPort(int originalPeakId, LegacyEmissionBinding.PeakOwner sourceOwner,
        double actualOffset, RasterPoint actualRaster, RasterPoint actualNativeGrid, Optional<Bounds> chartBounds) { }
    public record Run(int firstSample, int lastSample, double firstOffset, double lastOffset,
        Boundary leftBoundary, Boundary rightBoundary) { }
    /** Index enclosure only; rounded Mode center reconstruction, physical placement/support and ownership are unavailable. */
    public record Envelope(EnvelopeKind kind, Reason reason, List<Run> runs,
        long excludedInvalidSamples, long excludedZeroSamples) { }
    public record Row(int originalRow, int retainedRow, NativeConnectorEvaluator.SourceClass sourceClass,
        List<ProducerPort> producerPorts, Envelope validRuns, Envelope positiveRuns) { }
    /** Exact cell/node visit counts; visits can repeat between stations and are never called unique cells. */
    public record Support(long presentCells, long invalidCells, long outsideCells, long nodeReads, boolean available) { }
    public record Station(int pair, Domain domain, long index, RasterPoint actualRasterFrom,
        RasterPoint actualRasterTo, RasterPoint actualNativeFrom, RasterPoint actualNativeTo,
        boolean originalRouteAuthorized, boolean acquiredSegmentAuthorized, Support cellSupport) { }
    public record Plan(Domain domain, Optional<Bounds> declaredRectangle, Status status, Reason reason,
        long plannedStations, long routeAuthorized, long routeRefused, long routeUnqueried,
        long acquiredAuthorized, long acquiredRefused, long acquiredUnqueried, Support support,
        boolean spacingCertified, OptionalDouble nativeLengthUpper, OptionalDouble actualSpacingUpperMeters, Optional<Station> firstLimitingStation) { }
    public record Pair(int index, boolean savedOriginalWindowFold, Source source,
        Row left, Row right, Optional<Bounds> producerBounds, Optional<Bounds> validRunBounds,
        Optional<Bounds> positiveRunBounds, Optional<Plan> producerPlan, Optional<Plan> centerPlan,
        Reason chartReason, Optional<ChartCertificate> chart, Availability fullFutureDomain, Availability futureCenterPlacement,
        List<Family> unavailableFamilies, long operationUnits, long retainedBytes, long peakBytes) {
        @Override public String toString() { return "P0Pair[index=" + index + ",reason=" + chartReason + "]"; }
        private Pair accounted(long units, AttemptMemoryLedger.Owner owner) {
            return new Pair(index, savedOriginalWindowFold, source, left, right, producerBounds, validRunBounds,
                positiveRunBounds, producerPlan, centerPlan, chartReason, chart, fullFutureDomain,
                futureCenterPlacement, unavailableFamilies, units, owner.currentBytes(), owner.peakBytes());
        }
    }
    /** Borrowed synchronous callbacks: serialization/copies belong to the caller, not an unbounded census cache. */
    public interface Sink {
        void pair(Pair pair);
        default void station(Station station) { }
        /** Called before rethrowing the original sticky typed terminal failure; no partial result is returned. */
        default void stopped(int totalPairs, int firstUnvisitedPair) { }
    }
    public static final class Summary implements AutoCloseable {
        private final AttemptMemoryLedger.Owner owner;
        private final SampledEmissionInput.ResultLease lease;
        private Completion completion;
        private int totalPairs, visitedPairs, firstUnvisitedPair;
        private boolean matched, closed;
        private String profileArtifactHash;
        private int[] foldedPairs;
        private long operationUnits;
        private Summary(AttemptMemoryLedger.Owner owner, SampledEmissionInput.ResultLease lease) { this.owner = owner; this.lease = lease; }
        private void live() { if (closed) throw new IllegalStateException("Census summary is closed"); }
        public Completion completion() { live(); return completion; }
        /** -1 means no original mapping, not an observed empty pair set. */
        public int totalPairs() { live(); return totalPairs; }
        public int visitedPairs() { live(); return visitedPairs; }
        public int firstUnvisitedPair() { live(); return firstUnvisitedPair; }
        public boolean metadataIdentityMatched() { live(); return matched; }
        public String profileArtifactHash() { live(); return profileArtifactHash; }
        public int savedFoldPairCount() { live(); if (foldedPairs == null) throw new IllegalStateException("Saved pair labels are unavailable"); return foldedPairs.length; }
        public int savedFoldPairId(int index) { live(); return foldedPairs[index]; }
        /** Complete census delta, including shared owning source-validation scans and metadata preparation. */
        public long operationUnits() { live(); return operationUnits; }
        @Override public void close() { if (!closed) { closed = true; try { owner.close(); } finally { lease.close(); } } }
    }
    public static Summary scan(SampledEmissionInput input, Metadata metadata, Sink sink) {
        Objects.requireNonNull(input); Objects.requireNonNull(metadata); Objects.requireNonNull(sink);
        SampledEmissionInput.ResultLease lease = null;
        AttemptMemoryLedger.Owner owner = null; boolean success = false;
        int total = -1, next = 0;
        try {
            long workAtEntry = input.workBudget().snapshot().workUsed();
            input.requireOpenAndUnchanged();
            total = input.legacyBinding().map(binding -> binding.observations().size() - 1).orElse(-1);
            lease = input.retainResultLifetime();
            owner = input.openExecutionOwner(POLICY_ID); var budget = input.workBudget();
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1); var reservation = owner.reserve(4096);
            var result = new Summary(owner, lease); reservation.adopt(result);
            result.totalPairs = total; result.firstUnvisitedPair = 0;
            result.profileArtifactHash = metadata.actualProfileArtifactHash();
            if (total < 0) { result.completion = Completion.ORIGINAL_MAPPING_UNAVAILABLE; result.operationUnits = budget.snapshot().workUsed() - workAtEntry; success = true; return result; }
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,
                (long) metadata.expectedProfileArtifactHash().length() + metadata.actualProfileArtifactHash().length());
            result.matched = metadata.expectedProfileArtifactHash().equals(metadata.actualProfileArtifactHash()) && !metadata.actualProfileArtifactHash().isBlank();
            if (!result.matched) { result.completion = Completion.IDENTITY_MISMATCH; result.operationUnits = budget.snapshot().workUsed() - workAtEntry; success = true; return result; }
            int previous = -1;
            int labelCount = metadata.savedFoldedPairs().size();
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, labelCount);
            if (labelCount > total) throw new IllegalArgumentException("Too many saved pair labels");
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
            var labelsReservation = owner.reserve(AttemptMemoryLedger.arrayBytes(labelCount, Integer.BYTES));
            result.foldedPairs = new int[labelCount]; labelsReservation.adopt(result.foldedPairs);
            int labelIndex = 0;
            for (int folded : metadata.savedFoldedPairs()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (folded <= previous || folded >= total) throw new IllegalArgumentException("Saved labels require ordered unique original pair IDs");
                previous = folded; result.foldedPairs[labelIndex++] = folded;
            }
            try (var scratch = owner.child("one-pair-scratch")) {
                budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1); scratch.reserve(524_288);
                var math = new CensusGeometry(budget); var binding = input.legacyBinding().orElseThrow();
                for (int pair = 0; pair < total; pair++) {
                    long before = budget.snapshot().workUsed(); input.requireOpenAndUnchanged();
                    var left = binding.observations().get(pair); var right = binding.observations().get(pair + 1);
                    long samples = (long) left.measurementProfile().intensitySamples().size() + right.measurementProfile().intensitySamples().size();
                    long ports = (long) left.producerPeaks().size() + right.producerPeaks().size();
                    try (var pairOwner = scratch.child("pair")) {
                        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                        pairOwner.reserve(Math.addExact(16_384, Math.addExact(Math.multiplyExact(samples, 320), Math.multiplyExact(ports, 512))));
                        boolean folded = false;
                        for (int saved : result.foldedPairs) { math.work(1); if (saved == pair) folded = true; }
                        var data = pair(input, left, right, pair, folded, result.profileArtifactHash, math, pairOwner, sink, before);
                        input.requireOpenAndUnchanged(); math.work(1);
                        sink.pair(data.accounted(budget.snapshot().workUsed() - before, pairOwner));
                    }
                    next = pair + 1;
                }
            }
            input.requireOpenAndUnchanged(); result.completion = Completion.COMPLETE_DIAGNOSTIC;
            result.visitedPairs = total; result.firstUnvisitedPair = -1;
            result.operationUnits = budget.snapshot().workUsed() - workAtEntry; success = true; return result;
        } catch (RuntimeException failure) {
            if (ExperimentalMeasuredModeInventory.terminal(failure)) {
                input.recordTerminalExecutionFailure(failure);
                try { sink.stopped(total, next); } catch (RuntimeException ignored) { /* Preserve owning failure. */ }
            }
            throw failure;
        } finally { if (!success) { if (owner != null) owner.close(); if (lease != null) lease.close(); } }
    }
    private static Pair pair(SampledEmissionInput input, LegacyEmissionBinding.OriginalObservation left,
            LegacyEmissionBinding.OriginalObservation right, int pair, boolean folded, String profileArtifactHash, CensusGeometry math,
            AttemptMemoryLedger.Owner memory, Sink sink, long before) {
        var context = input.context(); var grid = context.nativeGrid();
        CensusGeometry.GridMap mapping = null; CensusGeometry.Chart chart = null; Reason reason = Reason.NONE;
        try { mapping = math.new GridMap(grid); } catch (CensusGeometry.Unavailable missing) { reason = Reason.UNKNOWN_GRID_MAPPING; }
        var source = new Source(context, grid, context.sourceIdentity(), context.evidenceContentHash(), profileArtifactHash, context.requestIdentity(),
            context.policyId(), context.lineage(), mapping == null ? OptionalDouble.empty() : OptionalDouble.of(mapping.scale), grid.originX(), grid.originY(), grid.width(), grid.height());
        if (mapping != null) try {
            chart = math.new Chart(grid.toGrid(left.measurementProfile().samplingAnchor().rasterPoint()), grid.toGrid(right.measurementProfile().samplingAnchor().rasterPoint()));
        } catch (CensusGeometry.Unavailable | IllegalArgumentException missing) { reason = Reason.DEGENERATE_CHORD; }
        var a = row(left, grid, mapping, chart, math); var b = row(right, grid, mapping, chart, math);
        CensusGeometry.Rectangle producer = null, valid = null, positive = null; Plan producerPlan = null, centerPlan = null;
        if (chart != null) try {
            var producerExtent = chart.new Extent(); var validExtent = chart.new Extent(); var positiveExtent = chart.new Extent();
            for (var row : List.of(a, b)) for (var port : row.producerPorts()) {
                math.work(1); producer = hull(producer, chart.enclose(mapping.pointBounds(port.actualRaster())));
                producerExtent.include(port.actualNativeGrid()); validExtent.include(port.actualNativeGrid()); positiveExtent.include(port.actualNativeGrid());
            }
            valid = producer; positive = producer;
            for (var row : List.of(a, b)) {
                var observation = row.originalRow() == left.originalRow() ? left : right;
                var profile = observation.measurementProfile(); var anchor = profile.samplingAnchor().rasterPoint();
                var normal = new RasterPoint(profile.normalScreen().x, profile.normalScreen().y);
                for (var run : row.validRuns().runs()) { math.work(1); valid = hull(valid, chart.enclose(mapping.sourceLine(anchor, normal, run.firstOffset(), run.lastOffset())));
                    validExtent.include(grid.toGrid(actualLine(anchor, normal, run.firstOffset()))); validExtent.include(grid.toGrid(actualLine(anchor, normal, run.lastOffset()))); }
                for (var run : row.positiveRuns().runs()) { math.work(1); positive = hull(positive, chart.enclose(mapping.sourceLine(anchor, normal, run.firstOffset(), run.lastOffset())));
                    positiveExtent.include(grid.toGrid(actualLine(anchor, normal, run.firstOffset()))); positiveExtent.include(grid.toGrid(actualLine(anchor, normal, run.lastOffset()))); }
            }
            producerPlan = plan(input, pair, Domain.PRODUCER_PORTS_ONLY, producer, producerExtent.nondegenerate(), chart, math, memory, sink);
            boolean positiveKnown = a.positiveRuns().kind() == EnvelopeKind.POSITIVE_RUN_ENVELOPE && b.positiveRuns().kind() == EnvelopeKind.POSITIVE_RUN_ENVELOPE;
            boolean validKnown = a.validRuns().kind() == EnvelopeKind.VALID_RUN_ENVELOPE && b.validRuns().kind() == EnvelopeKind.VALID_RUN_ENVELOPE;
            centerPlan = positiveKnown ? plan(input, pair, Domain.PRODUCER_PLUS_POSITIVE_RUN, positive, positiveExtent.nondegenerate(), chart, math, memory, sink)
                : validKnown ? plan(input, pair, Domain.PRODUCER_PLUS_VALID_RUN, valid, validExtent.nondegenerate(), chart, math, memory, sink) : null;
        } catch (CensusGeometry.Unavailable unavailable) { reason = Reason.ARITHMETIC_UNAVAILABLE; }
        return new Pair(pair, folded, source, a, b, bounds(producer), bounds(valid), bounds(positive), Optional.ofNullable(producerPlan),
            Optional.ofNullable(centerPlan), reason, chart == null ? Optional.empty() : Optional.of(new ChartCertificate(
                chart.origin, chart.direction, chart.normal, chart.determinant)), Availability.UNAVAILABLE, Availability.UNAVAILABLE, List.of(Family.values()),
            input.workBudget().snapshot().workUsed() - before, memory.currentBytes(), memory.peakBytes());
    }
    private static Row row(LegacyEmissionBinding.OriginalObservation row, NativeScalarGridView grid,
            CensusGeometry.GridMap mapping, CensusGeometry.Chart chart, CensusGeometry math) {
        var profile = row.measurementProfile(); var ports = new ArrayList<ProducerPort>(row.producerPeaks().size());
        for (var peak : row.producerPeaks()) {
            math.work(5); double offset = peak.peak().offsetPx();
            var actual = new RasterPoint(profile.anchorScreen().x + profile.normalScreen().x * offset, profile.anchorScreen().y + profile.normalScreen().y * offset);
            var nativePoint = grid.toGrid(actual); Optional<Bounds> chartBounds = Optional.empty();
            if (mapping != null && chart != null) try { chartBounds = bounds(chart.enclose(mapping.pointBounds(actual))); }
            catch (CensusGeometry.Unavailable unavailable) { /* Exact actual point remains available. */ }
            ports.add(new ProducerPort(peak.originalPeakId(), peak, offset, actual, nativePoint, chartBounds));
        }
        boolean observed = row.sourceClass() == NativeConnectorEvaluator.SourceClass.OBSERVED;
        double fraction = EvidenceModelParameters.Localization.defaults().localModeShoulderFraction();
        var valid = observed ? envelope(profile.intensitySamples(), false, fraction, math) : unavailable(Reason.ROW_SOURCE_UNAVAILABLE);
        var positive = observed ? envelope(profile.intensitySamples(), true, fraction, math) : unavailable(Reason.ROW_SOURCE_UNAVAILABLE);
        return new Row(row.originalRow(), row.retainedObservationIndex(), row.sourceClass(), List.copyOf(ports), valid, positive);
    }
    static Envelope envelope(List<IntensitySample> samples, boolean positive, double shoulderFraction, CensusGeometry math) {
        if (samples.isEmpty()) return unavailable(Reason.SAMPLES_UNAVAILABLE);
        // f>0.5 preserves positive MIN_VALUE; equality 0.5 can tie to zero and must fall back.
        if (positive && !(shoulderFraction > .5 && shoulderFraction <= 1)) return unavailable(Reason.SAMPLE_ORDER_OR_VALUE);
        double previous = Double.NEGATIVE_INFINITY; long invalid = 0, zero = 0; int validCount = 0;
        for (var sample : samples) {
            math.work(1);
            if (!Double.isFinite(sample.offsetPx()) || !(sample.offsetPx() > previous)
                    || sample.insideRaster() && (!Double.isFinite(sample.nativeIntensity()) || sample.nativeIntensity() < 0 || sample.nativeIntensity() > 1))
                return unavailable(Reason.SAMPLE_ORDER_OR_VALUE);
            previous = sample.offsetPx();
            if (!sample.insideRaster()) invalid++; else { validCount++; if (sample.nativeIntensity() == 0) zero++; }
        }
        if (validCount == 0) return unavailable(Reason.SAMPLES_UNAVAILABLE);
        var runs = new ArrayList<Run>(); int first = -1;
        for (int index = 0; index <= samples.size(); index++) {
            math.work(1); boolean included = index < samples.size() && samples.get(index).insideRaster() && (!positive || samples.get(index).nativeIntensity() > 0);
            if (included && first < 0) first = index;
            if (!included && first >= 0) {
                int last = index - 1;
                runs.add(new Run(first, last, samples.get(first).offsetPx(), samples.get(last).offsetPx(), boundary(samples, first - 1), boundary(samples, index))); first = -1;
            }
        }
        return new Envelope(positive ? EnvelopeKind.POSITIVE_RUN_ENVELOPE : EnvelopeKind.VALID_RUN_ENVELOPE, Reason.NONE, List.copyOf(runs), invalid, positive ? zero : 0);
    }
    private static Boundary boundary(List<IntensitySample> samples, int index) {
        return index < 0 || index >= samples.size() ? Boundary.PROFILE_EDGE : !samples.get(index).insideRaster() ? Boundary.INVALID_SAMPLE : Boundary.VALID_ZERO_SAMPLE;
    }
    private static Envelope unavailable(Reason reason) { return new Envelope(EnvelopeKind.UNAVAILABLE, reason, List.of(), 0, 0); }
    private static CensusGeometry.Rectangle hull(CensusGeometry.Rectangle a, CensusGeometry.Rectangle b) {
        return a == null ? b : new CensusGeometry.Rectangle(a.x().hull(b.x()), a.y().hull(b.y()));
    }
    private static Optional<Bounds> bounds(CensusGeometry.Rectangle rectangle) {
        return rectangle == null ? Optional.empty() : Optional.of(new Bounds(rectangle.x().lower(), rectangle.x().upper(), rectangle.y().lower(), rectangle.y().upper()));
    }
    private static RasterPoint actualLine(RasterPoint anchor, RasterPoint normal, double parameter) {
        try { return new RasterPoint(anchor.x() + normal.x() * parameter, anchor.y() + normal.y() * parameter); }
        catch (IllegalArgumentException nonfinite) { throw new CensusGeometry.Unavailable(); }
    }
    private static Plan plan(SampledEmissionInput input, int pair, Domain domain, CensusGeometry.Rectangle rectangle,
            boolean nondegenerate, CensusGeometry.Chart chart, CensusGeometry math, AttemptMemoryLedger.Owner memory, Sink sink) {
        var empty = new Support(0, 0, 0, 0, false);
        if (rectangle == null || !nondegenerate) return new Plan(domain, bounds(rectangle), Status.UNAVAILABLE,
            Reason.DEGENERATE_TRANSVERSE_DOMAIN, 0, 0, 0, 0, 0, 0, 0, empty, false, OptionalDouble.empty(), OptionalDouble.empty(), Optional.empty());
        var context = input.context(); var grid = context.nativeGrid();
        math.work(context.origin() == OptionalEmissionContext.Origin.MANAGED_CAPTURE ? 1 : context.resolution().spatialPitchSamples().size() + 1L);
        double pitch = context.minimumNativePitchMeters().orElse(Double.NaN), upper = pixelUpper(context, math);
        if (!(pitch > 0) || !(upper > 0)) return new Plan(domain, bounds(rectangle), Status.UNAVAILABLE, Reason.PITCH_UNAVAILABLE,
            0, 0, 0, 0, 0, 0, 0, empty, false, OptionalDouble.empty(), OptionalDouble.empty(), Optional.empty());
        double width = Math.nextUp(rectangle.x().upper() - rectangle.x().lower());
        double nativeLength = math.mul(point(width), point(Math.nextUp(Math.abs(chart.direction.x()) + Math.abs(chart.direction.y())))).upper();
        double length = math.mul(point(nativeLength), point(upper)).upper();
        double half = Math.nextDown(pitch * .5);
        if (!(half > 0)) return new Plan(domain, bounds(rectangle), Status.UNAVAILABLE, Reason.PITCH_UNAVAILABLE,
            0, 0, 0, 0, 0, 0, 0, empty, false, OptionalDouble.of(nativeLength), OptionalDouble.empty(), Optional.empty());
        double ratio = Math.nextUp(Math.nextUp(length / half) * 2);
        if (!Double.isFinite(ratio) || ratio >= Long.MAX_VALUE) input.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION, Long.MAX_VALUE);
        long segments = Math.max(1, (long) Math.ceil(ratio)), count = Math.addExact(segments, 1); math.work(count);
        long routeYes = 0, routeNo = 0, acquiredYes = 0, acquiredNo = 0, present = 0, invalid = 0, outside = 0, reads = 0;
        boolean spacing = true, physicalDomain = true, supportAvailable = true; double maximum = 0;
        RasterPoint oldA = null, oldB = null; Station first = null; Reason reason = Reason.NONE;
        for (long station = 0; station <= segments; station++) {
            math.work(1); double x = station == 0 ? rectangle.x().lower() : station == segments ? rectangle.x().upper()
                : rectangle.x().lower() + (rectangle.x().upper() - rectangle.x().lower()) * ((double) station / segments);
            var a = grid.toBaseRaster(chart.point(x, rectangle.y().lower())); var b = grid.toBaseRaster(chart.point(x, rectangle.y().upper()));
            var na = grid.toGrid(a); var nb = grid.toGrid(b);
            if (oldA != null) {
                double step = Math.nextUp(Math.max(math.upperL1(oldA, na), math.upperL1(oldB, nb)) * upper);
                maximum = Math.max(maximum, step); if (!(step <= half)) { spacing = false; reason = Reason.SPACING_UNRESOLVED; }
            }
            if (!inside(grid, na) || !inside(grid, nb)) { spacing = false; physicalDomain = false; if (reason == Reason.NONE) reason = Reason.OUTSIDE_PHYSICAL_DOMAIN; }
            oldA = na; oldB = nb;
            boolean route = context.routeSegmentAuthorized(a, b, input.workBudget(), memory), acquired = context.evidenceSegmentAuthorized(a, b, input.workBudget(), memory);
            if (route) routeYes++; else routeNo++; if (acquired) acquiredYes++; else acquiredNo++;
            Support support;
            try {
                var values = math.support(grid, na, nb); present += values.presentCells(); invalid += values.invalidCells(); outside += values.outsideCells(); reads += values.nodeReads();
                support = new Support(values.presentCells(), values.invalidCells(), values.outsideCells(), values.nodeReads(), true);
            } catch (CensusGeometry.Unavailable unavailable) { supportAvailable = false; support = empty; }
            var receipt = new Station(pair, domain, station, a, b, na, nb, route, acquired, support);
            if (first == null && (!spacing || !route || !acquired || !support.available() || support.invalidCells() > 0 || support.outsideCells() > 0)) first = receipt;
            sink.station(receipt);
        }
        boolean refused = routeNo > 0 || acquiredNo > 0 || invalid > 0 || outside > 0;
        if (reason == Reason.NONE) reason = routeNo > 0 ? Reason.ORIGINAL_ROUTE_REFUSED : acquiredNo > 0 ? Reason.ACQUIRED_SUPPORT_REFUSED : invalid > 0 || outside > 0 ? Reason.INVALID_CELL_SUPPORT : !supportAvailable ? Reason.ARITHMETIC_UNAVAILABLE : Reason.NONE;
        return new Plan(domain, bounds(rectangle), refused ? Status.CONSERVATIVE_PLAN_REFUSED : !spacing || !supportAvailable ? Status.UNAVAILABLE : Status.CENTER_DOMAIN_ONLY,
            reason, count, routeYes, routeNo, 0, acquiredYes, acquiredNo, 0, new Support(present, invalid, outside, reads, supportAvailable), spacing,
            OptionalDouble.of(nativeLength), physicalDomain ? OptionalDouble.of(maximum) : OptionalDouble.empty(), Optional.ofNullable(first));
    }
    private static boolean inside(NativeScalarGridView grid, RasterPoint point) {
        return point.x() >= 0 && point.y() >= 0 && point.x() <= grid.width() - 1 && point.y() <= grid.height() - 1;
    }
    private static double pixelUpper(OptionalEmissionContext context, CensusGeometry math) {
        if (context.nativeGrid() instanceof ManagedScalarGridView) return context.nativePitchMeters().orElse(Double.NaN);
        if (!(context.nativeGrid() instanceof ScalarFieldGridView grid)) return Double.NaN;
        var transform = context.rasterTransform();
        double divisor = transform.axisUnit() == RasterMetricTransform.AxisUnit.SOURCE_PIXEL ? transform.rasterPixelsPerSourcePixel() : 1;
        double x = math.div(math.add(point(Math.abs(transform.xAxisEastMetersPerSourcePixel())), point(Math.abs(transform.xAxisNorthMetersPerSourcePixel()))), divisor).upper();
        double y = math.div(math.add(point(Math.abs(transform.yAxisEastMetersPerSourcePixel())), point(Math.abs(transform.yAxisNorthMetersPerSourcePixel()))), divisor).upper();
        return math.mul(point(Math.max(x, y)), point(grid.reduction())).upper();
    }
}
