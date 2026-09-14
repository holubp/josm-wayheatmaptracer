package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;

class V022DiagnosticsReplayTest {
    @Test
    void t132Format14ReaderAdvertisesOnlyArtifactsActuallyPresent() {
        ReplayCapability capability = ReplayCapabilityInspector.inspect(14, Set.of(
            "diagnostics.json", "profile-intensity.csv", "rendered-layer-capture.png",
            "original-segment.osm", "candidate-previews.osm"));

        assertTrue(capability.supports(ReplayLevel.SCALAR_INFERENCE));
        assertTrue(capability.supports(ReplayLevel.RASTER_INFERENCE));
        assertTrue(capability.supports(ReplayLevel.FINAL_GEOMETRY));
        assertFalse(capability.supports(ReplayLevel.FULL_EDIT_PLAN));
    }

    @Test
    void t133MissingReplayContextIsEnumeratedRatherThanFabricated() {
        ReplayCapability capability = ReplayCapabilityInspector.inspect(14, Set.of("diagnostics.json"));

        assertTrue(capability.missingPrerequisites().stream()
            .anyMatch(value -> value.startsWith("SCALAR_INFERENCE:")));
        assertTrue(capability.missingPrerequisites().stream()
            .anyMatch(value -> value.startsWith("FULL_EDIT_PLAN:")));
    }

    @Test
    void t135FrozenReplayHasNoNetworkAcquisitionRoute() {
        FrozenReplayProvider provider = new FrozenReplayProvider(evidence(), network());

        assertFalse(provider.allowsNetwork());
        assertThrows(IllegalStateException.class, () -> provider.requireNoAcquisition("missing-tile"));
    }

    private static EvidenceSnapshot evidence() {
        GeographicPoint origin = new GeographicPoint(42.0, 19.0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.FROZEN_REPLAY,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "frozen",
            EvidenceCorrelationGroup.DIRECT_SOURCE, false);
        ScalarEvidenceField field = new ScalarEvidenceField(2, 2,
            new double[] {0.0, 0.5, 0.5, 1.0}, new boolean[] {true, true, true, true}, lineage);
        return new EvidenceSnapshot("evidence", frame,
            RasterMetricTransform.visible(new MetricPoint(0, 1), 1.0),
            EvidenceResolution.nativeSource(1.0, 1.0),
            MetricRegion.rectangle(0.2, 0.2, 0.8, 0.8), MetricRegion.rectangle(0, 0, 1, 1),
            Map.of("direct", field), "frozen-source");
    }

    private static NetworkSnapshot network() {
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey second = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey wayKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
        Map<PrimitiveKey, DetachedPrimitive> values = Map.of(
            first, new DetachedNode(first, new GeographicPoint(42.0, 19.0), Map.of(), false, false),
            second, new DetachedNode(second, new GeographicPoint(42.0001, 19.0001), Map.of(), false, false),
            wayKey, new DetachedWay(wayKey, List.of(first, second), Map.of("highway", "path"), false, false));
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
            "replay-closure-v1", values.keySet(), Set.of(), Set.of(), Set.of(first, second), Set.of(), Map.of(), List.of(),
            MetricRegion.rectangle(-20, -20, 20, 20), MetricRegion.rectangle(-20, -20, 20, 20),
            false, true, true, true);
        return new NetworkSnapshot("network", SnapshotRole.CAPTURED_BEFORE, "dataset", 1, closure, values,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                .closedWorldReferrerWatches(values));
    }
}
