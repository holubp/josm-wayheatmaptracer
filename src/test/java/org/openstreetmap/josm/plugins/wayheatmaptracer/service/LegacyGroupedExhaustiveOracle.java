package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Tiny test-only FULL prefix enumeration: no Key map, merge, bound, witness, or production stage controller. */
public final class LegacyGroupedExhaustiveOracle {
    public interface EdgeAdmission { boolean allowed(LegacyEmissionDomain.State from,LegacyEmissionDomain.State to); }
    public interface AdmissionFactory { EdgeAdmission prepare(LegacyEmissionDomain domain) throws Exception; }
    private record Borrowed(SampledEmissionInput input,List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile> profiles) { }
    public record Point(MetricPoint position,EmissionMapping.VertexOwner owner,Integer stateId) { }
    public record Stage(int seed,int ordinal,EmissionResult.Costs prefix,EmissionResult.Costs terminal) { }
    public record Result(int seed,EmissionResult.Costs first,EmissionResult.Costs stitched,List<Point> firstPoints,
            List<Point> points,List<Stage> stages,List<Integer> anchors,int terminalPaths) { }
    final Borrowed f;final LegacyEmissionKernel kernel=new LegacyEmissionKernel();
    final NativeConnectorEvaluator.QueryPolicy query;final NativeConnectorEvaluator.ExecutionPolicy execution;final EdgeAdmission admission;
    final EmissionWorkBudget budget;final LegacyEmissionDomain domain;final AttemptMemoryLedger.Owner owner;
    final double pitch;int terminalPaths;
    LegacyGroupedExhaustiveOracle(LegacyCompetitiveProofTest.Fixture f,List<Double> seeds,AttemptMemoryLedger.Owner owner) throws Exception {
        this(new Borrowed(f.input(),f.profiles()),seeds,owner,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,d->(a,b)->true);
    }
    private LegacyGroupedExhaustiveOracle(Borrowed f,List<Double> seeds,AttemptMemoryLedger.Owner owner,
            NativeConnectorEvaluator.QueryPolicy query,NativeConnectorEvaluator.ExecutionPolicy execution,AdmissionFactory factory)throws Exception{
        this.f=f;this.owner=owner;this.query=query;this.execution=execution;budget=f.input().workBudget();
        domain=new LegacyEmissionDomain(f.input().legacyBinding().orElseThrow(),f.profiles(),seeds,budget,owner);
        pitch=f.input().context().nativePitchMeters().orElseThrow();admission=factory.prepare(domain);
    }
    public static Result enumerate(SampledEmissionInput input,
            List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile> profiles,
            List<Double> seeds,List<LegacyEmissionKernel.AnchorProposal> proposals,NativeConnectorEvaluator.QueryPolicy query,
            NativeConnectorEvaluator.ExecutionPolicy execution,AdmissionFactory factory)throws Exception{
        try(var owner=input.openExecutionOwner("managed-graph-exhaustive-oracle")){
            return new LegacyGroupedExhaustiveOracle(new Borrowed(input,profiles),seeds,owner,query,execution,factory).run(proposals);
        }
    }
    static Result enumerate(LegacyCompetitiveProofTest.Fixture f,List<Double> seeds,List<LegacyEmissionKernel.AnchorProposal> proposals) throws Exception {
        try(var owner=f.input().openExecutionOwner("public-exhaustive-oracle")) {
            return new LegacyGroupedExhaustiveOracle(f,seeds,owner).run(proposals);
        }
    }
    static void proveActualReplacement(LegacyCompetitiveProofTest.Fixture f) throws Exception {
        try(var owner=f.input().openExecutionOwner("actual-front-replacement-oracle")) {
            var oracle=new LegacyGroupedExhaustiveOracle(f,List.of(0.0),owner);var domain=oracle.domain;var budget=oracle.budget;
            Object inventory=oracle.call("inventory",domain,budget,owner,null);
            try(var evaluator=(NativeConnectorEvaluator)oracle.call("evaluator",f.input(),domain,inventory,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,null)) {
                var terminals=new ArrayList<Object>();var seed=domain.seeds().get(0);
                for(var state:domain.observedAt(0))oracle.walk(oracle.call("initialProofNode",f.input(),domain,seed,state,owner,budget),null,null,0,inventory,evaluator,terminals);
                Object old=null,replacement=null;
                for(Object a:terminals)for(Object b:terminals)if(a!=b&&field(a,"key").equals(field(b,"key"))
                        &&(int)field(a,"consumed")>(int)field(b,"consumed")&&(boolean)oracle.call("dominates",b,a,budget,owner)) {old=a;replacement=b;break;}
                assertNotNull(old,"A real full-Key convergence must supply a dominated native prefix");assertNotNull(replacement);
                var map=new LinkedHashMap<>();oracle.call("insertExact",map,old,budget,owner,null);oracle.call("insertExact",map,replacement,budget,owner,null);
                assertEquals(1,map.size());assertEquals(1,field(map.values().iterator().next(),"size"));
                assertEquals(true,field(old,"released"));assertEquals(false,field(replacement,"released"));assertEquals(true,field(replacement,"inLayer"));
            }
        }
    }
    Result run(List<LegacyEmissionKernel.AnchorProposal> proposals) throws Exception {
        var evaluators=new ArrayList<NativeConnectorEvaluator>();
        try {
            Object best=null,bestInventory=null;NativeConnectorEvaluator bestEvaluator=null;int chosen=-1;
            var stages=new ArrayList<Stage>();
            for(var seed:domain.seeds()) {
                Object inventory=call("inventory",domain,budget,owner,null);
                var evaluator=(NativeConnectorEvaluator)call("evaluator",f.input(),domain,inventory,query,execution,null);
                evaluators.add(evaluator);var terminals=new ArrayList<Object>();
                var starts=domain.unsupported(0)?List.of(carry(inventory,evaluator,0,seed.id(),seed.offsetRasterPixels())):domain.observedAt(0);
                for(var state:starts)walk(call("initialProofNode",f.input(),domain,seed,state,owner,budget),null,null,0,inventory,evaluator,terminals);
                Object candidate=winner(terminals);stages.add(new Stage(seed.id(),0,null,costs(candidate)));
                if(candidate!=null&&(best==null||compare(candidate,best)<0)){best=candidate;bestInventory=inventory;bestEvaluator=evaluator;chosen=seed.id();}
            }
            assertNotNull(best);var first=costs(best);var firstPoints=points(best);var eligible=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
            for(var proposal:proposals)for(Object node:chain(best)) {
                var state=state(node);if(state.originalRow()==proposal.originalRow()&&state.peakOwner()==proposal.peakOwner()){eligible.add(proposal);break;}
            }
            for(int i=0;i+1<eligible.size();i++) {
                var oldChain=chain(best);Object prefix=null;LegacyEmissionDomain.State right=null;
                for(Object node:oldChain) {
                    if(state(node).originalRow()==eligible.get(i).originalRow())prefix=node;
                    if(state(node).originalRow()==eligible.get(i+1).originalRow())right=state(node);
                }
                assertNotNull(prefix);assertNotNull(right);var terminals=new ArrayList<Object>();
                walk(prefix,prefix,right,i+1,bestInventory,bestEvaluator,terminals);Object next=winner(terminals);assertNotNull(next);
                stages.add(new Stage(chosen,i+1,costs(prefix),costs(next)));
                for(Object node:oldChain)if(state(node).originalRow()>right.originalRow()) {
                    if(!admission.allowed(state(next),state(node)))throw new AssertionError("Independent admission refuses stitched ordinary edge");
                    next=call("advance",f.input(),domain,bestInventory,next,state(node),bestEvaluator,owner,CancellationProbe.NONE,i+1,pitch);
                }
                assertNotNull(next);best=next;
            }
            return new Result(chosen,first,costs(best),firstPoints,points(best),List.copyOf(stages),eligible.stream().map(LegacyEmissionKernel.AnchorProposal::originalRow).toList(),terminalPaths);
        } finally {for(var evaluator:evaluators)evaluator.close();}
    }
    void walk(Object from,Object prefix,LegacyEmissionDomain.State right,int stage,Object inventory,NativeConnectorEvaluator evaluator,List<Object> terminals) throws Exception {
        int row=state(from).originalRow(),end=right==null?domain.rowCount()-1:right.originalRow();
        if(row==end){terminals.add(from);terminalPaths++;return;}
        for(int toRow=row+1;toRow<=end&&toRow<=row+17;toRow++) {
            if(toRow>row+1&&((double)call("sourceSpanUpper",domain,f.input().context(),row,toRow)>20||(boolean)call("crossesMandatory",domain,row,toRow)))continue;
            List<LegacyEmissionDomain.State> choices;
            if(right!=null&&toRow==end)choices=List.of(right);
            else if(domain.unsupported(toRow))choices=List.of(prefix==null?carry(inventory,evaluator,toRow,state(from).offsetOriginId(),state(from).offsetRasterPixels())
                :(LegacyEmissionDomain.State)call("intervalTarget",domain,inventory,evaluator,toRow,state(prefix),right,budget,owner));
            else choices=domain.observedAt(toRow);
            for(var to:choices) {
                if(toRow==row+1&&!admission.allowed(state(from),to))continue;
                Object next=call("advance",f.input(),domain,inventory,from,to,evaluator,owner,CancellationProbe.NONE,stage,pitch);
                if(next!=null)walk(next,prefix,right,stage,inventory,evaluator,terminals);
            }
        }
    }
    LegacyEmissionDomain.State carry(Object inventory,NativeConnectorEvaluator evaluator,int row,int origin,double offset) throws Exception {
        return (LegacyEmissionDomain.State)call("reachedCarry",domain,inventory,evaluator,row,origin,offset,budget,owner);
    }
    static Object winner(List<Object> terminals) throws Exception {Object best=null;for(Object n:terminals)if(best==null||compare(n,best)<0)best=n;return best;}
    static int compare(Object a,Object b) throws Exception {
        int c=Double.compare(costs(a).total(),costs(b).total());if(c!=0)return c;
        c=Integer.compare((int)field(a,"consumed"),(int)field(b,"consumed"));if(c!=0)return c;
        var x=chain(a);var y=chain(b);
        for(int i=0;i<Math.min(x.size(),y.size());i++) {
            var s=state(x.get(i));var t=state(y.get(i));
            c=Integer.compare(s.originalRow(),t.originalRow());if(c!=0)return c;
            c=Integer.compare(s.nativeStateId(),t.nativeStateId());if(c!=0)return c;
            c=Integer.compare(s.offsetOriginId(),t.offsetOriginId());if(c!=0)return c;
        }
        return Integer.compare(x.size(),y.size());
    }
    static EmissionResult.Costs costs(Object n) throws Exception {
        if(n==null)return null;return new EmissionResult.Costs((double)field(n,"q"),(double)field(n,"c"),(double)field(n,"t"),(double)field(n,"k"));
    }
    static List<Object> chain(Object terminal) throws Exception {var r=new ArrayList<Object>();for(Object n=terminal;n!=null;n=field(n,"previous"))r.add(n);Collections.reverse(r);return r;}
    static List<Point> points(Object terminal) throws Exception {
        var r=new ArrayList<Point>();for(Object n:chain(terminal))for(Object p:(List<?>)field(n,"addedPoints")) {
            var s=(LegacyEmissionDomain.State)access(p,"state");r.add(new Point((MetricPoint)access(p,"position"),(EmissionMapping.VertexOwner)access(p,"owner"),s==null?null:s.nativeStateId()));
        }return List.copyOf(r);
    }
    static LegacyEmissionDomain.State state(Object node) throws Exception {return (LegacyEmissionDomain.State)access(field(node,"key"),"last");}
    Object call(String name,Object...args) throws Exception {
        for(var m:LegacyEmissionKernel.class.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length) {
            m.setAccessible(true);try{return m.invoke(kernel,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
        }throw new NoSuchMethodException(name);
    }
    static Object access(Object object,String name) throws Exception {var m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(object);}
    static Object field(Object object,String name) throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
}
