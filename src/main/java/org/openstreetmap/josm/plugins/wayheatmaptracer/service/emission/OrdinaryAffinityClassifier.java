package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** Conservative source-resolution affinity in an exact, frozen affine source chart. */
public final class OrdinaryAffinityClassifier {
    public enum Status { COMPATIBLE, INCOMPATIBLE, UNKNOWN }
    public enum Reason { SLAB_PREPARED, SAME_MEASURED_CHAIN, MEASURED_SEPARATING_CROSSCUT, UNSUPPORTED_CHART,
        SOURCE_OWNERSHIP, SOURCE_AUTHORITY, SCALAR_SUPPORT, UNRESOLVED_MODES, UNRESOLVED_LINKS,
        NO_CERTIFIED_CROSSCUT, UNPROVED_CONNECTOR, NATIVE_PITCH_UNAVAILABLE, FINAL_POINT_OWNERSHIP }
    public record Cell(int x, int y) { }
    public record CellEvidence(Cell cell, double maximumNodeIntensity) { }
    public record CrosscutPiece(RasterPoint fromGrid, RasterPoint toGrid,
        AcceptedModeThresholdReceipt lowerFrom, AcceptedModeThresholdReceipt lowerTo,
        AcceptedModeThresholdReceipt upperFrom, AcceptedModeThresholdReceipt upperTo,
        double threshold, List<CellEvidence> cells) {
        public CrosscutPiece { cells = List.copyOf(cells); }
    }
    public record StationPlan(int index, double parameter, MetricPoint anchor, double nativePitchMeters,
        MetricPoint lower, MetricPoint upper, int sampleCount) { }
    public enum QueryStatus { COMPLETE, PARTIAL_UNAVAILABLE }
    public record Station(double parameter, MetricPoint anchor, double nativePitchMeters,
        List<Sample> samples, AcceptedThresholdExtraction extraction,
        List<AcceptedModeThresholdReceipt> modes, List<BilinearSegmentIntegral.Result> sampleQueries,
        QueryStatus queryStatus) {
        public Station { samples = List.copyOf(samples); modes = List.copyOf(modes);
            sampleQueries = List.copyOf(sampleQueries); }
    }
    public enum LinkStatus { NOT_QUERIED, ADMITTED, REJECTED, UNAVAILABLE }
    public enum LinkReason { NONE, CONTINUATION_BOUND, SOURCE_AUTHORITY, SCALAR_SUPPORT, BELOW_THRESHOLD,
        MODE_COUNT_MISMATCH, MODE_INCOMPLETE }
    public record DirectLink(int piece, int fromModeIndex, int toModeIndex,
        AcceptedModeThresholdReceipt from, AcceptedModeThresholdReceipt to,
        java.util.Optional<BilinearSegmentIntegral.Result> scalar, double threshold,
        LinkStatus status, LinkReason reason) {
        public DirectLink {
            Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(scalar);
            Objects.requireNonNull(status); Objects.requireNonNull(reason);
            if (piece < 0 || fromModeIndex < 0 || toModeIndex < 0 || !Double.isFinite(threshold)
                    || threshold < 0 || threshold > 1
                    || status == LinkStatus.ADMITTED && (reason != LinkReason.NONE || scalar.isEmpty()
                        || scalar.orElseThrow().status() != BilinearSegmentIntegral.Status.COMPLETE
                        || scalar.orElseThrow().minimum().orElseThrow() < threshold)
                    || status == LinkStatus.UNAVAILABLE && (reason != LinkReason.SCALAR_SUPPORT || scalar.isEmpty()
                        || scalar.orElseThrow().status() != BilinearSegmentIntegral.Status.UNAVAILABLE)
                    || status == LinkStatus.NOT_QUERIED && (scalar.isPresent()
                        || reason != LinkReason.MODE_COUNT_MISMATCH && reason != LinkReason.MODE_INCOMPLETE)
                    || status == LinkStatus.REJECTED && reason == LinkReason.BELOW_THRESHOLD && (scalar.isEmpty()
                        || scalar.orElseThrow().status() != BilinearSegmentIntegral.Status.COMPLETE
                        || !(scalar.orElseThrow().minimum().orElseThrow() < threshold))
                    || status == LinkStatus.REJECTED && reason != LinkReason.BELOW_THRESHOLD
                        && (scalar.isPresent() || reason != LinkReason.CONTINUATION_BOUND && reason != LinkReason.SOURCE_AUTHORITY))
                throw new IllegalArgumentException("Directed link receipt contradicts its query status");
        }
    }
    public record ChainPair(int lowerChain, int upperChain, List<CrosscutPiece> crosscut) {
        public ChainPair { crosscut = List.copyOf(crosscut); }
    }
    public record ConnectorPiece(int piece, AcceptedModeThresholdReceipt from,
        AcceptedModeThresholdReceipt to, double threshold, BilinearSegmentIntegral.Result scalar) { }
    public enum PointOwnerKind { RAW_STATE_ALIAS, MEASURED_FINAL_POINT }
    /** Actual point evidence; final points borrow no native state position or invented mode. */
    public record PointOwnership(PointOwnerKind kind, int originalRow, int retainedRow,
        MetricPoint position, double rasterOffset, AcceptedModeThresholdReceipt sourceOwner,
        AcceptedModeThresholdReceipt stationOwner, int chain,
        java.util.Optional<BilinearSegmentIntegral.Result> pointScalar) { }
    public enum PreparationPhase { CHART, STATION_AUTHORITY, MODES, MODE_COUNT, LINKS }
    public record PreparationFailure(PreparationPhase phase, int stationIndex, int fromModeIndex,
        int toModeIndex, Reason reason) { }
    public record Slab(int originalFrom, int originalTo, Reason reason, RasterPoint rasterNormal,
        MetricPoint metricNormal, List<Station> stations, List<DirectLink> links,
        List<ChainPair> pairs, long stationExtractions, List<StationPlan> plan,
        java.util.Optional<PreparationFailure> failure) {
        public Slab { stations = List.copyOf(stations); links = List.copyOf(links); pairs = List.copyOf(pairs);
            plan = List.copyOf(plan); failure = Objects.requireNonNull(failure); }
        /** Absent planned station data is unqueried, not unavailable scalar data or measured zero. */
        public java.util.Optional<Station> availableStation(int index) {
            if (index < 0 || index >= plan.size()) throw new IndexOutOfBoundsException("Station is not in the frozen plan");
            return index >= stations.size() ? java.util.Optional.empty() : java.util.Optional.of(stations.get(index));
        }
    }
    public record Receipt(Status status, Reason reason, NativeConnectorEvaluator.RowState from,
        NativeConnectorEvaluator.RowState to, MetricPoint actualFrom, MetricPoint actualTo,
        Slab slab, int fromChain, int toChain, List<CrosscutPiece> crossed,
        java.util.Optional<BilinearSegmentIntegral.Result> scalar, List<ConnectorPiece> continuation,
        java.util.Optional<PointOwnership> fromOwnership, java.util.Optional<PointOwnership> toOwnership) {
        public Receipt { crossed = List.copyOf(crossed); scalar = Objects.requireNonNull(scalar);
            continuation = List.copyOf(continuation); fromOwnership = Objects.requireNonNull(fromOwnership);
            toOwnership = Objects.requireNonNull(toOwnership); }
        /** A hard graph admission decision, never a finite objective debit. */
        public boolean ordinaryEligible() { return status != Status.INCOMPATIBLE; }
    }
    private record RowPair(int from, int to) { }
    private static final class Identity {
        private final Object value;
        Identity(Object value) { this.value = value; }
        @Override public int hashCode() { return System.identityHashCode(value); }
        @Override public boolean equals(Object other) { return other instanceof Identity key && key.value == value; }
    }
    private record Edge(Identity from, Identity to, MetricPoint a, MetricPoint b,
        boolean finalPoints, double offsetA, double offsetB) { }

    private final SampledEmissionInput input;
    private final LegacyEmissionDomain domain;
    private final ExperimentalMeasuredModeInventory modes;
    private final EmissionWorkBudget budget;
    private final AttemptMemoryLedger.Owner memory;
    private final Map<RowPair, Slab> slabs;
    private final Map<Edge, Receipt> edges;
    private long stationExtractions;

    public OrdinaryAffinityClassifier(SampledEmissionInput input, LegacyEmissionDomain domain,
            AttemptMemoryLedger.Owner parent) {
        this.input = Objects.requireNonNull(input); this.domain = Objects.requireNonNull(domain);
        this.modes = Objects.requireNonNull(domain.measuredModes());
        if (!modes.belongsTo(input)) throw new IllegalArgumentException("Foreign affinity source inventory");
        this.budget = input.workBudget(); this.memory = parent.child("ordinary-affinity-v1");
        reserve(512); slabs = new HashMap<>(); edges = new HashMap<>();
    }

    public long stationExtractions() { live(); return stationExtractions; }
    public int preparedRowPairs() { live(); return slabs.size(); }

    /** Exact ordered endpoint classification; station preparation is shared by immutable row pair. */
    public Receipt classify(LegacyEmissionDomain.State from, LegacyEmissionDomain.State to,
            MetricPoint actualFrom, MetricPoint actualTo) {
        input.requireOpenAndUnchanged();
        return classifyInValidatedExecution(from, to, actualFrom, actualTo);
    }

    /** Numerical engine boundary already validated its frozen input; avoid rescanning profiles per DP edge. */
    Receipt classifyInValidatedExecution(LegacyEmissionDomain.State from, LegacyEmissionDomain.State to,
            MetricPoint actualFrom, MetricPoint actualTo) {
        return classify(from, to, actualFrom, actualTo, false, from.offsetRasterPixels(), to.offsetRasterPixels());
    }

    /** Explicit final-position measurement, sharing only the immutable row-pair source slab. */
    public Receipt classifyFinalGeometry(LegacyEmissionDomain.State from, LegacyEmissionDomain.State to,
            MetricPoint actualFrom, double offsetFrom, MetricPoint actualTo, double offsetTo) {
        input.requireOpenAndUnchanged();
        return classifyFinalInValidatedExecution(from, to, actualFrom, offsetFrom, actualTo, offsetTo);
    }

    Receipt classifyFinalInValidatedExecution(LegacyEmissionDomain.State from, LegacyEmissionDomain.State to,
            MetricPoint actualFrom, double offsetFrom, MetricPoint actualTo, double offsetTo) {
        return classify(from, to, actualFrom, actualTo, true, offsetFrom, offsetTo);
    }

    private Receipt classify(LegacyEmissionDomain.State from, LegacyEmissionDomain.State to,
            MetricPoint actualFrom, MetricPoint actualTo, boolean finalPoints, double offsetA, double offsetB) {
        live();
        try {
            work(1);
            var key = new Edge(new Identity(from.evaluatorState()), new Identity(to.evaluatorState()),
                actualFrom, actualTo, finalPoints, offsetA, offsetB);
            var cached = edges.get(key); if (cached != null) return cached;
            var rowPair = new RowPair(from.originalRow(), to.originalRow());
            Slab slab = slabs.get(rowPair);
            if (slab == null) {
                reserve(192);
                slab = prepare(rowPair);
                slabs.put(rowPair, slab);
            }
            reserve(1024);
            var result = classifyEdge(from, to, actualFrom, actualTo, slab, finalPoints, offsetA, offsetB);
            edges.put(key, result);
            return result;
        } catch (RuntimeException failure) {
            if (ExperimentalMeasuredModeInventory.terminal(failure)) input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    private Receipt classifyEdge(LegacyEmissionDomain.State from, LegacyEmissionDomain.State to,
            MetricPoint a, MetricPoint b, Slab slab, boolean finalPoints, double offsetA, double offsetB) {
        var ownerA = modes.ownerFor(from.evaluatorState()); var ownerB = modes.ownerFor(to.evaluatorState());
        if (ownerA != null && (ownerA.originalRow() != from.originalRow() || ownerA.retainedRow() != from.evaluatorState().row())
                || ownerB != null && (ownerB.originalRow() != to.originalRow() || ownerB.retainedRow() != to.evaluatorState().row()))
            throw new IllegalArgumentException("Affinity endpoint owner belongs to another source row");
        if (slab.reason() == Reason.UNSUPPORTED_CHART)
            return unknown(from, to, a, b, slab, slab.reason());
        if (!finalPoints && (ownerA == null || ownerB == null)) return unknown(from, to, a, b, slab, Reason.SOURCE_OWNERSHIP);
        if (slab.reason() != Reason.SLAB_PREPARED)
            return unknown(from, to, a, b, slab, slab.reason());
        if (!authority(a, b)) return unknown(from, to, a, b, slab, Reason.SOURCE_AUTHORITY);
        var scalar = integral(a, b);
        if (scalar.status() != BilinearSegmentIntegral.Status.COMPLETE)
            return unknown(from, to, a, b, slab, Reason.SCALAR_SUPPORT, scalar);
        PointOwnership ownershipA = finalPoints
            ? finalPoint(from, a, offsetA, slab.stations().get(0), slab.metricNormal())
            : rawPoint(from, a, ownerA, slab.stations().get(0), slab.metricNormal());
        PointOwnership ownershipB = finalPoints
            ? finalPoint(to, b, offsetB, slab.stations().get(slab.stations().size() - 1), slab.metricNormal())
            : rawPoint(to, b, ownerB, slab.stations().get(slab.stations().size() - 1), slab.metricNormal());
        if (ownershipA == null || ownershipB == null) return unknown(from, to, a, b, slab,
            finalPoints ? Reason.FINAL_POINT_OWNERSHIP : Reason.SOURCE_OWNERSHIP, scalar);
        int ca = ownershipA.chain(), cb = ownershipB.chain();
        if (ca == cb) {
            reserve(192L * slab.stations().size());
            var continuation = new ArrayList<ConnectorPiece>();
            Station previous = null;
            for (Station station : slab.stations()) {
                work(1);
                var mode = station.modes().get(ca);
                var point = interpolate(a, b, station.parameter());
                double offset = dot(point, station.anchor(), slab.metricNormal());
                // Each actual connector sample stays in that complete measured core/uncertainty support.
                if (mode.mode().distanceToCenterSet(offset) > mode.mode().localizationSigmaMeters())
                    return unknown(from, to, a, b, slab, Reason.UNPROVED_CONNECTOR, scalar);
                if (previous != null) {
                    var priorMode = previous.modes().get(ca);
                    double threshold = Math.min(priorMode.shoulderThreshold(), mode.shoulderThreshold());
                    var measured = integral(interpolate(a, b, previous.parameter()), point);
                    if (measured.status() != BilinearSegmentIntegral.Status.COMPLETE
                            || measured.minimum().orElseThrow() < threshold)
                        return unknown(from, to, a, b, slab, Reason.UNPROVED_CONNECTOR, scalar);
                    continuation.add(new ConnectorPiece(continuation.size(), priorMode, mode, threshold, measured));
                }
                previous = station;
            }
            return new Receipt(Status.COMPATIBLE, Reason.SAME_MEASURED_CHAIN, from.evaluatorState(),
                to.evaluatorState(), a, b, slab, ca, cb, List.of(), java.util.Optional.of(scalar), continuation,
                java.util.Optional.of(ownershipA), java.util.Optional.of(ownershipB));
        } else {
            for (ChainPair pair : slab.pairs()) {
                work(1);
                if (pair.lowerChain() != Math.min(ca, cb) || pair.upperChain() != Math.max(ca, cb)) continue;
                var ga = input.context().nativeGrid().toGrid(input.context().rasterAt(a));
                var gb = input.context().nativeGrid().toGrid(input.context().rasterAt(b));
                var crossed = new ArrayList<CrosscutPiece>();
                reserve(64L + 16L * pair.crosscut().size());
                for (var piece : pair.crosscut()) {
                    work(1);
                    boolean interior = false;
                    for (var cell : piece.cells()) {
                        work(1);
                        if (crossesCellInterior(ga, gb, cell.cell())) interior = true;
                    }
                    if (interior) crossed.add(piece);
                }
                if (!crossed.isEmpty()) return receipt(Status.INCOMPATIBLE,
                    Reason.MEASURED_SEPARATING_CROSSCUT, from, to, a, b, slab, ca, cb, crossed, scalar,
                    ownershipA, ownershipB);
            }
        }
        return unknown(from, to, a, b, slab, Reason.NO_CERTIFIED_CROSSCUT, scalar);
    }

    /** Available prefix plus an exact immutable plan: no failed query is erased or invented. */
    private final class Preparation {
        private final RowPair pair;
        private final long beforeExtractions;
        private RasterPoint normal;
        private MetricPoint metricNormal;
        private final List<StationPlan> plan;
        private final List<Station> stations;
        private final List<DirectLink> links;
        private final List<ChainPair> pairs;
        Preparation(RowPair pair) {
            reserve(512);
            this.pair = pair; beforeExtractions = stationExtractions;
            normal = new RasterPoint(0, 0); metricNormal = new MetricPoint(0, 0);
            plan = new ArrayList<>(); stations = new ArrayList<>();
            links = new ArrayList<>(); pairs = new ArrayList<>();
        }
        Slab fail(Reason reason, PreparationPhase phase, int station, int from, int to) {
            return finish(reason, java.util.Optional.of(new PreparationFailure(phase, station, from, to, reason)));
        }
        Slab finish(Reason reason, java.util.Optional<PreparationFailure> failure) {
            long entries = (long) plan.size() + stations.size() + links.size() + pairs.size();
            work(entries); reserve(256 + 16 * entries);
            return new Slab(pair.from(), pair.to(), reason, normal, metricNormal, stations, links, pairs,
                stationExtractions - beforeExtractions, plan, failure);
        }
    }

    private Slab prepare(RowPair pair) {
        var evidence = new Preparation(pair);
        var context = input.context();
        if (!ExperimentalMeasuredModeInventory.supportsAffineChart(context))
            return evidence.fail(Reason.UNSUPPORTED_CHART, PreparationPhase.CHART, -1, -1, -1);
        if (pair.to() != pair.from() + 1)
            return evidence.fail(Reason.UNSUPPORTED_CHART, PreparationPhase.CHART, -1, -1, -1);
        var left = domain.binding().observations().get(pair.from());
        var right = domain.binding().observations().get(pair.to());
        if (left.sourceClass() != NativeConnectorEvaluator.SourceClass.OBSERVED
                || right.sourceClass() != NativeConnectorEvaluator.SourceClass.OBSERVED)
            return evidence.fail(Reason.SOURCE_OWNERSHIP, PreparationPhase.CHART, -1, -1, -1);
        var fa = context.observations().get(left.retainedObservationIndex());
        var fb = context.observations().get(right.retainedObservationIndex());
        var normal = fa.normal(); evidence.normal = normal;
        if (!ExperimentalMeasuredModeInventory.same(normal.x(), fb.normal().x())
                || !ExperimentalMeasuredModeInventory.same(normal.y(), fb.normal().y()))
            return evidence.fail(Reason.UNSUPPORTED_CHART, PreparationPhase.CHART, -1, -1, -1);
        var ar = fa.location().rasterPoint(); var br = fb.location().rasterPoint();
        double determinant = (br.x() - ar.x()) * normal.y() - (br.y() - ar.y()) * normal.x();
        if (!Double.isFinite(determinant) || determinant == 0)
            return evidence.fail(Reason.UNSUPPORTED_CHART, PreparationPhase.CHART, -1, -1, -1);
        var a = context.metricAt(ar);
        var b = context.metricAt(br);
        var unitEnd = context.metricAt(new RasterPoint(ar.x() + normal.x(), ar.y() + normal.y()));
        double normalPitch = ExperimentalMeasuredModeInventory.normalPitch(context, left.retainedObservationIndex());
        var metricNormal = new MetricPoint((unitEnd.xMeters() - a.xMeters()) / normalPitch,
            (unitEnd.yMeters() - a.yMeters()) / normalPitch);
        evidence.metricNormal = metricNormal;
        double nativePitch = minimumNativePitch();
        if (!Double.isFinite(nativePitch) || nativePitch <= 0)
            return evidence.fail(Reason.NATIVE_PITCH_UNAVAILABLE, PreparationPhase.CHART, -1, -1, -1);
        long segmentCount = (long) Math.ceil(a.distanceTo(b) / (.5 * nativePitch));
        int segments = checkedCount(Math.max(1, segmentCount));
        int halfSamples = checkedCount((long) Math.ceil(context.searchHalfWidthMeters() / (.5 * nativePitch)));
        int sampleCount = checkedCount(Math.addExact(Math.multiplyExact((long) halfSamples, 2), 1));
        reserve(Math.addExact(1024L, Math.multiplyExact(segments + 1L, 512L + 256L * sampleCount)));
        for (int station = 0; station <= segments; station++) {
            work(1);
            double t = (double) station / segments;
            double sourceChainage = left.profile().cumulativeGroundDistanceMeters()
                + t * (right.profile().cumulativeGroundDistanceMeters() - left.profile().cumulativeGroundDistanceMeters());
            double pitch = context.nativePitchAtSourceChainage(sourceChainage);
            var anchor = interpolate(a, b, t);
            evidence.plan.add(new StationPlan(station, t, anchor, pitch,
                displaced(anchor, metricNormal, -context.searchHalfWidthMeters()),
                displaced(anchor, metricNormal, context.searchHalfWidthMeters()), sampleCount));
        }
        for (var planned : evidence.plan) {
            int station = planned.index();
            if (!authority(planned.lower(), planned.upper()))
                return evidence.fail(Reason.SOURCE_AUTHORITY, PreparationPhase.STATION_AUTHORITY, station, -1, -1);
            var samples = new ArrayList<Sample>(sampleCount);
            var queries = new ArrayList<BilinearSegmentIntegral.Result>(sampleCount);
            boolean unavailable = false;
            for (int sample = -halfSamples; sample <= halfSamples; sample++) {
                work(1);
                double offset = sample * (context.searchHalfWidthMeters() / halfSamples);
                var point = displaced(planned.anchor(), metricNormal, offset);
                var value = integral(point, point);
                queries.add(value);
                boolean complete = value.status() == BilinearSegmentIntegral.Status.COMPLETE;
                unavailable |= !complete;
                samples.add(new Sample(offset, value.mean().orElse(Double.NaN), complete));
            }
            var frozenSamples = List.copyOf(samples);
            var extraction = new LocalScalarProfileExtractor().extractDetailedWithAcceptedThresholds(frozenSamples,
                planned.nativePitchMeters(), EvidenceModelParameters.Localization.defaults(), memory, CancellationProbe.NONE,
                count -> budget.charge(EmissionWorkBudget.Operation.MODE_EXTRACTION, count));
            stationExtractions++;
            var accepted = extraction.acceptedReceipts();
            var current = new Station(planned.parameter(), planned.anchor(), planned.nativePitchMeters(),
                frozenSamples, extraction, accepted, queries, unavailable ? QueryStatus.PARTIAL_UNAVAILABLE : QueryStatus.COMPLETE);
            var previous = evidence.stations.isEmpty() ? null : evidence.stations.get(evidence.stations.size() - 1);
            evidence.stations.add(current); // Includes the actual failing station and its exact sample queries.
            if (accepted.isEmpty() || accepted.size() != extraction.result().rawPeaks().size()
                    || !extraction.result().suppressedModes().isEmpty()
                    || !extraction.result().baseline().censoredModes().isEmpty()) {
                unqueriedLinks(evidence, previous, current, station, LinkReason.MODE_INCOMPLETE);
                return evidence.fail(Reason.UNRESOLVED_MODES, PreparationPhase.MODES, station, -1, -1);
            }
            for (int index = 0; index < accepted.size(); index++) if (!complete(extraction, accepted.get(index))) {
                unqueriedLinks(evidence, previous, current, station, LinkReason.MODE_INCOMPLETE);
                return evidence.fail(Reason.UNRESOLVED_MODES, PreparationPhase.MODES, station, -1, index);
            }
            if (previous != null) {
                if (previous.modes().size() != accepted.size()) {
                    unqueriedLinks(evidence, previous, current, station, LinkReason.MODE_COUNT_MISMATCH);
                    return evidence.fail(Reason.UNRESOLVED_LINKS, PreparationPhase.MODE_COUNT, station, -1, -1);
                }
                int count = accepted.size(), base = evidence.links.size();
                reserve(256L * count * count);
                // Each directed pair is tested once. Incoming/outgoing uniqueness reads this same matrix.
                for (int from = 0; from < count; from++) for (int to = 0; to < count; to++) {
                    work(1);
                    evidence.links.add(link(station - 1, from, to, previous, current, metricNormal));
                }
                for (int mode = 0; mode < count; mode++) {
                    int outgoing = 0, incoming = 0;
                    for (int other = 0; other < count; other++) {
                        work(2);
                        if (evidence.links.get(base + mode * count + other).status() == LinkStatus.ADMITTED) outgoing++;
                        if (evidence.links.get(base + other * count + mode).status() == LinkStatus.ADMITTED) incoming++;
                    }
                    if (outgoing != 1 || incoming != 1
                            || evidence.links.get(base + mode * count + mode).status() != LinkStatus.ADMITTED)
                        return evidence.fail(Reason.UNRESOLVED_LINKS, PreparationPhase.LINKS, station, mode, mode);
                }
            }
        }
        int count = evidence.stations.get(0).modes().size();
        reserve(128L * count * count);
        for (int lower = 0; lower < count; lower++) for (int upper = lower + 1; upper < count; upper++) {
            work(1);
            var crosscut = crosscut(evidence.stations, lower, upper, metricNormal);
            if (crosscut != null) evidence.pairs.add(new ChainPair(lower, upper, crosscut));
        }
        return evidence.finish(Reason.SLAB_PREPARED, java.util.Optional.empty());
    }

    private void unqueriedLinks(Preparation evidence, Station previous, Station current, int station, LinkReason reason) {
        if (previous == null) return;
        reserve(256L * previous.modes().size() * current.modes().size());
        for (int from = 0; from < previous.modes().size(); from++) for (int to = 0; to < current.modes().size(); to++) {
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1); work(1);
            var a = previous.modes().get(from); var b = current.modes().get(to);
            evidence.links.add(new DirectLink(station - 1, from, to, a, b, java.util.Optional.empty(),
                Math.min(a.shoulderThreshold(), b.shoulderThreshold()), LinkStatus.NOT_QUERIED, reason));
        }
    }

    private List<CrosscutPiece> crosscut(List<Station> stations, int lower, int upper, MetricPoint normal) {
        reserve(128L * stations.size());
        var pieces = new ArrayList<CrosscutPiece>();
        RasterPoint previous = null;
        for (int index = 0; index < stations.size(); index++) {
            work(1);
            var station = stations.get(index);
            var lo = station.modes().get(lower); var hi = station.modes().get(upper);
            double left = Math.max(lo.mode().coreMaximumMeters(), rawCoreBoundary(station, lo, true))
                + lo.mode().localizationSigmaMeters();
            double right = Math.min(hi.mode().coreMinimumMeters(), rawCoreBoundary(station, hi, false))
                - hi.mode().localizationSigmaMeters();
            if (!(left < right)) return null;
            var midpoint = displaced(station.anchor(), normal, left + (right - left) * .5);
            var gridPoint = input.context().nativeGrid().toGrid(input.context().rasterAt(midpoint));
            if (previous != null) {
                var prior = stations.get(index - 1);
                var plo = prior.modes().get(lower); var phi = prior.modes().get(upper);
                double threshold = Math.min(Math.min(plo.shoulderThreshold(), phi.shoulderThreshold()),
                    Math.min(lo.shoulderThreshold(), hi.shoulderThreshold()));
                var cells = closedCellSupercover(previous, gridPoint, budget, memory);
                reserve(64L + 64L * cells.size());
                var evidence = new ArrayList<CellEvidence>(cells.size());
                for (Cell cell : cells) {
                    work(1);
                    var grid = input.context().nativeGrid();
                    var measured = darkCellEvidence(grid, cell, threshold, budget);
                    if (measured == null) return null;
                    for (int y = cell.y(); y <= cell.y() + 1; y++) for (int x = cell.x(); x <= cell.x() + 1; x++) {
                        work(1);
                        var base = grid.toBaseRaster(new RasterPoint(x, y));
                        var metric = input.context().metricAt(base);
                        if (!authority(metric, metric) || !insideTransverseWindow(metric, stations, normal)) return null;
                    }
                    evidence.add(measured);
                }
                pieces.add(new CrosscutPiece(previous, gridPoint, plo, lo, phi, hi, threshold, evidence));
            }
            previous = gridPoint;
        }
        return List.copyOf(pieces);
    }

    private DirectLink link(int piece, int fromIndex, int toIndex, Station previous, Station current, MetricPoint normal) {
        work(1); budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var from = previous.modes().get(fromIndex); var to = current.modes().get(toIndex);
        double threshold = Math.min(from.shoulderThreshold(), to.shoulderThreshold());
        if (Math.abs(from.mode().centerMeters() - to.mode().centerMeters())
                > 1.5 * Math.min(previous.nativePitchMeters(), current.nativePitchMeters()))
            return new DirectLink(piece, fromIndex, toIndex, from, to, java.util.Optional.empty(), threshold,
                LinkStatus.REJECTED, LinkReason.CONTINUATION_BOUND);
        var a = displaced(previous.anchor(), normal, from.mode().centerMeters());
        var b = displaced(current.anchor(), normal, to.mode().centerMeters());
        if (!authority(a, b)) return new DirectLink(piece, fromIndex, toIndex, from, to,
            java.util.Optional.empty(), threshold, LinkStatus.REJECTED, LinkReason.SOURCE_AUTHORITY);
        var scalar = integral(a, b);
        if (scalar.status() != BilinearSegmentIntegral.Status.COMPLETE)
            return new DirectLink(piece, fromIndex, toIndex, from, to, java.util.Optional.of(scalar), threshold,
                LinkStatus.UNAVAILABLE, LinkReason.SCALAR_SUPPORT);
        boolean admitted = scalar.minimum().orElseThrow() >= threshold;
        return new DirectLink(piece, fromIndex, toIndex, from, to, java.util.Optional.of(scalar), threshold,
            admitted ? LinkStatus.ADMITTED : LinkStatus.REJECTED,
            admitted ? LinkReason.NONE : LinkReason.BELOW_THRESHOLD);
    }

    private boolean complete(AcceptedThresholdExtraction extraction, AcceptedModeThresholdReceipt receipt) {
        if (receipt.rawCore().isEmpty()) return false;
        boolean core = false, flank = false;
        for (var candidate : extraction.result().rawCoreReceipts()) {
            work(1);
            if (candidate.mode() == receipt.mode() && candidate.leftAdjacentObservedValid()
                    && candidate.leftAdjacentBelowCore() && candidate.rightAdjacentObservedValid()
                    && candidate.rightAdjacentBelowCore()) core = true;
        }
        for (var candidate : extraction.result().flankReceipts()) {
            work(1); if (candidate.mode() == receipt.mode() && candidate.hasTwoSidedValidOutwardContinuation()) flank = true;
        }
        return core && flank;
    }

    private double rawCoreBoundary(Station station, AcceptedModeThresholdReceipt receipt, boolean right) {
        for (var core : station.extraction().result().rawCoreReceipts()) {
            work(1);
            if (core.mode() == receipt.mode()) return station.samples().get(right
                ? core.lastHighCoreSampleIndex() : core.firstHighCoreSampleIndex()).offsetMeters();
        }
        throw new IllegalStateException("Accepted affinity mode lost its raw-core identity");
    }

    private boolean insideTransverseWindow(MetricPoint point, List<Station> stations, MetricPoint normal) {
        var a = stations.get(0).anchor(); var b = stations.get(stations.size() - 1).anchor();
        double dx = b.xMeters() - a.xMeters(), dy = b.yMeters() - a.yMeters();
        double determinant = dx * normal.yMeters() - dy * normal.xMeters();
        double transverse = (dx * (point.yMeters() - a.yMeters())
            - dy * (point.xMeters() - a.xMeters())) / determinant;
        return Double.isFinite(transverse) && Math.abs(transverse) <= input.context().searchHalfWidthMeters();
    }

    static CellEvidence darkCellEvidence(BilinearSegmentIntegral.ReadOnlyScalarGrid grid, Cell cell,
            double threshold, EmissionWorkBudget budget) {
        budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1);
        if (!(threshold > 0) || cell.x() < 0 || cell.y() < 0 || cell.x() + 1 >= grid.width()
                || cell.y() + 1 >= grid.height() || !grid.supportsCell(cell.x(), cell.y())) return null;
        double maximum = 0;
        for (int y = cell.y(); y <= cell.y() + 1; y++) for (int x = cell.x(); x <= cell.x() + 1; x++) {
            budget.charge(EmissionWorkBudget.Operation.NODE_READ, 1);
            double value = grid.nodeValue(x, y);
            if (!Double.isFinite(value) || !(value < threshold)) return null;
            maximum = Math.max(maximum, value);
        }
        return new CellEvidence(cell, maximum);
    }

    private int bind(ExperimentalMeasuredModeInventory.Owner owner, Station station) {
        int match = -1;
        for (int i = 0; i < station.modes().size(); i++) {
            work(1);
            var receipt = station.modes().get(i);
            if (receipt.mode().distanceToCenterSet(owner.receipt().mode().centerMeters()) == 0) {
                if (match >= 0) return -1;
                match = i;
            }
        }
        return match;
    }

    private PointOwnership rawPoint(LegacyEmissionDomain.State state, MetricPoint actual,
            ExperimentalMeasuredModeInventory.Owner owner, Station station, MetricPoint normal) {
        if (owner == null || !actual.equals(domain.metricPoint(state, input.context()))) return null;
        int chain = bind(owner, station);
        if (chain < 0 || !endpointFits(state, actual, station, chain, normal)) return null;
        return new PointOwnership(PointOwnerKind.RAW_STATE_ALIAS, state.originalRow(), state.evaluatorState().row(),
            actual, state.offsetRasterPixels(), owner.receipt(), station.modes().get(chain), chain, java.util.Optional.empty());
    }

    private PointOwnership finalPoint(LegacyEmissionDomain.State state, MetricPoint actual, double rasterOffset,
            Station station, MetricPoint normal) {
        if (!Double.isFinite(rasterOffset)) return null;
        var observation = domain.binding().observations().get(state.originalRow());
        if (observation.sourceClass() != NativeConnectorEvaluator.SourceClass.OBSERVED
                || observation.retainedObservationIndex() != state.evaluatorState().row()) return null;
        var profile = observation.measurementProfile();
        var raster = new RasterPoint(profile.anchorScreen().x + profile.normalScreen().x * rasterOffset,
            profile.anchorScreen().y + profile.normalScreen().y * rasterOffset);
        var expected = input.context().metricAt(raster);
        if (!actual.equals(expected)) return null;
        double pitch = ExperimentalMeasuredModeInventory.normalPitch(input.context(), state.evaluatorState().row());
        double physicalOffset = rasterOffset * pitch;
        if (Math.abs(physicalOffset) > input.context().searchHalfWidthMeters()) return null;
        var scalar = integral(actual, actual);
        if (scalar.status() != BilinearSegmentIntegral.Status.COMPLETE) return null;
        PointOwnership found = null;
        for (var owner : modes.ownersAt(state.originalRow())) {
            work(1);
            if (owner.receipt().mode().distanceToCenterSet(physicalOffset) > owner.receipt().mode().localizationSigmaMeters()
                    || scalar.mean().orElseThrow() < owner.receipt().shoulderThreshold()) continue;
            int chain = bind(owner, station);
            if (chain < 0 || station.modes().get(chain).mode().distanceToCenterSet(dot(actual, station.anchor(), normal))
                    > station.modes().get(chain).mode().localizationSigmaMeters()
                    || scalar.mean().orElseThrow() < station.modes().get(chain).shoulderThreshold()) continue;
            if (found != null) return null;
            found = new PointOwnership(PointOwnerKind.MEASURED_FINAL_POINT, state.originalRow(),
                state.evaluatorState().row(), actual, rasterOffset, owner.receipt(), station.modes().get(chain),
                chain, java.util.Optional.of(scalar));
        }
        return found;
    }

    private boolean endpointFits(LegacyEmissionDomain.State state, MetricPoint actual, Station boundary,
            int chain, MetricPoint normal) {
        if (!actual.equals(domain.metricPoint(state, input.context()))) {
            double dx = actual.xMeters() - boundary.anchor().xMeters();
            double dy = actual.yMeters() - boundary.anchor().yMeters();
            if (dx * normal.yMeters() - dy * normal.xMeters() != 0) return false;
        }
        return boundary.modes().get(chain).mode().distanceToCenterSet(dot(actual, boundary.anchor(), normal))
            <= boundary.modes().get(chain).mode().localizationSigmaMeters();
    }

    private double minimumNativePitch() {
        if (input.context().resolution().nativePitchMeters().isEmpty()) return Double.NaN;
        double minimum = input.context().resolution().nativePitchMeters().orElseThrow();
        for (var sample : input.context().resolution().spatialPitchSamples()) {
            work(1); if (sample.nativePitchMeters().isEmpty()) return Double.NaN;
            minimum = Math.min(minimum, sample.nativePitchMeters().orElseThrow());
        }
        return minimum;
    }
    private boolean authority(MetricPoint a, MetricPoint b) {
        return input.context().decisionRegion().containsSegment(a, b, CancellationProbe.NONE,
                count -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, count), memory)
            && input.context().evidenceRegion().containsSegment(a, b, CancellationProbe.NONE,
                count -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, count), memory);
    }
    private BilinearSegmentIntegral.Result integral(MetricPoint a, MetricPoint b) {
        var grid = input.context().nativeGrid();
        var ga = grid.toGrid(input.context().rasterAt(a)); var gb = grid.toGrid(input.context().rasterAt(b));
        long cells = (long) Math.ceil(Math.abs(gb.x() - ga.x()) + Math.abs(gb.y() - ga.y())) + 16;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        try (var scratch = memory.reserve(Math.addExact(4096, Math.multiplyExact(cells, 2048)))) {
            return BilinearSegmentIntegral.evaluate(grid, ga, gb, CancellationProbe.NONE,
                () -> budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1));
        }
    }

    /** Complete closed-cell contacts, including both sides of grid edges and all vertex neighbors. */
    public static List<Cell> closedCellSupercover(RasterPoint a, RasterPoint b,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner memory) {
        long minX = (long) Math.ceil(Math.min(a.x(), b.x())) - 1;
        long maxX = (long) Math.floor(Math.max(a.x(), b.x()));
        long minY = (long) Math.ceil(Math.min(a.y(), b.y())) - 1;
        long maxY = (long) Math.floor(Math.max(a.y(), b.y()));
        long capacity = Math.multiplyExact(maxX - minX + 1, maxY - minY + 1);
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        memory.reserve(Math.addExact(64, Math.multiplyExact(capacity, 64)));
        var cells = new ArrayList<Cell>();
        for (long y = minY; y <= maxY; y++) for (long x = minX; x <= maxX; x++) {
            budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1);
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Crosscut cell coordinate is outside the native grid");
            if (contact(a, b, (int) x, (int) y, false)) cells.add(new Cell((int) x, (int) y));
        }
        return List.copyOf(cells);
    }
    static boolean crossesCellInterior(RasterPoint a, RasterPoint b, Cell cell) {
        if (a.x() == b.x() && a.y() == b.y()) return false;
        return contact(a, b, cell.x(), cell.y(), true);
    }
    private static boolean contact(RasterPoint a, RasterPoint b, int x, int y, boolean interior) {
        double lo = 0, hi = 1;
        for (int axis = 0; axis < 2; axis++) {
            double origin = axis == 0 ? a.x() : a.y();
            double delta = axis == 0 ? b.x() - a.x() : b.y() - a.y();
            int lower = axis == 0 ? x : y;
            if (delta == 0) {
                if (interior ? !(origin > lower && origin < lower + 1.0)
                        : origin < lower || origin > lower + 1.0) return false;
            } else {
                double t0 = (lower - origin) / delta;
                double t1 = (lower + 1.0 - origin) / delta;
                lo = Math.max(lo, Math.min(t0, t1)); hi = Math.min(hi, Math.max(t0, t1));
            }
        }
        return interior ? lo < hi : lo <= hi;
    }
    private int checkedCount(long count) {
        if (count <= 0 || count >= Integer.MAX_VALUE) budget.charge(EmissionWorkBudget.Operation.MODE_EXTRACTION, Long.MAX_VALUE);
        return Math.toIntExact(count);
    }
    private Receipt unknown(LegacyEmissionDomain.State from, LegacyEmissionDomain.State to,
            MetricPoint a, MetricPoint b, Slab slab, Reason reason) {
        return receipt(Status.UNKNOWN, reason, from, to, a, b, slab, -1, -1, List.of(), null);
    }
    private Receipt unknown(LegacyEmissionDomain.State from, LegacyEmissionDomain.State to,
            MetricPoint a, MetricPoint b, Slab slab, Reason reason, BilinearSegmentIntegral.Result scalar) {
        return receipt(Status.UNKNOWN, reason, from, to, a, b, slab, -1, -1, List.of(), scalar);
    }
    private Receipt receipt(Status status, Reason reason, LegacyEmissionDomain.State from,
            LegacyEmissionDomain.State to, MetricPoint a, MetricPoint b, Slab slab, int ca, int cb,
            List<CrosscutPiece> crossed, BilinearSegmentIntegral.Result scalar) {
        return receipt(status, reason, from, to, a, b, slab, ca, cb, crossed, scalar, null, null);
    }
    private Receipt receipt(Status status, Reason reason, LegacyEmissionDomain.State from,
            LegacyEmissionDomain.State to, MetricPoint a, MetricPoint b, Slab slab, int ca, int cb,
            List<CrosscutPiece> crossed, BilinearSegmentIntegral.Result scalar, PointOwnership ownerA, PointOwnership ownerB) {
        return new Receipt(status, reason, from.evaluatorState(), to.evaluatorState(), a, b, slab, ca, cb,
            crossed, java.util.Optional.ofNullable(scalar), List.of(), java.util.Optional.ofNullable(ownerA),
            java.util.Optional.ofNullable(ownerB));
    }
    private void live() { input.requireRetainedOpen(); if (memory.isClosed()) throw new IllegalStateException("Affinity classifier is closed"); }
    private void work(long count) { budget.charge(EmissionWorkBudget.Operation.PREPARATION, count); }
    private void reserve(long bytes) { budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1); memory.reserve(bytes); }
    private static MetricPoint interpolate(MetricPoint a, MetricPoint b, double t) {
        return new MetricPoint(a.xMeters() + t * (b.xMeters() - a.xMeters()), a.yMeters() + t * (b.yMeters() - a.yMeters()));
    }
    private static MetricPoint displaced(MetricPoint anchor, MetricPoint normal, double offset) {
        return new MetricPoint(anchor.xMeters() + normal.xMeters() * offset, anchor.yMeters() + normal.yMeters() * offset);
    }
    private static double dot(MetricPoint point, MetricPoint origin, MetricPoint normal) {
        return (point.xMeters() - origin.xMeters()) * normal.xMeters() + (point.yMeters() - origin.yMeters()) * normal.yMeters();
    }
}
