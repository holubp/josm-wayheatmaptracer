package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Point2D;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class LegacyPairedChordTest {
    private static final LegacyTrackerPolicy POLICY = LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1;

    @Test void actualScalarAndPeakMetadataAllowTheAtomicPairButNotInventedNeighborCenters() throws Exception {
        for(double pitch:List.of(1.0,6.0,24.0)) for(double normalization:List.of(6.0,24.0)) {
            var fixture=fixture(pitch);
            var result=new ArrayList<>(fixture.baseline());
            double before=LegacyNormalWavinessTest.maximumTurn(LegacyNormalWavinessTest.points(fixture.profiles(),result));
            assertEquals(42.536758,before,.0001);
            assertTrue(pair(fixture.profiles(),fixture.selected(),fixture.baseline(),result,2,normalization,pitch));
            double after=LegacyNormalWavinessTest.maximumTurn(LegacyNormalWavinessTest.points(fixture.profiles(),result));
            assertEquals(33.55,after,.02,"Predict the deterministic chord proposal, not an offline search optimum");
            assertTrue(LegacyNormalWavinessTest.energy(fixture.profiles(),result,pitch)
                    <LegacyNormalWavinessTest.energy(fixture.profiles(),fixture.baseline(),pitch));
            assertTrue(result.get(2)>fixture.baseline().get(2));
            assertTrue(result.get(3)>fixture.baseline().get(3));
            for(int i=0;i<6;i++) {
                assertTrue(Math.abs(result.get(i)-fixture.baseline().get(i))<=pitch*.5+1e-12);
                for(int channel=0;channel<3;channel++)assertTrue(LegacyFineLocalizationTest.scalar(fixture.profiles().get(i),result.get(i),channel)+1e-14
                        >=LegacyFineLocalizationTest.scalar(fixture.profiles().get(i),fixture.baseline().get(i),channel));
            }
            assertEquals(fixture.baseline().get(0),result.get(0));assertEquals(fixture.baseline().get(5),result.get(5));
            LegacyNormalWavinessTest.assertNoContacts(LegacyNormalWavinessTest.points(fixture.profiles(),result));
        }
    }

    @Test void productionScanUsesOneSnapshotAndKeepsCumulativeNativeCapsAcrossOverlappingPairs() throws Exception {
        for(int shape=0;shape<3;shape++) for(double pitch:List.of(1.0,6.0,24.0)) for(double normalization:List.of(6.0,24.0)) {
            var anchors=new ArrayList<Point2D.Double>();
            for(int i=0;i<9;i++)anchors.add(new Point2D.Double(i*10*pitch,
                    (shape==0?i%2==0&&i>0&&i<8:i>=2&&i<=(shape==1?4:3))?.75*pitch:0));
            var profiles=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            for(int i=0;i<9;i++) {
                var anchor=anchors.get(i);var normal=LegacyNormalWavinessTest.normal(anchors,i);
                double center=-anchor.y/normal.y;
                var peak=new RenderedHeatmapSampler.CrossSectionPeak(0,.9,3*pitch,false,.331,0,.331,0,0,1,
                        center,center,center,0,1,pitch*.5);
                profiles.add(new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(anchor.x,anchor.y),anchor,normal,
                        List.of(peak),true,LegacyFineLocalizationTest.samples(center,pitch)));
            }
            var baseline=LegacyTrackerPolicyTest.track(profiles,normalization,pitch,LegacyTrackerPolicyTest.SUPPORT).get(0);
            var candidate=LegacyTrackerPolicyTest.track(profiles,normalization,pitch,POLICY).get(0);
            var expected=new ArrayList<>(baseline.offsetsPx());var snapshot=List.copyOf(baseline.offsetsPx());
            var selected=LegacyNormalWavinessTest.measuredOffsets(profiles);int[] changes=new int[9];
            for(int i=1;i+2<9;i++) {
                double oldLeft=expected.get(i),oldRight=expected.get(i+1);
                if(pair(profiles,selected,snapshot,expected,i,normalization,pitch)) {
                    if(expected.get(i)!=oldLeft)changes[i]++;
                    if(expected.get(i+1)!=oldRight)changes[i+1]++;
                }
            }
            assertEquals(expected,candidate.offsetsPx(),"Actual public entrypoint must match one overlapping scan");
            assertTrue(LegacyNormalWavinessTest.maximumTurn(candidate.screenPoints())<=LegacyNormalWavinessTest.maximumTurn(baseline.screenPoints())+1e-10);
            if(shape==1) {
                assertEquals(baseline.offsetsPx(),expected,"Three-row flat top is a preserved unresolved case: every two-row proposal sharpens its exit");
            } else {
                assertTrue(LegacyNormalWavinessTest.energy(profiles,expected,pitch)<LegacyNormalWavinessTest.energy(profiles,baseline.offsetsPx(),pitch));
                assertTrue(java.util.stream.IntStream.range(1,8).anyMatch(i->Math.abs(expected.get(i)-baseline.offsetsPx().get(i))>.49*pitch));
            }
            if(shape==2) {
                assertTrue(LegacyNormalWavinessTest.maximumTurn(candidate.screenPoints())<LegacyNormalWavinessTest.maximumTurn(baseline.screenPoints()));
                var originalResult=List.copyOf(expected);
                assertFalse(pair(profiles,selected,snapshot,expected,2,normalization,pitch),"Repeated/overlapping requests retain original cap authority");
                assertEquals(originalResult,expected);
                var wronglyReset=new ArrayList<>(originalResult);
                assertTrue(pair(profiles,selected,originalResult,wronglyReset,2,normalization,pitch),"Sensitivity control: resetting cap authority would admit further motion");
                assertTrue(java.util.stream.IntStream.range(1,8).anyMatch(i->Math.abs(wronglyReset.get(i)-snapshot.get(i))>pitch*.5),"Resetting authority must actually violate the total cap");
            }
            for(int i=0;i<9;i++)assertTrue(Math.abs(expected.get(i)-baseline.offsetsPx().get(i))<=pitch*.5+1e-12,
                    "Overlapping pairs must cap TOTAL displacement against the original completed snapshot");
            assertEquals(baseline.offsetsPx().get(0),expected.get(0));assertEquals(baseline.offsetsPx().get(8),expected.get(8));
            LegacyNormalWavinessTest.assertNoContacts(candidate.screenPoints());
        }
    }

    @Test void refusalAndPostMutationCancellationRestoreBothSlotsAndPreservePrimaryIdentity() throws Exception {
        var fixture=fixture(24);
        var writes=new java.util.concurrent.atomic.AtomicInteger();
        var rejected=new ArrayList<Double>(fixture.baseline()) {
            @Override public Double set(int i,Double value){writes.incrementAndGet();return super.set(i,value);}
        };
        assertFalse(pair(fixture.profiles(),fixture.selected(),fixture.baseline(),rejected,1,6,24));
        assertTrue(writes.get()>=4,"This geometry refusal must evaluate then restore both temporary slots");
        for(int i=0;i<6;i++)assertSame(fixture.baseline().get(i),rejected.get(i));
        for(RuntimeException primary:List.of(new java.util.concurrent.CancellationException("synthetic"),new IllegalStateException("synthetic"))) {
            var current=new ArrayList<>(fixture.baseline());var observed=new java.util.concurrent.atomic.AtomicBoolean();
            var profiles=new java.util.AbstractList<RenderedHeatmapSampler.CrossSectionProfile>() {
                @Override public int size(){return fixture.profiles().size();}
                @Override public RenderedHeatmapSampler.CrossSectionProfile get(int i){
                    if(current.get(2)!=fixture.baseline().get(2)||current.get(3)!=fixture.baseline().get(3)) {
                        observed.set(true);throw primary;
                    }
                    return fixture.profiles().get(i);
                }
            };
            var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,
                    ()->pair(profiles,fixture.selected(),fixture.baseline(),current,2,6,24));
            assertSame(primary,failure.getCause());assertTrue(observed.get());
            for(int i=0;i<6;i++)assertSame(fixture.baseline().get(i),current.get(i));
        }
    }

    @Test void missingCensoredConflictingAndDegenerateEvidenceRefusesTheWholePair() throws Exception {
        for(int defect=0;defect<7;defect++) {
            var fixture=fixture(24);var profiles=new ArrayList<>(fixture.profiles());var original=profiles.get(3);
            var peak=original.peaks().get(0);var samples=original.intensitySamples();var normal=original.normalScreen();
            if(defect==0)samples=List.of();
            if(defect==1)samples=samples.stream().map(s->new RenderedHeatmapSampler.IntensitySample(s.offsetPx(),s.nativeIntensity(),s.lightFilteredIntensity(),s.standardFilteredIntensity(),false)).toList();
            if(defect==2)normal=new Point2D.Double(0,0);
            if(defect==3)normal=new Point2D.Double(Double.NaN,1);
            if(defect==4)peak=new RenderedHeatmapSampler.CrossSectionPeak(peak.offsetPx(),peak.intensity(),peak.supportWidthPx(),false,
                    peak.prominence(),peak.noiseFloor(),peak.maxProfileIntensity(),peak.gradientStrength(),peak.gradientBalance(),peak.nativeFilteredAgreement(),
                    peak.rawCenterPx(),peak.rawCenterPx()+24,peak.standardFilteredCenterPx(),0,1,12);
            var peaks=defect==6?List.of(peak,LegacyTrackerPolicyTest.peak(peak.rawCenterPx(),24,.96)):List.of(peak);
            profiles.set(3,new RenderedHeatmapSampler.CrossSectionProfile(original.samplingAnchor(),normal,peaks,defect!=5,samples));
            var result=new ArrayList<>(fixture.baseline());
            assertFalse(pair(profiles,fixture.selected(),fixture.baseline(),result,2,6,24),"defect="+defect);
            for(int i=0;i<6;i++)assertSame(fixture.baseline().get(i),result.get(i));
        }
    }

    @Test void activeFaintSignedArcsAndHairpinsKeepWholeGeometryScalarAndUncertaintyBounds() throws Exception {
        for(double pitch:List.of(1.0,6.0,24.0)) for(double normalization:List.of(6.0,24.0))
            for(double sweep:List.of(Math.toRadians(150),Math.toRadians(-150),Math.PI)) {
                var profiles=LegacyFineLocalizationTest.withScalarEvidence(LegacyNormalWavinessTest.circleTargetProfiles(pitch,sweep),pitch);
                var measured=LegacyNormalWavinessTest.measuredOffsets(profiles);
                var baseline=LegacyNormalWavinessTest.smooth(profiles,measured,normalization,pitch,LegacyTrackerPolicy.ORIGINAL_V02);
                var result=scan(profiles,measured,baseline,normalization,pitch);
                assertTrue(LegacyNormalWavinessTest.energy(profiles,result,pitch)<=LegacyNormalWavinessTest.energy(profiles,baseline,pitch)+1e-20);
                assertTrue(LegacyNormalWavinessTest.maximumTurn(LegacyNormalWavinessTest.points(profiles,result))
                        <=LegacyNormalWavinessTest.maximumTurn(LegacyNormalWavinessTest.points(profiles,baseline))+1e-10);
                for(int i=0;i<17;i++) {
                    assertTrue(Math.abs(result.get(i)-baseline.get(i))<=pitch*.5+1e-12);
                    assertTrue(Math.abs(result.get(i)-measured.get(i))<=Math.abs(baseline.get(i)-measured.get(i))+1e-12);
                    for(int channel=0;channel<3;channel++)assertTrue(LegacyFineLocalizationTest.scalar(profiles.get(i),result.get(i),channel)+1e-14
                            >=LegacyFineLocalizationTest.scalar(profiles.get(i),baseline.get(i),channel));
                }
                assertEquals(baseline.get(0),result.get(0));assertEquals(baseline.get(16),result.get(16));
                LegacyNormalWavinessTest.assertNoContacts(LegacyNormalWavinessTest.points(profiles,result));
            }
    }

    static List<Double> scan(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,List<Double> selected,
            List<Double> historical,double normalization,double pitch) throws Exception {
        var result=new ArrayList<>(historical);var snapshot=List.copyOf(historical);
        for(int i=1;i+2<profiles.size();i++)pair(profiles,selected,snapshot,result,i,normalization,pitch);
        return result;
    }

    record Fixture(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,List<Double> baseline,List<Double> selected) { }

    static Fixture fixture(double pitch) throws Exception {
        var samples=new ArrayList<List<RenderedHeatmapSampler.IntensitySample>>();
        var peaks=new ArrayList<List<RenderedHeatmapSampler.CrossSectionPeak>>();
        var anchors=new Point2D.Double[6];var normals=new Point2D.Double[6];var baseline=new Double[6];
        for(int i=0;i<6;i++){samples.add(new ArrayList<>());peaks.add(new ArrayList<>());}
        for(String[] row:resource("profiles.csv")) {
            int i=Integer.parseInt(row[0]);anchors[i]=new Point2D.Double(Double.parseDouble(row[1])*pitch,Double.parseDouble(row[2])*pitch);
            normals[i]=new Point2D.Double(Double.parseDouble(row[3]),Double.parseDouble(row[4]));baseline[i]=Double.parseDouble(row[5])*pitch;
            samples.get(i).add(new RenderedHeatmapSampler.IntensitySample(Double.parseDouble(row[6])*pitch,
                    Double.parseDouble(row[7]),Double.parseDouble(row[8]),Double.parseDouble(row[9]),Boolean.parseBoolean(row[10])));
        }
        for(String[] row:resource("peaks.csv")) {
            double[] v=new double[16];for(int j=0;j<16;j++)if(j!=3)v[j]=Double.parseDouble(row[j+1]);
            for(int j:new int[]{0,2,10,11,12,13,15})v[j]*=pitch;
            peaks.get(Integer.parseInt(row[0])).add(new RenderedHeatmapSampler.CrossSectionPeak(v[0],v[1],v[2],Boolean.parseBoolean(row[4]),
                    v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15]));
        }
        var profiles=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();var selected=new ArrayList<Double>();
        for(int i=0;i<6;i++) {
            profiles.add(new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(anchors[i].x,anchors[i].y),anchors[i],normals[i],peaks.get(i),true,samples.get(i)));
            selected.add(peaks.get(i).get(0).offsetPx());
        }
        return new Fixture(List.copyOf(profiles),List.of(baseline),List.copyOf(selected));
    }

    private static List<String[]> resource(String name) throws Exception {
        try(var stream=LegacyPairedChordTest.class.getResourceAsStream("/legacy-paired-nw/"+name)) {
            assertNotNull(stream);
            try(var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
                return reader.lines().skip(1).map(line->line.split(",")).toList();
            }
        }
    }

    static boolean pair(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,List<Double> selected,List<Double> historical,
            List<Double> current,int index,double normalization,double pitch) throws Exception {
        var context=LegacyTrackerPolicyTest.context(POLICY,normalization,pitch);
        var method=RidgeTracker.class.getDeclaredMethod("localizePairedChord",List.class,List.class,List.class,List.class,int.class,context.getClass());
        method.setAccessible(true);
        return (boolean)method.invoke(new RidgeTracker(),profiles,selected,historical,current,index,context);
    }
}
