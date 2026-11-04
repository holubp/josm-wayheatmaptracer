package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;

class LegacyGroupedAllStageTest {
    @Test void actualConvergentPrefixesReplaceAComponentAndTieDominatedLabel() throws Exception {
        try(var f=gapFourFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))) {
            LegacyGroupedExhaustiveOracle.proveActualReplacement(f);
            var expected=LegacyGroupedExhaustiveOracle.enumerate(f,List.of(0.0),List.of());
            try(var actual=solve(f.input(),f.profiles(),List.of(0.0),List.of(),(p,o,i)->o,"EXACT_Q0_CUTOFF_V1",null)){assertOracle(expected,actual);}
        }
    }
    static LegacyCompetitiveProofTest.Fixture gapFourFixture(EmissionWorkBudget budget) {
        var source=LegacyEmissionStagesTest.fixture();var trace=new CorridorTraceInput(source.input().profileLocations().subList(0,4),1);
        var chainage=new ProfileChainage(List.of(0.0,8.0,16.0,24.0),24);var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(source.evidence(),"stage-ridge",trace,chainage,25,budget,owner)) {
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(source.evidence(),"stage-ridge",trace.profileLocations(),25,1,1,budget,permit);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,List.of(0,1,2,3),1,List.of());
            var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
            return new LegacyCompetitiveProofTest.Fixture(input,binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList(),ledger,owner);
        }catch(RuntimeException failure){owner.close();throw failure;}
    }
    @Test void q0OptInActuallyDefersCertificatesAndKeepsEveryGroupedDecision() throws Exception {
        try(var f=LegacyCompetitiveProofTest.fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var offProgress=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC);
            var onProgress=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC);
            var off=solve(f.input(),f.profiles(),List.of(0.0,.25),List.of(),(p,o,i)->o,"EXACT_VERSIONS_V1",offProgress);
            var on=solve(f.input(),f.profiles(),List.of(0.0,.25),List.of(),(p,o,i)->o,"EXACT_Q0_CUTOFF_V1",onProgress)) {
            assertSameDecisions(off,on);assertEquals(2,onProgress.view().count(LegacyEmissionProgress.Counter.STRICT_CERTIFICATE_DEFERRALS));
            assertEquals(0,offProgress.view().count(LegacyEmissionProgress.Counter.STRICT_CERTIFICATE_DEFERRALS));
            assertTrue(onProgress.view().count(LegacyEmissionProgress.Counter.EVALUATION_STARTED)<offProgress.view().count(LegacyEmissionProgress.Counter.EVALUATION_STARTED));
            assertTrue(onProgress.view().count(LegacyEmissionProgress.Counter.EXTRACTION_STARTED)<offProgress.view().count(LegacyEmissionProgress.Counter.EXTRACTION_STARTED));
            assertTrue(onProgress.view().hasGroupedExecution());assertFalse(onProgress.view().hasWitnessUpper(),"No active stage U remains at final publication");
            assertEquals(on.work(),f.input().workBudget().snapshot());assertTrue(onProgress.view().maxGroupedKeyLabels()>=2);
        }
    }
    static void assertSameDecisions(LegacyEmissionKernel.GroupedResult a,LegacyEmissionKernel.GroupedResult b) {
        var x=a.emission();var y=b.emission();assertEquals(a.stages(),b.stages());assertEquals(x.chosenSeed(),y.chosenSeed());
        assertEquals(x.firstStage(),y.firstStage());assertEquals(x.stitched(),y.stitched());assertEquals(x.postSmoothing(),y.postSmoothing());
        assertEquals(x.firstStageVertices(),y.firstStageVertices());assertEquals(x.inferenceVertices(),y.inferenceVertices());assertEquals(x.smoothedVertices(),y.smoothedVertices());
        assertEquals(x.eligibleAnchors(),y.eligibleAnchors());assertEquals(x.selectedAnchors(),y.selectedAnchors());
        assertEquals(x.intervalStages(),y.intervalStages());
        assertEquals(x.intervalStageReferences().stream().map(EmissionResult.IntervalStageReference::vertices).toList(),y.intervalStageReferences().stream().map(EmissionResult.IntervalStageReference::vertices).toList());
    }
    @Test void cutoffMatchesExhaustiveProtectedZeroAndCarryStages() throws Exception {
        for(int fixture=0;fixture<3;fixture++) {
            var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
            try(var f=fixture==0?stageFixture(budget):fixture==1?zeroFixture(budget):darkFixture(budget)) {
                var anchors=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
                if(fixture==0)for(int row:List.of(0,2,4,6))anchors.add(new LegacyEmissionKernel.AnchorProposal(row,f.profiles().get(row).peaks().get(0)));
                if(fixture==1)for(int row=0;row<4;row++)anchors.add(new LegacyEmissionKernel.AnchorProposal(row,f.profiles().get(row).peaks().get(0)));
                var seeds=List.of(0.0,.25);var expected=LegacyGroupedExhaustiveOracle.enumerate(f,seeds,anchors);
                try(var off=solve(f.input(),f.profiles(),seeds,anchors,(p,o,i)->o,"EXACT_VERSIONS_V1",null);
                    var on=solve(f.input(),f.profiles(),seeds,anchors,(p,o,i)->o,"EXACT_Q0_CUTOFF_V1",null)) {
                    assertOracle(expected,off);assertOracle(expected,on);assertSameDecisions(off,on);
                }
            }
        }
    }

    @Test void equalityUnknownOrdinarySupportAndNoPositiveWitnessNeverBecomeRefusalProofs() throws Exception {
        for(boolean missing:List.of(false,true)) {
            var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
            try(var f=LegacyCompetitiveProofTest.fixture(missing,!missing,budget,EvidenceResolution.nativeSource(1,1));
                var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
                double seed=f.profiles().get(0).peaks().stream().min(Comparator.comparingDouble(s->Math.abs(s.offsetPx()))).orElseThrow().offsetPx();
                var expected=LegacyCompetitiveProofTest.enumerate(f,seed).stream().min(LegacyCompetitiveProofTest::compare).orElseThrow();
                try(var result=solve(f.input(),f.profiles(),List.of(seed),List.of(),(a,b,c)->b,"EXACT_Q0_CUTOFF_V1",p)) {
                    assertEquals(expected.costs(),result.emission().firstStage());assertTrue(result.stages().get(0).ordinaryWitness().isPresent());
                    if(missing) {
                        assertTrue(result.stages().get(0).ordinaryWitness().orElseThrow().q()>=8);
                        assertTrue(result.emission().inferenceArcs().stream().anyMatch(a->a.kind()==EmissionResult.ArcKind.ORDINARY_UNAVAILABLE));
                    } else {
                        assertEquals(0,result.stages().get(0).ordinaryWitness().orElseThrow().total(),0);
                        assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.STRICT_CERTIFICATE_DEFERRALS));
                        assertEquals(1,p.view().count(LegacyEmissionProgress.Counter.EVALUATION_STARTED));
                    }
                }
            }
        }
        try(var f=LegacyCompetitiveZeroDirectionTest.fixture(true);
            var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            long bytes=f.ledger().currentBytes();
            var failure=assertThrows(LegacyEmissionKernel.UnavailableException.class,()->solve(f.input(),f.profiles(),List.of(0.0),List.of(),(a,b,c)->b,"EXACT_Q0_CUTOFF_V1",p));
            assertEquals(LegacyEmissionKernel.UnavailableReason.NO_POSITIVE_PHYSICAL_LEG,failure.reason());assertFalse(p.view().hasWitnessUpper());
            assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.WITNESS_CERTIFIED));
            assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.STRICT_CERTIFICATE_DEFERRALS));assertEquals(bytes,f.ledger().currentBytes());
        }
    }

    @Test void fixedProgressOverflowsExplicitlyWhileAllOwnedStageFactsAndPinsRemainExact() throws Exception {
        var source=LegacyEmissionStagesTest.fixture();var evidence=source.evidence();var frame=evidence.coordinateFrame();var transform=evidence.transform();
        int rows=10;var locations=new ArrayList<DetachedProfileSamplingLocation>();var events=new ArrayList<LegacyEmissionBinding.ProtectedOccurrence>();
        for(int row=0;row<rows;row++) {
            locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(new MetricPoint(24+.25*row,64.22)),frame,transform,.25*row));
            if(row+1<rows)events.add(new LegacyEmissionBinding.ProtectedOccurrence("overflow-fixed-"+row,row,row+1,.25*row+.125,new MetricPoint(24+.25*row+.125,64.35),true));
        }
        var trace=new CorridorTraceInput(locations,1);var chainage=new ProfileChainage(locations.stream().map(DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList(),.25*(rows-1));
        var ledger=new AttemptMemoryLedger(4_000_000);var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var owner=ledger.rootOwner();var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,"stage-ridge",trace,chainage,25,budget,owner)) {
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,"stage-ridge",trace.profileLocations(),25,1,1,budget,permit);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,java.util.stream.IntStream.range(0,rows).boxed().toList(),1,events);
            try(var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
                var p=LegacyEmissionProgress.open(input,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
                var profiles=binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList();var anchors=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
                for(int row=0;row<rows;row++)anchors.add(new LegacyEmissionKernel.AnchorProposal(row,profiles.get(row).peaks().get(0)));
                try(var result=solve(input,profiles,Collections.nCopies(10,0.0),anchors,(a,b,c)->b,"EXACT_Q0_CUTOFF_V1",p)) {
                    assertEquals(19,result.stages().size());assertTrue(result.stages().stream().allMatch(s->s.ordinaryWitness().isPresent()));
                    assertEquals(2,p.view().seedOverflow());assertTrue(p.view().stageOverflow()>0);assertFalse(p.view().hasWitnessUpper());
                    assertEquals(result.emission().firstStage(),result.emission().stitched());assertEquals(0,result.emission().chosenSeed().id());
                    assertEquals(19,result.emission().inferenceVertices().size());assertTrue(result.peakRetainedBytes()<ledger.limitBytes());
                }
            }
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void protectedGapIntervalsRetainAbsolutePrefixMemoryAndNativeOwnership() throws Exception {
        try(var f=stageFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))) {
            var anchors=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
            for(int row:List.of(0,2,4,6))anchors.add(new LegacyEmissionKernel.AnchorProposal(row,f.profiles().get(row).peaks().get(0)));
            try(var owned=solve(f.input(),f.profiles(),List.of(0.0,.25),anchors,(p,o,i)->o,"EXACT_VERSIONS_V1",null)) {
                var result=owned.emission();
                assertEquals(3,result.intervalStageReferences().size());
                assertEquals(5,owned.stages().size());
                assertTrue(result.generatedStateReferences().stream().anyMatch(s->s.kind()==EmissionResult.StateKind.BASE_CARRY));
                assertTrue(result.generatedStateReferences().stream().anyMatch(s->s.kind()==EmissionResult.StateKind.INTERVAL_TARGET));
                var fixed=result.inferenceVertices().stream().filter(v->v.owner() instanceof EmissionMapping.BoundaryVertex).toList();
                assertEquals(1,fixed.size());assertEquals(new MetricPoint(52,66.5),fixed.get(0).position());
                assertTrue(result.smoothedVertices().contains(fixed.get(0)));
                assertEquals(result.firstStageVertices().get(0),result.inferenceVertices().get(0));
                assertEquals(result.firstStageVertices().get(result.firstStageVertices().size()-1),result.inferenceVertices().get(result.inferenceVertices().size()-1));
                for(var stage:result.intervalStageReferences()) {
                    assertEquals(stage.leftAnchor().originalRow(),((EmissionMapping.ObservationVertex)stage.vertices().get(0).owner()).observationIndex());
                    assertEquals(stage.rightAnchor().originalRow(),((EmissionMapping.ObservationVertex)stage.vertices().get(stage.vertices().size()-1).owner()).observationIndex());
                }
            }
        }
    }

    static LegacyCompetitiveProofTest.Fixture stageFixture(EmissionWorkBudget budget) throws Exception {
        // Reuse the genuine retained seven-row source generator; never fabricate a Context or native state.
        var source=LegacyEmissionStagesTest.fixture();
        EvidenceSnapshot evidence=source.evidence();CorridorTraceInput trace=source.input();ProfileChainage chainage=source.chainage();
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,"stage-ridge",trace,chainage,25,budget,owner)) {
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,"stage-ridge",trace.profileLocations(),25,1,1,budget,permit);
            var event=new LegacyEmissionBinding.ProtectedOccurrence("grouped-fixed",3,4,28,new MetricPoint(52,66.5),true);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,List.of(0,1,2,3,4,5,6),1,List.of(event));
            var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
            return new LegacyCompetitiveProofTest.Fixture(input,binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList(),ledger,owner);
        } catch(Exception failure){owner.close();throw failure;}
    }

    @Test void fullPrefixOracleChecksEverySeedAndAbsoluteProtectedIntervalBeforeStitch() throws Exception {
        try(var f=stageFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))) {
            var seeds=List.of(0.0,.25);var anchors=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
            for(int row:List.of(0,2,4,6))anchors.add(new LegacyEmissionKernel.AnchorProposal(row,f.profiles().get(row).peaks().get(0)));
            var oracle=LegacyGroupedExhaustiveOracle.enumerate(f,seeds,anchors);assertTrue(oracle.terminalPaths()>5);
            try(var result=solve(f.input(),f.profiles(),seeds,anchors,(p,o,i)->o,"EXACT_VERSIONS_V1",null)) {
                assertOracle(oracle,result);
                var last=result.stages().get(result.stages().size()-1);
                assertTrue(last.prefix().orElseThrow().k()>0,"The actual protected bend supplies inherited physical K");
                assertTrue(last.terminal().orElseThrow().k()>last.prefix().orElseThrow().k());
                for(var reference:result.emission().intervalStageReferences()) {
                    var absolute=result.stages().stream().filter(s->s.ordinal()==reference.ordinal()).findFirst().orElseThrow();
                    assertEquals(absolute.terminal().orElseThrow(),forwardBlocks(absolute.prefix().orElseThrow(),reference));
                }
            }
        }
    }
    static void assertOracle(LegacyGroupedExhaustiveOracle.Result expected,LegacyEmissionKernel.GroupedResult actual) {
        var emission=actual.emission();assertEquals(expected.seed(),emission.chosenSeed().id());
        assertEquals(expected.first(),emission.firstStage());assertEquals(expected.stitched(),emission.stitched());
        assertEquals(expected.firstPoints(),oraclePoints(emission.firstStageVertices()));assertEquals(expected.points(),oraclePoints(emission.inferenceVertices()));
        assertEquals(expected.anchors(),emission.eligibleAnchors().stream().map(EmissionResult.AnchorReference::originalRow).toList());
        assertEquals(expected.stages().size(),actual.stages().size());
        for(int i=0;i<expected.stages().size();i++) {
            var e=expected.stages().get(i);var a=actual.stages().get(i);
            assertEquals(e.seed(),a.seedId());assertEquals(e.ordinal(),a.ordinal());assertEquals(Optional.ofNullable(e.prefix()),a.prefix());
            assertEquals(Optional.ofNullable(e.terminal()),a.terminal());
            if(a.ordinaryWitness().isPresent())assertTrue(a.terminal().orElseThrow().total()<=a.ordinaryWitness().orElseThrow().total());
        }
    }
    static List<LegacyGroupedExhaustiveOracle.Point> oraclePoints(List<EmissionResult.Vertex> vertices) {
        return vertices.stream().map(v->new LegacyGroupedExhaustiveOracle.Point(v.position(),v.owner(),v.nativeStateId().orElse(null))).toList();
    }
    static EmissionResult.Costs forwardBlocks(EmissionResult.Costs prefix,EmissionResult.IntervalStageReference stage) {
        double q=prefix.q(),c=prefix.c(),t=prefix.t(),k=prefix.k(),blockQ=0,blockK=0;
        for(int i=0;i<stage.arcs().size();i++) {
            var arc=stage.arcs().get(i);blockQ+=arc.q();blockK+=arc.k();
            if(stage.vertices().get(i+1).owner() instanceof EmissionMapping.ObservationVertex) {
                q=q+blockQ;c=c+arc.c();t=t+arc.t();k=k+blockK;blockQ=blockK=0;
            }
        }
        return new EmissionResult.Costs(q,c,t,k);
    }

    @Test void zeroProtectedIntervalInheritsTheActualPositiveReferenceAndCursor() throws Exception {
        try(var f=zeroFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))) {
            var anchors=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
            for(int row=0;row<4;row++)anchors.add(new LegacyEmissionKernel.AnchorProposal(row,f.profiles().get(row).peaks().get(0)));
            var oracle=LegacyGroupedExhaustiveOracle.enumerate(f,List.of(0.0),anchors);
            try(var owned=solve(f.input(),f.profiles(),List.of(0.0),anchors,(p,o,i)->o,"EXACT_VERSIONS_V1",null)) {
                assertOracle(oracle,owned);var stage=owned.stages().stream().filter(s->s.ordinal()==2).findFirst().orElseThrow();
                assertTrue(stage.ordinaryWitness().isPresent(),"Inherited positive reference certifies this zero-motion interval");
                var arcs=owned.emission().inferenceArcs();assertEquals(0,arcs.get(1).lengthMeters(),0);assertEquals(0,arcs.get(2).lengthMeters(),0);
                assertSame(arcs.get(1).ordinaryZeroCursor().orElseThrow().ordinaryOrigin().orElseThrow(),
                    arcs.get(2).ordinaryZeroCursor().orElseThrow().ordinaryOrigin().orElseThrow());
                assertEquals(0,arcs.get(3).k(),1e-12);
            }
        }
    }
    static LegacyCompetitiveProofTest.Fixture zeroFixture(EmissionWorkBudget budget) {
        var source=LegacyEmissionStagesTest.fixture();var evidence=source.evidence();var frame=evidence.coordinateFrame();var transform=evidence.transform();
        var locations=new ArrayList<DetachedProfileSamplingLocation>();double[] xs={24,32,32,40};
        for(int row=0;row<4;row++)locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(new MetricPoint(xs[row],64.22)),frame,transform,8.0*row));
        var trace=new CorridorTraceInput(locations,1);var chainage=new ProfileChainage(List.of(0.0,8.0,16.0,24.0),24);
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,"stage-ridge",trace,chainage,25,budget,owner)) {
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,"stage-ridge",trace.profileLocations(),25,1,1,budget,permit);
            var p=retained.profiles().levelZeroProfiles().get(1);double offset=p.peaks().get(0).offsetPx();
            var exact=transform.pixelCenterToMetric(p.anchorScreen().x+p.normalScreen().x*offset,p.anchorScreen().y+p.normalScreen().y*offset);
            var event=new LegacyEmissionBinding.ProtectedOccurrence("grouped-zero",1,2,12,exact,true);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,List.of(0,1,2,3),1,List.of(event));
            var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
            return new LegacyCompetitiveProofTest.Fixture(input,binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList(),ledger,owner);
        } catch(RuntimeException failure){owner.close();throw failure;}
    }

    @Test void equalCarrySeedsAreSelectedBeforeTheSingleFinalSmoothingPass() throws Exception {
        try(var f=darkFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))) {
            var calls=new ArrayList<List<Double>>();
            LegacyEmissionKernel.Smoother smoother=(p,o,i)->{
                calls.add(List.copyOf(o));var moved=new ArrayList<>(o);if(o.get(0)<0)moved.set(1,4.0);return moved;
            };
            double negativePost,positivePost;
            try(var negative=solve(f.input(),f.profiles(),List.of(-.75),List.of(),smoother,"EXACT_VERSIONS_V1",null)) {negativePost=negative.emission().postSmoothing().total();}
            try(var positive=solve(f.input(),f.profiles(),List.of(.75),List.of(),smoother,"EXACT_VERSIONS_V1",null)) {positivePost=positive.emission().postSmoothing().total();}
            assertTrue(negativePost>positivePost);calls.clear();
            var oracle=LegacyGroupedExhaustiveOracle.enumerate(f,List.of(-.75,.75),List.of());
            try(var result=solve(f.input(),f.profiles(),List.of(-.75,.75),List.of(),smoother,"EXACT_VERSIONS_V1",null)) {
                assertOracle(oracle,result);assertEquals(0,result.emission().chosenSeed().id());assertEquals(negativePost,result.emission().postSmoothing().total(),0);
                assertEquals(List.of(List.of(-.75,-.75,-.75)),calls);
                assertEquals(EmissionResult.StateKind.BASE_CARRY,result.emission().inferenceVertices().get(1).kind());
                assertNotEquals(result.emission().inferenceVertices().get(1),result.emission().smoothedVertices().get(1));
            }
        }
    }
    static LegacyCompetitiveProofTest.Fixture darkFixture(EmissionWorkBudget budget) throws Exception {
        var method=LegacyEmissionOracleTest.class.getDeclaredMethod("darkFixture");method.setAccessible(true);Object source=method.invoke(null);
        var evidence=(EvidenceSnapshot)LegacyGroupedExhaustiveOracle.access(source,"evidence");
        var trace=(CorridorTraceInput)LegacyGroupedExhaustiveOracle.access(source,"input");
        var chainage=(ProfileChainage)LegacyGroupedExhaustiveOracle.access(source,"chainage");
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,"bright",trace,chainage,25,budget,owner)) {
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,"bright",trace.profileLocations(),25,1,1,budget,permit);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,List.of(0,1,2),24,List.of());
            var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
            return new LegacyCompetitiveProofTest.Fixture(input,binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList(),ledger,owner);
        } catch(RuntimeException failure){owner.close();throw failure;}
    }
    @Test void multipleSeedsSelectTheExhaustiveGroupedBaseBeforeOneSmoother() throws Exception {
        try(var f=LegacyCompetitiveProofTest.fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))) {
            var seeds=List.of(.25,0.0,.25); // stable original seed identity, including duplicate offsets
            var all=new ArrayList<LegacyCompetitiveProofTest.OraclePath>();
            for(double seed:seeds)all.addAll(LegacyCompetitiveProofTest.enumerate(f,seed));
            var winner=all.stream().min(LegacyCompetitiveProofTest::compare).orElseThrow();
            var calls=new ArrayList<List<Double>>();
            LegacyEmissionKernel.Smoother smooth=(p,o,i)->{calls.add(List.copyOf(o));return o;};
            try(var owned=solve(f.input(),f.profiles(),seeds,List.of(),smooth,"EXACT_VERSIONS_V1",null)) {
                var result=owned.emission();
                assertEquals(winner.costs(),result.firstStage());assertEquals(winner.costs(),result.stitched());
                assertEquals(winner.rows(),result.firstStageVertices().stream().map(v->((EmissionMapping.ObservationVertex)v.owner()).observationIndex()).toList());
                assertEquals(winner.ids(),result.firstStageVertices().stream().map(v->v.nativeStateId().orElseThrow()).toList());
                assertEquals(1,result.chosenSeed().id());
                assertEquals(winner.consumed()==0?1:0,calls.size(),"The native smoother only receives contiguous runs of at least three emitted rows");
                assertTrue(result.sourceReference().modelPolicyId().contains("LEGACY_GROUPED_EXACT_V1"));
                assertEquals(seeds.size(),owned.stages().size());
            }
        }
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    static LegacyEmissionKernel.GroupedResult solve(SampledEmissionInput input,List<CrossSectionProfile> profiles,List<Double> seeds,
            List<LegacyEmissionKernel.AnchorProposal> anchors,LegacyEmissionKernel.Smoother smoother,
            String choice,LegacyEmissionProgress progress) throws Exception {
        Class type=Class.forName(LegacyEmissionKernel.class.getName()+"$GroupedExecution");
        var method=LegacyEmissionKernel.class.getMethod("solveGrouped",SampledEmissionInput.class,List.class,List.class,List.class,
            LegacyEmissionKernel.Smoother.class,CancellationProbe.class,NativeConnectorEvaluator.QueryPolicy.class,
            NativeConnectorEvaluator.ExecutionPolicy.class,type,LegacyEmissionProgress.class);
        try{return (LegacyEmissionKernel.GroupedResult)method.invoke(new LegacyEmissionKernel(),input,profiles,seeds,anchors,smoother,CancellationProbe.NONE,
            LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,Enum.valueOf(type,choice),progress);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException primary)throw primary;throw failure;}
    }
}
