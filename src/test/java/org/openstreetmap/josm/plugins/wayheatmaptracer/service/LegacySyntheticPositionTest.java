package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class LegacySyntheticPositionTest {
    static final LegacyTrackerPolicy V1=LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1;
    static final LegacyTrackerPolicy V2=LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V2;

    @Test void v1OutputsMatchIndependentlyFrozenCfffRuntime() throws Exception {
        var text=new StringBuilder();
        for(double pitch:List.of(1.0,6.0,24.0))for(double normalization:List.of(6.0,24.0)) {
            var fixture=LegacyPairedChordTest.fixture("legacy-dominated-synthetic",17,pitch);
            text.append(LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(fixture.profiles(),normalization,pitch,V1)));
        }
        text.append(LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(constantStraightProfiles(-72.00392654351027),6,1,V1)));
        var digest=java.security.MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("f026f2b3f57a73f028d44b3abfce02919097bc075fa6ba595a24a21a63b01691",java.util.HexFormat.of().formatHex(digest));
    }

    @Test void actualRetainedSyntheticBridgeMustNotReplaceTheStrongerObservedChannel() throws Exception {
        for(double pitch:List.of(1.0,6.0,24.0)) for(double normalization:List.of(6.0,24.0)) {
            var fixture=LegacyPairedChordTest.fixture("legacy-dominated-synthetic",17,pitch);
            var profiles=fixture.profiles();
            var oldFiltered=LegacyTrackerPolicyTest.filtered(profiles,normalization,pitch,V1);
            var newFiltered=LegacyTrackerPolicyTest.filtered(profiles,normalization,pitch,V2);
            var synthetic=oldFiltered.get(8).peaks().stream().filter(RenderedHeatmapSampler.CrossSectionPeak::syntheticCenter).findFirst().orElseThrow();
            var observed=oldFiltered.get(8).peaks().get(0);
            assertFalse(observed.syntheticCenter());
            assertEquals(2,LegacyTrackerPolicyTest.support(oldFiltered,8,observed,normalization,pitch,V1));
            for(int channel=0;channel<3;channel++)assertTrue(LegacyFineLocalizationTest.scalar(profiles.get(8),observed.offsetPx(),channel)
                    >LegacyFineLocalizationTest.scalar(profiles.get(8),synthetic.offsetPx(),channel));
            assertTrue(oldFiltered.get(8).peaks().contains(synthetic));
            assertFalse(newFiltered.get(8).peaks().contains(synthetic),"Synthetic positional state must not survive direct three-channel dominance");
            assertTrue(newFiltered.get(8).peaks().contains(observed));
            if(pitch==24&&normalization==24) {
                var old=LegacyTrackerPolicyTest.track(profiles,normalization,pitch,V1).get(0);
                var corrected=LegacyTrackerPolicyTest.track(profiles,normalization,pitch,V2).get(0);
                assertTrue(old.offsetsPx().get(8)/pitch> -1.0,"Retain the actual weak-path RED");
                assertTrue(old.offsetsPx().get(9)/pitch> -1.0);
                for(int row:List.of(8,9)) {
                    assertTrue(corrected.offsetsPx().get(row)/pitch< -1.5,"Restore the stronger negative channel");
                    for(int channel=0;channel<3;channel++)assertTrue(
                            LegacyFineLocalizationTest.scalar(profiles.get(row),corrected.offsetsPx().get(row),channel)
                            >LegacyFineLocalizationTest.scalar(profiles.get(row),old.offsetsPx().get(row),channel));
                }
            }
        }
    }

    @Test void faintMeasuredMaximaSurviveBrighterOneTwoAndThreeProfileParallelBursts() throws Exception {
        for(double pitch:List.of(1.0,6.0,24.0)) for(int duration:List.of(1,2,3)) for(double separation:List.of(1.0,1.5)) {
            var profiles=syntheticChannel(pitch,duration,separation,false,.3);
            var old=LegacyTrackerPolicyTest.filtered(profiles,6,pitch,V1);
            var corrected=LegacyTrackerPolicyTest.filtered(profiles,6,pitch,V2);
            assertEquals(old,corrected,"Measured faint maximum is protected even when a competing burst is brighter in all channels");
            assertEquals(LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(profiles,6,pitch,V1)),
                    LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(profiles,6,pitch,V2)));
        }
    }

    @Test void positiveFlatCoreHasNoAmplitudeThresholdButExactlyZeroGapHasNoPosition() throws Exception {
        for(double pitch:List.of(1.0,6.0,24.0)) for(double amplitude:List.of(.2,1e-20,0.0)) {
            var profiles=syntheticChannel(pitch,9,3,true,amplitude);
            assertEquals(amplitude,LegacyFineLocalizationTest.scalar(profiles.get(4),0,0),"Zero gap must be exactly zero; positive faint plateau stays factual");
            var old=LegacyTrackerPolicyTest.filtered(profiles,6,pitch,V1);
            var corrected=LegacyTrackerPolicyTest.filtered(profiles,6,pitch,V2);
            assertTrue(old.get(4).peaks().stream().anyMatch(RenderedHeatmapSampler.CrossSectionPeak::syntheticCenter));
            assertEquals(amplitude>0,corrected.get(4).peaks().stream().anyMatch(RenderedHeatmapSampler.CrossSectionPeak::syntheticCenter));
            assertTrue(corrected.get(4).peaks().stream().anyMatch(p->!p.syntheticCenter()));
        }
    }

    @Test void ordinaryRetainedIsolatedSpikeWithoutCoherentOwnerCannotDeleteAnOffGridFaintCenter() throws Exception {
        for(double pitch:List.of(1.0,24.0)) {
            var profiles=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            for(int i=0;i<9;i++) {
                double center=(i==3?.1:.1-4*Math.abs(i-4))*pitch;
                var peaks=new ArrayList<RenderedHeatmapSampler.CrossSectionPeak>();
                peaks.add(synthetic(center,10*pitch));
                if(i==4)peaks.add(LegacyTrackerPolicyTest.peak(center+3*pitch,pitch,.96));
                var samples=channelSamples(pitch,center,.3,false,i==4,center+3*pitch);
                var anchor=new Point2D.Double(i*10*pitch,-center);
                profiles.add(new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(anchor.x,anchor.y),anchor,
                        new Point2D.Double(0,1),peaks,true,samples));
            }
            var old=LegacyTrackerPolicyTest.filtered(profiles,6,pitch,V1);
            var spike=old.get(4).peaks().stream().filter(p->!p.syntheticCenter()).findFirst().orElseThrow();
            var faint=old.get(4).peaks().stream().filter(RenderedHeatmapSampler.CrossSectionPeak::syntheticCenter).findFirst().orElseThrow();
            assertEquals(0,LegacyTrackerPolicyTest.support(old,4,spike,6,pitch,V1));
            for(int c=0;c<3;c++)assertTrue(LegacyFineLocalizationTest.scalar(profiles.get(4),spike.offsetPx(),c)
                    >LegacyFineLocalizationTest.scalar(profiles.get(4),faint.offsetPx(),c));
            assertTrue(LegacyFineLocalizationTest.scalar(profiles.get(4),0,0)
                    >LegacyFineLocalizationTest.scalar(profiles.get(4),faint.offsetPx(),0),"No measured-local-max protection masks the coherence check");
            assertEquals(old,LegacyTrackerPolicyTest.filtered(profiles,6,pitch,V2));
        }
    }

    @Test void missingCensoredOrNonfiniteScalarProofNeverDeletes() throws Exception {
        for(int defect=0;defect<5;defect++) {
            var profiles=new ArrayList<>(syntheticChannel(24,9,3,true,0));
            for(int i=0;i<profiles.size();i++) {
                var p=profiles.get(i);var samples=p.intensitySamples();
                if(defect==0)samples=List.of();
                if(defect==1)samples=samples.stream().map(s->new RenderedHeatmapSampler.IntensitySample(s.offsetPx(),s.nativeIntensity(),s.lightFilteredIntensity(),s.standardFilteredIntensity(),false)).toList();
                if(defect==2)samples=samples.stream().map(s->new RenderedHeatmapSampler.IntensitySample(s.offsetPx(),s.nativeIntensity(),Double.NaN,s.standardFilteredIntensity(),true)).toList();
                if(defect==3)samples=samples.stream().filter(s->s.offsetPx()>=0).toList();
                profiles.set(i,new RenderedHeatmapSampler.CrossSectionProfile(p.samplingAnchor(),p.normalScreen(),p.peaks(),defect!=4,samples));
            }
            assertEquals(LegacyTrackerPolicyTest.filtered(profiles,6,24,V1),LegacyTrackerPolicyTest.filtered(profiles,6,24,V2));
        }
    }

    @Test void cancellationCoversOwnerAndPruneStagesAndNeverMutatesCallerBank() throws Exception {
        var fixture=LegacyPairedChordTest.fixture("legacy-dominated-synthetic",17,24);
        var raw=fixture.profiles();var rawPeakLists=raw.stream().map(RenderedHeatmapSampler.CrossSectionProfile::peaks).toList();
        var context=LegacyTrackerPolicyTest.context(V2,24,24);
        var method=RidgeTracker.class.getDeclaredMethod("rejectDominatedSyntheticCenters",List.class,context.getClass(),
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.class);
        method.setAccessible(true);
        for(boolean duringPrune:List.of(false,true)) {
            var ordinary=new ArrayList<>(LegacyTrackerPolicyTest.filtered(raw,24,24,V1));var originalRow=ordinary.get(8);
            var primary=new java.util.concurrent.CancellationException("synthetic");
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe probe=
                    ()->!duringPrune||ordinary.get(8)!=originalRow;
            // Preserve the exact instance by using the probe's ordinary failure path.
            var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(new RidgeTracker(),ordinary,context,
                    (org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe)()->{
                        if(probe.cancelled())throw primary;return false;
                    }));
            assertSame(primary,failure.getCause());
            for(int i=0;i<raw.size();i++)assertSame(rawPeakLists.get(i),raw.get(i).peaks());
        }
        var shortBank=new ArrayList<>(raw.subList(0,3));
        assertSame(shortBank,LegacyTrackerPolicyTest.filtered(shortBank,24,24,V2),"Short-circuit never mutates caller-owned profile lists");
    }

    @Test void equalityInOneMeasuredChannelIsInsufficientForDeletion() throws Exception {
        var profiles=new ArrayList<>(syntheticChannel(24,9,3,true,0));
        for(int i=0;i<profiles.size();i++) {
            var p=profiles.get(i);
            var samples=p.intensitySamples().stream().map(s->new RenderedHeatmapSampler.IntensitySample(
                    s.offsetPx(),s.nativeIntensity(),.5,s.standardFilteredIntensity(),s.insideRaster())).toList();
            profiles.set(i,new RenderedHeatmapSampler.CrossSectionProfile(p.samplingAnchor(),p.normalScreen(),p.peaks(),true,samples));
        }
        assertEquals(LegacyTrackerPolicyTest.filtered(profiles,6,24,V1),LegacyTrackerPolicyTest.filtered(profiles,6,24,V2));
    }

    static List<RenderedHeatmapSampler.CrossSectionProfile> syntheticChannel(double pitch,int duration,double separation,
            boolean flat,double amplitude) {
        var profiles=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for(int i=0;i<9;i++) {
            boolean observed=duration==9||i>=3&&i<3+duration;
            var peaks=new ArrayList<RenderedHeatmapSampler.CrossSectionPeak>();peaks.add(synthetic(0,10*pitch));
            if(observed)peaks.add(LegacyTrackerPolicyTest.peak(separation*pitch,pitch,.96));
            var anchor=new Point2D.Double(i*10*pitch,0);
            profiles.add(new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(anchor.x,anchor.y),anchor,new Point2D.Double(0,1),
                    peaks,true,channelSamples(pitch,0,amplitude,flat,observed,separation*pitch)));
        }
        return List.copyOf(profiles);
    }

    private static RenderedHeatmapSampler.CrossSectionPeak synthetic(double offset,double width) {
        return new RenderedHeatmapSampler.CrossSectionPeak(offset,.8,width,true,.3,0,.9,0,0,1,
                offset,offset,offset,0,1,width/20);
    }

    static List<RenderedHeatmapSampler.IntensitySample> channelSamples(double pitch,double center,double amplitude,
            boolean flat,boolean bright,double brightCenter) {
        var samples=new ArrayList<RenderedHeatmapSampler.IntensitySample>();
        for(int j=-64;j<=64;j++) {
            double offset=j*pitch/4;
            double faint=flat&&Math.abs(offset-center)<=pitch*.5?amplitude:amplitude*Math.exp(-.5*Math.pow((offset-center)/(pitch*.15),2));
            // Resolved finite source support: no mathematical Gaussian tail may
            // turn the explicitly zero dark-gap control into a positive plateau.
            double signal=bright&&Math.abs(offset-brightCenter)<=pitch*.5
                    ?.9*Math.exp(-.5*Math.pow((offset-brightCenter)/(pitch*.1),2)):0;
            double value=Math.max(faint,signal);
            samples.add(new RenderedHeatmapSampler.IntensitySample(offset,value,value,value,true));
        }
        return List.copyOf(samples);
    }

    @Test void exactlyUnchangedRasterGeometryRetainsHistoricalOffsetAndEvidenceBits() {
        double pitch=1,offset=-72.00392654351027;
        var profiles=constantStraightProfiles(offset);
        var original=LegacyTrackerPolicyTest.track(profiles,6,pitch,LegacyTrackerPolicy.ORIGINAL_V02);
        var prior=LegacyTrackerPolicyTest.track(profiles,6,pitch,V1);
        assertEquals(original.get(0).screenPoints(),prior.get(0).screenPoints());
        assertNotEquals(LegacyTrackerPolicyTest.signature(original),LegacyTrackerPolicyTest.signature(prior),"V1 sensitivity: offsets change although raster points do not");
        assertEquals(LegacyTrackerPolicyTest.signature(original),LegacyTrackerPolicyTest.signature(
                LegacyTrackerPolicyTest.track(profiles,6,pitch,V2)));
    }

    static List<RenderedHeatmapSampler.CrossSectionProfile> constantStraightProfiles(double offset) {
        var profiles=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        var samples=new ArrayList<RenderedHeatmapSampler.IntensitySample>();
        for(int j=-16;j<=16;j++) {
            double x=offset+j*.25;
            double value=Math.abs(j)<=1?.5:.5*Math.exp(-.5*Math.pow((Math.abs(j)-1)*.25,2));
            samples.add(new RenderedHeatmapSampler.IntensitySample(x,value,value,value,true));
        }
        var peak=new RenderedHeatmapSampler.CrossSectionPeak(offset,.9,3,false,.5,0,.5,0,0,1,
                offset,offset,offset,0,1,.5);
        for(int i=0;i<15;i++) {
            var anchor=new Point2D.Double(10000+i*10,4000.123);
            profiles.add(new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(anchor.x,anchor.y),anchor,
                    new Point2D.Double(0,1),List.of(peak),true,samples));
        }
        return List.copyOf(profiles);
    }
}
