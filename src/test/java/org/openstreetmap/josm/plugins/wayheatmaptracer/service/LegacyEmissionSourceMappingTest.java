package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Native/Synthetic parity where original ordinals differ from retained context rows. */
class LegacyEmissionSourceMappingTest {
    @Test void nonidentityContextRowsPreserveSourceOriginAndExactEventMass() {
        int width = 96, height = 96;
        var values = new double[width * height];
        var valid = new boolean[values.length]; Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            values[y * width + x] = Math.exp(-.5 * Math.pow((y - 64.5) / 3, 2));
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
            new GeographicPoint(-.01, -.01), new GeographicPoint(.01, .01));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var field = new ScalarEvidenceField(width, height, values, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "ridge",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        var evidence = new EvidenceSnapshot("mapped-source", frame, transform,
            EvidenceResolution.nativeSource(1, 1), MetricRegion.rectangle(0, 0, 95, 95),
            MetricRegion.rectangle(-.5, -.5, 95.5, 95.5), Map.of("ridge", field), "mapped-source");
        var locations = new ArrayList<DetachedProfileSamplingLocation>();
        for (int row = 0; row < 7; row++) locations.add(DetachedProfileSamplingLocation.at(
            frame.toGeographic(new MetricPoint(24 + 4 * row, 64.22)), frame, transform, 4.0 * row));
        var trace = new CorridorTraceInput(locations, 1);
        var sourceS = new ProfileChainage(List.of(0.0, 4.0, 8.0, 12.0, 16.0, 20.0, 24.0), 4, 100);
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence, "ridge",
                 trace, sourceS, 25, budget, owner)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence, "ridge",
                trace.profileLocations(), 25, 1, 1, budget, permit);
            var mapping = List.of(1, 3, 5);
            var event = new LegacyEmissionBinding.ProtectedOccurrence("source-108", 0, 1, 108,
                new MetricPoint(32, 64.35), true);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                mapping, 1, List.of(event));
            var profiles = binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList();
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding);
                 var result = new LegacyEmissionKernel().solve(input, profiles, List.of(0.0), List.of(),
                     (rows, offsets, intensities) -> offsets, CancellationProbe.NONE)) {
                assertEquals(mapping, result.originalObservations().stream()
                    .map(EmissionResult.OriginalObservation::retainedContextIndex).toList());
                assertEquals(List.of(104.0, 112.0, 120.0), result.originalObservations().stream()
                    .map(EmissionResult.OriginalObservation::sourceChainageMeters).toList());
                assertEquals(List.of(4.0, 4.0, 8.0), result.inferenceArcs().stream()
                    .map(EmissionResult.Arc::sourceSpanMeters).toList());
                assertEquals(16, result.sourceIntervalMassMeters(), 0);
                assertEquals(event.exactPosition(), result.inferenceVertices().get(1).position());
                assertEquals(new EmissionMapping.BoundaryVertex(event.occurrenceId()),
                    result.inferenceVertices().get(1).owner());
                for (var original : result.originalObservations())
                    assertEquals(transform.pixelCenterToMetric(original.rasterAnchor().x(), original.rasterAnchor().y()),
                        original.metricAnchor());
            }
        }
        assertEquals(0, ledger.currentBytes());
    }
}
