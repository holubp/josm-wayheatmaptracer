package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;

/** Physical endpoint uncertainty must not become authority over gaps or fixed seams. */
class V022EndpointConnectorSupportTest {
    @Test
    void unknownOrSpatiallyVaryingNativePitchCannotAuthorizeAnEndpointMargin() {
        var constant = org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution
                .nativeSource(1.0, 0.2);
        assertEquals(1.0, ModernSingleWayEditPlanAdapter.constantNativeTerminalUncertainty(constant));
        assertEquals(0.0, ModernSingleWayEditPlanAdapter.constantNativeTerminalUncertainty(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution.renderedOnly(0.2)));
        var varying = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution(
                constant.kind(), constant.nativePitchMeters(), constant.renderedPitchMeters(),
                java.util.List.of(
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution.PitchSample(
                            0.0, java.util.OptionalDouble.of(1.0), 0.2),
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution.PitchSample(
                            10.0, java.util.OptionalDouble.of(0.1), 0.2)),
                java.util.OptionalDouble.empty());
        assertEquals(0.0, ModernSingleWayEditPlanAdapter.constantNativeTerminalUncertainty(varying),
                "representative pitch must not authorize a larger margin at a finer endpoint");
    }
    @Test
    void boundedAmbiguousEndpointPrefixCanReachDirectSupport() {
        assertTrue(admitted(3.575 / 8, 1.0, 5, true));
        assertFalse(admitted(3.575 / 8, 0.0, 5, true), "fixed-island joins retain the 2m bound");
    }

    @Test
    void uncertaintyAllowanceCannotGrowBeyondOneKnownSourcePixel() {
        assertTrue(admitted(0.5, 1.0, 6, true));
        assertFalse(admitted(0.5, 1.0, 7, true));
        assertFalse(admitted(0.5, 0.0, 5, true), "unknown native pitch supplies no extra authority");
    }

    @Test
    void ambiguousPrefixStillRequiresADirectSample() {
        assertFalse(admitted(0.5, 1.0, 6, false));
    }

    @Test
    void missingCensoredOrOccludedEvidenceCannotUseEndpointUncertainty() {
        for (ObservationOwnership missing : ObservationOwnership.values()) {
            if (missing == ObservationOwnership.DIRECT_TWO_SIDED
                    || missing == ObservationOwnership.DIRECT_AMBIGUOUS) continue;
            var support = new EndpointConnectorSupport(0.5, 1.0, 1.0);
            assertTrue(support.accept(missing, true));
            for (int i = 0; i < 3; i++) assertTrue(support.accept(ObservationOwnership.DIRECT_AMBIGUOUS, true));
            assertFalse(support.accept(ObservationOwnership.DIRECT_AMBIGUOUS, true), missing.name());
        }
        var occluded = new EndpointConnectorSupport(0.5, 1.0, 1.0);
        assertTrue(occluded.accept(ObservationOwnership.DIRECT_AMBIGUOUS, false));
        for (int i = 0; i < 3; i++) assertTrue(occluded.accept(ObservationOwnership.DIRECT_AMBIGUOUS, true));
        assertFalse(occluded.accept(ObservationOwnership.DIRECT_AMBIGUOUS, true));
    }

    @Test
    void ambiguityAfterDirectObservationKeepsTheOrdinaryBound() {
        var support = new EndpointConnectorSupport(0.5, 1.0, 1.0);
        assertTrue(support.accept(ObservationOwnership.DIRECT_TWO_SIDED, true));
        for (int i = 0; i < 4; i++) assertTrue(support.accept(ObservationOwnership.DIRECT_AMBIGUOUS, true));
        assertFalse(support.accept(ObservationOwnership.DIRECT_AMBIGUOUS, true));
    }

    private static boolean admitted(double step, double uncertainty, int ambiguous, boolean direct) {
        var support = new EndpointConnectorSupport(step, 1.0, uncertainty);
        for (int i = 0; i < ambiguous; i++) {
            if (!support.accept(ObservationOwnership.DIRECT_AMBIGUOUS, true)) return false;
        }
        if (direct && !support.accept(ObservationOwnership.DIRECT_TWO_SIDED, true)) return false;
        return support.directlyObserved();
    }
}
