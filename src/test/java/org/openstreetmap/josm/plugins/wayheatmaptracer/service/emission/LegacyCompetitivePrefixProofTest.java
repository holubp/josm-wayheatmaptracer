package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Canonical IEEE/front proof, deliberately not a claim of native reachability of its Q/C tuples. */
class LegacyCompetitivePrefixProofTest {
    static Class<?> type(String name){return Arrays.stream(LegacyEmissionKernel.class.getDeclaredClasses()).filter(t->t.getSimpleName().equals(name)).findFirst().orElseThrow();}
    static Object construct(String name,Object... args) throws Exception {
        var c=type(name).getDeclaredConstructors()[0];c.setAccessible(true);return c.newInstance(args);
    }
    static Object field(Object object,String name) throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static Method method(String name,Class<?>... params) throws Exception {var m=LegacyEmissionKernel.class.getDeclaredMethod(name,params);m.setAccessible(true);return m;}
    static EmissionWorkBudget budget(){return new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);}
    static Object key(int row,int id,Object cursor,int stage) throws Exception {
        var state=new LegacyEmissionDomain.State(row,id,LegacyEmissionDomain.Kind.BASE_CARRY,0,0,null,
            new NativeConnectorEvaluator.RowState(row,id,0,NativeConnectorEvaluator.SourceClass.GAP,null));
        return construct("Key",state,construct("ScalarMemory",row-2,0.0,row,0.0,0.0,0),null,
            cursor==null?null:construct("ReferenceIdentity",cursor),construct("PhaseContext",stage,null));
    }
    static Object node(AttemptMemoryLedger.Owner owner,EmissionWorkBudget budget,Object previous,Object key,
            double q,double c,double t,double k,int consumed,int seed) throws Exception {
        return method("allocateNode",AttemptMemoryLedger.Owner.class,EmissionWorkBudget.class,type("Node"),type("Key"),List.class,List.class,
            double.class,double.class,double.class,double.class,int.class,int.class)
            .invoke(null,owner,budget,previous,key,List.of(),List.of(),q,c,t,k,consumed,seed);
    }
    static Object prefix(AttemptMemoryLedger.Owner owner,EmissionWorkBudget budget,int firstId,Object common,
            double q,double c,double t,double k,int consumed) throws Exception {
        var root=node(owner,budget,null,key(0,firstId,null,0),0,0,0,0,0,0);
        var middle=node(owner,budget,root,key(2,0,new Object(),0),0,0,0,0,1,0);
        return node(owner,budget,middle,common,q,c,t,k,consumed,0);
    }
    static boolean dominates(Object a,Object b,EmissionWorkBudget budget,AttemptMemoryLedger.Owner owner) throws Exception {
        return (boolean)method("dominates",type("Node"),type("Node"),EmissionWorkBudget.class,AttemptMemoryLedger.Owner.class).invoke(null,a,b,budget,owner);
    }

    @Test void compatibilityMergeLosesCanonicalWinnerButExactFullKeyFrontKeepsIt() throws Exception {
        var ledger=AttemptMemoryLedger.production();var budget=budget();
        try(var owner=ledger.rootOwner()) {
            var common=key(4,0,new Object(),0);
            var p=prefix(owner,budget,0,common,1,0,0,0,2);
            var r=prefix(owner,budget,1,common,1-Math.scalb(1.0,-53),Math.scalb(1.0,-53),0,0,2);
            assertSame(field(p,"key"),field(r,"key"),"The entire future Key, not just the row, converges");
            var old=new LinkedHashMap<>();
            var insert=method("insert",Map.class,type("Node"),EmissionWorkBudget.class,AttemptMemoryLedger.Owner.class);
            insert.invoke(null,old,p,budget,owner);insert.invoke(null,old,r,budget,owner);
            assertSame(p,old.values().iterator().next(),"Old total/tie merge retains P");
            double inc=Math.scalb(1.0,-52);
            double pFinal=(1+inc)+0+0+0,rFinal=((1-Math.scalb(1.0,-53))+inc)+Math.scalb(1.0,-53)+0+0;
            assertEquals(1+inc,pFinal,0);assertEquals(1,rFinal,0);assertTrue(rFinal<pFinal);
            var a=prefix(owner,budget,0,common,1,0,0,0,2);
            var b=prefix(owner,budget,1,common,1-Math.scalb(1.0,-53),Math.scalb(1.0,-53),0,0,2);
            var fronts=new LinkedHashMap<>();
            var exact=method("insertExact",Map.class,type("Node"),EmissionWorkBudget.class,AttemptMemoryLedger.Owner.class,LegacyEmissionProgress.class);
            exact.invoke(null,fronts,a,budget,owner,null);exact.invoke(null,fronts,b,budget,owner,null);
            var front=fronts.values().iterator().next();assertEquals(2,field(front,"size"));
            var finals=new ArrayList<Double>();
            for(var label=field(front,"first");label!=null;label=field(label,"next")) {
                var x=field(label,"node");finals.add(((double)field(x,"q")+inc)+(double)field(x,"c")+(double)field(x,"t")+(double)field(x,"k"));
            }
            assertEquals(rFinal,finals.stream().min(Double::compare).orElseThrow(),0);
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void componentAndTieDominanceIsConservativeForRoundingNonfiniteAndSignedZero() throws Exception {
        var ledger=AttemptMemoryLedger.production();var budget=budget();
        try(var owner=ledger.rootOwner()) {
            var common=key(4,0,new Object(),0);
            for(int axis=0;axis<4;axis++) {
                double[] lower=new double[4],upper=new double[4];lower[axis]=.5;upper[axis]=1;
                var left=prefix(owner,budget,0,common,lower[0],lower[1],lower[2],lower[3],2);
                var right=prefix(owner,budget,1,common,upper[0],upper[1],upper[2],upper[3],2);
                assertTrue(dominates(left,right,budget,owner),"Finite component dominance axis "+axis);
                assertFalse(dominates(right,left,budget,owner));
                lower[axis]=-0.0;
                var signed=prefix(owner,budget,0,common,lower[0],lower[1],lower[2],lower[3],2);
                assertFalse(dominates(signed,right,budget,owner),"Signed-zero ambiguity axis "+axis);
            }
            var tieWorse=prefix(owner,budget,1,common,0,0,0,0,2);
            var tieBetter=prefix(owner,budget,0,common,1,0,0,0,2);
            assertFalse(dominates(tieWorse,tieBetter,budget,owner),"Strict component improvement may vanish after rounding");
            assertEquals(0+1e16,1+1e16,0);
            var good=prefix(owner,budget,0,common,0,0,0,0,2);
            var bad=prefix(owner,budget,1,common,1,0,0,0,2);
            assertTrue(dominates(good,bad,budget,owner));
            var moreConsumed=prefix(owner,budget,0,common,0,0,0,0,3);
            assertFalse(dominates(moreConsumed,bad,budget,owner));
            for(double invalid:new double[]{-0.0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
                var ambiguous=prefix(owner,budget,0,common,invalid,0,0,0,2);
                assertFalse(dominates(ambiguous,bad,budget,owner));assertFalse(dominates(good,ambiguous,budget,owner));
            }
            var tradeoff=prefix(owner,budget,0,common,.9,0,0,0,2);
            var other=prefix(owner,budget,1,common,.8,.3,0,0,2);
            assertFalse(dominates(tradeoff,other,budget,owner));
            var foreignCursor=prefix(owner,budget,1,key(4,0,new Object(),0),1,0,0,0,2);
            var otherPhase=prefix(owner,budget,1,key(4,0,new Object(),1),1,0,0,0,2);
            assertFalse(dominates(good,foreignCursor,budget,owner));assertFalse(dominates(good,otherPhase,budget,owner));
            var physical=construct("PhysicalLeg",new MetricPoint(0,0),new MetricPoint(1,0),new Object(),new Object(),construct("ReferenceIdentity",new Object()));
            var physicalKey=construct("Key",field(common,"last"),field(common,"scalar"),physical,field(common,"incomingCursor"),field(common,"phase"));
            var differentDirection=prefix(owner,budget,1,physicalKey,1,0,0,0,2);
            assertFalse(dominates(good,differentDirection,budget,owner));
            var scalarKey=construct("Key",field(common,"last"),construct("ScalarMemory",2,0.0,4,0.0,Math.nextUp(0.0),0),null,field(common,"incomingCursor"),field(common,"phase"));
            assertFalse(dominates(good,prefix(owner,budget,1,scalarKey,1,0,0,0,2),budget,owner));
            var foreignSeed=node(owner,budget,null,common,1,0,0,0,2,1);
            assertFalse(dominates(good,foreignSeed,budget,owner));
            assertEquals(Double.POSITIVE_INFINITY,(double)method("certifiedWitnessUpper",type("Node")).invoke(null,good),
                "Finite prefix arithmetic without a legal positive terminal leg is not a witness");
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void strictFiniteUpperNeverPrunesEqualityUnknownOrNoWitness() throws Exception {
        var cutoff=method("strictCutoff",double.class,double.class);
        assertFalse((boolean)cutoff.invoke(null,1.0,1.0));assertTrue((boolean)cutoff.invoke(null,Math.nextUp(1.0),1.0));
        for(double unknown:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1}) {
            assertFalse((boolean)cutoff.invoke(null,unknown,1.0));assertFalse((boolean)cutoff.invoke(null,2.0,unknown));
        }
        assertFalse((boolean)cutoff.invoke(null,0.0,0.0));
    }
}
