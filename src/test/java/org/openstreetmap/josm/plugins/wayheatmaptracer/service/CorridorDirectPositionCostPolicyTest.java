package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorCenterlineOptimizer.OriginalCostPolicy.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import java.awt.geom.Point2D;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

/** Fixed supplied offsets test the native unary; no selection-dependent arithmetic fixture. */
class CorridorDirectPositionCostPolicyTest {
    record Fixture(CorridorTrack track, List<CorridorProfile> profiles, LongitudinalCorridorTube tube) { }
    @Test void directUnaryOmitsOnlyAnalyticallyNonzeroAmplification() throws Exception {
        var f = fixed(.12, 0, false);
        var optimizer = new CorridorCenterlineOptimizer();
        Object historical = unary(optimizer, f, .75);
        double factor = 1 + 800 * ((.35 - (.12 - .03)) / .30);
        double base = CorridorOptimizationParameters.defaults().tubeCenterWeight() * .6
            * (.25 + .75 * (1 - .9)) * Math.pow((1 - .25) / 1, 2);
        assertTrue(base > .001);
        assertEquals(base * factor, number(historical, "tubeCenterCost"), 1e-12);
        double expected = number(historical, "total") - base * (factor - 1);
        assertTrue(number(historical, "total") - expected > 1);
        Object direct = unary(optimizer, f, 0, .75, Map.of(), DIRECT_POSITION_V1);
        assertEquals(expected, number(direct, "total"), 1e-10);
        assertEquals(base, number(direct, "tubeCenterCost"), 1e-12);
        for (String member : List.of("plateauCenterCost", "coarsePriorCost", "rippleAdditionalCost"))
            assertEquals(number(historical, member), number(direct, member));
    }
    @Test void saturatedMotionProminenceAndBandlessCostsRemainExact() throws Exception {
        var optimizer = new CorridorCenterlineOptimizer();
        for (var f : List.of(fixed(.12, 1, false), fixed(.8, 0, false), fixed(.12, 0, true)))
            for (double offset : List.of(-.5, .75, 2.0))
                assertEquals(unary(optimizer, f, offset), unary(optimizer, f, 0, offset, Map.of(), DIRECT_POSITION_V1));
    }

    @Test void immutableCopiesPreserveOriginalStateIdentityAndInterleavedScoring() throws Exception {
        var f = fixed(.12, 0, false); var optimizer = new CorridorCenterlineOptimizer();
        var historical = original(optimizer, f);
        var old = optimizer.optimizeOriginalDomain(historical);
        var direct = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(historical,
            CorridorWorkingSchedule.refusal("REFUSED_TEST_ELIGIBILITY"), DIRECT_POSITION_V1);
        assertSame(historical.states(), direct.states());
        assertSame(historical.token(), direct.token()); assertSame(historical.context(), direct.context());
        assertSame(historical.tube(), direct.tube()); assertSame(historical.approaches(), direct.approaches());
        assertEquals(HISTORICAL, historical.costPolicy()); assertEquals(DIRECT_POSITION_V1, direct.costPolicy());
        var copy = CorridorCenterlineOptimizer.withBudgets(direct, direct.budgets());
        assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(copy,
            CorridorWorkingSchedule.refusal("REFUSED_TEST"), DIRECT_POSITION_V1));
        assertThrows(IllegalArgumentException.class, () -> optimizer.prepareObservedShoulderDomain(copy, Map.of(copy.track().id(), copy.track()), Optional.empty()));
        assertSame(direct.states(), copy.states()); assertEquals(DIRECT_POSITION_V1, copy.costPolicy());
        var current = optimizer.optimizeOriginalDomain(copy);
        assertNotEquals(old.offsetsPx(), current.offsetsPx(), "Unary policy must reach the existing no-gap exact DP");
        assertEquals(old, optimizer.optimizeOriginalDomain(historical), "Interleaving cannot change the historical solve");
        assertEquals(current, optimizer.optimizeOriginalDomain(direct));
        assertEquals(old.transitionEvaluations(), current.transitionEvaluations());
        assertEquals(old.profileCostEvaluations(), current.profileCostEvaluations());
        assertEquals(old.maximumOffsetStates(), current.maximumOffsetStates());
        for (var row : current.costs()) {
            var nativeCost = unary(optimizer, f, row.profileIndex(), row.chosenOffsetPx(), Map.of(), DIRECT_POSITION_V1);
            assertEquals(number(nativeCost, "total"), row.dataCost(), 1e-12);
            assertEquals(number(nativeCost, "tubeCenterCost"), row.tubeCenterCost(), 1e-12);
        }
        assertEquals(current.costs().stream().mapToDouble(CorridorCenterlineOptimizer.CostRow::weightedTotal).sum(), current.totalCost(), 1e-12);
    }

    @Test void gapAndBudgetCopiesRetainGeometricPlanAndPolicy() throws Exception {
        var f = CorridorGapDomainTest.fixture(); var optimizer = CorridorGapDomainTest.guarded();
        var historical = CorridorGapDomainTest.original(optimizer, f, f.context(), f.track(), JunctionContext.empty(), TraceBudgets.defaults());
        var gap = optimizer.prepareGapDomain(historical, 7.01);
        var direct = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(gap,
            CorridorWorkingSchedule.refusal("REFUSED_TEST_ELIGIBILITY"), DIRECT_POSITION_V1);
        assertSame(gap.gapPlan().orElseThrow(), direct.gapPlan().orElseThrow());
        assertSame(gap.states(), direct.states()); assertSame(gap.token(), direct.token());
        assertSame(gap.context(), direct.context()); assertEquals(gap.budgets(), direct.budgets());
        var copy = CorridorCenterlineOptimizer.withBudgets(direct, direct.budgets());
        direct.gapPlan().orElseThrow().requireBinding(copy);
        var result = optimizer.optimizeOriginalDomain(copy);
        for (var row : result.costs()) {
            var input = new Fixture(copy.track(), copy.profiles(), copy.tube());
            assertEquals(number(unary(optimizer, input, row.profileIndex(), row.chosenOffsetPx(), Map.of(), DIRECT_POSITION_V1), "total"),
                row.dataCost(), 1e-12);
        }
        var attachedBeforeGap = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(historical,
            CorridorWorkingSchedule.refusal("REFUSED_TEST_ELIGIBILITY"), DIRECT_POSITION_V1);
        var lateGap = optimizer.prepareGapDomain(attachedBeforeGap, 7.01);
        assertEquals(DIRECT_POSITION_V1, lateGap.costPolicy()); assertEquals(gap.states(), lateGap.states());
        assertEquals(result, optimizer.optimizeOriginalDomain(lateGap));
    }

    @Test void gapFinalRowsUseTheSamePolicyAtAnAnalyticallyNonzeroForcedOffset() throws Exception {
        var f = CorridorGapDomainTest.fixture(); var points = new LinkedHashMap<Integer,CorridorTrackPoint>();
        f.track().points().forEach((i,p) -> {
            var b = p.band();
            var weak = new CorridorBand(b.id(), b.centerOffsetPx(), b.shoulderMinPx(), b.shoulderMaxPx(), b.coreMinPx(), b.coreMaxPx(),
                b.nestedCentersPx(), .12, .01, b.valleyRatio(), b.gradientStrength(), b.gradientBalance(), b.scaleAgreement(),
                b.signalExistenceConfidence(), b.localizationConfidence(), b.uncertaintyPx(), b.parentHypothesis(), b.childIds());
            points.put(i, new CorridorTrackPoint(i, weak, p.bridged(), p.support()));
        });
        var track = new CorridorTrack("weak-gap-forced", points, 1, 29. / 30, false, List.of(), "");
        var slices = new ArrayList<>(f.tube().slices()); var s = slices.get(0);
        slices.set(0, new CorridorTubeSlice(s.profileIndex(), s.distanceMeters(), s.centerOffsetPx(), s.tangentOffsetPerMeter(),
            s.localCenterOffsetPx(), s.localTangentOffsetPerMeter(), s.stabilityCenterOffsetPx(), s.stabilityTangentOffsetPerMeter(),
            s.stabilityUncertaintyPx(), 0., "SYNTHETIC_UNSUPPORTED_FIXED_OFFSET", s.curvatureOffsetPerMeterSquared(),
            s.coreMinPx(), s.coreMaxPx(), s.shoulderMinPx(), s.shoulderMaxPx(), s.uncertaintyPx(), s.confidence(),
            s.scaleConflict(), s.parentMerge(), s.rawCenterPx(), s.lightCenterPx(), s.standardCenterPx(), s.observed()));
        var tube = new LongitudinalCorridorTube(slices);
        assertEquals(0., tube.at(0).motionSupport());
        double factor = 1 + 800 * Math.max(0, Math.min(1, (.35 - (.12 - .01)) / .30)) * (1 - tube.at(0).motionSupport());
        assertEquals(641., factor, 1e-12);
        var junctions = new JunctionContext(List.of(new EndpointConstraint(0, 1, true, false, 0, 0, 0)));
        var optimizer = CorridorGapDomainTest.guarded();
        var historical = optimizer.prepareGapDomain(optimizer.prepareOriginalDomain(track, f.profiles(), 1., junctions,
            Map.of(), tube, TraceBudgets.defaults(), f.context()), 7.01);
        var direct = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(historical, CorridorWorkingSchedule.refusal("REFUSED_TEST"), DIRECT_POSITION_V1);
        assertEquals(List.of(0.), direct.states().get(0), "Existing fixed endpoint supplies the offset before inference");
        var result = optimizer.optimizeOriginalDomain(direct); var row = result.costs().get(0);
        assertEquals(0., row.chosenOffsetPx());
        var weight = CorridorCenterlineOptimizer.class.getDeclaredMethod("heatmapWeight", EndpointApproachModel.class, int.class);
        weight.setAccessible(true); double heatmapWeight = (double) weight.invoke(optimizer, direct.approaches(), 0);
        var component = Arrays.stream(CorridorCenterlineOptimizer.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("dataCostComponents") && m.getParameterCount() == 12).findFirst().orElseThrow();
        component.setAccessible(true);
        var directCost = component.invoke(optimizer, track, f.profiles().get(0), 0, 0., 1., Map.of(), tube.at(0),
            heatmapWeight, UnsupportedRippleEvaluator.RippleSupport.disabled(0), 0., 1., DIRECT_POSITION_V1);
        var historicalCost = component.invoke(optimizer, track, f.profiles().get(0), 0, 0., 1., Map.of(), tube.at(0),
            heatmapWeight, UnsupportedRippleEvaluator.RippleSupport.disabled(0), 0., 1., HISTORICAL);
        assertTrue(number(directCost, "tubeCenterCost") > .001);
        assertEquals(factor * number(directCost, "tubeCenterCost"), number(historicalCost, "tubeCenterCost"), 1e-10);
        assertTrue(number(historicalCost, "total") - number(directCost, "total") > 1);
        assertEquals(number(directCost, "total"), row.dataCost(), 1e-12);
        assertEquals(number(directCost, "tubeCenterCost"), row.tubeCenterCost(), 1e-12);
        var tables = Arrays.stream(CorridorCenterlineOptimizer.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("buildStateTables") && m.getParameterCount() == 12).findFirst().orElseThrow();
        tables.setAccessible(true);
        var ripple = java.util.stream.IntStream.range(0, direct.states().size()).mapToObj(UnsupportedRippleEvaluator.RippleSupport::disabled).toList();
        var gates = new double[direct.states().size()]; Arrays.fill(gates, 1);
        var scored = (List<?>) tables.invoke(optimizer, track, direct.profiles(), direct.states(), 1., junctions, Map.of(), direct.tube(),
            direct.approaches(), ripple, gates, 0., DIRECT_POSITION_V1);
        assertEquals(row.dataCost() + row.endpointCost(), number(((List<?>) scored.get(0)).get(0), "profileCost"), 1e-12);
    }

    @Test void attachmentRejectsNullDuplicateAndObservedRestrictionBeforeWork() {
        var f = fixed(.12, 0, false); var optimizer = new CorridorCenterlineOptimizer(); var d = original(optimizer, f);
        var refusal = CorridorWorkingSchedule.refusal("REFUSED_TEST_ELIGIBILITY");
        assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(d, null, DIRECT_POSITION_V1));
        assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(null, refusal, DIRECT_POSITION_V1));
        assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(d, refusal, null));
        var direct = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(d, refusal, DIRECT_POSITION_V1);
        assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(direct, refusal, DIRECT_POSITION_V1));
        var observed = optimizer.prepareObservedShoulderDomain(d, Map.of(d.track().id(), d.track()), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(observed, refusal, DIRECT_POSITION_V1));
        var before = optimizer.preparedWork();
        assertThrows(IllegalArgumentException.class, () -> optimizer.optimizePrepared(direct, null));
        assertEquals(before, optimizer.preparedWork(), "Prepared rejects DIRECT before even resetting work");
    }

    @Test void requestedIdentityRestoresR4GraphAndTrackerMapsAreInvocationLocal() throws Exception {
        var f = fixed(.12, 0, false);
        var input = new CorridorTraceInput(List.of(
            DetachedProfileSamplingLocation.class.cast(CorridorGapDomainTest.fixture().profiles().get(0).source().samplingAnchor()),
            DetachedProfileSamplingLocation.class.cast(CorridorGapDomainTest.fixture().profiles().get(1).source().samplingAnchor())), .25,
            CorridorTraceInput.ExecutionPolicy.R6_OWNED_DIRECT_POSITION_COST_V1, Set.of());
        assertTrue(input.directPositionCostPolicy()); assertTrue(input.boundedBridgePolicy());
        assertTrue(input.certifiedGapDomainPolicy()); assertFalse(input.observedShoulderPolicy());
        var seen = new ArrayList<CorridorCenterlineOptimizer.OriginalDomain>();
        CorridorAwareTracker.PreparedCorrespondencePolicy refuse = d -> {
            assertEquals(HISTORICAL, d.costPolicy()); seen.add(d);
            return CorridorWorkingSchedule.refusal("REFUSED_TEST_ELIGIBILITY");
        };
        var r4 = CorridorAwareTracker.withTargetOwnedGapDomainWithoutAuthority(new CorridorCenterlineOptimizer(), refuse);
        var r6 = CorridorAwareTracker.withTargetOwnedDirectPositionCostDomainWithoutAuthority(new CorridorCenterlineOptimizer(), refuse);
        var first = track(r4, f.profiles(), TraceBudgets.defaults()); var originalStates = seen.get(0).states(); seen.clear();
        var second = track(r6, f.profiles(), TraceBudgets.defaults());
        assertEquals(originalStates, seen.get(0).states());
        assertTrue(r4.executedCostPolicies().isEmpty()); assertFalse(r6.executedCostPolicies().isEmpty());
        assertTrue(r6.executedCostPolicies().values().stream().allMatch(p -> p == DIRECT_POSITION_V1));
        assertEquals(first.tracks(), second.tracks());
        assertEquals(first.optimizations().values().stream().map(CorridorCenterlineOptimizer.OptimizationResult::transitionEvaluations).toList(),
            second.optimizations().values().stream().map(CorridorCenterlineOptimizer.OptimizationResult::transitionEvaluations).toList());
        assertThrows(UnsupportedOperationException.class, () -> r6.executedCostPolicies().clear());
        track(r6, List.of(), TraceBudgets.defaults()); assertTrue(r6.executedCostPolicies().isEmpty());
        assertEquals(first.optimizations(), track(r4, f.profiles(), TraceBudgets.defaults()).optimizations());
    }

    @Test void terminalAdmissionPathsNeverAttachOrStartInference() throws Exception {
        var f = fixed(.12, 0, false);
        for (String status : List.of("RESOURCE_LIMIT", "REFUSED_NATIVE_FRAME_BINDING", "CANCELLED", "UNEXPECTED")) {
            var constructor = CorridorWorkingSchedule.Preparation.class.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            var terminal = (CorridorWorkingSchedule.Preparation) constructor.newInstance(status, null, null, 0L, 0L, 0L, 0L);
            var optimizer = new CorridorCenterlineOptimizer(); var d = original(optimizer, f);
            assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(d, terminal, DIRECT_POSITION_V1));
            var tracker = CorridorAwareTracker.withTargetOwnedDirectPositionCostDomainWithoutAuthority(optimizer, ignored -> terminal);
            assertThrows(RuntimeException.class, () -> track(tracker, f.profiles(), TraceBudgets.defaults()));
            assertTrue(tracker.executedCostPolicies().isEmpty()); assertEquals(0, optimizer.preparedWork().solverStarts());
        }
        var optimizer = new CorridorCenterlineOptimizer();
        var cancelled = CorridorAwareTracker.withTargetOwnedDirectPositionCostDomainWithoutAuthority(optimizer,
            ignored -> { throw new java.util.concurrent.CancellationException(); });
        assertThrows(java.util.concurrent.CancellationException.class, () -> track(cancelled, f.profiles(), TraceBudgets.defaults()));
        assertTrue(cancelled.executedCostPolicies().isEmpty()); assertEquals(0, optimizer.preparedWork().retainedUnaryEvaluations());
    }

    @Test void adapterAdmittedCorrespondenceRemainsHistoricalWithExactNativeOutputAndWork() throws Exception {
        var f = CorridorPolicyFixture.admittedParabola();
        var r4 = invokeOwned(withPolicy(f, CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1));
        var r6 = invokeOwned(withPolicy(f, CorridorTraceInput.ExecutionPolicy.R6_OWNED_DIRECT_POSITION_COST_V1));
        assertEquals(r4.run().result(), r6.run().result()); assertEquals(r4.run().usage(), r6.run().usage());
        assertEquals(r4.tracked().optimizations(), r6.tracked().optimizations());
        assertEquals(r4.run().corridorReceipt().orElseThrow().totals(), r6.run().corridorReceipt().orElseThrow().totals());
        assertEquals(1, r6.tracker().executedCostPolicies().size());
        assertEquals(128, r6.tracker().executedCostPolicyMetadataBytes());
        assertEquals(0, r4.tracker().executedCostPolicyMetadataBytes());
        for (var item : r4.tracker().correspondenceWork().entrySet()) {
            var before = item.getValue(); var after = r6.tracker().correspondenceWork().get(item.getKey());
            assertEquals(withoutReservation(before.preparation()), withoutReservation(after.preparation()));
            assertEquals(withoutReservation(before.hardGeometry()), withoutReservation(after.hardGeometry()));
            long extra = after.hardGeometry().metricCacheReservedBytes() - before.hardGeometry().metricCacheReservedBytes();
            assertEquals(r6.tracker().executedCostPolicyMetadataBytes(), extra, "Combined bytes include exactly the declared retained policy metadata");
            if (before.preparation().estimatedPreparedTableBytes().isPresent())
                assertEquals(extra, after.preparation().estimatedPreparedTableBytes().orElseThrow()
                    - before.preparation().estimatedPreparedTableBytes().orElseThrow());
        }
        var admitted = r6.tracker().correspondencePreparations().entrySet().stream().filter(e -> e.getValue().admitted()).toList();
        assertFalse(admitted.isEmpty(), "Actual native ADMITTED control must execute");
        for (var entry : admitted) {
            assertEquals(HISTORICAL, r6.tracker().executedCostPolicies().get(entry.getKey()));
            var optimizer = new CorridorCenterlineOptimizer(); var d = original(optimizer, fixed(.12, 0, false));
            assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(d, entry.getValue(), DIRECT_POSITION_V1));
            var before = optimizer.preparedWork();
            var bad = CorridorAwareTracker.withTargetOwnedDirectPositionCostDomainWithoutAuthority(optimizer, ignored -> entry.getValue());
            assertThrows(IllegalArgumentException.class, () -> track(bad, fixed(.12, 0, false).profiles(), TraceBudgets.defaults()));
            assertTrue(bad.executedCostPolicies().isEmpty()); assertEquals(0, optimizer.preparedWork().retainedUnaryEvaluations());
        }
        assertTrue(r4.tracker().executedCostPolicies().isEmpty());
    }

    @Test void nonzeroPlateauCoarseShoulderAndRippleRemainUnchanged() throws Exception {
        var f = fixed(.12, 0, false); var optimizer = new CorridorCenterlineOptimizer();
        var scales = Map.of(CorridorCenterlineOptimizer.scaleEvidenceKey(0, f.track().points().get(0).band().id()),
            new BandScaleEvidence(.8, -1., .7, List.of(0, 1), false, false));
        var ripple = new UnsupportedRippleEvaluator.RippleSupport(0, .2, .2, .8, .4, .6, 1., -.5, 0., .1, .5, .8, 2., 2, "synthetic-direct-unary");
        for (double offset : List.of(.75, 2.0)) {
            Object h = unary(optimizer, f, 0, offset, scales, HISTORICAL, ripple, .7);
            Object d = unary(optimizer, f, 0, offset, scales, DIRECT_POSITION_V1, ripple, .7);
            assertEquals(number(h, "total") - number(h, "tubeCenterCost") + number(d, "tubeCenterCost"), number(d, "total"), 1e-10);
            assertTrue(number(h, "coarsePriorCost") > 0); assertTrue(number(h, "rippleAdditionalCost") > 0);
            for (String member : List.of("plateauCenterCost", "coarsePriorCost", "rippleAdditionalCost"))
                assertEquals(number(h, member), number(d, member));
            if (offset == .75) assertTrue(number(h, "plateauCenterCost") > 0);
            else assertTrue(CorridorOptimizationParameters.defaults().shoulderDistanceWeight() * Math.pow(offset - 1.75, 2) > 0);
        }
    }

    @Test void policyMetadataSurvivesCopiesAndCountsInsideNativeCacheCeiling() throws Exception {
        var f = CorridorGapDomainTest.fixture(); var optimizer = CorridorGapDomainTest.guarded();
        var historical = CorridorGapDomainTest.original(optimizer, f, f.context(), f.track(), JunctionContext.empty(), TraceBudgets.defaults());
        var gap = optimizer.prepareGapDomain(historical, 7.01);
        optimizer.optimizeOriginalDomain(gap); long nativeBytes = optimizer.hardGeometryWork().metricCacheReservedBytes();
        var direct = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(gap, CorridorWorkingSchedule.refusal("REFUSED_TEST"), DIRECT_POSITION_V1);
        var reserved = CorridorCenterlineOptimizer.withRetainedCostPolicyBytes(direct, 128);
        var copy = CorridorCenterlineOptimizer.withBudgets(reserved, reserved.budgets());
        assertEquals(128, copy.retainedCostPolicyBytes()); assertSame(gap.states(), copy.states());
        optimizer.optimizeOriginalDomain(copy); assertEquals(nativeBytes + 128, optimizer.hardGeometryWork().metricCacheReservedBytes());
        assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withRetainedCostPolicyBytes(direct, -1));
        assertThrows(IllegalArgumentException.class, () -> CorridorCenterlineOptimizer.withRetainedCostPolicyBytes(direct, 512L * 128 + 1));

        // Two tiny real native states; byte-boundary proof needs no large table or solver.
        var stateType = Class.forName(CorridorCenterlineOptimizer.class.getName() + "$OffsetState");
        var stateCtor = stateType.getDeclaredConstructors()[0]; stateCtor.setAccessible(true);
        var state = stateCtor.newInstance(0., new Point2D.Double(20, 20), 0.);
        var counterType = Class.forName(CorridorCenterlineOptimizer.class.getName() + "$HardGeometryCounters");
        var counterCtor = counterType.getDeclaredConstructors()[0]; counterCtor.setAccessible(true);
        var cache = Arrays.stream(CorridorCenterlineOptimizer.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("metricEdgeCache") && m.getParameterCount() == 3).findFirst().orElseThrow();
        cache.setAccessible(true);
        long cap = 128L * 1024 * 1024, nativeTinyBytes = 448;
        assertNotNull(cache.invoke(optimizer, List.of(List.of(state), List.of(state)), counterCtor.newInstance(), cap - nativeTinyBytes));
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> cache.invoke(optimizer, List.of(List.of(state), List.of(state)), counterCtor.newInstance(), cap - nativeTinyBytes + 128));
        var limited = assertInstanceOf(CorridorCenterlineOptimizer.HardGeometryResourceLimitException.class, failure.getCause());
        assertEquals(cap + 128, limited.requestedCacheBytes().orElseThrow());
    }

    @Test void diagnosticRetentionIsBoundedWithoutBecomingAnExecutionCap() throws Exception {
        var tracker = CorridorAwareTracker.withTargetOwnedDirectPositionCostDomainWithoutAuthority(new CorridorCenterlineOptimizer(),
            ignored -> CorridorWorkingSchedule.refusal("REFUSED_TEST"));
        var record = CorridorAwareTracker.class.getDeclaredMethod("recordCostPolicy", CorridorCenterlineOptimizer.OriginalDomain.class);
        var reserve = CorridorAwareTracker.class.getDeclaredMethod("reserveCostPolicyMetadata", CorridorCenterlineOptimizer.OriginalDomain.class);
        record.setAccessible(true); reserve.setAccessible(true);
        var f = fixed(.12, 0, false); var optimizer = new CorridorCenterlineOptimizer();
        for (int i = 0; i <= 512; i++) {
            var track = new CorridorTrack("bound-" + i, f.track().points(), 0, 1, false, List.of(), "");
            var d = original(optimizer, new Fixture(track, f.profiles(), f.tube()));
            d = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(d, CorridorWorkingSchedule.refusal("REFUSED_TEST"), DIRECT_POSITION_V1);
            var reserved = (CorridorCenterlineOptimizer.OriginalDomain) reserve.invoke(tracker, d);
            assertEquals(Math.min(i + 1, 512) * 128L, reserved.retainedCostPolicyBytes());
            record.invoke(tracker, reserved);
            if (i == 512) assertFalse(optimizer.optimizeOriginalDomain(reserved).offsetsPx().isEmpty());
        }
        assertEquals(512, tracker.executedCostPolicies().size()); assertEquals(512L * 128, tracker.executedCostPolicyMetadataBytes());
        assertFalse(tracker.executedCostPolicies().containsKey("bound-512"));
    }

    @Test void sourceStateAndCancellationLimitsRemainTerminal() throws Exception {
        var f = fixed(.12, 0, false); var optimizer = new CorridorCenterlineOptimizer();
        var d = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(original(optimizer, f), CorridorWorkingSchedule.refusal("REFUSED_TEST"), DIRECT_POSITION_V1);
        var limited = CorridorCenterlineOptimizer.withBudgets(d, new TraceBudgets(1, 8000000, 128000000, 32, 8));
        assertThrows(CorridorResourceLimitException.class, () -> optimizer.optimizeOriginalDomain(limited));
        assertEquals(0, optimizer.preparedWork().solverStarts()); assertEquals(0, optimizer.preparedWork().retainedUnaryEvaluations());
        var gapFixture = CorridorGapDomainTest.fixture(); var cancel = new boolean[] {false};
        var guarded = new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(CorridorGapDomainTest.FRAME, () -> cancel[0]);
        var original = CorridorGapDomainTest.original(guarded, gapFixture, gapFixture.context(), gapFixture.track(), JunctionContext.empty(), TraceBudgets.defaults());
        var direct = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(original, CorridorWorkingSchedule.refusal("REFUSED_TEST"), DIRECT_POSITION_V1);
        cancel[0] = true;
        assertThrows(java.util.concurrent.CancellationException.class, () -> guarded.optimizeOriginalDomain(direct));
        assertEquals("CANCELLED", guarded.preparedWork().outcome());
        var cancelledWork = guarded.preparedWork(); var hardWork = guarded.hardGeometryWork();
        assertTrue(cancelledWork.retainedUnaryEvaluations() > 0, "Native unary table is built before its first metric-cache cancellation checkpoint");
        assertThrows(java.util.concurrent.CancellationException.class, () -> guarded.optimizeOriginalDomain(original));
        assertEquals(cancelledWork, guarded.preparedWork()); assertEquals(hardWork, guarded.hardGeometryWork());
    }

    @Test void flatPublicEntryClearsPriorPolicyBeforeEarlyValidationFailure() {
        var profiles = fixed(.8, 0, false).profiles().stream().map(CorridorProfile::source).toList();
        var tracker = reusableDirectTracker();
        Runnable populate = () -> tracker.trackDetailed(profiles, 1., JunctionContext.empty(), "native", GeometryCleanupConfig.disabled(), 1.);
        assertClearedAfterFailure(tracker, populate, () -> tracker.trackDetailed(List.of(profiles.get(1), profiles.get(0)),
            1., JunctionContext.empty(), "native", GeometryCleanupConfig.disabled(), 1.), IllegalArgumentException.class);
        assertClearedAfterFailure(tracker, populate, () -> tracker.trackDetailed((List<RenderedHeatmapSampler.CrossSectionProfile>) null,
            1., JunctionContext.empty(), "native", GeometryCleanupConfig.disabled(), 1.), NullPointerException.class);
    }

    @Test void multiscalePublicEntryClearsPriorPolicyBeforeEarlyValidationFailures() {
        var profiles = fixed(.8, 0, false).profiles().stream().map(CorridorProfile::source).toList();
        var tracker = reusableDirectTracker(); var valid = oneScale(profiles);
        Runnable populate = () -> tracker.trackDetailed(valid, 1., JunctionContext.empty(), "native", GeometryCleanupConfig.disabled(), 1.);
        for (var invalid : malformedScales(profiles))
            assertClearedAfterFailure(tracker, populate, () -> tracker.trackDetailed(invalid, 1., JunctionContext.empty(),
                "native", GeometryCleanupConfig.disabled(), 1.), IllegalArgumentException.class);
        assertClearedAfterFailure(tracker, populate, () -> tracker.trackDetailed((MultiScaleProfileSet) null,
            1., JunctionContext.empty(), "native", GeometryCleanupConfig.disabled(), 1.), NullPointerException.class);
    }

    @Test void budgetedMultiscalePublicEntryClearsPriorPolicyBeforeAnyEarlyFailure() {
        var profiles = fixed(.8, 0, false).profiles().stream().map(CorridorProfile::source).toList();
        var tracker = reusableDirectTracker(); var valid = oneScale(profiles);
        Runnable populate = () -> tracker.trackDetailed(valid, 1., JunctionContext.empty(), "native",
            GeometryCleanupConfig.disabled(), 1., TraceBudgets.defaults());
        assertClearedAfterFailure(tracker, populate, () -> tracker.trackDetailed(valid, 1., JunctionContext.empty(),
            "native", GeometryCleanupConfig.disabled(), 1., null), IllegalArgumentException.class);
        for (var invalid : malformedScales(profiles))
            assertClearedAfterFailure(tracker, populate, () -> tracker.trackDetailed(invalid, 1., JunctionContext.empty(),
                "native", GeometryCleanupConfig.disabled(), 1., TraceBudgets.defaults()), IllegalArgumentException.class);
        assertClearedAfterFailure(tracker, populate, () -> tracker.trackDetailed((MultiScaleProfileSet) null,
            1., JunctionContext.empty(), "native", GeometryCleanupConfig.disabled(), 1., TraceBudgets.defaults()), NullPointerException.class);
    }

    static CorridorAwareTracker reusableDirectTracker() {
        return CorridorAwareTracker.withTargetOwnedDirectPositionCostDomainWithoutAuthority(new CorridorCenterlineOptimizer(),
            ignored -> CorridorWorkingSchedule.refusal("REFUSED_TEST_ELIGIBILITY"));
    }
    static void assertClearedAfterFailure(CorridorAwareTracker tracker, Runnable populate, Runnable fail,
            Class<? extends Throwable> failureType) {
        populate.run();
        assertFalse(tracker.executedCostPolicies().isEmpty(), "Populate through the actual public extraction/association/DP entrypoint");
        assertTrue(tracker.executedCostPolicyMetadataBytes() > 0);
        assertThrows(failureType, fail::run);
        assertTrue(tracker.executedCostPolicies().isEmpty(), "An early failed invocation must not expose its predecessor's policy");
        assertEquals(0, tracker.executedCostPolicyMetadataBytes());
    }
    static MultiScaleProfileSet oneScale(List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        return new MultiScaleProfileSet(List.of(new MultiScaleProfileSet.ScaleProfileLevel(0, 1, 0., profiles)), 0, 0);
    }
    static List<MultiScaleProfileSet> malformedScales(List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        var shifted = profiles.stream().map(p -> {
            var a = p.samplingAnchor();
            var anchor = new ProfileSamplingAnchor(new EastNorth(a.rasterPoint().x(), a.rasterPoint().y()),
                a.rasterPoint().x(), a.rasterPoint().y(), a.cumulativeGroundDistanceMeters() + .5);
            return new RenderedHeatmapSampler.CrossSectionProfile(anchor, p.normalScreen(), p.peaks(), p.anchorWithinRaster(), p.intensitySamples());
        }).toList();
        var fine = new MultiScaleProfileSet.ScaleProfileLevel(0, 1, 0., profiles);
        return List.of(oneScale(List.of(profiles.get(1), profiles.get(0))),
            new MultiScaleProfileSet(List.of(fine, new MultiScaleProfileSet.ScaleProfileLevel(1, 2, 1., profiles.subList(0, profiles.size() - 1))), 0, 0),
            new MultiScaleProfileSet(List.of(fine, new MultiScaleProfileSet.ScaleProfileLevel(1, 2, 1., shifted)), 0, 0));
    }

    static CorridorCenterlineOptimizer.PreparedWork withoutReservation(CorridorCenterlineOptimizer.PreparedWork w) {
        return new CorridorCenterlineOptimizer.PreparedWork(w.originalStates(), w.retainedUnaryEvaluations(), w.bridgeUnaryEvaluations(),
            OptionalLong.empty(), w.outcome(), w.originalRows(), w.copiedProfileRows(), w.solverStarts(), w.gapWork(), w.observedWork());
    }
    static CorridorCenterlineOptimizer.HardGeometryWork withoutReservation(CorridorCenterlineOptimizer.HardGeometryWork w) {
        return new CorridorCenterlineOptimizer.HardGeometryWork(w.outcome(), w.profileCostEvaluations(), w.attemptedTransitions(),
            w.testedTriples(), w.rejectedTriples(), w.scoredTransitions(), w.metricPoints(), w.metricEdges(), 0);
    }
    record Owned(TraceEngineRun run, CorridorAwareTracker tracker, CorridorAwareTracker.TrackingResult tracked) { }
    static Owned invokeOwned(CorridorPolicyFixture.Fixture f) throws Exception {
        var type = Class.forName(CorridorEngineAdapter.class.getName() + "$OwnedInvocation");
        var ctor = type.getDeclaredConstructor(CorridorEngineAdapter.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot.class,org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.class); ctor.setAccessible(true);
        var owner = ctor.newInstance(new CorridorEngineAdapter("native"), f.request(), f.evidence(), f.network(), CancellationProbe.NONE);
        var method = type.getDeclaredMethod("execute"); method.setAccessible(true);
        var run = (TraceEngineRun) method.invoke(owner);
        var tracker = type.getDeclaredField("tracker"); tracker.setAccessible(true);
        var tracked = type.getDeclaredField("tracked"); tracked.setAccessible(true);
        return new Owned(run, (CorridorAwareTracker) tracker.get(owner), (CorridorAwareTracker.TrackingResult) tracked.get(owner));
    }
    static CorridorPolicyFixture.Fixture withPolicy(CorridorPolicyFixture.Fixture f, CorridorTraceInput.ExecutionPolicy policy) {
        var r = f.request();
        var input = r.corridorInput().orElseThrow().withPolicy(policy, r.corridorInput().orElseThrow().additionalFixedOccurrences());
        var request = new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(), r.geometryMode(), r.permissions(), r.budgets(),
            r.evidenceSnapshotId(), r.evidenceContentHash(), r.networkSnapshotId(), r.networkContentHash(), r.settingsHash(),
            r.parameterHash(), r.samplerId(), r.configuredSampleStepMeters(), r.profileChainage(), r.evidenceResolution(), Optional.of(input));
        return new CorridorPolicyFixture.Fixture(request, f.evidence(), f.network(), f.options());
    }
    static CorridorAwareTracker.TrackingResult track(CorridorAwareTracker tracker, List<CorridorProfile> profiles, TraceBudgets budgets) throws Exception {
        var method = Arrays.stream(CorridorAwareTracker.class.getDeclaredMethods()).filter(m -> m.getName().equals("trackExtracted")).findFirst().orElseThrow();
        method.setAccessible(true);
        try { return (CorridorAwareTracker.TrackingResult) method.invoke(tracker, profiles, 1., JunctionContext.empty(), "native",
            GeometryCleanupConfig.disabled(), 1., Map.of(), List.of(), 0L, 0L, budgets); }
        catch (java.lang.reflect.InvocationTargetException e) { if (e.getCause() instanceof RuntimeException r) throw r; throw e; }
    }

    static CorridorCenterlineOptimizer.OriginalDomain original(CorridorCenterlineOptimizer optimizer, Fixture f) {
        return optimizer.prepareOriginalDomain(f.track(), f.profiles(), 1, JunctionContext.empty(), Map.of(), f.tube());
    }
    static Fixture fixed(double peak, double motion, boolean bandless) {
        var profiles = new ArrayList<CorridorProfile>();
        var points = new LinkedHashMap<Integer, CorridorTrackPoint>();
        var slices = new ArrayList<CorridorTubeSlice>();
        for (int i = 0; i < 4; i++) {
            double center = .75;
            var band = new CorridorBand("synthetic-" + i, center, center - 1, center + 1,
                center - .25, center + .25, List.of(center), peak, .03, 1, 1, 1, 1, .9, .9, .5, false, List.of());
            var samples = new ArrayList<RenderedHeatmapSampler.IntensitySample>();
            for (int j = -32; j <= 32; j++) {
                double offset = j * .25, value = .03 + (peak - .03) * Math.exp(-.5 * Math.pow((offset - center) / .7, 2));
                samples.add(new RenderedHeatmapSampler.IntensitySample(offset, value, value, value, true));
            }
            var source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(i * 2, 0), i * 2, 0, i * 2),
                new Point2D.Double(0, 1), List.of(), true, samples);
            profiles.add(new CorridorProfile(i, source, bandless ? List.of() : List.of(band), peak, .03, peak - .03, true));
            if (!bandless) points.put(i, new CorridorTrackPoint(i, band, false, CorridorPointSupport.DIRECT_UNION));
            slices.add(new CorridorTubeSlice(i, i * 2, -.25, 0, center, 0, -.25, 0, 1, motion,
                "SYNTHETIC_EXPLICIT_MOTION", 0, center - .25, center + .25, center - 1, center + 1,
                1, .6, false, false, center, center, center, true));
        }
        return new Fixture(new CorridorTrack("fixed-unary", points, 0, 1, false, List.of(), ""),
            List.copyOf(profiles), new LongitudinalCorridorTube(slices));
    }
    static Object unary(CorridorCenterlineOptimizer optimizer, Fixture f, double offset) throws Exception {
        Method helper = Arrays.stream(CorridorCenterlineOptimizer.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("dataCostComponents") && m.getParameterCount() == 11).findFirst().orElseThrow();
        helper.setAccessible(true);
        return helper.invoke(optimizer, f.track(), f.profiles().get(0), 0, offset, 1., Map.of(), f.tube().at(0),
            1., UnsupportedRippleEvaluator.RippleSupport.disabled(0), 0., 1.);
    }
    static Object unary(CorridorCenterlineOptimizer optimizer, Fixture f, int row, double offset,
            Map<String,BandScaleEvidence> scales, CorridorCenterlineOptimizer.OriginalCostPolicy policy) throws Exception {
        return unary(optimizer, f, row, offset, scales, policy, UnsupportedRippleEvaluator.RippleSupport.disabled(row), 0.);
    }
    static Object unary(CorridorCenterlineOptimizer optimizer, Fixture f, int row, double offset,
            Map<String,BandScaleEvidence> scales, CorridorCenterlineOptimizer.OriginalCostPolicy policy,
            UnsupportedRippleEvaluator.RippleSupport ripple, double strength) throws Exception {
        Method helper = Arrays.stream(CorridorCenterlineOptimizer.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("dataCostComponents") && m.getParameterCount() == 12).findFirst().orElseThrow();
        helper.setAccessible(true);
        return helper.invoke(optimizer, f.track(), f.profiles().get(row), row, offset, 1., scales, f.tube().at(row),
            1., ripple, strength, 1., policy);
    }
    static double number(Object record, String name) throws Exception {
        Method member = record.getClass().getDeclaredMethod(name); member.setAccessible(true);
        return (double) member.invoke(record);
    }
}
