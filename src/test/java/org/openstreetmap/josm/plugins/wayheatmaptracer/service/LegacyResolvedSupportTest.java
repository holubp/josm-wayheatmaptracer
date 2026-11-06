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

    @Test void nonIncreasingNativeSupportCapReturnsBeforeMeasuredRivalScan() throws Exception {
        var target=LegacyTrackerPolicyTest.peak(0,1,.7);
        var rival=LegacyTrackerPolicyTest.peak(2,1,.8);
        var complete=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        complete.add(capProfile(0,List.of(target),LegacySyntheticPositionTest.channelSamples(1,0,.1,false,false,0)));
        complete.add(capProfile(1,List.of(target,rival),LegacySyntheticPositionTest.channelSamples(1,0,.1,false,true,2)));
        complete.add(capProfile(2,List.of(target),LegacySyntheticPositionTest.channelSamples(1,0,.1,false,false,0)));
        assertEquals(2,LegacyTrackerPolicyTest.support(complete,1,target,1,1,V4),
                "The historical target has support two, equal to the supplied native support");

        int[] reads={0};
        var historicalOnly=new java.util.AbstractList<RenderedHeatmapSampler.CrossSectionProfile>() {
            @Override public RenderedHeatmapSampler.CrossSectionProfile get(int index) {
                if(++reads[0]>3)throw new AssertionError("measured scalar/rival scan continued after historical support was sufficient");
                return complete.get(index);
            }
            @Override public int size(){return complete.size();}
        };
        var method=RidgeTracker.class.getDeclaredMethod("capDominatedSupportGain",List.class,int.class,
                RenderedHeatmapSampler.CrossSectionPeak.class,int.class,double.class);
        method.setAccessible(true);
        assertEquals(2,method.invoke(new RidgeTracker(),historicalOnly,1,target,2,1.0),
                "The V4 support return remains exactly the supplied native count");
        assertEquals(3,reads[0],"Only the target and historical neighbor profiles should be visited");
    }

    @Test void v4AnchorRankingComputesSupportAndQualityOnlyOncePerEligiblePeak() throws Exception {
        var target=LegacyTrackerPolicyTest.peak(0,24,.7);
        var context=LegacyTrackerPolicyTest.context(V4,24,24);
        var old=RidgeTracker.class.getDeclaredMethod("reliableAnchor",List.class,int.class,double.class,double.class,context.getClass());
        var optimized=RidgeTracker.class.getDeclaredMethod("reliableAnchorWithReusedSupport",List.class,int.class,double.class,double.class,context.getClass());
        old.setAccessible(true);optimized.setAccessible(true);
        var weaker=LegacyTrackerPolicyTest.peak(24,24,.62);
        for(var peaks:List.of(List.of(target),List.of(target,weaker))) {
            var complete=repeatedPeaks(peaks);
            var oldReads=new int[1];
            var oldCounted=countReads(complete,oldReads);
            assertNotNull(old.invoke(new RidgeTracker(),oldCounted,4,24,Double.NaN,context));
            assertEquals(peaks.size()==1?19:66,oldReads[0],"Baseline V4 selector read count for eligible peak bank");

            var newReads=new int[1];
            var newCounted=countReads(complete,newReads);
            assertNotNull(optimized.invoke(new RidgeTracker(),newCounted,4,24,Double.NaN,context));
            assertEquals(peaks.size()==1?10:27,newReads[0],"Observed profile-list reads for one support-aware anchor pass, including V4 cap/rival work");
        }
    }

    @Test void v4OnePassAnchorSelectionMatchesHistoricalIdentityTieAndNaNRules() throws Exception {
        var context=LegacyTrackerPolicyTest.context(V4,24,24);
        var old=RidgeTracker.class.getDeclaredMethod("reliableAnchor",List.class,int.class,double.class,double.class,context.getClass());
        var optimized=RidgeTracker.class.getDeclaredMethod("reliableAnchorWithReusedSupport",List.class,int.class,double.class,double.class,context.getClass());
        old.setAccessible(true);optimized.setAccessible(true);
        var target=LegacyTrackerPolicyTest.peak(0,24,.7);
        var weaker=LegacyTrackerPolicyTest.peak(24,24,.62);
        var distinctTie=List.of(LegacyTrackerPolicyTest.peak(-24,24,.7),LegacyTrackerPolicyTest.peak(24,24,.7));
        var duplicateIdentity=List.of(target,target);
        var nanQuality=new RenderedHeatmapSampler.CrossSectionPeak(0,.8,24,false,.1,0,.8,Double.NaN,.5,1,0,0,0,0,1,.5);
        var cases=List.of(
                repeatedPeaks(List.of(target,weaker)),
                repeatedPeaks(List.of(weaker,target,target)),
                repeatedPeaks(distinctTie),
                repeatedPeaks(duplicateIdentity),
                repeatedPeaks(List.of(nanQuality,weaker)),
                repeatedPeaks(List.of(weaker,nanQuality)),
                repeatedPeaks(List.of())
        );
        for(var policy:List.of(V3,V4)) {
            var selectedContext=LegacyTrackerPolicyTest.context(policy,24,24);
            for(var profiles:cases) {
                Object expected=old.invoke(new RidgeTracker(),profiles,4,24,Double.NaN,selectedContext);
                Object actual=optimized.invoke(new RidgeTracker(),profiles,4,24,Double.NaN,selectedContext);
                assertAnchorEqual(expected,actual);
            }
        }
    }

    @Test void v4CandidateOutputsMatchPreReuseRuntimeGolden() throws Exception {
        var profiles=LegacyTrackerPolicyTest.competingRidges(24);
        var output=LegacyTrackerPolicyTest.track(profiles,6,24,V4);
        assertEquals(List.of("ridge-1","ridge-2"),output.stream().map(candidate->candidate.id()).toList());
        String signature=LegacyTrackerPolicyTest.signature(output);
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(signature.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("399a4030176df4ddd1d0a7136d1021326da7086794b62440c6b605180cc090b3",digest);
    }

    @Test void v4ReusedAnchorsPreserveThreeSeedAnchoredPathResults() throws Exception {
        var profiles=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for(int i=0;i<9;i++) {
            profiles.add(LegacyTrackerPolicyTest.profile(i,List.of(
                    LegacyTrackerPolicyTest.peak(-48,24,.25),
                    LegacyTrackerPolicyTest.peak(0,24,.70),
                    LegacyTrackerPolicyTest.peak(48,24,.24)),48));
        }
        var tracker=new RidgeTracker();
        var context=LegacyTrackerPolicyTest.context(V4,6,24);
        var suppressMethod=RidgeTracker.class.getDeclaredMethod("suppressLongitudinalNoise",List.class,context.getClass(),
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.class);
        suppressMethod.setAccessible(true);
        @SuppressWarnings("unchecked") var retained=(List<RenderedHeatmapSampler.CrossSectionProfile>)suppressMethod.invoke(
                tracker,profiles,context,org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE);
        var seedsMethod=RidgeTracker.class.getDeclaredMethod("collectSeedOffsets",List.class);seedsMethod.setAccessible(true);
        @SuppressWarnings("unchecked") var seeds=(List<Double>)seedsMethod.invoke(tracker,retained);
        assertEquals(3,seeds.size(),"The control exercises three distinct Legacy seeds");
        var edgeMethod=RidgeTracker.class.getDeclaredMethod("profileEdgeLimit",List.class);edgeMethod.setAccessible(true);
        double edge=(double)edgeMethod.invoke(tracker,retained);
        var oldAnchorsMethod=RidgeTracker.class.getDeclaredMethod("reliableAnchors",List.class,double.class,double.class,context.getClass());oldAnchorsMethod.setAccessible(true);
        var newAnchorsMethod=RidgeTracker.class.getDeclaredMethod("reliableAnchorsWithReusedSupport",List.class,double.class,double.class,context.getClass());newAnchorsMethod.setAccessible(true);
        var oldAnchors=(List<?>)oldAnchorsMethod.invoke(tracker,retained,6.0,edge,context);
        var reusedAnchors=(List<?>)newAnchorsMethod.invoke(tracker,retained,6.0,edge,context);
        assertTrue(oldAnchors.size()>=2,"The three-seed fixture must exercise nonempty anchored intervals");
        assertEquals(oldAnchors,reusedAnchors,"One-pass V4 anchor identities and qualities must exactly match the old scan");

        var stateClass=Class.forName(RidgeTracker.class.getName()+"$State");
        var baseMethod=RidgeTracker.class.getDeclaredMethod("bestPathForSeed",List.class,double.class,double.class,context.getClass());baseMethod.setAccessible(true);
        var oldPathMethod=RidgeTracker.class.getDeclaredMethod("anchoredPath",List.class,stateClass,double.class,double.class,context.getClass());oldPathMethod.setAccessible(true);
        var reusedPathMethod=RidgeTracker.class.getDeclaredMethod("anchoredPathWithAnchors",List.class,stateClass,double.class,double.class,context.getClass(),List.class);reusedPathMethod.setAccessible(true);
        for(double seed:seeds) {
            Object base=baseMethod.invoke(tracker,retained,seed,6.0,context);
            Object before=oldPathMethod.invoke(tracker,retained,base,6.0,edge,context);
            Object after=reusedPathMethod.invoke(tracker,retained,base,6.0,edge,context,reusedAnchors);
            assertEquals(before,after,"Every seed must reuse the same exact anchored path result");
        }
    }

    private static RenderedHeatmapSampler.CrossSectionProfile capProfile(int index,
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks,
            List<RenderedHeatmapSampler.IntensitySample> samples) {
        var anchor=new Point2D.Double(index*20,0);
        return new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(anchor.x,anchor.y),anchor,
                new Point2D.Double(0,1),peaks,true,samples);
    }

    private static List<RenderedHeatmapSampler.CrossSectionProfile> repeatedPeaks(
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks) {
        var result=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for(int i=0;i<9;i++)result.add(LegacyTrackerPolicyTest.profile(i,peaks,48));
        return result;
    }

    private static List<RenderedHeatmapSampler.CrossSectionProfile> countReads(
            List<RenderedHeatmapSampler.CrossSectionProfile> complete,int[] reads) {
        return new java.util.AbstractList<>(){
            public RenderedHeatmapSampler.CrossSectionProfile get(int i){reads[0]++;return complete.get(i);}
            public int size(){return complete.size();}
        };
    }

    private static void assertAnchorEqual(Object expected,Object actual) throws Exception {
        assertEquals(expected==null,actual==null,"Anchor presence must retain historical gates");
        if(expected==null)return;
        for(String accessor:List.of("index","peak")) {
            var method=expected.getClass().getDeclaredMethod(accessor);method.setAccessible(true);
            Object left=method.invoke(expected),right=method.invoke(actual);
            if(accessor.equals("peak"))assertSame(left,right);else assertEquals(left,right);
        }
        var quality=expected.getClass().getDeclaredMethod("quality");quality.setAccessible(true);
        assertEquals(Double.doubleToRawLongBits((double)quality.invoke(expected)),
                Double.doubleToRawLongBits((double)quality.invoke(actual)));
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
