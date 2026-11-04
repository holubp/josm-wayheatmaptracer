package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.CompetitiveAdmissionExecutor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class NativeAPrefixDominanceExecutorTest {
    @ParameterizedTest @ValueSource(strings={"cancel","memory"})
    void finalRetiredFilteringPreadmitsScratchAndCannotReturnAnIncumbentAfterFailure(String kind) {
        var holder=new java.util.concurrent.atomic.AtomicReference<Control>();var armed=new boolean[]{true};
        var pressure=new AttemptMemoryLedger.Reservation[1];
        CancellationProbe cancellation=()->{
            var f=holder.get();if(f==null||!armed[0]||f.census.retiredPrefixes()==0||!f.census.hasIncumbent()
                    ||f.census.phase()!=NativeAExecutionCensus.Phase.CLOSURE||f.census.cursorId()!=-1)return false;
            armed[0]=false;if(kind.equals("cancel"))return true;
            pressure[0]=f.fixture.owner.reserve(f.fixture.ledger.limitBytes()-f.fixture.ledger.currentBytes());return false;
        };
        try(var f=new Control("filter",cancellation)) {
            holder.set(f);long before=f.fixture.ledger.currentBytes();
            try {
                var failure=assertThrows(RuntimeException.class,()->f.run(true));assertFalse(armed[0]);assertSame(failure,f.census.failure());
                assertTrue(f.census.hasIncumbent());assertEquals(0,f.census.filteredInactivePending());
                if(kind.equals("cancel"))assertInstanceOf(java.util.concurrent.CancellationException.class,failure);
                else assertInstanceOf(AttemptMemoryLedger.ResourceLimitException.class,failure);
                assertSame(failure,assertThrows(RuntimeException.class,f.fixture.input::requireOpenAndUnchanged));
            }finally{if(pressure[0]!=null)pressure[0].close();}
            assertEquals(before,f.fixture.ledger.currentBytes());
        }
    }
    @ParameterizedTest @ValueSource(strings={"work","pair","memory","cancel"})
    void replacementPublicationFailureIsStickyAndCannotRetireBeforeTheReplacementIsInstalled(String kind) {
        var holder=new java.util.concurrent.atomic.AtomicReference<Control>();var armed=new boolean[]{true};var seen=new long[]{-2};var visits=new int[1];
        var pressure=new AttemptMemoryLedger.Reservation[1];
        CancellationProbe cancellation=()->{
            var f=holder.get();if(f==null||!armed[0]||f.census.phase()!=NativeAExecutionCensus.Phase.PREFIX_PUBLICATION||f.census.cursorId()!=4)return false;
            if(seen[0]==f.census.prefixVersion())return false;seen[0]=f.census.prefixVersion();if(++visits[0]!=2)return false;
            armed[0]=false;if(kind.equals("cancel"))return true;
            var budget=f.fixture.input.workBudget();
            if(kind.equals("work"))budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.workLimit()-budget.workUsed());
            else if(kind.equals("pair"))budget.charge(EmissionWorkBudget.Operation.TRANSITION,budget.pairLimit()-budget.pairVisits());
            else pressure[0]=f.fixture.owner.reserve(f.fixture.ledger.limitBytes()-f.fixture.ledger.currentBytes());
            return false;
        };
        try(var f=new Control("retire",cancellation)) {
            holder.set(f);long before=f.fixture.ledger.currentBytes();
            try {
                var failure=assertThrows(RuntimeException.class,()->f.run(true));assertFalse(armed[0]);assertSame(failure,f.census.failure());
                if(kind.equals("memory"))assertInstanceOf(AttemptMemoryLedger.ResourceLimitException.class,failure);
                else if(kind.equals("cancel"))assertInstanceOf(java.util.concurrent.CancellationException.class,failure);
                else assertEquals(kind.equals("pair")?EmissionWorkBudget.LimitKind.PAIR_VISITS:EmissionWorkBudget.LimitKind.TOTAL_WORK,
                    ((EmissionWorkBudget.LimitExceededException)failure).kind());
                assertSame(failure,assertThrows(RuntimeException.class,f.fixture.input::requireOpenAndUnchanged));
                assertEquals(0,f.census.retiredPrefixes(),"Before the first retirement all replacement obligations must already be owned");
                assertEquals(f.fixture.input.workBudget().workUsed(),f.census.workUsed());
                assertEquals(f.fixture.input.workBudget().pairVisits(),f.census.pairVisits());
            }finally{if(pressure[0]!=null)pressure[0].close();}
            assertEquals(before,f.fixture.ledger.currentBytes(),"The failed closure/frontier and all pending leases close");
        }
    }
    @Test void aSourceChangeAfterRetirementCannotPublishEvenWhenStaleWorkBypassesCertify() {
        var holder=new java.util.concurrent.atomic.AtomicReference<Control>();var changed=new boolean[1];var old=new double[1];
        var mutated=new java.util.concurrent.atomic.AtomicReference<java.awt.geom.Point2D.Double>();
        CancellationProbe cancellation=()->{
            var f=holder.get();if(f!=null&&!changed[0]&&f.census.retiredPrefixes()>0) {
                changed[0]=true;var normal=f.fixture.input.profiles().levelZeroProfiles().get(0).normalScreen();mutated.set(normal);old[0]=normal.x;normal.x+=.125;
            }
            return false;
        };
        try(var f=new Control("retire",cancellation)) {
            holder.set(f);
            try {
                var failure=assertThrows(IllegalStateException.class,()->f.run(true));assertTrue(changed[0]);assertSame(failure,f.census.failure());
                assertSame(failure,assertThrows(RuntimeException.class,f.fixture.input::requireOpenAndUnchanged));
            }finally{if(mutated.get()!=null)mutated.get().x=old[0];}
        }
    }
    @Test void cancellationInAStaleHeapHeadVisitIsStillCheckedBeforeReturningTheIncumbent() {
        var holder=new java.util.concurrent.atomic.AtomicReference<Control>();var fired=new boolean[1];
        CancellationProbe cancellation=()->{
            var f=holder.get();if(f!=null&&!fired[0]&&f.census.retiredPrefixes()>0&&f.census.phase()==NativeAExecutionCensus.Phase.CLOSURE
                    &&f.census.cursorId()==4){fired[0]=true;return true;}return false;
        };
        try(var f=new Control("retire",cancellation)) {
            holder.set(f);var failure=assertThrows(java.util.concurrent.CancellationException.class,()->f.run(true));
            assertTrue(fired[0]);assertSame(failure,f.census.failure());assertSame(failure,assertThrows(RuntimeException.class,f.fixture.input::requireOpenAndUnchanged));
        }
    }
    @ParameterizedTest @ValueSource(strings={"retire","filter"})
    void aLateReplacementKeepsItsCoverageAndRetiredPendingNeverAppearsAsLiveProof(String mode) {
        try(var reference=new Control(mode);var enabled=new Control(mode)) {
            var expected=reference.enumerate().stream().min(NativeAPrefixDominanceExecutorTest::compare).orElseThrow();
            var result=enabled.run(true);assertEquals(expected.ids(),result.vertexIds());assertEquals(expected.cost(),result.cost(),0);
            assertTrue(enabled.census.retiredPrefixes()>0);assertTrue(result.finalMinimumLowerBound()>result.certifiedUpperBound());
            if(mode.equals("retire"))assertTrue(enabled.census.stalePrefixPops()>0);
            else {
                assertEquals(2,enabled.census.filteredInactivePending());assertEquals(1,result.unmeasuredCertifiedNoncompetitive());
                assertEquals(1,result.pendingProofs().size());assertEquals(3,result.pendingProofs().get(0).prefix().cursorId());
                assertTrue(result.pendingProofs().get(0).lowerBound()>result.cost());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","proper-prefix","signed"})
    void genericUnprovedRanksAndSignedCostDomainsKeepTheOriginalExecution(String mode) {
        try(var off=new Control(mode);var on=new Control(mode)) {
            var expected=off.run(false);var actual=on.run(true);
            assertEquals(expected.vertexIds(),actual.vertexIds());assertEquals(expected.arcIds(),actual.arcIds());assertEquals(expected.cost(),actual.cost(),0);
            assertEquals(expected.q(),actual.q(),0);assertEquals(expected.c(),actual.c(),0);
            assertFalse(on.census.hasPrefixDominance());assertEquals(0,on.census.dominatedPrefixes());assertEquals(0,on.census.retiredPrefixes());
            if(mode.equals("proper-prefix"))assertEquals(List.of(0,2,3,4,5,6),actual.vertexIds(),
                "Appending the common suffix reverses the shorter equal-prefix rank preference");
        }
    }
    @Test void fourSourceBackedPathsKeepTheHigherPrefixCostWhenTheSharedSuffixCollapsesTheGap() {
        try(var reference=new Control(false);var enabled=new Control(false)) {
            var paths=reference.enumerate();assertEquals(4,paths.size());
            var expected=paths.stream().min(NativeAPrefixDominanceExecutorTest::compare).orElseThrow();
            assertEquals(List.of(0,1,3,4,5),expected.ids());assertEquals(1,expected.cost(),0);assertEquals(0x1.0p-54,expected.c(),0);
            var actual=enabled.run(true);assertEquals(expected.ids(),actual.vertexIds());assertEquals(expected.cost(),actual.cost(),0);
            assertEquals(expected.q(),actual.q(),0);assertEquals(expected.c(),actual.c(),0);assertEquals(expected.t(),actual.t(),0);assertEquals(expected.k(),actual.k(),0);
            assertEquals(2,enabled.census.retiredPrefixes(),"Both equal-cost terminal versions must participate in stable-tie replacement");
            assertEquals(1,0+1.,0);assertEquals(1,0x1.0p-54+1.,0);
            assertEquals(2,0+2.,0);assertEquals(2,0x1.0p-54+2.,0);
        }
    }
    @Test void theTrueDominanceInverseSkipsOutgoingVersionsWithoutChangingTheExactFourPathWinner() {
        try(var reference=new Control(true);var enabled=new Control(true)) {
            var expected=reference.enumerate().stream().min(NativeAPrefixDominanceExecutorTest::compare).orElseThrow();
            var actual=enabled.run(true);assertEquals(expected.ids(),actual.vertexIds());assertEquals(expected.cost(),actual.cost(),0);
            assertTrue(enabled.census.dominatedPrefixes()>0,"A same-key lower-cost, lex-preferred installed prefix must suppress the dominated version's expansion");
            assertTrue(enabled.census.hasPrefixDominance());assertTrue(actual.finalMinimumLowerBound()>actual.certifiedUpperBound());
        }
    }
    record Path(CanonicalKey key,List<Integer> ids,double cost,double q,double c,double t,double k,int consumed) { }
    static int compare(Path a,Path b) {
        int order=Double.compare(a.cost(),b.cost());if(order!=0)return order;
        order=Integer.compare(a.consumed(),b.consumed());if(order!=0)return order;
        for(int i=0;i<Math.min(a.ids().size(),b.ids().size());i++){order=Integer.compare(a.ids().get(i),b.ids().get(i));if(order!=0)return order;}
        return Integer.compare(a.ids().size(),b.ids().size());
    }
    static final class Control implements AutoCloseable,NativePrefixDominanceProvider {
        final NativeAEmissionSolverTest.Fixture fixture;
        final AttemptMemoryLedger.Owner owner;
        final NativeAExecutionCensus census;
        final Graph graph;
        final BilinearSegmentIntegral.Result[] scalar=new BilinearSegmentIntegral.Result[3];
        final String mode;
        Control(boolean inverse){this(inverse?"inverse":"guard");}
        Control(String mode){this(mode,CancellationProbe.NONE);}
        Control(String mode,CancellationProbe cancellation) {
            this.mode=mode;fixture=new NativeAEmissionSolverTest.Fixture(List.of(new RasterPoint(10,20),new RasterPoint(12,20),new RasterPoint(14,20)),
                (x,y)->Math.max(0,1-Math.abs(y-20)/4.),5,new EmissionWorkBudget(TraceBudgets.defaults(),cancellation),0);
            owner=fixture.input.openExecutionOwner("source-backed-prefix-control");owner.reserve(4096);
            census=NativeAExecutionCensus.open(fixture.input);census.begin(fixture.input);
            int[] rows=mode.equals("filter")?new int[]{0,1,1,1,2,2,3,3,4,4,4}
                :mode.equals("proper-prefix")?new int[]{0,1,1,2,3,4,5}:new int[]{0,1,1,2,3,4,4};
            int[][] edges=mode.equals("filter")?new int[][]{{0,1},{0,2},{0,3},{1,4},{2,4},{3,5},{4,6},{5,7},{6,8},{6,9},{7,10}}
                :mode.equals("proper-prefix")?new int[][]{{0,1},{0,2},{1,4},{2,3},{3,4},{4,5},{5,6}}
                :new int[][]{{0,1},{0,2},{1,3},{2,3},{3,4},{4,5},{4,6}};
            int[] ranks=mode.equals("proper-prefix")?new int[]{0,1,1,2,2,2,3}:null;
            var vertices=new ArrayList<Vertex>();var arcs=new ArrayList<Arc>();
            for(int index=0;index<rows.length;index++)vertices.add(new Vertex(index,rows[index],0,true,index==0,
                mode.equals("filter")?index>=8:mode.equals("proper-prefix")?index==6:index>=5,
                ranks==null?mode.equals("duplicate")&&index==1?0:index:ranks[index]));
            for(int index=0;index<edges.length;index++)arcs.add(new Arc(index,edges[index][0],edges[index][1],2,0,false,false,index));
            if(mode.equals("signed")) {
                try {
                    var create=Graph.class.getDeclaredMethod("createSynthetic",List.class,List.class,Set.class,double.class,EmissionWorkBudget.class,AttemptMemoryLedger.Owner.class);
                    create.setAccessible(true);graph=(Graph)create.invoke(null,vertices,arcs,Set.of(),1.,fixture.input.workBudget(),owner);
                }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            }else graph=Graph.create(vertices,arcs,Set.of(),1,fixture.input.workBudget(),owner);
            var grid=fixture.input.context().nativeGrid();
            for(int index=0;index<3;index++) {
                fixture.input.workBudget().charge(EmissionWorkBudget.Operation.ALLOCATION,1);owner.reserve(1024);
                scalar[index]=BilinearSegmentIntegral.evaluate(grid,grid.toGrid(new RasterPoint(10,20+2*index)),
                    grid.toGrid(new RasterPoint(12,20+2*index)),CancellationProbe.NONE,
                    ()->fixture.input.workBudget().charge(EmissionWorkBudget.Operation.CELL_QUERY,1));
                assertEquals(BilinearSegmentIntegral.Status.COMPLETE,scalar[index].status());
                assertEquals(1-index*.5,scalar[index].mean().orElseThrow(),0);
            }
        }
        @Override public BilinearSegmentIntegral.ReadOnlyScalarGrid nativeGrid(){return fixture.input.context().nativeGrid();}
        @Override public NativeConnectorEvaluator.NativeScalarPreparation prepareScalar(CanonicalKey key,Arc arc){return null;}
        @Override public void requireDominanceSourceUnchanged(){fixture.input.requireOpenAndUnchanged();}
        @Override public Admission certify(CanonicalKey key,Arc arc) {
            requireDominanceSourceUnchanged();assertEquals(key.cursorId(),arc.fromId());
            int kind=mode.equals("filter")?arc.id()==8?1:arc.id()==9?2:0
                :mode.equals("proper-prefix")?arc.id()==6?1:0:arc.id()==5?1:arc.id()==6?2:0;
            double c=mode.equals("filter")?arc.id()==0?1:arc.id()==2?5:arc.id()==6&&key.effectivePositiveArcId()==4?10:0
                :mode.equals("proper-prefix")||mode.equals("signed")?0
                :mode.equals("retire")?arc.id()==0?0x1p-54:arc.id()==4&&key.effectivePositiveArcId()==3?0x1p-53:0
                :arc.id()==(mode.equals("inverse")?1:0)?0x1.0p-54:0;
            // Only Q is factual source integration. C is an explicit nonnegative executor
            // control table; this is not a manufactured native A receipt or NativeEngine proof.
            if(mode.equals("signed")) {
                try {
                    var raw=Admission.class.getDeclaredMethod("syntheticExact",double.class,double.class,double.class,double.class,Object.class);
                    raw.setAccessible(true);return (Admission)raw.invoke(null,arc.id()==0?-1.:kind,0.,0.,0.,scalar[kind]);
                }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            }
            return Admission.measured(arc.sourceSpanMeters(),1,scalar[kind],c,0,0,scalar[kind]);
        }
        Result run(boolean enabled) {
            try {return enabled?CompetitiveAdmissionExecutor.forNativePrefixDominance(graph,this,fixture.input.workBudget(),owner,census).run()
                :new CompetitiveAdmissionExecutor(graph,this,fixture.input.workBudget(),owner).run();
            }catch(RuntimeException failure){fixture.input.recordTerminalExecutionFailure(failure);census.fail(failure);throw failure;}
        }
        List<Path> enumerate(){var paths=new ArrayList<Path>();enumerate(new Path(new CanonicalKey(0,-1,0,0),List.of(0),0,0,0,0,0,0),paths);return paths;}
        void enumerate(Path p,List<Path> paths) {
            if(graph.vertices().get(p.key().cursorId()).terminal()){paths.add(p);return;}
            for(Arc arc:graph.arcs())if(arc.fromId()==p.key().cursorId()) {
                var a=certify(p.key(),arc);assertTrue(a.admitted());var ids=new ArrayList<>(p.ids());ids.add(arc.toId());
                enumerate(new Path(new CanonicalKey(arc.toId(),arc.zeroPhysical()?p.key().effectivePositiveArcId():arc.id(),arc.toId(),0),List.copyOf(ids),
                    p.cost()+a.q()+a.c()+a.t()+a.k(),p.q()+a.q(),p.c()+a.c(),p.t()+a.t(),p.k()+a.k(),p.consumed()+arc.consumedRows()),paths);
            }
        }
        @Override public void close(){owner.close();fixture.input.close();fixture.owner.close();census.close();assertEquals(0,fixture.ledger.currentBytes());}
    }
}
