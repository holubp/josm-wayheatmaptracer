package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;

class CorridorRasterIntegrationTest {
    private static final int PROFILE_COUNT = 61;
    private static final int START_X = 20;
    private static final int STEP_X = 2;
    private static final int SOURCE_Y = 60;

    @Test
    void centersBroadSaturatedCorridorDespiteAlternatingBrightestPixels() {
        BufferedImage raster = raster((x) -> 0.0, 0.92, true, false);

        List<CenterlineCandidate> candidates = track(raster);

        assertFalse(candidates.isEmpty());
        double rms = Math.sqrt(candidates.get(0).offsetsPx().stream()
            .mapToDouble(offset -> offset * offset).average().orElseThrow());
        assertTrue(rms <= 0.5, "Broad-corridor RMS was " + rms);
        assertTrue(candidates.get(0).evidence().corridorQuality().highFrequencyRmsSourcePx() <= 0.15,
            "Broad-corridor high-frequency RMS was "
                + candidates.get(0).evidence().corridorQuality().highFrequencyRmsSourcePx());
        assertTrue(candidates.get(0).evidence().corridorQuality().highFrequencyP95SourcePx() <= 0.25,
            "Broad-corridor high-frequency p95 was "
                + candidates.get(0).evidence().corridorQuality().highFrequencyP95SourcePx());
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
    void preservesSustainedSineAmplitude() {
        IntToDoubleFunction center = profile -> 8.0 * Math.sin(profile * 2.0 * Math.PI / (PROFILE_COUNT - 1));
        BufferedImage raster = raster(center, 0.85, false, false);

        CenterlineCandidate candidate = track(raster).get(0);

        double amplitude = (candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).max().orElseThrow()
            - candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).min().orElseThrow()) / 2.0;
        assertTrue(amplitude >= 7.2, "Retained raster sine amplitude was " + amplitude);
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

    private List<CenterlineCandidate> track(BufferedImage raster) {
        return detailed(raster).candidates();
    }

    private CorridorAwareTracker.TrackingResult detailed(BufferedImage raster) {
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = new RenderedHeatmapSampler()
            .sampleProfilesOnScaledRaster(raster, sourcePolyline(), 18, 1, "hot", 1.0, 1.0,
                IntensitySamplingMode.DIRECT_VALUE);
        return new CorridorAwareTracker().trackDetailed(profiles, 1.0);
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
}
