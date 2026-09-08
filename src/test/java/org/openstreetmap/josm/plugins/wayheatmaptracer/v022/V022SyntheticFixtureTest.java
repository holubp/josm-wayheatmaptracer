package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests the independent analytic fixture contract used by the v0.22 test suites. */
class V022SyntheticFixtureTest {
    @Test
    void fixturePixelCentersAndMetricTruthAreDeterministic() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S01", 11);

        assertEquals(1.5, scene.rasterPitchMeters());
        assertEquals(0.01, scene.backgroundIntensity());
        assertEquals(30.0, scene.haloMeters());
        assertEquals(scene.bounds().minX() + 0.75, scene.pixelCenterX(0));
        assertEquals(scene.bounds().minY() + 0.75, scene.pixelCenterY(0));
        assertTrue(scene.truthGraph().branches().get(0).sampledPoints().size() > 2_400);
    }

    @Test
    void fixtureCatalogProvidesAllNeutralPublicScenes() {
        assertEquals(28, V022SceneCatalog.ids().size());
        for (int number = 1; number <= 28; number++) {
            assertTrue(V022SceneCatalog.ids().contains("S%02d".formatted(number)));
        }
    }

    @Test
    void scalarRasterUsesPortableSeededNoiseInsteadOfRuntimeRandomness() {
        SyntheticHeatmapScene first = V022SceneCatalog.scene("S02", 29);
        SyntheticHeatmapScene second = V022SceneCatalog.scene("S02", 29);
        SyntheticHeatmapScene changedSeed = V022SceneCatalog.scene("S02", 47);

        double x = 92.25;
        double y = 3.75;
        assertEquals(first.intensityAt(x, y), second.intensityAt(x, y));
        assertNotEquals(first.intensityAt(x, y), changedSeed.intensityAt(x, y));
        assertTrue(first.intensityAt(x, y) >= 0.0 && first.intensityAt(x, y) <= 1.0);
    }

    @Test
    void truthEvidenceInitialGraphAndDefectiveCandidateStaySeparate() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 11);

        assertFalse(scene.truthGraph().branches().isEmpty());
        assertFalse(scene.initialGraph().ways().isEmpty());
        assertTrue(scene.defectiveCandidate().isPresent());
        assertNotEquals(
                scene.truthGraph().branches().get(0).sampledPoints(),
                scene.defectiveCandidate().orElseThrow().points());
    }

    @Test
    void holesAndMasksDoNotChangePhysicalTruth() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S12", 11);
        List<V022Point> truth = scene.truthGraph().branches().get(0).sampledPoints();
        V022Point supported = scene.truthGraph().branches().get(0).curve().pointAtFraction(100.0 / 600.0);
        V022Point missingTail = scene.truthGraph().branches().get(0).curve().pointAtFraction(400.0 / 600.0);

        assertTrue(truth.stream().anyMatch(point -> point.distanceTo(supported) < 0.11));
        assertTrue(scene.evidenceMask().isObserved(supported.x(), supported.y()));
        assertFalse(scene.evidenceMask().isObserved(missingTail.x(), missingTail.y()));
    }
}
