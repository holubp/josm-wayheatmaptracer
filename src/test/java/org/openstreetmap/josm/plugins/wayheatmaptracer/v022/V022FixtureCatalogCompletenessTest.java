package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Ensures every declared public fixture remains independently constructible and finite. */
class V022FixtureCatalogCompletenessTest {
    @Test
    void allScenesHaveFiniteIndependentGeometryAndDeclaredEvidence() {
        for (String id : V022SceneCatalog.ids()) {
            SyntheticHeatmapScene scene = V022SceneCatalog.scene(id, 83);

            assertEquals(id, scene.id());
            assertTrue(Double.isFinite(scene.bounds().minX()));
            assertTrue(Double.isFinite(scene.bounds().minY()));
            assertTrue(Double.isFinite(scene.bounds().maxX()));
            assertTrue(Double.isFinite(scene.bounds().maxY()));
            assertFalse(scene.attributes().isEmpty());
            assertFalse(scene.initialGraph().ways().isEmpty());
        }
    }

    @Test
    void sceneCatalogContainsNoPrivateLocationOrIdentityMaterial() {
        for (String id : V022SceneCatalog.ids()) {
            SyntheticHeatmapScene scene = V022SceneCatalog.scene(id, 11);
            assertTrue(scene.attributes().keySet().stream().noneMatch(key -> key.contains("wayId") || key.contains("archive")));
            assertTrue(scene.attributes().values().stream().noneMatch(value -> value.matches(".*[0-9]{9,}.*")));
        }
    }
}
