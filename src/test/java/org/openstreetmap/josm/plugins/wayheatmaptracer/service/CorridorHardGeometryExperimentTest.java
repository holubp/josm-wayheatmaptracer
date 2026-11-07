package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Independent three-state graph: zero-cost reversal versus symmetric costlier legal exits. */
class CorridorHardGeometryExperimentTest {
    @Test void explicitGuardFindsSlightlyCostlierLegalMinimumInsteadOfExistingHardBacktrack()throws Exception {
        var g=graph(false);var baseline=new CorridorCenterlineOptimizer().optimize(g.track,g.profiles,1,g.junctions);
        assertEquals(List.of(0.0,0.0,0.0),baseline.offsetsPx());
        assertEquals(1,FinalGeometryEvaluator.backtrackFindings(metric(baseline)).stream()
            .filter(f->f.code()==FinalGeometryEvaluator.FindingCode.ADJACENT_BACKTRACK).count());
        var result=guarded().optimize(g.track,g.profiles,1,g.junctions);
        assertTrue(FinalGeometryEvaluator.backtrackFindings(metric(result)).isEmpty(),"Explicit opt-in must reject the existing hard-backtrack triple");
        assertEquals(List.of(0.0,0.0,-4.0),result.offsetsPx(),"Independent legal states are +/-4; symmetric tie chooses -4");
        assertTrue(result.totalCost()>baseline.totalCost());
    }
    @Test void exhaustedGraphReturnsTypedNoRouteAndActualSpentWork()throws Exception {
        var g=graph(true);var optimizer=guarded();var result=optimizer.optimize(g.track,g.profiles,1,g.junctions);
        assertEquals(CorridorCenterlineOptimizer.SolverDisposition.NO_LEGAL_PATH,result.disposition());
        assertTrue(result.offsetsPx().isEmpty());assertEquals(3,result.profileCostEvaluations());
        var work=optimizer.hardGeometryWork();assertEquals(CorridorCenterlineOptimizer.HardGeometryOutcome.NO_LEGAL_PATH,work.outcome());
        assertEquals(2,work.attemptedTransitions());assertEquals(1,work.testedTriples());assertEquals(1,work.rejectedTriples());assertEquals(1,work.scoredTransitions());
        assertEquals(result.transitionEvaluations(),work.attemptedTransitions());assertTrue(work.metricEdges()>0);assertTrue(work.metricCacheReservedBytes()>0);
    }
    @Test void cancellationPreservesAlreadyEvaluatedStateCosts()throws Exception {
        var g=graph(false);var optimizer=new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(transform(0,1,1),()->true);
        var stopped=assertThrows(CorridorCenterlineOptimizer.HardGeometryCancelledException.class,()->optimizer.optimize(g.track,g.profiles,1,g.junctions));
        assertEquals(CorridorCenterlineOptimizer.HardGeometryOutcome.CANCELLED,stopped.work().outcome());
        assertTrue(stopped.work().profileCostEvaluations()>0);assertEquals(stopped.work(),optimizer.hardGeometryWork());
    }
    @Test void failedTrackUsageSurvivesTrackerAggregation()throws Exception {
        var g=graph(true);var samples=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for(var p:g.profiles){var values=new ArrayList<RenderedHeatmapSampler.IntensitySample>();for(int x=-7;x<=7;x++){double value=Math.abs(x)<=4?.8:0;values.add(new RenderedHeatmapSampler.IntensitySample(x,value,value,value,true));}var s=p.source();samples.add(new RenderedHeatmapSampler.CrossSectionProfile(s.samplingAnchor(),s.normalScreen(),List.of(),true,values));}
        var result=new CorridorAwareTracker(guarded()).trackDetailed(samples,1,g.junctions);
        assertTrue(result.candidates().isEmpty());assertFalse(result.optimizations().isEmpty());
        assertTrue(result.optimizations().values().stream().allMatch(r->r.disposition()==CorridorCenterlineOptimizer.SolverDisposition.NO_LEGAL_PATH));
        assertTrue(result.optimizations().values().stream().mapToLong(CorridorCenterlineOptimizer.OptimizationResult::transitionEvaluations).sum()>0);
    }
    @Test void sharedPredicateRetainsStrictBoundaryAndLiteralZeroEdges() {
        double y=StrictMath.sqrt(15);assertEquals(4.0,StrictMath.hypot(-1,y));
        assertFalse(predicate(1,0,-1,y));assertTrue(predicate(1,0,Math.nextDown(-1.0),y));assertFalse(predicate(1,0,Math.nextUp(-1.0),y));
        assertFalse(predicate(0,0,-1,0));assertFalse(predicate(1,0,0,0));
        for(double angle:List.of(0.0,.37,1.9))for(double scale:List.of(.25,1.0,7.0)) {
            double c=Math.cos(angle)*scale,s=Math.sin(angle)*scale;
            assertTrue(predicate(c,s,-c,-s));assertFalse(predicate(c,s,c,s));
        }
    }
    @Test void tinyExactDpMatchesIndependentExhaustiveLegalPathOracleIncludingTies()throws Exception {
        var random=new Random(74129);
        for(int sample=0;sample<18;sample++) {
            double[] x={0,1,0,2},y={0,random.nextDouble()*.1,random.nextDouble()*.1,0};
            var g=customGraph(x,y);var domain=List.of(List.of(0.0),List.of(-1.0,0.0,1.0),List.of(-4.0,0.0,4.0),List.of(-2.0,0.0,2.0));
            var metric=transform(sample*.13,sample%2==0?1:2,1);
            var optimizer=new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(metric,CancellationProbe.NONE);
            var context=context(g,optimizer);var all=new ArrayList<List<Double>>();enumerate(domain,0,new ArrayList<>(),all);
            List<Double> expected=null;double minimum=Double.POSITIVE_INFINITY;
            for(var path:all)if(independentlyLegal(g,path,metric)) {
                double cost=score(g,path,optimizer,context);
                if(cost<minimum||cost==minimum&&reverseOrder(path,expected)<0){minimum=cost;expected=path;}
            }
            Object exact=call(optimizer,"solveExact",g.track,g.profiles,domain,1.0,g.junctions,Map.of(),context.tube,context.approaches,context.ripple,new double[g.profiles.size()],0.0);
            @SuppressWarnings("unchecked") var actual=(List<Double>)call(exact,"offsetsPx");
            if(expected==null)assertTrue(actual.isEmpty());else {assertEquals(expected,actual);assertEquals(minimum,score(g,actual,optimizer,context),1e-10);}
        }
    }
    @Test void duplicateRunsRemainForFinalValidatorWithoutAddingHistory()throws Exception {
        var g=customGraph(new double[]{0,1,1,0},new double[]{0,1,2,3});
        var domain=List.of(List.of(0.0),List.of(-1.0),List.of(-2.0),List.of(-3.0));var opt=guarded();var context=context(g,opt);
        var exact=call(opt,"solveExact",g.track,g.profiles,domain,1.0,g.junctions,Map.of(),context.tube,context.approaches,context.ripple,new double[4],0.0);
        @SuppressWarnings("unchecked") var result=(List<Double>)call(exact,"offsetsPx");assertEquals(List.of(0.0,-1.0,-2.0,-3.0),result);
        assertEquals(0,opt.hardGeometryWork().rejectedTriples());
        var points=pathPoints(g,result,transform(0,1,1));assertFalse(FinalGeometryEvaluator.backtrackFindings(points).isEmpty(),"Full validator must still inspect backward across the zero run");
    }
    @Test void legalPlateauSineAndSwitchbackWinnersRetainExactUnchangedObjective()throws Exception {
        var fixtures=new CorridorCenterlineOptimizerTest();
        for(java.util.function.IntToDoubleFunction function:List.<java.util.function.IntToDoubleFunction>of(i->0,i->5*StrictMath.sin(i*Math.PI/10),i->8*StrictMath.sin(i*Math.PI/8))) {
            var fixture=call(fixtures,"scenario",function,false);
            var track=(CorridorTrack)call(fixture,"track");
            @SuppressWarnings("unchecked") var profiles=(List<CorridorProfile>)call(fixture,"profiles");
            var baseline=new CorridorCenterlineOptimizer().optimize(track,profiles,1);var opt=guarded();var filtered=opt.optimize(track,profiles,1);
            assertTrue(FinalGeometryEvaluator.backtrackFindings(metric(baseline)).isEmpty());
            assertEquals(baseline.offsetsPx(),filtered.offsetsPx());assertEquals(baseline.totalCost(),filtered.totalCost());assertEquals(baseline.costs(),filtered.costs());
            assertEquals(filtered.profileCostEvaluations(),opt.hardGeometryWork().metricPoints());
        }
    }
    @Test void explicitModeRetainsSupportedOppositeArcAndThinParallelIdentities()throws Exception {
        var tracker=new CorridorAwareTracker(guarded());
        var candidates=tracker.track(PhysicalGeometryFixture.supportedOppositeArcProfiles(),PhysicalGeometryFixture.NATIVE_PITCH);
        assertFalse(candidates.isEmpty());double radius=4*PhysicalGeometryFixture.NATIVE_PITCH;
        for(var point:candidates.get(0).screenPoints())assertEquals(radius,Math.hypot(point.x-64,point.y-64),PhysicalGeometryFixture.NATIVE_PITCH);
        assertTrue(candidates.get(0).screenPoints().stream().anyMatch(p->p.x<64-radius+PhysicalGeometryFixture.NATIVE_PITCH));
        for(double amplitude:List.of(.95,.35)) {
            var frame=PhysicalGeometryFixture.parallelFrame(amplitude);
            var old=new CorridorAwareTracker().trackDetailed(frame.profileSet(),frame.sourcePixelPitch(),JunctionContext.empty());
            var guarded=new CorridorAwareTracker(guarded()).trackDetailed(frame.profileSet(),frame.sourcePixelPitch(),JunctionContext.empty());
            assertFalse(guarded.candidates().isEmpty());assertEquals(old.candidates().get(0).id(),guarded.candidates().get(0).id());assertEquals(old.candidates().get(0).offsetsPx(),guarded.candidates().get(0).offsetsPx());
        }
    }
    @Test void explicitModePreservesSparseAndWeakRasterControlsWithFixedEndpoints()throws Exception {
        var fixtures=new CorridorRasterIntegrationTest();
        @SuppressWarnings("unchecked") var source=(List<Point2D.Double>)call(fixtures,"sourcePolyline");
        var sparse=(java.awt.image.BufferedImage)call(fixtures,"complementarySparseRaster");
        var weak=(java.awt.image.BufferedImage)call(fixtures,"raster",(java.util.function.IntToDoubleFunction)(i->8*StrictMath.sin(i*2*Math.PI/60)),.25,true,false);
        var fixed=new JunctionContext(List.of(new EndpointConstraint(0,1,true,false,0,0,6),new EndpointConstraint(60,2,true,false,0,0,6)));
        for(var image:List.of(sparse,weak)) {
            var profiles=new RenderedHeatmapSampler().sampleProfilesOnScaledRaster(image,source,18,1,"hot",1,1,IntensitySamplingMode.DIRECT_VALUE);
            var old=new CorridorAwareTracker().trackDetailed(profiles,1,fixed);
            var filtered=new CorridorAwareTracker(guarded()).trackDetailed(profiles,1,fixed);
            assertFalse(filtered.candidates().isEmpty());assertEquals(old.candidates().get(0).offsetsPx(),filtered.candidates().get(0).offsetsPx());
            assertEquals(0.0,filtered.candidates().get(0).offsetsPx().get(0),0.0);assertEquals(0.0,filtered.candidates().get(0).offsetsPx().get(60),0.0);
            assertTrue(filtered.candidates().get(0).evidence().corridorCoverage().complete());
        }
    }
    private static CorridorCenterlineOptimizer guarded()throws Exception {
        return new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(transform(0,1,1),CancellationProbe.NONE);
    }
    private static boolean predicate(double x,double y,double a,double b){return FinalGeometryEvaluator.hardBacktrackEdges(x,y,StrictMath.hypot(x,y),a,b,StrictMath.hypot(a,b));}
    private static RasterMetricTransform transform(double angle,double xScale,double yScale){double c=Math.cos(angle),s=Math.sin(angle);return new RasterMetricTransform("test-factual-metric",RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,new MetricPoint(10,-8),c*xScale,s*xScale,-s*yScale,c*yScale,1);}
    private static Object call(Object target,String name,Object...args)throws Exception{Class<?> c=target instanceof Class<?> t?t:target.getClass();for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(target instanceof Class<?>?null:target,args);}throw new NoSuchMethodException(name);}
    private static Graph customGraph(double[] xs,double[] ys){var profiles=new ArrayList<CorridorProfile>();var points=new LinkedHashMap<Integer,CorridorTrackPoint>();double chain=0;for(int i=0;i<xs.length;i++){if(i>0)chain+=Math.hypot(xs[i]-xs[i-1],ys[i]-ys[i-1]);var band=new CorridorBand("b"+i,0,-4,4,-4,4,List.of(0.0),.8,.02,1,1,1,.5,false,List.of());var source=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(xs[i],ys[i]),xs[i],ys[i],chain),new Point2D.Double(0,1),List.of(),true,List.of());profiles.add(new CorridorProfile(i,source,List.of(band),.8,.02,.78,true));points.put(i,new CorridorTrackPoint(i,band,false));}return new Graph(new CorridorTrack("graph",points,points.size(),1,false,List.of(),""),profiles,JunctionContext.empty());}
    private static Context context(Graph g,CorridorCenterlineOptimizer optimizer){var tube=new CorridorTubeBuilder().build(g.track,g.profiles,1,Map.of());var approaches=new EndpointApproachBuilder().build(g.track,g.profiles,tube,g.junctions,Map.of());return new Context(tube,approaches,new UnsupportedRippleEvaluator().evaluate(g.track,tube,1,10,false));}
    private static void enumerate(List<List<Double>> states,int row,List<Double> prefix,List<List<Double>> result){if(row==states.size()){result.add(List.copyOf(prefix));return;}for(double value:states.get(row)){prefix.add(value);enumerate(states,row+1,prefix,result);prefix.remove(prefix.size()-1);}}
    private static int reverseOrder(List<Double> a,List<Double> b){if(b==null)return -1;for(int i=a.size()-1;i>=0;i--){int c=Double.compare(a.get(i),b.get(i));if(c!=0)return c;}return 0;}
    private static List<MetricPoint> pathPoints(Graph g,List<Double> offsets,RasterMetricTransform transform){var result=new ArrayList<MetricPoint>();for(int i=0;i<offsets.size();i++){var p=g.profiles.get(i).source();var a=p.anchorScreen();var n=p.normalScreen();result.add(transform.pixelCenterToMetric(a.x+n.x*offsets.get(i),a.y+n.y*offsets.get(i)));}return result;}
    private static boolean independentlyLegal(Graph g,List<Double> offsets,RasterMetricTransform transform){var ps=pathPoints(g,offsets,transform);for(int i=1;i+1<ps.size();i++){var a=ps.get(i-1);var b=ps.get(i);var c=ps.get(i+1);double ux=b.xMeters()-a.xMeters(),uy=b.yMeters()-a.yMeters(),vx=c.xMeters()-b.xMeters(),vy=c.yMeters()-b.yMeters();if(Math.hypot(ux,uy)==0||Math.hypot(vx,vy)==0)continue;double turn=Math.abs(Math.atan2(ux*vy-uy*vx,ux*vx+uy*vy));if(turn>Math.acos(-.25))return false;}return true;}
    private static double score(Graph g,List<Double> offsets,CorridorCenterlineOptimizer opt,Context context)throws Exception{double total=0;for(int i=0;i<offsets.size();i++){double profile=((Number)call(opt,"profileCost",g.track,g.profiles,i,offsets.get(i),1.0,g.junctions,Map.of(),context.tube,context.approaches,context.ripple.get(i),0.0,0.0)).doubleValue();double continuity=0,curvature=0;if(i>0){double spacing=((Number)call(opt,"profileSpacing",g.profiles,i,1.0)).doubleValue();continuity=((Number)call(opt,"continuityWeight",g.track,i)).doubleValue()*Math.pow((offsets.get(i)-offsets.get(i-1))/spacing,2);}if(i>1)curvature=((Number)call(opt,"accelerationWeight",g.track,i,context.tube)).doubleValue()*((Number)call(opt,"geometricCurvatureCost",g.profiles,offsets,context.tube,i,1.0)).doubleValue();total=total+profile+continuity+curvature;}return total;}
    private record Context(LongitudinalCorridorTube tube,EndpointApproachModel approaches,List<UnsupportedRippleEvaluator.RippleSupport> ripple) { }
    private static List<MetricPoint> metric(CorridorCenterlineOptimizer.OptimizationResult result){return result.screenPoints().stream().map(p->new MetricPoint(p.x,-p.y)).toList();}
    private static Graph graph(boolean allFixed){
        var profiles=new ArrayList<CorridorProfile>();var points=new LinkedHashMap<Integer,CorridorTrackPoint>();
        for(int i=0;i<3;i++) {
            double x=i==1?1:0;var band=new CorridorBand("b"+i,0,-4,4,-4,4,List.of(0.0),.8,.02,1,1,1,.5,false,List.of());
            var source=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(x,0),x,0,i),new Point2D.Double(0,1),List.of(),true,List.of());
            profiles.add(new CorridorProfile(i,source,List.of(band),.8,.02,.78,true));points.put(i,new CorridorTrackPoint(i,band,false));
        }
        var fixed=new ArrayList<EndpointConstraint>();for(int i=0;i<(allFixed?3:2);i++)fixed.add(new EndpointConstraint(i,i+1,true,false,0,0,0));
        return new Graph(new CorridorTrack("graph",points,3,1,false,List.of(),""),profiles,new JunctionContext(fixed));
    }
    private record Graph(CorridorTrack track,List<CorridorProfile> profiles,JunctionContext junctions) { }
}
