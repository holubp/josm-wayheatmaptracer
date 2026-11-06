package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyDominatedSupportTest {
    static final LegacyTrackerPolicy V2=LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V2;
    static final LegacyTrackerPolicy V3=LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V3;
    static final double PITCH=36.84304062588829;

    static List<RenderedHeatmapSampler.CrossSectionProfile> fixture() throws Exception {
        return LegacyPairedChordTest.fixture("legacy-managed-hairpin-support",37,PITCH).profiles();
    }

    @Test void v2OutputRemainsBitExactToThePreCorrectionRuntime() throws Exception {
        var old=LegacyTrackerPolicyTest.track(fixture(),PITCH,PITCH,V2).get(0);
        assertEquals("452ef098586427aaa30d82d71832e4222f9bad825362324c415de5e005182f2b",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(LegacyTrackerPolicyTest.signature(List.of(old)).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test void actualHistoricallyCoherentReturnRivalDeniesOnlyWrongArmSupportGain() throws Exception {
        var profiles=fixture();
        for(int row:List.of(12,13,14,15,16,17,32)) {
            var wrong=profiles.get(row).peaks().get(0);
            var owned=profiles.get(row).peaks().get(1);
            int historical=LegacyTrackerPolicyTest.support(profiles,row,wrong,PITCH,PITCH,LegacyTrackerPolicy.ORIGINAL_V02);
            int coherence=LegacyTrackerPolicyTest.support(profiles,row,owned,PITCH,PITCH,LegacyTrackerPolicy.ORIGINAL_V02);
            assertTrue(coherence>=2,"Factual old coherence, not new physical support, owns the denial");
            assertTrue(LegacyTrackerPolicyTest.support(profiles,row,wrong,PITCH,PITCH,V2)>historical);
            for(int c=0;c<3;c++)assertTrue(LegacyFineLocalizationTest.scalar(profiles.get(row),owned.offsetPx(),c)
                    >LegacyFineLocalizationTest.scalar(profiles.get(row),wrong.offsetPx(),c));
            assertEquals(historical,LegacyTrackerPolicyTest.support(profiles,row,wrong,PITCH,PITCH,V3),"Wrong-arm new support at full row"+(row+150));
            assertEquals(LegacyTrackerPolicyTest.support(profiles,row,owned,PITCH,PITCH,V2),
                    LegacyTrackerPolicyTest.support(profiles,row,owned,PITCH,PITCH,V3));
        }
    }

    @Test void actualSuppressionMustNotIntroduceTheWeakerStateIntoAnchoredInterval() throws Exception {
        var profiles=fixture();var identities=profiles.stream().map(RenderedHeatmapSampler.CrossSectionProfile::peaks).toList();
        var old=LegacyTrackerPolicyTest.filtered(profiles,PITCH,PITCH,V2);
        var corrected=LegacyTrackerPolicyTest.filtered(profiles,PITCH,PITCH,V3);
        var wrong=profiles.get(32).peaks().get(0);var owned=profiles.get(32).peaks().get(1);
        assertTrue(old.get(32).peaks().contains(wrong),"Retain the real pre-correction RED");
        assertFalse(corrected.get(32).peaks().contains(wrong));
        assertTrue(corrected.get(32).peaks().contains(owned));
        for(int i=0;i<profiles.size();i++)assertSame(identities.get(i),profiles.get(i).peaks());
    }

    @Test void actualReducedCompletedPathReturnsToTheOwnedBranch() throws Exception {
        var profiles=fixture();
        var old=LegacyTrackerPolicyTest.track(profiles,PITCH,PITCH,V2).get(0);
        var corrected=LegacyTrackerPolicyTest.track(profiles,PITCH,PITCH,V3).get(0);
        for(int row:List.of(32,33)) {
            double owned=profiles.get(row).peaks().get(1).offsetPx();
            assertTrue(Math.abs(corrected.offsetsPx().get(row)-owned)<Math.abs(old.offsetsPx().get(row)-owned),"Completed historical output must improve at "+(row+150));
        }
    }

    @Test void incompleteEqualOrSyntheticRivalProofCannotDenyNativeSupport() throws Exception {
        for(int defect=0;defect<7;defect++) {
            var profiles=new ArrayList<>(fixture());
            for(int i=0;i<profiles.size();i++) {
                var p=profiles.get(i);var samples=p.intensitySamples();var peaks=p.peaks();
                if(defect==0)samples=List.of();
                if(defect==1)samples=samples.stream().map(s->new RenderedHeatmapSampler.IntensitySample(s.offsetPx(),s.nativeIntensity(),s.lightFilteredIntensity(),s.standardFilteredIntensity(),false)).toList();
                if(defect==2)samples=samples.stream().map(s->new RenderedHeatmapSampler.IntensitySample(s.offsetPx(),s.nativeIntensity(),Double.NaN,s.standardFilteredIntensity(),true)).toList();
                if(defect==3)samples=samples.stream().map(s->new RenderedHeatmapSampler.IntensitySample(s.offsetPx(),s.nativeIntensity(),.5,s.standardFilteredIntensity(),true)).toList();
                if(defect==4)samples=samples.stream().filter(s->s.offsetPx()>0).toList();
                if(defect==5)peaks=peaks.stream().map(q->new RenderedHeatmapSampler.CrossSectionPeak(q.offsetPx(),q.intensity(),q.supportWidthPx(),true,q.prominence(),q.noiseFloor(),q.maxProfileIntensity(),q.gradientStrength(),q.gradientBalance(),q.nativeFilteredAgreement(),q.rawCenterPx(),q.lightFilteredCenterPx(),q.standardFilteredCenterPx(),q.scaleOffsetRmsPx(),q.scaleAgreement(),q.centerUncertaintyPx())).toList();
                profiles.set(i,new RenderedHeatmapSampler.CrossSectionProfile(p.samplingAnchor(),p.normalScreen(),peaks,defect!=6,samples));
            }
            var target=profiles.get(12).peaks().get(0);
            assertEquals(LegacyTrackerPolicyTest.support(profiles,12,target,PITCH,PITCH,V2),
                    LegacyTrackerPolicyTest.support(profiles,12,target,PITCH,PITCH,V3),"Unavailable proof must not authorize denial: "+defect);
        }
    }

    @Test void anIsolatedBrighterRivalCannotDenyASeparateCoherentStrand() throws Exception {
        var profiles=new ArrayList<>(fixture());
        for(int row:List.of(10,11,13,14)) {
            var p=profiles.get(row);var peaks=new ArrayList<>(p.peaks());
            peaks.set(1,LegacyTrackerPolicyTest.peak((row+1)*1000,PITCH,.99));
            profiles.set(row,new RenderedHeatmapSampler.CrossSectionProfile(p.samplingAnchor(),p.normalScreen(),peaks,true,p.intensitySamples()));
        }
        var target=profiles.get(12).peaks().get(0);var rival=profiles.get(12).peaks().get(1);
        assertEquals(0,LegacyTrackerPolicyTest.support(profiles,12,rival,PITCH,PITCH,LegacyTrackerPolicy.ORIGINAL_V02));
        assertEquals(4,LegacyTrackerPolicyTest.support(profiles,12,target,PITCH,PITCH,V2));
        assertEquals(4,LegacyTrackerPolicyTest.support(profiles,12,target,PITCH,PITCH,V3));
    }

    @Test void parallelBurstsFaintSyntheticCoresAndStrongMovingRidgesDoNotDeteriorate() throws Exception {
        for(double pitch:List.of(1.0,6.0,24.0))for(double norm:List.of(6.0,24.0)) {
            for(int duration:List.of(1,2,3))for(double separation:List.of(1.0,1.5)) {
                assertSameOutput(LegacyTrackerPolicyTest.temporaryCloseStrand(pitch,separation,duration),norm,pitch);
                assertSameOutput(LegacySyntheticPositionTest.syntheticChannel(pitch,duration,separation,false,.3),norm,pitch);
            }
            for(double amplitude:List.of(0.0,1e-20,.2))assertSameOutput(
                    LegacySyntheticPositionTest.syntheticChannel(pitch,9,3,true,amplitude),norm,pitch);
            assertSameOutput(LegacyTrackerPolicyTest.competingRidges(pitch),norm,pitch);
        }
        var actual=LegacyPairedChordTest.fixture("legacy-dominated-synthetic",17,24).profiles();
        assertSameGeometryAndFactualChangedMetadata(actual);
        assertSameOutput(LegacyPairedChordTest.fixture(24).profiles(),24,24);
    }

    private static void assertSameGeometryAndFactualChangedMetadata(
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles) throws Exception {
        var old=LegacyTrackerPolicyTest.track(profiles,24,24,V2).get(0);
        var corrected=LegacyTrackerPolicyTest.track(profiles,24,24,V3).get(0);
        assertEquals(old.id(),corrected.id());
        assertEquals(Double.doubleToRawLongBits(old.score()),Double.doubleToRawLongBits(corrected.score()));
        assertEquals(old.offsetsPx(),corrected.offsetsPx());
        for(int i=0;i<old.screenPoints().size();i++) {
            assertEquals(Double.doubleToRawLongBits(old.screenPoints().get(i).x),Double.doubleToRawLongBits(corrected.screenPoints().get(i).x));
            assertEquals(Double.doubleToRawLongBits(old.screenPoints().get(i).y),Double.doubleToRawLongBits(corrected.screenPoints().get(i).y));
        }
        var before=LegacyTrackerPolicyTest.filtered(profiles,24,24,V2);
        var after=LegacyTrackerPolicyTest.filtered(profiles,24,24,V3);
        int removed=0;
        for(int i=0;i<profiles.size();i++)for(var peak:before.get(i).peaks())if(!after.get(i).peaks().contains(peak)) {
            removed++;
            int historical=LegacyTrackerPolicyTest.support(profiles,i,peak,24,24,LegacyTrackerPolicy.ORIGINAL_V02);
            assertTrue(LegacyTrackerPolicyTest.support(profiles,i,peak,24,24,V2)>historical);
            assertEquals(historical,LegacyTrackerPolicyTest.support(profiles,i,peak,24,24,V3));
        }
        assertEquals(2,removed,"The evidence change comes from two actually denied competing states");
        var a=old.evidence();var b=corrected.evidence();
        assertEquals(a.supportedProfiles(),b.supportedProfiles());
        assertEquals(a.totalIntensity(),b.totalIntensity());
        assertEquals(a.meanIntensity(),b.meanIntensity());
        assertEquals(a.meanGradientStrength(),b.meanGradientStrength());
        assertEquals(a.longitudinalStability(),b.longitudinalStability());
        assertEquals(7.0/17,a.ambiguity());assertEquals(5.0/17,b.ambiguity());
        double prominence=0;
        int competing=0;
        for(var profile:after) {
            prominence+=profile.peaks().stream().mapToDouble(RenderedHeatmapSampler.CrossSectionPeak::prominence).max().orElse(0);
            competing+=Math.max(0,profile.peaks().size()-1);
        }
        double ambiguity=(double)competing/after.size();
        double snr=(b.meanIntensity()*.58+(prominence/after.size())*.82+b.meanGradientStrength()*.28)
                *((double)b.supportedProfiles()/after.size())*(.75+.25*b.longitudinalStability())/(1+ambiguity);
        assertEquals(ambiguity,b.ambiguity());assertEquals(snr,b.signalToNoise());
        assertTrue(b.signalToNoise()>a.signalToNoise());
    }

    @Test void newDominanceScanCooperativelyCancelsWithoutMutatingTheStage() throws Exception {
        var profiles=fixture();var identities=profiles.stream().map(RenderedHeatmapSampler.CrossSectionProfile::peaks).toList();
        int[] reads={0};
        var counted=new java.util.AbstractList<RenderedHeatmapSampler.CrossSectionProfile>() {
            public RenderedHeatmapSampler.CrossSectionProfile get(int index){reads[0]++;return profiles.get(index);}
            public int size(){return profiles.size();}
        };
        LegacyTrackerPolicyTest.support(counted,12,profiles.get(12).peaks().get(0),PITCH,PITCH,V2);
        int historicalBoundary=reads[0];var primary=new java.util.concurrent.CancellationException("dominated-support");
        var cancellable=new java.util.AbstractList<RenderedHeatmapSampler.CrossSectionProfile>() {
            int count;
            public RenderedHeatmapSampler.CrossSectionProfile get(int index){if(++count>historicalBoundary)throw primary;return profiles.get(index);}
            public int size(){return profiles.size();}
        };
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->LegacyTrackerPolicyTest.support(
                cancellable,12,profiles.get(12).peaks().get(0),PITCH,PITCH,V3));
        assertSame(primary,failure.getCause());
        for(int i=0;i<profiles.size();i++)assertSame(identities.get(i),profiles.get(i).peaks());
    }

    private static void assertSameOutput(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,double norm,double pitch) {
        assertEquals(LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(profiles,norm,pitch,V2)),
                LegacyTrackerPolicyTest.signature(LegacyTrackerPolicyTest.track(profiles,norm,pitch,V3)));
    }
}
