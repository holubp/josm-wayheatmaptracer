package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;

class V022InferenceProfileOrientationReuseTest {
    @Test
    void presentBranchReusesItsSupportAndUnavailableBranchReusesTheImmutableFallback() {
        ImageOrientationSupport present = ImageOrientationSupport.unknown(
                ImageOrientationSupport.Status.UNKNOWN_FLAT);
        var profile = new InferenceProfile(0.0, new MetricPoint(0.0, 0.0), new MetricPoint(1.0, 0.0),
                List.of(
                        new LateralStateCell(-1.0, 1.0, false, true, "main"),
                        new LateralStateCell(1.0, 1.0, false, true, "unlocalized")),
                new double[] {0.0, 0.0}, Map.of("main", present),
                ObservationOwnership.NO_RASTER, true, new double[][] {new double[0], new double[0]});

        assertSame(present, profile.orientationSupport(0),
                "a measured branch lookup must preserve its existing support object");
        ImageOrientationSupport firstUnavailable = profile.orientationSupport(1);
        assertEquals(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT,
                firstUnavailable.status());
        assertTrue(firstUnavailable.modes().isEmpty());
        assertEquals(0.0, firstUnavailable.certainty());
        assertSame(firstUnavailable, profile.orientationSupport(1),
                "repeated absent-branch reads must reuse the immutable unavailable value");
    }
}
