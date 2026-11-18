package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Public analytical forced-gap reproducer; no archived imagery or private geometry. */
class CorridorGapDomainTest {
    static final RasterMetricTransform FRAME = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1.5, 0, 0, 1.5);
    record Fixture(CorridorTrack track, List<CorridorProfile> profiles, LongitudinalCorridorTube tube,
            EvidenceSnapshot evidence, CorridorWorkingSchedule.BoundContext context) { }

    @Test void certifiedGapRetainsHistoricalOffsetAndOffersCoherentTubeAlternative() throws Exception {
        var f = fixture();
        var optimizer = new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(FRAME, CancellationProbe.NONE);
        var old = optimizer.prepareOriginalDomain(f.track(), f.profiles(), 1, JunctionContext.empty(), Map.of(),
                f.tube(), TraceBudgets.defaults(), f.context());
        assertEquals(List.of(.5), old.states().get(12));
        var oldResult = optimizer.optimizeOriginalDomain(old);
        assertEquals(.5, oldResult.offsetsPx().get(12));
        var historical = guarded().optimize(f.track(), f.profiles(), 1, JunctionContext.empty(), Map.of(), f.tube(), GeometryCleanupConfig.disabled());
        assertEquals(historical.offsetsPx(), oldResult.offsetsPx());
        assertEquals(historical.totalCost(), oldResult.totalCost(), "Historical objective must remain exact");
        var expanded = optimizer.prepareGapDomain(old, 7.01);
        assertEquals(List.of(-.5, .5), expanded.states().get(12),
                "A certified unobserved row must retain the old value and offer its coherent tube center");
        assertFalse(expanded.track().points().containsKey(12), "Extra geometry must not fabricate an observation");
        var result = optimizer.optimizeOriginalDomain(expanded);
        assertTrue(ripple(result.offsetsPx()) < ripple(oldResult.offsetsPx()));
        assertTrue(result.totalCost() < oldResult.totalCost());
        assertEquals(expanded.gapPlan().orElseThrow().work(), optimizer.preparedWork().gapWork().orElseThrow(),
                "Inference reset must preserve admission work");
    }

    @Test void nativePointRejectionPreservesHistoricalSingletonAndCountsRejectedWork() {
        var f = fixture(); var e = f.evidence();
        var clipped = new EvidenceSnapshot(e.snapshotId(), e.coordinateFrame(), e.transform(), e.resolution(),
                MetricRegion.rectangle(0, 30, 94, 70), e.evidenceRegion(), e.fields(), e.sourceIdentity());
        var context = context(clipped, List.of());
        var optimizer = guarded(); var d = original(optimizer, f, context, f.track(), JunctionContext.empty(), TraceBudgets.defaults());
        var expanded = optimizer.prepareGapDomain(d, 7.01);
        assertEquals(List.of(.5), expanded.states().get(12));
        var work = expanded.gapPlan().orElseThrow().work();
        assertEquals(1, work.pointChecks()); assertEquals(1, work.rejectedPoints()); assertEquals(0, work.edgeChecks());
        assertEquals(d.budgets().maximumPairVisits() - 1, expanded.budgets().maximumPairVisits());
    }

    @Test void missingMarkerNonDirectProtectedFixedAndParentGapsRemainHistorical() {
        var f = fixture();
        for (int variant = 0; variant < 7; variant++) {
            var points = new LinkedHashMap<>(f.track().points()); var right = points.get(13);
            if (variant == 0) points.put(13, new CorridorTrackPoint(13, right.band(), false));
            if (variant == 1) points.put(13, new CorridorTrackPoint(13, right.band(), true, CorridorPointSupport.BOUNDED_INTERPOLATION));
            if (variant >= 5) {
                var b = right.band();
                var band = new CorridorBand(b.id(), b.centerOffsetPx(), b.shoulderMinPx(), b.shoulderMaxPx(), b.coreMinPx(), b.coreMaxPx(),
                        b.nestedCentersPx(), b.peakIntensity(), b.noiseFloor(), b.valleyRatio(), b.gradientStrength(), b.gradientBalance(),
                        b.scaleAgreement(), b.signalExistenceConfidence(), b.localizationConfidence(), b.uncertaintyPx(), variant == 6,
                        variant == 6 ? List.of("unresolved-child") : List.of(), variant == 5 ? CorridorBand.BoundaryCompleteness.CORE_CENSORED : CorridorBand.BoundaryCompleteness.COMPLETE,
                        variant == 5 ? CorridorBand.BoundarySide.RIGHT : CorridorBand.BoundarySide.NONE);
                points.put(13, new CorridorTrackPoint(13, band, true));
            }
            var track = new CorridorTrack(f.track().id(), points, 1, 29.0 / 30, variant == 4, List.of(), "");
            var context = variant == 2 ? context(f.evidence(), List.of(18.0)) : f.context();
            var junctions = variant == 3 ? new JunctionContext(List.of(new EndpointConstraint(12, 1, true, false, 0, 0, 0))) : JunctionContext.empty();
            var optimizer = guarded(); var d = original(optimizer, f, context, track, junctions, TraceBudgets.defaults());
            var expanded = optimizer.prepareGapDomain(d, 7.01);
            assertEquals(d.states(), expanded.states(), "Unsafe variant " + variant);
            assertEquals(0, expanded.gapPlan().orElseThrow().work().pointChecks());
        }
    }

    @Test void oneSidedOversizedAndOverTwentyMetreGapsKeepTheirOriginalStates() {
        var f = fixture(); var slices = new ArrayList<CorridorTubeSlice>();
        for (var s : f.tube().slices()) slices.add(new CorridorTubeSlice(s.profileIndex(), s.distanceMeters(), -.4, 0, 0,
                s.coreMinPx(), s.coreMaxPx(), s.shoulderMinPx(), s.shoulderMaxPx(), s.uncertaintyPx(), s.confidence(), false, false,
                -.4, -.4, -.4, s.observed()));
        for (int[] interval : List.of(new int[] {-1, 13}, new int[] {0, 18}, new int[] {5, 19})) {
            var points = new LinkedHashMap<>(f.track().points());
            points.keySet().removeIf(i -> i > interval[0] && i < interval[1]);
            var right = points.get(interval[1]); points.put(interval[1], new CorridorTrackPoint(interval[1], right.band(), true));
            var track = new CorridorTrack("bounded-negative", points, 1, .5, false, List.of(), "");
            var optimizer = guarded();
            var d = optimizer.prepareOriginalDomain(track, f.profiles(), 1, JunctionContext.empty(), Map.of(),
                    new LongitudinalCorridorTube(slices), TraceBudgets.defaults(), f.context());
            var expanded = optimizer.prepareGapDomain(d, 7.01);
            assertEquals(d.states(), expanded.states()); assertEquals(0, expanded.gapPlan().orElseThrow().work().pointChecks());
        }
    }

    @Test void planAndBudgetCopiesRetainExactStatesTokenAndContextWithoutAuthorityRerun() {
        var f = fixture(); var optimizer = guarded(); var old = original(optimizer, f, f.context(), f.track(), JunctionContext.empty(), TraceBudgets.defaults());
        var d = optimizer.prepareGapDomain(old, 7.01); var plan = d.gapPlan().orElseThrow();
        var copy = CorridorCenterlineOptimizer.withBudgets(d, d.budgets());
        assertSame(plan, copy.gapPlan().orElseThrow()); assertSame(d.states(), copy.states()); plan.requireBinding(copy);
        assertThrows(IllegalArgumentException.class, () -> plan.requireBinding(old));
        var other = original(optimizer, f, f.context(), f.track(), JunctionContext.empty(), TraceBudgets.defaults());
        assertThrows(IllegalArgumentException.class, () -> plan.requireBinding(other));
        assertThrows(UnsupportedOperationException.class, () -> d.states().get(12).clear());
        assertEquals(1, plan.work().pointChecks()); assertTrue(plan.work().edgeChecks() > 0);
        var wrongFrame = new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(
                RasterMetricTransform.metricGrid(new MetricPoint(100, 100), 0, 1.5, -1.5, 0), CancellationProbe.NONE);
        assertThrows(IllegalArgumentException.class, () -> wrongFrame.optimizeOriginalDomain(d));
        assertEquals(0, wrongFrame.preparedWork().solverStarts());
    }

    @Test void distinctEffectiveAndStabilityStatesStayOffGridAndBoundedToThreeAcrossSourcePitches() {
        var f = fixture(); var slices = new ArrayList<>(f.tube().slices());
        slices.set(12, new CorridorTubeSlice(12, 18, -.5, 0, -.5, 0, -.4, 0, .2, 0, "analytic-gap", 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, .2, .9, false, false, -.5, -.5, -.5, false));
        for (double pitch : List.of(.5, 1.0, 2.0)) {
            var optimizer = guarded();
            var d = optimizer.prepareOriginalDomain(f.track(), f.profiles(), pitch, JunctionContext.empty(), Map.of(),
                    new LongitudinalCorridorTube(slices), TraceBudgets.defaults(), f.context());
            var expanded = optimizer.prepareGapDomain(d, 7.01);
            assertEquals(List.of(-.5, -.4, .5), expanded.states().get(12));
            assertEquals(2, expanded.gapPlan().orElseThrow().work().pointChecks());
        }
    }

    @Test void combinedAdmissionUsesCheckedArithmeticAndRefusesBeforeSourceChecks() {
        assertThrows(ArithmeticException.class, () -> CorridorGapDomain.estimateMaskBytes(Long.MAX_VALUE, 1, 1));
        assertThrows(ArithmeticException.class, () -> CorridorGapDomain.estimateMetricBytes(1, Long.MAX_VALUE, 1));
        var f = fixture(); var optimizer = guarded();
        var budgets = new TraceBudgets(96, 1, 128_000_000, 32, 4);
        var d = original(optimizer, f, f.context(), f.track(), JunctionContext.empty(), budgets);
        assertThrows(CorridorResourceLimitException.class, () -> optimizer.prepareGapDomain(d, 7.01));
        assertEquals(0, optimizer.preparedWork().solverStarts());
        assertTrue(optimizer.preparedWork().gapWork().isEmpty());
    }

    @Test void fineChildCoarsePersistenceMergeIsNotAConflictingPositionPrior() {
        var f = fixture(); var slices = new ArrayList<>(f.tube().slices()); var band = f.track().points().get(11).band();
        slices.set(11, new CorridorTubeSlice(11, 16.5, -.5, 0, 0, band.coreMinPx(), band.coreMaxPx(),
                band.shoulderMinPx(), band.shoulderMaxPx(), .2, .9, false, true, .5, .5, .5, true));
        var tube = new LongitudinalCorridorTube(slices);
        for (boolean conflict : List.of(false, true)) {
            var scale = new BandScaleEvidence(.8, 9999, 1, List.of(0, 1), conflict, true);
            assertFalse(scale.hasCoarseCenterPrior());
            var optimizer = guarded();
            var original = optimizer.prepareOriginalDomain(f.track(), f.profiles(), 1, JunctionContext.empty(),
                    Map.of(CorridorCenterlineOptimizer.scaleEvidenceKey(11, band.id()), scale), tube, TraceBudgets.defaults(), f.context());
            var d = optimizer.prepareGapDomain(original, 7.01);
            assertEquals(conflict ? List.of(.5) : List.of(-.5, .5), d.states().get(12));
            assertFalse(d.states().get(12).contains(9999.0));
        }
    }

    @Test void sourceMaskIsEnforcedInsideTheActualDpAcrossDecisionNotch() {
        assertMaskedDp(12);
        assertMaskedDp(1); // First-pair initialization, not only later triple rows.
    }
    private static void assertMaskedDp(int gap) {
        var f = fixture(gap); var e = f.evidence(); double x = 1.5 * (10 + gap) - .75;
        var polygons = new ArrayList<List<MetricPoint>>();
        for (var bounds : List.of(new double[] {0, 0, x - .45, 70}, new double[] {x + .45, 0, 94, 70},
                new double[] {x - .45, 0, x + .45, 28.8}, new double[] {x - .45, 30, x + .45, 70}))
            polygons.addAll(MetricRegion.rectangle(bounds[0], bounds[1], bounds[2], bounds[3]).polygons());
        var notched = new EvidenceSnapshot(e.snapshotId(), e.coordinateFrame(), e.transform(), e.resolution(),
                new MetricRegion(polygons), e.evidenceRegion(), e.fields(), e.sourceIdentity());
        var optimizer = guarded(); var d = optimizer.prepareGapDomain(original(optimizer, f, context(notched, List.of()),
                f.track(), JunctionContext.empty(), TraceBudgets.defaults()), 7.01);
        var plan = d.gapPlan().orElseThrow(); assertTrue(plan.work().rejectedEdges() > 0);
        var result = optimizer.optimizeOriginalDomain(d);
        for (int row = 1; row < d.states().size(); row++)
            assertTrue(plan.allows(row, d.states().get(row - 1).indexOf(result.offsetsPx().get(row - 1)),
                    d.states().get(row).indexOf(result.offsetsPx().get(row))), "DP used uncertified added-state edge at " + row);
    }

    @Test void cancellationRetainsActualSourceCallsAndNextPreparationResetsThem() {
        var f = fixture(); var holder = new CorridorCenterlineOptimizer[1];
        holder[0] = new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(FRAME,
                () -> holder[0].preparedWork().gapWork().map(w -> w.pointChecks() > 0).orElse(false));
        var d = original(holder[0], f, f.context(), f.track(), JunctionContext.empty(), TraceBudgets.defaults());
        assertThrows(java.util.concurrent.CancellationException.class, () -> holder[0].prepareGapDomain(d, 7.01));
        assertEquals(1, holder[0].preparedWork().gapWork().orElseThrow().pointChecks());
        assertEquals(0, holder[0].preparedWork().solverStarts());
        original(holder[0], f, f.context(), f.track(), JunctionContext.empty(), TraceBudgets.defaults());
        assertTrue(holder[0].preparedWork().gapWork().isEmpty());
    }

    @Test void cancellationPreservesCompletedPointDecisionsAndPartialRowFacts() {
        var f = fixture(); var slices = new ArrayList<>(f.tube().slices());
        slices.set(12, new CorridorTubeSlice(12, 18, -.5, 0, -.5, 0, -.4, 0, .2, 0, "analytic-gap", 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, .2, .9, false, false, -.5, -.5, -.5, false));
        for (boolean reject : List.of(true, false)) {
            var e = f.evidence();
            if (reject) e = new EvidenceSnapshot(e.snapshotId(), e.coordinateFrame(), e.transform(), e.resolution(),
                    MetricRegion.rectangle(0, 30, 94, 70), e.evidenceRegion(), e.fields(), e.sourceIdentity());
            var holder = new CorridorCenterlineOptimizer[1];
            holder[0] = new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(FRAME,
                    () -> holder[0].preparedWork().gapWork().map(w -> w.pointChecks() == 1).orElse(false));
            var d = holder[0].prepareOriginalDomain(f.track(), f.profiles(), 1, JunctionContext.empty(), Map.of(),
                    new LongitudinalCorridorTube(slices), TraceBudgets.defaults(), context(e, List.of()));
            assertThrows(java.util.concurrent.CancellationException.class, () -> holder[0].prepareGapDomain(d, 7.01));
            var work = holder[0].preparedWork().gapWork().orElseThrow();
            assertEquals(1, work.pointChecks()); assertEquals(reject ? 1 : 0, work.rejectedPoints());
            assertEquals(reject ? 0 : 1, work.addedStates());
            assertEquals(reject ? List.of() : List.of(-.5), work.rows().get(0).addedOffsets());
            assertEquals(reject ? 1 : 0, work.rows().get(0).rejectedPoints());
        }
    }

    @Test void cancellationPreservesCompletedRejectedEdgeDecision() {
        var f = fixture(); var e = f.evidence();
        e = new EvidenceSnapshot(e.snapshotId(), e.coordinateFrame(), e.transform(), e.resolution(),
                MetricRegion.rectangle(32.9, 29, 33.1, 29.8), e.evidenceRegion(), e.fields(), e.sourceIdentity());
        var holder = new CorridorCenterlineOptimizer[1];
        holder[0] = new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(FRAME,
                () -> holder[0].preparedWork().gapWork().map(w -> w.edgeChecks() == 1).orElse(false));
        var d = original(holder[0], f, context(e, List.of()), f.track(), JunctionContext.empty(), TraceBudgets.defaults());
        assertThrows(java.util.concurrent.CancellationException.class, () -> holder[0].prepareGapDomain(d, 7.01));
        var work = holder[0].preparedWork().gapWork().orElseThrow();
        assertEquals(1, work.pointChecks()); assertEquals(1, work.addedStates());
        assertEquals(1, work.edgeChecks()); assertEquals(1, work.rejectedEdges());
        assertEquals(2, work.admissionUnits());
    }

    @Test void invalidInterpolationCellAndPhysicalRadiusRejectAdditionalPointWithoutClamping() {
        var f = fixture(); var e = f.evidence(); var field = e.fields().get("native");
        var valid = field.copiedValidity(); valid[19 * field.width() + 22] = false;
        var invalid = new EvidenceSnapshot(e.snapshotId(), e.coordinateFrame(), e.transform(), e.resolution(), e.decisionRegion(), e.evidenceRegion(),
                Map.of("native", new ScalarEvidenceField(field.width(), field.height(), field.copiedValues(), valid, field.lineage())), e.sourceIdentity());
        for (boolean cell : List.of(false, true)) {
            var optimizer = guarded();
            var d = original(optimizer, f, context(cell ? invalid : e, List.of()), f.track(), JunctionContext.empty(), TraceBudgets.defaults());
            var expanded = optimizer.prepareGapDomain(d, cell ? 7.01 : .1);
            assertEquals(List.of(.5), expanded.states().get(12));
            assertEquals(cell ? 1 : 0, expanded.gapPlan().orElseThrow().work().pointChecks());
        }
    }

    @Test void shouldersProjectAcrossRotatedSectionsRatherThanUsingBoundaryCenterHull() {
        var f = fixture(); var profiles = new ArrayList<>(f.profiles()); double c = Math.sqrt(.5);
        for (int row : List.of(11, 13)) {
            var p = profiles.get(row); var s = p.source();
            var source = new RenderedHeatmapSampler.CrossSectionProfile(s.samplingAnchor(), new Point2D.Double(c, c), s.peaks(), true, s.intensitySamples());
            profiles.set(row, new CorridorProfile(row, source, p.bands(), p.maxIntensity(), p.noiseFloor(), p.prominence(), p.supported()));
        }
        var optimizer = guarded();
        var original = optimizer.prepareOriginalDomain(f.track(), profiles, 1, JunctionContext.empty(), Map.of(), f.tube(), TraceBudgets.defaults(), f.context());
        var d = optimizer.prepareGapDomain(original, 7.01); var row = d.gapPlan().orElseThrow().work().rows().get(0);
        assertEquals(-1.5 * c, row.envelopeMin(), 1e-8); assertEquals(1.5 * c, row.envelopeMax(), 1e-8);
        assertTrue(d.states().get(12).contains(-.5)); // Both observed centers are+.5.
    }

    @Test void rotatedAndAnisotropicFramesUsePhysicalNormalDisplacement() {
        for (var transform : List.of(RasterMetricTransform.metricGrid(new MetricPoint(100, 100), 0, 1.5, -1.5, 0),
                RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1.5, 0, 0, 3))) {
            var f = fixture(); var old = f.evidence();
            var region = new MetricRegion(List.of(List.of(transform.pixelCenterToMetric(0, 0), transform.pixelCenterToMetric(63, 0),
                    transform.pixelCenterToMetric(63, 47), transform.pixelCenterToMetric(0, 47))));
            var evidence = new EvidenceSnapshot(old.snapshotId(), old.coordinateFrame(), transform, old.resolution(), region, region, old.fields(), old.sourceIdentity());
            var profiles = new ArrayList<CorridorProfile>();
            for (var p : f.profiles()) {
                var s = p.source(); var a = s.anchorScreen();
                var anchor = DetachedProfileSamplingLocation.at(old.coordinateFrame().toGeographic(transform.pixelCenterToMetric(a.x, a.y)),
                        old.coordinateFrame(), transform, s.cumulativeGroundDistanceMeters());
                var source = new RenderedHeatmapSampler.CrossSectionProfile(anchor, new Point2D.Double(0, 1), s.peaks(), true, s.intensitySamples());
                profiles.add(new CorridorProfile(p.index(), source, p.bands(), p.maxIntensity(), p.noiseFloor(), p.prominence(), p.supported()));
            }
            var optimizer = new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(transform, CancellationProbe.NONE);
            var original = optimizer.prepareOriginalDomain(f.track(), profiles, 1, JunctionContext.empty(), Map.of(), f.tube(), TraceBudgets.defaults(), context(evidence, List.of()));
            assertEquals(List.of(-.5, .5), optimizer.prepareGapDomain(original, 7.01).states().get(12));
            if (transform.pixelCenterToMetric(0, 0).distanceTo(transform.pixelCenterToMetric(0, 1)) == 3)
                assertEquals(List.of(.5), optimizer.prepareGapDomain(original, 1.0).states().get(12));
        }
    }

    private static double ripple(List<Double> values) {
        double result = 0;
        for (int i = 2; i < values.size(); i++) result += Math.pow(values.get(i) - 2 * values.get(i - 1) + values.get(i - 2), 2);
        return result;
    }

    static CorridorCenterlineOptimizer guarded() { return new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(FRAME, CancellationProbe.NONE); }
    static CorridorCenterlineOptimizer.OriginalDomain original(CorridorCenterlineOptimizer optimizer, Fixture f,
            CorridorWorkingSchedule.BoundContext context, CorridorTrack track, JunctionContext junctions, TraceBudgets budgets) {
        return optimizer.prepareOriginalDomain(track, f.profiles(), 1, junctions, Map.of(), f.tube(), budgets, context);
    }
    static CorridorWorkingSchedule.BoundContext context(EvidenceSnapshot evidence, List<Double> protectedRows) {
        return CorridorWorkingSchedule.bindContext("analytic-r4-request", "analytic-network", evidence, "native",
                new CorridorWorkingSchedule.SourceProtection(0, 43.5, protectedRows), "analytic-source-proof", CancellationProbe.NONE);
    }

    static Fixture fixture() {
        return fixture(12);
    }
    static Fixture fixture(int gap) {
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50, 14),
                new GeographicPoint(49.99, 13.99), new GeographicPoint(50.01, 14.02));
        int width = 64, height = 48;
        var values = new double[width * height]; var valid = new boolean[values.length]; Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            values[y * width + x] = Math.exp(-.5 * Math.pow((y - 19.5) / .6, 2));
        var field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "native", EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        var evidence = new EvidenceSnapshot("analytic-gap", frame, FRAME, EvidenceResolution.nativeSource(1.5, 1.5),
                MetricRegion.rectangle(0, 0, 94, 70), MetricRegion.rectangle(0, 0, 94, 70), Map.of("native", field), "analytic-gap-image");
        var profiles = new ArrayList<CorridorProfile>(); var points = new LinkedHashMap<Integer, CorridorTrackPoint>();
        var slices = new ArrayList<CorridorTubeSlice>();
        for (int i = 0; i < 30; i++) {
            boolean broad = i == gap - 1 || i == gap + 1; double center = broad ? .5 : -.5;
            var band = new CorridorBand("b" + i, center, broad ? -1.5 : -.8, broad ? 1.5 : -.2,
                    center - .2, center + .2, List.of(center), .9, .01, 0, .9, .9, .2, false, List.of());
            var anchor = DetachedProfileSamplingLocation.at(frame.toGeographic(FRAME.pixelCenterToMetric(10 + i, 20)),
                    frame, FRAME, i * 1.5);
            var samples = new ArrayList<RenderedHeatmapSampler.IntensitySample>();
            for (double offset : List.of(-1.5, -.8, -.5, -.2, .3, .5, .7, 1.5)) {
                double value = Math.exp(-.5 * Math.pow((offset + .5) / .6, 2));
                samples.add(new RenderedHeatmapSampler.IntensitySample(offset, value, value, value, true));
            }
            var source = new RenderedHeatmapSampler.CrossSectionProfile(anchor, new Point2D.Double(0, 1), List.of(), true, samples);
            profiles.add(new CorridorProfile(i, source, i == gap ? List.of() : List.of(band), .9, .01, .89, true));
            if (i != gap) points.put(i, new CorridorTrackPoint(i, band, i == gap + 1));
            slices.add(new CorridorTubeSlice(i, i * 1.5, -.5, 0, 0, -.7, -.3, -1.5, 1.5, .2, .9,
                    false, false, -.5, -.5, -.5, i != gap));
        }
        var context = CorridorWorkingSchedule.bindContext("analytic-r4-request", "analytic-network", evidence, "native",
                new CorridorWorkingSchedule.SourceProtection(0, 43.5, List.of()), "analytic-source-proof", CancellationProbe.NONE);
        return new Fixture(new CorridorTrack("analytic-gap-track", points, 1, 29.0 / 30, false, List.of(), ""),
                List.copyOf(profiles), new LongitudinalCorridorTube(slices), evidence, context);
    }
}
