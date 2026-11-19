package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorDirectPositionCostPolicyTest.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorCenterlineOptimizer.OriginalCostPolicy.*;
import java.util.*;
import java.lang.reflect.Method;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorDirectPositionCostPolicyTest.Fixture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorCenterlineOptimizer.OriginalCostPolicy;
import org.junit.jupiter.api.Test;

/** Native supplied-offset regression for the independently reviewed R7 measured-target formula. */
class CorridorMeasuredCenterCostPolicyTest {
    @Test void measuredModeRemovesOnlyTheR6SyntheticPlateauTargetPenalty() throws Exception {
        var f = fixed(.12, 0, false); var optimizer = new CorridorCenterlineOptimizer();
        var point = f.track().points().get(0); var band = point.band();
        assertEquals(CorridorPointSupport.DIRECT_UNION, point.support()); assertTrue(band.hasMeasuredCenter());
        assertEquals(.75, band.centerOffsetPx()); assertEquals(.75, (band.coreMinPx() + band.coreMaxPx()) / 2);
        assertEquals(0., f.tube().at(0).motionSupport()); assertEquals(.09, band.peakIntensity() - band.noiseFloor(), 1e-15);
        assertEquals(.12, f.profiles().get(0).source().intensitySamples().stream().mapToDouble(s -> s.nativeIntensity()).max().orElseThrow());
        var r6 = unary(optimizer, f, 0, .75, Map.of(), DIRECT_POSITION_V1);
        assertEquals(.259875, number(r6, "plateauCenterCost"), 1e-12);
        assertEquals(.320203125, number(r6, "total"), 1e-12);
        double expected = number(r6, "total") - number(r6, "plateauCenterCost");
        assertEquals(.060328125, expected, 1e-12);
        var r7 = unary(optimizer, f, 0, .75, Map.of(), DIRECT_MEASURED_CORE_V2);
        assertEquals(expected, number(r7, "total"), 1e-12);
        assertEquals(0., number(r7, "plateauCenterCost"));
        for (String name : List.of("tubeCenterCost", "coarsePriorCost", "rippleAdditionalCost"))
            assertEquals(number(r6, name), number(r7, name));
    }

    @Test void asymmetricMeasuredModeIsNotCoreMidpointEvenForStrongMotionOne() throws Exception {
        for (double peak : List.of(.12, .8)) for (double motion : List.of(0., 1.)) {
            var f = variant(fixed(peak, motion, false), true, CorridorBand.BoundaryCompleteness.COMPLETE, CorridorPointSupport.DIRECT_UNION);
            var b = f.track().points().get(0).band(); assertEquals(.75, b.centerOffsetPx());
            assertEquals(1., (b.coreMinPx() + b.coreMaxPx()) / 2);
            var optimizer = new CorridorCenterlineOptimizer();
            var r6 = unary(optimizer, f, 0, .75, Map.of(), DIRECT_POSITION_V1);
            var r7 = unary(optimizer, f, 0, .75, Map.of(), DIRECT_MEASURED_CORE_V2);
            assertEquals(motion == 1 ? .028875 : .259875, number(r6, "plateauCenterCost"), 1e-12);
            assertEquals(0., number(r7, "plateauCenterCost"), "Measured mode, not midpoint, owns position");
            assertEquals(number(r6, "total") - number(r6, "plateauCenterCost"), number(r7, "total"), 1e-12);
            assertEquals(number(r6, "tubeCenterCost"), number(r7, "tubeCenterCost"));
            assertEquals(unary(optimizer, f, 0, 2., Map.of(), DIRECT_POSITION_V1),
                unary(optimizer, f, 0, 2., Map.of(), DIRECT_MEASURED_CORE_V2), "Non-equivalent measured branch was already factual");
        }
    }

    @Test void fixedOffsetsOnBothEquivalentThresholdSidesRetainIntensityAndDeadbandRules() throws Exception {
        var f = fixed(.12, 0, false); var optimizer = new CorridorCenterlineOptimizer();
        for (double offset : List.of(.75, 1., 1.25, 2.)) {
            var r6 = unary(optimizer, f, 0, offset, Map.of(), DIRECT_POSITION_V1);
            var r7 = unary(optimizer, f, 0, offset, Map.of(), DIRECT_MEASURED_CORE_V2);
            boolean equivalent = offset <= 1.;
            assertEquals(equivalent, number(r6, "plateauCenterCost") > 0);
            if (equivalent) {
                double center = Math.pow(Math.max(0, Math.abs(offset - .75) - .25), 2) * .462;
                assertEquals(center, number(r7, "plateauCenterCost"), 1e-12);
                assertEquals(number(r6, "total") - number(r6, "plateauCenterCost") + center, number(r7, "total"), 1e-12);
            } else assertEquals(r6, r7);
        }
    }

    @Test void censorInterpolationAndBandlessRolesDoNotAcquireMeasuredAuthority() throws Exception {
        var optimizer = new CorridorCenterlineOptimizer();
        for (var completeness : CorridorBand.BoundaryCompleteness.values())
            for (var support : CorridorPointSupport.values()) {
                var f = variant(fixed(.12, 0, false), false, completeness, support);
                var r6 = unary(optimizer, f, 0, .75, Map.of(), DIRECT_POSITION_V1);
                var r7 = unary(optimizer, f, 0, .75, Map.of(), DIRECT_MEASURED_CORE_V2);
                if (support == CorridorPointSupport.DIRECT_UNION && completeness.hasMeasuredCenter()) {
                    assertEquals(0., number(r7, "plateauCenterCost"));
                    assertEquals(number(r6, "total") - number(r6, "plateauCenterCost"), number(r7, "total"), 1e-12);
                } else assertEquals(r6, r7, "Unmeasured/predicted rows retain every R6 component");
            }
        var bandless = fixed(.12, 0, true);
        assertEquals(unary(optimizer, bandless, 0, .75, Map.of(), DIRECT_POSITION_V1),
            unary(optimizer, bandless, 0, .75, Map.of(), DIRECT_MEASURED_CORE_V2));
    }

    @Test void nonzeroCoarseRippleTubeAndShoulderTermsRemainR6Exact() throws Exception {
        var f = fixed(.12, 0, false); var optimizer = new CorridorCenterlineOptimizer();
        var scales = Map.of(CorridorCenterlineOptimizer.scaleEvidenceKey(0, f.track().points().get(0).band().id()),
            new BandScaleEvidence(.8, -1., .7, List.of(0, 1), false, false));
        var ripple = new UnsupportedRippleEvaluator.RippleSupport(0, .2, .2, .8, .4, .6, 1., -.5, 0., .1, .5, .8, 2., 2, "synthetic-measured-unary");
        for (double offset : List.of(.75, 2.)) {
            var r6 = unary(optimizer, f, 0, offset, scales, DIRECT_POSITION_V1, ripple, .7);
            var r7 = unary(optimizer, f, 0, offset, scales, DIRECT_MEASURED_CORE_V2, ripple, .7);
            assertTrue(number(r6, "coarsePriorCost") > 0); assertTrue(number(r6, "rippleAdditionalCost") > 0);
            for (String term : List.of("coarsePriorCost", "tubeCenterCost", "rippleAdditionalCost"))
                assertEquals(number(r6, term), number(r7, term));
            if (offset == .75) assertEquals(number(r6, "total") - number(r6, "plateauCenterCost"), number(r7, "total"), 1e-12);
            else assertEquals(r6, r7, "Non-equivalent score includes the same nonzero shoulder penalty");
        }
    }

    @Test void actualMixedParentTargetsEnvelopeWithoutTurningPredictionsIntoEvidence() throws Exception {
        var fixture = CorridorGroupingTest.class.getDeclaredMethod("predictionSignalScenario"); fixture.setAccessible(true);
        var scenario = fixture.invoke(new CorridorGroupingTest());
        var tracksMethod = scenario.getClass().getDeclaredMethod("tracks"); tracksMethod.setAccessible(true);
        var profilesMethod = scenario.getClass().getDeclaredMethod("profiles"); profilesMethod.setAccessible(true);
        @SuppressWarnings("unchecked") var children = (List<CorridorTrack>) tracksMethod.invoke(scenario);
        @SuppressWarnings("unchecked") var profiles = (List<CorridorProfile>) profilesMethod.invoke(scenario);
        var childrenBefore = children.stream().map(t -> Map.copyOf(t.points())).toList();
        var grouped = new CorridorGrouping().group(children, profiles);
        var parent = grouped.tracks().stream().filter(CorridorTrack::parent).findFirst().orElseThrow();
        var bundle = grouped.bundles().stream().filter(b -> b.id().equals(parent.id())).findFirst().orElseThrow();
        assertEquals(1, bundle.points().get(1).directContributorTrackIds().size());
        assertEquals(1, bundle.points().get(1).predictedContributorTrackIds().size());
        assertTrue(bundle.points().get(2).directContributorTrackIds().isEmpty());
        assertEquals(2, bundle.points().get(2).predictedContributorTrackIds().size());
        assertEquals(CorridorPointSupport.DIRECT_UNION, parent.points().get(1).support());
        assertEquals(.20, parent.points().get(1).band().peakIntensity(), 1e-12);
        assertEquals(CorridorPointSupport.BOUNDED_INTERPOLATION, parent.points().get(2).support());
        assertEquals(0., parent.points().get(2).band().peakIntensity());
        assertEquals(0., parent.points().get(2).band().signalExistenceConfidence());
        var slices = new ArrayList<CorridorTubeSlice>();
        for (int row = 0; row < profiles.size(); row++) {
            var b = parent.points().get(row).band(); double center = b.centerOffsetPx();
            slices.add(new CorridorTubeSlice(row, profiles.get(row).source().cumulativeGroundDistanceMeters(),
                center - 2, 0, center, 0, center - 2, 0, 1, 0, "SYNTHETIC_PARENT_REFERENCE", 0,
                b.coreMinPx(), b.coreMaxPx(), b.shoulderMinPx(), b.shoulderMaxPx(), 1, .6, false, false, center, center, center,
                parent.points().get(row).support() == CorridorPointSupport.DIRECT_UNION));
        }
        var f = new Fixture(parent, profiles, new LongitudinalCorridorTube(slices)); var optimizer = new CorridorCenterlineOptimizer();
        var b = parent.points().get(1).band(); assertTrue(b.hasMeasuredCenter()); assertTrue(b.parentHypothesis());
        double offset = 2.; // Actual fixture's bright predicted child; not the parent's measured envelope center.
        assertNotEquals(offset, b.centerOffsetPx());
        var r6 = unary(optimizer, f, 1, offset, Map.of(), DIRECT_POSITION_V1);
        var r7 = unary(optimizer, f, 1, offset, Map.of(), DIRECT_MEASURED_CORE_V2);
        double oldBase = baseCenter(b, offset, b.centerOffsetPx() - 2, .25);
        double newBase = baseCenter(b, offset, b.centerOffsetPx(), .25);
        double parentPenalty = (parent.groupingDecision().equals("combined") ? .90 : .45)
            * Math.pow((offset - b.centerOffsetPx()) / Math.max(1, Math.min(1.5, b.uncertaintyPx())), 2);
        assertTrue(oldBase > 0); assertTrue(parentPenalty > 0);
        assertEquals(oldBase + parentPenalty, number(r6, "plateauCenterCost"), 1e-12);
        assertEquals(newBase + parentPenalty, number(r7, "plateauCenterCost"), 1e-12);
        assertEquals(number(r6, "total") - oldBase + newBase, number(r7, "total"), 1e-12);
        assertEquals(unary(optimizer, f, 2, offset, Map.of(), DIRECT_POSITION_V1),
            unary(optimizer, f, 2, offset, Map.of(), DIRECT_MEASURED_CORE_V2), "All-predicted parent remains R6-exact");
        assertEquals(childrenBefore, children.stream().map(t -> Map.copyOf(t.points())).toList());
        assertEquals(.20, parent.points().get(1).band().peakIntensity()); assertEquals(0., parent.points().get(2).band().peakIntensity());
    }

    @Test void interleavedImmutableDomainsAndFinalRowsRetainTheirOwnPolicies() throws Exception {
        var f = variant(fixed(.12, 0, false), true, CorridorBand.BoundaryCompleteness.COMPLETE, CorridorPointSupport.DIRECT_UNION);
        var optimizer = new CorridorCenterlineOptimizer(); var historical = original(optimizer, f);
        var refusal = CorridorWorkingSchedule.refusal("REFUSED_TEST_ELIGIBILITY");
        var r6 = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(historical, refusal, DIRECT_POSITION_V1);
        var r7 = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(historical, refusal, DIRECT_MEASURED_CORE_V2);
        var copy = CorridorCenterlineOptimizer.withBudgets(CorridorCenterlineOptimizer.withRetainedCostPolicyBytes(r7, 128), r7.budgets());
        assertSame(historical.states(), copy.states()); assertSame(historical.token(), copy.token()); assertSame(historical.context(), copy.context());
        assertSame(historical.tube(), copy.tube()); assertSame(historical.approaches(), copy.approaches());
        assertEquals(DIRECT_MEASURED_CORE_V2, copy.costPolicy()); assertEquals(128, copy.retainedCostPolicyBytes());
        var old = optimizer.optimizeOriginalDomain(historical); var six = optimizer.optimizeOriginalDomain(r6); var seven = optimizer.optimizeOriginalDomain(copy);
        assertNotEquals(six.totalCost(), seven.totalCost());
        assertEquals(six, optimizer.optimizeOriginalDomain(r6)); assertEquals(old, optimizer.optimizeOriginalDomain(historical));
        assertEquals(six.transitionEvaluations(), seven.transitionEvaluations()); assertEquals(six.profileCostEvaluations(), seven.profileCostEvaluations());
        assertEquals(six.maximumOffsetStates(), seven.maximumOffsetStates()); assertEquals(six.adjacentGeometryEntries(), seven.adjacentGeometryEntries());
        assertFinalRows(optimizer, f, seven);
        var tableMethod = Arrays.stream(CorridorCenterlineOptimizer.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("buildStateTables") && m.getParameterCount() == 12).findFirst().orElseThrow();
        tableMethod.setAccessible(true);
        var ripple = java.util.stream.IntStream.range(0, f.profiles().size()).mapToObj(UnsupportedRippleEvaluator.RippleSupport::disabled).toList();
        var gates = new double[f.profiles().size()]; Arrays.fill(gates, 1.);
        var tables = (List<?>) tableMethod.invoke(optimizer, copy.track(), copy.profiles(), copy.states(), 1., copy.junctions(), copy.scales(),
            copy.tube(), copy.approaches(), ripple, gates, 0., DIRECT_MEASURED_CORE_V2);
        int mode = copy.states().get(0).indexOf(.75); assertTrue(mode >= 0);
        assertEquals(number(unary(optimizer, f, 0, .75, Map.of(), DIRECT_MEASURED_CORE_V2), "total"),
            number(((List<?>) tables.get(0)).get(mode), "profileCost"), 1e-12);
    }

    @Test void gapCopyAndFinalCostsCarryV2WithoutChangingGeometricPlan() throws Exception {
        var base = CorridorGapDomainTest.fixture(); var points = new LinkedHashMap<>(base.track().points());
        var b = points.get(5).band();
        var asymmetric = new CorridorBand(b.id(), b.centerOffsetPx(), b.shoulderMinPx(), b.shoulderMaxPx(), -.55, -.2,
            b.nestedCentersPx(), b.peakIntensity(), b.noiseFloor(), b.valleyRatio(), b.gradientStrength(), b.gradientBalance(), b.scaleAgreement(),
            b.signalExistenceConfidence(), b.localizationConfidence(), b.uncertaintyPx(), b.parentHypothesis(), b.childIds());
        points.put(5, new CorridorTrackPoint(5, asymmetric, false));
        var profiles = new ArrayList<>(base.profiles()); var p = profiles.get(5);
        profiles.set(5, new CorridorProfile(5, p.source(), List.of(asymmetric), p.maxIntensity(), p.noiseFloor(), p.prominence(), p.supported()));
        var track = new CorridorTrack(base.track().id(), points, base.track().score(), base.track().supportRatio(), false, List.of(), "");
        var f = new CorridorGapDomainTest.Fixture(track, profiles, base.tube(), base.evidence(), base.context());
        var optimizer = CorridorGapDomainTest.guarded();
        assertEquals(1., f.tube().at(5).motionSupport()); assertNotEquals(asymmetric.centerOffsetPx(), (asymmetric.coreMinPx() + asymmetric.coreMaxPx()) / 2);
        var supplied = new Fixture(f.track(), f.profiles(), f.tube());
        assertTrue(number(unary(optimizer, supplied, 5, -.5, Map.of(), DIRECT_POSITION_V1), "plateauCenterCost") > 1e-6);
        assertEquals(0., number(unary(optimizer, supplied, 5, -.5, Map.of(), DIRECT_MEASURED_CORE_V2), "plateauCenterCost"));
        var original = CorridorGapDomainTest.original(optimizer, f, f.context(), f.track(), JunctionContext.empty(), TraceBudgets.defaults());
        var gap = optimizer.prepareGapDomain(original, 7.01);
        var r7 = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(gap, CorridorWorkingSchedule.refusal("REFUSED_TEST"), DIRECT_MEASURED_CORE_V2);
        var copy = CorridorCenterlineOptimizer.withBudgets(r7, r7.budgets()); gap.gapPlan().orElseThrow().requireBinding(copy);
        assertSame(gap.gapPlan().orElseThrow(), copy.gapPlan().orElseThrow()); assertSame(gap.states(), copy.states());
        assertEquals(gap.budgets(), copy.budgets()); assertEquals(DIRECT_MEASURED_CORE_V2, copy.costPolicy());
        var result = optimizer.optimizeOriginalDomain(copy); assertFalse(result.offsetsPx().isEmpty());
        assertFinalRows(optimizer, new Fixture(copy.track(), copy.profiles(), copy.tube()), result);
        var late = optimizer.prepareGapDomain(CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(original,
            CorridorWorkingSchedule.refusal("REFUSED_TEST"), DIRECT_MEASURED_CORE_V2), 7.01);
        assertEquals(DIRECT_MEASURED_CORE_V2, late.costPolicy()); assertEquals(gap.states(), late.states());
        assertEquals(result, optimizer.optimizeOriginalDomain(late));
    }

    @Test void actualAdapterBindsR7BeforeGenericR6AndKeepsAdmittedMathematicsExact() throws Exception {
        var plain = CorridorPolicyFixture.straight(true, false, Set.of());
        var six = invokeOwned(withPolicy(plain, CorridorTraceInput.ExecutionPolicy.R6_OWNED_DIRECT_POSITION_COST_V1));
        var sevenFixture = withPolicy(plain, CorridorTraceInput.ExecutionPolicy.R7_OWNED_MEASURED_CORE_COST_V1);
        var input = sevenFixture.request().corridorInput().orElseThrow();
        assertTrue(input.measuredCoreCostPolicy()); assertTrue(input.directPositionCostPolicy()); assertTrue(input.certifiedGapDomainPolicy());
        assertTrue(input.boundedBridgePolicy()); assertFalse(input.observedShoulderPolicy());
        var seven = invokeOwned(sevenFixture); assertEquals("COMPLETE", seven.run().corridorReceipt().orElseThrow().certificateStatus());
        assertEquals(six.tracked().profiles(), seven.tracked().profiles()); assertEquals(six.tracked().tracks(), seven.tracked().tracks());
        assertEquals(six.tracked().tubes(), seven.tracked().tubes()); assertEquals(six.tracked().scaleEvidence(), seven.tracked().scaleEvidence());
        assertTrue(seven.tracker().executedCostPolicies().values().contains(DIRECT_MEASURED_CORE_V2), "Actual non-ADMITTED adapter must not dispatch R6");
        assertTrue(six.tracker().executedCostPolicies().values().contains(DIRECT_POSITION_V1));
        var admitted = CorridorPolicyFixture.admittedParabola();
        var a = invokeOwned(withPolicy(admitted, CorridorTraceInput.ExecutionPolicy.R6_OWNED_DIRECT_POSITION_COST_V1));
        var b = invokeOwned(withPolicy(admitted, CorridorTraceInput.ExecutionPolicy.R7_OWNED_MEASURED_CORE_COST_V1));
        assertTrue(b.tracker().correspondencePreparations().values().stream().anyMatch(CorridorWorkingSchedule.Preparation::admitted));
        assertTrue(b.tracker().executedCostPolicies().values().stream().allMatch(p -> p == HISTORICAL));
        assertEquals(a.run().result(), b.run().result()); assertEquals(a.run().usage(), b.run().usage());
        assertEquals(a.tracked().optimizations(), b.tracked().optimizations());
        assertEquals(a.run().corridorReceipt().orElseThrow().totals(), b.run().corridorReceipt().orElseThrow().totals());
        for (var e : a.tracker().correspondenceWork().entrySet()) {
            var after = b.tracker().correspondenceWork().get(e.getKey());
            assertEquals(e.getValue().preparation(), after.preparation()); assertEquals(e.getValue().hardGeometry(), after.hardGeometry());
        }
    }

    @Test void allNonHistoricalPoliciesRejectR5RestrictionPreparedAndTerminalFallback() throws Exception {
        var f = fixed(.12, 0, false); var optimizer = new CorridorCenterlineOptimizer(); var h = original(optimizer, f);
        var refusal = CorridorWorkingSchedule.refusal("REFUSED_TEST");
        var d = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(h, refusal, DIRECT_MEASURED_CORE_V2);
        assertThrows(IllegalArgumentException.class, () -> optimizer.prepareObservedShoulderDomain(d, Map.of(h.track().id(), h.track()), Optional.empty()));
        var observed = optimizer.prepareObservedShoulderDomain(h, Map.of(h.track().id(), h.track()), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(observed, refusal, DIRECT_MEASURED_CORE_V2));
        var work = optimizer.preparedWork(); assertThrows(IllegalArgumentException.class, () -> optimizer.optimizePrepared(d, null));
        assertEquals(work, optimizer.preparedWork());
        assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(d, refusal, DIRECT_MEASURED_CORE_V2));
        for (String status : List.of("RESOURCE_LIMIT", "REFUSED_NATIVE_FRAME_BINDING", "CANCELLED")) {
            var ctor = CorridorWorkingSchedule.Preparation.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
            var terminal = (CorridorWorkingSchedule.Preparation) ctor.newInstance(status, null, null, 0L, 0L, 0L, 0L);
            assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(h, terminal, DIRECT_MEASURED_CORE_V2));
            var tracker = CorridorAwareTracker.withTargetOwnedMeasuredCoreCostDomainWithoutAuthority(optimizer, ignored -> terminal);
            assertThrows(RuntimeException.class, () -> track(tracker, f.profiles(), TraceBudgets.defaults()));
            assertTrue(tracker.executedCostPolicies().isEmpty()); assertEquals(0, optimizer.preparedWork().solverStarts());
        }
        var cancelled = CorridorAwareTracker.withTargetOwnedMeasuredCoreCostDomainWithoutAuthority(optimizer,
            ignored -> { throw new java.util.concurrent.CancellationException(); });
        assertThrows(java.util.concurrent.CancellationException.class, () -> track(cancelled, f.profiles(), TraceBudgets.defaults()));
        assertTrue(cancelled.executedCostPolicies().isEmpty());
        var limited = CorridorCenterlineOptimizer.withBudgets(d, new TraceBudgets(1, 8000000, 128000000, 32, 8));
        assertThrows(CorridorResourceLimitException.class, () -> optimizer.optimizeOriginalDomain(limited));
        assertEquals(0, optimizer.preparedWork().solverStarts());
    }

    @Test void r7ReusedPublicEntryFailuresNeverExposePreviousPolicyMetadata() {
        var profiles = fixed(.8, 0, false).profiles().stream().map(CorridorProfile::source).toList();
        var set = oneScale(profiles);
        for (int mode = 0; mode < 3; mode++) {
            var tracker = CorridorAwareTracker.withTargetOwnedMeasuredCoreCostDomainWithoutAuthority(new CorridorCenterlineOptimizer(),
                ignored -> CorridorWorkingSchedule.refusal("REFUSED_TEST"));
            if (mode == 0) tracker.trackDetailed(profiles, 1., JunctionContext.empty(), "native", GeometryCleanupConfig.disabled(), 1.);
            else if (mode == 1) tracker.trackDetailed(set, 1., JunctionContext.empty(), "native", GeometryCleanupConfig.disabled(), 1.);
            else tracker.trackDetailed(set, 1., JunctionContext.empty(), "native", GeometryCleanupConfig.disabled(), 1., TraceBudgets.defaults());
            assertTrue(tracker.executedCostPolicies().values().contains(DIRECT_MEASURED_CORE_V2));
            if (mode == 0) assertThrows(IllegalArgumentException.class, () -> tracker.trackDetailed(List.of(profiles.get(1), profiles.get(0)), 1.));
            else if (mode == 1) assertThrows(IllegalArgumentException.class, () -> tracker.trackDetailed(malformedScales(profiles).get(1), 1., JunctionContext.empty()));
            else assertThrows(IllegalArgumentException.class, () -> tracker.trackDetailed(set, 1., JunctionContext.empty(), "native", GeometryCleanupConfig.disabled(), 1., null));
            assertTrue(tracker.executedCostPolicies().isEmpty()); assertEquals(0, tracker.executedCostPolicyMetadataBytes());
        }
    }

    private static Fixture variant(Fixture f, boolean asymmetric, CorridorBand.BoundaryCompleteness completeness, CorridorPointSupport support) {
        var points = new LinkedHashMap<Integer,CorridorTrackPoint>(); var profiles = new ArrayList<CorridorProfile>();
        for (var profile : f.profiles()) {
            var p = f.track().points().get(profile.index()); var b = p.band();
            var band = new CorridorBand(b.id(), b.centerOffsetPx(), b.shoulderMinPx(), b.shoulderMaxPx(), b.coreMinPx(),
                asymmetric ? 1.5 : b.coreMaxPx(), b.nestedCentersPx(), b.peakIntensity(), b.noiseFloor(), b.valleyRatio(), b.gradientStrength(),
                b.gradientBalance(), b.scaleAgreement(), b.signalExistenceConfidence(), b.localizationConfidence(), b.uncertaintyPx(),
                b.parentHypothesis(), b.childIds(), completeness, completeness == CorridorBand.BoundaryCompleteness.COMPLETE ? CorridorBand.BoundarySide.NONE
                    : completeness == CorridorBand.BoundaryCompleteness.FULLY_CENSORED ? CorridorBand.BoundarySide.BOTH : CorridorBand.BoundarySide.RIGHT);
            points.put(profile.index(), new CorridorTrackPoint(profile.index(), band, p.bridged(), support));
            profiles.add(new CorridorProfile(profile.index(), profile.source(), List.of(band), profile.maxIntensity(), profile.noiseFloor(), profile.prominence(), profile.supported()));
        }
        return new Fixture(new CorridorTrack(f.track().id(), points, f.track().score(), f.track().supportRatio(), f.track().parent(),
            f.track().childTrackIds(), f.track().groupingDecision()), List.copyOf(profiles), f.tube());
    }
    private static double baseCenter(CorridorBand band, double offset, double target, double deadband) {
        return Math.pow(Math.max(0, Math.abs(offset - target) - deadband) / Math.max(1, band.uncertaintyPx()), 2) * (.12 + .38 * band.localizationConfidence());
    }
    private static void assertFinalRows(CorridorCenterlineOptimizer optimizer, Fixture f, CorridorCenterlineOptimizer.OptimizationResult result) throws Exception {
        for (var row : result.costs()) {
            var nativeCost = unary(optimizer, f, row.profileIndex(), row.chosenOffsetPx(), Map.of(), DIRECT_MEASURED_CORE_V2);
            assertEquals(number(nativeCost, "total"), row.dataCost(), 1e-12);
            assertEquals(number(nativeCost, "plateauCenterCost"), row.plateauCenterCost(), 1e-12);
            assertEquals(number(nativeCost, "tubeCenterCost"), row.tubeCenterCost(), 1e-12);
        }
        assertEquals(result.costs().stream().mapToDouble(CorridorCenterlineOptimizer.CostRow::weightedTotal).sum(), result.totalCost(), 1e-12);
    }
}
