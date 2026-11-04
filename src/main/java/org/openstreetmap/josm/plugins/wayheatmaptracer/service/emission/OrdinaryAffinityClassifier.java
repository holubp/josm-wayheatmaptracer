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
        NO_CERTIFIED_CROSSCUT, UNPROVED_CONNECTOR, NATIVE_PITCH_UNAVAILABLE }
    public record Cell(int x, int y) { }
    public record CellEvidence(Cell cell, double maximumNodeIntensity) { }
    public record CrosscutPiece(RasterPoint fromGrid, RasterPoint toGrid,
        AcceptedModeThresholdReceipt lowerFrom, AcceptedModeThresholdReceipt lowerTo,
        AcceptedModeThresholdReceipt upperFrom, AcceptedModeThresholdReceipt upperTo,
        double threshold, List<CellEvidence> cells) {
        public CrosscutPiece { cells = List.copyOf(cells); }
    }
    public record Station(double parameter, MetricPoint anchor, double nativePitchMeters,
        List<Sample> samples, AcceptedThresholdExtraction extraction,
        List<AcceptedModeThresholdReceipt> modes) {
        public Station { samples = List.copyOf(samples); modes = List.copyOf(modes); }
    }
    public record DirectLink(int piece, int lowerModeIndex, int upperModeIndex,
        AcceptedModeThresholdReceipt from, AcceptedModeThresholdReceipt to,
        BilinearSegmentIntegral.Result scalar, double threshold) { }
    public record ChainPair(int lowerChain, int upperChain, List<CrosscutPiece> crosscut) {
        public ChainPair { crosscut = List.copyOf(crosscut); }
    }
    public record ConnectorPiece(int piece, AcceptedModeThresholdReceipt from,
        AcceptedModeThresholdReceipt to, double threshold, BilinearSegmentIntegral.Result scalar) { }
    public record Slab(int originalFrom, int originalTo, Reason reason, RasterPoint rasterNormal,
        MetricPoint metricNormal, List<Station> stations, List<DirectLink> links,
        List<ChainPair> pairs, long stationExtractions) {
        public Slab { stations = List.copyOf(stations); links = List.copyOf(links); pairs = List.copyOf(pairs); }
    }
    public record Receipt(Status status, Reason reason, NativeConnectorEvaluator.RowState from,
        NativeConnectorEvaluator.RowState to, MetricPoint actualFrom, MetricPoint actualTo,
        Slab slab, int fromChain, int toChain, List<CrosscutPiece> crossed,
        java.util.Optional<BilinearSegmentIntegral.Result> scalar, List<ConnectorPiece> continuation) {
        public Receipt { crossed = List.copyOf(crossed); scalar = Objects.requireNonNull(scalar);
            continuation = List.copyOf(continuation); }
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
    private record Edge(Identity from, Identity to, MetricPoint a, MetricPoint b) { }

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
        live();
        try {
            work(1);
            var key = new Edge(new Identity(from.evaluatorState()), new Identity(to.evaluatorState()), actualFrom, actualTo);
            var cached = edges.get(key); if (cached != null) return cached;
            var rowPair = new RowPair(from.originalRow(), to.originalRow());
            Slab slab = slabs.get(rowPair);
            if (slab == null) {
                reserve(192);
                slab = prepare(rowPair);
                slabs.put(rowPair, slab);
            }
            reserve(512);
            var result = classifyEdge(from, to, actualFrom, actualTo, slab);
            edges.put(key, result);
            return result;
        } catch (RuntimeException failure) {
            if (ExperimentalMeasuredModeInventory.terminal(failure)) input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    private Receipt classifyEdge(LegacyEmissionDomain.State from, LegacyEmissionDomain.State to,
            MetricPoint a, MetricPoint b, Slab slab) {
        var ownerA = modes.ownerFor(from.evaluatorState()); var ownerB = modes.ownerFor(to.evaluatorState());
        if (ownerA != null && (ownerA.originalRow() != from.originalRow() || ownerA.retainedRow() != from.evaluatorState().row())
                || ownerB != null && (ownerB.originalRow() != to.originalRow() || ownerB.retainedRow() != to.evaluatorState().row()))
            throw new IllegalArgumentException("Affinity endpoint owner belongs to another source row");
        if (ownerA == null || ownerB == null) return unknown(from, to, a, b, slab, Reason.SOURCE_OWNERSHIP);
        if (slab.reason() != Reason.SLAB_PREPARED)
            return unknown(from, to, a, b, slab, slab.reason());
        if (!authority(a, b)) return unknown(from, to, a, b, slab, Reason.SOURCE_AUTHORITY);
        var scalar = integral(a, b);
        if (scalar.status() != BilinearSegmentIntegral.Status.COMPLETE)
            return unknown(from, to, a, b, slab, Reason.SCALAR_SUPPORT);
        int ca = bind(ownerA, slab.stations().get(0));
        int cb = bind(ownerB, slab.stations().get(slab.stations().size() - 1));
        if (ca < 0 || cb < 0) return unknown(from, to, a, b, slab, Reason.SOURCE_OWNERSHIP);
        if (!endpointFits(from, a, slab.stations().get(0), ca, slab.metricNormal())
                || !endpointFits(to, b, slab.stations().get(slab.stations().size() - 1), cb, slab.metricNormal()))
            return unknown(from, to, a, b, slab, Reason.SOURCE_OWNERSHIP);
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
                    return unknown(from, to, a, b, slab, Reason.UNPROVED_CONNECTOR);
                if (previous != null) {
                    var priorMode = previous.modes().get(ca);
                    double threshold = Math.min(priorMode.shoulderThreshold(), mode.shoulderThreshold());
                    var measured = integral(interpolate(a, b, previous.parameter()), point);
                    if (measured.status() != BilinearSegmentIntegral.Status.COMPLETE
                            || measured.minimum().orElseThrow() < threshold)
                        return unknown(from, to, a, b, slab, Reason.UNPROVED_CONNECTOR);
                    continuation.add(new ConnectorPiece(continuation.size(), priorMode, mode, threshold, measured));
                }
                previous = station;
            }
            return new Receipt(Status.COMPATIBLE, Reason.SAME_MEASURED_CHAIN, from.evaluatorState(),
                to.evaluatorState(), a, b, slab, ca, cb, List.of(), java.util.Optional.of(scalar), continuation);
        } else {
            for (ChainPair pair : slab.pairs()) {
                work(1);
                if (pair.lowerChain() != Math.min(ca, cb) || pair.upperChain() != Math.max(ca, cb)) continue;
                var ga = input.context().nativeGrid().toGrid(input.context().rasterTransform().metricToPixelCenter(a));
                var gb = input.context().nativeGrid().toGrid(input.context().rasterTransform().metricToPixelCenter(b));
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
                    Reason.MEASURED_SEPARATING_CROSSCUT, from, to, a, b, slab, ca, cb, crossed, scalar);
            }
        }
        return unknown(from, to, a, b, slab, Reason.NO_CERTIFIED_CROSSCUT);
    }

    private Slab prepare(RowPair pair) {
        long before = stationExtractions;
        var slab = prepareRows(pair);
        return new Slab(slab.originalFrom(), slab.originalTo(), slab.reason(), slab.rasterNormal(),
            slab.metricNormal(), slab.stations(), slab.links(), slab.pairs(), stationExtractions - before);
    }

    private Slab prepareRows(RowPair pair) {
        var context = input.context();
        if (pair.to() != pair.from() + 1) return empty(pair, Reason.UNSUPPORTED_CHART);
        var left = domain.binding().observations().get(pair.from());
        var right = domain.binding().observations().get(pair.to());
        if (left.sourceClass() != NativeConnectorEvaluator.SourceClass.OBSERVED
                || right.sourceClass() != NativeConnectorEvaluator.SourceClass.OBSERVED)
            return empty(pair, Reason.SOURCE_OWNERSHIP);
        var fa = context.observations().get(left.retainedObservationIndex());
        var fb = context.observations().get(right.retainedObservationIndex());
        var normal = fa.normal();
        if (!ExperimentalMeasuredModeInventory.same(normal.x(), fb.normal().x())
                || !ExperimentalMeasuredModeInventory.same(normal.y(), fb.normal().y()))
            return empty(pair, Reason.UNSUPPORTED_CHART);
        var ar = fa.location().rasterPoint(); var br = fb.location().rasterPoint();
        double determinant = (br.x() - ar.x()) * normal.y() - (br.y() - ar.y()) * normal.x();
        if (!Double.isFinite(determinant) || determinant == 0) return empty(pair, Reason.UNSUPPORTED_CHART);
        var a = context.rasterTransform().pixelCenterToMetric(ar.x(), ar.y());
        var b = context.rasterTransform().pixelCenterToMetric(br.x(), br.y());
        var unitEnd = context.rasterTransform().pixelCenterToMetric(ar.x() + normal.x(), ar.y() + normal.y());
        double normalPitch = ExperimentalMeasuredModeInventory.normalPitch(context, left.retainedObservationIndex());
        var metricNormal = new MetricPoint((unitEnd.xMeters() - a.xMeters()) / normalPitch,
            (unitEnd.yMeters() - a.yMeters()) / normalPitch);
        double nativePitch = minimumNativePitch();
        if (!Double.isFinite(nativePitch) || nativePitch <= 0) return empty(pair, Reason.NATIVE_PITCH_UNAVAILABLE);
        long segmentCount = (long) Math.ceil(a.distanceTo(b) / (.5 * nativePitch));
        int segments = checkedCount(Math.max(1, segmentCount));
        int halfSamples = checkedCount((long) Math.ceil(context.searchHalfWidthMeters() / (.5 * nativePitch)));
        int sampleCount = checkedCount(Math.addExact(Math.multiplyExact((long) halfSamples, 2), 1));
        reserve(Math.addExact(1024L, Math.multiplyExact(segments + 1L, 256L + 96L * sampleCount)));
        var stations = new ArrayList<Station>(segments + 1);
        var links = new ArrayList<DirectLink>();
        for (int station = 0; station <= segments; station++) {
            double t = (double) station / segments;
            double stationChainage = left.profile().cumulativeGroundDistanceMeters()
                + t * (right.profile().cumulativeGroundDistanceMeters() - left.profile().cumulativeGroundDistanceMeters());
            double stationPitch = context.resolution().effectivePitchMetersAt(
                context.profileChainage().sourceOriginGroundMeters() + stationChainage);
            var anchor = interpolate(a, b, t);
            var lower = displaced(anchor, metricNormal, -context.searchHalfWidthMeters());
            var upper = displaced(anchor, metricNormal, context.searchHalfWidthMeters());
            if (!authority(lower, upper)) return empty(pair, Reason.SOURCE_AUTHORITY);
            var samples = new ArrayList<Sample>(sampleCount);
            for (int sample = -halfSamples; sample <= halfSamples; sample++) {
                work(1);
                double offset = sample * (context.searchHalfWidthMeters() / halfSamples);
                var point = displaced(anchor, metricNormal, offset);
                var value = integral(point, point);
                samples.add(new Sample(offset, value.mean().orElse(Double.NaN),
                    value.status() == BilinearSegmentIntegral.Status.COMPLETE));
            }
            var frozenSamples = List.copyOf(samples);
            var extraction = new LocalScalarProfileExtractor().extractDetailedWithAcceptedThresholds(frozenSamples,
                stationPitch, EvidenceModelParameters.Localization.defaults(), memory, CancellationProbe.NONE,
                count -> budget.charge(EmissionWorkBudget.Operation.MODE_EXTRACTION, count));
            stationExtractions++;
            var accepted = extraction.acceptedReceipts();
            if (accepted.isEmpty() || accepted.size() != extraction.result().rawPeaks().size()
                    || !extraction.result().suppressedModes().isEmpty()
                    || !extraction.result().baseline().censoredModes().isEmpty())
                return empty(pair, Reason.UNRESOLVED_MODES);
            for (var receipt : accepted) if (!complete(extraction, receipt))
                return empty(pair, Reason.UNRESOLVED_MODES);
            if (!stations.isEmpty()) {
                var previous = stations.get(stations.size() - 1);
                if (previous.modes().size() != accepted.size()) return empty(pair, Reason.UNRESOLVED_LINKS);
                reserve(128L * accepted.size());
                for (int i = 0; i < accepted.size(); i++) {
                    int forward = 0, backward = 0;
                    BilinearSegmentIntegral.Result selected = null;
                    for (int j = 0; j < accepted.size(); j++) {
                        work(1);
                        var forwardLink = link(previous, previous.modes().get(i), anchor, accepted.get(j), stationPitch, metricNormal);
                        if (forwardLink != null) { forward++; if (i == j) selected = forwardLink; }
                        if (i != j && link(previous, previous.modes().get(j), anchor, accepted.get(i), stationPitch, metricNormal) != null)
                            backward++;
                    }
                    if (forward != 1 || backward != 0 || selected == null)
                        return empty(pair, Reason.UNRESOLVED_LINKS);
                    links.add(new DirectLink(station - 1, i, i, previous.modes().get(i), accepted.get(i), selected,
                        Math.min(previous.modes().get(i).shoulderThreshold(), accepted.get(i).shoulderThreshold())));
                }
            }
            stations.add(new Station(t, anchor, stationPitch, frozenSamples, extraction, accepted));
        }
        var pairs = new ArrayList<ChainPair>();
        int count = stations.get(0).modes().size();
        reserve(128L * count * count);
        for (int lower = 0; lower < count; lower++) for (int upper = lower + 1; upper < count; upper++) {
            work(1);
            var crosscut = crosscut(stations, lower, upper, metricNormal);
            if (crosscut != null) pairs.add(new ChainPair(lower, upper, crosscut));
        }
        return new Slab(pair.from(), pair.to(), Reason.SLAB_PREPARED, normal, metricNormal,
            stations, links, pairs, stations.size());
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
            var gridPoint = input.context().nativeGrid().toGrid(input.context().rasterTransform().metricToPixelCenter(midpoint));
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
                        var metric = input.context().rasterTransform().pixelCenterToMetric(base.x(), base.y());
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

    private BilinearSegmentIntegral.Result link(Station previous, AcceptedModeThresholdReceipt from,
            MetricPoint anchor, AcceptedModeThresholdReceipt to, double pitch, MetricPoint normal) {
        work(1);
        if (Math.abs(from.mode().centerMeters() - to.mode().centerMeters())
                > 1.5 * Math.min(previous.nativePitchMeters(), pitch)) return null;
        var a = displaced(previous.anchor(), normal, from.mode().centerMeters());
        var b = displaced(anchor, normal, to.mode().centerMeters());
        if (!authority(a, b)) return null;
        var scalar = integral(a, b);
        return scalar.status() == BilinearSegmentIntegral.Status.COMPLETE
            && scalar.minimum().orElseThrow() >= Math.min(from.shoulderThreshold(), to.shoulderThreshold()) ? scalar : null;
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
        var grid = input.context().nativeGrid(); var transform = input.context().rasterTransform();
        var ga = grid.toGrid(transform.metricToPixelCenter(a)); var gb = grid.toGrid(transform.metricToPixelCenter(b));
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
    private Slab empty(RowPair pair, Reason reason) {
        return new Slab(pair.from(), pair.to(), reason, new RasterPoint(0, 0), new MetricPoint(0, 0),
            List.of(), List.of(), List.of(), 0);
    }
    private Receipt unknown(LegacyEmissionDomain.State from, LegacyEmissionDomain.State to,
            MetricPoint a, MetricPoint b, Slab slab, Reason reason) {
        return receipt(Status.UNKNOWN, reason, from, to, a, b, slab, -1, -1, List.of(), null);
    }
    private Receipt receipt(Status status, Reason reason, LegacyEmissionDomain.State from,
            LegacyEmissionDomain.State to, MetricPoint a, MetricPoint b, Slab slab, int ca, int cb,
            List<CrosscutPiece> crossed, BilinearSegmentIntegral.Result scalar) {
        return new Receipt(status, reason, from.evaluatorState(), to.evaluatorState(), a, b, slab, ca, cb,
            crossed, java.util.Optional.ofNullable(scalar), List.of());
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
