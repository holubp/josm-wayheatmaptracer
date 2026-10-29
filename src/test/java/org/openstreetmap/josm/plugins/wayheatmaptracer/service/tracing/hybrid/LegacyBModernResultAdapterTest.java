package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

class LegacyBModernResultAdapterTest {
    @Test void commonPreviewCarriesActualAssembledGeometryWithoutInventingBPosterior() {
        var fixture = LegacyBProductionRunnerTest.fixture(false, false);
        var ledger = AttemptMemoryLedger.production();
        try (var root = ledger.rootOwner()) {
            var executed = new LegacyBProductionRunner().runVisible(fixture.input(), fixture.visible(),
                    CancellationProbe.NONE, root, LegacyBPipeline.Deadline.production());
            var adapted = LegacyBModernResultAdapter.adapt(executed.value(), fixture.input(),
                    new ModernTracePipeline.Options(fixture.input().fieldName(), GeometryCleanupConfig.disabled(),
                            "selected-visible", 0), CancellationProbe.NONE);
            assertEquals(TrackerMode.LEGACY_THEN_B, adapted.inference().engine());
            assertEquals(executed.value().assessments().size(), adapted.routes().size());
            assertFalse(adapted.routes().isEmpty());
            for (var route : adapted.routes()) {
                var original = executed.value().assessments().stream().filter(row ->
                        row.candidate().id().equals(route.hypothesis().id())).findFirst().orElseThrow();
                assertEquals(original.candidate().points(), route.hypothesis().points());
                assertEquals(route.hypothesis(), route.rawHypothesis(),
                        "The assembled Hybrid output is the raw input to common final processing");
                assertFalse(route.geometryChanged(), "No common cleanup changed the assembled Hybrid output");
                assertEquals(original.pointIds(), route.pointIds());
                assertEquals(original.assignments(), route.assignments());
                assertEquals(original.quality(), route.quality());
                assertTrue(route.hypothesis().posteriorProbability().isEmpty());
                assertEquals(route.hypothesis().points().size(), route.hypothesis().support().size());
                var parent = executed.value().alternatives().stream().filter(alternative ->
                        alternative.id().equals(original.candidate().parentSeedId())).findFirst().orElseThrow();
                assertEquals(parent.score(), route.hypothesis().diagnostics().get("legacyNativeScore"));
            }
            long transitions = executed.value().batch().sections().stream()
                    .flatMap(section -> section.execution().stream())
                    .mapToLong(section -> section.run().result().evaluatedTransitions()).sum();
            assertTrue(transitions > 0);
            assertEquals(transitions, adapted.inference().evaluatedTransitions());
            assertTrue(adapted.inference().workAccounting().isEmpty());
        }
    }
}
