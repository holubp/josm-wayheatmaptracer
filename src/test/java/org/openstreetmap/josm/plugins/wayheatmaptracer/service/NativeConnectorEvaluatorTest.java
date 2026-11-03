package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class NativeConnectorEvaluatorTest {
    private static final int[] WITNESS = {0, 1, 15, 16};

    @Test void queryPoliciesRecordVersionedIdentityAndPreserveExplicitDenseNumerics() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var dense = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()));
             var explicitDense = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()),
                 QueryPolicy.DENSE_EIGHTH_NATIVE_V1);
             var half = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()),
                 QueryPolicy.HALF_NATIVE_L0_V1);
             var pixel = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()),
                 QueryPolicy.PIXEL_CAPPED_L0_V1)) {
            var from = f.states().get(0);
            var to = f.states().get(1);
            var defaultReceipt = dense.evaluate(from, to);
            var explicitDenseReceipt = explicitDense.evaluate(from, to);
            var halfReceipt = half.evaluate(from, to);
            var pixelReceipt = pixel.evaluate(from, to);
            assertEquals(QueryPolicy.DENSE_EIGHTH_NATIVE_V1, defaultReceipt.queryPolicy());
            assertEquals(defaultReceipt.parameterVersion(), explicitDenseReceipt.parameterVersion());
            assertEquals(defaultReceipt.probes().get(0).samples(), explicitDenseReceipt.probes().get(0).samples());
            assertEquals(defaultReceipt.probes().get(0).selectedMode(), explicitDenseReceipt.probes().get(0).selectedMode());
            assertEquals(defaultReceipt.scalar().orElseThrow().mean(), explicitDenseReceipt.scalar().orElseThrow().mean());
            assertEquals(defaultReceipt.scalar().orElseThrow().minimum(), explicitDenseReceipt.scalar().orElseThrow().minimum());
            assertNotEquals(defaultReceipt.parameterVersion(), halfReceipt.parameterVersion());
            assertNotEquals(halfReceipt.parameterVersion(), pixelReceipt.parameterVersion());
        }
    }

    @Test void nullQueryPolicyFailsBeforeOpeningExecutionOwner() {
        try (var f = straight(false, SourceClass.OBSERVED)) {
            long retainedBytes = f.ledger().currentBytes();
            assertThrows(NullPointerException.class, () -> new NativeConnectorEvaluator(
                f.input(), new Domain(f.states(), List.of()), null));
            assertEquals(retainedBytes, f.ledger().currentBytes());
            try (var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
                assertEquals(Status.ADMITTED, evaluator.evaluate(f.states().get(0), f.states().get(1)).status());
            }
        }
    }

    @Test void admitsActualR4NativeWitnessForBothEnginesThroughRetainedCroppedField() throws Exception {
        var original = PhysicalGeometryFixture.straightRidgeThroughParabola(4);
        // This is the production crop, not a full-image field or a profile-derived raster.
        int minX=18,minY=17,w=93,h=86;
        double[] values=new double[w*h];
        for(int y=0;y<h;y++) for(int x=0;x<w;x++)
            values[y*w+x]=Math.round(255*Math.exp(-0.5*Math.pow((minY+y-64)/0.8,2)))/255.0;
        var evidence=evidence(w,h,values,RasterMetricTransform.metricGrid(new MetricPoint(minX,minY),1,0,0,1),
            MetricRegion.rectangle(minX,minY,110,102));
        var locations=new ArrayList<DetachedProfileSamplingLocation>();
        double distance=0; RasterPoint previous=null;
        for(int j=-9;j<=9;j++) {
            var r=new RasterPoint(64+j-minX,64-9.7+j*j/8.0-minY);
            if(previous!=null)distance+=Math.hypot(r.x()-previous.x(),r.y()-previous.y());
            locations.add(at(evidence,r,distance)); previous=r;
        }
        var input=new CorridorTraceInput(locations,1);
        var chainage=new ProfileChainage(locations.stream().map(DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList(),1);
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner();var retained=new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(
                evidence,"native",input,chainage,25,budget(),owner)) {
            assertFalse(retained.context().productionEligible());
            assertEquals(0,retained.context().nativeGrid().originX());
            assertEquals(0,retained.context().nativeGrid().originY());
            assertEquals(w,retained.context().nativeGrid().width());
            assertEquals(h,retained.context().nativeGrid().height());
            // Pixel conversion is factual captured transform: local grid y47 is full-image y64.
            assertEquals(64,retained.context().rasterTransform().pixelCenterToMetric(0,47).yMeters(),0);
            var result=new CorridorAwareTracker().trackDetailed(original,1,JunctionContext.empty());
            var selected=result.candidates().get(0);
            var track=result.tracks().stream().filter(t->t.id().equals(selected.id())).findFirst().orElseThrow();
            var tube=result.tubes().get(selected.id());
            var optimization=result.optimizations().get(selected.id());
            var optimizer=new CorridorCenterlineOptimizer();
            for(String engine:List.of("A","Legacy")) {
                var states=new ArrayList<RowState>();
                for(int row=0;row<original.size();row++) {
                    var profile=original.get(row);
                    var peaks=retained.profiles().levelZeroProfiles().get(row+1).peaks();
                    assertEquals(1,peaks.size());
                    List<Double> offsets;
                    if(engine.equals("A")) {
                        @SuppressWarnings("unchecked") var actual=(List<Double>)invoke(optimizer,"allowedOffsets",track,result.profiles(),row,
                            1.0,JunctionContext.empty(),result.scaleEvidence(),tube.at(row),optimization.endpointApproaches());
                        offsets=actual;
                    } else offsets=List.of(profile.peaks().get(0).offsetPx());
                    int best=0;
                    for(int k=1;k<offsets.size();k++)
                        if(Math.abs(profile.anchorScreen().y+profile.normalScreen().y*offsets.get(k)-64)
                           <Math.abs(profile.anchorScreen().y+profile.normalScreen().y*offsets.get(best)-64))best=k;
                    states.add(new RowState(row+1,best,offsets.get(best),SourceClass.OBSERVED,peaks.get(0)));
                    var factual=retained.context().observations().get(row+1);
                    var local=factual.location().rasterPoint();var n=factual.normal();
                    var point=retained.context().rasterTransform().pixelCenterToMetric(local.x()+n.x()*offsets.get(best),local.y()+n.y()*offsets.get(best));
                    if(Arrays.stream(WITNESS).anyMatch(i->i==factual.index()-1))assertEquals(64,point.yMeters(),engine.equals("A")?.001:.08);
                }
                try(var evaluator=new NativeConnectorEvaluator(retained,new Domain(states,List.of()))) {
                    for(int i=1;i<WITNESS.length;i++) {
                        var receipt=evaluator.evaluate(states.get(WITNESS[i-1]),states.get(WITNESS[i]));
                        assertEquals(Status.ADMITTED,receipt.status(),engine+" "+WITNESS[i-1]+"->"+WITNESS[i]+" "+receipt);
                    }
                }
                var expectedSampleCounts = Map.of(QueryPolicy.DENSE_EIGHTH_NATIVE_V1, 401,
                    QueryPolicy.HALF_NATIVE_L0_V1, 101, QueryPolicy.PIXEL_CAPPED_L0_V1, 51);
                for (var policy : QueryPolicy.values()) {
                    try (var evaluator = new NativeConnectorEvaluator(retained, new Domain(states,List.of()), policy)) {
                        var receipt = evaluator.evaluate(states.get(0), states.get(1));
                        assertEquals(expectedSampleCounts.get(policy), receipt.probes().get(0).samples().size(), policy.name());
                    }
                }
            }
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void rejectsUniformBrightFieldWithoutNativeRidge() {
        try(var f=straight(true,SourceClass.GAP)) {
            try(var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()))) {
                assertEquals(Status.REJECTED,evaluator.evaluate(f.states().get(0),f.states().get(1)).status());
            }
        }
    }

    @Test void admitsMeasuredStraightRidgeWithOriginalPeakOwnership() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
            assertTrue(receipt.scalarEnvelopeComplete());
            assertTrue(receipt.measuredNativeContinuationComplete());
            assertEquals(20.0, receipt.emittedLengthMeters(), 1e-12);
            assertEquals(20.0, receipt.sourceSpanMeters(), 1e-12);
        }
    }

    @Test void freshUnownedPortsCannotBindEachOtherWithoutAnOriginalBranchOwner() {
        try (var f = straight(false, SourceClass.GAP);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.UNAVAILABLE, receipt.status());
            assertEquals(Reason.ENDPOINT_OWNERSHIP, receipt.reason());
        }
    }

    @Test void rejectsEqualValuedForeignStateIdentity() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var nativeState = f.states().get(0);
            var foreign = new RowState(nativeState.row(), nativeState.nativeStateId(),
                nativeState.offsetRasterPixels(), nativeState.sourceClass(), nativeState.peakOwner());
            var receipt = evaluator.evaluate(foreign, f.states().get(1));
            assertEquals(Status.UNAVAILABLE, receipt.status());
            assertEquals(Reason.FOREIGN_STATE, receipt.reason());
        }
    }

    @Test void refusesUseAfterInputCloses() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            f.input().close();
            assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(f.states().get(0), f.states().get(1)));
        }
    }

    @Test void rejectsDuplicatedNativeRowStateIdInDeclaredDomain() {
        try (var f = straight(false, SourceClass.OBSERVED)) {
            var state = f.states().get(0);
            var duplicate = new RowState(state.row(), state.nativeStateId(), 0.5,
                state.sourceClass(), state.peakOwner());
            assertThrows(IllegalArgumentException.class, () -> new NativeConnectorEvaluator(f.input(),
                new Domain(List.of(state, duplicate, f.states().get(1)), List.of())));
        }
    }

    @Test void reportsOutOfRangeStateBeforeProjectingOverflowingOffset() {
        try (var f = straight(false, SourceClass.OBSERVED)) {
            var state = f.states().get(0);
            var invalid = new RowState(state.row(), state.nativeStateId(), Double.MAX_VALUE,
                state.sourceClass(), state.peakOwner());
            try (var evaluator = new NativeConnectorEvaluator(f.input(),
                    new Domain(List.of(invalid, f.states().get(1)), List.of()))) {
                var receipt = evaluator.evaluate(invalid, f.states().get(1));
                assertEquals(Status.UNAVAILABLE, receipt.status());
                assertEquals(Reason.INVALID_STATE, receipt.reason());
            }
        }
    }

    @Test void brightEndpointsDoNotAuthorizeAnInteriorDarkGap() {
        try (var f = scene((x, y) -> Math.abs(x - 20) <= 1 ? 0
                    : Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertNotEquals(Status.ADMITTED, receipt.status());
            assertEquals(0.0, receipt.scalar().orElseThrow().minimum().orElseThrow());
        }
    }

    @Test void invalidInteriorCellIsNotInterpolatedAcross() {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> x != 20 || y != 32, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.UNAVAILABLE, receipt.status());
            assertEquals(Reason.SCALAR_SUPPORT, receipt.reason());
        }
    }

    @Test void decisionAuthorityMustCoverWholeConnectorIncludingNarrowNotch() {
        var authority = new MetricRegion(List.of(List.of(new MetricPoint(0, 0),
            new MetricPoint(63, 0), new MetricPoint(63, 63), new MetricPoint(20.01, 63),
            new MetricPoint(20.01, 31.99), new MetricPoint(19.99, 31.99),
            new MetricPoint(19.99, 63), new MetricPoint(0, 63))));
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, authority, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.REJECTED, receipt.status());
            assertEquals(Reason.DECISION_AUTHORITY, receipt.reason());
        }
    }

    @Test void oneFreshGapPortCanBindThroughDirectEvidenceToAnOwnedPort() {
        try (var f = straight(false, SourceClass.OBSERVED)) {
            var gap = new RowState(0, 0, 0, SourceClass.GAP, null);
            try (var evaluator = new NativeConnectorEvaluator(f.input(),
                    new Domain(List.of(gap, f.states().get(1)), List.of()))) {
                var receipt = evaluator.evaluate(gap, f.states().get(1));
                assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
                assertEquals(SourceClass.GAP, receipt.from().sourceClass());
                assertNull(receipt.from().peakOwner());
            }
        }
    }

    @Test void refusesProtectedEventEvenWhenItsEndpointsHaveBrightSupport() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of(),
                 List.of(new Barrier("protected-occurrence", 0, 1, 10, new MetricPoint(20, 32)))))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.REJECTED, receipt.status());
            assertEquals(Reason.MANDATORY_BARRIER, receipt.reason());
        }
    }

    @Test void exactSixteenConsumedRowsAndTwentyMetersAreAllowed() {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 18, 20);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(17));
            assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
            assertEquals(20, receipt.sourceSpanMeters());
        }
    }

    @Test void seventeenthConsumedRowIsRejectedWithoutNarrowingStates() {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 19, 20);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(18));
            assertEquals(Status.REJECTED, receipt.status());
            assertEquals(Reason.SPAN, receipt.reason());
        }
    }

    @Test void sourceSpanBeyondTwentyMetersIsRejected() {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 2, 20.001);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            assertEquals(Reason.SPAN, evaluator.evaluate(f.states().get(0), f.states().get(1)).reason());
        }
    }

    @Test void anisotropicFramesAreRejectedByTheRetainedInputFactory() {
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 2);
        assertThrows(IllegalArgumentException.class, () -> {
            try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                    (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 126), SourceClass.OBSERVED, 2, 20, transform)) {
                // Admission never receives unsupported anisotropy; do not bypass that guard.
            }
        });
    }

    @Test void invalidAffineStateIsRefusedBeforeProjectionCanOverflow() {
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 2, 0, 0, 2);
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 126, 126), SourceClass.OBSERVED, 2, 20, transform)) {
            var outside = new RowState(0, 0, Double.MAX_VALUE, SourceClass.OBSERVED, f.states().get(0).peakOwner());
            try (var evaluator = new NativeConnectorEvaluator(f.input(),
                    new Domain(List.of(outside, f.states().get(1)), List.of()))) {
                var receipt = assertDoesNotThrow(() -> evaluator.evaluate(outside, f.states().get(1)));
                assertEquals(Reason.INVALID_STATE, receipt.reason());
            }
        }
    }

    @Test void representableSubEventToleranceNotchCannotBeSkipped() {
        double left = 20 - 0.5e-9, right = 20 + 0.5e-9;
        var authority = new MetricRegion(List.of(
            MetricRegion.rectangle(0, 0, left, 63).polygons().get(0),
            MetricRegion.rectangle(right, 0, 63, 63).polygons().get(0)));
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, authority, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertNotEquals(Status.ADMITTED, receipt.status(), "A representable authority hole is not permission");
            assertEquals(Reason.DECISION_AUTHORITY, receipt.reason());
        }
    }

    @Test void anExhaustedAttemptCannotContinueWithAnotherConnector() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var failure = assertThrows(EmissionWorkBudget.LimitExceededException.class,
                () -> f.input().workBudget().charge(EmissionWorkBudget.Operation.PREPARATION, Long.MAX_VALUE));
            assertSame(failure, assertThrows(EmissionWorkBudget.LimitExceededException.class,
                () -> evaluator.evaluate(f.states().get(0), f.states().get(1))));
        }
    }

    @Test void memoryRefusalCannotBeResetByOpeningAnotherSessionOnTheSameAttempt() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            AttemptMemoryLedger.ResourceLimitException failure;
            // Simulate retained work owned by another phase, leaving only2KiB for admission.
            try (var held = f.owner().reserve(f.ledger().limitBytes() - f.ledger().currentBytes() - 2048)) {
                failure = assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> evaluator.evaluate(f.states().get(0), f.states().get(1)));
                assertSame(failure, assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> evaluator.evaluate(f.states().get(0), f.states().get(1))));
            }
            assertSame(failure, assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> {
                try (var retried = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
                    // Releasing scratch must not turn a failed attempt into a new numerical run.
                }
            }));
        }
    }

    @Test void deterministicCancellationDuringEvaluationIsStickyAcrossSessions() {
        var remaining = new java.util.concurrent.atomic.AtomicInteger(-1);
        var work = new EmissionWorkBudget(TraceBudgets.defaults(),
            () -> remaining.get() >= 0 && remaining.getAndDecrement() == 0);
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 2, 20,
                RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1), work);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            long before = work.snapshot().workUsed();
            remaining.set(20);
            var failure = assertThrows(java.util.concurrent.CancellationException.class,
                () -> evaluator.evaluate(f.states().get(0), f.states().get(1)));
            assertTrue(work.snapshot().workUsed() > before);
            long terminalWork = work.snapshot().workUsed();
            assertSame(failure, assertThrows(java.util.concurrent.CancellationException.class,
                () -> evaluator.evaluate(f.states().get(0), f.states().get(1))));
            assertSame(failure, assertThrows(java.util.concurrent.CancellationException.class, () -> {
                try (var retried = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) { }
            }));
            assertEquals(terminalWork, work.snapshot().workUsed());
        }
    }

    @Test void completeBroadPlateauRemainsAdmissible() {
            try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(Math.max(0, Math.abs(y - 32) - 2), 2)),
                    (x, y) -> true,
                    MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED);
                 var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
                var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
                assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
            }
    }

    @Test void faintConcentratedEndpointCanBindWithoutRelabellingSyntheticSourceAsObserved() {
        try (var f = scene((x, y) -> (0.1 + 0.6 * Math.max(0, Math.min(1, (30 - x) / 20)))
                    * Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED)) {
            assertFalse(f.states().get(0).peakOwner().syntheticCenter());
            assertTrue(f.states().get(1).peakOwner().syntheticCenter(),
                "Original sampler supplies a fallback at intensity0.1, not an observed peak");
            var gap = new RowState(1, 0, 0, SourceClass.GAP, null);
            try (var evaluator = new NativeConnectorEvaluator(f.input(),
                    new Domain(List.of(f.states().get(0), gap), List.of()))) {
                var receipt = evaluator.evaluate(f.states().get(0), gap);
                assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
                assertEquals(SourceClass.GAP, receipt.to().sourceClass());
                assertNull(receipt.to().peakOwner());
            }
        }
    }

    @Test void offConnectorInvalidPixelDoesNotIntroduceADiskTopologyVeto() {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> x != 20 || y != 45, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
        }
    }

    @Test void measuredForkCannotAuthorizeTheStraightDarkMiddle() {
        try (var f = scene((x, y) -> x < 19 ? Math.exp(-0.5 * Math.pow(y - 32, 2))
                    : Math.max(Math.exp(-0.5 * Math.pow(y - 29, 2)),
                        Math.exp(-0.5 * Math.pow(y - 35, 2))),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertNotEquals(Status.ADMITTED, receipt.status());
            assertFalse(receipt.measuredNativeContinuationComplete());
        }
    }

    @Test void zeroLengthEdgeRequiresAndTransportsAnActualIssuedIncomingArc() {
        int w = 64, h = 64;
        double[] values = new double[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            values[y * w + x] = Math.exp(-0.5 * Math.pow(y - 15, 2));
        var evidence = evidence(w, h, values,
            RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1),
            MetricRegion.rectangle(0, 0, 63, 63));
        var guide = List.of(new RasterPoint(10, 20), new RasterPoint(20, 10),
            new RasterPoint(20, 20), new RasterPoint(30, 10));
        var locations = new ArrayList<DetachedProfileSamplingLocation>();
        double distance = 0;
        for (int row = 0; row < guide.size(); row++) {
            if (row > 0) distance += Math.hypot(guide.get(row).x() - guide.get(row - 1).x(),
                guide.get(row).y() - guide.get(row - 1).y());
            locations.add(at(evidence, guide.get(row), distance));
        }
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner();
             var sampled = new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(
                 evidence, "native", new CorridorTraceInput(locations, 1),
                 new ProfileChainage(locations.stream().map(
                     DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList(), 1),
                 12, budget(), owner)) {
            var states = new ArrayList<RowState>();
            double[] offsets = {-5 * Math.sqrt(2), 5, -5, 5 * Math.sqrt(2)};
            for (int row = 0; row < 4; row++) states.add(new RowState(row, 0, offsets[row],
                SourceClass.OBSERVED, sampled.profiles().levelZeroProfiles().get(row).peaks().get(0)));
            try (var evaluator = new NativeConnectorEvaluator(sampled, new Domain(states, List.of()))) {
                assertEquals(Reason.INCOMING_DIRECTION_UNAVAILABLE,
                    evaluator.evaluate(states.get(1), states.get(2)).reason());
                var incoming = evaluator.evaluate(states.get(0), states.get(1));
                assertEquals(Status.ADMITTED, incoming.status(), incoming::toString);
                var zero = evaluator.evaluate(states.get(1), states.get(2), incoming);
                assertEquals(Status.ADMITTED, zero.status(), zero::toString);
                assertEquals(0, zero.emittedLengthMeters());
                assertSame(incoming, zero.transportedIncoming().orElseThrow());
                assertTrue(zero.sourceSpanMeters() > 0);
                var forged = new Receipt(incoming.status(), incoming.reason(), incoming.context(),
                    incoming.from(), incoming.to(), incoming.queryPolicy(), incoming.parameterVersion(), incoming.sourceSpanMeters(),
                    incoming.emittedLengthMeters(), incoming.minimumNativePitchMeters(), incoming.scalar(),
                    incoming.scalarEnvelopeComplete(), incoming.measuredNativeContinuationComplete(),
                    incoming.probes(), incoming.transportedIncoming());
                assertEquals(Reason.INCOMING_DIRECTION_UNAVAILABLE,
                    evaluator.evaluate(states.get(1), states.get(2), forged).reason());
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    private record Straight(AttemptMemoryLedger ledger,AttemptMemoryLedger.Owner owner,
            SampledEmissionInput input,List<RowState> states) implements AutoCloseable {
        @Override public void close(){ input.close(); owner.close(); assertEquals(0,ledger.currentBytes()); }
    }
    private static Straight straight(boolean uniform,SourceClass sourceClass) {
        return scene((x, y) -> uniform ? 1 : Math.exp(-.5 * Math.pow(y - 32, 2)),
            (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), sourceClass);
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision, SourceClass sourceClass) {
        return scene(intensity, validity, decision, sourceClass, 2, 20);
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision,
            SourceClass sourceClass, int rows, double span) {
        return scene(intensity, validity, decision, sourceClass, rows, span,
            RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1));
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision,
            SourceClass sourceClass, int rows, double span, RasterMetricTransform transform) {
        return scene(intensity, validity, decision, sourceClass, rows, span, transform, budget());
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision,
            SourceClass sourceClass, int rows, double span, RasterMetricTransform transform,
            EmissionWorkBudget work) {
        int w=64,h=64; double[] values=new double[w*h];
        boolean[] valid = new boolean[w * h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            values[y*w+x]=intensity.applyAsDouble(x,y);
            valid[y*w+x]=validity.test(x,y);
        }
        var evidence=evidence(w,h,values,valid,transform,decision);
        var locations = new ArrayList<DetachedProfileSamplingLocation>();
        for (int row = 0; row < rows; row++) {
            double chainage = span * row / (rows - 1);
            locations.add(at(evidence, new RasterPoint(10 + chainage / evidence.resolution().outputRasterPitchMeters(), 32), chainage));
        }
        var input=new CorridorTraceInput(locations,.5);
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        SampledEmissionInput sampled;
        try {
            sampled=new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(evidence,"native",input,
                new ProfileChainage(locations.stream().map(DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList(), span / (rows - 1)),8,work,owner);
        } catch (RuntimeException failure) {
            owner.close();
            assertEquals(0, ledger.currentBytes());
            throw failure;
        }
        var states=new ArrayList<RowState>();
        for(int i=0;i<rows;i++)states.add(new RowState(i,0,0,sourceClass,sourceClass==SourceClass.OBSERVED?sampled.profiles().levelZeroProfiles().get(i).peaks().get(0):null));
        return new Straight(ledger,owner,sampled,states);
    }
    private static Object invoke(Object object,String name,Object...args)throws Exception {
        Method m=Arrays.stream(object.getClass().getDeclaredMethods()).filter(x->x.getName().equals(name)&&x.getParameterCount()==args.length).findFirst().orElseThrow();
        m.setAccessible(true);return m.invoke(object,args);
    }
    private static EmissionWorkBudget budget(){return new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);}
    private static EvidenceSnapshot evidence(int w,int h,double[] values,RasterMetricTransform transform,MetricRegion decision) {
        boolean[] valid=new boolean[w*h]; Arrays.fill(valid,true);
        return evidence(w,h,values,valid,transform,decision);
    }
    private static EvidenceSnapshot evidence(int w,int h,double[] values,boolean[] valid,RasterMetricTransform transform,MetricRegion decision) {
        var origin=new GeographicPoint(0,0);
        var frame=LocalMetricFrame.certifiedEquirectangular(origin,new GeographicPoint(-.01,-.01),new GeographicPoint(.01,.01));
        var field=new ScalarEvidenceField(w,h,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        double scale = transform.axisUnit() == RasterMetricTransform.AxisUnit.SOURCE_PIXEL
            ? transform.rasterPixelsPerSourcePixel() : 1;
        double pitch = Math.hypot(transform.xAxisEastMetersPerSourcePixel(), transform.xAxisNorthMetersPerSourcePixel()) / scale;
        return new EvidenceSnapshot("native-connector-test",frame,transform,EvidenceResolution.nativeSource(pitch,pitch),decision,
            MetricRegion.rectangle(transform.origin().xMeters(),transform.origin().yMeters(),transform.pixelCenterToMetric(w-1,h-1).xMeters(),transform.pixelCenterToMetric(w-1,h-1).yMeters()),Map.of("native",field),"declared-only");
    }
    private static DetachedProfileSamplingLocation at(EvidenceSnapshot evidence,RasterPoint raster,double chainage) {
        var metric=evidence.transform().pixelCenterToMetric(raster.x(),raster.y());
        return new DetachedProfileSamplingLocation(evidence.coordinateFrame().toGeographic(metric),metric,raster,
            evidence.coordinateFrame(),evidence.transform(),chainage);
    }
}
