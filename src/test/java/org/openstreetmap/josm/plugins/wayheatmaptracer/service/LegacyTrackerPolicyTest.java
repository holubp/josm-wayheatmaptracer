package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class LegacyTrackerPolicyTest {
    static final LegacyTrackerPolicy SUPPORT = LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_V1;
    static final LegacyTrackerPolicy COMBINED = LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_GEOMETRY_V1;

    @Test void onlyIndependentlyScopedPoliciesAreSelectable() {
        assertEquals(7, LegacyTrackerPolicy.values().length);
        for (var p : LegacyTrackerPolicy.values()) {
            if (p.isParked()) {
                assertThrows(IllegalArgumentException.class, () -> LegacyTrackerPolicy.fromId(p.id()));
                assertThrows(IllegalArgumentException.class, () -> new RidgeTracker().track(competingRidges(24),6,24,CancellationProbe.NONE,p));
            } else assertSame(p, LegacyTrackerPolicy.fromId(p.id()));
        }
        for (String id : List.of("native-continuity-v1", "native-motion-v1", "native-motion-reciprocal-v1",
                "native-geometry-smoothing-v1", "native-pure-support-v1", "unknown")) {
            assertThrows(IllegalArgumentException.class, () -> LegacyTrackerPolicy.fromId(id));
        }
        assertThrows(IllegalArgumentException.class, () -> LegacyTrackerPolicy.fromId(null));
        assertTrue(SUPPORT.usesNativeSupport());
        assertFalse(SUPPORT.guardsSmoothingGeometry());
        assertFalse(LegacyTrackerPolicy.GEOMETRY_ONLY_V1.usesNativeSupport());
        assertTrue(LegacyTrackerPolicy.GEOMETRY_ONLY_V1.guardsSmoothingGeometry());
        assertTrue(COMBINED.usesNativeSupport());
        assertTrue(COMBINED.guardsSmoothingGeometry());
    }

    @Test void defaultAndExplicitHistoricalPoliciesAreBitExact() {
        var tracker = new RidgeTracker();
        for (var fixture : historicalFixtures()) for (double norm : List.of(1.0, 6.0, 24.0, Double.NaN)) {
            var old = tracker.track(fixture, norm, CancellationProbe.NONE);
            var explicit = tracker.track(fixture, norm, Double.NaN, CancellationProbe.NONE, LegacyTrackerPolicy.ORIGINAL_V02);
            assertEquals(signature(old), signature(explicit));
        }
    }

    @Test void historicalOutputMatchesIndependentlyCompiledBase88e06715() throws Exception {
        assertEquals("cb7d069279171ed59005086ee95eb8e68409f2c61bcc36ec486f4f95daa1882c", historicalDigest());
    }

    @Test void reciprocalSupportRecoversPhysicalSupportWithoutRequiringNewGeometryCapability() throws Exception {
        for (double pitch : List.of(6.0, 24.0)) {
            var profiles = competingRidges(pitch);
            var filtered = filtered(profiles, 6, pitch, SUPPORT);
            for (int i = 0; i < profiles.size(); i++) {
                assertTrue(filtered.get(i).peaks().contains(profiles.get(i).peaks().get(0)), "moving primary row" + i);
                assertEquals(i == 0 || i == 8 ? 1 : 2, support(profiles, i, profiles.get(i).peaks().get(0), 6, pitch, SUPPORT));
            }
        }
        var large = competingRidges(24);
        assertEquals(0, support(large, 4, large.get(4).peaks().get(0), 6, 24, LegacyTrackerPolicy.ORIGINAL_V02));
        assertEquals(1, filtered(large, 6, 24, LegacyTrackerPolicy.ORIGINAL_V02).get(4).peaks().size());
    }

    @Test void nineProfileGeometryFailureRemainsExplicitBaselineLimitationWithoutDeterioration() {
        var profiles = competingRidges(24);
        for (double norm : List.of(6.0, 24.0)) {
            var original = track(profiles, norm, 24, LegacyTrackerPolicy.ORIGINAL_V02);
            var pure = track(profiles, norm, 24, SUPPORT);
            double oldError = minimumPrimaryError(original, 24);
            double newError = minimumPrimaryError(pure, 24);
            assertTrue(oldError > 1.6, "The stronger-drift positive oracle is a measured historical failure");
            assertTrue(newError > 1.6, "Do not relabel this unresolved positive oracle as fixed");
            assertTrue(newError <= oldError + 1e-12, "The scoped support repair must not worsen that known limitation");
        }
    }

    @Test void reciprocalSupportRequiresUniqueOwnershipBothBoundsAndCurrentStagePeaks() throws Exception {
        var target = peak(0, 24, .62);
        var narrow = peak(30, 8, .62);
        var targetRow = profile(0, List.of(target), 48);
        var narrowRow = profile(1, List.of(narrow), 48);
        // Target's 36px bound alone is insufficient: the neighbor owns 24px.
        assertEquals(0, support(List.of(targetRow,narrowRow),0,target,6,24,SUPPORT));
        var tiedRow = profile(1,List.of(peak(-24,24,.62),peak(24,24,.62)),48);
        assertEquals(0,support(List.of(targetRow,tiedRow),0,target,6,24,SUPPORT));
        var duplicateRow = profile(0,List.of(target,peak(0,24,.62)),48);
        assertEquals(0,support(List.of(duplicateRow,profile(1,List.of(peak(0,24,.62)),48)),0,target,6,24,SUPPORT));
        var invalidWidth = profile(1,List.of(peak(0,Double.NaN,.62)),48);
        assertEquals(0,support(List.of(targetRow,invalidWidth),0,target,6,24,SUPPORT));
        // A three-row transient may legitimately meet the historical support
        // count. The output regression below, not a duration veto, owns safety.
        var raw = temporaryCloseStrand(24,1,3);
        var stage = new ArrayList<>(raw);
        stage.set(3,profile(3,List.of(raw.get(3).peaks().get(0)),48));
        stage.set(5,profile(5,List.of(raw.get(5).peaks().get(0)),48));
        var bright = raw.get(4).peaks().get(1);
        assertEquals(2,support(raw,4,bright,24,24,SUPPORT));
        assertEquals(0,support(List.copyOf(stage),4,bright,24,24,SUPPORT),
                "Absent neighboring peaks cannot authorize a later anchor");
        var physical=competingRidges(24);
        for(int i=0;i<physical.size();i++) assertEquals(
                support(physical,i,physical.get(i).peaks().get(0),6,24,SUPPORT),
                support(physical,i,physical.get(i).peaks().get(0),24,24,SUPPORT));
    }

    @Test void geometryOnlyDoesNotChangeSupportAnchorsOrMotionBeforeSmoothing() throws Exception {
        var profiles = competingRidges(24);
        var old = context(LegacyTrackerPolicy.ORIGINAL_V02, 6, 24);
        var geo = context(LegacyTrackerPolicy.GEOMETRY_ONLY_V1, 6, 24);
        assertEquals(filtered(profiles, 6, 24, LegacyTrackerPolicy.ORIGINAL_V02),
                filtered(profiles, 6, 24, LegacyTrackerPolicy.GEOMETRY_ONLY_V1));
        var stateClass = Class.forName(RidgeTracker.class.getName() + "$State");
        var constructor = stateClass.getDeclaredConstructor(double.class, double.class, double.class, List.class, List.class);
        constructor.setAccessible(true);
        var state = constructor.newInstance(0.0, 0.0, 0.0, List.of(0.0), List.of(.48));
        var append = RidgeTracker.class.getDeclaredMethod("append", stateClass, RenderedHeatmapSampler.CrossSectionPeak.class, old.getClass());
        append.setAccessible(true);
        var peak = peak(24, 24, .48);
        assertEquals(append.invoke(new RidgeTracker(), state, peak, old), append.invoke(new RidgeTracker(), state, peak, geo));
        assertEquals(append.invoke(new RidgeTracker(), state, peak, old),
                append.invoke(new RidgeTracker(), state, peak, context(SUPPORT, 6, 24)));
    }

    @Test void everyPolicyKeepsExactHistoricalAnchorJumpBound() throws Exception {
        for (double norm : List.of(6.0, 24.0)) {
            var profiles = new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            for (int i = 0; i < 9; i++) profiles.add(profile(i, List.of(peak(i == 4 ? 1.4 * norm : 0, 48, .90)), 48));
            var context = context(SUPPORT, norm, 24);
            var tracker = new RidgeTracker();
            var one = RidgeTracker.class.getDeclaredMethod("reliableAnchor", List.class, int.class, double.class, double.class, context.getClass());
            one.setAccessible(true);
            assertNotNull(one.invoke(tracker, profiles, 4, norm, Double.NaN, context), "Support qualifies the isolated anchor before adjacency check");
            for (var policy : LegacyTrackerPolicy.values()) {
                var c = context(policy, norm, 24);
                var anchors = RidgeTracker.class.getDeclaredMethod("reliableAnchors", List.class, double.class, double.class, c.getClass());
                anchors.setAccessible(true);
                List<?> result = (List<?>) anchors.invoke(tracker, profiles, norm, Double.NaN, c);
                for (Object anchor : result) {
                    var index = anchor.getClass().getDeclaredMethod("index");index.setAccessible(true);
                    assertNotEquals(4, index.invoke(anchor), "The 1.35*normalization skip bound must remain historical for" + policy);
                }
            }
        }
    }

    @Test void brightTemporaryCloseStrandsCannotCreateNewOneTwoOrThreeProfileExcursions() {
        for (double norm : List.of(6.0, 24.0)) for (double separation : List.of(1.0, 1.5)) for (int duration : List.of(1, 2, 3)) {
            var profiles = temporaryCloseStrand(24, separation, duration);
            double baseline = excursion(track(profiles, norm, 24, LegacyTrackerPolicy.ORIGINAL_V02));
            for(var policy:List.of(SUPPORT,COMBINED,LegacyTrackerPolicy.FINE_LOCALIZATION_V1,LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_FINE_LOCALIZATION_V1,LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1)) {
              double changed = excursion(track(profiles, norm, 24, policy));
              assertTrue(changed <= baseline + 1e-9,
                    "New transient regression norm=" + norm + " separation=" + separation + " duration=" + duration
                    + " original=" + baseline / 24 + " policy=" + policy + " changed=" + changed / 24);
            }
        }
    }

    @Test void alternatingParallelIsolationTiesNoSignalAndGapControlsStaySafe() {
        for (double norm : List.of(6.0, 24.0)) {
            var parallel = new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            var isolated = new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            var ties = new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            var empty = new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            var gaps = new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            for (int i = 0; i < 9; i++) {
                parallel.add(profile(i, List.of(peak(0,24,i%2==0?.50:.48), peak(36,24,i%2==0?.48:.50)),48));
                isolated.add(profile(i, i==4 ? List.of(peak(0,24,.62),peak(24,24,.96)):List.of(peak(0,24,.62)),48));
                ties.add(profile(i, List.of(peak(-24,24,.5),peak(24,24,.5)),48));
                empty.add(profile(i, List.of(),48));
                gaps.add(profile(i, i==3||i==4 ? List.of():List.of(peak(144,48,.70)),48));
            }
            for (var policy : List.of(SUPPORT, LegacyTrackerPolicy.GEOMETRY_ONLY_V1, COMBINED, LegacyTrackerPolicy.FINE_LOCALIZATION_V1, LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_FINE_LOCALIZATION_V1,LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1)) {
                var modes = track(parallel,norm,24,policy);
                assertTrue(modes.stream().anyMatch(c -> nearConstant(c.offsetsPx(),0,24*.45)));
                assertTrue(modes.stream().anyMatch(c -> nearConstant(c.offsetsPx(),36,24*.45)));
                assertTrue(excursion(track(isolated,norm,24,policy)) <= excursion(track(isolated,norm,24,LegacyTrackerPolicy.ORIGINAL_V02))+1e-9);
                var oldTies = track(ties,norm,24,LegacyTrackerPolicy.ORIGINAL_V02);
                var newTies = track(ties,norm,24,policy);
                assertEquals(oldTies.size(),newTies.size());
                for(int c=0;c<oldTies.size();c++) {
                    assertEquals(oldTies.get(c).id(),newTies.get(c).id());
                    assertEquals(oldTies.get(c).score(),newTies.get(c).score());
                    assertEquals(oldTies.get(c).evidence(),newTies.get(c).evidence());
                    for(int row=0;row<9;row++) assertEquals(oldTies.get(c).offsetsPx().get(row),newTies.get(c).offsetsPx().get(row),1e-12);
                }
                var absent = track(empty,norm,24,policy).get(0);
                assertFalse(absent.evidence().hasSignal());assertTrue(nearConstant(absent.offsetsPx(),0,0));
                assertTrue(track(gaps,norm,24,policy).get(0).offsetsPx().stream().allMatch(x -> x>120));
            }
        }
    }

    @Test void explicitExperimentsRequireNativePitchAndRetainCancellation() {
        for (var policy : List.of(SUPPORT, LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1)) {
            for (double invalid : List.of(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertThrows(IllegalArgumentException.class, () -> track(competingRidges(24),6,invalid,policy));
            }
            assertThrows(CancellationException.class, () -> new RidgeTracker().track(competingRidges(24),6,24,()->true,policy));
            var checkpoints=new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(CancellationException.class, () -> new RidgeTracker().track(competingRidges(24),6,24,
                    ()->checkpoints.incrementAndGet()>20,policy));
            assertEquals(21,checkpoints.get(),"Cancellation must propagate during traversal");
        }
    }

    @Test void combinationHasExactlyTheTwoIndependentlyTestedCapabilities() throws Exception {
        for(double pitch:List.of(6.0,24.0)) {
            var competing=competingRidges(pitch);
            assertEquals(filtered(competing,6,pitch,SUPPORT),filtered(competing,6,pitch,COMBINED));
            var straight=LegacyNormalWavinessTest.straightTargetProfiles(pitch);
            var measured=LegacyNormalWavinessTest.measuredOffsets(straight);
            assertEquals(LegacyNormalWavinessTest.smooth(straight,measured,pitch,pitch,LegacyTrackerPolicy.GEOMETRY_ONLY_V1),
                    LegacyNormalWavinessTest.smooth(straight,measured,pitch,pitch,COMBINED));
            for(double norm:List.of(6.0,pitch)) {
                var result=track(straight,norm,pitch,COMBINED).get(0);
                for(var point:result.screenPoints())assertEquals(0,point.y,1e-9);
                assertEquals(measured.get(0),result.offsetsPx().get(0));
                assertEquals(measured.get(16),result.offsetsPx().get(16));
                LegacyNormalWavinessTest.assertNoContacts(result.screenPoints());
                for(double sweep:List.of(Math.toRadians(150),Math.toRadians(-150),Math.PI)) {
                    var arc=LegacyNormalWavinessTest.circleTargetProfiles(pitch,sweep);
                    var nativeOffsets=LegacyNormalWavinessTest.measuredOffsets(arc);
                    var tracked=track(arc,norm,pitch,COMBINED).get(0);
                    for(int i=0;i<17;i++)assertTrue(tracked.screenPoints().get(i).distance(
                            LegacyNormalWavinessTest.point(arc.get(i),nativeOffsets.get(i)))<=.5*pitch+1e-9);
                    assertTrue(LegacyNormalWavinessTest.energy(arc,tracked.offsetsPx(),pitch)
                            <=LegacyNormalWavinessTest.energy(arc,nativeOffsets,pitch)+1e-20);
                    assertEquals(nativeOffsets.get(0),tracked.offsetsPx().get(0));
                    assertEquals(nativeOffsets.get(16),tracked.offsetsPx().get(16));
                    LegacyNormalWavinessTest.assertNoContacts(tracked.screenPoints());
                }
            }
        }
    }

    static List<List<RenderedHeatmapSampler.CrossSectionProfile>> historicalFixtures() {
        return List.of(competingRidges(6), competingRidges(24), temporaryCloseStrand(24,1,1),
                temporaryCloseStrand(24,1,3), temporaryCloseStrand(24,1.5,3),
                LegacyNormalWavinessTest.straightTargetProfiles(6), LegacyNormalWavinessTest.straightTargetProfiles(24));
    }
    static String historicalDigest() throws Exception {
        var text = new StringBuilder();
        for (var profiles : historicalFixtures()) for (double norm : List.of(1.0,6.0,24.0,Double.NaN))
            text.append(signature(new RidgeTracker().track(profiles,norm,CancellationProbe.NONE)));
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }
    static String signature(List<CenterlineCandidate> candidates) {
        var result=new StringBuilder();
        for(var c:candidates){result.append(c.id()).append(':').append(Long.toHexString(Double.doubleToRawLongBits(c.score())));
            for(double x:c.offsetsPx())result.append('/').append(Long.toHexString(Double.doubleToRawLongBits(x)));
            for(var p:c.screenPoints())result.append('@').append(Long.toHexString(Double.doubleToRawLongBits(p.x))).append(',').append(Long.toHexString(Double.doubleToRawLongBits(p.y)));
            result.append(c.evidence().toString());}
        return result.toString();
    }
    static List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> p,double norm,double pitch,LegacyTrackerPolicy policy){
        if (!policy.isParked()) return new RidgeTracker().track(p,norm,pitch,CancellationProbe.NONE,policy);
        // Preserve the former experiment's behavioral controls as private evidence;
        // neither public overload nor policy-id parsing may select the failed model.
        try {
            var method=RidgeTracker.class.getDeclaredMethod("trackWithPolicy",List.class,double.class,double.class,CancellationProbe.class,LegacyTrackerPolicy.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked") var result=(List<CenterlineCandidate>)method.invoke(new RidgeTracker(),p,norm,pitch,CancellationProbe.NONE,policy);
            return result;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static List<RenderedHeatmapSampler.CrossSectionProfile> competingRidges(double pitch){
        var result=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for(int i=0;i<9;i++)result.add(profile(i,List.of(peak((6-i)*pitch,pitch,.48),peak(8*pitch,2*pitch,.37)),2*pitch));
        return List.copyOf(result);
    }
    static List<RenderedHeatmapSampler.CrossSectionProfile> temporaryCloseStrand(double pitch,double separation,int duration){
        var result=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for(int i=0;i<9;i++)result.add(profile(i,i>=3&&i<3+duration?List.of(peak(0,pitch,.62),peak(separation*pitch,pitch,.96)):List.of(peak(0,pitch,.62)),2*pitch));
        return List.copyOf(result);
    }
    static RenderedHeatmapSampler.CrossSectionPeak peak(double x,double w,double intensity){return new RenderedHeatmapSampler.CrossSectionPeak(x,intensity,w,false);}
    static RenderedHeatmapSampler.CrossSectionProfile profile(int i,List<RenderedHeatmapSampler.CrossSectionPeak> peaks,double spacing){
        var a=new Point2D.Double(i*spacing,0);return new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(a.x,a.y),a,new Point2D.Double(0,1),peaks);
    }
    static boolean nearConstant(List<Double> v,double center,double tolerance){return v.stream().allMatch(x->Math.abs(x-center)<=tolerance);}
    static double excursion(List<CenterlineCandidate> c){return c.get(0).offsetsPx().stream().mapToDouble(Math::abs).max().orElseThrow();}
    static double minimumPrimaryError(List<CenterlineCandidate> c,double pitch){return c.stream().mapToDouble(candidate->{double sum=0;for(int i=0;i<9;i++)sum+=Math.abs(candidate.offsetsPx().get(i)/pitch-(6-i));return sum/9;}).min().orElseThrow();}
    static Object context(LegacyTrackerPolicy p,double norm,double pitch)throws Exception{
        var c=Class.forName(RidgeTracker.class.getName()+"$ContinuitySupportPolicy");var ctor=c.getDeclaredConstructor(LegacyTrackerPolicy.class,double.class,double.class);ctor.setAccessible(true);return ctor.newInstance(p,norm,pitch);
    }
    @SuppressWarnings("unchecked") static List<RenderedHeatmapSampler.CrossSectionProfile> filtered(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,double norm,double pitch,LegacyTrackerPolicy p)throws Exception{
        Object c=context(p,norm,pitch);var m=RidgeTracker.class.getDeclaredMethod("suppressLongitudinalNoise",List.class,c.getClass());m.setAccessible(true);return (List<RenderedHeatmapSampler.CrossSectionProfile>)m.invoke(new RidgeTracker(),profiles,c);
    }
    static int support(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,int index,RenderedHeatmapSampler.CrossSectionPeak peak,double norm,double pitch,LegacyTrackerPolicy p)throws Exception{
        Object c=context(p,norm,pitch);var m=RidgeTracker.class.getDeclaredMethod("longitudinalSupport",List.class,int.class,RenderedHeatmapSampler.CrossSectionPeak.class,c.getClass());m.setAccessible(true);return (int)m.invoke(new RidgeTracker(),profiles,index,peak,c);
    }
}
