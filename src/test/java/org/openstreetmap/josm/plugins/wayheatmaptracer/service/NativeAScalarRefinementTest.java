package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;

class NativeAScalarRefinementTest {
    @Test void theNextOriginalHypothesisCannotInheritThePreviousHypothesesIncumbentOnConstructionFailure() {
        var ref=new java.util.concurrent.atomic.AtomicReference<NativeAExecutionCensus>();var armed=new boolean[1];
        var budget=new EmissionWorkBudget(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),()->
            armed[0]&&ref.get()!=null&&ref.get().phase()==NativeAExecutionCensus.Phase.ENGINE);
        try(var fixture=new NativeAEmissionSolverTest.Fixture(3,budget);var census=NativeAExecutionCensus.open(fixture.input)) {
            census.begin(fixture.input);ref.set(census);
            try(var first=solve(fixture,census)){assertTrue(census.hasIncumbent());assertTrue(Double.isFinite(first.cost()));}
            long actualCalls=census.evaluateStarts();armed[0]=true;
            var failure=assertThrows(java.util.concurrent.CancellationException.class,()->solve(fixture,census));
            assertSame(failure,census.failure());assertFalse(census.hasIncumbent());assertTrue(Double.isNaN(census.upperBound()));
            assertEquals(0,census.queueSize());assertEquals(0,census.reachedSize());assertEquals(actualCalls,census.evaluateStarts());
        }
    }
    @Test void actualDarkerFirstArcYieldsToADifferentBrighterAdmissionBeforeItsModes() throws Exception {
        var ref=new java.util.concurrent.atomic.AtomicReference<NativeAExecutionCensus>();
        var dark=new int[]{-1};var firstEvaluation=new int[]{-1};var previousPositive=new long[1];
        var last=new NativeAExecutionCensus.Phase[]{null};
        var budget=new EmissionWorkBudget(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),()->{
            var census=ref.get();if(census==null)return false;
            if(census.phase()!=last[0]) {
                last[0]=census.phase();
                if(census.phase()==NativeAExecutionCensus.Phase.SCALAR)previousPositive[0]=census.positiveQ();
                if(census.phase()==NativeAExecutionCensus.Phase.REINSERT&&census.positiveQ()>previousPositive[0]&&dark[0]<0)dark[0]=census.arcId();
            }
            if(census.evaluateStarts()>0&&firstEvaluation[0]<0)firstEvaluation[0]=census.arcId();
            return false;
        });
        var fixedStart=new JunctionContext(List.of(new EndpointConstraint(0,1,true,false,0,0,0)));
        try(var off=new NativeAEmissionSolverTest.Fixture(3);var on=new NativeAEmissionSolverTest.Fixture(3,budget);
                var census=NativeAExecutionCensus.open(on.input)) {
            census.begin(on.input);ref.set(census);
            try(var before=NativeARefusalReuseTest.solve(off,fixedStart,false,NativeAEmissionSolver.ExecutionChoice.UNCHANGED_V1);
                    var after=solve(on,census,fixedStart)) {
                assertTrue(dark[0]>=0);assertTrue(firstEvaluation[0]>=0);assertNotEquals(dark[0],firstEvaluation[0],
                    "A real positive-loss pending Arc must yield to another source-brighter certificate before Modes");
                var engine=NativeARefusalReuseTest.field(after,"engine");
                var cache=(NativeScalarPreparations)NativeARefusalReuseTest.field(engine,"scalarPreparations");
                var graph=(CompetitiveAdmissionExecutor.Graph)NativeARefusalReuseTest.field(engine,"graph");
                assertTrue(cache.prepared(graph.arcs().get(dark[0])).nativeQ()>cache.prepared(graph.arcs().get(firstEvaluation[0])).nativeQ());
                assertEquals(before.vertexIds(),after.vertexIds());assertEquals(before.costs(),after.costs());assertEquals(before.cost(),after.cost(),0);
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"EAGER_ALL_PROBES_V1","STOP_ON_FINAL_MODE_FAILURE_V1","EXACT_SLAB_PREFLIGHT_V1","INITIAL_LOCAL_NOISE_PRECHECK_V1"})
    void originalLocalAndExactSlabPoliciesConsumePreparedScalarWithoutChangingTheirNativeWinner(String policy) {
        try(var off=NativeARefusalReuseTest.tiny("weak");var on=NativeARefusalReuseTest.tiny("weak");var census=NativeAExecutionCensus.open(on.input)) {
            census.begin(on.input);var execution=ExecutionPolicy.valueOf(policy);
            var p1=new CorridorExtractor().extract(off.input.profiles().levelZeroProfiles(),1);
            var p2=new CorridorExtractor().extract(on.input.profiles().levelZeroProfiles(),1);
            try(var before=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),off.input,NativeAEmissionSolverTest.track(p1),p1,1,
                        NativeARefusalReuseTest.constraints("weak"),Map.of(),List.of(),true,QueryPolicy.ZERO_GUARDED_LOCAL_V1,execution,
                        GeometryCleanupConfig.disabled(),null,null,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1);
                    var after=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),on.input,NativeAEmissionSolverTest.track(p2),p2,1,
                        NativeARefusalReuseTest.constraints("weak"),Map.of(),List.of(),true,QueryPolicy.ZERO_GUARDED_LOCAL_V1,execution,
                        GeometryCleanupConfig.disabled(),null,null,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_REFINEMENT_V1,census)) {
                assertEquals(before.vertexIds(),after.vertexIds());assertEquals(before.costs(),after.costs());assertEquals(before.cost(),after.cost(),0);
                assertTrue(census.scalarStarts()>0);
            }
        }
    }
    @Test void transportedZeroDirectionsKeepTheirDifferentOriginalModesAndHaveNoScalarRefinement() throws Exception {
        try(var fixture=new NativeAEmissionSolverTest.Fixture(List.of(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(10,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(12,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(14,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(12,20)));
                var census=NativeAExecutionCensus.open(fixture.input)) {
            census.begin(fixture.input);
            try(var engine=new NativeARefusalReuseTest.NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_REFINEMENT_V1,JunctionContext.empty(),census)) {
                var arc=engine.arc(1,3,false);assertTrue(arc.zeroPhysical());var keys=engine.prefixes(arc);
                var a=engine.provider.certify(keys.get(0),arc);var b=engine.provider.certify(keys.get(1),arc);
                assertTrue(a.admitted());assertTrue(b.admitted());
                var first=(Receipt)NativeARefusalReuseTest.method(a.receipt(),"optional");var second=(Receipt)NativeARefusalReuseTest.method(b.receipt(),"optional");
                assertNotEquals(first.probes().get(0).metricNormal(),second.probes().get(0).metricNormal());
                assertNull(NativeARefusalReuseTest.field(engine.actual,"scalarPreparations"));
                assertEquals(0,census.scalarStarts());assertEquals(2,census.evaluateStarts());assertEquals(2,census.evaluateReturned());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"barrier","ordinary-unavailable"})
    void protectedOccurrencesAndOrdinaryUnknownLossKeepTheirOriginalExactCosts(String kind) {
        try(var fixture=new NativeAEmissionSolverTest.Fixture(List.of(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(10,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(12,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(14,20)),
                (x,y)->Math.max(0,1-Math.abs(y-20)/3.),5,
                new EmissionWorkBudget(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE),1000,kind.equals("ordinary-unavailable"));
                var census=NativeAExecutionCensus.open(fixture.input)) {
            census.begin(fixture.input);var profiles=new CorridorExtractor().extract(fixture.input.profiles().levelZeroProfiles(),1);
            var barriers=kind.equals("barrier")?List.of(new Barrier("exact-event",0,1,fixture.input.context().sourceChainageMeters(0),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(10,20))):List.<Barrier>of();
            var fixed=new JunctionContext(java.util.stream.IntStream.range(0,3).mapToObj(row->new EndpointConstraint(row,row+1,true,false,0,0,0)).toList());
            try(var result=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),fixture.input,NativeAEmissionSolverTest.track(profiles),profiles,1,
                    fixed,Map.of(),barriers,true,QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,
                    GeometryCleanupConfig.disabled(),null,null,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_REFINEMENT_V1,census)) {
                if(kind.equals("barrier")){assertTrue(result.emitted().stream().anyMatch(point->"exact-event".equals(point.occurrenceId())));assertEquals(0,result.cost(),0);}
                else {assertEquals(4,result.q(),0);assertEquals(4,result.cost(),0);assertTrue(result.costs().stream().noneMatch(NativeAEmissionSolver.SelectedCost::scalarAvailable));}
                assertTrue(result.costs().stream().noneMatch(NativeAEmissionSolver.SelectedCost::optional));assertEquals(0,census.scalarStarts());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"admitted","rejected","unavailable"})
    void alreadySettledPositiveEvidencePrecedesFurtherScalarWorkForEveryStatus(String status) throws Exception {
        try(var fixture=NativeARefusalReuseTest.fixture(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,status.equals("unavailable"));
                var census=NativeAExecutionCensus.open(fixture.input)) {
            census.begin(fixture.input);
            try(var engine=new NativeARefusalReuseTest.NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_REFINEMENT_V1,
                        JunctionContext.empty(),census)) {
                var provider=(CompetitiveAdmissionExecutor.NativeScalarRefinementProvider)engine.provider;
                var arc=engine.arc(1,3,status.equals("rejected"));var keys=engine.prefixes(arc);
                var fact=provider.prepareScalar(keys.get(0),arc);assertNotNull(fact);
                var first=provider.certify(keys.get(0),arc);assertEquals(status.equals("admitted"),first.admitted());
                var receipt=first.admitted()?(Receipt)NativeARefusalReuseTest.method(first.receipt(),"optional"):engine.retainedReceipt(arc);
                assertSame(fact.scalar(),receipt.scalar().orElseThrow());
                var before=fixture.input.workBudget().snapshot();
                assertNull(provider.prepareScalar(keys.get(1),arc),"Settled admitted/refusal facts precede any scalar preparation");
                var second=provider.certify(keys.get(1),arc);assertEquals(first.admitted(),second.admitted());assertEquals(first.reason(),second.reason());
                var after=fixture.input.workBudget().snapshot();
                assertEquals(before.operationUnits().get(EmissionWorkBudget.Operation.CELL_QUERY),after.operationUnits().get(EmissionWorkBudget.Operation.CELL_QUERY));
                assertEquals(before.operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION),after.operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION));
                assertEquals(1,census.scalarStarts());assertEquals(1,census.evaluateStarts());assertEquals(1,census.evaluateReturned());
            }
        }
    }
    @Test void reinsertKeepsTheSamePrefixVersionSerialAndLiveLeaseAndYieldsBeforeModes() {
        var ref=new java.util.concurrent.atomic.AtomicReference<NativeAExecutionCensus>();
        var fixtureRef=new java.util.concurrent.atomic.AtomicReference<NativeAEmissionSolverTest.Fixture>();
        var seen=new HashMap<List<Long>,Object>();var replayed=new HashSet<List<Long>>();
        var last=new NativeAExecutionCensus.Phase[]{null};var scalarModes=new long[1];var yielded=new boolean[1];
        var budget=new EmissionWorkBudget(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),()->{
            var census=ref.get();var fixture=fixtureRef.get();if(census==null||fixture==null||census.phase()==last[0])return false;
            last[0]=census.phase();
            var identity=List.of((long)census.arcId(),census.prefixVersion(),census.pendingSerial());
            if(census.phase()==NativeAExecutionCensus.Phase.SCALAR)
                scalarModes[0]=fixture.input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION);
            if(census.phase()==NativeAExecutionCensus.Phase.REINSERT) {
                assertEquals(scalarModes[0],fixture.input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION),
                    "No hidden full evaluator/Modes pass may run in refinement");
                assertNull(seen.put(identity,livePending(fixture,identity)),"Each obligation is refined at most once");
            }
            if(census.phase()==NativeAExecutionCensus.Phase.CLOSURE&&!seen.isEmpty()) {
                yielded[0]=true;
                if(seen.containsKey(identity)) {
                    assertSame(seen.get(identity),livePending(fixture,identity),
                        "The identical Pending object, original Prefix and active charged lease return to the frontier");
                    replayed.add(identity);
                }
            }
            return false;
        });
        try(var fixture=new NativeAEmissionSolverTest.Fixture(List.of(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(8,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(10,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(12,20)),
                (x,y)->.1*Math.max(0,1-Math.abs(y-20)/3.),5,budget,0);
                var census=NativeAExecutionCensus.open(fixture.input)) {
            fixtureRef.set(fixture);ref.set(census);census.begin(fixture.input);
            try(var actual=solve(fixture,census)) {
                assertTrue(yielded[0]);assertFalse(replayed.isEmpty());assertEquals(seen.size(),census.reinsertions());
                for(var pending:actual.closure().pendingProofs())if(pending.scalarRefinementAttempted()) {
                    assertFalse(pending.admissionAlreadyMeasured());assertTrue(pending.pendingSerial()>=0);assertTrue(pending.prefixVersion()>=0);
                    assertTrue(pending.lowerBound()>actual.cost());
                }
            }
        }
    }
    private static Object livePending(NativeAEmissionSolverTest.Fixture fixture,List<Long> identity) {
        try {
            var charges=(Map<?,?>)NativeARefusalReuseTest.field(fixture.ledger,"charges");
            for(Object key:charges.keySet()) {
                var field=key.getClass().getSuperclass().getDeclaredField("identity");field.setAccessible(true);var pending=field.get(key);
                if(!pending.getClass().getName().endsWith("$Pending"))continue;
                var arc=(CompetitiveAdmissionExecutor.Arc)NativeARefusalReuseTest.field(pending,"arc");
                var prefix=NativeARefusalReuseTest.field(pending,"prefix");
                if(arc.id()==identity.get(0)&&(long)NativeARefusalReuseTest.field(prefix,"serial")==identity.get(1)
                        &&(long)NativeARefusalReuseTest.field(pending,"serial")==identity.get(2)) {
                    assertEquals(true,NativeARefusalReuseTest.field(NativeARefusalReuseTest.field(pending,"lease"),"active"));return pending;
                }
            }
            fail("The original Pending charge must remain present");return null;
        }catch(Exception failure){throw new AssertionError(failure);}
    }
    @ParameterizedTest @ValueSource(strings={"refused","tie","weak"})
    void tinyExhaustiveNativeReferenceAndBothOldClosuresAgreeExactly(String variant) {
        try(var eager=NativeARefusalReuseTest.tiny(variant);var old=NativeARefusalReuseTest.tiny(variant);var source=NativeARefusalReuseTest.tiny(variant);
                var census=NativeAExecutionCensus.open(source.input)) {
            census.begin(source.input);
            try(var expected=NativeARefusalReuseTest.solve(eager,NativeARefusalReuseTest.constraints(variant),false,NativeAEmissionSolver.ExecutionChoice.UNCHANGED_V1);
                    var oldClosure=NativeARefusalReuseTest.solve(old,NativeARefusalReuseTest.constraints(variant),true,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1);
                    var actual=solve(source,census,NativeARefusalReuseTest.constraints(variant))) {
                assertEquals(expected.vertexIds(),actual.vertexIds());assertEquals(expected.arcIds(),actual.arcIds());
                assertEquals(expected.costs(),actual.costs());assertEquals(expected.cost(),actual.cost(),0);
                assertEquals(expected.consumedRows(),actual.consumedRows());assertEquals(oldClosure.vertexIds(),actual.vertexIds());
                assertEquals(census.evaluateStarts(),census.evaluateReturned());
                assertEquals(census.evaluateReturned(),census.admitted()+census.rejected()+census.unavailable());
                var diagnostics=actual.refusalReuseDiagnostics().orElseThrow();
                assertEquals(diagnostics.evaluatorMissCalls()+diagnostics.excludedOptionalEvaluatorCalls(),census.evaluateStarts());
                assertEquals(diagnostics.refusalReuseHits(),census.refusalHits());assertEquals(diagnostics.admittedEvidenceReuseHits(),census.admittedHits());
            }
        }
    }
    @Test void publicNonidentityCropEntryPreservesOriginalNativeOutputAndSealsItsIndependentCarrier() {
        try(var a=new NativeAEmissionBindingTest.SmallFrame(true);var b=new NativeAEmissionBindingTest.SmallFrame(true);
                var before=NativeAEmissionBinding.fromOriginalGeometry(a.input);var after=NativeAEmissionBinding.fromOriginalGeometry(b.input);
                var census=NativeAExecutionCensus.open(b.input)) {
            var tracker=new CorridorAwareTracker();
            try(var expected=tracker.trackOptionalEmission(a.input,before,JunctionContext.empty(),List.of(),GeometryCleanupConfig.disabled(),
                        QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1);
                    var actual=tracker.trackOptionalEmissionWithScalarRefinement(b.input,after,JunctionContext.empty(),List.of(),GeometryCleanupConfig.disabled(),
                        QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,census)) {
                assertEquals(expected.trackIds(),actual.trackIds());assertTrue(census.finished());assertNull(census.failure());
                for(int i=0;i<actual.results().size();i++) {
                    var one=expected.results().get(i);var two=actual.results().get(i);
                    assertEquals(one.vertexIds(),two.vertexIds());assertEquals(one.costs(),two.costs());assertEquals(one.emitted(),two.emitted());
                    assertEquals(NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_REFINEMENT_V1,two.executionChoice());
                    assertTrue(two.executionVersion().contains("scalar-refinement"));
                }
            }
            b.input.close();b.root.close();census.close();assertEquals(0,census.currentBytes());
        }
    }
    @ParameterizedTest @ValueSource(strings={"SCALAR","REINSERT","CERTIFY","FINAL_EXACT"})
    void cancellationAtEveryNewBoundaryIsStickyAndExportNeedsNoLiveEngine(String name) {
        var phase=NativeAExecutionCensus.Phase.valueOf(name);var censusRef=new java.util.concurrent.atomic.AtomicReference<NativeAExecutionCensus>();
        var budget=new EmissionWorkBudget(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),()->{
            var census=censusRef.get();return census!=null&&census.phase()==phase;
        });
        try(var f=new NativeAEmissionSolverTest.Fixture(List.of(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(8,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(10,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(12,20)),
                (x,y)->.1*Math.max(0,1-Math.abs(y-20)/3.),5,budget,0);
                var census=NativeAExecutionCensus.open(f.input)) {
            long before=f.ledger.currentBytes();census.begin(f.input);censusRef.set(census);
            var failure=assertThrows(java.util.concurrent.CancellationException.class,()->solve(f,census));
            assertSame(failure,census.failure());assertEquals(phase,census.phase());assertTrue(census.finished());
            assertSame(failure,assertThrows(RuntimeException.class,f.input::requireOpenAndUnchanged));
            assertEquals(before,f.ledger.currentBytes(),"Failed Engine and all queued leases are closed");
            assertEquals(budget.snapshot().workUsed(),census.workUsed());assertEquals(budget.snapshot().pairVisits(),census.pairVisits());
            census.fail(new IllegalStateException("later export failure"));assertSame(failure,census.failure());
            if(phase==NativeAExecutionCensus.Phase.REINSERT){assertTrue(census.scalarCompleted()>0);assertEquals(0,census.reinsertions());}
            f.input.close();f.owner.close();census.close();assertEquals(0,census.currentBytes());
        }
    }
    @ParameterizedTest @ValueSource(strings={"work","pair","memory","publication-work","publication-cancel"})
    void fixedCarrierSurvivesResourceAndCompletedIntegralPublicationFailures(String kind) {
        var ref=new java.util.concurrent.atomic.AtomicReference<NativeAExecutionCensus>();
        var fixtureRef=new java.util.concurrent.atomic.AtomicReference<NativeAEmissionSolverTest.Fixture>();
        var armed=new boolean[]{true};
        var pressure=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Reservation[1];
        var budget=new EmissionWorkBudget(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),()->{
            var census=ref.get();var fixture=fixtureRef.get();if(!armed[0]||census==null||fixture==null
                    ||census.phase()!=NativeAExecutionCensus.Phase.SCALAR||kind.startsWith("publication")&&census.scalarCompleted()==0)return false;
            armed[0]=false;
            if(kind.equals("publication-cancel"))return true;
            var work=fixture.input.workBudget();var before=work.snapshot();
            if(kind.equals("pair"))work.charge(EmissionWorkBudget.Operation.TRANSITION,before.pairLimit()-before.pairVisits());
            else if(kind.equals("memory"))pressure[0]=fixture.owner.reserve(fixture.ledger.limitBytes()-fixture.ledger.currentBytes());
            else work.charge(EmissionWorkBudget.Operation.PREPARATION,before.workLimit()-before.workUsed());
            return false;
        });
        try(var fixture=new NativeAEmissionSolverTest.Fixture(List.of(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(8,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(10,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(12,20)),
                (x,y)->.1*Math.max(0,1-Math.abs(y-20)/3.),5,budget,0);
                var census=NativeAExecutionCensus.open(fixture.input)) {
            fixtureRef.set(fixture);ref.set(census);census.begin(fixture.input);long before=fixture.ledger.currentBytes();
            try {
                var failure=assertThrows(RuntimeException.class,()->solve(fixture,census));assertSame(failure,census.failure());
                if(kind.equals("memory"))assertInstanceOf(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.ResourceLimitException.class,failure);
                else if(kind.equals("publication-cancel"))assertInstanceOf(java.util.concurrent.CancellationException.class,failure);
                else assertInstanceOf(EmissionWorkBudget.LimitExceededException.class,failure);
                assertSame(failure,assertThrows(RuntimeException.class,fixture.input::requireOpenAndUnchanged));
                assertEquals(0,census.evaluateReturned());
                if(kind.startsWith("publication")){assertEquals(1,census.scalarCompleted());assertEquals(1,census.scalarStarts());assertEquals(0,census.nativeQAvailable());}
                assertEquals(budget.snapshot().workUsed(),census.workUsed());assertEquals(budget.snapshot().pairVisits(),census.pairVisits());
            }finally{if(pressure[0]!=null)pressure[0].close();}
            assertEquals(before,fixture.ledger.currentBytes());
        }
    }
    @Test void actualScalarStrengthReinsertsBeforeFullAdmissionAndPreservesExactNativeWinner() {
        try(var off=NativeARefusalReuseTest.tiny("weak");var on=NativeARefusalReuseTest.tiny("weak");
                var census=NativeAExecutionCensus.open(on.input)) {
            census.begin(on.input);
            try(var expected=NativeARefusalReuseTest.solve(off,NativeARefusalReuseTest.constraints("weak"),true,
                        NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1);
                    var actual=solve(on,census)) {
                assertTrue(census.reinsertions()>0,"An actual positive-loss native arc must return to the heap before Modes");
                assertTrue(census.scalarStarts()>0);assertEquals(census.scalarStarts(),census.scalarCompleted());
                assertEquals(expected.vertexIds(),actual.vertexIds());assertEquals(expected.arcIds(),actual.arcIds());
                assertEquals(expected.cost(),actual.cost(),0);assertEquals(expected.costs(),actual.costs());
                assertTrue(actual.closure().finalMinimumLowerBound()>actual.closure().certifiedUpperBound());
            }
        }
    }
    static NativeAEmissionSolver.Result solve(NativeAEmissionSolverTest.Fixture f,NativeAExecutionCensus census) {
        return solve(f,census,NativeARefusalReuseTest.constraints("weak"));
    }
    static NativeAEmissionSolver.Result solve(NativeAEmissionSolverTest.Fixture f,NativeAExecutionCensus census,JunctionContext junctions) {
        var profiles=new CorridorExtractor().extract(f.input.profiles().levelZeroProfiles(),1);
        return NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),f.input,NativeAEmissionSolverTest.track(profiles),profiles,1,
            junctions,Map.of(),List.of(),true,QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
            ExecutionPolicy.EAGER_ALL_PROBES_V1,GeometryCleanupConfig.disabled(),null,null,
            NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_REFINEMENT_V1,census);
    }
}
