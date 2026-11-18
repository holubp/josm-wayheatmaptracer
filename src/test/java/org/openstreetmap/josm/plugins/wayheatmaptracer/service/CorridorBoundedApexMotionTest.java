package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Analytic source-independent motion authorization oracles, not captured offset sequences. */
class CorridorBoundedApexMotionTest {
    private CorridorTubeBuilder controlBuilder;
    @Test void completedOwnedApexSurvivesNeighboringReboundEnteringWindow()throws Exception {
        double[] centers={0,1,2,3,4,5,6,5,4,3,2,1,2,3,4,5,6};
        var s=scenario(centers,1);var old=new CorridorTubeBuilder().build(s.track,s.profiles,1,Map.of());
        assertEquals("reversing-noise",old.at(8).motionSupportReason());
        var actual=ownedBuilder().build(s.track,s.profiles,1,Map.of());
        assertEquals("target-owned-apex",actual.at(8).motionSupportReason());
        assertEquals(1,actual.at(8).motionSupport());
        assertEquals(actual.at(8).localCenterOffsetPx(),actual.at(8).centerOffsetPx(),1e-12);
        assertEquals(old.at(0),actual.at(0),"Remote turn cannot change historical target outside local5m");
    }
    @Test void analyticTwoTurnSwitchbackOwnsNearestCompletedTurn()throws Exception {
        double[] centers={0,1,2,3,4,5,6,5,4,3,2,1,2,3,4,5,6};
        var s=scenario(centers,1);var actual=ownedBuilder().build(s.track,s.profiles,1,Map.of());
        assertEquals("target-owned-apex",actual.at(10).motionSupportReason());
        assertEquals(1,actual.at(10).motionSupport());
    }
    private static CorridorTubeBuilder ownedBuilder()throws Exception {
        try{return new CorridorTubeBuilder(CorridorTubeBuilder.MotionPolicy.valueOf("TARGET_OWNED_APEX"));}
        catch(IllegalArgumentException absent){return new CorridorTubeBuilder();}
    }
    @Test void ownedTurnAdverseDirectnessThresholdAndHistoricalExactness()throws Exception {
        for(double[] values:List.of(new double[]{0,.2,.4,.6,.8,.6,.4,.2,0,.2,.4,.6,.8,.6,.4,.2,0},
                new double[]{0,2,0,2,0,2,0,2,0,2,0,2,0,2,0,2,0},
                new double[]{0,1,2,1,0,1,2,1,0,1,2,1,0,1,2,1,0})){
            var s=scenario(values,1);var old=new CorridorTubeBuilder().build(s.track,s.profiles,1,Map.of());
            var actual=ownedBuilder().build(s.track,s.profiles,1,Map.of());
            assertEquals(old,actual,"Sub-T/one-step/short repeated turns retain historical outcome");
        }
        double[] values={0,1,2,3,4,5,6,5,4,3,2,1,2,3,4,5,6};var s=scenario(values,1);
        var points=new LinkedHashMap<>(s.track.points());points.remove(7);
        var missing=new CorridorTrack("missing-arm",points,0,0,false,List.of(),"");
        var actual=ownedBuilder().build(missing,s.profiles,1,Map.of());
        assertFalse(actual.at(6).motionSupportReason().startsWith("target-owned-apex"),"Missing row cannot be bridged into an apex arm");
        var interpolation=new LinkedHashMap<Integer,CorridorTrackPoint>();for(var p:s.track.points().values())interpolation.put(p.profileIndex(),new CorridorTrackPoint(p.profileIndex(),p.band(),true,CorridorPointSupport.BOUNDED_INTERPOLATION));
        var predicted=new CorridorTrack("predicted-parent",interpolation,0,0,true,List.of(),"");
        assertEquals(new CorridorTubeBuilder().build(predicted,s.profiles,1,Map.of()),ownedBuilder().build(predicted,s.profiles,1,Map.of()));
        var old=new CorridorTubeBuilder().build(s.track,s.profiles,1,Map.of());var fresh=ownedBuilder().build(s.track,s.profiles,1,Map.of());
        for(int i=0;i<values.length;i++)if(!old.at(i).motionSupportReason().equals("reversing-noise"))assertEquals(old.at(i),fresh.at(i));
    }
    @Test void ownedTurnPlateausReversalReflectionOversamplingAndScaleAttenuation()throws Exception {
        double[] values={0,1,2,3,4,5,6,6,6,5,4,3,2,1,1,1,2,3,4,5,6};
        var initial=scenario(values,1);var expected=ownedBuilder().build(initial.track,initial.profiles,1,Map.of()).at(9);
        assertEquals("target-owned-apex",expected.motionSupportReason());
        for(boolean reverse:List.of(false,true))for(double sign:List.of(-1.0,1.0))for(double scale:List.of(1.0,8.0)){
            var transformed=new double[values.length];for(int i=0;i<values.length;i++)transformed[i]=sign*values[reverse?values.length-1-i:i];
            var s=scenario(transformed,scale);int target=reverse?values.length-1-9:9;
            var actual=ownedBuilder().build(s.track,s.profiles,scale,Map.of()).at(target);
            assertEquals(expected.motionSupportReason(),actual.motionSupportReason());assertEquals(expected.motionSupport(),actual.motionSupport(),1e-12);
            assertEquals(expected.centerOffsetPx(),actual.centerOffsetPx()/(scale*sign),1e-10);
        }
        var scale=new LinkedHashMap<String,BandScaleEvidence>();for(int i=0;i<values.length;i++)scale.put(CorridorCenterlineOptimizer.scaleEvidenceKey(i,"b"+i),new BandScaleEvidence(.5,Double.NaN,Double.NaN,List.of(0),true,false));
        var conflict=ownedBuilder().build(initial.track,initial.profiles,1,scale).at(9);assertEquals(0,conflict.motionSupport());assertTrue(conflict.motionSupportReason().contains("scale-conflict"));
        var profiles=new ArrayList<CorridorProfile>();for(var p:initial.profiles){double c=p.bands().get(0).centerOffsetPx();var peak=new RenderedHeatmapSampler.CrossSectionPeak(c,.25,0,false,.23,.02,.25,1,1,1,c,c+1,c+2,0,1,.5);var original=p.source();var source=new RenderedHeatmapSampler.CrossSectionProfile(original.samplingAnchor(),original.normalScreen(),List.of(peak),true,List.of());profiles.add(new CorridorProfile(p.index(),source,p.bands(),.25,.02,.23,true));}
        var disagreement=ownedBuilder().build(initial.track,profiles,1,Map.of()).at(9);assertEquals(0,disagreement.motionSupport());assertTrue(disagreement.motionSupportReason().contains("center-disagreement"));
    }
    @Test void ownedTurnStrongFaintDiffuseParallelSparseAndRepeatedCurveControls()throws Exception {
        controlBuilder=ownedBuilder();
        optInStrongArcThinParallelSparseAndNoSignalControls();
        optInWeakSineSwitchbackAndRepeatedCurvedPassKeepShapeAndAnchors();
    }
    @Test void ownedTurnWeakDiffusePlateauAndValidNoSignalRemainHistorical()throws Exception {
        var fixtures=new CorridorRasterIntegrationTest();
        @SuppressWarnings("unchecked") var source=(List<Point2D.Double>)call(fixtures,"sourcePolyline");
        var weak=(java.awt.image.BufferedImage)call(fixtures,"raster",(java.util.function.IntToDoubleFunction)(i->i%2==0?-1:1),.16,false,false);
        var profiles=sample(weak,source);var old=new CorridorAwareTracker().trackDetailed(profiles,1,ends());
        controlBuilder=ownedBuilder();var actual=tracker().trackDetailed(profiles,1,ends());assertEquals(old.candidates().size(),actual.candidates().size());
        for(int i=0;i<old.candidates().size();i++)assertEquals(old.candidates().get(i).offsetsPx(),actual.candidates().get(i).offsetsPx());
        for(var tube:actual.tubes().values())assertTrue(tube.slices().stream().noneMatch(t->t.motionSupportReason().startsWith("target-owned-apex")));
        var blank=new java.awt.image.BufferedImage(weak.getWidth(),weak.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics=blank.createGraphics();graphics.setColor(java.awt.Color.BLACK);graphics.fillRect(0,0,blank.getWidth(),blank.getHeight());graphics.dispose();
        var noSignal=sample(blank,source);assertTrue(noSignal.stream().allMatch(RenderedHeatmapSampler.CrossSectionProfile::anchorWithinRaster));
        assertTrue(tracker().track(noSignal,1).isEmpty());
    }
    @Test void sustainedApexWithUnresolvedSecondaryReboundRetainsLocalPhysicalReference()throws Exception {
        double[] centers={0,1,2,3,4,5,6,5,4,3,2.4,2.8,1.8,.8,-.2,-1.2,-2.2};
        var scenario=scenario(centers,1);
        var historical=new CorridorTubeBuilder().build(scenario.track,scenario.profiles,1,Map.of());
        var original=historical.at(8);
        assertEquals("reversing-noise",original.motionSupportReason());assertEquals(0,original.motionSupport());
        assertTrue(Math.abs(original.centerOffsetPx()-original.localCenterOffsetPx())>.2,
            "Old rejection must replace the supported local physical reference with a remote blend");
        var revised=revisedBuilder().build(scenario.track,scenario.profiles,1,Map.of());var actual=revised.at(8);
        assertEquals("supported-apex-bounded-reversal",actual.motionSupportReason());
        assertEquals(1,actual.motionSupport());assertEquals(actual.localCenterOffsetPx(),actual.centerOffsetPx(),1e-12);
        assertEquals(centers[8],actual.rawCenterPx(),0);assertEquals(actual.rawCenterPx(),actual.lightCenterPx(),0);assertEquals(actual.rawCenterPx(),actual.standardCenterPx(),0);
    }
    @Test void secondaryReboundCrossingWindowBoundaryDoesNotTriggerRemoteReferenceSubstitution()throws Exception {
        double[] centers=new double[29];for(int i=0;i<centers.length;i++)centers[i]=i<=12?i:i<=19?24-i:5.4-(i-20);
        var scenario=scenario(centers,1);var old=new CorridorTubeBuilder().build(scenario.track,scenario.profiles,1,Map.of());
        assertEquals("supported-apex",old.at(11).motionSupportReason());assertEquals("reversing-noise",old.at(12).motionSupportReason());
        var revised=revisedBuilder().build(scenario.track,scenario.profiles,1,Map.of());
        assertEquals(old.at(11),revised.at(11),"Already supported window must remain exactly unchanged");
        for(int i=12;i<=13;i++){var value=revised.at(i);assertEquals("supported-apex-bounded-reversal",value.motionSupportReason());assertEquals(1,value.motionSupport());assertEquals(value.localCenterOffsetPx(),value.centerOffsetPx(),1e-12);}
    }
    @Test void resolvedSecondaryTurnsAndInsufficientArmSupportRemainRejectedExactly()throws Exception {
        double[] above={0,1,2,3,4,5,6,5,4,3,2.4,3.65,1.8,.8,-.2,-1.2,-2.2};
        double[] competing={0,1,2,3,4,5,6,4,2,3,4,5,3,1,0,-1,-2};
        double[] oneRise={0,0,0,0,0,0,6,5,4,3,2.4,2.8,1.8,1.4,1,.6,.2};
        double[] alternating=new double[17];for(int i=0;i<17;i++)alternating[i]=i%2==0?-1:1;
        for(var centers:List.of(above,competing,oneRise,alternating)){
            var s=scenario(centers,1);var old=new CorridorTubeBuilder().build(s.track,s.profiles,1,Map.of());var actual=revisedBuilder().build(s.track,s.profiles,1,Map.of());
            assertEquals("reversing-noise",old.at(8).motionSupportReason());assertEquals(0,actual.at(8).motionSupport());assertEquals(old.slices(),actual.slices());
        }
    }
    @Test void thresholdAllowsAnExactlyUnresolvedReboundButNotALargerOne()throws Exception {
        double[] centers={0,1,2,3,4,5,6,5,4,3,2.4,3.4,1.8,.8,-.2,-1.2,-2.2};
        var s=scenario(centers,1);assertEquals(1,revisedBuilder().build(s.track,s.profiles,1,Map.of()).at(8).motionSupport());
        centers[11]=Math.nextUp(3.4);s=scenario(centers,1);assertEquals(0,revisedBuilder().build(s.track,s.profiles,1,Map.of()).at(8).motionSupport());
    }
    @Test void reversalReflectionAndRasterOversamplingPreserveAuthorization()throws Exception {
        double[] base={0,1,2,3,4,5,6,5,4,3,2.4,2.8,1.8,.8,-.2,-1.2,-2.2};var first=scenario(base,1);var expected=revisedBuilder().build(first.track,first.profiles,1,Map.of()).at(8);
        for(boolean reverse:List.of(false,true))for(double sign:List.of(-1.0,1.0))for(double scale:List.of(1.0,8.0)){
            var centers=new double[17];for(int i=0;i<17;i++)centers[i]=sign*base[reverse?16-i:i];var s=scenario(centers,scale);var actual=revisedBuilder().build(s.track,s.profiles,scale,Map.of()).at(8);
            assertEquals(expected.motionSupport(),actual.motionSupport(),1e-12);assertEquals(expected.motionSupportReason(),actual.motionSupportReason());assertEquals(expected.centerOffsetPx(),actual.centerOffsetPx()/(sign*scale),1e-10);assertEquals(expected.uncertaintyPx(),actual.uncertaintyPx()/scale,1e-10);
        }
    }
    @Test void scaleAndChannelDisagreementRetainExistingAttenuation()throws Exception {
        double[] centers={0,1,2,3,4,5,6,5,4,3,2.4,2.8,1.8,.8,-.2,-1.2,-2.2};var s=scenario(centers,1);var scale=new LinkedHashMap<String,BandScaleEvidence>();
        for(int i=0;i<17;i++)scale.put(CorridorCenterlineOptimizer.scaleEvidenceKey(i,"b"+i),new BandScaleEvidence(.5,Double.NaN,Double.NaN,List.of(0),true,false));
        var conflicted=revisedBuilder().build(s.track,s.profiles,1,scale).at(8);assertEquals(0,conflicted.motionSupport());assertTrue(conflicted.motionSupportReason().contains("scale-conflict"));
        var profiles=new ArrayList<CorridorProfile>();for(var p:s.profiles){double c=p.bands().get(0).centerOffsetPx();var peak=new RenderedHeatmapSampler.CrossSectionPeak(c,.25,0,false,.23,.02,.25,1,1,1,c,c+1,c+2,0,1,.5);var original=p.source();var source=new RenderedHeatmapSampler.CrossSectionProfile(original.samplingAnchor(),original.normalScreen(),List.of(peak),true,List.of());profiles.add(new CorridorProfile(p.index(),source,p.bands(),.25,.02,.23,true));}
        var disagreement=revisedBuilder().build(s.track,profiles,1,Map.of()).at(8);assertEquals(0,disagreement.motionSupport());assertTrue(disagreement.motionSupportReason().contains("center-disagreement"));
    }
    @Test void insufficientDirectEvidenceAndInterpolationCannotAuthorizeApex()throws Exception {
        for(double[] centers:List.of(new double[]{0,2,4,3.6},new double[]{0,2,4,3.6,0})){
            var s=scenario(centers,1);assertEquals(new CorridorTubeBuilder().build(s.track,s.profiles,1,Map.of()),revisedBuilder().build(s.track,s.profiles,1,Map.of()));
        }
        double[] centers={0,1,2,3,4,5,6,5,4,3,2.4,2.8,1.8,.8,-.2,-1.2,-2.2};var s=scenario(centers,1);var predicted=new LinkedHashMap<Integer,CorridorTrackPoint>();for(var p:s.track.points().values())predicted.put(p.profileIndex(),new CorridorTrackPoint(p.profileIndex(),p.band(),true,CorridorPointSupport.BOUNDED_INTERPOLATION));
        for(var track:List.of(new CorridorTrack("empty",Map.of(),0,0,false,List.of(),""),new CorridorTrack("parent",predicted,0,0,true,List.of("one","two"),"combined"))){
            var old=new CorridorTubeBuilder().build(track,s.profiles,1,Map.of());var actual=revisedBuilder().build(track,s.profiles,1,Map.of());assertEquals(old,actual);assertTrue(actual.slices().stream().allMatch(v->v.motionSupport()==0));
        }
    }
    @Test void alreadySupportedWindowsAndRemoteValidBendKeepCompleteRecords()throws Exception {
        double[] centers=new double[100];for(int i=0;i<100;i++)centers[i]=i<=12?i:i<=19?24-i:i<40?5.4-(i-20):5*StrictMath.sin((i-60)*Math.PI/20);
        var s=scenario(centers,1);var old=new CorridorTubeBuilder().build(s.track,s.profiles,1,Map.of());var actual=revisedBuilder().build(s.track,s.profiles,1,Map.of());
        for(int i=0;i<100;i++)if(!old.at(i).motionSupportReason().equals("reversing-noise"))assertEquals(old.at(i),actual.at(i),"Historical successful/other rejected record must remain exact at "+i);
        assertTrue(old.at(70).motionSupportReason().startsWith("supported-apex"));for(int i=55;i<95;i++)assertEquals(old.at(i),actual.at(i),"Remote valid bend "+i);
    }
    @Test void optInStrongArcThinParallelSparseAndNoSignalControls()throws Exception {
        var arc=tracker().track(PhysicalGeometryFixture.supportedOppositeArcProfiles(),PhysicalGeometryFixture.NATIVE_PITCH);assertFalse(arc.isEmpty());
        call(PhysicalPathContinuityTest.class,"assertOppositeArc",arc.get(0).screenPoints());
        for(double amplitude:List.of(.95,.35)){
            var frame=PhysicalGeometryFixture.parallelFrame(amplitude);var result=tracker().trackDetailed(frame.profileSet(),frame.sourcePixelPitch(),JunctionContext.empty());assertFalse(result.candidates().isEmpty());
            call(PhysicalPathContinuityTest.class,"assertParallelStrand",result.candidates().get(0),frame,"bounded-apex opt-in");
        }
        var fixtures=new CorridorRasterIntegrationTest();
        @SuppressWarnings("unchecked") var source=(List<Point2D.Double>)call(fixtures,"sourcePolyline");
        var sparse=(java.awt.image.BufferedImage)call(fixtures,"complementarySparseRaster");var fixed=ends();
        var result=tracker().trackDetailed(sample(sparse,source),1,fixed);assertFalse(result.sparseBundles().isEmpty());assertFalse(result.candidates().isEmpty());var bundle=result.sparseBundles().stream().max(Comparator.comparingDouble(SparseCorridorBundle::unionSupportRatio)).orElseThrow();var candidate=result.candidates().stream().filter(c->c.id().equals(bundle.id())).findFirst().orElseThrow();assertEquals(58,bundle.directUnionProfileCount());assertEquals(3,bundle.interpolatedProfileCount());assertTrue(candidate.evidence().corridorCoverage().complete());
        assertEquals(0,candidate.offsetsPx().subList(2,59).stream().mapToDouble(Double::doubleValue).average().orElseThrow(),.75);assertTrue(candidate.evidence().corridorQuality().nonSustainedHighFrequencyP95SourcePx()<=.40);
        var zero=new java.awt.image.BufferedImage(sparse.getWidth(),sparse.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);assertTrue(tracker().track(sample(zero,source),1).isEmpty(),"No-signal cannot become a candidate");
    }
    @Test void optInWeakSineSwitchbackAndRepeatedCurvedPassKeepShapeAndAnchors()throws Exception {
        var fixtures=new CorridorRasterIntegrationTest();
        @SuppressWarnings("unchecked") var source=(List<Point2D.Double>)call(fixtures,"sourcePolyline");
        java.util.function.IntToDoubleFunction sine=i->8*StrictMath.sin(i*2*Math.PI/60);
        java.util.function.IntToDoubleFunction switchback=i->{int phase=i%20;double rising=-8+16.0*phase/20;return (i/20)%2==0?rising:-rising;};
        for(var curve:List.of(sine,switchback)){
            var raster=(java.awt.image.BufferedImage)call(fixtures,"raster",curve,.16,false,false);var result=tracker().trackDetailed(sample(raster,source),1,ends());assertFalse(result.candidates().isEmpty());var offsets=result.candidates().get(0).offsetsPx();double amplitude=(offsets.stream().mapToDouble(Double::doubleValue).max().orElseThrow()-offsets.stream().mapToDouble(Double::doubleValue).min().orElseThrow())/2;assertTrue(amplitude>=7.2);
            assertEquals(0.0,offsets.get(0),0);assertEquals(0.0,offsets.get(60),0);if(curve==switchback)assertTrue(offsets.get(20)>=6.5&&offsets.get(40)<=-6.5);
        }
        var raster=(java.awt.image.BufferedImage)call(fixtures,"raster",(java.util.function.IntToDoubleFunction)(i->6*StrictMath.sin(i*2*Math.PI/60)),.82,false,true);
        var fixed=new JunctionContext(List.of(new EndpointConstraint(0,1,true,true,0,0,6),new EndpointConstraint(30,2,true,true,0,0,6),new EndpointConstraint(60,3,true,true,0,0,6)));
        var first=tracker().trackDetailed(sample(raster,source),1,fixed).candidates().get(0);
        var repeated=tracker().trackDetailed(sample(raster,first.screenPoints()),1,fixed).candidates().stream().filter(c->c.evidence().corridorCoverage().complete()).min(Comparator.comparingDouble(c->{try{return ((Number)call(fixtures,"bidirectionalMaximumDrift",first.screenPoints(),c.screenPoints())).doubleValue();}catch(Exception e){throw new AssertionError(e);}})).orElseThrow();
        assertTrue(first.evidence().corridorCoverage().complete());assertTrue(repeated.evidence().corridorCoverage().complete());assertTrue(repeated.evidence().corridorQuality().unsupportedExcursions()<=first.evidence().corridorQuality().unsupportedExcursions());assertTrue(((Number)call(fixtures,"bidirectionalMaximumDrift",first.screenPoints(),repeated.screenPoints())).doubleValue()<=1);
        for(int i:List.of(0,30,60))assertEquals(0.0,repeated.offsetsPx().get(i),0);
    }
    private static JunctionContext ends(){return new JunctionContext(List.of(new EndpointConstraint(0,1,true,false,0,0,6),new EndpointConstraint(60,2,true,false,0,0,6)));}
    private static List<RenderedHeatmapSampler.CrossSectionProfile> sample(java.awt.image.BufferedImage raster,List<Point2D.Double> source){return new RenderedHeatmapSampler().sampleProfilesOnScaledRaster(raster,source,18,1,"hot",1,1,IntensitySamplingMode.DIRECT_VALUE);}
    private CorridorAwareTracker tracker()throws Exception{return new CorridorAwareTracker(new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(RasterMetricTransform.visible(new MetricPoint(0,0),1),CancellationProbe.NONE),controlBuilder==null?revisedBuilder():controlBuilder);}
    private static Object call(Object target,String name,Object...args)throws Exception{Class<?> c=target instanceof Class<?> t?t:target.getClass();for(var m:c.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(target instanceof Class<?>?null:target,args);}throw new NoSuchMethodException(name);}
    // Before this opt-in API exists, run the real historical builder for behavioral RED.
    private static CorridorTubeBuilder revisedBuilder()throws Exception {
        try {var type=Class.forName(CorridorTubeBuilder.class.getName()+"$MotionPolicy");
            @SuppressWarnings({"rawtypes","unchecked"})Object policy=Enum.valueOf((Class)type,"BOUNDED_SECONDARY_REVERSAL");
            return CorridorTubeBuilder.class.getConstructor(type).newInstance(policy);
        }catch(ClassNotFoundException|NoSuchMethodException absent){return new CorridorTubeBuilder();}
    }
    private static Scenario scenario(double[] centers,double rasterScale){
        var profiles=new ArrayList<CorridorProfile>();var points=new LinkedHashMap<Integer,CorridorTrackPoint>();
        for(int i=0;i<centers.length;i++){
            double center=centers[i]*rasterScale;var band=new CorridorBand("b"+i,center,center-2*rasterScale,center+2*rasterScale,center-.5*rasterScale,center+.5*rasterScale,List.of(center),.25,.02,1,.8,.9,.5*rasterScale,false,List.of());
            var peak=new RenderedHeatmapSampler.CrossSectionPeak(center,.25);
            var source=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(i*1.5,0),i*1.5*rasterScale,0,i*1.5),new Point2D.Double(0,1),List.of(peak),true,List.of());
            profiles.add(new CorridorProfile(i,source,List.of(band),.25,.02,.23,true));points.put(i,new CorridorTrackPoint(i,band,false));
        }
        return new Scenario(new CorridorTrack("analytic-apex",points,centers.length,1,false,List.of(),""),profiles);
    }
    private record Scenario(CorridorTrack track,List<CorridorProfile> profiles) { }
}
