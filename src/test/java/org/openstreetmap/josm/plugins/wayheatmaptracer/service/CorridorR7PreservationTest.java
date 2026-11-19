package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorCenterlineOptimizer.OriginalCostPolicy.DIRECT_MEASURED_CORE_V2;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorCenterlineOptimizer.OriginalCostPolicy.DIRECT_POSITION_V1;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorDirectPositionCostPolicyTest.number;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorDirectPositionCostPolicyTest.unary;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Full native V2 geometry controls; profile-only fixtures do not claim source certificates. */
class CorridorR7PreservationTest {
    private final CorridorRasterIntegrationTest rasters = new CorridorRasterIntegrationTest();

    @Test void strongAndFaintConcentratedArcsRetainNinetyPercentSagittaAndSourcePixelApex() {
        double radius = 4 * PhysicalGeometryFixture.NATIVE_PITCH;
        double chordX = 64 + radius * Math.cos(Math.toRadians(105));
        double sagitta = radius * (1 - Math.cos(Math.toRadians(75)));
        for (double strength : List.of(.95, .35)) {
            var profiles = PhysicalGeometryFixture.supportedOppositeArcProfiles(strength);
            var result = run(profiles, PhysicalGeometryFixture.NATIVE_PITCH, JunctionContext.empty(), 1.);
            var points = result.candidates().get(0).screenPoints();
            PhysicalPathContinuityTest.assertOppositeArc(points);
            double retained = chordX - points.stream().mapToDouble(p -> p.x).min().orElseThrow();
            double apexError = points.stream().mapToDouble(p -> p.distance(64-radius, 64)).min().orElseThrow();
            assertTrue(retained >= .9 * sagitta, "strength=" + strength + " retained=" + retained + " truth=" + sagitta);
            assertTrue(apexError <= PhysicalGeometryFixture.NATIVE_PITCH, "strength=" + strength + " apex error=" + apexError);
        }
    }

    @Test void strongAndFaintParallelStrandsRetainImageIdentityWithoutDarkConnector() {
        for (double strength : List.of(.95, .35)) {
            var frame = PhysicalGeometryFixture.parallelFrame(strength);
            var result = run(frame.profileSet(), frame.sourcePixelPitch(), JunctionContext.empty(), 1.);
            PhysicalPathContinuityTest.assertParallelStrand(result.candidates().get(0), frame, "R7 strength=" + strength);
            assertEquals(0xff000000, frame.raster().getRGB(108,80));
            assertTrue(result.tracks().stream().filter(t -> !t.parent()).count() >= 2, "Separated children remain available");
        }
    }

    @Test void collinearSourceInsertionPreservesIndependentCurvedImageAndApex() {
        var frame = PhysicalGeometryFixture.collinearInsertionFrame();
        PhysicalPathContinuityTest.assertEquivalentResampledSource(frame);
        var original = run(frame.originalProfiles(), frame.sourcePixelPitch(), JunctionContext.empty(), 1.);
        var inserted = run(frame.insertedProfiles(), frame.sourcePixelPitch(), JunctionContext.empty(), 1.);
        PhysicalPathContinuityTest.assertSameImageCurveAndApex(original.candidates().get(0), inserted.candidates().get(0), frame.sourcePixelPitch());
    }

    @Test void sustainedStrongAndFaintSinesRetainNinetyPercentAmplitude() {
        for (double strength : List.of(.85, .16)) {
            var raster = rasters.raster(row -> 8 * Math.sin(row * 2 * Math.PI / 60), strength, false, false);
            var candidate = raster(raster, rasters.sourcePolyline(), JunctionContext.empty()).candidates().get(0);
            assertTrue(amplitude(candidate.offsetsPx()) >= 7.2, "strength=" + strength + " amplitude=" + amplitude(candidate.offsetsPx()));
        }
    }

    @Test void sustainedFaintSwitchbackRetainsBothActualApices() {
        var image = rasters.raster(row -> {
            int phase = row % 20; double rising = -8 + 16. * phase / 20;
            return (row / 20) % 2 == 0 ? rising : -rising;
        }, .16, false, false);
        var candidate = raster(image, rasters.sourcePolyline(), JunctionContext.empty()).candidates().get(0);
        assertTrue(amplitude(candidate.offsetsPx()) >= 7.2);
        assertTrue(candidate.offsetsPx().get(20) >= 6.5 && candidate.offsetsPx().get(40) <= -6.5, candidate.offsetsPx().toString());
    }

    @Test void sparseStrandRejectsIsolatedBrightOutlier() {
        var image = rasters.raster(row -> -3, .13, false, true);
        rasters.setIntensity(image, 20 + 30 * 2, 60 + 14, .95);
        var candidate = raster(image, rasters.sourcePolyline(), JunctionContext.empty()).candidates().get(0);
        assertEquals(-3., candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).average().orElseThrow(), 1.);
        assertTrue(candidate.offsetsPx().stream().noneMatch(value -> value > 8));
    }

    @Test void complementarySparseParentKeepsChildrenAndDirectVersusInterpolatedOwnership() {
        var result = raster(rasters.complementarySparseRaster(), rasters.sourcePolyline(), fixed(false,0,60));
        var bundle = result.sparseBundles().stream().max(java.util.Comparator.comparingDouble(SparseCorridorBundle::unionSupportRatio)).orElseThrow();
        var candidate = result.candidates().stream().filter(c -> c.id().equals(bundle.id())).findFirst().orElseThrow();
        var parent = result.tracks().stream().filter(t -> t.id().equals(bundle.id())).findFirst().orElseThrow();
        assertEquals(58, bundle.directUnionProfileCount()); assertEquals(3, bundle.interpolatedProfileCount());
        assertTrue(candidate.evidence().corridorCoverage().complete());
        assertEquals(0., candidate.offsetsPx().subList(2,59).stream().mapToDouble(Double::doubleValue).average().orElseThrow(), .75);
        assertTrue(candidate.evidence().corridorQuality().nonSustainedHighFrequencyP95SourcePx() <= .40);
        assertFalse(parent.childTrackIds().isEmpty());
        for (String child : parent.childTrackIds()) assertTrue(result.tracks().stream().anyMatch(t -> !t.parent() && t.id().equals(child)));
        for (var point : bundle.points().values()) {
            if (point.directContributorTrackIds().isEmpty()) {
                assertEquals(CorridorPointSupport.BOUNDED_INTERPOLATION, parent.points().get(point.profileIndex()).support());
                assertEquals(0., parent.points().get(point.profileIndex()).band().peakIntensity());
                assertEquals(0., parent.points().get(point.profileIndex()).band().signalExistenceConfidence());
            }
        }
    }

    @Test void repeatedCurvedPassKeepsProtectedAnchorsAndOneSourcePixelDrift() {
        var image = rasters.raster(row -> 6 * Math.sin(row * 2 * Math.PI / 60), .82, false, true);
        var anchors = fixed(0,30,60);
        var first = raster(image, rasters.sourcePolyline(), anchors).candidates().get(0);
        var repeated = raster(image, first.screenPoints(), anchors).candidates().stream()
            .filter(c -> c.evidence().corridorCoverage().complete())
            .min(java.util.Comparator.comparingDouble(c -> rasters.bidirectionalMaximumDrift(first.screenPoints(), c.screenPoints()))).orElseThrow();
        assertTrue(first.evidence().corridorCoverage().complete());
        assertTrue(repeated.evidence().corridorQuality().unsupportedExcursions() <= first.evidence().corridorQuality().unsupportedExcursions());
        assertEquals(first.screenPoints().size(), repeated.screenPoints().size());
        assertTrue(rasters.bidirectionalMaximumDrift(first.screenPoints(), repeated.screenPoints()) <= 1.);
        for (int row : List.of(0,30,60)) assertEquals(0., repeated.offsetsPx().get(row), 1e-9);
    }

    @Test void ordinaryPhysicalSearchWidthsRetainTurnWithoutNearbyLimbSwitch() {
        double metersPerPixel = .389, sourcePixelPitch = 3.5 / metersPerPixel;
        var image = rasters.switchbackWithNearbyLimbRaster(metersPerPixel);
        var expected = rasters.expectedSwitchbackOffsets(metersPerPixel);
        var source = rasters.sourcePolyline(); var anchors = new ArrayList<ProfileSamplingAnchor>();
        double distance = 0;
        for (int row = 0; row < source.size(); row++) {
            var point = source.get(row);
            if (row > 0) distance += source.get(row-1).distance(point) * metersPerPixel;
            anchors.add(new ProfileSamplingAnchor(new EastNorth(point.x * metersPerPixel, point.y * metersPerPixel), point.x, point.y, distance));
        }
        for (double halfWidthMeters : List.of(7.01,10.,14.)) {
            var profiles = new RenderedHeatmapSampler().sampleMultiScaleProfilesOnAnchors(image, anchors,
                (int)Math.round(halfWidthMeters / metersPerPixel), (int)Math.round(1.56 / metersPerPixel), "hot", 1,1,
                IntensitySamplingMode.DIRECT_VALUE, sourcePixelPitch);
            assertEquals(2 * metersPerPixel, profiles.levelZeroProfiles().get(1).cumulativeGroundDistanceMeters(), 1e-12);
            var result = run(profiles, sourcePixelPitch, JunctionContext.empty(), metersPerPixel);
            var candidate = result.candidates().get(0);
            assertTrue(candidate.evidence().corridorCoverage().complete(), "half width=" + halfWidthMeters);
            double error = 0;
            for (int row=0; row<expected.size(); row++) error += Math.abs(candidate.offsetsPx().get(row)-expected.get(row));
            assertTrue(error / expected.size() <= sourcePixelPitch * .35, "half width=" + halfWidthMeters + " error=" + error / expected.size());
            assertTrue(2 * amplitude(candidate.offsetsPx()) >= 4 / metersPerPixel, "half width=" + halfWidthMeters);
        }
    }

    @Test void mixedAndPredictedParentRowsCompleteNativeDpWithoutInventingObservedSignal() throws Exception {
        var scenario = new CorridorGroupingTest().predictionSignalScenario();
        var originalChildren = scenario.tracks().stream().map(t -> Map.copyOf(t.points())).toList();
        var grouped = new CorridorGrouping().group(scenario.tracks(), scenario.profiles());
        assertEquals(3, grouped.tracks().size(), "Exactly two elementary children and one parent before any solve");
        var parent = grouped.tracks().stream().filter(CorridorTrack::parent).findFirst().orElseThrow();
        var bundle = grouped.bundles().stream().filter(b -> b.id().equals(parent.id())).findFirst().orElseThrow();
        assertEquals(1, bundle.points().get(1).directContributorTrackIds().size());
        assertEquals(1, bundle.points().get(1).predictedContributorTrackIds().size());
        assertTrue(bundle.points().get(2).directContributorTrackIds().isEmpty());
        assertEquals(2, bundle.points().get(2).predictedContributorTrackIds().size());
        assertEquals(.20, parent.points().get(1).band().peakIntensity(), 1e-12);
        assertEquals(CorridorPointSupport.BOUNDED_INTERPOLATION, parent.points().get(2).support());
        assertEquals(0., parent.points().get(2).band().peakIntensity());
        assertEquals(0., parent.points().get(2).band().signalExistenceConfidence());
        for (var track : grouped.tracks()) {
            var optimizer = new CorridorCenterlineOptimizer();
            var tube = new CorridorTubeBuilder(CorridorTubeBuilder.MotionPolicy.TARGET_OWNED_APEX).build(track, scenario.profiles(), 1., Map.of());
            var domain = optimizer.prepareOriginalDomain(track, scenario.profiles(), 1., JunctionContext.empty(), Map.of(), tube);
            var direct = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(domain, CorridorWorkingSchedule.refusal("REFUSED_PORTABLE_PROFILE_ONLY"), DIRECT_MEASURED_CORE_V2);
            assertEquals(DIRECT_MEASURED_CORE_V2, direct.costPolicy());
            var result = optimizer.optimizeOriginalDomain(direct); assertEquals(5, result.offsetsPx().size());
            var fixture = new CorridorDirectPositionCostPolicyTest.Fixture(track, scenario.profiles(), tube);
            for (var row : result.costs()) {
                var nativeCost = unary(optimizer, fixture, row.profileIndex(), row.chosenOffsetPx(), Map.of(), DIRECT_MEASURED_CORE_V2);
                assertEquals(number(nativeCost, "total"), row.dataCost(), 1e-12);
                if (track.parent() && row.profileIndex() == 2) assertEquals(
                    unary(optimizer, fixture, 2, row.chosenOffsetPx(), Map.of(), DIRECT_POSITION_V1), nativeCost);
            }
        }
        assertEquals(originalChildren, scenario.tracks().stream().map(t -> Map.copyOf(t.points())).toList());
    }

    @Test void fixedEndpointApproachRemainsMonotonicWithoutTerminalReversal() {
        var scenario = new CorridorEndpointApproachTest().scenario(6.,16,true);
        var junctions = fixed(0); var optimizer = new CorridorCenterlineOptimizer();
        var tube = new CorridorTubeBuilder(CorridorTubeBuilder.MotionPolicy.TARGET_OWNED_APEX).build(scenario.track(), scenario.profiles(),1.,Map.of());
        var domain = optimizer.prepareOriginalDomain(scenario.track(),scenario.profiles(),1.,junctions,Map.of(),tube);
        var direct = CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(domain,CorridorWorkingSchedule.refusal("REFUSED_PORTABLE_PROFILE_ONLY"),DIRECT_MEASURED_CORE_V2);
        var result = optimizer.optimizeOriginalDomain(direct);
        assertEquals(0.,result.offsetsPx().get(0),1e-9);
        for (int row=1; row<=5; row++) assertTrue(result.offsetsPx().get(row)>=result.offsetsPx().get(row-1)-1e-9);
        double maximumTurn=0;
        for (int row=1; row<6; row++) {
            var a=result.screenPoints().get(row-1); var b=result.screenPoints().get(row); var c=result.screenPoints().get(row+1);
            double turn=Math.atan2(c.y-b.y,c.x-b.x)-Math.atan2(b.y-a.y,b.x-a.x);
            maximumTurn=Math.max(maximumTurn,Math.abs(Math.toDegrees(Math.atan2(Math.sin(turn),Math.cos(turn)))));
        }
        assertTrue(maximumTurn<=15.,"terminal turn="+maximumTurn);
    }

    @Test void sameValidZeroRasterReturnsTruthfulNoSignalWithoutPolicyMetadata() {
        var image = new BufferedImage(160,120,BufferedImage.TYPE_INT_RGB);
        var profiles = new RenderedHeatmapSampler().sampleProfilesOnScaledRaster(image,rasters.sourcePolyline(),18,1,"hot",1,1,IntensitySamplingMode.DIRECT_VALUE);
        var tracker = tracker(); var result = tracker.trackDetailed(profiles,1.,JunctionContext.empty(),"hot",GeometryCleanupConfig.disabled(),1.);
        assertTrue(result.candidates().isEmpty()); assertTrue(result.tracks().isEmpty());
        assertTrue(tracker.executedCostPolicies().isEmpty()); assertEquals(0,tracker.executedCostPolicyMetadataBytes());
    }

    private CorridorAwareTracker.TrackingResult raster(BufferedImage image,List<Point2D.Double> source,JunctionContext junctions) {
        var profiles = new RenderedHeatmapSampler().sampleProfilesOnScaledRaster(image,source,18,1,"hot",1,1,IntensitySamplingMode.DIRECT_VALUE);
        return run(profiles,1.,junctions,1.);
    }
    private static CorridorAwareTracker tracker() {
        return CorridorAwareTracker.withTargetOwnedMeasuredCoreCostDomainWithoutAuthority(new CorridorCenterlineOptimizer(),
            ignored -> CorridorWorkingSchedule.refusal("REFUSED_PORTABLE_PROFILE_ONLY"));
    }
    private static CorridorAwareTracker.TrackingResult run(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,double sourcePitch,JunctionContext junctions,double metersPerPixel) {
        var tracker=tracker(); var result=tracker.trackDetailed(profiles,sourcePitch,junctions,"hot",GeometryCleanupConfig.disabled(),metersPerPixel);
        assertNativeV2(tracker,result); return result;
    }
    private static CorridorAwareTracker.TrackingResult run(MultiScaleProfileSet profiles,double sourcePitch,JunctionContext junctions,double metersPerPixel) {
        var tracker=tracker(); var result=tracker.trackDetailed(profiles,sourcePitch,junctions,"hot",GeometryCleanupConfig.disabled(),metersPerPixel);
        assertNativeV2(tracker,result); return result;
    }
    private static void assertNativeV2(CorridorAwareTracker tracker,CorridorAwareTracker.TrackingResult result) {
        assertFalse(result.candidates().isEmpty()); assertFalse(tracker.executedCostPolicies().isEmpty());
        assertTrue(tracker.executedCostPolicies().values().stream().allMatch(policy -> policy==DIRECT_MEASURED_CORE_V2));
        assertEquals(128L*tracker.executedCostPolicies().size(),tracker.executedCostPolicyMetadataBytes());
        for(var entry:result.optimizations().entrySet()) {
            var optimized=entry.getValue(); assertFalse(optimized.offsetsPx().isEmpty(),entry.getKey());
            assertEquals(optimized.offsetsPx().size(),optimized.costs().size());
            assertEquals(optimized.totalCost(),optimized.costs().stream().mapToDouble(CorridorCenterlineOptimizer.CostRow::weightedTotal).sum(),1e-9);
            assertTrue(optimized.profileCostEvaluations()>0 && optimized.transitionEvaluations()>0);
            assertTrue(optimized.retainedPairStateAllocations()>=0 && optimized.retainedPairStateAllocations()<=optimized.transitionEvaluations());
        }
    }
    private static JunctionContext fixed(int... rows) {
        return fixed(true, rows);
    }
    private static JunctionContext fixed(boolean shared, int... rows) {
        var constraints=new ArrayList<EndpointConstraint>();
        for(int row:rows) constraints.add(new EndpointConstraint(row,row+1L,true,shared,0.,0.,6));
        return new JunctionContext(constraints);
    }
    private static double amplitude(List<Double> offsets) {
        return (offsets.stream().mapToDouble(Double::doubleValue).max().orElseThrow()-offsets.stream().mapToDouble(Double::doubleValue).min().orElseThrow())/2;
    }
}
