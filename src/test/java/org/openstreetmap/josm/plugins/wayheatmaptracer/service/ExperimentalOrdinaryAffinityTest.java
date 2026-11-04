package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class ExperimentalOrdinaryAffinityTest {
    private static final String FIELD = "affinity-contract-probe";

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
    void sourceSlabsRetainEveryDirectedLinkAttemptAndActualUnknownPrefix(int variant) {
        var fixture = LegacyAffinityContractProbeTest.fixture(.6, variant == 1, variant == 2);
        withInput(fixture, (input, memory) -> {
            var domain = new LegacyEmissionDomain(input.legacyBinding().orElseThrow(), input.profiles().levelZeroProfiles(),
                List.of(-4.0, 4.0), input.workBudget(), memory, input.experimentalMeasuredModes());
            var classifier = new OrdinaryAffinityClassifier(input, domain, memory);
            var from = domain.observedAt(4).stream().filter(s -> s.offsetRasterPixels() < 0).findFirst().orElseThrow();
            var to = domain.observedAt(5).stream().filter(s -> s.offsetRasterPixels() > 0).findFirst().orElseThrow();
            var receipt = classifier.classify(from, to, domain.metricPoint(from, input.context()), domain.metricPoint(to, input.context()));
            var slab = receipt.slab();
            if (variant == 0) {
                assertEquals(4 * (slab.stations().size() - 1), slab.links().size(),
                    "All four directed previous/current mode pairs need retained outcomes");
                assertTrue(slab.failure().isEmpty());
                for (var link : slab.links()) {
                    assertSame(slab.stations().get(link.piece()).modes().get(link.fromModeIndex()), link.from());
                    assertSame(slab.stations().get(link.piece() + 1).modes().get(link.toModeIndex()), link.to());
                    if (link.fromModeIndex() == link.toModeIndex()) {
                        assertEquals(OrdinaryAffinityClassifier.LinkStatus.ADMITTED, link.status());
                        assertEquals(BilinearSegmentIntegral.Status.COMPLETE, link.scalar().orElseThrow().status());
                        assertTrue(link.scalar().orElseThrow().minimum().orElseThrow() >= link.threshold());
                    } else {
                        assertEquals(OrdinaryAffinityClassifier.LinkStatus.REJECTED, link.status());
                        assertEquals(OrdinaryAffinityClassifier.LinkReason.CONTINUATION_BOUND, link.reason());
                        assertTrue(link.scalar().isEmpty(), "Bound rejection does not invent a queried scalar");
                    }
                }
            } else {
                assertEquals(OrdinaryAffinityClassifier.Status.UNKNOWN, receipt.status());
                assertFalse(slab.stations().isEmpty(), "UNKNOWN must retain its actual measured station prefix");
                assertEquals(slab.stationExtractions(), slab.stations().size());
                assertEquals(slab.stations().size() - 1, slab.failure().orElseThrow().stationIndex());
                assertTrue(slab.plan().size() > slab.stations().size());
                assertTrue(slab.availableStation(slab.stations().size()).isEmpty(), "The remaining plan is unqueried");
                var failed = slab.stations().get(slab.stations().size() - 1);
                assertEquals(failed.samples().size(), failed.sampleQueries().size());
                if (variant == 1) {
                    assertEquals(OrdinaryAffinityClassifier.QueryStatus.PARTIAL_UNAVAILABLE, failed.queryStatus());
                    assertTrue(failed.sampleQueries().stream().anyMatch(q -> q.status() == BilinearSegmentIntegral.Status.UNAVAILABLE
                        && q.mean().isEmpty() && q.reason() == BilinearSegmentIntegral.Reason.UNSUPPORTED_CELL));
                } else {
                    assertEquals(OrdinaryAffinityClassifier.QueryStatus.COMPLETE, failed.queryStatus());
                    assertTrue(slab.links().stream().anyMatch(l -> l.status() == OrdinaryAffinityClassifier.LinkStatus.NOT_QUERIED
                        && l.reason() == OrdinaryAffinityClassifier.LinkReason.MODE_INCOMPLETE && l.scalar().isEmpty()));
                }
            }
            long before = classifier.stationExtractions();
            var same = domain.observedAt(5).stream().filter(s -> s.offsetRasterPixels() < 0).findFirst().orElseThrow();
            var next = classifier.classify(from, same, domain.metricPoint(from, input.context()), domain.metricPoint(same, input.context()));
            assertSame(slab, next.slab());
            assertEquals(before, classifier.stationExtractions());
        });
    }

    @Test void unqueriedAuthorityStationRetainsItsPlanAndTheActuallyMeasuredPrefix() {
        var base = scene(1, 1, 0, .6, 8, false, false);
        var left = MetricRegion.rectangle(0, 0, 45, 127);
        var right = MetricRegion.rectangle(47, 0, 127, 127);
        var middle = MetricRegion.rectangle(45, 0, 47, 80);
        var polygons = new java.util.ArrayList<List<MetricPoint>>();
        polygons.addAll(left.polygons()); polygons.addAll(right.polygons()); polygons.addAll(middle.polygons());
        var old = base.evidence();
        var evidence = new EvidenceSnapshot("authority-gap", old.coordinateFrame(), old.transform(), old.resolution(),
            new MetricRegion(polygons), old.evidenceRegion(), old.fields(), "authority-gap");
        withInput(new LegacyAffinityContractProbeTest.Fixture(evidence, base.input(), base.chainage()), (input, memory) -> {
            var domain = new LegacyEmissionDomain(input.legacyBinding().orElseThrow(), input.profiles().levelZeroProfiles(),
                List.of(-4.0, 4.0), input.workBudget(), memory, input.experimentalMeasuredModes());
            var classifier = new OrdinaryAffinityClassifier(input, domain, memory);
            var a = domain.observedAt(4).get(0); var b = domain.observedAt(5).get(0);
            var receipt = classifier.classify(a, b, domain.metricPoint(a, input.context()), domain.metricPoint(b, input.context()));
            assertEquals(OrdinaryAffinityClassifier.Status.UNKNOWN, receipt.status());
            var slab = receipt.slab();
            assertEquals(OrdinaryAffinityClassifier.PreparationPhase.STATION_AUTHORITY, slab.failure().orElseThrow().phase());
            int stopped = slab.failure().orElseThrow().stationIndex();
            assertTrue(stopped > 0);
            assertEquals(stopped, slab.stations().size());
            assertTrue(slab.availableStation(stopped).isEmpty());
            assertEquals(stopped, slab.stationExtractions());
            assertTrue(slab.stations().stream().allMatch(s -> s.queryStatus() == OrdinaryAffinityClassifier.QueryStatus.COMPLETE));
            long before = classifier.stationExtractions();
            assertSame(slab, classifier.classify(a, b, domain.metricPoint(a, input.context()), domain.metricPoint(b, input.context())).slab());
            assertEquals(before, classifier.stationExtractions());
        });
    }

    @Test void unavailableActualLinkMeasureRemainsDistinctFromAnUnqueriedLink() {
        var base = scene(1, 1, 0, .6, 8, false, false);
        double[] values = new double[128 * 128]; boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(valid, true);
        for (int y = 0; y < 128; y++) for (int x = 10; x <= 90; x++)
            values[y * 128 + x] = .9 * Math.max(Math.max(0, 1 - Math.abs(y - 60) / 8.0),
                Math.max(0, 1 - Math.abs(y - 76) / 8.0));
        valid[60 * 128 + 44] = false;
        var field = new ScalarEvidenceField(128, 128, values, valid, base.evidence().fields().get(FIELD).lineage());
        var old = base.evidence();
        // Declared 8 m native uncertainty on a 1 m retained grid tests an inter-station support hole.
        var evidence = new EvidenceSnapshot("link-hole", old.coordinateFrame(), old.transform(), EvidenceResolution.nativeSource(8, 1),
            old.decisionRegion(), old.evidenceRegion(), java.util.Map.of(FIELD, field), "link-hole");
        withInput(new LegacyAffinityContractProbeTest.Fixture(evidence, base.input(), base.chainage()), (input, memory) -> {
            var domain = new LegacyEmissionDomain(input.legacyBinding().orElseThrow(), input.profiles().levelZeroProfiles(),
                List.of(-4.0, 4.0), input.workBudget(), memory, input.experimentalMeasuredModes());
            var classifier = new OrdinaryAffinityClassifier(input, domain, memory);
            var a = domain.observedAt(4).get(0); var b = domain.observedAt(5).get(0);
            var receipt = classifier.classify(a, b, domain.metricPoint(a, input.context()), domain.metricPoint(b, input.context()));
            assertEquals(OrdinaryAffinityClassifier.Status.UNKNOWN, receipt.status());
            assertTrue(receipt.slab().links().stream().anyMatch(l -> l.status() == OrdinaryAffinityClassifier.LinkStatus.UNAVAILABLE
                && l.reason() == OrdinaryAffinityClassifier.LinkReason.SCALAR_SUPPORT && l.scalar().isPresent()
                && l.scalar().orElseThrow().status() == BilinearSegmentIntegral.Status.UNAVAILABLE
                && l.scalar().orElseThrow().mean().isEmpty()));
            assertTrue(receipt.slab().links().stream().anyMatch(l -> l.status() == OrdinaryAffinityClassifier.LinkStatus.REJECTED
                && l.reason() == OrdinaryAffinityClassifier.LinkReason.CONTINUATION_BOUND && l.scalar().isEmpty()));
        });
    }

    @Test void lowSignalFallbackNeverIssuesMeasuredOwnersEvenWhenOriginalClassIsObserved() {
        var base = scene(1, 1, 0, .3, 8, false, false);
        var old = base.evidence().fields().get(FIELD);
        double[] values = new double[128 * 128]; boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(valid, true);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) values[y * 128 + x] = old.sample(x, y).orElseThrow();
        values[60 * 128 + 34] = .1; values[68 * 128 + 34] = .1;
        var field = new ScalarEvidenceField(128, 128, values, valid, old.lineage());
        var evidence = new EvidenceSnapshot("low-fallback", base.evidence().coordinateFrame(), base.evidence().transform(),
            base.evidence().resolution(), base.evidence().decisionRegion(), base.evidence().evidenceRegion(),
            java.util.Map.of(FIELD, field), "low-fallback");
        withInput(new LegacyAffinityContractProbeTest.Fixture(evidence, base.input(), base.chainage()), (input, memory) -> {
            var row = input.legacyBinding().orElseThrow().observations().get(3);
            assertEquals(NativeConnectorEvaluator.SourceClass.OBSERVED, row.sourceClass());
            assertTrue(row.profile().peaks().stream().allMatch(p -> p.syntheticCenter()
                && p.supportWidthPx() <= 0 && p.prominence() <= 0));
            var modes = input.experimentalMeasuredModes();
            assertTrue(modes.ownersAt(3).isEmpty());
            var domain = new LegacyEmissionDomain(input.legacyBinding().orElseThrow(), input.profiles().levelZeroProfiles(),
                List.of(-4.0, 4.0), input.workBudget(), memory, modes);
            assertEquals(1, domain.observedAt(3).size());
            assertNull(modes.ownerFor(domain.observedAt(3).get(0).evaluatorState()));
        });
    }

    @Test void classifierMemoryWorkAndMidPreparationCancellationAreTerminalNotUnknown() {
        var fixture = LegacyAffinityContractProbeTest.fixture(.6, false, false);
        withInput(fixture, (input, memory) -> {
            var domain = new LegacyEmissionDomain(input.legacyBinding().orElseThrow(), input.profiles().levelZeroProfiles(),
                List.of(-4.0, 4.0), input.workBudget(), memory, input.experimentalMeasuredModes());
            var classifier = new OrdinaryAffinityClassifier(input, domain, memory);
            var a = domain.observedAt(4).get(0); var b = domain.observedAt(5).get(0);
            try (var pressure = memory.reserve(AttemptMemoryLedger.MAX_BYTES - memory.currentBytes() - 8192)) {
                assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> classifier.classify(a, b,
                    domain.metricPoint(a, input.context()), domain.metricPoint(b, input.context())));
            }
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class, input::requireOpenAndUnchanged);
        });
        var remaining = new java.util.concurrent.atomic.AtomicInteger(-1);
        withInput(fixture, TraceBudgets.defaults(), () -> {
            int value = remaining.get();
            return value >= 0 && remaining.getAndDecrement() == 0;
        }, (input, memory) -> {
            var domain = new LegacyEmissionDomain(input.legacyBinding().orElseThrow(), input.profiles().levelZeroProfiles(),
                List.of(-4.0, 4.0), input.workBudget(), memory, input.experimentalMeasuredModes());
            var classifier = new OrdinaryAffinityClassifier(input, domain, memory);
            var a = domain.observedAt(4).get(0); var b = domain.observedAt(5).get(0);
            remaining.set(300);
            assertThrows(java.util.concurrent.CancellationException.class, () -> classifier.classify(a, b,
                domain.metricPoint(a, input.context()), domain.metricPoint(b, input.context())));
            remaining.set(-1);
            assertThrows(java.util.concurrent.CancellationException.class, input::requireOpenAndUnchanged);
        });
        withInput(fixture, new TraceBudgets(96, 8_000_000, 850_000, 32, 8), CancellationProbe.NONE, (input, memory) -> {
            var domain = new LegacyEmissionDomain(input.legacyBinding().orElseThrow(), input.profiles().levelZeroProfiles(),
                List.of(-4.0, 4.0), input.workBudget(), memory, input.experimentalMeasuredModes());
            var classifier = new OrdinaryAffinityClassifier(input, domain, memory);
            var a = domain.observedAt(4).get(0); var b = domain.observedAt(5).get(0);
            assertThrows(EmissionWorkBudget.LimitExceededException.class, () -> classifier.classify(a, b,
                domain.metricPoint(a, input.context()), domain.metricPoint(b, input.context())));
            assertThrows(EmissionWorkBudget.LimitExceededException.class, input::requireOpenAndUnchanged);
        });
    }

    @Test void foreignOwnersRefuseEvenWithIdenticalSourceHashesAndFrames() {
        var fixture = LegacyAffinityContractProbeTest.fixture(.3, false, false);
        withInput(fixture, (first, firstMemory) -> {
            var firstModes = first.experimentalMeasuredModes();
            var owner = firstModes.ownersAt(0).get(0);
            withInput(fixture, (second, secondMemory) -> {
                var secondModes = second.experimentalMeasuredModes();
                assertEquals(first.context().evidenceContentHash(), second.context().evidenceContentHash());
                assertThrows(IllegalArgumentException.class, () -> secondModes.requireOwned(owner, 0));
                assertThrows(IllegalArgumentException.class, () -> new LegacyEmissionDomain(second.legacyBinding().orElseThrow(),
                    second.profiles().levelZeroProfiles(), List.of(-4.0, 4.0), second.workBudget(), secondMemory, firstModes));
                assertThrows(IllegalArgumentException.class, () -> new NativeConnectorEvaluator(second,
                    new NativeConnectorEvaluator.Domain(List.of(), List.of()),
                    NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1,
                    NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1, firstModes));
            });
        });
    }

    @Test void selectedUnknownJoinRemainsEligibleAndIsExplicitlyUncertified() {
        withInput(LegacyAffinityContractProbeTest.fixture(.6, false, true), (input, memory) -> {
            try (var result = new RidgeTracker().trackEmissionMeasuredAffinity(input, CancellationProbe.NONE)) {
                assertFalse(result.affinity().orElseThrow().fullyCertified());
                assertTrue(result.affinity().orElseThrow().inference().stream()
                    .anyMatch(a -> a.status() == OrdinaryAffinityClassifier.Status.UNKNOWN));
                assertEquals("LEGACY_OPTIONAL_MEASURED_AFFINITY_V1", result.sourceReference().modelPolicyId());
            }
        });
    }

    @Test void originalGapDoesNotGainObservedOwnershipButKeepsSupportedOptionalBridge() {
        var base = scene(1, 1, 0, .3, 8, false, false);
        double[] values = new double[128 * 128]; boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(valid, true);
        var old = base.evidence().fields().get(FIELD);
        for (int x = 30; x <= 100; x++) values[60 * 128 + x] = .9;
        var field = new ScalarEvidenceField(128, 128, values, valid, old.lineage());
        var evidence = new EvidenceSnapshot("supported-gap", base.evidence().coordinateFrame(), base.evidence().transform(),
            base.evidence().resolution(), base.evidence().decisionRegion(), base.evidence().evidenceRegion(),
            java.util.Map.of(FIELD, field), "supported-gap");
        // The middle original normal is horizontal at y=64 and measures an actual all-zero GAP.
        // The physical bridge between rows 1 and 5 follows the independently acquired bright y=60 line.
        var points = List.of(new MetricPoint(52, 64), new MetricPoint(60, 64), new MetricPoint(64, 62),
            new MetricPoint(64, 64), new MetricPoint(64, 66), new MetricPoint(68, 64), new MetricPoint(76, 64));
        var locations = new java.util.ArrayList<DetachedProfileSamplingLocation>();
        var chainages = new java.util.ArrayList<Double>();
        double chainage = 0;
        for (int row = 0; row < points.size(); row++) {
            var point = points.get(row);
            if (row > 0) chainage += points.get(row - 1).distanceTo(point);
            locations.add(new DetachedProfileSamplingLocation(evidence.coordinateFrame().toGeographic(point), point,
                new RasterPoint(point.xMeters(), point.yMeters()), evidence.coordinateFrame(), evidence.transform(), chainage));
            chainages.add(chainage);
        }
        var fixture = new LegacyAffinityContractProbeTest.Fixture(evidence, new CorridorTraceInput(locations, 1),
            new ProfileChainage(chainages, chainage));
        withInput(fixture, (input, memory) -> {
            var inventory = input.experimentalMeasuredModes();
            assertEquals(NativeConnectorEvaluator.SourceClass.GAP, input.legacyBinding().orElseThrow().observations().get(3).sourceClass());
            assertTrue(input.profiles().levelZeroProfiles().get(3).intensitySamples().stream()
                .allMatch(sample -> sample.nativeIntensity() == 0));
            assertTrue(inventory.ownersAt(3).isEmpty());
            var domain = new LegacyEmissionDomain(input.legacyBinding().orElseThrow(), input.profiles().levelZeroProfiles(),
                List.of(-4.0, 4.0), input.workBudget(), memory, inventory);
            var states = java.util.stream.IntStream.range(0, 7).boxed()
                .flatMap(row -> domain.observedAt(row).stream()).map(LegacyEmissionDomain.State::evaluatorState).toList();
            var ordinals = new java.util.HashMap<Integer, Integer>();
            for (int row = 0; row < 7; row++) ordinals.put(row, row);
            try (var evaluator = new NativeConnectorEvaluator(input,
                    new NativeConnectorEvaluator.Domain(states, List.of(0, 6), List.of(), ordinals),
                    NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                    NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1, inventory)) {
                var from = domain.observedAt(1).get(0);
                var to = domain.observedAt(5).get(0);
                var bridge = evaluator.evaluate(from.evaluatorState(), to.evaluatorState());
                assertEquals(NativeConnectorEvaluator.Status.ADMITTED, bridge.status(), bridge.toString());
                assertEquals(chainages.get(5) - chainages.get(1), bridge.sourceSpanMeters(), 0);
                assertTrue(bridge.probes().stream().allMatch(p -> p.extraction().result().baseline().maximumIntensity() > .85));
            }
        });
    }

    private static void withInput(LegacyAffinityContractProbeTest.Fixture fixture,
            java.util.function.BiConsumer<SampledEmissionInput, AttemptMemoryLedger.Owner> action) {
        withInput(fixture, TraceBudgets.defaults(), CancellationProbe.NONE, action);
    }

    private static void withInput(LegacyAffinityContractProbeTest.Fixture fixture, TraceBudgets limits,
            CancellationProbe cancellation, java.util.function.BiConsumer<SampledEmissionInput, AttemptMemoryLedger.Owner> action) {
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(limits, cancellation);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), FIELD,
                 fixture.input(), fixture.chainage(), 25, budget, root)) {
            double pitch = fixture.evidence().resolution().outputRasterPitchMeters();
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), FIELD,
                fixture.input().profileLocations(), 25 / pitch, fixture.input().lateralStepMeters() / pitch,
                fixture.evidence().resolution().effectivePitchMeters() / pitch, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                java.util.stream.IntStream.range(0, fixture.input().profileLocations().size()).boxed().toList(), 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) {
                action.accept(input, root);
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void affineObliquePhysicalPitchStrengthDirectionAndSeparationControlsKeepSourceOwnership() {
        double[][] variants = {
            {1, 1, 0, .3, 8, 0, 0}, {1.5560803, .6, .8, .3, 8, 0, 0},
            {.75, 1, 0, .6, 12, 1, 0}, {1, 1, 0, .2, 6, 0, 0}, {1, 1, 0, .6, 8, 0, 1}
        };
        for (double[] settings : variants) {
            double pitch = settings[0];
            var fixture = scene(pitch, settings[1], settings[2], settings[3], (int) settings[4],
                settings[5] != 0, settings[6] != 0);
            var ledger = AttemptMemoryLedger.production();
            var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
            try (var root = ledger.rootOwner();
                 var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), FIELD,
                     fixture.input(), fixture.chainage(), 25, budget, root)) {
                var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), FIELD,
                    fixture.input().profileLocations(), 25 / pitch, fixture.input().lateralStepMeters() / pitch,
                    1, budget, permit);
                var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                    java.util.stream.IntStream.range(0, 11).boxed().toList(), 1, List.of());
                try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) {
                    var inventory = input.experimentalMeasuredModes();
                    var domain = new LegacyEmissionDomain(binding, retained.profiles().levelZeroProfiles(),
                        List.of(-4.0, 4.0), budget, root, inventory);
                    var classifier = new OrdinaryAffinityClassifier(input, domain, root);
                    var from = domain.observedAt(4).stream().filter(s -> s.offsetRasterPixels() < 0).findFirst().orElseThrow();
                    var same = domain.observedAt(5).stream().filter(s -> s.offsetRasterPixels() < 0).findFirst().orElseThrow();
                    var other = domain.observedAt(5).stream().filter(s -> s.offsetRasterPixels() > 0).findFirst().orElseThrow();
                    var a = domain.metricPoint(from, input.context());
                    var cross = classifier.classify(from, other, a, domain.metricPoint(other, input.context()));
                    var straight = classifier.classify(from, same, a, domain.metricPoint(same, input.context()));
                    System.out.println("affine variant=" + java.util.Arrays.toString(settings) + " cross="
                        + cross.status() + "/" + cross.reason() + " same=" + straight.status());
                    if (settings[6] != 0) {
                        assertEquals(OrdinaryAffinityClassifier.Status.UNKNOWN, cross.status());
                        assertEquals(OrdinaryAffinityClassifier.Reason.UNSUPPORTED_CHART, cross.reason());
                    } else {
                        assertEquals(OrdinaryAffinityClassifier.Status.INCOMPATIBLE, cross.status(), cross.toString());
                        assertEquals(OrdinaryAffinityClassifier.Status.COMPATIBLE, straight.status(), straight.toString());
                        assertEquals(pitch, inventory.ownerFor(from.evaluatorState()).nativePitchMeters(), 0);
                        assertEquals(pitch, inventory.ownerFor(from.evaluatorState()).metersPerRasterOffset(), 1e-14);
                    }
                }
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    static LegacyAffinityContractProbeTest.Fixture scene(double pitch, double cosine, double sine,
            double weak, int separation, boolean reverse, boolean curved) {
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
            new GeographicPoint(-.01, -.01), new GeographicPoint(.01, .01));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0),
            pitch * cosine, pitch * sine, -pitch * sine, pitch * cosine);
        double[] values = new double[128 * 128]; boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(valid, true);
        for (int x = 10; x <= 90; x++) {
            boolean firstStrong = (x < 50) != reverse;
            values[(64 - separation / 2) * 128 + x] = Math.rint((firstStrong ? .9 : weak) * 255) / 255;
            values[(64 + separation / 2) * 128 + x] = Math.rint((firstStrong ? weak : .9) * 255) / 255;
        }
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(128, 128, values, valid, lineage);
        var evidence = new EvidenceSnapshot(FIELD, frame, transform, EvidenceResolution.nativeSource(pitch, pitch),
            rectangle(transform, 0, 127), rectangle(transform, -.5, 127.5), java.util.Map.of(FIELD, field), FIELD);
        var locations = new java.util.ArrayList<DetachedProfileSamplingLocation>();
        var chainages = new java.util.ArrayList<Double>();
        for (int row = 0; row < 11; row++) {
            double x = reverse ? 90 - row * 8 : 10 + row * 8;
            double y = curved && row == 6 ? 65 : 64;
            var point = transform.pixelCenterToMetric(x, y);
            double chainage = row * 8.0 * pitch;
            locations.add(new DetachedProfileSamplingLocation(frame.toGeographic(point), point,
                new RasterPoint(x, y), frame, transform, chainage));
            chainages.add(chainage);
        }
        return new LegacyAffinityContractProbeTest.Fixture(evidence, new CorridorTraceInput(locations, pitch),
            new ProfileChainage(chainages, 80 * pitch));
    }

    private static MetricRegion rectangle(RasterMetricTransform transform, double minimum, double maximum) {
        return new MetricRegion(List.of(List.of(transform.pixelCenterToMetric(minimum, minimum),
            transform.pixelCenterToMetric(maximum, minimum), transform.pixelCenterToMetric(maximum, maximum),
            transform.pixelCenterToMetric(minimum, maximum))));
    }
    @Test void revisedProductionClassifierProvesOnlyTheMeasuredSeparatedCrossingAndSharesStations() {
        for (int variant = 0; variant < 3; variant++) {
            var fixture = LegacyAffinityContractProbeTest.fixture(.6, variant == 1, variant == 2);
            var ledger = AttemptMemoryLedger.production();
            var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
            try (var root = ledger.rootOwner();
                 var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), FIELD,
                     fixture.input(), fixture.chainage(), 25, budget, root)) {
                var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), FIELD,
                    fixture.input().profileLocations(), 25, 1, 1, budget, permit);
                var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                    java.util.stream.IntStream.range(0, 11).boxed().toList(), 1, List.of());
                try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) {
                    var domain = new LegacyEmissionDomain(binding, retained.profiles().levelZeroProfiles(),
                        List.of(-4.0, 4.0), budget, root, input.experimentalMeasuredModes());
                    var classifier = new OrdinaryAffinityClassifier(input, domain, root);
                    var from = domain.observedAt(4).stream().filter(s -> s.offsetRasterPixels() < 0).findFirst().orElseThrow();
                    var same = domain.observedAt(5).stream().filter(s -> s.offsetRasterPixels() < 0).findFirst().orElseThrow();
                    var other = domain.observedAt(5).stream().filter(s -> s.offsetRasterPixels() > 0).findFirst().orElseThrow();
                    var a = domain.metricPoint(from, input.context());
                    var cross = classifier.classify(from, other, a, domain.metricPoint(other, input.context()));
                    long extractionCount = classifier.stationExtractions();
                    var straight = classifier.classify(from, same, a, domain.metricPoint(same, input.context()));
                    System.out.println("production affinity variant=" + variant + " cross=" + cross.status()
                        + "/" + cross.reason() + " same=" + straight.status() + "/" + straight.reason()
                        + " stations=" + extractionCount + " work=" + budget.snapshot());
                    if (variant == 0) {
                        assertEquals(OrdinaryAffinityClassifier.Status.INCOMPATIBLE, cross.status(), cross.toString());
                        assertEquals(OrdinaryAffinityClassifier.Status.COMPATIBLE, straight.status(), straight.toString());
                        assertFalse(cross.crossed().isEmpty());
                        assertEquals(cross.slab().stations().size(), extractionCount);
                        for (int i = 1; i < cross.slab().stations().size(); i++) {
                            var previous = cross.slab().stations().get(i - 1);
                            var next = cross.slab().stations().get(i);
                            assertTrue(previous.anchor().distanceTo(next.anchor()) <= .5,
                                "Actual frozen coordinates determine the half-native station bound");
                        }
                    } else {
                        assertEquals(OrdinaryAffinityClassifier.Status.UNKNOWN, cross.status());
                        assertEquals(OrdinaryAffinityClassifier.Status.UNKNOWN, straight.status());
                    }
                    assertEquals(extractionCount, classifier.stationExtractions());
                    assertEquals(1, classifier.preparedRowPairs());
                    assertSame(cross, classifier.classify(from, other, a, domain.metricPoint(other, input.context())));
                }
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

}
