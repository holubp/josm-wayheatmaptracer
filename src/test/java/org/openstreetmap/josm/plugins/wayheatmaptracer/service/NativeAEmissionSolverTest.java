package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class NativeAEmissionSolverTest {
    @Test void endpointGuidePreparationMustFitTheSameAttemptWorkBeforeNativeBuildersRun() {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var fixture=new Fixture(4,budget)) {
            var profiles=new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(),1);
            long samples=profiles.stream().mapToLong(p->p.source().intensitySamples().size()).sum();
            long remaining=profiles.size()*4096L+samples*128L+8000;
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.snapshot().workLimit()-budget.snapshot().workUsed()-remaining);
            long baseline=fixture.ledger.currentBytes();
            var constraints=new JunctionContext(java.util.stream.IntStream.range(0,4).mapToObj(row->
                new EndpointConstraint(row,row+1,true,false,0,0,0)).toList());
            var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->NativeAEmissionSolver.solve(
                new CorridorCenterlineOptimizer(),fixture.input,track(profiles),profiles,1,constraints,Map.of(),List.of(),true));
            assertEquals(EmissionWorkBudget.LimitKind.TOTAL_WORK,failure.kind());
            assertEquals(baseline,fixture.ledger.currentBytes());
        }
    }
    @Test void numericContinuityUsesNativeCleanupOffCoefficientsAndOriginalRasterSpacing() {
        try(var fixture=new Fixture(List.of(new RasterPoint(10,19),new RasterPoint(12,20),new RasterPoint(14,19)))) {
            var profiles=new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(),1);
            var selected=track(profiles);var optimizer=new CorridorCenterlineOptimizer();
            var mandatory=new JunctionContext(List.of(new EndpointConstraint(1,1,false,false,5,0,0)));
            var tube=new CorridorTubeBuilder().build(selected,profiles,1,Map.of());
            var approaches=new EndpointApproachBuilder().build(selected,profiles,tube,mandatory,Map.of());
            var domain=optimizer.nativeStateDomain(selected,profiles,1,mandatory,Map.of(),tube,approaches);
            try(var result=NativeAEmissionSolver.solve(optimizer,fixture.input,selected,profiles,1,mandatory,Map.of(),List.of(),true)) {
                assertTrue(result.consumedRows().isEmpty());assertTrue(result.c()>0);
                double expected=0,objective=0;
                for(int row=1;row<3;row++) {
                    double delta=result.emitted().get(row).offsetPx()-result.emitted().get(row-1).offsetPx();
                    double spacing=profiles.get(row-1).source().anchorScreen().distance(profiles.get(row).source().anchorScreen());
                    expected+=domain.rows().get(row).continuityWeight()*Math.pow(delta/spacing,2);
                }
                for(var edge:result.costs())objective=objective+edge.q()+edge.c()+edge.t()+edge.k();
                assertEquals(expected,result.c(),0);assertEquals(objective,result.cost(),0);
            }
        }
    }
    @Test void ordinaryUnavailableEvidenceUsesLossOneAndNeverFabricatesScalarAvailability() {
        var anchors=List.of(new RasterPoint(10,20),new RasterPoint(12,20),new RasterPoint(14,20));
        try(var fixture=new Fixture(anchors,(x,y)->Math.max(0,1-Math.abs(y-20)/3.0),5,
                new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE),0,true)) {
            var profiles=new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(),1);
            var fixed=new JunctionContext(java.util.stream.IntStream.range(0,3).mapToObj(row->
                new EndpointConstraint(row,row+1,true,false,0,0,0)).toList());
            try(var result=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),fixture.input,track(profiles),
                    profiles,1,fixed,Map.of(),List.of(),true)) {
                assertEquals(4,result.q(),0);assertEquals(4,result.cost(),0);
                assertTrue(result.costs().stream().noneMatch(NativeAEmissionSolver.SelectedCost::scalarAvailable));
            }
        }
    }

    @Test void protectedSourceCoordinatesStayInTheirBoundContextMeasureAtNonzeroSourceOrigin() {
        var anchors=List.of(new RasterPoint(10,20),new RasterPoint(12,20),new RasterPoint(14,20));
        try(var fixture=new Fixture(anchors,(x,y)->Math.max(0,1-Math.abs(y-20)/3.0),5,
                new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE),1000)) {
            var profiles=new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(),1);
            double eventS=fixture.input.context().sourceChainageMeters(0);
            var event=new NativeConnectorEvaluator.Barrier("origin-owned-event",0,1,eventS,new MetricPoint(10,20));
            var fixed=new JunctionContext(List.of(new EndpointConstraint(0,1,true,true,0,0,0)));
            try(var result=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),fixture.input,track(profiles),
                    profiles,1,fixed,Map.of(),List.of(event),true)) {
                assertEquals(0,result.cost(),0);assertEquals(4,result.costs().stream().mapToDouble(
                    NativeAEmissionSolver.SelectedCost::sourceSpanMeters).sum(),0);
                assertEquals("origin-owned-event",result.emitted().get(1).occurrenceId());
            }
        }
    }
    @Test void actualOptionalAdmissionConsumesAnUnassociatedRowAndMatchesFullCanonicalSolve() {
        var anchors=List.of(new RasterPoint(10,20),new RasterPoint(14,13),new RasterPoint(18,20));
        try(var fullFixture=new Fixture(anchors,(x,y)->y==20?1:0,4);
            var fixture=new Fixture(anchors,(x,y)->y==20?1:0,4)) {
            var fullProfiles=new CorridorExtractor().extract(fullFixture.input.profiles().levels().get(0).profiles(),1);
            var profiles=new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(),1);
            var fullTracks=new CorridorTracker().track(fullProfiles,1);var tracks=new CorridorTracker().track(profiles,1);
            assertTrue(profiles.get(1).bands().isEmpty(),"Middle section must be a genuine unsupported association");
            assertFalse(tracks.isEmpty(),()->"Native bands="+profiles.stream().map(p->p.bands().stream()
                .map(b->b.centerOffsetPx()+":"+b.boundaryCompleteness()).toList()).toList());
            assertEquals(fullTracks.size(),tracks.size());
            try(var full=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),fullFixture.input,fullTracks.get(0),
                    fullProfiles,1,JunctionContext.empty(),Map.of(),List.of(),false);
                var result=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),fixture.input,tracks.get(0),
                    profiles,1,JunctionContext.empty(),Map.of(),List.of(),true)) {
                assertEquals(List.of(1),result.consumedRows());assertEquals(2,result.emitted().size());
                assertEquals(0,result.cost(),0);assertEquals(0,full.cost(),0);
                assertEquals(full.vertexIds(),result.vertexIds());assertEquals(full.arcIds(),result.arcIds());
                assertEquals(List.of(0,2),result.emitted().stream().map(NativeAEmissionSolver.EmittedOccurrence::row).toList());
                assertTrue(result.costs().get(0).optional());assertTrue(result.costs().get(0).scalarAvailable());
                assertTrue(result.closure().unmeasuredCertifiedNoncompetitive()>0);
                assertEquals(2*Math.sqrt(65),result.costs().get(0).sourceSpanMeters(),0);
            }
        }
    }

    @Test void cancellationDuringFinalCanonicalDpPublishesNoClosureWinner() {
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),()->armed.get()&&StackWalker.getInstance().walk(frames->
            frames.anyMatch(frame->frame.getClassName().endsWith("NativeAEmissionSolver$Engine")&&frame.getMethodName().equals("solveExact"))));
        try(var fixture=new Fixture(3,budget)) {
            var profiles=new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(),1);
            long baseline=fixture.ledger.currentBytes();armed.set(true);
            var fixed=new JunctionContext(java.util.stream.IntStream.range(0,3).mapToObj(row->
                new EndpointConstraint(row,row+1,true,false,0,0,0)).toList());
            var failure=assertThrows(java.util.concurrent.CancellationException.class,()->NativeAEmissionSolver.solve(
                new CorridorCenterlineOptimizer(),fixture.input,track(profiles),profiles,1,fixed,Map.of(),List.of(),true));
            assertEquals(baseline,fixture.ledger.currentBytes());
            assertSame(failure,assertThrows(java.util.concurrent.CancellationException.class,fixture.input::requireOpenAndUnchanged));
        }
    }

    @Test void pairCapAndTotalWorkCapNeverReturnAnIncumbent() {
        for(boolean pair:List.of(true,false)) {
            var limits=new TraceBudgets(96,pair?5:8_000_000,128_000_000,32,8);
            var budget=new EmissionWorkBudget(limits,CancellationProbe.NONE);
            try(var fixture=new Fixture(3,budget)) {
                if(!pair)budget.charge(EmissionWorkBudget.Operation.PREPARATION,
                    budget.snapshot().workLimit()-budget.snapshot().workUsed()-128);
                var profiles=new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(),1);
                long baseline=fixture.ledger.currentBytes();
                var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->NativeAEmissionSolver.solve(
                    new CorridorCenterlineOptimizer(),fixture.input,track(profiles),profiles,1,JunctionContext.empty(),Map.of(),List.of(),true));
                assertEquals(pair?EmissionWorkBudget.LimitKind.PAIR_VISITS:EmissionWorkBudget.LimitKind.TOTAL_WORK,failure.kind());
                assertEquals(baseline,fixture.ledger.currentBytes());
                assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,fixture.input::requireOpenAndUnchanged));
            }
        }
    }
    @Test void actualNativeAFallbackStatesPreserveAContinuousStrengthSwappingStrand() {
        var anchors=List.of(new RasterPoint(10,20),new RasterPoint(18,20),new RasterPoint(26,20));
        try(var fixture=new Fixture(anchors,(x,y)-> {
                double upper=x<18?.95:.60,lower=x<18?.60:.95;
                return y==16?upper:y==24?lower:0;
            },7)) {
            for(var profile:fixture.input.profiles().levels().get(0).profiles()) {
                assertTrue(profile.peaks().stream().anyMatch(p->Math.abs(p.offsetPx()+4)<1));
                assertTrue(profile.peaks().stream().anyMatch(p->Math.abs(p.offsetPx()-4)<1));
            }
            var nativeBaseline=new CorridorAwareTracker().trackDetailed(fixture.input.profiles(),1,JunctionContext.empty());
            try(var result=new CorridorAwareTracker().trackOptionalEmission(fixture.input,JunctionContext.empty(),List.of(),
                    GeometryCleanupConfig.disabled(),NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                    NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1)) {
                assertFalse(result.results().isEmpty());
                assertEquals(nativeBaseline.tracks().size(),result.results().size(),"Preserve the exact native track inventory");
                for(var candidate:result.results()) {
                    int strand=candidate.emitted().get(0).raster().y()<20?16:24;
                    for(var point:candidate.emitted())assertEquals(strand,point.raster().y(),1,
                        "Actual A output must stay on one independently painted native strand");
                    var leading=candidate.nativeStates().stream().filter(state->state.row()==0).toList();
                    assertEquals(1,leading.size());assertEquals(4,leading.get(0).offsetPx(),0);
                    assertEquals(NativeConnectorEvaluator.SourceClass.GAP,leading.get(0).sourceClass());
                    assertTrue(candidate.closure().finalMinimumLowerBound()>candidate.closure().certifiedUpperBound());
                    System.out.println("actual-a-affinity nativeStates="+candidate.nativeStates()+" emitted="+candidate.emitted()
                        +" costs="+candidate.costs()+" J="+candidate.cost()+" work="+candidate.work());
                }
            }
        }
    }
    @Test void explicitTrackerBoundaryUsesTheActualNativePipelineAndOwnsEveryResult() {
        try(var fixture=new Fixture(3)) {
            long baseline=fixture.ledger.currentBytes();
            var tracker=new CorridorAwareTracker();
            try(var result=tracker.trackOptionalEmission(fixture.input,JunctionContext.empty(),List.of(),
                    GeometryCleanupConfig.disabled(),NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                    NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1)) {
                assertFalse(result.results().isEmpty());
                for(var candidate:result.results()) {
                    assertEquals(0,candidate.cost(),0);assertEquals(3,candidate.emitted().size());
                    assertEquals(fixture.input.context().sourceIdentity(),candidate.sourceIdentity());
                }
            }
            assertEquals(baseline,fixture.ledger.currentBytes());
        }
    }
    @Test void nativePhysicalAndReferenceCostsMatchAnIndependentFixedPathOracle() {
        try(var fixture=new Fixture(List.of(new RasterPoint(10,19),new RasterPoint(12,20),new RasterPoint(14,19)))) {
            var profiles=new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(),1);
            var selected=track(profiles);var optimizer=new CorridorCenterlineOptimizer();
            var fixed=new JunctionContext(java.util.stream.IntStream.range(0,3).mapToObj(row->
                new EndpointConstraint(row,row+1,true,false,0,0,0)).toList());
            var tube=new CorridorTubeBuilder().build(selected,profiles,1,Map.of());
            var approaches=new EndpointApproachBuilder().build(selected,profiles,tube,fixed,Map.of());
            var nativeRows=optimizer.nativeStateDomain(selected,profiles,1,fixed,Map.of(),tube,approaches);
            try(var result=NativeAEmissionSolver.solve(optimizer,fixture.input,selected,profiles,1,fixed,Map.of(),List.of(),true)) {
                assertEquals(3,result.emitted().size());assertTrue(result.consumedRows().isEmpty());
                double expectedQ=0;
                for(int row=1;row<3;row++) {
                    var grid=fixture.input.context().nativeGrid();
                    var scalar=BilinearSegmentIntegral.evaluate(grid,grid.toGrid(result.emitted().get(row-1).raster()),
                        grid.toGrid(result.emitted().get(row).raster()),CancellationProbe.NONE,()->{});
                    expectedQ+=fixture.input.context().sourceSpanUpperMeters(row-1,row)*(1-scalar.mean().orElseThrow());
                }
                double theta=2*Math.atan2(1,2);
                double expectedK=theta*theta/Math.sqrt(5);
                var reference=new ArrayList<RasterPoint>();
                for(int row=0;row<3;row++) {
                    var profile=profiles.get(row).source();double offset=tube.at(row).centerOffsetPx();
                    reference.add(new RasterPoint(profile.anchorScreen().x+profile.normalScreen().x*offset,
                        profile.anchorScreen().y+profile.normalScreen().y*offset));
                }
                double referenceTurn=turn(reference.get(0),reference.get(1),reference.get(2));
                double actualTurn=turn(result.emitted().get(0).raster(),result.emitted().get(1).raster(),result.emitted().get(2).raster());
                double residual=Math.atan2(Math.sin(actualTurn-referenceTurn),Math.cos(actualTurn-referenceTurn));
                double expectedT=nativeRows.rows().get(2).accelerationWeight()*Math.pow(residual/Math.sqrt(5),2);
                assertEquals(expectedQ,result.q(),1e-14);assertEquals(0,result.c(),0);
                assertEquals(expectedT,result.t(),1e-14);assertEquals(expectedK,result.k(),1e-14);
                assertEquals(result.costs().stream().mapToDouble(NativeAEmissionSolver.SelectedCost::q).sum(),result.q(),0);
            }
        }
    }

    @Test void mandatoryBoundaryAliasKeepsItsOccurrenceAndDoesNotInventDirectionOrOffset() {
        try(var fixture=new Fixture(3)) {
            var profiles=new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(),1);
            var fixed=new JunctionContext(List.of(new EndpointConstraint(0,1,true,true,0,0,0)));
            var event=new NativeConnectorEvaluator.Barrier("protected-alias",0,1,0,new MetricPoint(10,20));
            try(var result=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),fixture.input,track(profiles),
                    profiles,1,fixed,Map.of(),List.of(event),true)) {
                assertEquals(4,result.emitted().size());assertTrue(result.consumedRows().isEmpty());
                var boundary=result.emitted().get(1);
                assertEquals("protected-alias",boundary.occurrenceId());assertNull(boundary.offsetPx());
                assertEquals(result.emitted().get(0).metric(),boundary.metric());
                assertEquals(0,result.costs().get(0).sourceSpanMeters(),0);
                assertEquals(0,result.costs().get(0).k(),0);assertEquals(0,result.costs().get(1).k(),0);
                assertEquals(0,result.cost(),0);
            }
        }
    }

    private static double turn(RasterPoint a,RasterPoint b,RasterPoint c) {
        double value=Math.atan2(c.y()-b.y(),c.x()-b.x())-Math.atan2(b.y()-a.y(),b.x()-a.x());
        return Math.atan2(Math.sin(value),Math.cos(value));
    }
    @Test void nativeMemoryFailurePoisonsTheAttemptAndReleasesAllPartialStorage() {
        try(var fixture=new Fixture(3)) {
            var profiles=new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(),1);
            try(var pressure=fixture.owner.reserve(fixture.ledger.limitBytes()-fixture.ledger.currentBytes()-512)) {
                long baseline=fixture.ledger.currentBytes();
                var failure=assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->NativeAEmissionSolver.solve(
                    new CorridorCenterlineOptimizer(),fixture.input,track(profiles),profiles,1,JunctionContext.empty(),Map.of(),List.of(),true));
                assertEquals(baseline,fixture.ledger.currentBytes());
                assertSame(failure,assertThrows(AttemptMemoryLedger.ResourceLimitException.class,fixture.input::requireOpenAndUnchanged));
                assertSame(failure,assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->
                    fixture.input.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,0)));
            }
        }
    }
    @Test void nativeCompetitiveClosurePublishesOnlyTheFinalExactDpAndMatchesFullAdmission() {
        try (var fullFixture=new Fixture(3); var fixture = new Fixture(3)) {
            var fullProfiles = new CorridorExtractor().extract(fullFixture.input.profiles().levels().get(0).profiles(),1);
            var profiles = new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(), 1);
            try(var full=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),fullFixture.input,track(fullProfiles),
                fullProfiles,1,JunctionContext.empty(),Map.of(),List.of(),false);
                var actual=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),fixture.input,track(profiles),
                profiles,1,JunctionContext.empty(),Map.of(),List.of(),true)) {
            assertEquals(full.vertexIds(),actual.vertexIds());
            assertEquals(full.arcIds(),actual.arcIds());
            assertEquals(full.cost(),actual.cost(),0);
            assertEquals(full.q(),actual.q(),0); assertEquals(full.c(),actual.c(),0);
            assertEquals(full.t(),actual.t(),0); assertEquals(full.k(),actual.k(),0);
            assertEquals(0,actual.cost(),0,"Actual straight native center has zero Q/C/T/K");
            assertEquals(3,actual.vertexIds().size(),"Exact tie keeps all observations");
            assertTrue(actual.closure().finalMinimumLowerBound()>actual.closure().certifiedUpperBound());
            assertEquals(actual.vertexIds(),actual.closure().vertexIds());
            }
        }
    }

    @Test void foreignExtractedProfilesAreNeverAcceptedAsNativeSourceAttestation() {
        try (var first=new Fixture(3); var foreign=new Fixture(3)) {
            var profiles=new CorridorExtractor().extract(foreign.input.profiles().levels().get(0).profiles(),1);
            assertThrows(IllegalArgumentException.class,()->NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),
                first.input,track(profiles),profiles,1,JunctionContext.empty(),Map.of(),List.of(),true));
        }
    }

    @Test void inventoryComesFromTheActualNativeOptimizerIncludingGapStates() {
        try (var fixture = new Fixture(3)) {
            var profiles = new CorridorExtractor().extract(fixture.input.profiles().levels().get(0).profiles(), 1);
            var track = track(profiles);
            var optimizer = new CorridorCenterlineOptimizer();
            var tube = new CorridorTubeBuilder().build(track, profiles, 1, Map.of());
            var approaches = new EndpointApproachBuilder().build(track, profiles, tube, JunctionContext.empty(), Map.of());
            var expected = optimizer.nativeStateDomain(track, profiles, 1, JunctionContext.empty(), Map.of(), tube, approaches);
            assertEquals(3,expected.rows().size());
            try(var result=NativeAEmissionSolver.solve(optimizer,fixture.input,track,profiles,1,JunctionContext.empty(),Map.of(),List.of(),false)) {
                assertEquals(expected.rows().stream().flatMap(row->row.states().stream().map(state->
                    new NativeAEmissionSolver.NativeStateInventory(row.rowIndex(),state.stateId(),state.offsetPx(),
                        NativeConnectorEvaluator.SourceClass.OBSERVED))).toList(),result.nativeStates());
            }
        }
    }

    static CorridorTrack track(List<CorridorProfile> profiles) {
        Map<Integer,CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int row=0; row<profiles.size(); row++) if (!profiles.get(row).bands().isEmpty())
            points.put(row,new CorridorTrackPoint(row,profiles.get(row).bands().get(0),false));
        return new CorridorTrack("native-a",points,1,1,false,List.of(),"");
    }

    static final class Fixture implements AutoCloseable {
        final AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        final AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        final SampledEmissionInput input;
        Fixture(int rows) {this(rows,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));}
        Fixture(int rows,EmissionWorkBudget budget) {this(java.util.stream.IntStream.range(0,rows)
            .mapToObj(row->new RasterPoint(10+row*2,20)).toList(),(x,y)->Math.max(0,1-Math.abs(y-20)/3.0),5,budget,0);}
        Fixture(List<RasterPoint> anchors) {this(anchors,(x,y)->Math.max(0,1-Math.abs(y-20)/3.0),5);}
        Fixture(List<RasterPoint> anchors,java.util.function.DoubleBinaryOperator signal,double halfWidth) {
            this(anchors,signal,halfWidth,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE),0);
        }
        Fixture(List<RasterPoint> anchors,java.util.function.DoubleBinaryOperator signal,double halfWidth,
                EmissionWorkBudget budget,double sourceOrigin) {
            this(anchors,signal,halfWidth,budget,sourceOrigin,false);
        }
        Fixture(List<RasterPoint> anchors,java.util.function.DoubleBinaryOperator signal,double halfWidth,
                EmissionWorkBudget budget,double sourceOrigin,boolean unavailableCell) {
            this(anchors,signal,halfWidth,budget,sourceOrigin,unavailableCell,EvidenceResolution.nativeSource(1,1));
        }
        Fixture(List<RasterPoint> anchors,java.util.function.DoubleBinaryOperator signal,double halfWidth,
                EmissionWorkBudget budget,double sourceOrigin,boolean unavailableCell,EvidenceResolution resolution) {
            int width=40,height=40;
            double[] values=new double[width*height];
            for(int y=0;y<height;y++) for(int x=0;x<width;x++)
                values[y*width+x]=signal.applyAsDouble(x,y);
            boolean[] valid=new boolean[values.length]; Arrays.fill(valid,true);
            boolean[] cells=new boolean[(width-1)*(height-1)]; Arrays.fill(cells,true);
            if(unavailableCell)cells[20*(width-1)+11]=false;
            var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50,14),
                new GeographicPoint(49.99,13.99),new GeographicPoint(50.01,14.02));
            var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
            var field=new ScalarEvidenceField(width,height,values,valid,cells,new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                "native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
            var evidence=new EvidenceSnapshot("a-synthetic",frame,transform,resolution,
                MetricRegion.rectangle(5,5,35,35),MetricRegion.rectangle(-.5,-.5,39.5,39.5),
                Map.of("native",field),"declared-synthetic-a");
            List<DetachedProfileSamplingLocation> locations=new ArrayList<>();
            List<Double> distances=new ArrayList<>();
            double distance=0;
            for(int row=0;row<anchors.size();row++) {
                var raster=anchors.get(row);var metric=transform.pixelCenterToMetric(raster.x(),raster.y());
                if(row>0)distance+=Math.hypot(anchors.get(row-1).x()-raster.x(),anchors.get(row-1).y()-raster.y());
                locations.add(new DetachedProfileSamplingLocation(frame.toGeographic(metric),metric,raster,frame,transform,distance));
                distances.add(distance);
            }
            input=new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(evidence,"native",
                new CorridorTraceInput(locations,1),new ProfileChainage(distances,2,sourceOrigin),halfWidth,budget,owner);
        }
        @Override public void close() { input.close(); owner.close(); assertEquals(0,ledger.currentBytes()); }
    }
}
