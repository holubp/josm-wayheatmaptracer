package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Native-raster analytic correspondence oracle, without captured geometry or hand-fit output. */
class CorridorBridgePreparedTest {
    @Test void nativeStraightRidgeRetainsBridgeInsteadOfMandatoryOrderReversingVertices()throws Exception {
        var fixture=fixture();var sampled=fixture.sampled();var profiles=fixture.profiles();var track=fixture.track();var allowed=fixture.allowed();
        assertTrue(sampled.get(4).anchorScreen().x<sampled.get(3).anchorScreen().x,"Independent source chart must reverse its physical order");
        var optimizer=new CorridorCenterlineOptimizer();CorridorCenterlineOptimizer.OptimizationResult result;
        try{
            var scheduleType=Class.forName(CorridorCenterlineOptimizer.class.getPackageName()+".CorridorWorkingSchedule");
            var bridge=scheduleType.getDeclaredMethod("bridge",int.class,int.class,int.class);bridge.setAccessible(true);var schedule=bridge.invoke(null,10,1,8);
            var prepared=CorridorCenterlineOptimizer.class.getDeclaredMethod("optimizePrepared",CorridorTrack.class,List.class,double.class,scheduleType,List.class);prepared.setAccessible(true);
            result=(CorridorCenterlineOptimizer.OptimizationResult)prepared.invoke(optimizer,track,profiles,1.0,schedule,allowed);
        }catch(ClassNotFoundException|NoSuchMethodException oldFullVertexModel){
            result=optimizer.optimize(track,profiles,1);
        }
        assertEquals(4,result.screenPoints().size(),"The prepared graph retains original rows0,1,8,9; six rows remain bridge measurements");
        double previous=Double.NEGATIVE_INFINITY;for(var p:result.screenPoints()){assertTrue(p.x>=previous,"Retained ridge cannot reverse along the native straight signal");previous=p.x;assertEquals(26,p.y,.3);}
    }

    private record Fixture(List<RenderedHeatmapSampler.CrossSectionProfile> sampled,List<CorridorProfile> profiles,
        CorridorTrack track,List<List<Double>> allowed){ }

    private static Fixture fixture(){return fixture(false);}
    private static Fixture fixture(boolean monotonicSourceY){return fixture(monotonicSourceY,1.2);}
    private static Fixture fixture(boolean monotonicSourceY,double sigma){return fixture(monotonicSourceY,sigma,false);}
    private static Fixture fixture(boolean monotonicSourceY,double sigma,boolean broadPlateau){
        var image=new BufferedImage(80,48,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            double ridge=broadPlateau?(Math.abs(y-26)<=3?1:0):Math.exp(-.5*Math.pow((y-26)/sigma,2));
            int intensity=(int)Math.round(255*(.02+.90*ridge));image.setRGB(x,y,new Color(intensity,intensity,intensity).getRGB());
        }
        double[] x={30,40,40.5,40.8,40.6,40.2,40.3,40.7,41,50};
        var source=new ArrayList<Point2D.Double>();for(int i=0;i<x.length;i++)source.add(new Point2D.Double(x[i],20+(monotonicSourceY?0:.002*Math.sin(i))));
        var sampled=new RenderedHeatmapSampler().sampleProfilesOnScaledRaster(image,source,12,1,"hot",1,1,IntensitySamplingMode.DIRECT_VALUE);
        var profiles=new CorridorExtractor().extract(sampled,1);var points=new LinkedHashMap<Integer,CorridorTrackPoint>();var allowed=new ArrayList<List<Double>>();
        for(int i=0;i<profiles.size();i++){
            var leaves=profiles.get(i).bands().stream().filter(b->!b.parentHypothesis()&&b.hasMeasuredCenter()).toList();assertEquals(1,leaves.size(),"Analytic image has only one native ridge");
            var band=leaves.get(0);points.put(i,new CorridorTrackPoint(i,band,false));allowed.add(List.of(band.centerOffsetPx()));
        }
        var track=new CorridorTrack("native-straight-alias",points,10,1,false,List.of(),"");
        return new Fixture(sampled,profiles,track,allowed);
    }

    @Test void originalMappingPreservesDistantGapsEndpointsAndLiteralRecords(){
        var s=CorridorWorkingSchedule.bridge(309,200,207);
        assertEquals(303,s.workingCount());assertEquals(51,s.originalRow(51));assertEquals(304,s.originalRow(298));
        assertEquals(200,s.originalRow(200));assertEquals(207,s.originalRow(201));assertEquals(308,s.originalRow(302));
        assertEquals(List.of(201,202,203,204,205,206),s.consumedRows());
        for(int row:s.consumedRows())assertEquals(-1,s.workingRow(row));
        var f=fixture();var small=CorridorWorkingSchedule.bridge(10,1,8);
        var tube=new CorridorTubeBuilder().build(f.track(),f.profiles(),1,Map.of());
        var retained=small.geometryTube(tube);
        assertEquals(tube.at(8).centerOffsetPx(),retained.at(2).centerOffsetPx());
        assertEquals(tube.at(8).distanceMeters(),retained.at(2).distanceMeters());
        assertSame(f.profiles().get(8),small.retain(f.profiles()).get(2));
        var guide=new EndpointApproachModel(List.of(new EndpointApproachModel.EndpointApproach(0,1,9,true,"test",
            List.of(new EndpointApproachModel.GuideTarget(3,0,1,false)))));
        assertThrows(IllegalArgumentException.class,()->small.geometryApproaches(guide));
    }

    @Test void originalIdentityPathMatchesDefaultExactly(){
        var f=fixture();var optimizer=new CorridorCenterlineOptimizer();
        var tube=new CorridorTubeBuilder().build(f.track(),f.profiles(),1,Map.of());
        var original=optimizer.prepareOriginalDomain(f.track(),f.profiles(),1,JunctionContext.empty(),Map.of(),tube);
        assertEquals(optimizer.optimize(f.track(),f.profiles(),1),optimizer.optimizeOriginalDomain(original));
    }

    @Test void exhaustiveRetainedGraphChargesAllOriginalUnariesOnceAndBothCollarTurns()throws Exception{
        var f=fixture();var optimizer=new CorridorCenterlineOptimizer();
        var tube=new CorridorTubeBuilder().build(f.track(),f.profiles(),1,Map.of());
        var original=optimizer.prepareOriginalDomain(f.track(),f.profiles(),1,JunctionContext.empty(),Map.of(),tube);
        var states=new ArrayList<List<Double>>();for(int row=0;row<10;row++){
            double c=f.allowed().get(row).get(0);states.add(List.of(c-.12,c+.18));
        }
        var s=CorridorWorkingSchedule.bridge(10,1,8);
        var domain=CorridorWorkingSchedule.BridgeDomain.intersections(s,f.track(),f.profiles(),states);
        var independentEdges=new LinkedHashMap<String,List<Double>>();
        for(int b=0;b<2;b++)for(int c=0;c<2;c++){
            var a=point(f.profiles().get(1),states.get(1).get(b));var z=point(f.profiles().get(8),states.get(8).get(c));
            double slope=(z.y-a.y)/(z.x-a.x);var hits=new ArrayList<Double>();boolean valid=true;
            for(int row=2;row<8;row++){
                var p=f.profiles().get(row).source();var band=f.track().points().get(row).band();
                double offset=(a.y+slope*(p.anchorScreen().x-a.x)-p.anchorScreen().y)/(p.normalScreen().y-slope*p.normalScreen().x);
                double fraction=(p.anchorScreen().x+p.normalScreen().x*offset-a.x)/(z.x-a.x);
                valid&=fraction>=0&&fraction<=1&&offset>band.coreMinPx()&&offset<band.coreMaxPx();hits.add(offset);
            }
            if(valid)independentEdges.put(b+":"+c,List.copyOf(hits));
        }
        assertEquals(2,independentEdges.size(),"Independent line equation identifies two core-compatible bridges");
        assertEquals(independentEdges.keySet(),domain.edges().stream().map(e->e.leftState()+":"+e.rightState()).collect(java.util.stream.Collectors.toSet()));
        for(var e:domain.edges())for(int k=0;k<6;k++)assertEquals(independentEdges.get(e.leftState()+":"+e.rightState()).get(k),e.offsets().get(k),1e-12);
        var result=optimizer.optimizePrepared(f.track(),f.profiles(),1,JunctionContext.empty(),Map.of(),tube,original.approaches(),s,states,domain);
        double minimum=Double.POSITIVE_INFINITY;List<Double> best=null;
        for(int a=0;a<2;a++)for(int b=0;b<2;b++)for(int c=0;c<2;c++)for(int d=0;d<2;d++){
            if(!independentEdges.containsKey(b+":"+c))continue;
            var offsets=List.of(states.get(0).get(a),states.get(1).get(b),states.get(8).get(c),states.get(9).get(d));
            double cost=0;for(int k=0;k<4;k++)cost+=unary(optimizer,original,s.originalRow(k),offsets.get(k));
            var hits=independentEdges.get(b+":"+c);for(int k=0;k<6;k++)cost+=unary(optimizer,original,k+2,hits.get(k));
            cost+=geometryCost(f,s,tube,offsets);
            if(cost<minimum){minimum=cost;best=offsets;}
        }
        assertEquals(best,result.geometry().offsetsPx());assertEquals(minimum,result.geometry().totalCost(),1e-10);
        assertEquals(12,result.bridgeUnaryEvaluations());assertEquals(8,optimizer.preparedWork().retainedUnaryEvaluations());
        assertEquals(12,optimizer.preparedWork().bridgeUnaryEvaluations());
        assertEquals("UNSUPPORTED_CORRESPONDENCE",result.cleanupCorrespondenceStatus());
        assertEquals(result.geometry().costs().stream().mapToDouble(CorridorCenterlineOptimizer.CostRow::weightedTotal).sum()+result.bridgeUnaryCost(),result.geometry().totalCost());
    }

    private static double unary(CorridorCenterlineOptimizer optimizer,CorridorCenterlineOptimizer.OriginalDomain d,int row,double offset)throws Exception{
        var cleanup=GeometryCleanupConfig.disabled();var ripple=new UnsupportedRippleEvaluator().evaluate(d.track(),d.tube(),1,cleanup.rippleScaleMeters(),false);
        var method=Arrays.stream(CorridorCenterlineOptimizer.class.getDeclaredMethods()).filter(m->m.getName().equals("profileCost")).findFirst().orElseThrow();method.setAccessible(true);
        return (double)method.invoke(optimizer,d.track(),d.profiles(),row,offset,1.0,d.junctions(),d.scales(),d.tube(),d.approaches(),ripple.get(row),0.0,1.0);
    }
    /** Independent physical retained-edge/triple oracle; never calls the optimizer's geometry methods. */
    private static double geometryCost(Fixture f,CorridorWorkingSchedule s,LongitudinalCorridorTube tube,List<Double> offsets){
        double result=0;double[] spacing=new double[4],angle=new double[4],referenceAngle=new double[4];
        for(int k=1;k<4;k++){
            int row=s.originalRow(k),before=s.originalRow(k-1);var p=f.profiles().get(row);var b=f.profiles().get(before);
            spacing[k]=Math.max(1,p.source().anchorScreen().distance(b.source().anchorScreen()));
            double delta=(offsets.get(k)-offsets.get(k-1))/spacing[k];
            result+=(.06+.18*(1-f.track().points().get(row).band().localizationConfidence()))*delta*delta;
            angle[k]=heading(point(b,offsets.get(k-1)),point(p,offsets.get(k)));
            referenceAngle[k]=heading(point(b,tube.at(before).centerOffsetPx()),point(p,tube.at(row).centerOffsetPx()));
            if(k>=2){double residual=wrap(wrap(angle[k]-angle[k-1])-wrap(referenceAngle[k]-referenceAngle[k-1]))/Math.max(1,(spacing[k]+spacing[k-1])/2);
                double weight=(.34+.30*(1-f.track().points().get(row).band().localizationConfidence()))*(1.15+tube.at(row).motionSupport()*(.65-1.15));result+=weight*residual*residual;}
        }return result;
    }
    private static Point2D.Double point(CorridorProfile p,double offset){var a=p.source().anchorScreen();var n=p.source().normalScreen();return new Point2D.Double(a.x+n.x*offset,a.y+n.y*offset);}
    private static double heading(Point2D.Double a,Point2D.Double b){return Math.atan2(b.y-a.y,b.x-a.x);}
    private static double wrap(double a){while(a>Math.PI)a-=2*Math.PI;while(a< -Math.PI)a+=2*Math.PI;return a;}

    @Test void refusedEdgeDomainReturnsTypedSpentNoRouteAndNeverOriginalFallback(){
        var f=fixture();var optimizer=new CorridorCenterlineOptimizer();var s=CorridorWorkingSchedule.bridge(10,1,8);
        var tube=new CorridorTubeBuilder().build(f.track(),f.profiles(),1,Map.of());
        var states=new ArrayList<>(f.allowed());states.set(1,List.of(100.0));states.set(8,List.of(100.0));
        var domain=CorridorWorkingSchedule.BridgeDomain.intersections(s,f.track(),f.profiles(),states);
        assertTrue(domain.edges().isEmpty());
        var result=optimizer.optimizePrepared(f.track(),f.profiles(),1,JunctionContext.empty(),Map.of(),tube,
            new EndpointApproachModel(List.of()),s,states,domain);
        assertEquals(CorridorCenterlineOptimizer.SolverDisposition.NO_LEGAL_PATH,result.geometry().disposition());
        assertEquals(4,result.geometry().profileCostEvaluations());assertEquals(0,result.bridgeUnaryEvaluations());
        assertTrue(result.geometry().transitionEvaluations()>0);assertEquals("NO_LEGAL_PATH",optimizer.preparedWork().outcome());
    }

    @Test void cancelledPreparationRetainsCompletedUnaryWork(){
        var f=fixture();var count=new java.util.concurrent.atomic.AtomicInteger();
        var optimizer=new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(
            RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),()->count.incrementAndGet()>2);
        var s=CorridorWorkingSchedule.bridge(10,1,8);var tube=new CorridorTubeBuilder().build(f.track(),f.profiles(),1,Map.of());
        var domain=CorridorWorkingSchedule.BridgeDomain.intersections(s,f.track(),f.profiles(),f.allowed());
        assertThrows(java.util.concurrent.CancellationException.class,()->optimizer.optimizePrepared(f.track(),f.profiles(),1,
            JunctionContext.empty(),Map.of(),tube,new EndpointApproachModel(List.of()),s,f.allowed(),domain));
        assertEquals("CANCELLED",optimizer.preparedWork().outcome());assertEquals(2,optimizer.preparedWork().retainedUnaryEvaluations());
        assertEquals(0,optimizer.preparedWork().bridgeUnaryEvaluations());
    }

    @Test void stateCapRefusesBeforeUnaryAllocation(){
        var f=fixture();var optimizer=new CorridorCenterlineOptimizer();var s=CorridorWorkingSchedule.bridge(10,1,8);
        var tube=new CorridorTubeBuilder().build(f.track(),f.profiles(),1,Map.of());var states=new ArrayList<>(f.allowed());
        states.set(0,java.util.stream.IntStream.range(0,97).mapToObj(i->(double)i).toList());
        var domain=CorridorWorkingSchedule.BridgeDomain.intersections(s,f.track(),f.profiles(),states);
        assertThrows(CorridorResourceLimitException.class,()->optimizer.optimizePrepared(f.track(),f.profiles(),1,
            JunctionContext.empty(),Map.of(),tube,new EndpointApproachModel(List.of()),s,states,domain));
        assertEquals("RESOURCE_LIMIT",optimizer.preparedWork().outcome());assertEquals(0,optimizer.preparedWork().retainedUnaryEvaluations());
    }

    @Test void detachedProtectionIncludesUnsampledOccurrencesAndEndpointDependencies(){
        var f=fixture(true);var tube=foldingReference(f);var optimizer=new CorridorCenterlineOptimizer();
        var original=optimizer.prepareOriginalDomain(f.track(),f.profiles(),1,JunctionContext.empty(),Map.of(),tube);
        double end=f.profiles().get(9).source().cumulativeGroundDistanceMeters();
        double between=f.profiles().get(4).source().cumulativeGroundDistanceMeters()+.0001;
        var blocked=admission(f,tube,original.approaches(),new CorridorWorkingSchedule.SourceProtection(0,end,List.of(between)),(a,b)->true);
        assertEquals("REFUSED_SOURCE_PROTECTION_OR_SPAN",blocked.status());assertEquals(0,blocked.pairTests());
        var guide=new EndpointApproachModel(List.of(new EndpointApproachModel.EndpointApproach(0,1,9,true,"test",
            List.of(new EndpointApproachModel.GuideTarget(4,0,1,false)))));
        var dependency=admission(f,tube,guide,new CorridorWorkingSchedule.SourceProtection(0,end,List.of()),(a,b)->true);
        assertEquals("REFUSED_ENDPOINT_DEPENDENCY",dependency.status());assertEquals(0,dependency.pairTests());
        var valid=admission(f,tube,original.approaches(),new CorridorWorkingSchedule.SourceProtection(0,end,List.of()),(a,b)->true);
        assertEquals("ADMITTED",valid.status(),()->valid+" collars="+valid.schedule().leftOriginalRow()+":"+valid.schedule().rightOriginalRow()
            +" centers="+f.track().points().entrySet().stream().map(e->e.getKey()+"="+point(f.profiles().get(e.getKey()),e.getValue().band().centerOffsetPx())).toList());assertTrue(valid.pairTests()>0);assertTrue(valid.nativeChecks()>0);
        var noNative=admission(f,tube,original.approaches(),new CorridorWorkingSchedule.SourceProtection(0,end,List.of()),(a,b)->false);
        assertEquals("REFUSED_NO_ADMITTED_EDGES",noNative.status());assertEquals(valid.pairTests(),noNative.pairTests());assertEquals(valid.nativeChecks(),noNative.nativeChecks());
    }

    private static CorridorWorkingSchedule.Preparation admission(Fixture f,LongitudinalCorridorTube tube,EndpointApproachModel approaches,
        CorridorWorkingSchedule.SourceProtection protection,CorridorWorkingSchedule.SegmentAuthority authority){
        return CorridorWorkingSchedule.prepare(f.track(),f.profiles(),tube,f.allowed(),Map.of(),approaches,JunctionContext.empty(),
            RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),1,protection,authority,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE);
    }
    /** Analytic aliasing reference over a single straight observed world ridge; source Y is monotone. */
    private static LongitudinalCorridorTube foldingReference(Fixture f){
        var original=new CorridorTubeBuilder().build(f.track(),f.profiles(),1,Map.of());var slices=new ArrayList<CorridorTubeSlice>();
        for(int row=0;row<10;row++){
            var p=f.profiles().get(row).source();double y=26+(row==3||row==6?.3:row==4||row==7?-.3:0);
            double center=(y-p.anchorScreen().y)/p.normalScreen().y;var s=original.at(row);
            slices.add(new CorridorTubeSlice(row,s.distanceMeters(),center,s.tangentOffsetPerMeter(),center,s.localTangentOffsetPerMeter(),center,
                s.stabilityTangentOffsetPerMeter(),s.stabilityUncertaintyPx(),s.motionSupport(),s.motionSupportReason(),s.curvatureOffsetPerMeterSquared(),
                s.coreMinPx(),s.coreMaxPx(),s.shoulderMinPx(),s.shoulderMaxPx(),s.uncertaintyPx(),s.confidence(),s.scaleConflict(),s.parentMerge(),
                s.rawCenterPx(),s.lightCenterPx(),s.standardCenterPx(),s.observed()));
        }return new LongitudinalCorridorTube(slices);
    }

    @Test void explicitCorrespondencePathPreservesSupportedArcThinRivalsWeakBendsAndSparseEvidence()throws Exception{
        noTriggerControl(PhysicalGeometryFixture.supportedOppositeArcProfiles(),PhysicalGeometryFixture.NATIVE_PITCH,JunctionContext.empty());
        for(double amplitude:List.of(.95,.35)){
            var frame=PhysicalGeometryFixture.parallelFrame(amplitude);
            noTriggerControl(frame.profileSet().levelZeroProfiles(),frame.sourcePixelPitch(),JunctionContext.empty());
        }
        var rasterFixtures=new CorridorRasterIntegrationTest();
        @SuppressWarnings("unchecked") var source=(List<Point2D.Double>)fixtureCall(rasterFixtures,"sourcePolyline");
        var fixed=new JunctionContext(List.of(new EndpointConstraint(0,1,true,false,0,0,6),new EndpointConstraint(60,2,true,false,0,0,6)));
        var images=new ArrayList<BufferedImage>();images.add((BufferedImage)fixtureCall(rasterFixtures,"complementarySparseRaster"));
        images.add((BufferedImage)fixtureCall(rasterFixtures,"raster",(java.util.function.IntToDoubleFunction)(i->8*Math.sin(i*2*Math.PI/60)),.16,false,false));
        images.add((BufferedImage)fixtureCall(rasterFixtures,"raster",(java.util.function.IntToDoubleFunction)(i->{int phase=i%20;double rising=-8.0+16.0*phase/20;return(i/20)%2==0?rising:-rising;}),.16,false,false));
        for(var image:images){var profiles=new RenderedHeatmapSampler().sampleProfilesOnScaledRaster(image,source,18,1,"hot",1,1,IntensitySamplingMode.DIRECT_VALUE);noTriggerControl(profiles,1,fixed);}
    }
    private static void noTriggerControl(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,double pitch,JunctionContext junctions){
        var frame=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var baseline=new CorridorAwareTracker(new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(frame,()->false),
            new CorridorTubeBuilder(CorridorTubeBuilder.MotionPolicy.TARGET_OWNED_APEX)).trackDetailed(profiles,pitch,junctions);
        var tracker=CorridorAwareTracker.withTargetOwnedBridgeCorrespondence(
            new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(frame,()->false),original->{
                double first=original.profiles().get(0).source().cumulativeGroundDistanceMeters();
                double last=original.profiles().get(original.profiles().size()-1).source().cumulativeGroundDistanceMeters();
                return CorridorWorkingSchedule.prepare(original.track(),original.profiles(),original.tube(),original.states(),original.scales(),
                    original.approaches(),original.junctions(),frame,pitch,new CorridorWorkingSchedule.SourceProtection(first,last,List.of(first,last)),
                    (a,b)->{fail("A no-trigger preservation control must not acquire bridge authority");return false;},()->false);
            });
        var experiment=tracker.trackDetailed(profiles,pitch,junctions);
        assertFalse(baseline.candidates().isEmpty());assertEquals(baseline.tracks(),experiment.tracks());assertEquals(baseline.tubes(),experiment.tubes());
        assertEquals(baseline.optimizations(),experiment.optimizations());assertEquals(baseline.scaleEvidence(),experiment.scaleEvidence());
        assertEquals(baseline.candidates().size(),experiment.candidates().size());
        for(int i=0;i<baseline.candidates().size();i++){var a=baseline.candidates().get(i);var b=experiment.candidates().get(i);
            assertEquals(a.id(),b.id());assertEquals(a.score(),b.score());assertEquals(a.screenPoints(),b.screenPoints());assertEquals(a.offsetsPx(),b.offsetsPx());assertEquals(a.evidence(),b.evidence());
            var x=a.cleanupEvidence();var y=b.cleanupEvidence();assertEquals(x.status(),y.status());assertEquals(x.profiles(),y.profiles());
            assertEquals(x.samplingFrame().detectorMode(),y.samplingFrame().detectorMode());assertEquals(x.samplingFrame().groundMetersPerRasterPixel(),y.samplingFrame().groundMetersPerRasterPixel());
            assertEquals(x.samplingFrame().profiles().size(),y.samplingFrame().profiles().size());
            for(int row=0;row<x.samplingFrame().profiles().size();row++){
                var u=x.samplingFrame().profiles().get(row);var v=y.samplingFrame().profiles().get(row);
                assertEquals(u.profileIndex(),v.profileIndex());assertEquals(u.cumulativeGroundDistanceMeters(),v.cumulativeGroundDistanceMeters());assertEquals(u.anchorWithinRaster(),v.anchorWithinRaster());assertEquals(u.sourcePixelPitchRasterPx(),v.sourcePixelPitchRasterPx());assertEquals(u.projectedLateralTransform(),v.projectedLateralTransform());assertEquals(u.sampleCount(),v.sampleCount());
                for(int sample=0;sample<u.sampleCount();sample++){assertEquals(u.offsetPxAt(sample),v.offsetPxAt(sample));assertEquals(u.nativeIntensityAt(sample),v.nativeIntensityAt(sample));assertEquals(u.lightFilteredIntensityAt(sample),v.lightFilteredIntensityAt(sample));assertEquals(u.standardFilteredIntensityAt(sample),v.standardFilteredIntensityAt(sample));assertEquals(u.insideRasterAt(sample),v.insideRasterAt(sample));}
            }}
        assertFalse(tracker.correspondencePreparations().isEmpty());assertTrue(tracker.correspondencePreparations().values().stream().allMatch(p->p.status().equals("NO_TRIGGER")));
    }
    private static Object fixtureCall(Object target,String name,Object...args)throws Exception{
        var method=Arrays.stream(target.getClass().getDeclaredMethods()).filter(m->m.getName().equals(name)&&m.getParameterCount()==args.length).findFirst().orElseThrow();method.setAccessible(true);return method.invoke(target,args);
    }

    @Test void symmetricPreparedGraphKeepsDeterministicTieOrderAndZeroEdgesRemainForFinalValidation(){
        var profiles=new ArrayList<CorridorProfile>();var points=new LinkedHashMap<Integer,CorridorTrackPoint>();
        double[] x={0,1,1.4,1.3,1.7,2,3};double chainage=0;
        for(int row=0;row<7;row++){
            if(row>0)chainage+=Math.abs(x[row]-x[row-1]);
            var band=new CorridorBand("b"+row,0,-4,4,-3,3,List.of(0.0),.8,.02,1,1,1,.5,false,List.of());
            var samples=new ArrayList<RenderedHeatmapSampler.IntensitySample>();for(int offset=-6;offset<=6;offset++)samples.add(new RenderedHeatmapSampler.IntensitySample(offset,.8,.8,.8,true));
            var profile=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new org.openstreetmap.josm.data.coor.EastNorth(x[row],0),x[row],0,chainage),new Point2D.Double(0,1),List.of(),true,samples);
            profiles.add(new CorridorProfile(row,profile,List.of(band),.8,.02,.78,true));points.put(row,new CorridorTrackPoint(row,band,false));
        }
        var track=new CorridorTrack("symmetric",points,7,1,false,List.of(),"");var s=CorridorWorkingSchedule.bridge(7,1,5);
        var tube=new CorridorTubeBuilder().build(track,profiles,1,Map.of());var allowed=new ArrayList<List<Double>>();for(int row=0;row<7;row++)allowed.add(row==0||row==6?List.of(-1.0,1.0):List.of(0.0));
        var frame=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);var optimizer=new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(frame,()->false);
        var domain=CorridorWorkingSchedule.BridgeDomain.intersections(s,track,profiles,allowed);
        var result=optimizer.optimizePrepared(track,profiles,1,JunctionContext.empty(),Map.of(),tube,new EndpointApproachModel(List.of()),s,allowed,domain);
        assertEquals(List.of(-1.0,0.0,0.0,-1.0),result.geometry().offsetsPx());
        allowed.set(0,List.of(0.0));allowed.set(6,List.of(0.0));var old=profiles.get(0);var source=old.source();
        var duplicate=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new org.openstreetmap.josm.data.coor.EastNorth(1,0),1,0,0),source.normalScreen(),source.peaks(),true,source.intensitySamples());
        profiles.set(0,new CorridorProfile(0,duplicate,old.bands(),old.maxIntensity(),old.noiseFloor(),old.prominence(),true));
        for(int row=1;row<7;row++){
            var p=profiles.get(row);var u=p.source();var anchor=new ProfileSamplingAnchor(new org.openstreetmap.josm.data.coor.EastNorth(x[row],0),x[row],0,u.cumulativeGroundDistanceMeters()-1);
            var shifted=new RenderedHeatmapSampler.CrossSectionProfile(anchor,u.normalScreen(),u.peaks(),true,u.intensitySamples());
            profiles.set(row,new CorridorProfile(row,shifted,p.bands(),p.maxIntensity(),p.noiseFloor(),p.prominence(),true));
        }
        tube=new CorridorTubeBuilder().build(track,profiles,1,Map.of());domain=CorridorWorkingSchedule.BridgeDomain.intersections(s,track,profiles,allowed);
        var zero=optimizer.optimizePrepared(track,profiles,1,JunctionContext.empty(),Map.of(),tube,new EndpointApproachModel(List.of()),s,allowed,domain);
        assertEquals(zero.geometry().screenPoints().get(0),zero.geometry().screenPoints().get(1));assertEquals(0,optimizer.hardGeometryWork().rejectedTriples());
    }

    @Test void actualRivalCensoredAndBoundaryClaimsRefuseButParentEnvelopeDoesNotEraseLeaf(){
        var f=fixture(true);var tube=foldingReference(f);double end=f.profiles().get(9).source().cumulativeGroundDistanceMeters();
        var protection=new CorridorWorkingSchedule.SourceProtection(0,end,List.of());var approaches=new EndpointApproachModel(List.of());
        var b=f.track().points().get(4).band();
        for(var other:List.of(copyBand(b,"rival",false,List.of(),b.boundaryCompleteness()),
            copyBand(b,"censored",false,List.of(),CorridorBand.BoundaryCompleteness.CORE_CENSORED))){
            var profiles=new ArrayList<>(f.profiles());var p=profiles.get(4);profiles.set(4,new CorridorProfile(4,p.source(),List.of(b,other),p.maxIntensity(),p.noiseFloor(),p.prominence(),p.supported()));
            var competing=new Fixture(f.sampled(),profiles,f.track(),f.allowed());var result=admission(competing,tube,approaches,protection,(a,z)->true);
            assertEquals("REFUSED_NO_ADMITTED_EDGES",result.status());assertEquals(0,result.nativeChecks());
        }
        var profiles=new ArrayList<>(f.profiles());var p=profiles.get(4);profiles.set(4,new CorridorProfile(4,p.source(),List.of(b,copyBand(b,"parent",true,List.of(b.id()),b.boundaryCompleteness())),p.maxIntensity(),p.noiseFloor(),p.prominence(),p.supported()));
        assertEquals("ADMITTED",admission(new Fixture(f.sampled(),profiles,f.track(),f.allowed()),tube,approaches,protection,(a,z)->true).status());
        var states=new ArrayList<>(f.allowed());states.set(1,List.of(f.track().points().get(1).band().coreMinPx()));
        assertEquals("REFUSED_NO_ADMITTED_EDGES",admission(new Fixture(f.sampled(),f.profiles(),f.track(),states),tube,approaches,protection,(a,z)->true).status());
    }

    @Test void longCandidateRefusesEvenWhenMeasuredCenterChordIsShort(){
        var f=fixture(true,1,true);var tube=foldingReference(f);var states=new ArrayList<>(f.allowed());
        double left=f.allowed().get(1).get(0)-2,right=f.allowed().get(8).get(0)+2;
        assertTrue(left>f.track().points().get(1).band().coreMinPx());assertTrue(right<f.track().points().get(8).band().coreMaxPx());
        states.set(1,List.of(left));states.set(8,List.of(right));
        var narrowed=new Fixture(f.sampled(),f.profiles(),f.track(),states);
        double end=f.profiles().get(9).source().cumulativeGroundDistanceMeters();
        var result=admission(narrowed,tube,new EndpointApproachModel(List.of()),new CorridorWorkingSchedule.SourceProtection(0,end,List.of()),(a,b)->{fail("Overlong actual bridge cannot reach native admission");return false;});
        assertEquals("REFUSED_NO_ADMITTED_EDGES",result.status());assertEquals(1,result.pairTests());assertEquals(0,result.nativeChecks());
    }

    private static CorridorBand copyBand(CorridorBand b,String id,boolean parent,List<String> children,CorridorBand.BoundaryCompleteness completeness){
        return new CorridorBand(id,b.centerOffsetPx(),b.shoulderMinPx(),b.shoulderMaxPx(),b.coreMinPx(),b.coreMaxPx(),b.nestedCentersPx(),
            b.peakIntensity(),b.noiseFloor(),b.valleyRatio(),b.gradientStrength(),b.gradientBalance(),b.scaleAgreement(),b.signalExistenceConfidence(),
            b.localizationConfidence(),b.uncertaintyPx(),parent,children,completeness,
            completeness==CorridorBand.BoundaryCompleteness.COMPLETE?CorridorBand.BoundarySide.NONE:CorridorBand.BoundarySide.RIGHT);
    }

    @Test void nativeFrameRotationReflectionTranslationAndScalePreserveAdmittedOriginalEdgeIds(){
        var f=fixture(true);var tube=foldingReference(f);double end=f.profiles().get(9).source().cumulativeGroundDistanceMeters();
        List<String> expected=null;
        for(var frame:List.of(RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),
            RasterMetricTransform.metricGrid(new MetricPoint(0,0),0,1,-1,0),RasterMetricTransform.metricGrid(new MetricPoint(0,0),-1,0,0,1),
            RasterMetricTransform.metricGrid(new MetricPoint(1000,2000),1,0,0,1),RasterMetricTransform.metricGrid(new MetricPoint(0,0),2,0,0,2))){
            double pitch=frame.xAxisEastMetersPerSourcePixel()==2?2:1;
            var result=CorridorWorkingSchedule.prepare(f.track(),f.profiles(),tube,f.allowed(),Map.of(),new EndpointApproachModel(List.of()),
                JunctionContext.empty(),frame,pitch,new CorridorWorkingSchedule.SourceProtection(0,end,List.of()),(a,b)->true,()->false);
            assertEquals("ADMITTED",result.status());var ids=result.domain().edges().stream().map(e->e.leftState()+":"+e.rightState()).toList();
            if(expected==null)expected=ids;else assertEquals(expected,ids);
        }
    }

    @Test void mappedOwnershipUsesOriginalGapAndActualNativeAuthorityRejectsHoleAndDecisionBoundary(){
        var f=fixture(true);var s=CorridorWorkingSchedule.bridge(10,1,8);var trackPoints=new LinkedHashMap<>(f.track().points());
        var row=trackPoints.get(8);trackPoints.put(8,new CorridorTrackPoint(8,row.band(),true,CorridorPointSupport.BOUNDED_INTERPOLATION));
        var track=new CorridorTrack("mapped",trackPoints,10,1,false,List.of(),"");
        var offsets=s.originalRows().stream().map(i->f.allowed().get(i).get(0)).toList();
        var metric=s.originalRows().stream().map(i->point(f.profiles().get(i),f.allowed().get(i).get(0))).map(p->new MetricPoint(p.x,p.y)).toList();
        var evidence=nativeEvidence(false);
        var support=org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorEngineAdapter.support(track,offsets,metric,evidence,"native",true,s);
        assertEquals(List.of(ObservationOwnership.FIXED_TOPOLOGY_ONLY,ObservationOwnership.DIRECT_TWO_SIDED,ObservationOwnership.INFERRED_GAP,ObservationOwnership.FIXED_TOPOLOGY_ONLY),support);
        var nativeAuthority=CorridorWorkingSchedule.nativeAuthority(evidence,"native",()->false);
        assertTrue(nativeAuthority.admits(metric.get(1),metric.get(2)));
        assertFalse(nativeAuthority.admits(new MetricPoint(0,26),metric.get(2)),"Actual decision boundary remains binding");
        var hole=CorridorWorkingSchedule.nativeAuthority(nativeEvidence(true),"native",()->false);
        assertFalse(hole.admits(metric.get(1),metric.get(2)),"A touched invalid interpolation cell cannot be skipped");
    }

    private static EvidenceSnapshot nativeEvidence(boolean hole){
        int width=80,height=48;double[] values=new double[width*height];Arrays.fill(values,.9);boolean[] valid=new boolean[values.length];Arrays.fill(valid,true);
        if(hole)valid[26*width+40]=false;
        var scalar=new ScalarEvidenceField(width,height,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50,14),new GeographicPoint(49.99,13.99),new GeographicPoint(50.01,14.02));
        return new EvidenceSnapshot("native-test",frame,RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),EvidenceResolution.nativeSource(1,1),
            MetricRegion.rectangle(1,1,78,46),MetricRegion.rectangle(-.5,-.5,79.5,47.5),Map.of("native",scalar),"synthetic-source");
    }
}
