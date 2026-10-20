package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Cheap discovery sentinel: no source capture, engine, fixture acquisition or model claim. */
class V022DedicatedExperimentDiscoveryTest {
    @Test void ordinaryDiscoveryRemainsExecutable() {
        assertEquals(4, Math.addExact(2, 2));
        System.out.println("ORDINARY_DISCOVERY_EXECUTED");
    }

    @Test @Tag("dedicated-experiment") void dedicatedDiscoveryRequiresHardOptIn() {
        assertEquals("true", System.getenv("V022_DEDICATED_DISCOVERY_TEST"),
            "Dedicated discovery requires explicit environment opt-in before acquisition");
        System.out.println("DEDICATED_DISCOVERY_AFTER_OPT_IN");
    }
}
