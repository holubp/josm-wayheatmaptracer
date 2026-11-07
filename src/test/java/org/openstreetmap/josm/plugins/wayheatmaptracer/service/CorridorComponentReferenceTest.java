package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;

/** Independent analytic world ridge and factual-source-chainage reference oracles. */
class CorridorComponentReferenceTest {
    @Test void nativeProperWrapperMatchesClassifierAndExcludesTouchesOverlapsNearAndNonfinite()throws Exception {
        var a=new MetricPoint(0,0);var b=new MetricPoint(2,2);var c=new MetricPoint(0,2);var d=new MetricPoint(2,0);
        assertTrue(FinalGeometryEvaluator.properIntersection(a,b,c,d));
        assertFalse(FinalGeometryEvaluator.properIntersection(a,b,b,new MetricPoint(3,1)));
        assertFalse(FinalGeometryEvaluator.properIntersection(a,b,new MetricPoint(1,1),new MetricPoint(3,3)));
        assertFalse(FinalGeometryEvaluator.properIntersection(a,b,new MetricPoint(0,1e-12),new MetricPoint(2,2+1e-12)));
        assertThrows(NullPointerException.class,()->FinalGeometryEvaluator.properIntersection(null,b,c,d));
        assertThrows(IllegalArgumentException.class,()->new MetricPoint(Double.NaN,0));
        for(var left:List.of(a,b,c,d))for(var right:List.of(a,b,c,d))for(var third:List.of(a,b,c,d))for(var fourth:List.of(a,b,c,d)){
            var nativeMethod=FinalGeometryEvaluator.class.getDeclaredMethod("intersection",MetricPoint.class,MetricPoint.class,MetricPoint.class,MetricPoint.class);nativeMethod.setAccessible(true);
            assertEquals(nativeMethod.invoke(null,left,right,third,fourth).toString().equals("PROPER"),FinalGeometryEvaluator.properIntersection(left,right,third,fourth));
        }
    }
    @Test void protectedAndMissingDirectComponentRefuseWithoutChangingAnyRecord()throws Exception {
        var fixture=arc();var original=owned().build(fixture.track,fixture.profiles,1,Map.of());
        var protectedResult=component(Set.of(0,15,30)).buildComponentReference(fixture.track,fixture.profiles,1,Map.of());
        assertEquals(original,protectedResult.tube());assertTrue(protectedResult.status().startsWith("REFUSED"));
        var points=new LinkedHashMap<>(fixture.track.points());points.put(15,new CorridorTrackPoint(15,points.get(15).band(),true,CorridorPointSupport.BOUNDED_INTERPOLATION));
        var sparse=new CorridorTrack("missing-component",points,31,1,false,List.of(),"");
        var sparseOriginal=owned().build(sparse,fixture.profiles,1,Map.of());var sparseResult=component(Set.of(0,30)).buildComponentReference(sparse,fixture.profiles,1,Map.of());
        assertEquals(sparseOriginal,sparseResult.tube());
    }
    @Test void noTriggerCurvedAndShortControlsRetainExactRecordsAndOptimizerOutputs()throws Exception {
        var fixture=arc();var smooth=new ArrayList<CorridorProfile>();var track=new LinkedHashMap<Integer,CorridorTrackPoint>();double distance=0;Point2D.Double previous=null;
        for(int i=0;i<31;i++){
            double theta=.03*i;var anchor=new Point2D.Double(4*StrictMath.cos(theta),4*StrictMath.sin(theta));if(previous!=null)distance+=anchor.distance(previous);previous=anchor;
            var band=fixture.profiles.get(i).bands().get(0);var zero=new CorridorBand(band.id(),0,-2,2,-.5,.5,List.of(0.0),.8,.02,1,.95,.95,.5,false,List.of());
            var source=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(anchor.x,anchor.y),anchor.x,anchor.y,distance),new Point2D.Double(StrictMath.cos(theta),StrictMath.sin(theta)),List.of(new RenderedHeatmapSampler.CrossSectionPeak(0,.8)),true,List.of());
            smooth.add(new CorridorProfile(i,source,List.of(zero),.8,.02,.78,true));track.put(i,new CorridorTrackPoint(i,zero,false));
        }
        var route=new CorridorTrack("smooth",track,31,1,false,List.of(),"");var original=owned().build(route,smooth,1,Map.of());var result=component(Set.of(0,30)).buildComponentReference(route,smooth,1,Map.of());
        assertEquals(original,result.tube());assertTrue(result.components().isEmpty());assertTrue(result.contactTests()>0);
        var optimizer=new CorridorCenterlineOptimizer();var old=optimizer.optimize(route,smooth,1,JunctionContext.empty(),Map.of(),original,GeometryCleanupConfig.disabled());var actual=optimizer.optimize(route,smooth,1,JunctionContext.empty(),Map.of(),result.tube(),GeometryCleanupConfig.disabled());
        assertEquals(old,actual);
        assertEquals(new LongitudinalCorridorTube(List.of()),component(Set.of()).build(new CorridorTrack("empty",Map.of(),0,0,false,List.of(),""),List.of(),1,Map.of()));
    }
    @Test void metricRotationTranslationAndReflectionPreserveSharedWeightFits()throws Exception {
        var fixture=arc();var baseline=component(Set.of(0,30)).buildComponentReference(fixture.track,fixture.profiles,1,Map.of());
        assertEquals("COMPONENT_LOCAL_METRIC_XY",baseline.status());
        for(double angle:List.of(.73,2.1))for(double reflect:List.of(-1.0,1.0)){
            double c=StrictMath.cos(angle),s=StrictMath.sin(angle);var frame=RasterMetricTransform.metricGrid(new MetricPoint(30,-20),c,s,-reflect*s,reflect*c);
            var rotated=new CorridorTubeBuilder(frame,1,Set.of(0,30)).buildComponentReference(fixture.track,fixture.profiles,1,Map.of());
            assertEquals(baseline.components(),rotated.components());assertEquals(baseline.status(),rotated.status());
            for(int i=0;i<31;i++){assertEquals(baseline.tube().at(i).centerOffsetPx(),rotated.tube().at(i).centerOffsetPx(),1e-9);assertEquals(baseline.tube().at(i).tangentOffsetPerMeter(),rotated.tube().at(i).tangentOffsetPerMeter(),1e-9);assertEquals(baseline.tube().at(i).confidence(),rotated.tube().at(i).confidence(),1e-9);assertEquals(baseline.tube().at(i).uncertaintyPx(),rotated.tube().at(i).uncertaintyPx(),1e-9);}
            for(int i=0;i<baseline.fits().size();i++)for(int j=0;j<baseline.fits().get(i).finalSharedWeights().size();j++)assertEquals(baseline.fits().get(i).finalSharedWeights().get(j),rotated.fits().get(i).finalSharedWeights().get(j),1e-9);
        }
    }
    @Test void unchangedStrongFaintSparseSwitchbackAndRepeatedCurvedPassRemainHardGates()throws Exception {
        var controls=new CorridorBoundedApexMotionTest();var field=CorridorBoundedApexMotionTest.class.getDeclaredField("controlBuilder");field.setAccessible(true);
        field.set(controls,new CorridorTubeBuilder(RasterMetricTransform.visible(new MetricPoint(0,0),1),1,Set.of(0,30,60)));
        controls.optInStrongArcThinParallelSparseAndNoSignalControls();controls.optInWeakSineSwitchbackAndRepeatedCurvedPassKeepShapeAndAnchors();
    }
    @Test void reverseRayOrientationAndProfileOrderPreservePhysicalCorrection()throws Exception {
        var fixture=arc();var ordinary=component(Set.of(0,30)).buildComponentReference(fixture.track,fixture.profiles,1,Map.of());
        var profiles=new ArrayList<CorridorProfile>();var track=new LinkedHashMap<Integer,CorridorTrackPoint>();double distance=0;Point2D.Double previous=null;
        for(int i=0;i<31;i++){
            var old=fixture.profiles.get(30-i);var p=old.source();var a=p.anchorScreen();var n=p.normalScreen();if(previous!=null)distance+=a.distance(previous);previous=a;
            var b=old.bands().get(0);double center=-b.centerOffsetPx();var band=new CorridorBand(b.id(),center,-b.shoulderMaxPx(),-b.shoulderMinPx(),-b.coreMaxPx(),-b.coreMinPx(),List.of(center),b.peakIntensity(),b.noiseFloor(),b.valleyRatio(),b.signalExistenceConfidence(),b.localizationConfidence(),b.uncertaintyPx(),false,List.of());
            var source=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(a.x,a.y),a.x,a.y,distance),new Point2D.Double(-n.x,-n.y),List.of(new RenderedHeatmapSampler.CrossSectionPeak(center,.8)),true,List.of());
            profiles.add(new CorridorProfile(i,source,List.of(band),.8,.02,.78,true));track.put(i,new CorridorTrackPoint(i,band,false));
        }
        var reversed=component(Set.of(0,30)).buildComponentReference(new CorridorTrack("reverse",track,31,1,false,List.of(),""),profiles,1,Map.of());
        assertEquals(ordinary.status(),reversed.status());for(int i=0;i<31;i++){
            var expected=point(fixture.profiles.get(30-i),ordinary.tube().at(30-i).centerOffsetPx());var actual=point(profiles.get(i),reversed.tube().at(i).centerOffsetPx());assertEquals(expected.xMeters(),actual.xMeters(),1e-8);assertEquals(expected.yMeters(),actual.yMeters(),1e-8);
        }
    }
    @Test void radialOutlierWeightsAreSharedAndRotationInvariant()throws Exception {
        var fixture=arc();var profiles=new ArrayList<>(fixture.profiles);var points=new LinkedHashMap<>(fixture.track.points());var p=profiles.get(14);var b=p.bands().get(0);double center=b.centerOffsetPx()+4;
        var band=new CorridorBand(b.id(),center,center-2,center+2,center-.5,center+.5,List.of(center),.8,.02,1,.95,.95,.5,false,List.of());
        var source=new RenderedHeatmapSampler.CrossSectionProfile(p.source().samplingAnchor(),p.source().normalScreen(),List.of(new RenderedHeatmapSampler.CrossSectionPeak(center,.8)),true,List.of());profiles.set(14,new CorridorProfile(14,source,List.of(band),.8,.02,.78,true));points.put(14,new CorridorTrackPoint(14,band,false));
        var track=new CorridorTrack("one-radial-outlier",points,31,1,false,List.of(),"");var first=component(Set.of(0,30)).buildComponentReference(track,profiles,1,Map.of());
        double angle=.71,c=StrictMath.cos(angle),s=StrictMath.sin(angle);var rotated=new CorridorTubeBuilder(RasterMetricTransform.metricGrid(new MetricPoint(20,-30),c,s,-s,c),1,Set.of(0,30)).buildComponentReference(track,profiles,1,Map.of());
        assertEquals(first.status(),rotated.status());assertFalse(first.fits().isEmpty());
        boolean downweighted=false;for(int i=0;i<first.fits().size();i++){var weights=first.fits().get(i).finalSharedWeights();double maximum=weights.stream().mapToDouble(Double::doubleValue).max().orElseThrow();for(int j=0;j<weights.size();j++){assertEquals(weights.get(j),rotated.fits().get(i).finalSharedWeights().get(j),1e-8);downweighted|=weights.get(j)<maximum*.5;}}
        assertTrue(downweighted,"Radial outlier must actually exercise the common Huber attenuation");
    }
    @Test void shortSingularAndOffNormalFitsFailClosedWithFactualDiagnostics()throws Exception {
        var builder=component(Set.of());var profiles=new ArrayList<CorridorProfile>();var observations=new ArrayList<Object>();double[] distances={0,10,20};
        for(int i=0;i<3;i++){
            double x=i==1?10:0;var band=new CorridorBand("degenerate"+i,0,-2,2,-.5,.5,List.of(0.0),.8,.02,1,.95,.95,.5,false,List.of());
            var source=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(x,0),x,0,distances[i]),new Point2D.Double(0,1),List.of(new RenderedHeatmapSampler.CrossSectionPeak(0,.8)),true,List.of());
            profiles.add(new CorridorProfile(i,source,List.of(band),.8,.02,.78,true));observations.add(call(builder,"observation",new CorridorTrackPoint(i,band,false),profiles.get(i),distances,1.0,Map.of()));
        }
        var fits=new ArrayList<CorridorTubeBuilder.WorldFitDiagnostic>();
        var miss=assertThrows(InvocationTargetException.class,()->call(builder,"worldRegression",observations,1,"off-normal",profiles,distances,1.0,profiles.get(1).bands().get(0),fits));assertInstanceOf(IllegalArgumentException.class,miss.getCause());assertTrue(fits.get(0).projectionMissMeters()>1);
        var shortWindow=assertThrows(InvocationTargetException.class,()->call(builder,"worldRegression",observations.subList(0,1),1,"short",profiles,distances,1.0,profiles.get(1).bands().get(0),new ArrayList<>()));assertInstanceOf(IllegalArgumentException.class,shortWindow.getCause());
        var singular=assertThrows(InvocationTargetException.class,()->call(builder,"weightedWorld",List.of(observations.get(0),observations.get(0)),new double[]{1,1},new double[]{0,1},new double[]{0,1},0.0));assertInstanceOf(IllegalArgumentException.class,singular.getCause());
    }
    @Test void scalarRayReferenceFoldsAroundSupportedAnalyticWorldArc()throws Exception {
        var fixture=arc();var original=owned().build(fixture.track,fixture.profiles,1,Map.of());
        assertEquals(0,properCount(fixture.profiles.stream().map(p->point(p,0)).toList()),"Analytic source has no inherited contact");
        assertTrue(properCount(points(fixture.profiles,original))>0,"Scalar reference must independently demonstrate physical folding");
        var result=component(Set.of(0,30)).buildComponentReference(fixture.track,fixture.profiles,1,Map.of());var actual=result.tube();
        assertEquals(0,properCount(points(fixture.profiles,actual)),"World-reference correction must remove the demonstrated reference fold");
        assertTrue(FinalGeometryEvaluator.backtrackFindings(points(fixture.profiles,actual)).isEmpty());
        assertNotEquals(original,actual);
        for(int i=0;i<31;i++){
            int row=i;if(result.components().stream().noneMatch(c->row>=c.firstProfile()&&row<=c.lastProfile()))assertEquals(original.at(i),actual.at(i),"Remote valid arc and literal collars must be exact");
            var slice=actual.at(i);assertTrue(Double.isFinite(slice.tangentOffsetPerMeter()));assertTrue(Double.isFinite(slice.uncertaintyPx()));assertTrue(Double.isFinite(slice.confidence()));
            if(!slice.equals(original.at(i)))assertTrue(Math.abs(StrictMath.hypot(point(fixture.profiles.get(i),slice.centerOffsetPx()).xMeters(),point(fixture.profiles.get(i),slice.centerOffsetPx()).yMeters())-4)<.25,"Analytic radius must survive complete-field reconstruction");
        }
    }
    @Test void anisotropicMovingFrameSlopeIncludesNormalScaleDerivative()throws Exception {
        var profiles=new ArrayList<CorridorProfile>();var track=new LinkedHashMap<Integer,CorridorTrackPoint>();
        for(int i=0;i<9;i++){
            double s=-2+i*.5,d=StrictMath.hypot(s/2,1),nx=(s/2)/d,ny=-1/d;
            var band=new CorridorBand("line"+i,d,d-2,d+2,d-.5,d+.5,List.of(d),.8,.02,1,.95,.95,.5,false,List.of());
            var source=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(s,1),s/2,1,i*.5),new Point2D.Double(nx,ny),List.of(new RenderedHeatmapSampler.CrossSectionPeak(d,.8)),true,List.of());
            profiles.add(new CorridorProfile(i,source,List.of(band),.8,.02,.78,true));track.put(i,new CorridorTrackPoint(i,band,false));
        }
        var frame=RasterMetricTransform.metricGrid(new MetricPoint(0,0),2,0,0,1);var builder=new CorridorTubeBuilder(frame,1,Set.of());double[] distances=new double[9];for(int i=0;i<9;i++)distances[i]=i*.5;
        var observations=new ArrayList<Object>();for(var p:track.values())observations.add(call(builder,"observation",p,profiles.get(p.profileIndex()),distances,1.0,Map.of()));
        var diagnostics=new ArrayList<CorridorTubeBuilder.WorldFitDiagnostic>();var regression=call(builder,"worldRegression",observations,5,"independent-anisotropy",profiles,distances,1.0,track.get(5).band(),diagnostics);
        double s=.5,d=StrictMath.hypot(s/2,1);assertEquals(d,((Number)call(regression,"centerOffsetPx")).doubleValue(),1e-12);
        double[] left=metricNormal(profiles.get(4),frame),middle=metricNormal(profiles.get(5),frame),right=metricNormal(profiles.get(6),frame);
        double nxp=right[0]-left[0],nyp=right[1]-left[1],kp=right[2]-left[2],epsilon=1e-6;
        java.util.function.DoubleUnaryOperator projected=t->((s+t)*(middle[0]+nxp*t)-(middle[1]+nyp*t))/(middle[2]+kp*t);
        double expected=(projected.applyAsDouble(epsilon)-projected.applyAsDouble(-epsilon))/(2*epsilon);
        assertEquals(expected,((Number)call(regression,"tangentOffsetPerMeter")).doubleValue(),1e-8);
        double withoutScale=(middle[0]+s*nxp-nyp)/middle[2];assertTrue(Math.abs(expected-withoutScale)>.01,"Fixture must independently distinguish the scale-derivative term");
        assertEquals(0,diagnostics.get(0).projectionMissMeters(),1e-12);assertEquals(.5,diagnostics.get(0).residualMeters(),1e-12);
    }
    private static double[] metricNormal(CorridorProfile p,RasterMetricTransform transform){var a=p.source().anchorScreen();var n=p.source().normalScreen();var origin=transform.pixelCenterToMetric(a.x,a.y);var end=transform.pixelCenterToMetric(a.x+n.x,a.y+n.y);double x=end.xMeters()-origin.xMeters(),y=end.yMeters()-origin.yMeters(),k=StrictMath.hypot(x,y);return new double[]{x/k,y/k,k};}
    private static Object call(Object target,String name,Object...args)throws Exception{for(var m:target.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(target,args);}throw new NoSuchMethodException(name);}
    private static CorridorTubeBuilder owned(){return new CorridorTubeBuilder(CorridorTubeBuilder.MotionPolicy.TARGET_OWNED_APEX);}
    private static CorridorTubeBuilder component(Set<Integer> protectedProfiles)throws Exception {
        try{return CorridorTubeBuilder.class.getConstructor(RasterMetricTransform.class,double.class,Set.class).newInstance(RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),1.0,protectedProfiles);}
        catch(NoSuchMethodException absent){return owned();}
    }
    private static Fixture arc(){
        var profiles=new ArrayList<CorridorProfile>();var observed=new LinkedHashMap<Integer,CorridorTrackPoint>();double chainage=0;Point2D.Double previous=null;
        for(int i=0;i<31;i++){
            double theta=.03*i,normalX=StrictMath.cos(theta),normalY=StrictMath.sin(theta),jitter=i>=8&&i<=20&&i%2==0?12:0;
            var anchor=new Point2D.Double((4+jitter)*normalX,(4+jitter)*normalY);if(previous!=null)chainage+=anchor.distance(previous);previous=anchor;
            double center=-jitter;var band=new CorridorBand("ridge"+i,center,center-2,center+2,center-.5,center+.5,List.of(center),.8,.02,1,.95,.95,.5,false,List.of());
            var source=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(anchor.x,anchor.y),anchor.x,anchor.y,chainage),new Point2D.Double(normalX,normalY),List.of(new RenderedHeatmapSampler.CrossSectionPeak(center,.8)),true,List.of());
            profiles.add(new CorridorProfile(i,source,List.of(band),.8,.02,.78,true));observed.put(i,new CorridorTrackPoint(i,band,false));
        }
        return new Fixture(new CorridorTrack("analytic-world-arc",observed,31,1,false,List.of(),""),profiles);
    }
    private static MetricPoint point(CorridorProfile p,double offset){var a=p.source().anchorScreen();var n=p.source().normalScreen();return new MetricPoint(a.x+n.x*offset,a.y+n.y*offset);}
    private static List<MetricPoint> points(List<CorridorProfile> profiles,LongitudinalCorridorTube tube){var result=new ArrayList<MetricPoint>();for(int i=0;i<profiles.size();i++)result.add(point(profiles.get(i),tube.at(i).centerOffsetPx()));return result;}
    private static int properCount(List<MetricPoint> points)throws Exception {var method=FinalGeometryEvaluator.class.getDeclaredMethod("intersection",MetricPoint.class,MetricPoint.class,MetricPoint.class,MetricPoint.class);method.setAccessible(true);int count=0;for(int i=0;i+1<points.size();i++)for(int j=i+2;j+1<points.size();j++)if(method.invoke(null,points.get(i),points.get(i+1),points.get(j),points.get(j+1)).toString().equals("PROPER"))count++;return count;}
    private record Fixture(CorridorTrack track,List<CorridorProfile> profiles) { }
}
