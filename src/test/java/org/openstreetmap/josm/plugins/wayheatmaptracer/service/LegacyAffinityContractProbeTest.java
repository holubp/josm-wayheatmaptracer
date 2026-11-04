package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.Sample;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** Executable measurement packet; no replacement tracker or fabricated source states. */
class LegacyAffinityContractProbeTest {
    private static final String FIELD = "affinity-contract-probe";
    private enum AffinityProof { COMPATIBLE, INCOMPATIBLE, UNKNOWN }

    @Test void cachedOwnerValidationAndRevisedDomainRejectMutatedSourceNormals() {
        var fixture = fixture(.3, false, false);
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
                var modes = input.experimentalMeasuredModes();
                var owner = modes.ownersAt(0).get(0);
                var normal = retained.profiles().levelZeroProfiles().get(0).normalScreen();
                double original = normal.x;
                normal.x += .1;
                try {
                    assertThrows(IllegalStateException.class, () -> modes.requireOwned(owner, 0));
                    assertThrows(IllegalStateException.class, () -> new LegacyEmissionDomain(binding,
                        retained.profiles().levelZeroProfiles(), List.of(-4.0, 4.0), budget, root, modes));
                } finally { normal.x = original; }
                modes.requireOwned(owner, 0);
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {100, 292, 576})
    void revisedDomainMemoryRefusalStaysTerminalAfterPressureIsReleased(long residual) {
        var fixture = fixture(.3, false, false);
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
                var modes = input.experimentalMeasuredModes();
                long stableBytes = ledger.currentBytes();
                // Native domain reserve is 512+512*11+256*11=8960, then aliases require 192 each.
                try (var pressure = root.reserve(ledger.limitBytes() - ledger.currentBytes() - 8960 - residual)) {
                    assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> new LegacyEmissionDomain(binding,
                        retained.profiles().levelZeroProfiles(), List.of(-4.0, 4.0), budget, root, modes));
                }
                assertEquals(stableBytes, ledger.currentBytes(), "Partial domain/alias reservations must roll back");
                assertThrows(AttemptMemoryLedger.ResourceLimitException.class, input::requireOpenAndUnchanged);
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void revisedOwnerFamilyAndCancellationFailuresRemainTerminalAndReleaseMemory() {
        for (boolean cancel : new boolean[] {false, true}) {
            var fixture = fixture(.3, false, false);
            var ledger = AttemptMemoryLedger.production();
            var cancellation = new java.util.concurrent.atomic.AtomicBoolean();
            var limits = cancel ? TraceBudgets.defaults() : new TraceBudgets(1, 8_000_000, 128_000_000, 32, 8);
            var budget = new EmissionWorkBudget(limits, cancellation::get);
            try (var root = ledger.rootOwner();
                 var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), FIELD,
                     fixture.input(), fixture.chainage(), 25, budget, root)) {
                var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), FIELD,
                    fixture.input().profileLocations(), 25, 1, 1, budget, permit);
                var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                    java.util.stream.IntStream.range(0, 11).boxed().toList(), 1, List.of());
                try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) {
                    if (cancel) {
                        cancellation.set(true);
                        assertThrows(java.util.concurrent.CancellationException.class, input::experimentalMeasuredModes);
                        cancellation.set(false);
                        assertThrows(java.util.concurrent.CancellationException.class, input::requireOpenAndUnchanged);
                    } else {
                        var modes = input.experimentalMeasuredModes();
                        assertThrows(LegacyEmissionDomain.StateFamilyLimitException.class,
                            () -> new LegacyEmissionDomain(binding, retained.profiles().levelZeroProfiles(),
                                List.of(-4.0, 4.0), budget, root, modes));
                        assertThrows(LegacyEmissionDomain.StateFamilyLimitException.class, input::requireOpenAndUnchanged);
                    }
                }
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test void revisedDomainRepresentsEveryAcceptedCenterWithoutReplacingNativeStates() {
        var fixture = fixture(.3, false, false);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), FIELD,
                 fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), FIELD,
                fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var rowMap = java.util.stream.IntStream.range(0, 11).boxed().toList();
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, rowMap, 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) {
                var modes = input.experimentalMeasuredModes();
                assertSame(modes, input.experimentalMeasuredModes());
                var old = new LegacyEmissionDomain(binding, retained.profiles().levelZeroProfiles(),
                    List.of(-4.0, 4.0), budget, root);
                var domain = new LegacyEmissionDomain(binding, retained.profiles().levelZeroProfiles(),
                    List.of(-4.0, 4.0), budget, root, modes);
                int represented = 0;
                for (int row = 0; row < 11; row++) {
                    assertEquals(1, retained.profiles().levelZeroProfiles().get(row).peaks().size());
                    assertEquals(2, domain.observedAt(row).size(), "Both accepted measured centers at row=" + row);
                    assertSame(binding.observations().get(row).producerPeaks().get(0).peak(),
                        domain.observedAt(row).get(0).peakOwner());
                    assertEquals(old.observedAt(row).get(0).nativeStateId(), domain.observedAt(row).get(0).nativeStateId());
                    assertEquals(old.observedAt(row).get(0).offsetOriginId(), domain.observedAt(row).get(0).offsetOriginId());
                    assertEquals(LegacyEmissionDomain.Kind.OBSERVED_SCALAR_MODE, domain.observedAt(row).get(1).kind());
                    assertNull(domain.observedAt(row).get(1).peakOwner());
                    for (var state : domain.observedAt(row)) assertNotNull(modes.ownerFor(state.evaluatorState()));
                    represented += domain.observedAt(row).size();
                }
                assertEquals(22, represented);
                assertThrows(IllegalArgumentException.class, () -> modes.requireOwned(modes.ownersAt(0).get(0), 1));
                var states = java.util.stream.IntStream.range(0, 11).boxed()
                    .flatMap(row -> domain.observedAt(row).stream()).map(LegacyEmissionDomain.State::evaluatorState).toList();
                try (var evaluator = new NativeConnectorEvaluator(input, new NativeConnectorEvaluator.Domain(states, List.of()),
                        NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1,
                        NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1, modes)) {
                    var weakA = domain.observedAt(0).get(1).evaluatorState();
                    var weakB = domain.observedAt(1).get(1).evaluatorState();
                    assertEquals(NativeConnectorEvaluator.Status.ADMITTED, evaluator.evaluate(weakA, weakB).status());
                    var fake = new NativeConnectorEvaluator.RowState(weakA.row(), 999,
                        weakA.offsetRasterPixels(), weakA.sourceClass(), null);
                    assertNull(modes.ownerFor(fake));
                    assertEquals(NativeConnectorEvaluator.Reason.FOREIGN_STATE, evaluator.evaluate(fake, weakB).reason());
                }
                input.close();
                assertThrows(IllegalStateException.class, () -> modes.ownersAt(0));
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void actualExtractorMeasuresBothThinStrandsFromEveryRetainedRow() {
        var fixture = fixture(.3, false, false);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), FIELD,
                 fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), FIELD,
                fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            for (int row = 0; row < 11; row++) {
                var profile = retained.profiles().levelZeroProfiles().get(row);
                var samples = profile.intensitySamples().stream()
                    .map(sample -> new Sample(sample.offsetPx(), sample.nativeIntensity(), sample.insideRaster()))
                    .toList();
                var measured = new LocalScalarProfileExtractor().extractDetailedWithAcceptedThresholds(
                    samples, 1.0, EvidenceModelParameters.Localization.defaults(), root,
                    CancellationProbe.NONE, count -> budget.charge(EmissionWorkBudget.Operation.MODE_EXTRACTION, count));
                System.out.println("retained row=" + row + " sampleCount=" + samples.size()
                    + " oldPeaks=" + profile.peaks().stream().map(p -> p.offsetPx()).toList()
                    + " baseline=" + measured.result().baseline() + " accepted=" + measured.acceptedReceipts()
                    + " suppressed=" + measured.result().suppressedModes());
                assertEquals(2, measured.result().baseline().modes().size(), "row=" + row);
                assertEquals(2, measured.acceptedReceipts().size(), "row=" + row);
                assertTrue(measured.result().suppressedModes().isEmpty());
                for (double offset : new double[] {-4, 4}) {
                    var receipt = measured.acceptedReceipts().stream()
                        .filter(r -> Math.abs(r.mode().centerMeters() - offset) < 1e-9).findFirst().orElseThrow();
                    assertTrue(receipt.rawCore().isPresent());
                    var flank = measured.result().flankReceipts().stream()
                        .filter(f -> f.mode() == receipt.mode()).findFirst().orElseThrow();
                    assertTrue(flank.hasTwoSidedValidOutwardContinuation());
                    var core = measured.result().rawCoreReceipts().stream()
                        .filter(c -> c.mode() == receipt.mode()).findFirst().orElseThrow();
                    assertTrue(core.leftAdjacentObservedValid() && core.leftAdjacentBelowCore());
                    assertTrue(core.rightAdjacentObservedValid() && core.rightAdjacentBelowCore());
                }
            }
            System.out.println("extractor work=" + budget.snapshot() + " peakBytes=" + ledger.peakBytes());
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void actualNativeCertificateSeparatesMeasuredSameBandAndCrossBandConnectors() {
        probeActualNativeCertificates(false, false);
    }

    @Test void invalidSourceRemainsUnavailableAndCannotBecomeDarkConflict() {
        probeActualNativeCertificates(true, false);
    }

    @Test void supportedJoinCannotSupplyASeparatedCorridorConflict() {
        probeActualNativeCertificates(false, true);
    }

    @Test void directlyMeasuredWeakBendAndSwitchbackHaveSupportedEmittedLegs() {
        double[] values = new double[128 * 128];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int x = 10; x <= 90; x++) {
            int center = weakPathY(x);
            values[center * 128 + x] = .3;
            values[(center - 1) * 128 + x] = .15;
            values[(center + 1) * 128 + x] = .15;
        }
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(128, 128, values, valid, lineage);
        for (int x = 42; x <= 50; x++) {
            var samples = new ArrayList<Sample>();
            for (int half = -50; half <= 50; half++) {
                double y = 64 + half * .5;
                var value = BilinearSegmentIntegral.evaluate(ScalarFieldGridView.fromEvidence(field),
                    new RasterPoint(x, y), new RasterPoint(x, y), CancellationProbe.NONE, () -> { });
                samples.add(new Sample(half * .5, value.mean().orElseThrow(), true));
            }
            var extracted = new LocalScalarProfileExtractor().extractDetailedWithAcceptedThresholds(samples, 1,
                EvidenceModelParameters.Localization.defaults(), null, CancellationProbe.NONE, count -> { });
            assertEquals(1, extracted.acceptedReceipts().size());
            assertEquals(weakPathY(x) - 64, extracted.acceptedReceipts().get(0).mode().centerMeters(), 1e-9);
        }
        // The apex is an actual emitted vertex. The shortcut intentionally erases its source-supported bend.
        var vertices = List.of(new RasterPoint(42, 60), new RasterPoint(46, 64), new RasterPoint(50, 60));
        for (int i = 1; i < vertices.size(); i++) {
            var integral = BilinearSegmentIntegral.evaluate(ScalarFieldGridView.fromEvidence(field),
                vertices.get(i - 1), vertices.get(i), CancellationProbe.NONE, () -> { });
            System.out.println("weak supported leg=" + integral);
            assertEquals(BilinearSegmentIntegral.Status.COMPLETE, integral.status());
            assertTrue(integral.minimum().orElseThrow() >= .20);
        }
        var shortcut = BilinearSegmentIntegral.evaluate(ScalarFieldGridView.fromEvidence(field),
            vertices.get(0), vertices.get(2), CancellationProbe.NONE, () -> { });
        assertEquals(0, shortcut.minimum().orElseThrow());
    }

    @Test void physicalModePitchDoesNotBecomeVirtualRasterPixelPitch() {
        double nativePitchMeters = 1.5560803;
        double normalizationRasterPixelsPerNativePixel = 24.001238633946606;
        double metersPerVirtualRasterPixel = nativePitchMeters / normalizationRasterPixelsPerNativePixel;
        var samples = new ArrayList<Sample>();
        for (int i = -32; i <= 32; i++) {
            double nativeOffset = i * .5;
            double rasterOffset = nativeOffset * normalizationRasterPixelsPerNativePixel;
            double physicalOffset = rasterOffset * metersPerVirtualRasterPixel;
            double intensity = Math.max(.9 * Math.max(0, 1 - Math.abs(nativeOffset + 4)),
                .3 * Math.max(0, 1 - Math.abs(nativeOffset - 4)));
            samples.add(new Sample(physicalOffset, intensity, true));
        }
        var result = new LocalScalarProfileExtractor().extractDetailed(samples, nativePitchMeters,
            EvidenceModelParameters.Localization.defaults());
        assertEquals(2, result.baseline().modes().size());
        for (var mode : result.baseline().modes()) {
            assertEquals(4 * nativePitchMeters, Math.abs(mode.centerMeters()), 1e-12);
            assertEquals(nativePitchMeters * .5, mode.localizationSigmaMeters(), 1e-12);
            assertEquals(4 * normalizationRasterPixelsPerNativePixel,
                Math.abs(mode.centerMeters() / metersPerVirtualRasterPixel), 1e-10);
        }
        System.out.println("unit control nativePitchMeters=" + nativePitchMeters + " metersPerVirtualPixel="
            + metersPerVirtualRasterPixel + " modes=" + result.baseline().modes());
    }

    private static int weakPathY(int x) {
        return x <= 42 || x >= 50 ? 60 : x <= 46 ? 60 + x - 42 : 64 - (x - 46);
    }

    private static void probeActualNativeCertificates(boolean missing, boolean join) {
        var fixture = fixture(.6, missing, join);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), FIELD,
                 fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), FIELD,
                fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var rowMap = java.util.stream.IntStream.range(0, 11).boxed().toList();
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, rowMap, 1, List.of());
            // The production domain constructs the endpoint identities and source classes.
            var domain = new LegacyEmissionDomain(binding, retained.profiles().levelZeroProfiles(),
                List.of(-4.0, 4.0), budget, root);
            var states = java.util.stream.IntStream.range(0, 11).boxed()
                .flatMap(row -> domain.observedAt(row).stream()).map(LegacyEmissionDomain.State::evaluatorState)
                .toList();
            var from = domain.observedAt(4).stream().filter(s -> s.offsetRasterPixels() < 0)
                .findFirst().orElseThrow().evaluatorState();
            var same = domain.observedAt(5).stream().filter(s -> s.offsetRasterPixels() < 0)
                .findFirst().orElseThrow().evaluatorState();
            var other = domain.observedAt(5).stream().filter(s -> s.offsetRasterPixels() > 0)
                .findFirst().orElseThrow().evaluatorState();
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding);
                 var evaluator = new NativeConnectorEvaluator(input,
                     new NativeConnectorEvaluator.Domain(states, List.of()),
                     NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
                var sameReceipt = evaluator.evaluate(from, same);
                var crossReceipt = evaluator.evaluate(from, other);
                boolean separator = hasMeasuredDarkSeparator(fixture.evidence().fields().get(FIELD), budget, root);
                AffinityProof proof = crossReceipt.status() == NativeConnectorEvaluator.Status.ADMITTED
                    ? AffinityProof.COMPATIBLE : separator && crossReceipt.scalar().isPresent()
                        && crossReceipt.scalar().orElseThrow().minimum().orElse(Double.NaN) == 0
                        ? AffinityProof.INCOMPATIBLE : AffinityProof.UNKNOWN;
                System.out.println("native missing=" + missing + " join=" + join
                    + " proof=" + proof + " same=" + summarize(sameReceipt) + " cross=" + summarize(crossReceipt));
                if (missing) {
                    assertEquals(NativeConnectorEvaluator.Status.UNAVAILABLE, sameReceipt.status());
                    assertEquals(NativeConnectorEvaluator.Reason.SCALAR_SUPPORT, sameReceipt.reason());
                    assertEquals(AffinityProof.UNKNOWN, proof);
                } else if (join) {
                    assertEquals(AffinityProof.UNKNOWN, proof);
                } else {
                    assertEquals(NativeConnectorEvaluator.Status.ADMITTED, sameReceipt.status());
                    assertEquals(NativeConnectorEvaluator.Status.REJECTED, crossReceipt.status());
                    assertEquals(AffinityProof.INCOMPATIBLE, proof);
                }
            }
            System.out.println("native work=" + budget.snapshot() + " peakBytes=" + ledger.peakBytes());
        }
        assertEquals(0, ledger.currentBytes());
    }

    private static String summarize(NativeConnectorEvaluator.Receipt receipt) {
        return receipt.status() + "/" + receipt.reason() + " scalar=" + receipt.scalar()
            + " planned=" + receipt.plannedProbeCount() + " probes=" + receipt.probes().stream()
                .filter(p -> p.parameter() == 0 || p.parameter() == 1 || Math.abs(p.parameter() - .5) < .03)
                .map(p -> p.parameter() + ":" + p.profileQuery() + ":modes="
                    + p.extraction().result().baseline().modes().size()).toList();
    }

    /** Exact narrow image witness, not a general production affinity classifier. */
    private static boolean hasMeasuredDarkSeparator(ScalarEvidenceField field, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        var previousThresholds = new java.util.HashMap<Double, Double>();
        for (int x = 42; x <= 50; x++) {
            var samples = new ArrayList<Sample>();
            for (int half = -50; half <= 50; half++) {
                double y = 64 + half * .5;
                var sampled = BilinearSegmentIntegral.evaluate(ScalarFieldGridView.fromEvidence(field),
                    new RasterPoint(x, y), new RasterPoint(x, y), CancellationProbe.NONE,
                    () -> budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1));
                samples.add(new Sample(half * .5, sampled.mean().orElse(Double.NaN),
                    sampled.status() == BilinearSegmentIntegral.Status.COMPLETE));
            }
            var extraction = new LocalScalarProfileExtractor().extractDetailedWithAcceptedThresholds(samples, 1,
                EvidenceModelParameters.Localization.defaults(), owner, CancellationProbe.NONE,
                count -> budget.charge(EmissionWorkBudget.Operation.MODE_EXTRACTION, count));
            if (extraction.acceptedReceipts().size() != 2
                    || !extraction.result().baseline().censoredModes().isEmpty()
                    || !extraction.result().suppressedModes().isEmpty()) return false;
            for (double offset : new double[] {-4, 4}) {
                var receipt = extraction.acceptedReceipts().stream()
                    .filter(r -> Math.abs(r.mode().centerMeters() - offset) < 1e-9).findFirst();
                if (receipt.isEmpty() || receipt.orElseThrow().rawCore().isEmpty()) return false;
                var core = extraction.result().rawCoreReceipts().stream()
                    .filter(c -> c.mode() == receipt.orElseThrow().mode()).findFirst().orElseThrow();
                if (!core.leftAdjacentObservedValid() || !core.leftAdjacentBelowCore()
                        || !core.rightAdjacentObservedValid() || !core.rightAdjacentBelowCore()) return false;
                if (x > 42) {
                    var leg = BilinearSegmentIntegral.evaluate(ScalarFieldGridView.fromEvidence(field),
                        new RasterPoint(x - 1, 64 + offset), new RasterPoint(x, 64 + offset),
                        CancellationProbe.NONE,
                        () -> budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1));
                    if (leg.status() != BilinearSegmentIntegral.Status.COMPLETE
                            || leg.minimum().orElseThrow() < Math.min(previousThresholds.get(offset),
                                receipt.orElseThrow().shoulderThreshold())) return false;
                }
                previousThresholds.put(offset, receipt.orElseThrow().shoulderThreshold());
            }
            // Every node of every separating bilinear cell is observed zero: a 2D barrier,
            // rather than treating a failed local extractor as evidence of darkness.
            for (int y = 61; y <= 67; y++) {
                budget.charge(EmissionWorkBudget.Operation.NODE_READ, 1);
                var value = field.sample(x, y);
                if (value.isEmpty() || value.orElseThrow() != 0) return false;
            }
        }
        return true;
    }

    record Fixture(EvidenceSnapshot evidence, CorridorTraceInput input, ProfileChainage chainage) { }

    static Fixture fixture(double weak, boolean missing, boolean join) {
        double[] values = new double[128 * 128];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int x = 10; x <= 90; x++) {
            values[60 * 128 + x] = Math.rint((x < 50 ? .9 : weak) * 255) / 255;
            values[68 * 128 + x] = Math.rint((x < 50 ? weak : .9) * 255) / 255;
        }
        if (missing) for (int y = 0; y < 128; y++) valid[y * 128 + 46] = false;
        if (join) for (int y = 60; y <= 68; y++) values[y * 128 + 46] = .6;
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
            new GeographicPoint(-.01, -.01), new GeographicPoint(.01, .01));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(128, 128, values, valid, lineage);
        var evidence = new EvidenceSnapshot(FIELD, frame, transform, EvidenceResolution.nativeSource(1, 1),
            MetricRegion.rectangle(0, 0, 127, 127), MetricRegion.rectangle(-.5, -.5, 127.5, 127.5),
            Map.of(FIELD, field), FIELD);
        List<DetachedProfileSamplingLocation> locations = new ArrayList<>();
        List<Double> chainages = new ArrayList<>();
        for (int row = 0; row < 11; row++) {
            double chainage = row * 8.0;
            var point = new MetricPoint(10 + chainage, 64);
            locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(point), frame, transform, chainage));
            chainages.add(chainage);
        }
        return new Fixture(evidence, new CorridorTraceInput(locations, 1), new ProfileChainage(chainages, 80));
    }
}
