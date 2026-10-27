package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Manual analytic controls and genuine captured joins are explicitly separate. */
class V022BSharedSolverParityTest {
    private static final TraceBudgets RAW1=new TraceBudgets(8,10000,128000000,1,1);

    //Declared in fixtures.md before execution. These are MANUAL finite graphs,
    //not captured-source capabilities or substitutes for the integration test.
    private static final double[][][] FEATURE={{{2,.5},{.25,0}},{{1,.25},{.5,0}}};
    private static final EvidenceModelParameters SHAPE=new EvidenceModelParameters("task4-analytic",1.25,1,.75,.5,.25,.75,1.5,0);

    @Test void independentRetainedMapAndExhaustivePosteriorForAllExactPolicies(){
        var graph=analytic();var all=enumerate(graph,SHAPE,FEATURE);
        assertEquals(8,all.size());
        assertTrue(unary(graph,SHAPE,0,0)<0);assertTrue(pair(graph,SHAPE,0,0,0)>0);
        assertTrue(triple(graph,SHAPE,2,0,1,0)>0);
        for(int cap:new int[]{1,2,32}){
            var expected=retained(graph,SHAPE,FEATURE,cap);
            var exact=manualSolve(graph,SHAPE,cap,FEATURE,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            var eager=manualSolve(graph,SHAPE,cap,FEATURE,ProbabilisticExecutionPolicy.Mode.EAGER_V1);
            var raw=manualRaw(graph,SHAPE,cap,FEATURE);
            assertResult(expected,all,graph,SHAPE,cap,raw.result());
            assertResult(expected,all,graph,SHAPE,cap,exact);
            assertResult(expected,all,graph,SHAPE,cap,eager);
            assertEquals(cap==1?ProbabilisticExecutionPolicy.CROSS_STREAM_VERSION:ProbabilisticExecutionPolicy.CURRENT_VERSION,
                    raw.result().workAccounting().orElseThrow().executionPolicy());
            assertEquals(ProbabilisticExecutionPolicy.CURRENT_VERSION,exact.workAccounting().orElseThrow().executionPolicy());
            assertEquals(ProbabilisticExecutionPolicy.VERSION,eager.workAccounting().orElseThrow().executionPolicy());
            if(cap!=1)assertEquals(0,raw.boundWork().boundQueries());
            assertEquals(exact.completion(),raw.result().completion());
        }
        //Mutation controls: removing feature or retaining only a singleton
        //changes a real oracle quantity; no success-only table bypass check.
        assertNotEquals(partition(all,SHAPE.temperature()),partition(enumerate(graph,SHAPE,new double[2][2][2]),SHAPE.temperature()));
        assertNotEquals(all.size(),1);
    }

    @Test void roundedPrefixRetentionIsNotExhaustiveFinalEnergyLexicalSorting(){
        var graph=List.of(flat(0,new double[]{.5,0}),flat(1,new double[]{0}),flat(2,new double[]{0}),flat(3,new double[]{2e16}));
        var p=new EvidenceModelParameters("task4-rounded",1,0,Double.MIN_VALUE,0,0,1,1,0);
        double[][][] f={{{.125},{.125}},{{.25}},{{.5}}};
        var expected=retained(graph,p,f,1);var all=enumerate(graph,p,f);all.sort(ORDER);
        assertArrayEquals(new int[]{1,0,0,0},expected.get(0).states());
        assertArrayEquals(new int[]{0,0,0,0},all.get(0).states(),"independent final sorting loses the earlier retained-prefix decision");
        assertPaths(expected,manualRaw(graph,p,1,f).result().rawPaths());
        assertPaths(expected,manualSolve(graph,p,1,f,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2).rawPaths());
    }

    @Test void additionalPairPrecedesQuarterMagnitudeGuardAndUnsafeUpperFallsBack(){
        var graph=List.of(flat(0,new double[]{0,0}),flat(1,new double[]{0,0}),flat(2,new double[]{0,0}));
        double[][][] f={{{0,0},{0,0}},{{5.5e307,5.5e307},{5.5e307,5.5e307}}};
        var huge=new EvidenceModelParameters("task4-quarter",1,0,1,0,0,1,1e308,0);
        var guarded=manualRaw(graph,huge,1,f);var nativeExact=manualSolve(graph,huge,1,f,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
        assertTrue(guarded.boundWork().magnitudeTests()>0);assertEquals(0,guarded.boundWork().partialAdditions());
        assertEquals(0,guarded.boundWork().skippedStreams());assertPaths(retained(graph,huge,f,1),guarded.result().rawPaths());
        bits(nativeExact.logPartition(),guarded.result().logPartition());
        var unsafe=new EvidenceModelParameters("task4-unsafe",1,0,1,0,0,Double.MIN_VALUE,1,0);
        var fallback=manualRaw(graph,unsafe,1,new double[2][2][2]);
        assertTrue(fallback.boundWork().boundQueries()>0);assertEquals(0,fallback.boundWork().skippedStreams());
        assertPaths(retained(graph,unsafe,new double[2][2][2],1),fallback.result().rawPaths());
    }

    @Test void nonfiniteNegativeAndSignedZeroPotentialAndActualFailureBudgets(){
        var graph=analytic();
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-.25}){
            var result=rawWith(graph,SHAPE,RAW1,(stage,l,r)->bad,CancellationProbe.NONE);
            assertNoPrefix(result.result(),ProbabilisticInferenceResult.Status.NUMERIC_FAILURE);
        }
        var negativeZero=rawWith(graph,SHAPE,RAW1,(stage,l,r)->-0.0,CancellationProbe.NONE);
        var positiveZero=rawWith(graph,SHAPE,RAW1,(stage,l,r)->0.0,CancellationProbe.NONE);
        assertArrayEquals(positiveZero.result().mapPath().orElseThrow().stateIndices(),negativeZero.result().mapPath().orElseThrow().stateIndices());
        bits(positiveZero.result().logPartition(),negativeZero.result().logPartition());
        var limited=rawWith(graph,SHAPE,new TraceBudgets(8,1,128000000,1,1),(s,l,r)->FEATURE[s][l][r],CancellationProbe.NONE);
        assertNoPrefix(limited.result(),ProbabilisticInferenceResult.Status.RESOURCE_LIMIT);
        assertEquals(0,limited.result().workAccounting().orElseThrow().refusedScoreAttempts(),"pair-count admission refuses before a scored transition");
        var scoreLimited=rawWith(graph,SHAPE,new TraceBudgets(8,10000,1,1,1),(s,l,r)->FEATURE[s][l][r],CancellationProbe.NONE);
        assertNoPrefix(scoreLimited.result(),ProbabilisticInferenceResult.Status.RESOURCE_LIMIT);
        assertEquals(1,scoreLimited.result().workAccounting().orElseThrow().refusedScoreAttempts());
        var ledger=new AttemptMemoryLedger(128000000);
        try(var owner=ledger.rootOwner();var caller=owner.reserve(7)){
            int[] calls={0};
            assertThrows(CancellationException.class,()->new ProbabilisticInference().solveCrossStreamRaw1(graph,SHAPE,RAW1,null,
                    ()->++calls[0]==3,owner,(s,l,r)->FEATURE[s][l][r]));
            assertEquals(7,owner.currentBytes(),"cancelled solver returns no retained prefix or child allocation");
        }
        assertEquals(0,ledger.currentBytes());
    }

    private record Path(int[] states,double energy,double measure){}
    private static final Comparator<Path> ORDER=(a,b)->{int c=Double.compare(a.energy,b.energy);return c!=0?c:Arrays.compare(a.states,b.states);};
    private static List<InferenceProfile> analytic(){
        double[] x={0,2,5},y={0,1.5,3.5};double[][] widths={{.5,1.5},{1.25,.75},{.625,1.375}},u={{-.5,.25},{.125,-.25},{-.125,.5}};
        var rows=new ArrayList<InferenceProfile>();
        for(int i=0;i<3;i++)rows.add(new InferenceProfile(x[i],new MetricPoint(x[i],y[i]),new MetricPoint(0,1),
                List.of(new LateralStateCell(-.25,widths[i][0],false,true,"same"),new LateralStateCell(.5,widths[i][1],false,true,"same")),
                u[i],List.of(0.0),.8,ObservationOwnership.DIRECT_TWO_SIDED,false).withStructuralGuideCosts(new double[]{.125,.25}));
        return List.copyOf(rows);
    }
    private static InferenceProfile flat(double x,double[] costs){var cells=new ArrayList<LateralStateCell>();
        for(int i=0;i<costs.length;i++)cells.add(new LateralStateCell(0,1,false,true,"same"));
        return new InferenceProfile(x,new MetricPoint(x,0),new MetricPoint(0,1),cells,costs,List.of(),0,ObservationOwnership.DIRECT_TWO_SIDED,false);}
    //Independent mathematical factors; no production cost, comparator, heap,
    //cached heading or marginal helper is used by either oracle.
    private static double unary(List<InferenceProfile> g,EvidenceModelParameters p,int i,int s){double q=i==0?.5*(g.get(1).chainageMeters()-g.get(0).chainageMeters()):i==g.size()-1?.5*(g.get(i).chainageMeters()-g.get(i-1).chainageMeters()):.5*(g.get(i+1).chainageMeters()-g.get(i-1).chainageMeters());return q*(p.dataWeight()*g.get(i).unaryCost(s)+p.guideWeight()*g.get(i).structuralGuideCost(s));}
    private static MetricPoint point(InferenceProfile p,int s){double u=p.cells().get(s).offsetMeters();return u==0?new MetricPoint(p.anchor().xMeters(),p.anchor().yMeters()):new MetricPoint(p.anchor().xMeters()+p.normalUnit().xMeters()*u,p.anchor().yMeters()+p.normalUnit().yMeters()*u);}
    private static double heading(MetricPoint a,MetricPoint b){return StrictMath.atan2(b.yMeters()-a.yMeters(),b.xMeters()-a.xMeters());}
    private static double pair(List<InferenceProfile> g,EvidenceModelParameters p,int i,int a,int b){if(p.orientationWeight()==0)return 0;double h=heading(point(g.get(i),a),point(g.get(i+1),b));assertTrue(h>0&&h<Math.PI/2);double sine=StrictMath.sin(h),m=.8*(sine*sine);return p.orientationWeight()*(g.get(i+1).chainageMeters()-g.get(i).chainageMeters())*.5*(m+m);}
    private static double triple(List<InferenceProfile> g,EvidenceModelParameters p,int i,int a,int b,int c){double d=heading(point(g.get(i-1),b),point(g.get(i),c))-heading(point(g.get(i-2),a),point(g.get(i-1),b));double z=StrictMath.atan2(StrictMath.sin(d),StrictMath.cos(d))/p.turnScaleRadians(),v=Math.abs(z);return p.turnWeight()*(v<=1?.5*z*z:v-.5)/Math.max(1e-6,.5*(g.get(i).chainageMeters()-g.get(i-2).chainageMeters()));}
    private static double measure(InferenceProfile p,int s){var c=p.cells().get(s);return c.exactAnchor()?0:StrictMath.log(c.quadratureWidthMeters());}
    private static List<Path> retained(List<InferenceProfile> g,EvidenceModelParameters p,double[][][] f,int cap){
        var paths=new ArrayList<Path>();for(int a=0;a<g.get(0).cells().size();a++)for(int b=0;b<g.get(1).cells().size();b++)paths.add(new Path(new int[]{a,b},unary(g,p,0,a)+unary(g,p,1,b)+(pair(g,p,0,a,b)+f[0][a][b]),measure(g.get(0),a)+measure(g.get(1),b)));
        for(int i=2;i<g.size();i++){var next=new ArrayList<Path>();for(int b=0;b<g.get(i-1).cells().size();b++)for(int c=0;c<g.get(i).cells().size();c++){var candidates=new ArrayList<Path>();for(var before:paths)if(before.states[i-1]==b){int a=before.states[i-2];int[] states=Arrays.copyOf(before.states,i+1);states[i]=c;double step=(unary(g,p,i,c)+(pair(g,p,i-1,b,c)+f[i-1][b][c]))+triple(g,p,i,a,b,c);candidates.add(new Path(states,before.energy+step,before.measure+measure(g.get(i),c)));}candidates.sort(ORDER);next.addAll(candidates.subList(0,Math.min(cap,candidates.size())));}paths=next;}
        paths.sort(ORDER);return List.copyOf(paths.subList(0,Math.min(cap,paths.size())));
    }
    private static List<Path> enumerate(List<InferenceProfile> g,EvidenceModelParameters p,double[][][] f){var out=new ArrayList<Path>();enumerate(g,p,f,new int[g.size()],0,out);return out;}
    private static void enumerate(List<InferenceProfile> g,EvidenceModelParameters p,double[][][] f,int[] states,int i,List<Path> out){if(i<g.size()){for(int s=0;s<g.get(i).cells().size();s++){states[i]=s;enumerate(g,p,f,states,i+1,out);}return;}double e=0,m=0;for(int r=0;r<g.size();r++){e+=unary(g,p,r,states[r]);m+=measure(g.get(r),states[r]);}for(int r=0;r<g.size()-1;r++)e+=pair(g,p,r,states[r],states[r+1])+f[r][states[r]][states[r+1]];for(int r=2;r<g.size();r++)e+=triple(g,p,r,states[r-2],states[r-1],states[r]);out.add(new Path(states.clone(),e,m));}
    private static TraceBudgets budget(int cap){return new TraceBudgets(8,10000,128000000,cap,Math.min(8,cap));}
    private static CrossStreamInferenceResult manualRaw(List<InferenceProfile> g,EvidenceModelParameters p,int cap,double[][][] f){return rawWith(g,p,budget(cap),(s,l,r)->f[s][l][r],CancellationProbe.NONE);}
    private static CrossStreamInferenceResult rawWith(List<InferenceProfile> g,EvidenceModelParameters p,TraceBudgets b,AdjacentStatePotential f,CancellationProbe probe){var ledger=new AttemptMemoryLedger(128000000);try(var o=ledger.rootOwner()){return new ProbabilisticInference().solveCrossStreamRaw1(g,p,b,null,probe,o,f);}finally{assertEquals(0,ledger.currentBytes());}}
    private static ProbabilisticInferenceResult manualSolve(List<InferenceProfile> g,EvidenceModelParameters p,int cap,double[][][] f,ProbabilisticExecutionPolicy.Mode mode){var ledger=new AttemptMemoryLedger(128000000);try(var o=ledger.rootOwner()){return new ProbabilisticInference().solve(g,p,budget(cap),null,CancellationProbe.NONE,o,mode,(s,l,r)->f[s][l][r]);}finally{assertEquals(0,ledger.currentBytes());}}
    private static void bits(double a,double b){assertEquals(Double.doubleToRawLongBits(a),Double.doubleToRawLongBits(b));}
    private static void assertPaths(List<Path> expected,List<ProbabilisticPath> actual){assertEquals(expected.size(),actual.size());for(int i=0;i<expected.size();i++){assertArrayEquals(expected.get(i).states,actual.get(i).stateIndices());bits(expected.get(i).energy,actual.get(i).energy());bits(expected.get(i).measure,actual.get(i).logBaseMeasure());}}
    private static double partition(List<Path> all,double t){double max=Double.NEGATIVE_INFINITY;for(var p:all)max=Math.max(max,-p.energy/t+p.measure);double sum=0;for(var p:all)sum+=StrictMath.exp(-p.energy/t+p.measure-max);return max+StrictMath.log(sum);}
    private static void assertResult(List<Path> expected,List<Path> all,List<InferenceProfile> g,EvidenceModelParameters p,int cap,ProbabilisticInferenceResult actual){
        assertTrue(actual.mapPath().isPresent());assertTrue(actual.posteriorUsable());assertPaths(expected,actual.rawPaths());
        var map=actual.mapPath().orElseThrow();assertArrayEquals(expected.get(0).states,map.stateIndices());for(int i=0;i<g.size();i++)assertEquals(point(g.get(i),expected.get(0).states[i]),map.points().get(i));
        double z=partition(all,p.temperature());assertEquals(z,actual.logPartition(),2e-12);
        for(int i=0;i<g.size();i++){double[] mass=new double[g.get(i).cells().size()];for(var path:all)mass[path.states[i]]+=StrictMath.exp(-path.energy/p.temperature()+path.measure-z);assertArrayEquals(mass,actual.positionMarginals().get(i),2e-12);assertArrayEquals(mass,actual.forwardPositionMarginals().get(i),2e-12);assertArrayEquals(mass,actual.backwardPositionMarginals().get(i),2e-12);assertCredible(g.get(i),mass,actual.credibleSets().get(i));}
        var c=actual.completion().orElseThrow();assertEquals(cap,c.effectiveRawLimit());assertEquals(Math.min(8,cap),c.effectiveDistinctLimit());assertEquals(Math.min(cap+1,all.size()),c.completePathsAtSaturation());assertEquals(all.size()>cap,c.terminalCountSaturated());assertEquals(all.size()>cap,c.rawEnumerationCapped());assertEquals(actual.distinctPaths().size()==Math.min(8,cap),c.requestedDiversityReached());
    }
    private static void assertCredible(InferenceProfile row,double[] mass,CredibleLateralSet actual){var order=new ArrayList<Integer>();for(int i=0;i<mass.length;i++)order.add(i);order.sort((a,b)->{int c=Double.compare(mass[b],mass[a]);return c==0?Integer.compare(a,b):c;});boolean[] chosen=new boolean[mass.length];double sum=0;for(int s:order){chosen[s]=true;sum+=mass[s];if(sum>=.95)break;}var intervals=new ArrayList<CredibleLateralSet.Interval>();for(int i=0;i<chosen.length;i++)if(chosen[i]){var cell=row.cells().get(i);double lo=cell.offsetMeters()-.5*cell.quadratureWidthMeters(),hi=cell.offsetMeters()+.5*cell.quadratureWidthMeters();while(i+1<chosen.length&&chosen[i+1]){cell=row.cells().get(++i);hi=cell.offsetMeters()+.5*cell.quadratureWidthMeters();}intervals.add(new CredibleLateralSet.Interval(lo,hi));}assertEquals(intervals,actual.intervals());assertEquals(Math.min(1,sum),actual.retainedMass(),2e-12);}
    private static void assertNoPrefix(ProbabilisticInferenceResult r,ProbabilisticInferenceResult.Status status){assertEquals(status,r.status());assertTrue(r.mapPath().isEmpty());assertTrue(r.rawPaths().isEmpty());assertTrue(r.positionMarginals().isEmpty());assertTrue(r.credibleSets().isEmpty());}

    @Test void actualCapturedTableEntryUsesNewRowsOnceAndBindsOriginalPoints()throws Exception {
        new V022BCapturedTableIntegrationTest().withCompletedTable(table->{
            @SuppressWarnings("unchecked") var rows=(List<InferenceProfile>)member(table,"graph");
            var work=(BPhysicalWork)member(table,"work");var altered=new ArrayList<>(rows);var original=rows.get(0);
            var parameters=(EvidenceModelParameters)member(table,"parameters");
            assertEquals(List.of(1,1),rows.stream().map(r->r.cells().size()).toList());
            long[] before=((long[])member(work,"stats")).clone();long queries=work.queries(),semantic=work.semanticUnits();
            long[] globalBefore=((long[])member(work.tableRegistry(),"entered")).clone();
            var probe=work.probe();int wrapperBefore=(int)member(probe,"solverWrapperChecks");
            double j=table.potential(2).energy(0,0,0);assertTrue(j>=0);
            var armed=probe.getClass().getDeclaredField("solverArmed");armed.setAccessible(true);armed.setBoolean(probe,true);
            var lease=(AttemptMemoryLedger.Reservation)member(table,"solverHeadroom");assertEquals(member(member(table,"budget"),"headroom"),lease.bytes());
            var workspace=member(member(table,"permit"),"workspace");
            long bytesBefore=work.owner().currentBytes();var actual=table.solveRaw1(rows,parameters,RAW1,2);
            assertNull(member(table,"solverHeadroom"));assertEquals("CLOSED",member(lease,"state").toString());
            assertSame(workspace,member(member(table,"permit"),"workspace"),"the pool and numerical bank owner stay retained");
            System.out.println("TASK4_ENTRY status="+actual.result().status()+" explanation="+actual.result().explanation()+" nativeWork="+actual.result().workAccounting()
                    +" before="+bytesBefore+" after="+work.owner().currentBytes()+" peak="+work.owner().peakBytes()+" cap="+work.owner().limitBytes()
                    +" H="+member(member(table,"budget"),"headroom")+" pool="+BPhysicalAdaptiveIntegral.poolBytes(table.capacity()));
            assertEquals(semantic+BPhysicalPairTable.SOLVER_ENTRY_UNITS,work.semanticUnits());
            assertTrue(actual.result().mapPath().isPresent(),actual.result().explanation());
            assertArrayEquals(new int[]{0,0},actual.result().mapPath().orElseThrow().stateIndices());
            assertEquals(List.of(rows.get(0).point(0),rows.get(1).point(0)),actual.result().mapPath().orElseThrow().points());
            //Single original edge:zero anchor unaries and direct parity of the
            //original independent native orientation expression plus cached J.
            double orientation=independentOrientation(rows.get(0),rows.get(1));
            bits(orientation+j,actual.result().mapPath().orElseThrow().energy());
            assertEquals(-(orientation+j)/parameters.temperature(),actual.result().logPartition(),2e-15);
            assertEquals(1,actual.result().rawPaths().size());assertEquals(1,actual.result().completion().orElseThrow().completePathsAtSaturation());
            assertEquals(2,(int)member(probe,"solverWrapperChecks")-wrapperBefore,"one actual solve:first+final wrapper checkpoints;hidden second solve mutates this");
            assertEquals(queries,work.queries());assertArrayEquals(before,(long[])member(work,"stats"),"no raster/source point work inside solver");
            assertArrayEquals(globalBefore,(long[])member(work.tableRegistry(),"entered"),"no source reconstruction or invented global ENERGY event");
            assertEquals(0,work.tableRegistry().actual(BPhysicalTableRegistry.ENERGY,0));
            assertEquals(64L<<20,work.owner().limitBytes());assertTrue(work.owner().currentBytes()<=64L<<20);
            double[] unary=new double[original.cells().size()];for(int i=0;i<unary.length;i++)unary[i]=original.unaryCost(i);
            altered.set(0,new InferenceProfile(original.chainageMeters(),
                    new MetricPoint(original.anchor().xMeters()+.125,original.anchor().yMeters()),original.normalUnit(),
                    original.cells(),unary,List.of(),0,ObservationOwnership.DIRECT_TWO_SIDED,false));
            assertNotEquals(original.point(0),altered.get(0).point(0));
            assertEquals("Original table graph identity",assertThrows(IllegalArgumentException.class,
                    ()->table.solveRaw1(altered,parameters,RAW1,2)).getMessage());
            assertEquals("Original table graph identity",assertThrows(IllegalArgumentException.class,
                    ()->table.solveRaw1(new ArrayList<>(rows),parameters,RAW1,2)).getMessage());
            assertEquals("Original table parameter identity",assertThrows(IllegalArgumentException.class,
                    ()->table.solveRaw1(rows,EvidenceModelParameters.defaults(),RAW1,2)).getMessage());
            assertEquals("Explicit raw-one budgets required",assertThrows(IllegalArgumentException.class,
                    ()->table.solveRaw1(rows,parameters,budget(2),2)).getMessage());
            assertEquals("Fixed eta index",assertThrows(IllegalArgumentException.class,
                    ()->table.solveRaw1(rows,parameters,RAW1,4)).getMessage());
            assertEquals("Table solver entry consumed",assertThrows(IllegalStateException.class,
                    ()->table.solveRaw1(rows,parameters,RAW1,2)).getMessage());
            assertEquals(7,member(table,"solverAttempts"));
            assertEquals(semantic+7*BPhysicalPairTable.SOLVER_ENTRY_UNITS,work.semanticUnits());
            assertThrows(UnsupportedOperationException.class,()->((BPhysicalResponse)member(table,"response")).bound().requireRuntimeAuthority());
            System.out.println("TASK4_CAPTURED actualRows="+rows.size()+" J="+Double.toHexString(j)+" energy="+Double.toHexString(actual.result().mapPath().orElseThrow().energy())
                    +" policy="+actual.result().workAccounting().orElseThrow().executionPolicy()+" originalQueries="+queries+" solverWrapperChecks=2 peak="+work.owner().peakBytes());
        });
    }
    private static double independentOrientation(InferenceProfile a,InferenceProfile b){double h=heading(point(a,0),point(b,0));return EvidenceModelParameters.defaults().orientationWeight()*(b.chainageMeters()-a.chainageMeters())*.5*(orientation(a,h)+orientation(b,h));}
    private static double orientation(InferenceProfile p,double h){var s=p.orientationSupport(0);if(s.certainty()==0||s.modes().isEmpty())return 0;double best=Double.POSITIVE_INFINITY;double angle=h%Math.PI;if(angle<0)angle+=Math.PI;for(var m:s.modes()){double a=distance(angle,m.startRadians()),b=distance(angle,m.endRadians());boolean inside=m.startRadians()<=m.endRadians()?angle>=m.startRadians()&&angle<=m.endRadians():angle>=m.startRadians()||angle<=m.endRadians();double d=inside?0:Math.min(a,b);assertTrue(d==0||d>1e-12,"fixture outside native angular containment fuzz");best=Math.min(best,d);}double sine=StrictMath.sin(best);return s.certainty()*p.orientationReliability(0)*(sine*sine);}
    private static double distance(double a,double b){double d=Math.abs(a-b);return Math.min(d,Math.PI-d);}

    @Test void callbackScheduleDetectsOmittedInitialPosteriorOrMaterializationWork(){
        var g=analytic();int[] calls=new int[3];
        var actual=rawWith(g,SHAPE,RAW1,(s,l,r)->{
            boolean fb=false,kb=false,material=false;
            for(var frame:Thread.currentThread().getStackTrace())if(frame.getClassName().equals(ProbabilisticInference.class.getName())){
                fb|=frame.getMethodName().equals("forwardBackward");kb|=frame.getMethodName().equals("enumerateKBest");material|=frame.getMethodName().equals("materializeWithPotential");}
            assertEquals(1,(fb?1:0)+(kb?1:0)+(material?1:0));calls[fb?0:kb?1:2]++;
            return FEATURE[s][l][r];
        },CancellationProbe.NONE);
        assertArrayEquals(new int[]{4+8+8,4+4,0},calls,"one solve;normal finite masses need no exceptional materialization replay");
        assertPaths(retained(g,SHAPE,FEATURE,1),actual.result().rawPaths());
        var best=actual.result().rawPaths().get(0);double z=partition(enumerate(g,SHAPE,FEATURE),SHAPE.temperature());
        assertEquals(StrictMath.exp(-best.energy()/SHAPE.temperature()+best.logBaseMeasure()-z),best.conditionalPosteriorMass(),2e-12);
        assertNotEquals(0,calls[0]);assertNotEquals(0,calls[1]); //omission mutations are observable.
    }

    @Test void nativeHelperOnlyEntryCancellationAndBoundedPreflightsRemainTerminal()throws Exception {
        //Helper lifecycle coverage ONLY;this no-signal native fixture is not
        //the captured/new-kernel proof above and does not change its recipe.
        for(boolean cancel:new boolean[]{false,true}){
            boolean[] armed={false};
            try(var w=BPhysicalWork.create(()->armed[0])){
                var f=V022BPhysicalPairTableTest.fixture(w,false,2);
                var lattices=f.profiles().stream().map(p->V022BPhysicalPairTableTest.nativeLattice(w,p)).toList();
                var p=EvidenceModelParameters.defaults();
                try(var table=BPhysicalPairTable.build(w,f.snapshot(),"scalar",f.profiles(),lattices,p,4,BPhysicalPairTable.unitPublicationBounds(w,2))){
                    @SuppressWarnings("unchecked") var rows=(List<InferenceProfile>)member(table,"graph");long sem=w.semanticUnits();
                    var lease=(AttemptMemoryLedger.Reservation)member(table,"solverHeadroom");assertNotNull(lease);
                    if(cancel){
                        long before=w.owner().currentBytes();armed[0]=true;
                        assertThrows(CancellationException.class,()->table.solveRaw1(rows,p,RAW1,2));armed[0]=false;
                        assertEquals(before,w.owner().currentBytes());assertEquals(true,member(table,"solverConsumed"));
                        assertSame(lease,member(table,"solverHeadroom"));assertEquals("ACTIVE",member(lease,"state").toString());
                        assertEquals("Table solver entry consumed",assertThrows(IllegalStateException.class,()->table.solveRaw1(rows,p,RAW1,2)).getMessage());
                        assertEquals(sem+2*BPhysicalPairTable.SOLVER_ENTRY_UNITS,w.semanticUnits());
                    }else{
                        for(int i=0;i<8;i++)assertEquals("Original table graph identity",assertThrows(IllegalArgumentException.class,
                                ()->table.solveRaw1(new ArrayList<>(rows),p,RAW1,2)).getMessage());
                        assertEquals(false,member(table,"solverConsumed"));
                        assertEquals("Solver preflight ceiling",assertThrows(IllegalStateException.class,()->table.solveRaw1(rows,p,RAW1,2)).getMessage());
                        assertEquals(sem+9*BPhysicalPairTable.SOLVER_ENTRY_UNITS,w.semanticUnits());
                        assertThrows(IllegalStateException.class,table::complete);
                    }
                    table.close();assertThrows(IllegalStateException.class,()->table.solveRaw1(rows,p,RAW1,2));
                    assertEquals("CLOSED",member(lease,"state").toString());
                }
            }
        }
    }

    @Test void historicalSoleRouteExceptionalMassReplayConsumesTheSameNonnullPotential()throws Exception {
        //Exact existing NUM01 fixture,not a new captured/source input. All
        //rows exist before either invocation;the callback only returns0.
        var method=V022SingleRoutePosteriorRoundoffTest.class.getDeclaredMethod("originalProfiles");method.setAccessible(true);
        @SuppressWarnings("unchecked") var g=(List<InferenceProfile>)method.invoke(null);
        var p=new EvidenceModelParameters("p6-independent",0,.5,0,0,.75,.5,1,.01);
        var region=MetricRegion.rectangle(-1,.5,5,2);int[] replay={0};
        var ledger=new AttemptMemoryLedger(1000000);
        try(var owner=ledger.rootOwner()){
            var result=new ProbabilisticInference().solveCrossStreamRaw1(g,p,RAW1,region,CancellationProbe.NONE,owner,(s,l,r)->{
                if(inSolver("materializeWithPotential"))replay[0]++;return 0;
            });
            assertEquals(3,replay[0],"initial pair and two later pairs only on the exceptional finite mass>1 path");
            var path=result.result().rawPaths().get(0);assertArrayEquals(new int[]{2,2,2,2},path.stateIndices());
            assertEquals(0x4000333333333334L,Double.doubleToRawLongBits(path.energy()));
            assertEquals(0x3fec8ff7c79a9a22L,Double.doubleToRawLongBits(path.logBaseMeasure()));
            assertEquals(1,path.conditionalPosteriorMass());
        }
        assertEquals(0,ledger.currentBytes());
        //A TEST-only failing callback at that actual replay seam must propagate,
        //rather than silently dropping the immutable feature or publishing mass.
        var sentinel=new IllegalStateException("test exceptional potential replay");
        var failureLedger=new AttemptMemoryLedger(1000000);
        try(var owner=failureLedger.rootOwner();var caller=owner.reserve(7)){
            assertSame(sentinel,assertThrows(IllegalStateException.class,()->new ProbabilisticInference().solveCrossStreamRaw1(g,p,RAW1,region,CancellationProbe.NONE,owner,(s,l,r)->{
                if(inSolver("materializeWithPotential"))throw sentinel;return 0;
            })));
            assertEquals(7,owner.currentBytes());
        }
        assertEquals(0,failureLedger.currentBytes());
    }
    private static boolean inSolver(String method){for(var frame:Thread.currentThread().getStackTrace())if(frame.getClassName().equals(ProbabilisticInference.class.getName())&&frame.getMethodName().equals(method))return true;return false;}

    @Test void entryLayoutAndSourcePartitionArePrepaidBeforeAllocationAndK()throws Exception {
        long fields=0;int refs=0,ints=0,flags=0;
        for(var f:BPhysicalPairTable.class.getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(f.getModifiers())){
            Class<?> t=f.getType();if(!t.isPrimitive()){fields+=8;refs++;}else if(t==int.class){fields+=4;ints++;}else if(t==boolean.class){fields++;flags++;}else fail("unexpected entry layout "+t);}
        assertEquals(22,refs);assertEquals(5,ints);assertEquals(2,flags);assertEquals(198,fields);
        assertEquals("FixedSource",BPhysicalPairTable.class.getDeclaredField("metadataSource").getType().getSimpleName());
        assertEquals(216,AttemptMemoryLedger.objectBytes(fields));
        assertEquals(216+80+72+272+24+48,712);assertEquals(776,BPhysicalPairTable.FRAME);assertTrue(712<=BPhysicalPairTable.FRAME);
        long leaseFields=0;for(var f:AttemptMemoryLedger.Reservation.class.getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(f.getModifiers())){if(f.getType().isPrimitive())assertEquals(long.class,f.getType());leaseFields+=8;}
        assertEquals(32,leaseFields);assertEquals(48,AttemptMemoryLedger.objectBytes(leaseFields));
        var path=java.nio.file.Path.of("src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/BPhysicalPairTable.java");
        String s=java.nio.file.Files.readString(path),entry=s.substring(s.indexOf("    CrossStreamInferenceResult solveRaw1("),s.indexOf("    MutableSupport supportCarrier("));
        //Actual transitive guard sites:ready twice,bound,probe,owner,potential.
        assertEquals(2,java.util.regex.Pattern.compile("ready\\(\\)").matcher(entry).results().count());
        for(String call:List.of("response.bound()","work.probe()","work.owner()","potential(eta)"))assertEquals(1,entry.split(java.util.regex.Pattern.quote(call),-1).length-1);
        assertEquals(8,BPhysicalPairTable.STORED_SOLVER_FORWARD_UNITS);
        assertEquals(8+512+6*(2*73+32),BPhysicalPairTable.SOLVER_ENTRY_UNITS);
        assertEquals(9*BPhysicalPairTable.SOLVER_ENTRY_UNITS,BPhysicalPairTable.SOLVER_ENTRY_UPPER);
        int buildStart=s.indexOf("BExperimentalScalarCapture.AcquisitionPath actualPath,String[] declaredGlobals)");
        int buildEnd=s.indexOf("    private void prepareCapturedLocal()");
        assertTrue(buildStart>=0&&buildEnd>buildStart);
        assertFundedConstruction(s.substring(buildStart,buildEnd),
                "work.prepayTableBody(256+36+METADATA_REFERENCE_INIT+8)",
                "owner=work.owner().child(\"physical-complete-table\")");
        int metadataStart=s.indexOf("    static BPhysicalPublicationMetadata.Plan preflightCompletePublicationData(");
        int metadataEnd=s.indexOf("    void requireMetadataLive(");
        assertTrue(metadataStart>=0&&metadataEnd>metadataStart);
        assertFundedConstruction(s.substring(metadataStart,metadataEnd),
                "work.prepayTableBody(292+METADATA_REFERENCE_INIT)",
                "owner=work.owner().child(\"physical-metadata-pre-K\")");
        assertTrue(s.contains("long legacyFixed=add(SOLVER_ENTRY_UPPER+SOLVER_HEADROOM_SETUP,"));
        assertTrue(s.contains("fixed=add(legacyFixed,metadataFuture)"));
        String[] entryTerms={"prepayTableBody(SOLVER_ENTRY_UNITS)","solverAttempts==",
                "expectedGraph!=graph","expectedParameters!=parameters","budgets==null",
                "if(eta<0||eta>3)","if(solverConsumed)","solverConsumed=true",
                "work.poll();ready();","potential(eta)","lease.close()","engine.solveCrossStreamRaw1"};
        assertSourceOrder(entry,entryTerms);
        //Source-check controls only; removed guards must not pass as index(-1).
        for(String term:entryTerms){String missing=entry.replace(term,"");
            assertThrows(AssertionError.class,()->assertSourceOrder(missing,entryTerms));}
        assertEquals(178+256,BPhysicalPairTable.SOLVER_HEADROOM_SETUP);
        String adaptive=java.nio.file.Files.readString(path.resolveSibling("BPhysicalAdaptiveIntegral.java"));
        String tableConstructor=adaptive.substring(adaptive.indexOf("    static BPhysicalAdaptiveIntegral forTable("),adaptive.indexOf("    void certifyForTable("));
        assertSourceOrder(tableConstructor,"prepayTableBody(BPhysicalPairTable.SOLVER_HEADROOM_SETUP)","admit(19)");
        assertTrue(tableConstructor.contains("holdSolverHeadroom(a.owner.reserve(headroom))"));
        assertTrue(tableConstructor.contains("a.owner.reserve(poolCharge)"));
        assertFalse(tableConstructor.contains("adopt("));
    }
    private static void assertFundedConstruction(String source,String prepay,String child){
        String[] terms={prepay,child,"owner.reserve(FRAME)","new BPhysicalPairTable("};
        assertSourceOrder(source,terms);
        //In-memory source-check controls only; neither altered body executes.
        assertThrows(AssertionError.class,()->assertSourceOrder(source.replace(prepay,""),terms));
        assertThrows(AssertionError.class,()->assertSourceOrder("new BPhysicalPairTable("+source,terms));
    }
    private static void assertSourceOrder(String source,String... terms){
        int previous=-1;
        for(String term:terms){int at=source.indexOf(term);assertTrue(at>=0,"Missing allocation/funding site: "+term);
            assertTrue(at>previous,"Allocation/funding sites out of order: "+term);previous=at;}
    }
    private static Object member(Object object,String name){try{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
}
