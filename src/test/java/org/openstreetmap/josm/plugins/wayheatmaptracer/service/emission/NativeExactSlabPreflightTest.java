package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;

/** Actual scalar slabs, not a whole-integral heuristic, own early necessary failure. */
class NativeExactSlabPreflightTest {
    private static final ExecutionPolicy PREFLIGHT = ExecutionPolicy.EXACT_SLAB_PREFLIGHT_V1;

    @Test void exactZeroFutureSlabRefusesBeforeAnyModeExtraction() {
        try (var f = scene((x,y) -> x >= 18 && x <= 22 ? 0 : Math.exp(-.5 * Math.pow(y - 32, 2)),
                (x,y) -> true, false, budget());
             var old = evaluator(f, ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
             var fresh = evaluator(f, PREFLIGHT)) {
            assertNotEquals(Status.ADMITTED, old.evaluate(f.states().get(0), f.states().get(1)).status());
            long beforeModes = units(f, EmissionWorkBudget.Operation.MODE_EXTRACTION);
            var rejected = fresh.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.REJECTED, rejected.status());
            assertEquals(Reason.EXACT_ZERO_SLAB_NECESSARY_FAILURE, rejected.reason());
            assertEquals(beforeModes, units(f, EmissionWorkBudget.Operation.MODE_EXTRACTION));
            assertTrue(rejected.probes().isEmpty(), "No mode or shoulder threshold was measured");
            assertFalse(rejected.scalarEnvelopeComplete());
            assertFalse(rejected.measuredNativeContinuationComplete());
            assertEquals(-1, rejected.firstFailingSlabIndex(), "This is not an observed mode/slab failure");
            assertEquals(41, rejected.plannedProbeCount());
            var proof = rejected.exactSlabPreflight().orElseThrow();
            assertTrue(proof.necessaryFailureSlabIndex() >= 0);
            var slab = proof.slabs().get(proof.necessaryFailureSlabIndex());
            assertEquals(BilinearSegmentIntegral.Status.COMPLETE, slab.result().status());
            assertEquals(0, slab.result().minimum().orElseThrow(), 0);
            assertSame(f.input().context(), slab.context());
            assertSame(f.input().context().nativeGrid(), slab.grid());
            assertEquals(EvidenceModelParameters.defaults().version(), slab.parameterVersion());
            var grid = slab.grid();
            var checked = BilinearSegmentIntegral.evaluate(grid, grid.toGrid(slab.from()), grid.toGrid(slab.to()),
                CancellationProbe.NONE, () -> { });
            assertEquals(checked, slab.result(), "The proof contains the actual future-endpoint query, not the global hint");
            assertNotSame(rejected.scalar().orElseThrow(), slab.result());
            assertEquals(0, proof.finalSlabCacheHits());
            assertTrue(rejected.parameterVersion().endsWith("/EXACT_SLAB_PREFLIGHT_V1"));
        }
    }

    @Test void positiveSupportedRidgeRetainsAdmissionQProbeBitsAndCellQueries() {
        try (var f = scene((x,y) -> Math.exp(-.5 * Math.pow(y - 32, 2)), (x,y) -> true, false, budget());
             var old = evaluator(f, ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
             var fresh = evaluator(f, PREFLIGHT)) {
            long before = units(f, EmissionWorkBudget.Operation.CELL_QUERY);
            var expected = old.evaluate(f.states().get(0), f.states().get(1));
            long oldQueries = units(f, EmissionWorkBudget.Operation.CELL_QUERY) - before;
            before = units(f, EmissionWorkBudget.Operation.CELL_QUERY);
            var actual = fresh.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.ADMITTED, expected.status()); assertEquals(expected.status(), actual.status());
            assertEquals(expected.sourceWeightedLoss(), actual.sourceWeightedLoss(), 0);
            assertEquals(expected.scalar(), actual.scalar());
            assertEquals(oldQueries, units(f, EmissionWorkBudget.Operation.CELL_QUERY) - before);
            assertTrue(actual.exactSlabPreflight().isEmpty(), "Positive global minima do not schedule the optional preflight");
            assertEquals(expected.probes().size(), actual.probes().size());
            for (int i = 0; i < actual.probes().size(); i++) {
                var a = expected.probes().get(i); var b = actual.probes().get(i);
                assertEquals(a.parameter(), b.parameter(), 0); assertEquals(a.rasterPosition(), b.rasterPosition());
                assertEquals(a.samples(), b.samples()); assertEquals(a.selectedMode(), b.selectedMode());
                assertEquals(a.threshold().shoulderThreshold(), b.threshold().shoulderThreshold(), 0);
                assertEquals(a.profileQuery(), b.profileQuery());
            }
        }
    }

    @Test void necessaryFailureRetainsOnlyItsProofNotFutureScheduleScratch() {
        try (var f = scene((x,y)->0,(x,y)->true,false,budget()); var evaluator = evaluator(f,PREFLIGHT)) {
            long before = f.ledger().currentBytes();
            var result = evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(1,result.exactSlabPreflight().orElseThrow().slabs().size());
            assertEquals(41,result.plannedProbeCount());
            long retained = f.ledger().currentBytes()-before;
            assertTrue(retained < 2048,
                "One scalar slab/two endpoints and an empty-mode refusal fit below 2 KiB; the 41-point schedule is scratch, retained="+retained);
        }
    }

    @Test void invalidSupportAndMissingZeroDirectionKeepTheirEarlierReasons() {
        try (var f = scene((x,y) -> 0, (x,y) -> x != 20 || y != 32, false, budget());
             var evaluator = evaluator(f, PREFLIGHT)) {
            var r = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Reason.SCALAR_SUPPORT, r.reason()); assertTrue(r.exactSlabPreflight().isEmpty());
        }
        try (var f = scene((x,y) -> 0, (x,y) -> true, true, budget());
             var evaluator = evaluator(f, PREFLIGHT)) {
            var r = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Reason.INCOMING_DIRECTION_UNAVAILABLE, r.reason());
            assertTrue(r.exactSlabPreflight().isEmpty()); assertTrue(r.probes().isEmpty());
        }
    }

    @Test void positiveShoulderGuardIsExactDefaultVersionAndRejectsHalfSubnormalTie() {
        var defaults = EvidenceModelParameters.defaults();
        assertTrue(NativeConnectorEvaluator.positiveShoulderInvariant(defaults));
        assertEquals(Double.MIN_VALUE, .72 * Double.MIN_VALUE, 0);
        assertEquals(0, .5 * Double.MIN_VALUE, 0);
        for (double fraction : new double[]{0, .5})
            assertFalse(NativeConnectorEvaluator.positiveShoulderInvariant(withShoulder(defaults, fraction)));
        assertFalse(NativeConnectorEvaluator.positiveShoulderInvariant(new EvidenceModelParameters("unsupported-version",
            defaults.dataWeight(), defaults.centerWeight(), defaults.turnWeight(), defaults.orientationWeight(),
            defaults.guideWeight(), defaults.turnScaleRadians(), defaults.temperature(), defaults.ambiguityEnergyDelta(), defaults.localization())));
    }

    @Test void globalZeroHintOverPositiveSlabsCannotRejectAndFinalChecksReuseExactResults() {
        try (var f = scene((x,y) -> .8, (x,y) -> true, false, budget()); var evaluator = evaluator(f,PREFLIGHT)) {
            var context = f.input().context(); var grid = context.nativeGrid();
            var globalHint = new BilinearSegmentIntegral.Result(BilinearSegmentIntegral.Status.COMPLETE,
                java.util.OptionalDouble.of(.8), java.util.OptionalDouble.of(0), 1, 4, BilinearSegmentIntegral.Reason.NONE);
            assertTrue(NativeConnectorEvaluator.shouldRunExactPreflight(PREFLIGHT, EvidenceModelParameters.defaults(), globalHint));
            long retained = f.ledger().currentBytes();
            try (var destination = f.owner().child("published-cache-proof")) {
                try (var cache = NativeConnectorEvaluator.ExactSlabCache.create(evaluator,
                        new RasterPoint(10,32), new RasterPoint(30,32), new double[]{0,.5,1}, f.owner())) {
                    assertEquals(-1, cache.preflight(), "A global zero is only a hint; all actual slabs are positive");
                    var proof = cache.snapshot(destination);
                    assertEquals(2,proof.slabs().size());
                    assertEquals(-1, proof.necessaryFailureSlabIndex());
                    long afterPreflight = units(f,EmissionWorkBudget.Operation.CELL_QUERY);
                    for (var slab : proof.slabs()) {
                        assertTrue(slab.result().minimum().orElseThrow() > 0);
                        assertTrue(slab.result().nodeReads() > 0);
                        assertSame(slab.result(), cache.finalResult(slab.slabIndex(), slab.from(), slab.to(),
                            f.input(), EvidenceModelParameters.defaults().version()));
                    }
                    assertEquals(afterPreflight, units(f,EmissionWorkBudget.Operation.CELL_QUERY), "Final slab reuse must perform no native reads");
                    assertEquals(2, cache.snapshot(destination).finalSlabCacheHits());
                }
                assertTrue(f.ledger().currentBytes() > retained, "Published exact scalar identities remain charged after cache close");
            }
            assertEquals(retained, f.ledger().currentBytes());
        }
    }

    @Test void exactCacheKeysRejectForeignVersionEndpointBitsAndClosedLifetime() {
        try (var f = scene((x,y) -> .8, (x,y) -> true, false, budget());
             var foreign = scene((x,y) -> .8, (x,y) -> true, false, budget()); var evaluator = evaluator(f,PREFLIGHT)) {
            var grid = f.input().context().nativeGrid(); String version = EvidenceModelParameters.defaults().version();
            var cache = NativeConnectorEvaluator.ExactSlabCache.create(evaluator,
                new RasterPoint(10,32), new RasterPoint(Math.nextUp(10.0),32), new double[]{0,Math.nextDown(1.0),1}, f.owner());
            RasterPoint from, to;
            try (cache) {
                assertEquals(-1, cache.preflight()); from = cache.pointAt(0); to = cache.pointAt(1);
                assertThrows(IllegalArgumentException.class, () -> cache.finalResult(0,from,to,foreign.input(),version));
                assertThrows(IllegalArgumentException.class, () -> cache.finalResult(0,from,to,f.input(),"foreign-version"));
                assertThrows(IllegalArgumentException.class, () -> cache.finalResult(0,
                    new RasterPoint(Math.nextUp(from.x()),from.y()),to,f.input(),version));
            }
            assertThrows(IllegalStateException.class, () -> cache.finalResult(0,from,to,f.input(),version));
        }
    }

    @Test void positiveTinyActualScalarIsNeverAZeroHint() {
        var tiny = new BilinearSegmentIntegral.ReadOnlyScalarGrid() {
            public int width() { return 2; } public int height() { return 2; }
            public boolean supportsCell(int x,int y) { return true; }
            public double nodeValue(int x,int y) { return Double.MIN_VALUE; }
        };
        var scalar = BilinearSegmentIntegral.evaluate(tiny,new RasterPoint(0,0),new RasterPoint(0,0),CancellationProbe.NONE,()->{ });
        assertEquals(Double.MIN_VALUE, scalar.minimum().orElseThrow(), 0);
        assertFalse(NativeConnectorEvaluator.shouldRunExactPreflight(PREFLIGHT,EvidenceModelParameters.defaults(),scalar));
        try (var f = scene((x,y) -> Float.MIN_VALUE, (x,y) -> true, false, budget()); var evaluator = evaluator(f,PREFLIGHT)) {
            var result = evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertNotEquals(Reason.EXACT_ZERO_SLAB_NECESSARY_FAILURE,result.reason());
            assertTrue(result.exactSlabPreflight().isEmpty());
        }
    }

    @Test void cornerEdgeAndOneUlpSubslabsNeverTurnAZeroHintIntoProof() {
        try (var f = scene((x,y)->.8,(x,y)->true,false,budget()); var evaluator = evaluator(f,PREFLIGHT)) {
            for (var endpoints : List.of(
                    List.of(new RasterPoint(10,10),new RasterPoint(11,11)),
                    List.of(new RasterPoint(10,10),new RasterPoint(10,11)),
                    List.of(new RasterPoint(Math.nextDown(10.0),10),new RasterPoint(Math.nextUp(10.0),10)))) {
                try (var cache = ExactSlabCache.create(evaluator,endpoints.get(0),endpoints.get(1),
                        new double[]{0,Math.nextDown(.5),.5,1},f.owner()); var proofOwner = f.owner().child("boundary-proof")) {
                    assertEquals(-1,cache.preflight());
                    for (var slab : cache.snapshot(proofOwner).slabs()) {
                        assertEquals(BilinearSegmentIntegral.Status.COMPLETE,slab.result().status());
                        assertTrue(slab.result().minimum().orElseThrow()>0);
                    }
                }
            }
        }
    }

    @Test void sourceAuthorityStillPrecedesAnyScalarPreflight() {
        var decision = new MetricRegion(List.of(
            List.of(new MetricPoint(0,0),new MetricPoint(17,0),new MetricPoint(17,63),new MetricPoint(0,63)),
            List.of(new MetricPoint(23,0),new MetricPoint(63,0),new MetricPoint(63,63),new MetricPoint(23,63))));
        try (var f = scene((x,y)->0,(x,y)->true,List.of(new MetricPoint(10,32),new MetricPoint(30,32)),
                List.of(0.0,20.0),decision,budget()); var evaluator = evaluator(f,PREFLIGHT)) {
            long before = units(f,EmissionWorkBudget.Operation.CELL_QUERY);
            var result = evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(Reason.DECISION_AUTHORITY,result.reason());
            assertTrue(result.exactSlabPreflight().isEmpty());
            assertEquals(before,units(f,EmissionWorkBudget.Operation.CELL_QUERY));
        }
    }

    @Test void zeroLengthPreflightNeedsAndRetainsActualOrdinaryDirection() {
        try (var f = scene((x,y)->0,(x,y)->true,
                List.of(new MetricPoint(10,32),new MetricPoint(20,32),new MetricPoint(20,32)),
                List.of(0.0,10.0,20.0),MetricRegion.rectangle(0,0,63,63),budget());
             var evaluator = evaluator(f,PREFLIGHT)) {
            var direction = evaluator.registerOrdinaryPhysicalLeg(f.states().get(0),f.states().get(1),NativeReferenceStatus.UNAVAILABLE);
            assertEquals(10,direction.lengthMeters(),0);
            var result = evaluator.evaluateWithOrdinaryIncoming(f.states().get(1),f.states().get(2),direction);
            assertEquals(Reason.EXACT_ZERO_SLAB_NECESSARY_FAILURE,result.reason());
            assertSame(direction,result.transportedOrdinary().orElseThrow());
            var proof = result.exactSlabPreflight().orElseThrow();
            assertEquals(1,proof.slabs().size());
            assertEquals(proof.slabs().get(0).from(),proof.slabs().get(0).to());
            assertTrue(result.probes().isEmpty());
        }
    }

    @Test void ordinaryLegFactsRemainIndependentOfOptionalSlabFailure() {
        try (var f = scene((x,y)->0,(x,y)->true,false,budget());
             var old = evaluator(f,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1); var fresh = evaluator(f,PREFLIGHT)) {
            var a = old.registerOrdinaryPhysicalLeg(f.states().get(0),f.states().get(1),NativeReferenceStatus.UNAVAILABLE);
            var b = fresh.registerOrdinaryPhysicalLeg(f.states().get(0),f.states().get(1),NativeReferenceStatus.UNAVAILABLE);
            assertEquals(a.fromRaster(),b.fromRaster()); assertEquals(a.toRaster(),b.toRaster());
            assertEquals(a.fromMetric(),b.fromMetric()); assertEquals(a.toMetric(),b.toMetric());
            assertEquals(a.lengthMeters(),b.lengthMeters(),0); assertEquals(a.nativeReferenceStatus(),b.nativeReferenceStatus());
            assertEquals(Reason.EXACT_ZERO_SLAB_NECESSARY_FAILURE,
                fresh.evaluate(f.states().get(0),f.states().get(1)).reason());
            assertEquals(NativeReferenceStatus.UNAVAILABLE,b.nativeReferenceStatus());
        }
    }

    @Test void workAndPairRefusalsDuringPreflightAreStickyBeforeModeExtraction() {
        for (var kind : EmissionWorkBudget.LimitKind.values()) {
            var armed = new java.util.concurrent.atomic.AtomicBoolean(); var injected = new java.util.concurrent.atomic.AtomicBoolean();
            EmissionWorkBudget[] holder = new EmissionWorkBudget[1];
            var work = new EmissionWorkBudget(TraceBudgets.defaults(),()->{
                if (armed.get() && inPreflight() && injected.compareAndSet(false,true)) {
                    var s = holder[0].snapshot();
                    if (kind == EmissionWorkBudget.LimitKind.TOTAL_WORK)
                        holder[0].charge(EmissionWorkBudget.Operation.PREPARATION,s.workLimit()-s.workUsed());
                    else holder[0].charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,s.pairLimit()-s.pairVisits()+1);
                }
                return false;
            }); holder[0] = work;
            try (var f = scene((x,y)->0,(x,y)->true,false,work); var evaluator = evaluator(f,PREFLIGHT)) {
                long modes = units(f,EmissionWorkBudget.Operation.MODE_EXTRACTION); armed.set(true);
                var failure = assertThrows(EmissionWorkBudget.LimitExceededException.class,
                    ()->evaluator.evaluate(f.states().get(0),f.states().get(1)));
                assertTrue(injected.get()); assertEquals(kind,failure.kind());
                assertEquals(modes,units(f,EmissionWorkBudget.Operation.MODE_EXTRACTION));
                var after = work.snapshot();
                assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,
                    ()->evaluator.evaluate(f.states().get(0),f.states().get(1))));
                assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,()->evaluator(f,PREFLIGHT)));
                assertEquals(after,work.snapshot());
            }
        }
    }

    @Test void cancellationDuringPreflightIsStickyAndReleasesItsScope() {
        var armed = new java.util.concurrent.atomic.AtomicBoolean(); var visits = new java.util.concurrent.atomic.AtomicInteger();
        var work = new EmissionWorkBudget(TraceBudgets.defaults(),()->armed.get() && inPreflight() && visits.incrementAndGet() >= 3);
        try (var f = scene((x,y)->0,(x,y)->true,false,work)) {
            long baseline = f.ledger().currentBytes();
            try (var evaluator = evaluator(f,PREFLIGHT)) {
                armed.set(true);
                var failure = assertThrows(java.util.concurrent.CancellationException.class,
                    ()->evaluator.evaluate(f.states().get(0),f.states().get(1)));
                assertTrue(visits.get() >= 3);
                assertSame(failure,assertThrows(java.util.concurrent.CancellationException.class,
                    ()->evaluator.evaluate(f.states().get(0),f.states().get(1))));
            }
            assertEquals(baseline,f.ledger().currentBytes());
        }
    }

    @Test void memoryPressureDuringPreflightIsStickyAndRetainsNoScratchAfterClose() {
        var armed = new java.util.concurrent.atomic.AtomicBoolean(); var injected = new java.util.concurrent.atomic.AtomicBoolean();
        AttemptMemoryLedger.Reservation[] held = new AttemptMemoryLedger.Reservation[1]; Scene[] holder = new Scene[1];
        var work = new EmissionWorkBudget(TraceBudgets.defaults(),()->{
            if (armed.get() && inPreflight() && injected.compareAndSet(false,true)) {
                var f = holder[0]; held[0] = f.owner().reserve(f.ledger().limitBytes()-f.ledger().currentBytes()-64);
            }
            return false;
        });
        try (var f = scene((x,y)->0,(x,y)->true,false,work)) {
            holder[0] = f; long baseline = f.ledger().currentBytes();
            try (var evaluator = evaluator(f,PREFLIGHT)) {
                armed.set(true);
                var failure = assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    ()->evaluator.evaluate(f.states().get(0),f.states().get(1)));
                assertTrue(injected.get());
                held[0].close(); held[0] = null;
                assertSame(failure,assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    ()->evaluator.evaluate(f.states().get(0),f.states().get(1))));
            } finally { if (held[0] != null) held[0].close(); }
            assertEquals(baseline,f.ledger().currentBytes());
        }
    }

    private static boolean inPreflight() {
        return StackWalker.getInstance().walk(frames->frames.anyMatch(frame->
            frame.getClassName().contains("NativeConnectorEvaluator$ExactSlabCache") && frame.getMethodName().equals("preflight")));
    }

    private static EvidenceModelParameters withShoulder(EvidenceModelParameters p, double fraction) {
        var x = p.localization();
        var local = new EvidenceModelParameters.Localization(x.orientationHeadingCount(), x.minimumOrientationRayMeters(),
            x.orientationRayLengthPitches(), x.maximumOrientationStepPitches(), x.minimumOrientationValidFraction(),
            x.orientationBackgroundQuantile(), x.orientationProminenceFraction(), x.maximumOrientationSampleCount(),
            x.localModeProminenceFraction(), fraction, x.localModeCoreFraction(), x.routeProfileHalfWidthMeters(), x.scalarAmplitudeHalfResponse());
        return new EvidenceModelParameters(p.version(), p.dataWeight(), p.centerWeight(), p.turnWeight(), p.orientationWeight(),
            p.guideWeight(), p.turnScaleRadians(), p.temperature(), p.ambiguityEnergyDelta(), local);
    }
    private static NativeConnectorEvaluator evaluator(Scene f, ExecutionPolicy policy) {
        return new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), QueryPolicy.ZERO_GUARDED_LOCAL_V1, policy);
    }
    private static long units(Scene f, EmissionWorkBudget.Operation operation) { return f.work().snapshot().operationUnits().get(operation); }
    private static EmissionWorkBudget budget() { return new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE); }
    private record Scene(AttemptMemoryLedger ledger, AttemptMemoryLedger.Owner owner, SampledEmissionInput input,
            List<RowState> states, EmissionWorkBudget work) implements AutoCloseable {
        @Override public void close() { input.close(); owner.close(); assertEquals(0, ledger.currentBytes()); }
    }
    private static Scene scene(DoubleBinaryOperator intensity, BiPredicate<Integer,Integer> validity,
            boolean coincident, EmissionWorkBudget work) {
        return scene(intensity,validity,List.of(new MetricPoint(10,32),new MetricPoint(coincident?10:30,32)),
            List.of(0.0,20.0),MetricRegion.rectangle(0,0,63,63),work);
    }
    private static Scene scene(DoubleBinaryOperator intensity,BiPredicate<Integer,Integer> validity,
            List<MetricPoint> anchors,List<Double> chainage,MetricRegion decision,EmissionWorkBudget work) {
        int size = 64; var values = new double[size * size]; var valid = new boolean[values.length];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) { values[y * size + x] = intensity.applyAsDouble(x,y); valid[y * size + x] = validity.test(x,y); }
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0), new GeographicPoint(-.01,-.01), new GeographicPoint(.01,.01));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var field = new ScalarEvidenceField(size,size,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var region = MetricRegion.rectangle(0,0,63,63);
        var evidence = new EvidenceSnapshot("exact-slab",frame,transform,EvidenceResolution.nativeSource(1,1),decision,region,Map.of("native",field),"exact-slab");
        var locations = new java.util.ArrayList<DetachedProfileSamplingLocation>();
        for (int i = 0; i < anchors.size(); i++) { var point = anchors.get(i);
            locations.add(new DetachedProfileSamplingLocation(frame.toGeographic(point),point,
                new RasterPoint(point.xMeters(),point.yMeters()),frame,transform,chainage.get(i))); }
        var ledger = AttemptMemoryLedger.production(); var owner = ledger.rootOwner();
        try {
            var input = new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(evidence,"native",new CorridorTraceInput(locations,1),
                new ProfileChainage(chainage,20),8,work,owner);
            var states = java.util.stream.IntStream.range(0,anchors.size()).mapToObj(i->new RowState(i,0,0,SourceClass.GAP,null)).toList();
            return new Scene(ledger,owner,input,states,work);
        } catch (RuntimeException failure) { owner.close(); throw failure; }
    }
}
