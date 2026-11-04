package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.CompetitiveAdmissionExecutor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.Status;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class NativeARefusalReuseTest {
    @Test void optInPreadmitsWorkForItsWholeArcIndexedSlotCapacity() throws Exception {
        try(var off=fixture(CancellationProbe.NONE,true);var on=fixture(CancellationProbe.NONE,true);
                var baseline=new NativeEngine(off,NativeAEmissionSolver.ExecutionChoice.UNCHANGED_V1);
                var enabled=new NativeEngine(on,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            assertEquals(baseline.arcs,enabled.arcs);
            long extra=on.input.workBudget().snapshot().workUsed()-off.input.workBudget().snapshot().workUsed();
            assertTrue(extra>=enabled.arcs.size(),"Prepay zero-initialization of every potential-arc slot before allocating it");
        }
    }
    @ParameterizedTest @ValueSource(strings={"refused","tie","weak"})
    void everyTinyNativePathAndCompetitiveClosurePreserveExactCostSequenceReasonsAndTies(String variant) throws Exception {
        var junctions=constraints(variant);Path expected;
        try(var fixture=tiny(variant);var engine=new NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.UNCHANGED_V1,junctions)) {
            var paths=new ArrayList<Path>();var graph=(Graph)field(engine.actual,"graph");
            for(var vertex:graph.vertices())if(vertex.initial())enumerate(engine,graph,
                new CanonicalKey(vertex.id(),-1,vertex.id(),0),List.of(vertex.id()),0,0,paths);
            assertTrue(paths.size()>1);expected=paths.stream().min(NativeARefusalReuseTest::compare).orElseThrow();
        }
        List<?> referenceReasons=null;
        for(boolean competitive:new boolean[]{false,true})for(var choice:NativeAEmissionSolver.ExecutionChoice.values()) {
            try(var fixture=tiny(variant);var result=solve(fixture,junctions,competitive,choice)) {
                assertEquals(expected.ids(),result.vertexIds());assertEquals(expected.cost(),result.cost(),0);
                assertEquals(expected.consumed(),result.consumedRows().size());
                double folded=0;for(var cost:result.costs())folded=folded+cost.q()+cost.c()+cost.t()+cost.k();
                assertEquals(folded,result.cost(),0);
                if(choice==NativeAEmissionSolver.ExecutionChoice.UNCHANGED_V1)assertTrue(result.refusalReuseDiagnostics().isEmpty());
                else {
                    var stats=result.refusalReuseDiagnostics().orElseThrow();
                    assertEquals(stats.positiveArcLookups(),stats.evaluatorMissCalls()+stats.admittedEvidenceReuseHits()+stats.refusalReuseHits());
                    assertEquals(stats.evaluatorMissCalls(),stats.returnedAdmitted()+stats.returnedRejected()+stats.returnedUnavailable());
                    assertEquals(stats.positiveCanonicalRefusals(),stats.positiveCanonicalRejected()+stats.positiveCanonicalUnavailable());
                    assertEquals(stats.positiveCanonicalRefusals(),stats.returnedRejected()+stats.returnedUnavailable()+stats.refusalReuseHits());
                    assertEquals(stats.retainedRefusalEntries(),stats.returnedRejected()+stats.returnedUnavailable());
                    if(!competitive&&variant.equals("refused"))assertTrue(stats.refusalReuseHits()>0);
                }
                if(competitive) {
                    var closure=result.closure();assertTrue(closure.finalMinimumLowerBound()>closure.certifiedUpperBound());
                    var reasons=closure.measuredTransitions().stream().map(row->List.of(row.prefix(),row.arcId(),row.admitted(),row.reason(),
                        row.q(),row.c(),row.t(),row.k())).toList();
                    if(referenceReasons==null)referenceReasons=reasons;else assertEquals(referenceReasons,reasons);
                }
            }
        }
    }

    @Test void explicitOffPublicEntryPreservesTheDefaultIdentityWorkAndRetainedAllocationPath() {
        try(var a=new NativeAEmissionSolverTest.Fixture(3);var b=new NativeAEmissionSolverTest.Fixture(3)) {
            var tracker=new CorridorAwareTracker();
            try(var defaults=tracker.trackOptionalEmission(a.input,JunctionContext.empty(),List.of(),GeometryCleanupConfig.disabled(),
                        QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1);
                    var explicit=tracker.trackOptionalEmission(b.input,JunctionContext.empty(),List.of(),GeometryCleanupConfig.disabled(),
                        QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,NativeAEmissionSolver.ExecutionChoice.UNCHANGED_V1)) {
                assertEquals(defaults.trackIds(),explicit.trackIds());
                for(int i=0;i<defaults.results().size();i++) {
                    var one=defaults.results().get(i);var two=explicit.results().get(i);
                    assertEquals(one.vertexIds(),two.vertexIds());assertEquals(one.cost(),two.cost(),0);
                    assertEquals(NativeAEmissionSolver.EXECUTION_VERSION,one.executionVersion());assertEquals(one.executionVersion(),two.executionVersion());
                    assertEquals(one.work(),two.work());assertTrue(one.refusalReuseDiagnostics().isEmpty());
                    assertTrue(two.refusalReuseDiagnostics().isEmpty());
                }
                assertEquals(a.ledger.currentBytes(),b.ledger.currentBytes());assertEquals(a.ledger.peakBytes(),b.ledger.peakBytes());
            }
        }
    }

    @Test void explicitOriginalRowAliasForwardsOnlyTheNamedAExecutionChoiceAndClosedResultsRefuseAccess() {
        try(var fixture=new NativeAEmissionBindingTest.SmallFrame();var binding=NativeAEmissionBinding.fromOriginalGeometry(fixture.input);
                var tracking=new CorridorAwareTracker().trackOptionalEmission(fixture.input,binding,JunctionContext.empty(),List.of(),
                    GeometryCleanupConfig.disabled(),QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,
                    NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            assertFalse(tracking.results().isEmpty());var result=tracking.results().get(0);
            assertEquals(NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1,result.executionChoice());
            assertNotEquals(NativeAEmissionSolver.EXECUTION_VERSION,result.executionVersion());
            assertEquals(OptionalEmissionContext.POLICY_ID,result.numericalPolicyId());assertEquals(0,result.cost(),0);
            assertTrue(result.refusalReuseDiagnostics().isPresent());result.close();
            assertThrows(IllegalStateException.class,result::refusalReuseDiagnostics);
        }
    }

    private record Path(List<Integer> ids,double cost,int consumed) { }
    private static int compare(Path a,Path b) {
        int compared=Double.compare(a.cost(),b.cost());if(compared!=0)return compared;
        compared=Integer.compare(a.consumed(),b.consumed());if(compared!=0)return compared;
        for(int i=0;i<Math.min(a.ids().size(),b.ids().size());i++) {
            compared=Integer.compare(a.ids().get(i),b.ids().get(i));if(compared!=0)return compared;
        }
        return Integer.compare(a.ids().size(),b.ids().size());
    }
    private static void enumerate(NativeEngine engine,Graph graph,CanonicalKey key,List<Integer> ids,double cost,int consumed,List<Path> paths) {
        if(graph.vertices().get(key.cursorId()).terminal()){paths.add(new Path(ids,cost,consumed));return;}
        for(var arc:graph.arcs())if(arc.fromId()==key.cursorId()) {
            var actual=engine.provider.certify(key,arc);if(!actual.admitted())continue;
            var next=graph.vertices().get(arc.toId());var path=new ArrayList<Integer>(ids);path.add(next.id());
            enumerate(engine,graph,new CanonicalKey(next.id(),arc.zeroPhysical()?key.effectivePositiveArcId():arc.id(),
                next.numeric()?next.id():key.lastNumericVertexId(),0),List.copyOf(path),cost+actual.q()+actual.c()+actual.t()+actual.k(),
                consumed+arc.consumedRows(),paths);
        }
    }
    static NativeAEmissionSolverTest.Fixture tiny(String variant) {
        var anchors=variant.equals("refused")?List.of(new RasterPoint(8,20),new RasterPoint(10,20),new RasterPoint(12,22.5),
            new RasterPoint(14,20),new RasterPoint(16,22.5)):List.of(new RasterPoint(8,20),new RasterPoint(10,20),new RasterPoint(12,20));
        return new NativeAEmissionSolverTest.Fixture(anchors,(x,y)->(variant.equals("weak")?.1:1)*Math.max(0,1-Math.abs(y-20)/3.),5);
    }
    static JunctionContext constraints(String variant) {
        return new JunctionContext((variant.equals("refused")?java.util.stream.IntStream.of(0,2,4):java.util.stream.IntStream.of(0,2))
            .mapToObj(row->new EndpointConstraint(row,row+1,true,false,0,0,0)).toList());
    }
    static NativeAEmissionSolver.Result solve(NativeAEmissionSolverTest.Fixture f,JunctionContext junctions,boolean competitive,
            NativeAEmissionSolver.ExecutionChoice choice) {
        var profiles=new CorridorExtractor().extract(f.input.profiles().levelZeroProfiles(),1);
        return NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),f.input,NativeAEmissionSolverTest.track(profiles),profiles,1,
            junctions,Map.of(),List.of(),competitive,QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,
            GeometryCleanupConfig.disabled(),null,null,choice);
    }
    @Test void distinctActualCanonicalPrefixesReuseOneCompletedPositiveRejectedEvaluation() throws Exception {
        var observer=new Evaluations();
        try(var fixture=fixture(observer,false);var engine=new NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var arc=engine.arc(1,3,true);var keys=engine.prefixes(arc);observer.calls.set(0);
            var first=observer.certify(engine.provider,keys.get(0),arc);var receipt=engine.retainedReceipt(arc);
            var beforeHit=fixture.input.workBudget().snapshot();
            var second=observer.certify(engine.provider,keys.get(1),arc);
            assertFalse(first.admitted());assertEquals(first.reason(),second.reason());
            assertEquals(1,observer.calls.get(),"Count actual NativeConnectorEvaluator.evaluateInternal entries, not memo statistics");
            assertSame(receipt,engine.retainedReceipt(arc));assertEquals(Status.REJECTED,receipt.status());
            var afterHit=fixture.input.workBudget().snapshot();
            assertTrue(beforeHit.operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION)>0);
            for(var operation:List.of(EmissionWorkBudget.Operation.MODE_EXTRACTION,EmissionWorkBudget.Operation.REGION_QUERY,
                    EmissionWorkBudget.Operation.NODE_READ,EmissionWorkBudget.Operation.CELL_QUERY))
                assertEquals(beforeHit.operationUnits().get(operation),afterHit.operationUnits().get(operation),operation.name());
            var stats=engine.stats();assertEquals(1,stats.evaluatorMissCalls());assertEquals(1,stats.refusalReuseHits());
            assertEquals(2,stats.positiveCanonicalRefusals());assertEquals(2,stats.positiveCanonicalRejected());
            assertEquals(0,stats.positiveCanonicalUnavailable());assertEquals(1,stats.returnedRejected());
            assertEquals(Map.of(receipt.reason(),1L),stats.firstRejectedReasons());assertEquals(1,stats.retainedRefusalEntries());
        }
    }

    @Test void closingTheRetainedInputBeforeAHitRevokesTheBorrowedReceiptAndReleasesAllOwners() throws Exception {
        try(var fixture=fixture(CancellationProbe.NONE,true);
                var engine=new NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var arc=engine.arc(1,3,false);var keys=engine.prefixes(arc);engine.provider.certify(keys.get(0),arc);
            assertEquals(1,engine.stats().retainedRefusalEntries());fixture.input.close();
            assertThrows(IllegalStateException.class,()->engine.provider.certify(keys.get(1),arc));
            assertEquals(0,fixture.ledger.currentBytes());
        }
    }

    @Test void distinctActualCanonicalPrefixesReuseOneNormallyUnavailablePositiveEvaluation() throws Exception {
        var observer=new Evaluations();
        try(var fixture=fixture(observer,true);var engine=new NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var arc=engine.arc(1,3,false);var keys=engine.prefixes(arc);observer.calls.set(0);
            var first=observer.certify(engine.provider,keys.get(0),arc);var second=observer.certify(engine.provider,keys.get(1),arc);
            assertEquals("SCALAR_SUPPORT",first.reason());assertEquals(first.reason(),second.reason());
            assertEquals(1,observer.calls.get());
            assertEquals(Status.UNAVAILABLE,engine.retainedReceipt(arc).status());
            var stats=engine.stats();assertEquals(1,stats.returnedUnavailable());assertEquals(2,stats.positiveCanonicalUnavailable());
            assertEquals(0,stats.positiveCanonicalRejected());assertEquals(Map.of(Reason.SCALAR_SUPPORT,1L),stats.firstUnavailableReasons());
        }
    }

    @Test void admittedPositiveEvidenceStillComputesDifferentIncomingNativeTurnCosts() throws Exception {
        var observer=new Evaluations();
        try(var fixture=fixture(observer,false);var engine=new NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var arc=engine.arc(1,3,false);var keys=engine.prefixes(arc);observer.calls.set(0);
            var first=observer.certify(engine.provider,keys.get(0),arc);var second=observer.certify(engine.provider,keys.get(1),arc);
            assertTrue(first.admitted());assertTrue(second.admitted());assertEquals(1,observer.calls.get());
            assertTrue(second.k()>first.k());assertTrue(second.t()>first.t());
            var stats=engine.stats();assertEquals(1,stats.returnedAdmitted());assertEquals(1,stats.admittedEvidenceReuseHits());
            assertEquals(0,stats.refusalReuseHits());assertEquals(0,stats.retainedRefusalEntries());
        }
    }

    @Test void zeroArcWithTwoActualIncomingDirectionsIsEvaluatedTwiceAndNeverMemoized() throws Exception {
        var observer=new Evaluations();
        try(var fixture=new NativeAEmissionSolverTest.Fixture(List.of(new RasterPoint(10,20),new RasterPoint(12,20),
                new RasterPoint(14,20),new RasterPoint(12,20)),(x,y)->Math.max(0,1-Math.abs(y-20)/3.),5,
                new EmissionWorkBudget(TraceBudgets.defaults(),observer),0);
                var engine=new NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var arc=engine.arc(1,3,false);assertTrue(arc.zeroPhysical());var keys=engine.prefixes(arc);observer.calls.set(0);
            var first=observer.certify(engine.provider,keys.get(0),arc);var receipts=new HashSet<>(engine.receipts());
            var second=observer.certify(engine.provider,keys.get(1),arc);var later=new HashSet<>(engine.receipts());later.removeAll(receipts);
            assertTrue(first.admitted());assertTrue(second.admitted());assertEquals(2,observer.calls.get());assertEquals(1,later.size());
            assertNotEquals(receipts.iterator().next().probes().get(0).metricNormal(),later.iterator().next().probes().get(0).metricNormal());
            var stats=engine.stats();assertEquals(0,stats.positiveArcLookups());assertEquals(0,stats.evaluatorMissCalls());
            assertEquals(0,stats.retainedRefusalEntries());assertEquals(2,stats.excludedOptionalEvaluatorCalls());
        }
    }

    @Test void defaultOffStillReevaluatesRefusalsAndDoesNotInventDiagnostics() throws Exception {
        var observer=new Evaluations();
        try(var fixture=fixture(observer,true);var engine=new NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.UNCHANGED_V1)) {
            var arc=engine.arc(1,3,false);var keys=engine.prefixes(arc);observer.calls.set(0);
            observer.certify(engine.provider,keys.get(0),arc);observer.certify(engine.provider,keys.get(1),arc);
            assertEquals(2,observer.calls.get());assertNull(field(engine.actual,"refusalMemo"));
            assertTrue(engine.optionalStats().isEmpty());
        }
    }

    @Test void reusedReceiptCannotCrossEngineArcIdentityAndSourceMustStillBeFreshAndOpen() throws Exception {
        try(var fixture=fixture(CancellationProbe.NONE,true);var foreign=fixture(CancellationProbe.NONE,true)) {
            long baseline=fixture.ledger.currentBytes();
            try(var engine=new NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1);
                    var other=new NativeEngine(foreign,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
                var arc=engine.arc(1,3,false);var keys=engine.prefixes(arc);engine.provider.certify(keys.get(0),arc);
                var foreignArc=other.arc(1,3,false);assertEquals(arc,foreignArc);assertNotSame(arc,foreignArc);
                assertThrows(IllegalArgumentException.class,()->engine.provider.certify(keys.get(1),foreignArc));
                var normal=fixture.input.profiles().levelZeroProfiles().get(1).normalScreen();double x=normal.x;normal.x+=.125;
                assertThrows(IllegalStateException.class,()->engine.provider.certify(keys.get(1),arc));normal.x=x;
                assertEquals(0,engine.stats().refusalReuseHits());engine.provider.certify(keys.get(1),arc);
                assertEquals(1,engine.stats().refusalReuseHits());engine.close();
                assertThrows(IllegalStateException.class,()->engine.provider.certify(keys.get(1),arc));
            }
            assertEquals(baseline,fixture.ledger.currentBytes());
        }
    }

    @ParameterizedTest @ValueSource(strings={"work","pair","cancel","memory"})
    void aTerminalBeforeAHitThrowsTheSameFailureInsteadOfReturningOldEvidence(String kind) throws Exception {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        try(var fixture=fixture(cancelled::get,true)) {
            long baseline=fixture.ledger.currentBytes();
            try(var engine=new NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
                var arc=engine.arc(1,3,false);var keys=engine.prefixes(arc);engine.provider.certify(keys.get(0),arc);
                var budget=fixture.input.workBudget();AttemptMemoryLedger.Reservation pressure=null;
                if(kind.equals("work"))budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.snapshot().workLimit()-budget.snapshot().workUsed());
                if(kind.equals("pair"))budget.charge(EmissionWorkBudget.Operation.TRANSITION,budget.snapshot().pairLimit()-budget.snapshot().pairVisits());
                if(kind.equals("cancel"))cancelled.set(true);
                if(kind.equals("memory"))pressure=fixture.owner.reserve(fixture.ledger.limitBytes()-fixture.ledger.currentBytes()-511);
                try {
                    var failure=assertThrows(RuntimeException.class,()->engine.provider.certify(keys.get(1),arc));
                    if(kind.equals("memory"))assertInstanceOf(AttemptMemoryLedger.ResourceLimitException.class,failure);
                    else if(kind.equals("cancel"))assertInstanceOf(java.util.concurrent.CancellationException.class,failure);
                    else assertEquals(kind.equals("pair")?EmissionWorkBudget.LimitKind.PAIR_VISITS:EmissionWorkBudget.LimitKind.TOTAL_WORK,
                        ((EmissionWorkBudget.LimitExceededException)failure).kind());
                    assertSame(failure,assertThrows(RuntimeException.class,fixture.input::requireOpenAndUnchanged));
                    assertSame(failure,assertThrows(RuntimeException.class,()->engine.provider.certify(keys.get(1),arc)));
                    assertEquals(0,(long)field(field(engine.actual,"refusalMemo"),"refusalHits"));
                }finally{if(pressure!=null)pressure.close();}
            }
            assertEquals(baseline,fixture.ledger.currentBytes());
        }
    }

    @ParameterizedTest @ValueSource(strings={"memory","cancel","work"})
    void failedFirstPublicationLeavesNoMemoEntryAndReleasesTheCompletedEvaluatorPayload(String kind) throws Exception {
        var fault=new PublicationFault(kind);
        try(var fixture=fixture(fault,false)) {
            long baseline=fixture.ledger.currentBytes();
            try(var engine=new NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
                var arc=engine.arc(1,3,true);var keys=engine.prefixes(arc);
                fault.fixture=fixture;fault.issued=engine.receiptMap();fault.armed=true;
                var failure=assertThrows(RuntimeException.class,()->engine.provider.certify(keys.get(0),arc));
                assertEquals(1,fault.issued.size(),"The real evaluator completed one returned receipt before memo publication");
                assertEquals(0,(long)field(field(engine.actual,"refusalMemo"),"entries"));
                assertEquals(0,Arrays.stream((Object[])field(field(engine.actual,"refusalMemo"),"slots")).filter(Objects::nonNull).count());
                assertSame(failure,assertThrows(RuntimeException.class,fixture.input::requireOpenAndUnchanged));
                assertSame(failure,assertThrows(RuntimeException.class,()->engine.provider.certify(keys.get(1),arc)));
            }finally{if(fault.pressure!=null)fault.pressure.close();}
            assertEquals(baseline,fixture.ledger.currentBytes());
        }
    }

    static final class PublicationFault implements CancellationProbe {
        final String kind;boolean armed;Map<?,?> issued;NativeAEmissionSolverTest.Fixture fixture;
        AttemptMemoryLedger.Reservation pressure;
        PublicationFault(String kind){this.kind=kind;}
        @Override public boolean cancelled() {
            if(!armed||issued==null||issued.isEmpty())return false;armed=false;
            if(kind.equals("cancel"))return true;
            if(kind.equals("memory"))pressure=fixture.owner.reserve(fixture.ledger.limitBytes()-fixture.ledger.currentBytes()-64);
            if(kind.equals("work")) {
                var budget=fixture.input.workBudget();budget.charge(EmissionWorkBudget.Operation.PREPARATION,
                    budget.snapshot().workLimit()-budget.snapshot().workUsed());
            }
            return false;
        }
    }

    static NativeAEmissionSolverTest.Fixture fixture(CancellationProbe observer,boolean unavailable) {
        return new NativeAEmissionSolverTest.Fixture(List.of(new RasterPoint(8,20),new RasterPoint(10,20),
            new RasterPoint(12,20),new RasterPoint(14,20)),(x,y)->Math.max(0,1-Math.abs(y-20)/3.),5,
            new EmissionWorkBudget(TraceBudgets.defaults(),observer),0,unavailable);
    }
    static final class Evaluations implements CancellationProbe {
        final AtomicInteger calls=new AtomicInteger();
        boolean awaitingEntry;
        Admission certify(TransitionProvider provider,CanonicalKey key,Arc arc) {
            awaitingEntry=true;
            try{return provider.certify(key,arc);}finally{awaitingEntry=false;}
        }
        @Override public boolean cancelled() {
            if(!awaitingEntry)return false;
            boolean entry=StackWalker.getInstance().walk(frames->{
                var iterator=frames.iterator();
                while(iterator.hasNext()) {
                    var frame=iterator.next();
                    if(frame.getClassName().equals(EmissionWorkBudget.class.getName())&&frame.getMethodName().equals("charge")) {
                        if(!iterator.hasNext())return false;var caller=iterator.next();
                        if(!caller.getClassName().equals(SampledEmissionInput.class.getName())
                                ||!caller.getMethodName().equals("requireRetainedSourceUnchanged"))return false;
                        while(iterator.hasNext()) {var ancestor=iterator.next();
                            if(ancestor.getClassName().equals(NativeConnectorEvaluator.class.getName())
                                    &&ancestor.getMethodName().equals("evaluateInternal"))return true;}
                        return false;
                    }
                }
                return false;
            });
            if(entry){calls.incrementAndGet();awaitingEntry=false;}
            return false;
        }
    }
    static final class NativeEngine implements AutoCloseable {
        final Object actual;
        final TransitionProvider provider;
        final List<Arc> arcs;
        final List<?> vertices;
        NativeEngine(NativeAEmissionSolverTest.Fixture f,NativeAEmissionSolver.ExecutionChoice choice) throws Exception {
            this(f,choice,JunctionContext.empty());
        }
        NativeEngine(NativeAEmissionSolverTest.Fixture f,NativeAEmissionSolver.ExecutionChoice choice,JunctionContext junctions) throws Exception {
            var profiles=new CorridorExtractor().extract(f.input.profiles().levelZeroProfiles(),1);
            var owner=f.input.openExecutionOwner("tiny-native-refusal-test");owner.reserve(2048);
            var type=Class.forName(NativeAEmissionSolver.class.getName()+"$Engine");
            var constructor=Arrays.stream(type.getDeclaredConstructors()).filter(c->c.getParameterCount()==15).findFirst().orElseThrow();
            constructor.setAccessible(true);
            try {actual=constructor.newInstance(new CorridorCenterlineOptimizer(),f.input,NativeAEmissionSolverTest.track(profiles),
                profiles,1.,junctions,Map.of(),List.of(),QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                ExecutionPolicy.EAGER_ALL_PROBES_V1,GeometryCleanupConfig.disabled(),owner,null,null,choice);}
            catch(InvocationTargetException failure){owner.close();throw (Exception)failure.getCause();}
            provider=(TransitionProvider)actual;arcs=((Graph)field(actual,"graph")).arcs();vertices=(List<?>)field(actual,"vertices");
        }
        int row(int id) throws Exception{return (int)method(vertices.get(id),"row");}
        double offset(int id) throws Exception{return (double)method(vertices.get(id),"offset");}
        int vertex(int row,boolean far) throws Exception {
            int best=-1;double value=far?-1:Double.POSITIVE_INFINITY;
            for(int id=0;id<vertices.size();id++)if(row(id)==row) {
                double candidate=Math.abs(offset(id));
                if(far?candidate>value:candidate<value){best=id;value=candidate;}
            }
            return best;
        }
        Arc arc(int from,int to,boolean far) throws Exception {
            int a=vertex(from,far),b=vertex(to,far);
            return arcs.stream().filter(arc->arc.fromId()==a&&arc.toId()==b&&arc.optional()).findFirst().orElseThrow();
        }
        List<CanonicalKey> prefixes(Arc outgoing) throws Exception {
            var incoming=arcs.stream().filter(arc->!arc.optional()&&arc.toId()==outgoing.fromId()).toList();
            assertTrue(incoming.size()>=2);var keys=new ArrayList<CanonicalKey>();
            Arc near=incoming.get(0),far=incoming.get(0);
            for(var arc:incoming) {
                if(Math.abs(offset(arc.fromId()))<Math.abs(offset(near.fromId())))near=arc;
                if(Math.abs(offset(arc.fromId()))>Math.abs(offset(far.fromId())))far=arc;
            }
            for(var arc:List.of(near,far)) {
                var key=new CanonicalKey(arc.fromId(),-1,arc.fromId(),0);
                assertTrue(provider.certify(key,arc).admitted());keys.add(new CanonicalKey(arc.toId(),arc.id(),arc.toId(),0));
            }
            assertNotEquals(keys.get(0),keys.get(1));return keys;
        }
        @SuppressWarnings("unchecked") Optional<NativeAEmissionSolver.RefusalReuseDiagnostics> optionalStats() throws Exception {
            var method=actual.getClass().getDeclaredMethod("reuseDiagnostics");method.setAccessible(true);
            return (Optional<NativeAEmissionSolver.RefusalReuseDiagnostics>)method.invoke(actual);
        }
        NativeAEmissionSolver.RefusalReuseDiagnostics stats() throws Exception{return optionalStats().orElseThrow();}
        Receipt retainedReceipt(Arc arc) throws Exception {
            Object entry=((Object[])field(field(actual,"refusalMemo"),"slots"))[arc.id()];return (Receipt)method(entry,"receipt");
        }
        @SuppressWarnings("unchecked") Map<Receipt,Boolean> receiptMap() throws Exception {
            return (Map<Receipt,Boolean>)field(field(actual,"evaluator"),"issued");
        }
        Set<Receipt> receipts() throws Exception{return receiptMap().keySet();}
        @Override public void close() throws Exception{((AutoCloseable)actual).close();}
    }
    static Object field(Object instance,String name) throws Exception {var field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);}
    static Object method(Object instance,String name) throws Exception {var method=instance.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(instance);}
}
