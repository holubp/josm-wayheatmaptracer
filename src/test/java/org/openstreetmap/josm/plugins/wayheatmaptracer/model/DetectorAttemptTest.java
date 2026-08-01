package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests detector attempt applicability semantics. */
class DetectorAttemptTest {
    @Test
    void onlyApplicableStatusEnablesGeometryApplication() {
        assertTrue(attempt(DetectorAttemptStatus.APPLICABLE).applicable());
        assertFalse(attempt(DetectorAttemptStatus.STRUCTURALLY_UNSAFE).applicable());
        assertFalse(attempt(DetectorAttemptStatus.NO_PERSISTENT_CORRIDOR).applicable());
        assertFalse(attempt(DetectorAttemptStatus.SOURCE_UNAVAILABLE).applicable());
    }

    private DetectorAttempt attempt(DetectorAttemptStatus status) {
        return new DetectorAttempt("hot", "hot", TrackerMode.CORRIDOR_AWARE, status,
            List.of("hot/track"), status.name().toLowerCase(), "test");
    }
}
