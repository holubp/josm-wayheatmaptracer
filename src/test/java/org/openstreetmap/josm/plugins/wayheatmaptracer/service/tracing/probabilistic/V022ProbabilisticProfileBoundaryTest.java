package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.StrictScalarSampler;

/** Configured physical windows must not lose an authorized remainder or skip an unauthorized prefix. */
class V022ProbabilisticProfileBoundaryTest {
    @Test void boundaryMembershipIndexesAreSharedWithinTheirSamplingOwnerAndReleased() {
        var evidence = scene(WIDTH, NATIVE, .08, false, MetricRegion.rectangle(-10, -WIDTH, 74, WIDTH));
        var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            var work = new ProbabilisticProfileFactory.BoundaryWork(CancellationProbe.NONE,
                    ProbabilisticProfileFactory.BoundaryWork.MAXIMUM_UNITS, owner);
            assertEquals(WIDTH, ProbabilisticProfileFactory.authorizedBoundary(new MetricPoint(32, 0),
                    new MetricPoint(0, 1), 1, WIDTH, .5 * NATIVE, evidence, work, owner), 0);
            long retained = owner.currentBytes();
            assertTrue(retained > 0, "Persistent indexes are charged until sampling ownership closes");
            assertEquals(WIDTH, ProbabilisticProfileFactory.authorizedBoundary(new MetricPoint(40, 0),
                    new MetricPoint(0, 1), 1, WIDTH, .5 * NATIVE, evidence, work, owner), 0);
            assertEquals(retained, owner.currentBytes(), "The next profile reuses both indexes");
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void cancellationDuringMembershipPreparationReleasesPartialIndexStorage() {
        var polygons = new java.util.ArrayList<List<MetricPoint>>();
        for (int index = 0; index < 450; index++) polygons.add(MetricRegion.rectangle(
                -10 + index * (84.0 / 450), -WIDTH,
                -10 + (index + 1) * (84.0 / 450), WIDTH).polygons().get(0));
        var evidence = scene(WIDTH, NATIVE, .08, false, new MetricRegion(polygons));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            CancellationProbe cancellation = () -> calls.incrementAndGet() >= 32;
            var work = new ProbabilisticProfileFactory.BoundaryWork(cancellation,
                    ProbabilisticProfileFactory.BoundaryWork.MAXIMUM_UNITS, owner);
            assertThrows(java.util.concurrent.CancellationException.class, () -> ProbabilisticProfileFactory.authorizedBoundary(
                    new MetricPoint(32, 0), new MetricPoint(0, 1), 1, WIDTH, .5 * NATIVE, evidence, work, owner));
            assertTrue(ledger.peakBytes() > 0);
            assertEquals(0, ledger.currentBytes());
        }
    }
    @Test void denseCorridorUsesActualEventsWithinTheOldFullCapacityCost() {
        var polygons = new java.util.ArrayList<List<MetricPoint>>();
        for (int index = 0; index < 450; index++) polygons.add(MetricRegion.rectangle(
                -10 + index * (84.0 / 450), -WIDTH,
                -10 + (index + 1) * (84.0 / 450), WIDTH).polygons().get(0));
        var decision = new MetricRegion(polygons);
        var evidence = scene(WIDTH, NATIVE, .08, false, decision);
        double step = .5 * NATIVE;
        long requested = (long) StrictMath.ceil(WIDTH / step);
        long[] oldUnits = {requested};
        double last = 0;
        for (long visit = 1; visit <= requested; visit++) {
            double bounded = visit == requested ? WIDTH : Math.min(WIDTH, visit * step);
            var from = new MetricPoint(32.1, last); var to = new MetricPoint(32.1, bounded);
            oldUnits[0]++;
            assertTrue(decision.containsSegment(from, to, CancellationProbe.NONE, units -> oldUnits[0] += units, null));
            assertTrue(evidence.evidenceRegion().containsSegment(from, to, CancellationProbe.NONE,
                    units -> oldUnits[0] += units, null));
            last = bounded;
        }
        assertTrue(oldUnits[0] > 1 && oldUnits[0] < ProbabilisticProfileFactory.BoundaryWork.MAXIMUM_UNITS);
        assertEquals(last, ProbabilisticProfileFactory.authorizedBoundary(new MetricPoint(32.1, 0),
                new MetricPoint(0, 1), 1, WIDTH, step, evidence,
                new ProbabilisticProfileFactory.BoundaryWork(CancellationProbe.NONE, oldUnits[0] - 1), null), 0,
                "Exact observed events must not spend the old all-edge event-capacity charge");
    }
    private static final double WIDTH = 7.01;
    private static final double NATIVE = 1.7546997679008605;
    private static final List<MetricPoint> SOURCE = List.of(new MetricPoint(0, 0), new MetricPoint(64, 0));

    @ParameterizedTest
    @CsvSource({"7.01,1", "10,1.3", "14,1.56"})
    void symmetricFullWindowRetainsCenterWithoutExceedingSamplePitch(double width, double nativePitch) {
        var evidence = scene(width, nativePitch, 0.8, false,
                MetricRegion.rectangle(-10, -width, 74, width));
        for (boolean directB : List.of(false, true)) {
            var profile = profiles(evidence, width, SOURCE, directB).get(2);
            var samples = profile.samples();
            assertEquals(-width, samples.get(0).offsetMeters(), 0.0);
            assertEquals(width, samples.get(samples.size() - 1).offsetMeters(), 0.0);
            assertTrue(samples.stream().anyMatch(sample -> sample.offsetMeters() == 0.0),
                    "a residual boundary interval must not replace the center with a half-cell offset");
            for (int i = 1; i < samples.size(); i++) {
                double gap = samples.get(i).offsetMeters() - samples.get(i - 1).offsetMeters();
                assertTrue(gap > 0.0 && gap <= 0.5 * nativePitch + 1e-12);
                assertEquals(-samples.get(i).offsetMeters(),
                        samples.get(samples.size() - 1 - i).offsetMeters(), 1e-12);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"7.01,1.7546997679008605", "10,2.25", "14,1.56", "7,2"})
    void completeConfiguredWindowIncludesItsExactEndpoints(double width, double nativePitch) {
        var evidence = scene(width, nativePitch, 0.08, false, MetricRegion.rectangle(-10, -width, 74, width));
        for (boolean directB : List.of(false, true)) {
            var profile = profiles(evidence, width, SOURCE, directB).get(2);
            assertEquals(-width, profile.minimumOffsetMeters(), 1e-12);
            assertEquals(width, profile.maximumOffsetMeters(), 1e-12);
            assertEquals(-width, profile.samples().get(0).offsetMeters(), 1e-12);
            assertEquals(width, profile.samples().get(profile.samples().size() - 1).offsetMeters(), 1e-12);
        }
    }

    @ParameterizedTest
    @CsvSource({"0.08,false", "0.8,false", "0.08,true", "0.8,true"})
    void completeNearEdgeBranchKeepsItsLegalBelowThresholdBracket(double amplitude, boolean reversed) {
        var evidence = scene(WIDTH, NATIVE, amplitude, false, MetricRegion.rectangle(-10, -WIDTH, 74, WIDTH));
        var source = reversed ? List.of(SOURCE.get(1), SOURCE.get(0)) : SOURCE;
        var profile = profiles(evidence, WIDTH, source, false).get(2);
        int intervals = (int) Math.ceil(2.0 * WIDTH / (0.5 * NATIVE));
        var observed = java.util.stream.IntStream.rangeClosed(0, intervals).mapToObj(index -> {
            double offset = -WIDTH + 2.0 * WIDTH * index / intervals;
            var point = new MetricPoint(profile.anchor().xMeters() + offset * profile.normalUnit().xMeters(),
                    profile.anchor().yMeters() + offset * profile.normalUnit().yMeters());
            assertTrue(evidence.routeSegmentAuthorized(profile.anchor(), point));
            return new LocalScalarProfileExtractor.Sample(offset, StrictScalarSampler.sample(
                    evidence.fields().get("scalar"), evidence.transform(), evidence.decisionRegion(), point)
                    .orElseThrow(), true);
        }).toList();
        var fullWindow = new LocalScalarProfileExtractor().extract(observed, NATIVE,
                EvidenceModelParameters.defaults().localization());
        assertTrue(!fullWindow.modes().isEmpty() && fullWindow.censoredModes().isEmpty(),
                "independently measured brackets and the unchanged extractor establish fixture completeness");
        assertTrue(profile.modes().stream().anyMatch(mode -> Math.abs(mode.coreCenterMeters()
                - (reversed ? -5.25 : 5.25)) < NATIVE),
                "complete weak/strong branch has an observed legal bracket before the configured edge");
        assertTrue(profile.censoredModes().isEmpty(), "sampling phase cannot manufacture a clipped component");
    }

    @ParameterizedTest
    @MethodSource("noncontiguousRegions")
    void authorizedEndpointCannotSkipTheFirstUnauthorizedNormalPrefix(MetricRegion decision) {
        var evidence = scene(WIDTH, NATIVE, 0.08, false, decision);
        var profile = profiles(evidence, WIDTH, SOURCE, false).get(2);
        var endpoint = new MetricPoint(profile.anchor().xMeters(), profile.maximumOffsetMeters());
        assertTrue(profile.maximumOffsetMeters() <= 4.8 + 1e-10);
        assertTrue(evidence.routeSegmentAuthorized(profile.anchor(), endpoint),
                "both normal endpoints may be admitted while a concave notch lies between them");
    }

    @Test
    void residualSubstepCannotJumpAThinUnauthorizedStrip() {
        var decision = strips(6.4, 6.55);
        var evidence = scene(WIDTH, NATIVE, 0.08, false, decision);
        var profile = profiles(evidence, WIDTH, SOURCE, false).get(2);
        assertTrue(profile.maximumOffsetMeters() <= 6.4);
        assertTrue(evidence.routeSegmentAuthorized(profile.anchor(),
                new MetricPoint(profile.anchor().xMeters(), profile.maximumOffsetMeters())));
    }

    @Test
    void invalidLegalEdgeDoesNotSupplyABelowThresholdBracket() {
        var evidence = scene(WIDTH, NATIVE, 0.08, true, MetricRegion.rectangle(-10, -WIDTH, 74, WIDTH));
        var profile = profiles(evidence, WIDTH, SOURCE, false).get(2);
        assertTrue(profile.modes().isEmpty(), "missing edge samples must never grant a complete center");
        assertTrue(!profile.censoredModes().isEmpty());
    }

    @Test
    void bothFirstPrefixesUnavailableCannotRecreateUnauthorizedSamples() {
        var decision = new MetricRegion(List.of(
                MetricRegion.rectangle(-10, -0.2, 74, 0.2).polygons().get(0),
                MetricRegion.rectangle(-10, 1.0, 74, WIDTH).polygons().get(0),
                MetricRegion.rectangle(-10, -WIDTH, 74, -1.0).polygons().get(0)));
        var evidence = scene(WIDTH, NATIVE, 0.08, false, decision);
        for (boolean directB : List.of(false, true)) {
            assertThrows(IllegalArgumentException.class,
                    () -> profiles(evidence, WIDTH, SOURCE, directB),
                    "neither first normal prefix is admitted; a nonzero profile window is unavailable");
        }
    }

    @Test
    void longRayCannotUseNormalizedEventDedupToWaiveAnUnauthorizedShortPrefix() {
        var decision = new MetricRegion(List.of(
                MetricRegion.rectangle(-10, -100, 74, 50.25 - 2.5e-9).polygons().get(0),
                MetricRegion.rectangle(-10, 50.25 + 2.5e-9, 74, 100).polygons().get(0)));
        var evidence = scene(100, 1.0, 0.08, false, decision);
        var anchor = new MetricPoint(32, 0);
        assertTrue(decision.containsSegment(anchor, new MetricPoint(32, 100)),
                "historical normalized event dedup makes the whole-ray proof scale dependent");
        assertTrue(!decision.containsSegment(new MetricPoint(32, 50), new MetricPoint(32, 50.5)),
                "the same real gap is resolved by the ordered physical prefix");
        assertEquals(50.0, ProbabilisticProfileFactory.authorizedBoundary(anchor, new MetricPoint(0, 1),
                1.0, 100, 0.5, evidence,
                new ProbabilisticProfileFactory.BoundaryWork(CancellationProbe.NONE), null), 0.0);
    }

    @Test
    void requestedBoundaryVisitsAreAdmittedBeforeQueryScratchAllocation() {
        var evidence = scene(WIDTH, NATIVE, 0.08, false,
                MetricRegion.rectangle(-10, -WIDTH, 74, WIDTH));
        var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var refusal = assertThrows(ProbabilisticProfileFactory.ProfileBoundaryResourceLimitException.class,
                () -> ProbabilisticProfileFactory.authorizedBoundary(new MetricPoint(32, 0),
                    new MetricPoint(0, 1), 1, WIDTH, 0.5 * NATIVE, evidence,
                    new ProbabilisticProfileFactory.BoundaryWork(CancellationProbe.NONE, 1L), owner));
        assertEquals("profile boundary geometry work limit", refusal.getMessage());
        assertEquals(0L, ledger.peakBytes());
        assertEquals(0L, ledger.currentBytes());
        owner.close();
    }

    private static Stream<MetricRegion> noncontiguousRegions() {
        var concave = new MetricRegion(List.of(List.of(new MetricPoint(-10, -WIDTH),
                new MetricPoint(74, -WIDTH), new MetricPoint(74, WIDTH), new MetricPoint(-10, WIDTH),
                new MetricPoint(-10, 5.05), new MetricPoint(34, 5.05), new MetricPoint(34, 4.8),
                new MetricPoint(-10, 4.8))));
        return Stream.of(strips(4.8, 5.05), concave);
    }

    private static MetricRegion strips(double firstEnd, double secondStart) {
        return new MetricRegion(List.of(MetricRegion.rectangle(-10, -WIDTH, 74, firstEnd).polygons().get(0),
                MetricRegion.rectangle(-10, secondStart, 74, WIDTH).polygons().get(0)));
    }

    private static List<ProbabilisticProfile> profiles(EvidenceSnapshot evidence, double width,
            List<MetricPoint> source, boolean directB) {
        return new ProbabilisticProfileFactory().create(source, 16, width, false, evidence,
                evidence.fields().get("scalar"), EvidenceModelParameters.defaults(), CancellationProbe.NONE, directB);
    }

    private static EvidenceSnapshot scene(double widthMeters, double nativePitch, double amplitude,
            boolean invalidEdge, MetricRegion decision) {
        double extent = Math.max(20.0, widthMeters + 2.0);
        int width = 417, height = (int) Math.ceil(2.0 * extent / 0.25) + 1;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double lateral = -extent + 0.25 * y;
            values[y * width + x] = 0.02 + amplitude * StrictMath.exp(-0.5 * Math.pow((lateral - 5.25) / 0.7, 2));
            if (invalidEdge && lateral >= 6.25) valid[y * width + x] = false;
        }
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "physical-profile-boundary",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        var transform = new RasterMetricTransform("physical-profile-boundary",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(-20, -extent), 0.25, 0, 0, 0.25, 1);
        return new EvidenceSnapshot("physical-profile-boundary", frame, transform,
                EvidenceResolution.nativeSource(nativePitch, 0.25), decision,
                MetricRegion.rectangle(-19, -extent + 1, 83, extent - 1),
                Map.of("scalar", new ScalarEvidenceField(width, height, values, valid, lineage)),
                "physical-profile-boundary");
    }
}
