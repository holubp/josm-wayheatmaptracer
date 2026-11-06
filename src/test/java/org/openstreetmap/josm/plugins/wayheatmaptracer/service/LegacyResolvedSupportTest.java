package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class LegacyResolvedSupportTest {
    static final LegacyTrackerPolicy V3=LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V3;
    static final LegacyTrackerPolicy V4=LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V4;

    @Test void coarseMeasuredBanksMustRetainTheirHistoricalSupportedPath() throws Exception {
        var checks=new ArrayList<org.junit.jupiter.api.function.Executable>();
        for(var entry:List.of(new Object[]{"a",24},new Object[]{"b",75},new Object[]{"c",102}))checks.add(()->{
            var p=LegacyPairedChordTest.fixture("legacy-coarse-"+entry[0],(int)entry[1],1).profiles();
            var old=LegacyTrackerPolicyTest.track(p,1,1,LegacyTrackerPolicy.ORIGINAL_V02);
            var v3=LegacyTrackerPolicyTest.track(p,1,1,V3);
            String before=LegacyTrackerPolicyTest.signature(v3);
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(before.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if(entry[0].equals("a"))assertEquals("0f16b2f67d71446c5908c301d6bf3f8f4b5979ca19b8c9eaa6c9d4320e3da428",digest,
                    "Independent pre-V4 RED runtime golden must stay exact");
            assertNotEquals(LegacyTrackerPolicyTest.signature(old),before,"Preserve actual pre-correction failure");
            assertEquals(LegacyTrackerPolicyTest.signature(old),LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(p,1,1,V4)),"coarse bank "+entry[0]);
        });
        assertAll(checks);
    }

    @Test void historicalNeighborBoundMattersEvenWhenTargetNativeBoundIsNotNarrower() throws Exception {
        var target=LegacyTrackerPolicyTest.peak(0,4.7,.7);
        var p=List.of(LegacyTrackerPolicyTest.profile(0,List.of(target),20),
                LegacyTrackerPolicyTest.profile(1,List.of(LegacyTrackerPolicyTest.peak(5,1,.7)),20));
        assertEquals(1,LegacyTrackerPolicyTest.support(p,0,target,6,4,LegacyTrackerPolicy.ORIGINAL_V02));
        assertEquals(0,LegacyTrackerPolicyTest.support(p,0,target,6,4,V3));
        assertEquals(1,LegacyTrackerPolicyTest.support(p,0,target,6,4,V4),"Target bound7.05 equals historical; peer bound4 narrows admitted5px match");
    }

    @Test void unresolvedScalarDominanceCannotDenyAnOtherwiseNativeSupportGain() throws Exception {
        var p=new ArrayList<>(LegacyPairedChordTest.fixture("legacy-managed-hairpin-support",37,LegacyDominatedSupportTest.PITCH).profiles());
        for(int i=0;i<p.size();i++) {
            var row=p.get(i);var samples=new ArrayList<RenderedHeatmapSampler.IntensitySample>();
            for(int j=0;j<row.intensitySamples().size();j+=2)samples.add(row.intensitySamples().get(j));
            p.set(i,new RenderedHeatmapSampler.CrossSectionProfile(row.samplingAnchor(),row.normalScreen(),row.peaks(),true,samples));
        }
        var peak=p.get(12).peaks().get(0);double pitch=LegacyDominatedSupportTest.PITCH;
        assertEquals(1,LegacyTrackerPolicyTest.support(p,12,peak,pitch,pitch,V3));
        assertEquals(4,LegacyTrackerPolicyTest.support(p,12,peak,pitch,pitch,V4));
    }

    @Test void scalarBracketBoundaryIsExactAndLegacyProofRemainsUnchanged() throws Exception {
        var method=RidgeTracker.class.getDeclaredMethod("scalarSupportAt",RenderedHeatmapSampler.CrossSectionProfile.class,double.class,double.class);method.setAccessible(true);
        var old=RidgeTracker.class.getDeclaredMethod("scalarSupportAt",RenderedHeatmapSampler.CrossSectionProfile.class,double.class);old.setAccessible(true);
        for(double span:List.of(1.0,Math.nextUp(1.0),2.0)) {
            var samples=List.of(new RenderedHeatmapSampler.IntensitySample(-2,.3,.3,.3,true),
                    new RenderedHeatmapSampler.IntensitySample(0,.5,.5,.5,true),
                    new RenderedHeatmapSampler.IntensitySample(span,.7,.7,.7,true));
            var anchor=new Point2D.Double(0,0);
            var p=new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(0,0),anchor,new Point2D.Double(0,1),List.of(),true,samples);
            assertNotNull(old.invoke(new RidgeTracker(),p,.25));
            assertEquals(span==1,method.invoke(new RidgeTracker(),p,.25,1.0)!=null,"No epsilon can admit a wider-than-native bracket");
        }
    }

    @Test void floorOnlyCoherenceWithResolvedScalarsCannotAuthorizeDeletionOrPairedPosition() throws Exception {
        var profiles=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();var offsets=new ArrayList<Double>();
        for(int i=0;i<9;i++) {
            double owner=6+4*(i-4);offsets.add(owner);
            var observed=new RenderedHeatmapSampler.CrossSectionPeak(owner,.96,3,false,.5,0,.96,.2,.8,1,owner,owner,owner,0,1,.5);
            var synthetic=new RenderedHeatmapSampler.CrossSectionPeak(0,.8,10,true,.3,0,.9,0,0,1,0,0,0,0,1,.5);
            var anchor=new Point2D.Double(i*10,0);
            profiles.add(new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(anchor.x,anchor.y),anchor,new Point2D.Double(0,1),List.of(synthetic,observed),true,
                    LegacySyntheticPositionTest.channelSamples(1,0,0,true,true,owner)));
        }
        var owner=profiles.get(4).peaks().get(1);
        assertTrue(LegacyTrackerPolicyTest.support(profiles,4,owner,6,1,V4)>=2);
        var context=LegacyTrackerPolicyTest.context(V4,6,1);
        var proof=RidgeTracker.class.getDeclaredMethod("positionalLongitudinalSupport",List.class,int.class,RenderedHeatmapSampler.CrossSectionPeak.class,context.getClass());proof.setAccessible(true);
        assertEquals(0,proof.invoke(new RidgeTracker(),profiles,4,owner,context));
        for(int c=0;c<3;c++)assertTrue(LegacyFineLocalizationTest.scalar(profiles.get(4),owner.offsetPx(),c)>
                LegacyFineLocalizationTest.scalar(profiles.get(4),0,c),"Resolved strict dominance alone must not replace native coherence");
        var retained=LegacyTrackerPolicyTest.filtered(profiles,6,1,V4).get(4).peaks();
        assertTrue(retained.contains(owner));assertTrue(retained.stream().anyMatch(RenderedHeatmapSampler.CrossSectionPeak::syntheticCenter));
        var paired=RidgeTracker.class.getDeclaredMethod("pairedPeak",List.class,List.class,List.class,int.class,context.getClass());paired.setAccessible(true);
        assertNull(paired.invoke(new RidgeTracker(),profiles,offsets,offsets,4,context));
    }

    @Test void floorPeerScanCooperativelyCancelsWithoutChangingBank() throws Exception {
        var target=LegacyTrackerPolicyTest.peak(0,4.7,.7);
        var profiles=List.of(LegacyTrackerPolicyTest.profile(0,List.of(target),20),
                LegacyTrackerPolicyTest.profile(1,List.of(LegacyTrackerPolicyTest.peak(5,1,.7)),20));
        var primary=new java.util.concurrent.CancellationException("historical-floor");int[] reads={0};
        var counted=new java.util.AbstractList<RenderedHeatmapSampler.CrossSectionProfile>(){
            public RenderedHeatmapSampler.CrossSectionProfile get(int i){reads[0]++;return profiles.get(i);}
            public int size(){return profiles.size();}
        };
        LegacyTrackerPolicyTest.support(counted,0,target,6,4,V3);int boundary=reads[0];
        var cancellable=new java.util.AbstractList<RenderedHeatmapSampler.CrossSectionProfile>(){
            int count;
            public RenderedHeatmapSampler.CrossSectionProfile get(int i){if(++count>boundary)throw primary;return profiles.get(i);}
            public int size(){return profiles.size();}
        };
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->LegacyTrackerPolicyTest.support(cancellable,0,target,6,4,V4));
        assertSame(primary,failure.getCause());assertSame(target,profiles.get(0).peaks().get(0));
    }

    @Test void resolvedNativeControlsPreserveV3WhileFineCompatibilityControlsPreserveOriginal() throws Exception {
        for(var fixture:List.of(LegacyPairedChordTest.fixture("legacy-dominated-synthetic",17,24),
                LegacyPairedChordTest.fixture(24),LegacyPairedChordTest.fixture("legacy-managed-hairpin-support",37,LegacyDominatedSupportTest.PITCH))) {
            double pitch=fixture.profiles().size()==37?LegacyDominatedSupportTest.PITCH:24;
            assertEquals(LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(fixture.profiles(),pitch,pitch,V3)),
                    LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(fixture.profiles(),pitch,pitch,V4)));
        }
        for(int duration:List.of(1,2,3))for(double separation:List.of(1.0,1.5))for(double norm:List.of(1.0,6.0)) {
            var p=LegacyTrackerPolicyTest.temporaryCloseStrand(1,separation,duration);
            assertEquals(LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(p,norm,1,LegacyTrackerPolicy.ORIGINAL_V02)),
                    LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(p,norm,1,V4)));
        }
    }
}
