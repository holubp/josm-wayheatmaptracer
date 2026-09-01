package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage;

class CorridorCoverageCalculatorTest {
    @Test
    void rejectsAVisibleLocalIslandWithInformativeEvidenceBeyondIt() {
        List<CorridorProfile> profiles = profiles(10, 2.5);
        CorridorTrack island = track(profiles, 2, 5, false);

        CorridorCoverage coverage = new CorridorCoverageCalculator().calculate(
            island, profiles, new EndpointApproachModel(List.of()));

        assertFalse(coverage.complete());
        assertTrue(coverage.informativeEvidenceBeyondTrack());
        assertEquals("unsupported-leading-corridor", coverage.reason());
    }

    @Test
    void acceptsAContinuousLowIntensityStrand() {
        List<CorridorProfile> profiles = profiles(10, 2.5);
        CorridorTrack full = track(profiles, 0, 9, false);

        CorridorCoverage coverage = new CorridorCoverageCalculator().calculate(
            full, profiles, new EndpointApproachModel(List.of()));

        assertTrue(coverage.complete());
        assertEquals(1.0, coverage.informativeCoverageRatio(), 1e-9);
    }

    @Test
    void acceptsOnlyTrackerApprovedBoundedInternalBridge() {
        List<CorridorProfile> profiles = profiles(10, 2.5);
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int index : List.of(0, 1, 2, 6, 7, 8, 9)) {
            points.put(index, new CorridorTrackPoint(index, profiles.get(index).bands().get(0), index == 6));
        }
        CorridorTrack bridged = new CorridorTrack("track", points, 1.0, 0.7, false, List.of(), "");

        CorridorCoverage coverage = new CorridorCoverageCalculator().calculate(
            bridged, profiles, new EndpointApproachModel(List.of()));

        assertTrue(coverage.complete());
        assertEquals(1, coverage.approvedBridgeCount());
        assertEquals(3, coverage.maximumInternalUnsupportedProfiles());
        assertEquals(10.0, coverage.maximumInternalUnsupportedMeters(), 1e-9);
    }

    @Test
    void rejectsUnapprovedProfileLimitedAndDistanceLimitedBridges() {
        CorridorCoverage unapproved = coverageAcrossGap(profiles(8, 1.0), 0, 7, false);
        CorridorCoverage tooManyProfiles = coverageAcrossGap(profiles(20, 0.5), 0, 18, true);
        CorridorCoverage tooFar = coverageAcrossGap(profiles(6, 5.1), 0, 5, true);

        assertFalse(unapproved.complete());
        assertFalse(tooManyProfiles.complete());
        assertFalse(tooFar.complete());

        assertEquals("unapproved-internal-gap", unapproved.reason());
        assertEquals("unapproved-internal-gap", tooManyProfiles.reason());
        assertEquals("unapproved-internal-gap", tooFar.reason());
    }

    @Test
    void distinguishesBridgedAndUnresolvedSearchEdgeCensoring() {
        List<CorridorProfile> profiles = new java.util.ArrayList<>(profiles(8, 2.0));
        for (int index = 3; index <= 4; index++) {
            CorridorProfile original = profiles.get(index);
            CorridorBand band = original.bands().get(0);
            CorridorBand censored = new CorridorBand(band.id(), 2.0, 0.0, 4.0, 1.5, 4.0,
                List.of(2.0), 0.12, 0.05, 1.0, 0.0, 0.0, 0.0,
                0.20, 0.0, 4.0, false, List.of(),
                CorridorBand.BoundaryCompleteness.CORE_CENSORED, CorridorBand.BoundarySide.RIGHT);
            profiles.set(index, new CorridorProfile(index, original.source(), List.of(censored),
                0.12, 0.05, 0.07, true));
        }

        Map<Integer, CorridorTrackPoint> bridgedPoints = new LinkedHashMap<>();
        Map<Integer, CorridorTrackPoint> unresolvedPoints = new LinkedHashMap<>();
        for (int index : List.of(0, 1, 2, 5, 6, 7)) {
            CorridorBand band = profiles.get(index).bands().get(0);
            bridgedPoints.put(index, new CorridorTrackPoint(index, band, index == 5));
            unresolvedPoints.put(index, new CorridorTrackPoint(index, band, false));
        }
        CorridorCoverage bridged = new CorridorCoverageCalculator().calculate(
            new CorridorTrack("bridged", bridgedPoints, 1.0, 0.75, false, List.of(), ""), profiles,
            new EndpointApproachModel(List.of()));
        CorridorCoverage unresolved = new CorridorCoverageCalculator().calculate(
            new CorridorTrack("unresolved", unresolvedPoints, 1.0, 0.75, false, List.of(), ""), profiles,
            new EndpointApproachModel(List.of()));

        assertTrue(bridged.complete());
        assertEquals("complete-with-search-edge-bridge", bridged.reason());
        assertEquals(6, bridged.informativeProfiles(),
            "Unmeasured edge evidence must not enter the positional coverage denominator");
        assertEquals(1.0, bridged.informativeCoverageRatio(), 1e-9);
        assertFalse(unresolved.complete());
        assertEquals("unresolved-search-edge-censoring", unresolved.reason());
    }

    @Test
    void unrelatedClippedParallelBandDoesNotRelabelAnOrdinaryBridgeAsSearchEdge() {
        List<CorridorProfile> profiles = new java.util.ArrayList<>(profiles(8, 2.0));
        for (int index = 3; index <= 4; index++) {
            CorridorProfile original = profiles.get(index);
            CorridorBand censored = new CorridorBand("unrelated-edge", 15.0, 13.0, 17.0, 14.5, 17.0,
                List.of(15.0), 0.12, 0.05, 1.0, 0.0, 0.0, 0.0,
                0.20, 0.0, 4.0, false, List.of(),
                CorridorBand.BoundaryCompleteness.CORE_CENSORED, CorridorBand.BoundarySide.RIGHT);
            profiles.set(index, new CorridorProfile(index, original.source(), List.of(censored),
                0.12, 0.05, 0.07, true));
        }
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int index : List.of(0, 1, 2, 5, 6, 7)) {
            points.put(index, new CorridorTrackPoint(index, profiles.get(index).bands().get(0), index == 5));
        }

        CorridorCoverage coverage = new CorridorCoverageCalculator().calculate(
            new CorridorTrack("selected", points, 1.0, 0.75, false, List.of(), ""), profiles,
            new EndpointApproachModel(List.of()));

        assertTrue(coverage.complete());
        assertEquals("complete", coverage.reason());
    }

    @Test
    void countsSparseBundleUnionAsDirectAndBoundedPredictionsOnlyAsBridge() {
        List<CorridorProfile> profiles = profiles(10, 2.5);
        Map<Integer, CorridorTrackPoint> parentPoints = new LinkedHashMap<>();
        Map<Integer, SparseCorridorBundlePoint> bundlePoints = new LinkedHashMap<>();
        for (int index = 0; index < profiles.size(); index++) {
            boolean interpolated = index >= 2 && index <= 4;
            CorridorPointSupport support = interpolated
                ? CorridorPointSupport.BOUNDED_INTERPOLATION : CorridorPointSupport.DIRECT_UNION;
            parentPoints.put(index, new CorridorTrackPoint(index, profiles.get(index).bands().get(0),
                interpolated, support));
            bundlePoints.put(index, new SparseCorridorBundlePoint(index, support,
                interpolated ? List.of() : List.of("left"), interpolated ? List.of("left", "right") : List.of(),
                0.0, 1.0, -2.0, 2.0, -0.5, 0.5, 1.0, 0.8));
        }
        CorridorTrack parent = new CorridorTrack("bundle-1", parentPoints, 1.0, 0.7,
            true, List.of("left", "right"), "combined");
        SparseCorridorBundle bundle = new SparseCorridorBundle("bundle-1", List.of("left", "right"),
            "combined", bundlePoints, 0.7, 0.0, 0.0, 1.0, 1.0, 4.0,
            "complementary-child-union");

        CorridorCoverage coverage = new CorridorCoverageCalculator().calculate(
            parent, profiles, new EndpointApproachModel(List.of()), bundle);

        assertTrue(coverage.complete());
        assertEquals(7, coverage.observedProfiles(), "Interpolation must not inflate direct-union support");
        assertEquals(1, coverage.approvedBridgeCount());
        assertEquals(3, coverage.maximumInternalUnsupportedProfiles());
    }

    private CorridorCoverage coverageAcrossGap(
        List<CorridorProfile> profiles,
        int left,
        int right,
        boolean rightBoundaryApproved
    ) {
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        points.put(left, new CorridorTrackPoint(left, profiles.get(left).bands().get(0), false));
        points.put(right, new CorridorTrackPoint(
            right, profiles.get(right).bands().get(0), rightBoundaryApproved));
        return new CorridorCoverageCalculator().calculate(
            new CorridorTrack("gap", points, 1.0, 0.1, false, List.of(), ""),
            profiles,
            new EndpointApproachModel(List.of()));
    }

    private List<CorridorProfile> profiles(int count, double spacingMeters) {
        return java.util.stream.IntStream.range(0, count).mapToObj(index -> {
            CorridorBand band = new CorridorBand("band", 0.0, -2.0, 2.0, -0.5, 0.5,
                List.of(0.0), 0.12, 0.05, 1.0, 0.20, 0.35, 0.5, false, List.of());
            var source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * spacingMeters, 0.0), index * 40.0, 0.0,
                    index * spacingMeters),
                new Point2D.Double(0.0, 1.0), List.of(), true, List.of());
            return new CorridorProfile(index, source, List.of(band), 0.12, 0.05, 0.07, true);
        }).toList();
    }

    private CorridorTrack track(List<CorridorProfile> profiles, int first, int last, boolean bridged) {
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int index = first; index <= last; index++) {
            points.put(index, new CorridorTrackPoint(index, profiles.get(index).bands().get(0), bridged));
        }
        return new CorridorTrack("track", points, 1.0, points.size() / (double) profiles.size(),
            false, List.of(), "");
    }
}
