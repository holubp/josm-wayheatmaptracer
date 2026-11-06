package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022AdaptiveBSolverTest {
    @Test void cachedPreEntryAccountingDenialUsesOwnedTypedMemoryBoundary() {
        var p=V022ExactKBestExecutionTest.profile(0,new double[]{0,0},new double[]{1,1});
        var ledger=new AttemptMemoryLedger(1);
        try(var owner=ledger.rootOwner()) {
            var denied=assertThrows(TraceMemoryLimitException.class,() -> new ProbabilisticInference().solve(
                    List.of(p),EvidenceModelParameters.defaults(),new TraceBudgets(1,8_000_000,128_000_000,32,8),
                    null,CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4));
            assertEquals(0,denied.transitions());
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void earlyCachedRefusalsHaveTypedZeroWorkWithoutChangingExactCompatibility() {
        for (boolean orientation : new boolean[]{false,true}) {
            var p=sampled(false);
            var lattice=new ProbabilisticStateBuilder().build(p,96).lattice().orElseThrow();
            var evaluated=new ProbabilisticObservationModel().evaluate(p,lattice,EvidenceModelParameters.defaults());
            var dense=orientation ? new InferenceProfile(p.chainageMeters(),p.anchor(),p.normalUnit(),
                    evaluated.cells(),evaluated.unaryCosts(),ImageOrientationSupport.unknown(
                    ImageOrientationSupport.Status.RESOURCE_LIMIT),evaluated.ownership(),false) : evaluated;
            var budget=orientation ? TraceBudgets.defaults() : new TraceBudgets(1,8_000_000,128_000_000,32,8);
            var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var old=new ProbabilisticInference().solve(List.of(dense),EvidenceModelParameters.defaults(),
                        budget,null,CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
                assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,old.status());
                assertTrue(old.workAccounting().isEmpty(),"historical exact pre-entry receipt remains unattested");
                for(var policy:List.of(BPolicy.ADAPTIVE_MULTIMODAL_V1,BPolicy.ADAPTIVE_MULTIMODAL_V2)) {
                    int[] observed={0}; long[] progress={-1,-1};
                    var result=assertDoesNotThrow(() -> new AdaptiveBInference().solve(List.of(p),List.of(dense),
                            List.of(lattice),new double[]{Double.NaN},EvidenceModelParameters.defaults(),budget,null,
                            CancellationProbe.NONE,owner,(pass,row,index,original) -> observed[0]++,
                            (pairs,scores) -> {progress[0]=pairs;progress[1]=scores;},policy));
                    var refusal=result.inference();
                    assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,refusal.status());
                    assertTrue(refusal.explanation().contains(orientation ? "orientation descriptor resource limit" : "state budget exceeded"));
                    assertEquals(0,result.refinements()); assertEquals(0,result.firstScores());
                    assertEquals(0,result.secondScores()); assertTrue(refusal.rawPaths().isEmpty());
                    assertTrue(refusal.mapPath().isEmpty()); assertFalse(refusal.posteriorUsable());
                    var receipt=refusal.workAccounting().orElseThrow();
                    assertEquals(policy.version(),receipt.executionPolicy()); assertEquals(0,receipt.computedScores());
                    assertEquals(0,receipt.refusedScoreAttempts()); assertArrayEquals(new long[]{0,0},progress);
                    assertTrue(observed[0]>0,"prepared view provenance remains observable");
                }
            }
            assertEquals(0,ledger.currentBytes());
        }
    }

    @Test void retainedViewKeepsFaintRemoteModesContinuationAndDenseUnaryBits() {
        var profile = sampled(false);
        var builder = new ProbabilisticStateBuilder();
        var lattice = builder.buildWithProposal(profile, 96, 9.125, null).lattice().orElseThrow();
        var dense = new ProbabilisticObservationModel().evaluate(profile, lattice, EvidenceModelParameters.defaults());
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            var view = AdaptiveSolverView.initial(profile, dense, lattice.components(), 9.125,
                    CancellationProbe.NONE, owner);
            assertTrue(view.size() < dense.cells().size());
            assertEquals(-25, view.profile().cells().get(0).offsetMeters());
            assertEquals(25, view.profile().cells().get(view.size() - 1).offsetMeters());
            for (int i = 0; i < dense.cells().size(); i++) {
                var cell = dense.cells().get(i);
                boolean core = profile.modes().stream().anyMatch(m -> cell.offsetMeters()
                        >= m.coreMinimumMeters() - 0.5 * profile.sourcePitchMeters()
                        && cell.offsetMeters() <= m.coreMaximumMeters() + 0.5 * profile.sourcePitchMeters());
                if (cell.mandatory() || core || cell.offsetMeters() == 9.125)
                    assertTrue(java.util.stream.IntStream.range(0, view.size())
                            .anyMatch(j -> view.sourceIndexAt(j) == dense.cells().indexOf(cell)));
            }
            int[] indices = view.copySourceIndices(owner);
            for (int i = 0; i < indices.length; i++) {
                assertSame(dense.cells().get(indices[i]), view.profile().cells().get(i));
                assertEquals(Double.doubleToRawLongBits(dense.unaryCost(indices[i])),
                        Double.doubleToRawLongBits(view.profile().unaryCost(i)));
            }
            indices[0] = 999;
            assertEquals(0, view.sourceIndexAt(0), "provenance must be immutable");
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void fixedAnchorAndCancellationFailClosed() {
        var p = sampled(true);
        var lattice = new ProbabilisticStateBuilder().build(p, 96).lattice().orElseThrow();
        var dense = new ProbabilisticObservationModel().evaluate(p, lattice, EvidenceModelParameters.defaults());
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            assertEquals(1, AdaptiveSolverView.initial(p, dense, lattice.components(), Double.NaN,
                    CancellationProbe.NONE, owner).size());
            assertThrows(CancellationException.class, () -> AdaptiveSolverView.initial(p, dense,
                    lattice.components(), Double.NaN, () -> true, owner));
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void cachedFullGraphMatchesOldExactBitForBitWithUnequalRowsAndTies() {
        var input = List.of(V022ExactKBestExecutionTest.profile(0, new double[]{0, 0}, new double[]{.5,1}),
                V022ExactKBestExecutionTest.profile(2, new double[]{.1,-.2,.3}, new double[]{1,.75,1.5}),
                V022ExactKBestExecutionTest.profile(5, new double[]{0,.2}, new double[]{1,2}));
        var profiles = input.stream().map(p -> new InferenceProfile(p.chainageMeters(),p.anchor(),p.normalUnit(),
                p.cells(),p.unaryCosts(),List.of(.3,.9),.75,p.ownership(),false)).toList();
        var parameters = EvidenceModelParameters.defaults();
        var budgets = new TraceBudgets(96, 8_000_000, 128_000_000, 32, 8);
        var ledger = AttemptMemoryLedger.production();
        try (var a = ledger.rootOwner(); var b = a.child("cached-test")) {
            var solver = new ProbabilisticInference();
            var old = solver.solve(profiles, parameters, budgets, null, CancellationProbe.NONE, a,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            var cached = solver.solveCached(profiles, parameters, budgets, null, CancellationProbe.NONE, b);
            assertEquals(Double.doubleToRawLongBits(old.logPartition()), Double.doubleToRawLongBits(cached.logPartition()));
            assertEquals(old.evaluatedTransitions(), cached.evaluatedTransitions());
            assertEquals(old.rawPaths().size(), cached.rawPaths().size());
            for (int i=0;i<old.rawPaths().size();i++) {
                assertArrayEquals(old.rawPaths().get(i).stateIndices(), cached.rawPaths().get(i).stateIndices());
                assertEquals(Double.doubleToRawLongBits(old.rawPaths().get(i).energy()),
                        Double.doubleToRawLongBits(cached.rawPaths().get(i).energy()));
            }
            for(int i=0;i<profiles.size();i++) assertArrayEquals(old.positionMarginals().get(i),
                    cached.positionMarginals().get(i));
            assertEquals("cached-pair-exact-scored-work-v3", cached.workAccounting().orElseThrow().executionPolicy());
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void coarseRivalsGetAtMostOneRefinementAndAConditionalReviewWithCumulativeWork() {
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            List<ProbabilisticProfile> sampled = new ArrayList<>();
            List<ProbabilisticStateLattice> lattices = new ArrayList<>();
            List<InferenceProfile> dense = new ArrayList<>();
            for (int row = 0; row < 3; row++) {
                var p = sampled(false);
                p = new ProbabilisticProfile(row, row * 2, new MetricPoint(row * 2,0), p.normalUnit(),
                        p.minimumOffsetMeters(),p.maximumOffsetMeters(),p.sourcePitchMeters(),true,p.noiseFloor(),
                        p.samples(),p.modes(),p.censoredModes(),p.exactAnchorOffsetMeters(),p.orientationSupport());
                sampled.add(p);
                var lattice = new ProbabilisticStateBuilder().buildWithProposal(p,96,9.125,null).lattice().orElseThrow();
                lattices.add(lattice);
                double[] unary = new double[lattice.cells().size()];
                for(int i=0;i<unary.length;i++) unary[i] = lattice.cells().get(i).offsetMeters()==9.125 ? 0 : 100;
                dense.add(new InferenceProfile(p.chainageMeters(),p.anchor(),p.normalUnit(),lattice.cells(),
                        unary,List.of(),0,ObservationOwnership.DIRECT_TWO_SIDED,false));
            }
            var result = new AdaptiveBInference().solve(sampled,dense,lattices,new double[]{9.125,9.125,9.125},
                    EvidenceModelParameters.withoutShapeTerms(),TraceBudgets.defaults(),null,CancellationProbe.NONE,owner);
            assertEquals(1,result.refinements(),"every retained coarse rival triggers one recorded refinement");
            assertEquals(ProbabilisticInferenceResult.Status.REVIEW_REQUIRED,result.inference().status());
            assertFalse(result.inference().posteriorUsable(),"conditional retained-graph mass is not full-graph certainty");
            assertEquals(result.firstScores()+result.secondScores(),result.inference().evaluatedTransitions());
            assertTrue(result.firstScores()>0 && result.secondScores()>0);
            int initialWidth=AdaptiveSolverView.initial(sampled.get(0),dense.get(0),lattices.get(0).components(),
                    9.125,CancellationProbe.NONE,owner).size();
            long[] progress={-1,-1};
            var secondRefusal=new AdaptiveBInference().solve(sampled,dense,lattices,new double[]{9.125,9.125,9.125},
                    EvidenceModelParameters.withoutShapeTerms(),new TraceBudgets(initialWidth,8_000_000,128_000_000,32,8),
                    null,CancellationProbe.NONE,owner,AdaptiveBObserver.NONE,
                    (pairs,scores) -> {progress[0]=pairs;progress[1]=scores;},BPolicy.ADAPTIVE_MULTIMODAL_V2);
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,secondRefusal.inference().status());
            assertTrue(secondRefusal.inference().explanation().contains("state budget exceeded"));
            assertEquals(1,secondRefusal.refinements()); assertTrue(secondRefusal.firstScores()>0);
            assertEquals(0,secondRefusal.secondScores());
            assertEquals(secondRefusal.firstScores(),secondRefusal.inference().workAccounting().orElseThrow().computedScores());
            assertEquals(secondRefusal.firstScores(),progress[1],"second zero-work refusal preserves completed first pass");
            assertTrue(secondRefusal.inference().rawPaths().isEmpty());
            assertEquals(BPolicy.VERSION,result.inference().workAccounting().orElseThrow().executionPolicy());
            long limit = result.firstScores() + 1;
            var capped = new AdaptiveBInference().solve(sampled,dense,lattices,new double[]{9.125,9.125,9.125},
                    EvidenceModelParameters.withoutShapeTerms(),new TraceBudgets(96,8_000_000,limit,32,8),
                    null,CancellationProbe.NONE,owner);
            assertEquals(1,capped.refinements());
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,capped.inference().status());
            assertEquals(limit,capped.inference().evaluatedTransitions());
            assertEquals(1,capped.secondScores());
            assertTrue(capped.inference().rawPaths().isEmpty());
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void cachedPreparationCancellationAndMemoryRefusalReleaseAllOwnedScratch() {
        var p = List.of(V022ExactKBestExecutionTest.profile(0,new double[]{0,1},new double[]{1,1}),
                V022ExactKBestExecutionTest.profile(2,new double[]{0,1},new double[]{1,1}),
                V022ExactKBestExecutionTest.profile(4,new double[]{0,1},new double[]{1,1}));
        var ledger = AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            int[] visits={0};
            assertThrows(CancellationException.class,() -> new ProbabilisticInference().solveCached(p,
                    EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,() -> ++visits[0]==10,owner));
            assertEquals(0,ledger.currentBytes());
        }
        var small = new AttemptMemoryLedger(512);
        try(var owner=small.rootOwner()) {
            var refused = new ProbabilisticInference().solveCached(p,EvidenceModelParameters.defaults(),
                    TraceBudgets.defaults(),null,CancellationProbe.NONE,owner);
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,refused.status());
            assertTrue(refused.explanation().contains("cached pair tables"));
            assertTrue(refused.rawPaths().isEmpty());
            assertEquals(0,refused.evaluatedTransitions());
        }
        assertEquals(0,small.currentBytes());
    }

    @Test void cancelledCachedPassReportsActualScoredWorkBeforeReleasingScratch() {
        List<InferenceProfile> input=new ArrayList<>();
        for(int i=0;i<5;i++) input.add(V022ExactKBestExecutionTest.profile(i*2,new double[16],
                V022ExactKBestExecutionTest.ones(16)));
        long[] work={-1,-1};int[] checks={0};
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            assertThrows(CancellationException.class,() -> new ProbabilisticInference().solveCached(input,
                    EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,() -> {
                        boolean inside=Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
                                frame.getClassName().equals(ProbabilisticInference.class.getName())
                                && frame.getMethodName().equals("forwardBackward"));
                        return inside && ++checks[0]==3;
                    },owner,(pairs,scores) -> { work[0]=pairs;work[1]=scores; }));
            assertEquals(1024,work[1],"completed actual scores survive cancellation");
            assertTrue(work[0]>0);
            assertEquals(0,ledger.currentBytes());
        }
    }

    @Test void cachedDisconnectedGraphAndReversedPhysicalOrderMatchOldExact() {
        var profiles = List.of(V022ExactKBestExecutionTest.profile(0,new double[]{0,0},new double[]{1,1}),
                V022ExactKBestExecutionTest.profile(2,new double[]{0,0},new double[]{1,1}),
                V022ExactKBestExecutionTest.profile(5,new double[]{0,0},new double[]{1,1}));
        var region = new MetricRegion(List.of(
                List.of(new MetricPoint(-1,-1),new MetricPoint(.2,-1),new MetricPoint(.2,1),new MetricPoint(-1,1)),
                List.of(new MetricPoint(1.8,-1),new MetricPoint(6,-1),new MetricPoint(6,1),new MetricPoint(1.8,1))));
        for (boolean reverse : new boolean[]{false,true}) {
            var input = profiles;
            if(reverse) {
                List<InferenceProfile> reversed = new ArrayList<>();
                for(int i=0;i<profiles.size();i++) {
                    var p=profiles.get(profiles.size()-1-i);
                    reversed.add(new InferenceProfile(i*2,p.anchor(),p.normalUnit(),p.cells(),p.unaryCosts(),
                            List.of(.3),.7,p.ownership(),false));
                }
                input=reversed;
            }
            var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var old=new ProbabilisticInference().solve(input,EvidenceModelParameters.defaults(),TraceBudgets.defaults(),
                        region,CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
                var cached=new ProbabilisticInference().solveCached(input,EvidenceModelParameters.defaults(),
                        TraceBudgets.defaults(),region,CancellationProbe.NONE,owner);
                assertEquals(ProbabilisticInferenceResult.Status.NO_ROUTE,old.status());
                assertEquals(old.status(),cached.status());
                assertEquals(old.evaluatedTransitions(),cached.evaluatedTransitions());
            }
            assertEquals(0,ledger.currentBytes());
        }
    }

    @Test void exactSingletonRowsRequireNoRefinementAndRemainConditional() {
        List<ProbabilisticProfile> sampled=new ArrayList<>();
        List<InferenceProfile> dense=new ArrayList<>();
        List<ProbabilisticStateLattice> lattices=new ArrayList<>();
        for(int row=0;row<3;row++) {
            var p=sampled(true);
            p=new ProbabilisticProfile(row,row*2,new MetricPoint(row*2,0),p.normalUnit(),p.minimumOffsetMeters(),
                    p.maximumOffsetMeters(),p.sourcePitchMeters(),true,p.noiseFloor(),p.samples(),p.modes(),
                    p.censoredModes(),p.exactAnchorOffsetMeters(),p.orientationSupport());
            var lattice=new ProbabilisticStateBuilder().build(p,96).lattice().orElseThrow();
            sampled.add(p);lattices.add(lattice);
            dense.add(new ProbabilisticObservationModel().evaluate(p,lattice,EvidenceModelParameters.defaults()));
        }
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var result=new AdaptiveBInference().solve(sampled,dense,lattices,new double[]{Double.NaN,Double.NaN,Double.NaN},
                    EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,CancellationProbe.NONE,owner);
            assertEquals(0,result.refinements());
            assertEquals(0,result.secondScores());
            assertEquals(ProbabilisticInferenceResult.Status.REVIEW_REQUIRED,result.inference().status());
            assertFalse(result.inference().posteriorUsable());
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void ordinaryPipelineSelectsTheFrozenAdaptivePolicyAndDoesNotExportFullPosterior() throws Exception {
        var evidence = V022LocalizationEvidenceTest.snapshot((x,y) -> .02 + .6 * Math.exp(-.5 * Math.pow(y-31,2)));
        var first = PrimitiveKey.existing(PrimitiveKey.Type.NODE,1);
        var last = PrimitiveKey.existing(PrimitiveKey.Type.NODE,2);
        var way = PrimitiveKey.existing(PrimitiveKey.Type.WAY,3);
        Map<PrimitiveKey,DetachedPrimitive> primitives = Map.of(
                first,new DetachedNode(first,evidence.coordinateFrame().toGeographic(new MetricPoint(50,30)),Map.of(),false,false),
                last,new DetachedNode(last,evidence.coordinateFrame().toGeographic(new MetricPoint(54,30)),Map.of(),false,false),
                way,new DetachedWay(way,List.of(first,last),Map.of("highway","path"),false,false));
        var region = evidence.decisionRegion();
        var closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,"adaptive-test",primitives.keySet(),
                Set.of(way),Set.of(),Set.of(first,last),Set.of(),Map.of(way,List.of(new OccurrenceRange(0,1))),
                List.of(),region,region,false,true,true,true);
        var network = new NetworkSnapshot("adaptive-network",SnapshotRole.CAPTURED_BEFORE,"adaptive-test",1,closure,
                primitives,V022SnapshotFixtures.closedWorldReferrerWatches(primitives));
        var source = List.of(new MetricPoint(50,30),new MetricPoint(54,30));
        var exact = new TraceRequest(way,new OccurrenceRange(0,1),TrackerMode.PROBABILISTIC,AlignmentMode.MOVE_EXISTING_NODES,
                RecoveryPermissions.disabled(25),TraceBudgets.defaults(),evidence.snapshotId(),evidence.canonicalHash(),
                network.snapshotId(),network.canonicalHash(),"settings",BPolicy.exactParameterHash(),"sampler",2,
                new ProbabilisticProfileFactory().profileChainage(source,2),evidence.resolution());
        var options = new ModernTracePipeline.Options("scalar",GeometryCleanupConfig.disabled(),"native",0);
        List<String> previousIndices=null;
        List<TraceHypothesis> previousHypotheses=null;
        for(var policy:List.of(BPolicy.ADAPTIVE_MULTIMODAL_V1,BPolicy.ADAPTIVE_MULTIMODAL_V2)) {
            var adaptive = BPolicy.adaptiveRequest(exact,policy);
            var frozen = new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput(
                    adaptive,evidence,network,options);
            var decoded = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec.decode(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec.encode(frozen));
            List<String> indices=new ArrayList<>();
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.begin();
            try(var observation=AdaptiveBObserver.observe((pass,row,compact,originalIndex) ->
                    indices.add(pass+","+row+","+compact+","+originalIndex))) {
                var result = new ModernTracePipeline((request,e,n,c) -> { throw new AssertionError("A must not run"); })
                        .run(decoded.request(),decoded.evidence(),decoded.network(),decoded.options(),CancellationProbe.NONE);
                assertTrue(result.inference().workAccounting().orElseThrow().hasPolicy(policy.version()));
                assertEquals(TraceHypothesisSet.Status.AMBIGUOUS,result.inference().status());
                assertFalse(result.inference().hypotheses().isEmpty());
                assertTrue(result.inference().hypotheses().stream().allMatch(h -> h.posteriorProbability().isEmpty()));
                assertTrue(result.inference().explanation().contains("originalIndexHash="));
                if(previousIndices!=null) {
                    assertEquals(previousIndices,indices,"V4 must not change initial or refined retained indices");
                    assertEquals(previousHypotheses.size(),result.inference().hypotheses().size());
                    for(int i=0;i<previousHypotheses.size();i++) {
                        assertEquals(previousHypotheses.get(i).points(),result.inference().hypotheses().get(i).points());
                        assertEquals(previousHypotheses.get(i).objective(),result.inference().hypotheses().get(i).objective());
                    }
                }
                previousIndices=List.copyOf(indices);previousHypotheses=result.inference().hypotheses();
                if(policy==BPolicy.ADAPTIVE_MULTIMODAL_V2) {
                    var counters=org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.snapshot();
                    assertTrue(counters.containsKey("b.merge.pass0.headScores"));
                    long scored=counters.get("b.merge.headScores").longValue()+counters.get("b.merge.advances").longValue();
                    assertEquals(result.inference().workAccounting().orElseThrow().bStages().get(0).kBestScores(),scored);
                    assertTrue(counters.size()<=128);
                }
            } finally { org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.end(); }
        }
    }

    private static ProbabilisticProfile sampled(boolean anchor) {
        List<ProbabilisticProfile.Sample> samples = new ArrayList<>();
        for(int i=-50;i<=50;i++) samples.add(new ProbabilisticProfile.Sample(i*.5,
                Math.exp(-Math.pow(i*.5-16,2))*.02 + Math.exp(-Math.pow(i*.5+7,2))*.7, true));
        var modes = List.of(new ProbabilisticProfile.Mode("near", "n", -7.5,-6.5,.5,.8,.8,
                        List.of(-7.0),List.of(-7.0),false),
                new ProbabilisticProfile.Mode("faint-remote", "f",15.5,16.5,.5,.02,.2,
                        List.of(16.0),List.of(16.0),false));
        return new ProbabilisticProfile(0,0,new MetricPoint(0,0),new MetricPoint(0,1),-25,25,2,true,
                .001,samples,modes,List.of(),anchor?OptionalDouble.of(0):OptionalDouble.empty(),
                ImageOrientationSupport.unknown(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT));
    }
}
