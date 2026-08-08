package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;
import org.openstreetmap.josm.data.coor.EastNorth;

class CorridorRasterIntegrationTest {
    private static final int PROFILE_COUNT = 61;
    private static final int START_X = 20;
    private static final int STEP_X = 2;
    private static final int SOURCE_Y = 60;

    @Test
    void centersBroadSaturatedCorridorDespiteAlternatingBrightestPixels() {
        BufferedImage raster = raster((x) -> 0.0, 0.92, true, false);

        CorridorAwareTracker.TrackingResult result = detailed(raster);
        List<CenterlineCandidate> candidates = result.candidates();

        assertFalse(candidates.isEmpty());
        CorridorTrack track = result.tracks().stream()
            .filter(value -> value.id().equals(candidates.get(0).id())).findFirst().orElseThrow();
        CorridorBand middleBand = track.points().get(PROFILE_COUNT / 2).band();
        CorridorTubeSlice middleTube = result.tubes().get(candidates.get(0).id()).at(PROFILE_COUNT / 2);
        String centerEvidence = "band=" + middleBand.centerOffsetPx()
            + ", localization=" + middleBand.localizationConfidence()
            + ", raw=" + middleTube.rawCenterPx()
            + ", b3=" + middleTube.lightCenterPx()
            + ", b5=" + middleTube.standardCenterPx();
        double rms = Math.sqrt(candidates.get(0).offsetsPx().stream()
            .mapToDouble(offset -> offset * offset).average().orElseThrow());
        assertTrue(rms <= 0.5, "Broad-corridor RMS was " + rms);
        assertTrue(candidates.get(0).evidence().corridorQuality().highFrequencyRmsSourcePx() <= 0.15,
            "Broad-corridor high-frequency RMS was "
                + candidates.get(0).evidence().corridorQuality().highFrequencyRmsSourcePx()
                + "; " + centerEvidence);
        assertTrue(candidates.get(0).evidence().corridorQuality().highFrequencyP95SourcePx() <= 0.25,
            "Broad-corridor high-frequency p95 was "
                + candidates.get(0).evidence().corridorQuality().highFrequencyP95SourcePx());
    }

    @Test
    void retainsOneSharedCleanupFrameAndFailsClosedWithoutProjectedTransforms() {
        BufferedImage raster = raster((x) -> 0.0, 0.92, true, false);
        List<RenderedHeatmapSampler.CrossSectionProfile> compatibilityProfiles = new RenderedHeatmapSampler()
            .sampleProfilesOnScaledRaster(raster, sourcePolyline(), 18, 1, "hot", 1.0, 1.0,
                IntensitySamplingMode.DIRECT_VALUE);
        CorridorAwareTracker.TrackingResult unavailable = new CorridorAwareTracker()
            .trackDetailed(compatibilityProfiles, 1.0, JunctionContext.empty(), "hot");
        assertFalse(unavailable.candidates().isEmpty());
        assertTrue(unavailable.candidates().stream().allMatch(candidate ->
            candidate.cleanupEvidence().status() == CleanupEvidenceStatus.MISSING_PROJECTED_TRANSFORM));

        List<RenderedHeatmapSampler.CrossSectionProfile> projectedProfiles = compatibilityProfiles.stream()
            .map(profile -> new RenderedHeatmapSampler.CrossSectionProfile(
                profile.samplingAnchor(), profile.normalScreen(), profile.peaks(), profile.anchorWithinRaster(),
                profile.intensitySamples(), java.util.Optional.of(new ProjectedLateralTransform(
                    new EastNorth(profile.anchorScreen().x, profile.anchorScreen().y),
                    profile.normalScreen().x, profile.normalScreen().y))))
            .toList();
        CorridorAwareTracker.TrackingResult available = new CorridorAwareTracker()
            .trackDetailed(projectedProfiles, 1.0, JunctionContext.empty(), "hot",
                GeometryCleanupConfig.disabled(), 0.42);
        List<CenterlineCandidate> complete = available.candidates().stream()
            .filter(candidate -> candidate.evidence().corridorCoverage().complete()).toList();

        assertFalse(complete.isEmpty());
        assertTrue(complete.stream().allMatch(candidate -> candidate.cleanupEvidence().eligible()));
        Object sharedFrame = complete.get(0).cleanupEvidence().samplingFrame();
        complete.forEach(candidate -> assertSame(sharedFrame, candidate.cleanupEvidence().samplingFrame()));
        assertEquals("hot", complete.get(0).cleanupEvidence().samplingFrame().detectorMode());
        assertEquals(0.42,
            complete.get(0).cleanupEvidence().samplingFrame().groundMetersPerRasterPixel(), 0.0);
        assertTrue(complete.get(0).cleanupEvidence().samplingFrame().hasGroundScale());
    }

    @Test
    void usesSamePipelineForMediumIntensityCorridor() {
        BufferedImage raster = raster((x) -> 2.0, 0.24, false, false);

        List<CenterlineCandidate> candidates = track(raster);

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.get(0).evidence().signalExistenceConfidence() > 0.0);
        assertEquals(2.0, mean(candidates.get(0).offsetsPx()), 0.75);
        assertTrue(candidates.get(0).evidence().corridorQuality().longitudinalPersistence() > 0.5);
    }

    @Test
    void keepsCenterContinuousAcrossSparseToDenseIntensity() {
        List<Double> means = new ArrayList<>();
        for (double peak : List.of(0.10, 0.16, 0.24, 0.45, 0.72, 0.92)) {
            CenterlineCandidate candidate = track(raster((x) -> 2.0, peak, false, false)).get(0);
            means.add(mean(candidate.offsetsPx()));
            assertEquals(2.0, means.get(means.size() - 1), 0.75,
                "Center must not jump at an intensity threshold; peak=" + peak);
        }
        double range = means.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
            - means.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        assertTrue(range <= 0.50, "Sparse-to-dense center discontinuity=" + range + ", means=" + means);
    }

    @Test
    void centersWeakOffGridCorridorWithoutLongitudinalRipple() {
        double sourcePixelPitch = 4.0;
        List<Point2D.Double> scaledSource = sourcePolyline().stream()
            .map(point -> new Point2D.Double(point.x * sourcePixelPitch, point.y * sourcePixelPitch))
            .toList();
        List<String> diagnostics = new ArrayList<>();
        double maximumCenterError = 0.0;
        double maximumRipple = 0.0;
        for (double expectedSourceCenter : List.of(-1.75, -1.25, -0.75, -0.25, 0.25, 0.75, 1.25, 1.75)) {
            double expectedCenter = expectedSourceCenter * sourcePixelPitch;
            BufferedImage raster = weakOffGridRaster(expectedSourceCenter);
            MultiScaleProfileSet profiles = new RenderedHeatmapSampler().sampleMultiScaleProfilesOnScaledRaster(
                raster, scaledSource, 18, 1, "hot", sourcePixelPitch, sourcePixelPitch,
                IntensitySamplingMode.DIRECT_VALUE, 1.0);

            CorridorAwareTracker.TrackingResult result = new CorridorAwareTracker()
                .trackDetailed(profiles, sourcePixelPitch, JunctionContext.empty());

            assertFalse(result.candidates().isEmpty());
            CenterlineCandidate candidate = result.candidates().get(0);
            double centerError = Math.abs(mean(candidate.offsetsPx()) - expectedCenter);
            double ripple = highFrequencyRms(candidate.offsetsPx());
            maximumCenterError = Math.max(maximumCenterError, centerError);
            maximumRipple = Math.max(maximumRipple, ripple);
            CorridorTrack selectedTrack = result.tracks().stream()
                .filter(track -> track.id().equals(candidate.id())).findFirst().orElseThrow();
            CorridorBand middleBand = selectedTrack.points().get(PROFILE_COUNT / 2).band();
            CorridorTubeSlice middleTube = result.tubes().get(candidate.id()).at(PROFILE_COUNT / 2);
            BandScaleEvidence middleScale = result.scaleEvidence().get(
                CorridorCenterlineOptimizer.scaleEvidenceKey(PROFILE_COUNT / 2, middleBand.id()));
            diagnostics.add("sourceCenter=" + expectedSourceCenter + ", mean=" + mean(candidate.offsetsPx())
                + ", ripple=" + ripple + ", band=" + middleBand.centerOffsetPx()
                + ", core=" + (middleBand.coreMinPx() + middleBand.coreMaxPx()) / 2.0
                + ", localization=" + middleBand.localizationConfidence()
                + ", tube=" + middleTube.centerOffsetPx()
                + ", coarse=" + (middleScale == null ? Double.NaN : middleScale.coarseCenterPx()));
        }
        assertTrue(maximumCenterError <= sourcePixelPitch * 0.05,
            "Weak corridor should be centered independently of source-pixel phase: " + diagnostics);
        assertTrue(maximumRipple <= sourcePixelPitch * 0.07,
            "Weak corridor should not ripple longitudinally: " + diagnostics);
    }

    @Test
    void preservesSustainedSineAmplitude() {
        IntToDoubleFunction center = profile -> 8.0 * Math.sin(profile * 2.0 * Math.PI / (PROFILE_COUNT - 1));
        BufferedImage raster = raster(center, 0.85, false, false);

        CenterlineCandidate candidate = track(raster).get(0);

        double amplitude = (candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).max().orElseThrow()
            - candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).min().orElseThrow()) / 2.0;
        assertTrue(amplitude >= 7.2, "Retained raster sine amplitude was " + amplitude);
    }

    @Test
    void preservesLowIntensitySustainedSineAmplitude() {
        IntToDoubleFunction center = profile -> 8.0 * Math.sin(profile * 2.0 * Math.PI / (PROFILE_COUNT - 1));
        BufferedImage raster = raster(center, 0.16, false, false);

        CenterlineCandidate candidate = track(raster).get(0);

        double amplitude = (candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).max().orElseThrow()
            - candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).min().orElseThrow()) / 2.0;
        assertTrue(amplitude >= 7.2, "Retained weak raster sine amplitude was " + amplitude);
    }

    @Test
    void preservesLowIntensitySustainedSwitchbacks() {
        IntToDoubleFunction center = profile -> {
            int phase = profile % 20;
            double rising = -8.0 + 16.0 * phase / 20.0;
            return (profile / 20) % 2 == 0 ? rising : -rising;
        };
        CenterlineCandidate candidate = track(raster(center, 0.16, false, false)).get(0);

        double amplitude = (candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).max().orElseThrow()
            - candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).min().orElseThrow()) / 2.0;
        assertTrue(amplitude >= 7.2, "Retained weak switchback amplitude was " + amplitude);
        assertTrue(candidate.offsetsPx().get(20) >= 6.5 && candidate.offsetsPx().get(40) <= -6.5,
            "Weak switchback apices must not be flattened: "
                + List.of(candidate.offsetsPx().get(20), candidate.offsetsPx().get(40)));
    }

    @Test
    void tracesPersistentSparseStrandAcrossShortGapsButIgnoresIsolatedOutlier() {
        BufferedImage raster = raster((x) -> -3.0, 0.13, false, true);
        setIntensity(raster, START_X + 30 * STEP_X, SOURCE_Y + 14, 0.95);

        CenterlineCandidate candidate = track(raster).get(0);

        assertEquals(-3.0, mean(candidate.offsetsPx()), 1.0);
        assertTrue(candidate.offsetsPx().stream().noneMatch(offset -> offset > 8.0));
    }

    @Test
    void exposesParentForLaneLikeStrandsButKeepsDeepValleyRoadsSeparate() {
        BufferedImage laneRaster = parallelRaster(0.70);
        CorridorAwareTracker.TrackingResult lanes = detailed(laneRaster);
        BufferedImage carriagewayRaster = parallelRaster(0.15);
        CorridorAwareTracker.TrackingResult carriageways = detailed(carriagewayRaster);

        assertTrue(lanes.tracks().stream().anyMatch(track -> track.parent()
            && "combined".equals(track.groupingDecision())));
        assertTrue(lanes.tracks().stream().filter(track -> !track.parent()).count() >= 2);
        assertTrue(carriageways.tracks().stream().noneMatch(CorridorTrack::parent));
        assertTrue(carriageways.tracks().stream().filter(track -> !track.parent()).count() >= 2);
    }

    @Test
    void centersComplementarySparseTracesAcrossBoundedZeroHoles() {
        JunctionContext fixedEndpoints = new JunctionContext(List.of(
            new EndpointConstraint(0, 1L, true, false, 0.0, 0.0, 6),
            new EndpointConstraint(PROFILE_COUNT - 1, 2L, true, false, 0.0, 0.0, 6)
        ));
        CorridorAwareTracker.TrackingResult result = detailed(
            complementarySparseRaster(), sourcePolyline(), fixedEndpoints);

        assertFalse(result.sparseBundles().isEmpty(), "Complementary intermittent tracks need a bundle hypothesis");
        SparseCorridorBundle bundle = result.sparseBundles().stream()
            .max(java.util.Comparator.comparingDouble(SparseCorridorBundle::unionSupportRatio)).orElseThrow();
        CenterlineCandidate candidate = result.candidates().stream()
            .filter(value -> value.id().equals(bundle.id())).findFirst().orElseThrow();
        assertEquals(PROFILE_COUNT - 3, bundle.directUnionProfileCount(),
            "The two empty profiles and isolated bright outlier should not count as direct corridor support");
        assertEquals(3, bundle.interpolatedProfileCount());
        assertTrue(candidate.evidence().corridorCoverage().complete(),
            "A bounded 5m hole with bracketing compatible traces should remain complete");
        double interiorMean = candidate.offsetsPx().subList(2, PROFILE_COUNT - 2).stream()
            .mapToDouble(Double::doubleValue).average().orElseThrow();
        assertEquals(0.0, interiorMean, 0.75,
            "Sparse bundle should center the whole recording envelope instead of following one child");
        assertTrue(candidate.evidence().corridorQuality().nonSustainedHighFrequencyP95SourcePx() <= 0.40,
            "Sparse bundle ripple p95="
                + candidate.evidence().corridorQuality().nonSustainedHighFrequencyP95SourcePx());
    }

    @Test
    void repeatedCurvedCorridorPassStaysWithinOneSourcePixelAndKeepsFixedAnchors() {
        IntToDoubleFunction center = profile -> 6.0 * Math.sin(profile * 2.0 * Math.PI / (PROFILE_COUNT - 1));
        BufferedImage raster = raster(center, 0.82, false, true);
        JunctionContext fixedAnchors = new JunctionContext(List.of(
            new EndpointConstraint(0, 1L, true, true, 0.0, 0.0, 6),
            new EndpointConstraint(PROFILE_COUNT / 2, 2L, true, true, 0.0, 0.0, 6),
            new EndpointConstraint(PROFILE_COUNT - 1, 3L, true, true, 0.0, 0.0, 6)
        ));

        CenterlineCandidate first = detailed(raster, sourcePolyline(), fixedAnchors).candidates().get(0);
        CenterlineCandidate repeated = detailed(raster, first.screenPoints(), fixedAnchors).candidates().stream()
            .filter(candidate -> candidate.evidence().corridorCoverage().complete())
            .min(java.util.Comparator.comparingDouble(candidate -> bidirectionalMaximumDrift(
                first.screenPoints(), candidate.screenPoints())))
            .orElseThrow();

        assertTrue(first.evidence().corridorCoverage().complete());
        assertTrue(repeated.evidence().corridorCoverage().complete());
        assertTrue(repeated.evidence().corridorQuality().unsupportedExcursions()
            <= first.evidence().corridorQuality().unsupportedExcursions());
        assertEquals(first.screenPoints().size(), repeated.screenPoints().size());
        double maximumDrift = bidirectionalMaximumDrift(first.screenPoints(), repeated.screenPoints());
        assertTrue(maximumDrift <= 1.0, "repeat maximum drift=" + maximumDrift);
        for (int index : List.of(0, PROFILE_COUNT / 2, PROFILE_COUNT - 1)) {
            assertEquals(0.0, repeated.offsetsPx().get(index), 1e-9);
        }
    }

    private List<CenterlineCandidate> track(BufferedImage raster) {
        return detailed(raster).candidates();
    }

    private CorridorAwareTracker.TrackingResult detailed(BufferedImage raster) {
        return detailed(raster, sourcePolyline(), JunctionContext.empty());
    }

    private CorridorAwareTracker.TrackingResult detailed(
        BufferedImage raster,
        List<Point2D.Double> source,
        JunctionContext junctionContext
    ) {
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = new RenderedHeatmapSampler()
            .sampleProfilesOnScaledRaster(raster, source, 18, 1, "hot", 1.0, 1.0,
                IntensitySamplingMode.DIRECT_VALUE);
        return new CorridorAwareTracker().trackDetailed(profiles, 1.0, junctionContext);
    }

    private BufferedImage raster(
        IntToDoubleFunction centerFunction,
        double peak,
        boolean alternatingBrightest,
        boolean gaps
    ) {
        BufferedImage raster = background();
        for (int profile = 0; profile < PROFILE_COUNT; profile++) {
            int center = (int) Math.round(SOURCE_Y + centerFunction.applyAsDouble(profile));
            int x = START_X + profile * STEP_X;
            if (gaps && (profile == 24 || profile == 25)) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -5; dy <= 5; dy++) {
                    double value = Math.abs(dy) <= 3 ? peak : peak * 0.45;
                    setIntensity(raster, x + dx, center + dy, value);
                }
                if (alternatingBrightest) {
                    setIntensity(raster, x + dx, center + (profile % 2 == 0 ? -2 : 2), 1.0);
                }
            }
        }
        return raster;
    }

    private BufferedImage parallelRaster(double valley) {
        BufferedImage raster = background();
        for (int profile = 0; profile < PROFILE_COUNT; profile++) {
            int x = START_X + profile * STEP_X;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -8; dy <= 8; dy++) {
                    double value = valley;
                    if (Math.abs(dy + 4) <= 1 || Math.abs(dy - 4) <= 1) {
                        value = 0.95;
                    }
                    setIntensity(raster, x + dx, SOURCE_Y + dy, value);
                }
            }
        }
        return raster;
    }

    private BufferedImage complementarySparseRaster() {
        BufferedImage raster = background();
        for (int profile = 0; profile < PROFILE_COUNT; profile++) {
            if (profile == 30 || profile == 31) {
                continue;
            }
            int x = START_X + profile * STEP_X;
            int center = SOURCE_Y + (profile % 2 == 0 ? -4 : 4);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    setIntensity(raster, x + dx, center + dy, 0.18 - 0.04 * Math.abs(dy));
                }
            }
        }
        setIntensity(raster, START_X + 18 * STEP_X, SOURCE_Y + 14, 0.80);
        return raster;
    }

    private BufferedImage weakOffGridRaster(double centerOffset) {
        BufferedImage raster = background();
        for (int profile = 0; profile < PROFILE_COUNT; profile++) {
            int x = START_X + profile * STEP_X;
            double localCenter = SOURCE_Y + centerOffset + (profile % 2 == 0 ? -0.08 : 0.08);
            for (int dx = -1; dx <= 1; dx++) {
                for (int y = SOURCE_Y - 14; y <= SOURCE_Y + 14; y++) {
                    double distance = y - localCenter;
                    double intensity = 0.02 + 0.20 * Math.exp(-0.5 * distance * distance / 4.0);
                    setIntensity(raster, x + dx, y, intensity);
                }
            }
        }
        return raster;
    }

    private BufferedImage background() {
        BufferedImage raster = new BufferedImage(160, 120, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                setIntensity(raster, x, y, 0.01);
            }
        }
        return raster;
    }

    private List<Point2D.Double> sourcePolyline() {
        List<Point2D.Double> points = new ArrayList<>();
        for (int profile = 0; profile < PROFILE_COUNT; profile++) {
            points.add(new Point2D.Double(START_X + profile * STEP_X, SOURCE_Y));
        }
        return points;
    }

    private void setIntensity(BufferedImage image, int x, int y, double intensity) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return;
        }
        int value = Math.max(0, Math.min(255, (int) Math.round(intensity * 255.0)));
        image.setRGB(x, y, 0xFF000000 | value << 16 | value << 8 | value);
    }

    private double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private double highFrequencyRms(List<Double> values) {
        double squaredResiduals = 0.0;
        for (int index = 1; index < values.size() - 1; index++) {
            double residual = values.get(index) - (values.get(index - 1) + values.get(index + 1)) / 2.0;
            squaredResiduals += residual * residual;
        }
        return values.size() < 3 ? 0.0 : Math.sqrt(squaredResiduals / (values.size() - 2));
    }

    private double maximumPointToPolylineDistance(
        List<Point2D.Double> points,
        List<Point2D.Double> polyline
    ) {
        return points.stream().mapToDouble(point -> {
            double nearest = Double.POSITIVE_INFINITY;
            for (int index = 1; index < polyline.size(); index++) {
                nearest = Math.min(nearest, Line2D.ptSegDist(
                    polyline.get(index - 1).x, polyline.get(index - 1).y,
                    polyline.get(index).x, polyline.get(index).y, point.x, point.y));
            }
            return nearest;
        }).max().orElse(0.0);
    }

    private double bidirectionalMaximumDrift(
        List<Point2D.Double> left,
        List<Point2D.Double> right
    ) {
        return Math.max(maximumPointToPolylineDistance(left, right),
            maximumPointToPolylineDistance(right, left));
    }
}
