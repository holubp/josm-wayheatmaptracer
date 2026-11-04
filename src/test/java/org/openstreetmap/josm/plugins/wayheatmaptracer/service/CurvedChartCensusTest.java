package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

public class CurvedChartCensusTest {
    public static NativeScalarGridView croppedGrid() {
        var values = new float[16]; java.util.Arrays.fill(values, .5f);
        var valid = new boolean[16]; java.util.Arrays.fill(valid, true);
        return ScalarFieldGridView.fromIntensity(new ScalarIntensityField(10, 20, 4, 4, 3, values, valid));
    }
    private static CurvedChartCensus.Metadata metadata() { return new CurvedChartCensus.Metadata("synthetic", "synthetic", List.of(4)); }
    @Test void censusVisitsEveryActualOriginalPairWithoutConstructingMeasuredInventory() {
        var fixture = LegacyAffinityContractProbeTest.fixture(.30, false, false);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        String field = fixture.evidence().fields().keySet().iterator().next();
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), field,
                 fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), field,
                fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                java.util.stream.IntStream.range(0, 11).boxed().toList(), 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) {
                var count = new AtomicInteger();
                var pairWork = new java.util.concurrent.atomic.AtomicLong();
                long before = budget.snapshot().workUsed();
                try (var result = CurvedChartCensus.scan(input,
                    new CurvedChartCensus.Metadata("synthetic", "synthetic", List.of(4)),
                    pair -> { assertEquals(count.getAndIncrement(), pair.index()); pairWork.addAndGet(pair.operationUnits()); })) {
                assertEquals(10, result.totalPairs());
                assertEquals(10, result.visitedPairs());
                assertEquals(10, count.get());
                assertTrue(result.metadataIdentityMatched());
                assertEquals(budget.snapshot().workUsed() - before, result.operationUnits());
                assertTrue(result.operationUnits() > pairWork.get(), "Shared validation/metadata work must remain present in whole-census accounting");
                }
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void actualProducerPositionsAndPositiveCenterPlanAreUsefulButFutureFamiliesStayUnavailable() {
        withInput(LegacyAffinityContractProbeTest.fixture(.60, false, false), budget(), (input, owner, ledger) -> {
            long modes = input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION);
            try (var result = CurvedChartCensus.scan(input, metadata(), pair -> {
                assertSame(input.context(), pair.source().contextOwner()); assertSame(input.context().nativeGrid(), pair.source().gridOwner());
                assertEquals("synthetic", pair.source().profileArtifactHash());
                var chart = pair.chart().orElseThrow(); assertTrue(chart.exactPositiveDeterminant().signum() > 0);
                var leftAnchor = input.context().nativeGrid().toGrid(input.legacyBinding().orElseThrow().observations().get(pair.index()).measurementProfile().samplingAnchor().rasterPoint());
                var rightAnchor = input.context().nativeGrid().toGrid(input.legacyBinding().orElseThrow().observations().get(pair.index() + 1).measurementProfile().samplingAnchor().rasterPoint());
                assertEquals(leftAnchor, chart.capturedNativeOrigin());
                assertEquals(new RasterPoint(rightAnchor.x() - leftAnchor.x(), rightAnchor.y() - leftAnchor.y()), chart.capturedRoundedChord());
                for (var row : List.of(pair.left(), pair.right())) {
                    var actual = input.legacyBinding().orElseThrow().observations().get(row.originalRow());
                    assertEquals(actual.producerPeaks().size(), row.producerPorts().size());
                    for (int i = 0; i < row.producerPorts().size(); i++) {
                        var port = row.producerPorts().get(i); var peak = actual.producerPeaks().get(i);
                        assertSame(peak, port.sourceOwner()); assertEquals(peak.originalPeakId(), port.originalPeakId());
                        var profile = actual.measurementProfile();
                        assertEquals(new RasterPoint(profile.anchorScreen().x + profile.normalScreen().x * peak.peak().offsetPx(),
                            profile.anchorScreen().y + profile.normalScreen().y * peak.peak().offsetPx()), port.actualRaster());
                        assertEquals(input.context().nativeGrid().toGrid(port.actualRaster()), port.actualNativeGrid());
                    }
                    assertEquals(CurvedChartCensus.EnvelopeKind.POSITIVE_RUN_ENVELOPE, row.positiveRuns().kind());
                    assertEquals(2, row.positiveRuns().runs().size());
                }
                assertEquals(List.of(CurvedChartCensus.Family.values()), pair.unavailableFamilies());
                assertEquals(CurvedChartCensus.Availability.UNAVAILABLE, pair.fullFutureDomain());
                assertEquals(CurvedChartCensus.Availability.UNAVAILABLE, pair.futureCenterPlacement());
                var valid = pair.validRunBounds().orElseThrow(); var positive = pair.positiveRunBounds().orElseThrow();
                assertTrue(valid.maximumY() - valid.minimumY() > positive.maximumY() - positive.minimumY());
                var plan = pair.centerPlan().orElseThrow();
                assertEquals(CurvedChartCensus.Domain.PRODUCER_PLUS_POSITIVE_RUN, plan.domain());
                assertEquals(CurvedChartCensus.Status.CENTER_DOMAIN_ONLY, plan.status());
                assertTrue(plan.routeAuthorized() > 0); assertEquals(0, plan.routeRefused()); assertEquals(0, plan.acquiredRefused());
                assertTrue(plan.support().presentCells() > 0); assertEquals(0, plan.support().invalidCells());
                assertTrue(plan.spacingCertified()); assertTrue(plan.actualSpacingUpperMeters().orElseThrow() <= .5);
                assertTrue(pair.operationUnits() > 0); assertTrue(pair.retainedBytes() > 0);
                if (pair.index() == 4) measured("complete", pair);
            })) { assertEquals(10, result.visitedPairs()); }
            assertEquals(modes, input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION));
        });
    }

    @Test void sourceAuthorityRefusalAndMissingAcquiredCellsRemainDistinct() {
        var base = LegacyAffinityContractProbeTest.fixture(.6, false, false); var old = base.evidence();
        var regions = new ArrayList<List<MetricPoint>>(); regions.addAll(MetricRegion.rectangle(0, 0, 43, 127).polygons());
        regions.addAll(MetricRegion.rectangle(49, 0, 127, 127).polygons());
        var narrow = new EvidenceSnapshot(old.snapshotId(), old.coordinateFrame(), old.transform(), old.resolution(),
            new MetricRegion(regions), old.evidenceRegion(), old.fields(), old.sourceIdentity());
        var refusals = new AtomicInteger();
        withInput(new LegacyAffinityContractProbeTest.Fixture(narrow, base.input(), base.chainage()), budget(), (input, owner, ledger) -> {
            try (var result = CurvedChartCensus.scan(input, metadata(), pair -> {
                var plan = pair.centerPlan().orElseThrow();
                if (plan.routeRefused() > 0) { refusals.incrementAndGet(); assertEquals(CurvedChartCensus.Status.CONSERVATIVE_PLAN_REFUSED, plan.status()); measured("route-gap", pair); }
                assertEquals(0, plan.acquiredRefused());
                assertEquals(0, plan.support().invalidCells()); assertTrue(plan.support().presentCells() > 0);
            })) { assertEquals(10, result.visitedPairs()); }
        });
        assertTrue(refusals.get() > 0, "An unauthorized gap between measured original rows must be queried and reported");
        var invalid = new AtomicInteger();
        withInput(LegacyAffinityContractProbeTest.fixture(.6, true, false), budget(), (input, owner, ledger) -> {
            try (var result = CurvedChartCensus.scan(input, metadata(), pair -> {
                pair.centerPlan().ifPresent(plan -> { if (plan.support().invalidCells() > 0) {
                    invalid.incrementAndGet(); assertEquals(0, plan.routeRefused()); assertEquals(CurvedChartCensus.Status.CONSERVATIVE_PLAN_REFUSED, plan.status()); measured("support-gap", pair);
                }});
            })) { assertEquals(10, result.visitedPairs()); }
        });
        assertTrue(invalid.get() > 0);
    }

    @Test void identityMismatchAndAbsentOriginalMappingAreUnavailableWithoutPairQueries() {
        withInput(LegacyAffinityContractProbeTest.fixture(.3, false, false), budget(), (input, owner, ledger) -> {
            try (var result = CurvedChartCensus.scan(input, new CurvedChartCensus.Metadata("expected", "different", List.of()), pair -> fail())) {
                assertEquals(CurvedChartCensus.Completion.IDENTITY_MISMATCH, result.completion());
                assertEquals(0, result.visitedPairs()); assertEquals(0, result.firstUnvisitedPair());
            }
        });
        try (var fixture = new NativeAEmissionSolverTest.Fixture(3);
             var result = CurvedChartCensus.scan(fixture.input, metadata(), pair -> fail())) {
            assertEquals(CurvedChartCensus.Completion.ORIGINAL_MAPPING_UNAVAILABLE, result.completion());
            assertEquals(-1, result.totalPairs());
        }
    }

    @Test void foldedOriginalNormalsDoNotFoldFixedChartAndActualSlantedPortsRemainInterior() {
        var base = LegacyAffinityContractProbeTest.fixture(.6, false, false); var frame = base.evidence().coordinateFrame(); var transform = base.evidence().transform();
        var points = List.of(new MetricPoint(40, 64), new MetricPoint(41, 64), new MetricPoint(42, 64.1));
        var locations = new ArrayList<DetachedProfileSamplingLocation>(); var distances = List.of(0.0, 1.0, 2.01);
        for (int i = 0; i < points.size(); i++) locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(points.get(i)), frame, transform, distances.get(i)));
        var folded = new LegacyAffinityContractProbeTest.Fixture(base.evidence(), new CorridorTraceInput(locations, 1), new ProfileChainage(distances, 1));
        withInput(folded, budget(), (input, owner, ledger) -> {
            var a = input.context().observations().get(0); var b = input.context().observations().get(1);
            double dx = b.location().rasterPoint().x() - a.location().rasterPoint().x(), dy = b.location().rasterPoint().y() - a.location().rasterPoint().y();
            double nx = b.normal().x() - a.normal().x(), ny = b.normal().y() - a.normal().y();
            double lo = (dx - 25 * nx) * a.normal().y() - (dy - 25 * ny) * a.normal().x();
            double hi = (dx + 25 * nx) * a.normal().y() - (dy + 25 * ny) * a.normal().x();
            assertTrue(lo * hi < 0, "The actual full-window original-normal chart folds");
            try (var result = CurvedChartCensus.scan(input, new CurvedChartCensus.Metadata("s", "s", List.of(0)), pair -> {
                assertEquals(CurvedChartCensus.Reason.NONE, pair.chartReason());
                assertTrue(pair.centerPlan().isPresent());
                if (pair.index() == 0) {
                    assertTrue(pair.savedOriginalWindowFold());
                    measured("folded-original", pair);
                    assertTrue(pair.right().producerPorts().stream().anyMatch(port -> port.chartBounds().isPresent()
                        && port.chartBounds().orElseThrow().minimumX() > 0 && port.chartBounds().orElseThrow().maximumX() < 1),
                        "The actual slanted upper port must retain its interior longitudinal position");
                }
            })) { assertEquals(2, result.visitedPairs()); }
        });
    }

    @Test void oneBandProducerDomainMustNotGainArtificialTransverseWidthFromRoundoff() {
        withInput(LegacyAffinityContractProbeTest.fixture(0, false, false), budget(), (input, owner, ledger) -> {
            try (var result = CurvedChartCensus.scan(input, metadata(), pair -> {
                if (pair.index() == 0) {
                    assertEquals(1, pair.left().producerPorts().size()); assertEquals(1, pair.right().producerPorts().size());
                    assertEquals(CurvedChartCensus.Status.UNAVAILABLE, pair.producerPlan().orElseThrow().status());
                    assertEquals(CurvedChartCensus.Reason.DEGENERATE_TRANSVERSE_DOMAIN, pair.producerPlan().orElseThrow().reason());
                }
            })) { assertEquals(10, result.visitedPairs()); }
        });
    }

    @Test void memoryWorkAndCancellationRemainStickyAndReportFirstUnvisitedPairWithNoLeakedScratch() {
        withInput(LegacyAffinityContractProbeTest.fixture(.3, false, false), budget(), (input, owner, ledger) -> {
            long before = ledger.currentBytes(); var stop = new AtomicInteger(-1);
            RuntimeException failure;
            try (var pressure = owner.reserve(AttemptMemoryLedger.MAX_BYTES - ledger.currentBytes() - 16_384)) {
                failure = assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> CurvedChartCensus.scan(input, metadata(), new CurvedChartCensus.Sink() {
                    public void pair(CurvedChartCensus.Pair pair) { fail(); }
                    public void stopped(int total, int first) { assertEquals(10, total); stop.set(first); }
                }));
            }
            assertEquals(before, ledger.currentBytes()); assertEquals(0, stop.get());
            assertSame(failure, assertThrows(AttemptMemoryLedger.ResourceLimitException.class, input::requireOpenAndUnchanged));
        });
        for (boolean cancel : List.of(false, true)) {
            var cancelled = new AtomicBoolean(); var work = new EmissionWorkBudget(TraceBudgets.defaults(), cancelled::get);
            withInput(LegacyAffinityContractProbeTest.fixture(.3, false, false), work, (input, owner, ledger) -> {
                long before = ledger.currentBytes(); var stop = new AtomicInteger(-1); var visited = new AtomicInteger();
                var failure = assertThrows(RuntimeException.class, () -> CurvedChartCensus.scan(input, metadata(), new CurvedChartCensus.Sink() {
                    public void pair(CurvedChartCensus.Pair pair) { visited.incrementAndGet(); if (cancel) cancelled.set(true);
                        else work.charge(EmissionWorkBudget.Operation.PREPARATION, work.snapshot().workLimit() - work.snapshot().workUsed() - 1); }
                    public void stopped(int total, int first) { stop.set(first); }
                }));
                assertEquals(1, visited.get()); assertEquals(1, stop.get()); assertEquals(before, ledger.currentBytes());
                assertTrue(cancel ? failure instanceof CancellationException : failure instanceof EmissionWorkBudget.LimitExceededException);
                assertSame(failure, assertThrows(RuntimeException.class, input::requireOpenAndUnchanged));
            });
        }
    }
    @Test void externalSavedLabelsAreFrozenBeforeAnyBorrowedCallbackCanMutateThem() {
        withInput(LegacyAffinityContractProbeTest.fixture(.3, false, false), budget(), (input, owner, ledger) -> {
            var labels = new ArrayList<>(List.of(4)); var observed = new ArrayList<Integer>();
            try (var result = CurvedChartCensus.scan(input, new CurvedChartCensus.Metadata("s", "s", labels), pair -> {
                if (pair.index() == 0) { labels.clear(); labels.add(0); }
                if (pair.savedOriginalWindowFold()) observed.add(pair.index());
            })) { assertEquals(List.of(4), observed); assertEquals(1, result.savedFoldPairCount()); assertEquals(4, result.savedFoldPairId(0)); assertEquals("s", result.profileArtifactHash()); }
        });
    }
    @Test void successfulSummaryRetainsOnlyItsOwnedHeaderAndReleasesAfterLogicalInputClose() {
        withInput(LegacyAffinityContractProbeTest.fixture(.3, false, false), budget(), (input, owner, ledger) -> {
            long before = ledger.currentBytes();
            var result = CurvedChartCensus.scan(input, metadata(), pair -> { });
            long held = ledger.currentBytes(); assertTrue(held > before);
            input.close(); assertEquals(held, ledger.currentBytes(), "Result lease keeps the captured source alive");
            assertEquals(10, result.visitedPairs()); result.close(); assertTrue(ledger.currentBytes() < held);
            result.close(); assertThrows(IllegalStateException.class, result::visitedPairs);
        });
    }
    private static EmissionWorkBudget budget() { return new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE); }
    private static void measured(String scene, CurvedChartCensus.Pair pair) {
        var plan = pair.centerPlan().orElseThrow();
        System.out.println("P0 synthetic=" + scene + " pair=" + pair.index() + " producerPorts="
            + (pair.left().producerPorts().size() + pair.right().producerPorts().size()) + " positiveIntervals="
            + (pair.left().positiveRuns().runs().size() + pair.right().positiveRuns().runs().size())
            + " stations=" + plan.plannedStations() + " routeYes=" + plan.routeAuthorized() + " routeNo=" + plan.routeRefused()
            + " acquiredNo=" + plan.acquiredRefused() + " present=" + plan.support().presentCells() + " invalid=" + plan.support().invalidCells()
            + " outside=" + plan.support().outsideCells() + " spacingUpperMeters=" + plan.actualSpacingUpperMeters()
            + " status=" + plan.status() + " work=" + pair.operationUnits() + " retained=" + pair.retainedBytes() + " peak=" + pair.peakBytes());
    }
    @FunctionalInterface private interface Check { void accept(SampledEmissionInput input, AttemptMemoryLedger.Owner owner, AttemptMemoryLedger ledger); }
    private static void withInput(LegacyAffinityContractProbeTest.Fixture fixture, EmissionWorkBudget budget, Check check) {
        var ledger = AttemptMemoryLedger.production(); String field = fixture.evidence().fields().keySet().iterator().next();
        try (var root = ledger.rootOwner(); var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(
                fixture.evidence(), field, fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), field, fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var mapping = java.util.stream.IntStream.range(0, fixture.input().profileLocations().size()).boxed().toList();
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, mapping, 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) { check.accept(input, root, ledger); }
        }
        assertEquals(0, ledger.currentBytes());
    }
}
