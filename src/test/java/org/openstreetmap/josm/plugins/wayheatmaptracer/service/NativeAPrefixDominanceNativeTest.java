package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.CompetitiveAdmissionExecutor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class NativeAPrefixDominanceNativeTest {
    record Path(CanonicalKey key,List<Integer> ids,List<Integer> arcs,double cost,double q,double c,double t,double k,int consumed) { }
    record Collision(int ySteps,int xSteps,int preferredBranch,int cheapBranch,double preferredPrefix,double cheapPrefix,
            List<Integer> suffixes,List<Double> finalCosts) { }

    @Test void actualNativePrefixesWithOneCommonPositiveIncomingArcHaveTwoLegalRoundingCollapseSuffixes() throws Exception {
        Collision found=findCollision(1,0);
        assertNotNull(found,"The frozen actual native four-path collision must remain reachable");
        assertEquals(new Collision(1,0,1,19,0x1.9bb05e818b213p-1,0x1.9bb05e818b211p-1,
            List.of(42,44),List.of(0x1.d00ea5700fbep0,0x1.b736ae78e57b2p0)),found);
        System.out.println("Actual native collision "+found+" prefix hex="+Double.toHexString(found.preferredPrefix())
            +"/"+Double.toHexString(found.cheapPrefix())+" final hex="+found.finalCosts().stream().map(Double::toHexString).toList());
    }
    @Test void theExecutedFourPathProjectionUsesActualNativeAdmissionsAndKeepsTheMoreExpensivePreferredPrefix() throws Exception {
        try(var f=fixture(1,0);var e=new NativeARefusalReuseTest.NativeEngine(f,
                NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1,fixed());
                var owner=f.input.openExecutionOwner("actual-native-four-path-projection");var census=NativeAExecutionCensus.open(f.input)) {
            owner.reserve(4096);census.begin(f.input);
            int[] ids={0,1,19,20,21,e.arcs.get(42).toId(),e.arcs.get(44).toId()};int[] sourceArcs=new int[7];
            var nativeGraph=(Graph)NativeARefusalReuseTest.field(e.actual,"graph");var vertices=new ArrayList<Vertex>();
            for(int index=0;index<ids.length;index++) {
                var v=nativeGraph.vertices().get(ids[index]);vertices.add(new Vertex(index,v.originalRow(),v.phaseId(),v.numeric(),index==0,index>=5,index));
            }
            int[][] edges={{0,1},{0,2},{1,3},{2,3},{3,4},{4,5},{4,6}};var arcs=new ArrayList<Arc>();
            for(int index=0;index<edges.length;index++) {
                var a=arc(e,ids[edges[index][0]],ids[edges[index][1]]);sourceArcs[index]=a.id();
                arcs.add(new Arc(index,edges[index][0],edges[index][1],a.sourceSpanMeters(),a.consumedRows(),a.optional(),a.zeroPhysical(),index));
            }
            var graph=Graph.create(vertices,arcs,Set.of(),1,f.input.workBudget(),owner);
            NativePrefixDominanceProvider provider=new NativePrefixDominanceProvider() {
                @Override public BilinearSegmentIntegral.ReadOnlyScalarGrid nativeGrid(){return e.provider.nativeGrid();}
                @Override public NativeScalarPreparation prepareScalar(CanonicalKey key,Arc arc){return null;}
                @Override public void requireDominanceSourceUnchanged(){f.input.requireOpenAndUnchanged();}
                @Override public Admission certify(CanonicalKey key,Arc arc) {
                    var actual=new CanonicalKey(ids[key.cursorId()],key.effectivePositiveArcId()<0?-1:sourceArcs[key.effectivePositiveArcId()],
                        ids[key.lastNumericVertexId()],key.phaseId());
                    // Every returned Q/C/T/K and receipt comes from the actual private Engine.
                    // Only this bounded test graph projects four paths; production keeps all states.
                    return e.provider.certify(actual,e.arcs.get(sourceArcs[arc.id()]));
                }
            };
            var reference=new CompetitiveAdmissionExecutor(graph,provider,f.input.workBudget(),owner).run();
            var actual=CompetitiveAdmissionExecutor.forNativePrefixDominance(graph,provider,f.input.workBudget(),owner,census).run();
            assertEquals(List.of(0,1,3,4,6),reference.vertexIds());assertEquals(reference.vertexIds(),actual.vertexIds());
            assertEquals(0x1.b736ae78e57b2p0,actual.cost(),0);assertEquals(reference.cost(),actual.cost(),0);
            assertEquals(reference.q(),actual.q(),0);assertEquals(reference.c(),actual.c(),0);assertEquals(reference.t(),actual.t(),0);assertEquals(reference.k(),actual.k(),0);
            assertEquals(List.of(0,1,20,21,26),actual.vertexIds().stream().map(index->ids[index]).toList());
        }
    }
    @Test void completeActualNativeInventoryMatchesIndependentEnumerationAndBothOldAndNewFinalSolvers() throws Exception {
        Path expected;
        try(var f=fixture(1,0);var e=new NativeARefusalReuseTest.NativeEngine(f,
                NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1,fixed())) {
            var graph=(Graph)NativeARefusalReuseTest.field(e.actual,"graph");var paths=new ArrayList<Path>();int seed=one(e,0);
            enumerate(e,graph,new Path(new CanonicalKey(seed,-1,seed,0),List.of(seed),List.of(),0,0,0,0,0,0),paths);
            assertTrue(paths.size()>4);expected=paths.stream().min(NativeAPrefixDominanceNativeTest::compare).orElseThrow();
        }
        for(int execution=0;execution<4;execution++) {
            boolean competitive=execution!=0;
            var choice=execution<2?NativeAEmissionSolver.ExecutionChoice.UNCHANGED_V1
                :execution==2?NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_REFINEMENT_V1
                :NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_PREFIX_DOMINANCE_V1;
            var current=new java.util.concurrent.atomic.AtomicReference<NativeAExecutionCensus>();var beforeFinal=new long[]{-1};
            CancellationProbe probe=()->{var c=current.get();if(c!=null&&c.phase()==NativeAExecutionCensus.Phase.FINAL_EXACT&&beforeFinal[0]<0)
                beforeFinal[0]=c.dominatedPrefixes()+c.retiredPrefixes();return false;};
            try(var f=fixture(1,0,probe);var census=NativeAExecutionCensus.open(f.input)) {
                boolean observed=choice!=NativeAEmissionSolver.ExecutionChoice.UNCHANGED_V1;
                if(observed){census.begin(f.input);current.set(census);}
                var profiles=new CorridorExtractor().extract(f.input.profiles().levelZeroProfiles(),1);
                try(var result=NativeAEmissionSolver.solve(new CorridorCenterlineOptimizer(),f.input,NativeAEmissionSolverTest.track(profiles),profiles,1,
                        fixed(),Map.of(),List.of(),competitive,QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,
                        GeometryCleanupConfig.disabled(),null,null,choice,observed?census:null)) {
                    assertEquals(expected.ids(),result.vertexIds());assertEquals(expected.arcs(),result.arcIds());assertEquals(expected.cost(),result.cost(),0);
                    assertEquals(expected.q(),result.q(),0);assertEquals(expected.c(),result.c(),0);assertEquals(expected.t(),result.t(),0);assertEquals(expected.k(),result.k(),0);
                    assertEquals(expected.consumed(),result.consumedRows().size());
                    for(var point:result.emitted()) {
                        var state=result.nativeStates().stream().filter(s->s.row()==point.row()&&s.nativeStateId()==point.nativeStateId()).findFirst().orElseThrow();
                        var profile=profiles.get(point.row()).source();
                        assertEquals(new RasterPoint(profile.anchorScreen().x+profile.normalScreen().x*state.offsetPx(),
                            profile.anchorScreen().y+profile.normalScreen().y*state.offsetPx()),point.raster());
                    }
                    if(choice==NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_PREFIX_DOMINANCE_V1) {
                        assertTrue(census.hasPrefixDominance());assertTrue(census.dominatedPrefixes()+census.retiredPrefixes()>0);
                        assertTrue(census.dominatedPrefixes()+census.retiredPrefixes()>beforeFinal[0],"The same law must eliminate versions in final exact replay too");
                        assertTrue(result.closure().finalMinimumLowerBound()>result.closure().certifiedUpperBound());
                    }
                }
            }
        }
    }
    @Test void explicitPublicEntryKeepsForeignInputRejectionAndTerminalCarrierRelease() throws Exception {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var a=new NativeAEmissionSolverTest.Fixture(3,budget);var foreign=new NativeAEmissionSolverTest.Fixture(3,budget);
                var census=NativeAExecutionCensus.open(a.input)) {
            var tracker=new CorridorAwareTracker();var before=budget.snapshot();long bytes=a.ledger.currentBytes(),peak=a.ledger.peakBytes();
            assertThrows(IllegalArgumentException.class,()->tracker.trackOptionalEmissionWithPrefixDominance(foreign.input,null,JunctionContext.empty(),List.of(),
                GeometryCleanupConfig.disabled(),QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,census));
            assertNull(census.failure());assertFalse(census.finished());assertEquals(before,budget.snapshot());assertEquals(bytes,a.ledger.currentBytes());assertEquals(peak,a.ledger.peakBytes());
            try(var result=tracker.trackOptionalEmissionWithPrefixDominance(a.input,null,JunctionContext.empty(),List.of(),GeometryCleanupConfig.disabled(),
                    QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,census)) {
                assertFalse(result.results().isEmpty());assertTrue(census.finished());assertNull(census.failure());assertTrue(census.hasPrefixDominance());
                assertEquals(NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_PREFIX_DOMINANCE_V1,result.results().get(0).executionChoice());
                var binding=NativeAExecutionCensus.class.getDeclaredField("input");binding.setAccessible(true);assertNull(binding.get(census));
            }
        }
    }
    static void enumerate(NativeARefusalReuseTest.NativeEngine e,Graph graph,Path p,List<Path> paths) {
        if(graph.vertices().get(p.key().cursorId()).terminal()){paths.add(p);return;}
        for(Arc a:e.arcs)if(a.fromId()==p.key().cursorId())enumerate(e,graph,extend(e,p,a),paths);
    }
    static int compare(Path a,Path b) {
        int order=Double.compare(a.cost(),b.cost());if(order!=0)return order;
        order=Integer.compare(a.consumed(),b.consumed());if(order!=0)return order;
        for(int index=0;index<Math.min(a.ids().size(),b.ids().size());index++){order=Integer.compare(a.ids().get(index),b.ids().get(index));if(order!=0)return order;}
        return Integer.compare(a.ids().size(),b.ids().size());
    }

    static Collision findCollision(int ySteps,int xSteps) throws Exception {
        try(var f=fixture(ySteps,xSteps);var e=new NativeARefusalReuseTest.NativeEngine(f,
                NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1,fixed())) {
            var graph=(Graph)NativeARefusalReuseTest.field(e.actual,"graph");
            int seed=one(e,0),join=one(e,2),cursor=one(e,3);
            var prefixes=new ArrayList<Path>();
            for(var vertex:graph.vertices())if(vertex.originalRow()==1) {
                Path p=new Path(new CanonicalKey(seed,-1,seed,0),List.of(seed),List.of(),0,0,0,0,0,0);
                p=extend(e,p,arc(e,seed,vertex.id()));p=extend(e,p,arc(e,vertex.id(),join));p=extend(e,p,arc(e,join,cursor));
                assertFalse(e.arcs.get(p.arcs().get(p.arcs().size()-1)).zeroPhysical());prefixes.add(p);
            }
            for(Path preferred:prefixes)for(Path cheap:prefixes) {
                if(!(preferred.cost()>cheap.cost())||preferred.ids().get(1)>=cheap.ids().get(1))continue;
                assertEquals(preferred.key(),cheap.key());assertEquals(preferred.consumed(),cheap.consumed());
                var suffixes=new ArrayList<Integer>();var costs=new ArrayList<Double>();
                for(Arc suffix:e.arcs)if(suffix.fromId()==cursor&&graph.vertices().get(suffix.toId()).terminal()) {
                    Path p=extend(e,preferred,suffix),q=extend(e,cheap,suffix);
                    if(Double.doubleToLongBits(p.cost())==Double.doubleToLongBits(q.cost())){suffixes.add(suffix.id());costs.add(p.cost());}
                    if(suffixes.size()==2)return new Collision(ySteps,xSteps,preferred.ids().get(1),cheap.ids().get(1),
                        preferred.cost(),cheap.cost(),List.copyOf(suffixes),List.copyOf(costs));
                }
            }
            return null;
        }
    }
    static NativeAEmissionSolverTest.Fixture fixture(int ySteps,int xSteps) {
        return fixture(ySteps,xSteps,CancellationProbe.NONE);
    }
    static NativeAEmissionSolverTest.Fixture fixture(int ySteps,int xSteps,CancellationProbe cancellation) {
        double y=steps(20,ySteps),x=steps(11,xSteps);
        return new NativeAEmissionSolverTest.Fixture(List.of(new RasterPoint(6,20),new RasterPoint(x,y),new RasterPoint(16,20),
            new RasterPoint(21,20),new RasterPoint(26,20)),(px,py)->(px<=21?1:.8)*Math.max(0,Math.min(1,(5-Math.abs(py-20))/4.)),5,
            new EmissionWorkBudget(TraceBudgets.defaults(),cancellation),0);
    }
    static double steps(double value,int count){for(int i=0;i<Math.abs(count);i++)value=count>0?Math.nextUp(value):Math.nextDown(value);return value;}
    static JunctionContext fixed(){return new JunctionContext(java.util.stream.IntStream.of(0,2,3)
        .mapToObj(row->new EndpointConstraint(row,row+1,true,false,0,0,0)).toList());}
    static int one(NativeARefusalReuseTest.NativeEngine e,int row) throws Exception {
        var matches=new ArrayList<Integer>();for(int id=0;id<e.vertices.size();id++)if(e.row(id)==row)matches.add(id);
        assertEquals(1,matches.size());return matches.get(0);
    }
    static Arc arc(NativeARefusalReuseTest.NativeEngine e,int from,int to){return e.arcs.stream()
        .filter(a->a.fromId()==from&&a.toId()==to&&!a.optional()).findFirst().orElseThrow();}
    static Path extend(NativeARefusalReuseTest.NativeEngine e,Path p,Arc arc) {
        var admitted=e.provider.certify(p.key(),arc);assertTrue(admitted.admitted());
        var ids=new ArrayList<>(p.ids());ids.add(arc.toId());var arcs=new ArrayList<>(p.arcs());arcs.add(arc.id());
        var key=new CanonicalKey(arc.toId(),arc.zeroPhysical()?p.key().effectivePositiveArcId():arc.id(),arc.toId(),0);
        return new Path(key,List.copyOf(ids),List.copyOf(arcs),p.cost()+admitted.q()+admitted.c()+admitted.t()+admitted.k(),
            p.q()+admitted.q(),p.c()+admitted.c(),p.t()+admitted.t(),p.k()+admitted.k(),p.consumed()+arc.consumedRows());
    }
}
