package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyFineLocalizationTest {
    private static final LegacyTrackerPolicy FINE = LegacyTrackerPolicy.FINE_LOCALIZATION_V1;
    private static final LegacyTrackerPolicy COMBINED = LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_FINE_LOCALIZATION_V1;

    @Test void reducedShortTurnImprovesCompletedHistoricalGeometryWithinOwnedScalarEvidence() throws Exception {
        for (double pitch : List.of(6.0,24.0)) {
            var profiles = fineShortTurn(pitch);
            for (var policy : List.of(FINE,COMBINED)) {
                // The seam is AFTER smoothing. Supply the recorded completed
                // historical phase, instead of rerunning four passes on a
                // truncated trace with different endpoint/window ownership.
                var baseline = completedHistoricalOffsets(pitch);
                var localized = localize(profiles,baseline,pitch,policy);
                var baselinePoints=LegacyNormalWavinessTest.points(profiles,baseline);
                var localizedPoints=LegacyNormalWavinessTest.points(profiles,localized);
                double oldTurn = localTurn(baselinePoints,4,10);
                double newTurn = localTurn(localizedPoints,4,10);
                assertEquals(42.5368,oldTurn,.002,"Factual completed NW geometry must be reproduced");
                if(policy==COMBINED) assertTrue(newTurn < oldTurn, "Actual reduced NW must improve: old="+oldTurn+" new="+newTurn);
                else assertTrue(newTurn <= oldTurn+1e-10);
                assertTrue(LegacyNormalWavinessTest.maximumTurn(localizedPoints)
                        <= LegacyNormalWavinessTest.maximumTurn(baselinePoints)+1e-10);
                assertTrue(LegacyNormalWavinessTest.energy(profiles,localized,pitch)
                        <= LegacyNormalWavinessTest.energy(profiles,baseline,pitch)+1e-20);
                assertEquals(baseline.get(0),localized.get(0));
                assertEquals(baseline.get(13),localized.get(13));
                for(int i=0;i<profiles.size();i++) {
                    assertTrue(Math.abs(localized.get(i)-baseline.get(i))<=pitch*.5+1e-12);
                    for(int channel=0;channel<3;channel++) assertTrue(
                            scalar(profiles.get(i),localized.get(i),channel)+1e-14
                            >=scalar(profiles.get(i),baseline.get(i),channel));
                }
                LegacyNormalWavinessTest.assertNoContacts(localizedPoints);
            }
        }
    }

    @Test void absentCensoredConflictingOrNonfiniteEvidenceCannotMoveTheCompletedPath() throws Exception {
        for(double pitch:List.of(6.0,24.0)) for(int defect=0;defect<12;defect++) {
            var profiles=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            for(var original:fineShortTurn(pitch)) {
                var peak=original.peaks().get(0);var samples=original.intensitySamples();
                if(defect==0) samples=List.of();
                if(defect==1) samples=samples.stream().map(s->new RenderedHeatmapSampler.IntensitySample(
                        s.offsetPx(),s.nativeIntensity(),s.lightFilteredIntensity(),s.standardFilteredIntensity(),false)).toList();
                if(defect==2) samples=samples.stream().map(s->new RenderedHeatmapSampler.IntensitySample(
                        s.offsetPx(),s.nativeIntensity(),s.lightFilteredIntensity(),Double.NaN,true)).toList();
                if(defect==3) samples=samples.stream().filter(s->Math.abs(s.offsetPx()-peak.rawCenterPx())<=pitch*.25).toList();
                if(defect==4) samples=samples.stream().map(s->new RenderedHeatmapSampler.IntensitySample(s.offsetPx(),0,0,0,true)).toList();
                var changed=new RenderedHeatmapSampler.CrossSectionPeak(peak.offsetPx(),peak.intensity(),
                        defect==8?Double.POSITIVE_INFINITY:peak.supportWidthPx(),
                        defect==5,peak.prominence(),peak.noiseFloor(),peak.maxProfileIntensity(),peak.gradientStrength(),peak.gradientBalance(),
                        defect==9?Double.POSITIVE_INFINITY:peak.nativeFilteredAgreement(),peak.rawCenterPx(),
                        defect==6?peak.rawCenterPx()+pitch:peak.lightFilteredCenterPx(),peak.standardFilteredCenterPx(),
                        peak.scaleOffsetRmsPx(),defect==10?Double.POSITIVE_INFINITY:peak.scaleAgreement(),
                        defect==11?Double.POSITIVE_INFINITY:peak.centerUncertaintyPx());
                profiles.add(new RenderedHeatmapSampler.CrossSectionProfile(original.samplingAnchor(),original.normalScreen(),
                        List.of(changed),defect!=7,samples));
            }
            var baseline=completedHistoricalOffsets(pitch);
            for(var policy:List.of(FINE,COMBINED)) assertEquals(baseline,localize(profiles,baseline,pitch,policy),"defect="+defect);
        }
    }

    @Test void brighterNeighboringStrandCannotSupplyAnotherPeaksPosition() throws Exception {
        for(double pitch:List.of(6.0,24.0)) for(double separation:List.of(1.0,1.5)) {
            var profiles=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            for(int i=0;i<5;i++) {
                double center=i==2?-separation*pitch:0;
                var peak=new RenderedHeatmapSampler.CrossSectionPeak(0,.62,3*pitch,false,.5,0,.9,0,0,1,
                        center,center,center,0,1,pitch*.5);
                var peaks=i==2?List.of(peak,LegacyTrackerPolicyTest.peak(center,pitch,.96)):List.of(peak);
                var base=LegacyTrackerPolicyTest.profile(i,peaks,10*pitch);
                var anchor=new java.awt.geom.Point2D.Double(i*10*pitch,i==2?pitch:0);
                profiles.add(new RenderedHeatmapSampler.CrossSectionProfile(
                        new org.openstreetmap.josm.data.coor.EastNorth(anchor.x,anchor.y),anchor,base.normalScreen(),peaks,
                        true,samples(center,pitch)));
            }
            var baseline=List.of(0.0,0.0,0.0,0.0,0.0);
            // Moving toward the bright competitor would improve both scalar
            // support and this wrinkle. Geometric unique-peak ownership vetoes it.
            assertTrue(scalar(profiles.get(2),-pitch*.5,0)>scalar(profiles.get(2),0,0));
            for(var policy:List.of(FINE,COMBINED)) assertEquals(baseline,localize(profiles,baseline,pitch,policy));
        }
    }

    @Test void activeFaintCurvesAndPhysicalWrinklesRetainWholeGeometryAndScalarBounds() throws Exception {
        for(double pitch:List.of(6.0,24.0)) {
            var fixtures=new ArrayList<List<RenderedHeatmapSampler.CrossSectionProfile>>();
            fixtures.add(LegacyNormalWavinessTest.straightTargetProfiles(pitch));
            for(double sweep:List.of(Math.toRadians(150),Math.toRadians(-150),Math.PI))
                fixtures.add(LegacyNormalWavinessTest.circleTargetProfiles(pitch,sweep));
            for(var original:fixtures) {
                var profiles=withScalarEvidence(original,pitch);
                var measured=LegacyNormalWavinessTest.measuredOffsets(profiles);
                var baseline=LegacyNormalWavinessTest.smooth(profiles,measured,pitch,pitch,LegacyTrackerPolicy.ORIGINAL_V02);
                assertTrue(java.util.stream.IntStream.range(1,profiles.size()-1)
                        .anyMatch(i->Math.abs(baseline.get(i)-measured.get(i))>.01*pitch),"Active historical proposals required");
                for(var policy:List.of(FINE,COMBINED)) {
                    var result=localize(profiles,baseline,pitch,policy);
                    assertTrue(LegacyNormalWavinessTest.energy(profiles,result,pitch)<=LegacyNormalWavinessTest.energy(profiles,baseline,pitch)+1e-20);
                    assertTrue(LegacyNormalWavinessTest.maximumTurn(LegacyNormalWavinessTest.points(profiles,result))
                            <=LegacyNormalWavinessTest.maximumTurn(LegacyNormalWavinessTest.points(profiles,baseline))+1e-10);
                    for(int i=0;i<profiles.size();i++) {
                        assertTrue(Math.abs(result.get(i)-baseline.get(i))<=pitch*.5+1e-12);
                        for(int channel=0;channel<3;channel++) assertTrue(scalar(profiles.get(i),result.get(i),channel)+1e-14
                                >=scalar(profiles.get(i),baseline.get(i),channel));
                    }
                    assertEquals(baseline.get(0),result.get(0));assertEquals(baseline.get(16),result.get(16));
                    LegacyNormalWavinessTest.assertNoContacts(LegacyNormalWavinessTest.points(profiles,result));
                }
            }
        }
    }

    static List<RenderedHeatmapSampler.CrossSectionProfile> withScalarEvidence(
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles,double pitch) {
        var result=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for(var original:profiles) {
            var peak=original.peaks().get(0);double center=peak.offsetPx();
            var fine=new RenderedHeatmapSampler.CrossSectionPeak(center,peak.intensity(),peak.supportWidthPx(),false,
                    .331,0,.331,peak.gradientStrength(),peak.gradientBalance(),peak.nativeFilteredAgreement(),
                    center,center,center,0,1,pitch*.5);
            result.add(new RenderedHeatmapSampler.CrossSectionProfile(original.samplingAnchor(),original.normalScreen(),
                    List.of(fine),true,samples(center,pitch)));
        }
        return List.copyOf(result);
    }

    static List<Double> completedHistoricalOffsets(double pitch) {
        return java.util.stream.DoubleStream.of(-.147645,.335120,1.036149,2.688393,3.371263,3.891761,
                4.482599,4.743294,4.843399,5.046877,5.460388,5.619986,5.537354,5.641534)
                .map(x->x*pitch).boxed().toList();
    }

    @SuppressWarnings("unchecked")
    static List<Double> localize(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,List<Double> baseline,
            double pitch,LegacyTrackerPolicy policy) throws Exception {
        var result=new ArrayList<>(baseline);
        var selected=LegacyNormalWavinessTest.measuredOffsets(profiles);
        var context=LegacyTrackerPolicyTest.context(policy,pitch,pitch);
        var method=RidgeTracker.class.getDeclaredMethod("localizeFineCenter",List.class,List.class,List.class,int.class,context.getClass());
        method.setAccessible(true);
        for(int i=1;i<profiles.size()-1;i++)method.invoke(new RidgeTracker(),profiles,selected,result,i,context);
        return result;
    }

    static List<RenderedHeatmapSampler.CrossSectionProfile> fineShortTurn(double pitch) {
        var profiles=LegacyNormalWavinessTest.shortTurnProfiles(pitch);
        var result=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for(int i=0;i<profiles.size();i++) {
            var original=profiles.get(i);var peak=original.peaks().get(0);
            double center=i==6||i==7?6*pitch:peak.offsetPx();
            var fine=new RenderedHeatmapSampler.CrossSectionPeak(peak.offsetPx(),peak.intensity(),peak.supportWidthPx(),false,
                    .331,0,.331,peak.gradientStrength(),peak.gradientBalance(),peak.nativeFilteredAgreement(),
                    center,center,center,0,1,pitch*.5);
            result.add(new RenderedHeatmapSampler.CrossSectionProfile(original.samplingAnchor(),original.normalScreen(),
                    List.of(fine),true,samples(center,pitch)));
        }
        return List.copyOf(result);
    }

    static List<RenderedHeatmapSampler.IntensitySample> samples(double center,double pitch) {
        var result=new ArrayList<RenderedHeatmapSampler.IntensitySample>();
        for(int j=-64;j<=64;j++) {
            double offset=j*pitch/4;
            double value=.331*Math.exp(-.5*Math.pow((offset-center)/pitch,2));
            result.add(new RenderedHeatmapSampler.IntensitySample(offset,value,value*.95,value*.9,true));
        }
        return List.copyOf(result);
    }

    static double scalar(RenderedHeatmapSampler.CrossSectionProfile profile,double offset,int channel) {
        var samples=profile.intensitySamples();
        for(int i=1;i<samples.size();i++) {
            var left=samples.get(i-1);var right=samples.get(i);
            if(offset>=left.offsetPx()&&offset<=right.offsetPx()) {
                double f=(offset-left.offsetPx())/(right.offsetPx()-left.offsetPx());
                double a=value(left,channel),b=value(right,channel);return a+(b-a)*f;
            }
        }
        return Double.NaN;
    }

    private static double value(RenderedHeatmapSampler.IntensitySample sample,int channel) {
        return channel==0?sample.nativeIntensity():channel==1?sample.lightFilteredIntensity():sample.standardFilteredIntensity();
    }

    static double localTurn(List<java.awt.geom.Point2D.Double> points,int first,int last) {
        return LegacyNormalWavinessTest.maximumTurn(points.subList(first-1,last+2));
    }
}
