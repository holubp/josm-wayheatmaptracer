package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Actual three-seed Legacy solve on the preserved 17-native-row R4 source. */
class LegacyEmissionR4Test {
    private static final String FIELD = "synthetic";

    @Test
    void allOriginalR4SeedsShareOneBoundedAttemptAndPreserveNativeToRetainedOwnership() {
        var source = PhysicalGeometryFixture.r4SyntheticFrame(4.0);
        var nativeRows = source.profiles().subList(1, 18);
        var crop = crop(source);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(crop.evidence(),
                 FIELD, crop.input(), crop.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(crop.evidence(),
                FIELD, crop.input().profileLocations(), 25, 1, 1, budget, permit);
            assertEquals(19, retained.profiles().levelZeroProfiles().size());
            var truth = new OptionalEmissionContextFactory.DeclaredSyntheticRasterTruth(
                source.fullScalarGrid(), 18, 17, 1.0, 0.0, List.of());
            var map = IntStream.range(1, 18).boxed().toList();
            var binding = OptionalEmissionContextFactory.createDeclaredSyntheticR4Binding(permit,
                retained, nativeRows, map, 1.0, truth);
            assertFalse(binding.productionEligible());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,
                    retained, binding);
                 var result = new RidgeTracker().trackEmission(input, CancellationProbe.NONE)) {
                assertEquals(17, result.originalObservations().size());
                assertEquals(map, result.originalObservations().stream()
                    .map(EmissionResult.OriginalObservation::retainedContextIndex).toList());
                assertTrue(result.intervalStages().isEmpty(),
                    "The unchanged R4 reliable-anchor inventory is empty");
                assertEquals(result.firstStage(), result.stitched());
                assertEquals(17, result.mapping().observationCount());
                assertTrue(result.mapping().emittedVertexForObservation(0).isPresent());
                assertTrue(result.mapping().emittedVertexForObservation(16).isPresent());
                int consumed = 0;
                for (int row = 0; row < 17; row++) {
                    var original = result.originalObservations().get(row);
                    assertEquals(List.of(0), original.originalPeakIds());
                    assertEquals(NativeConnectorEvaluator.SourceClass.OBSERVED, original.sourceClass());
                    assertEquals(0, binding.observations().get(row).producerPeaks().get(0).originalPeakId());
                    assertSame(nativeRows.get(row).peaks().get(0),
                        binding.observations().get(row).producerPeaks().get(0).peak());
                    var owner = result.mapping().observationOwners().get(row);
                    if (owner instanceof EmissionMapping.Emitted emitted) {
                        var vertex = result.inferenceVertices().get(emitted.vertexIndex());
                        assertEquals(EmissionResult.StateKind.OBSERVED_PEAK, vertex.kind());
                        assertEquals(0, vertex.nativeStateId().orElseThrow());
                        assertEquals(new EmissionMapping.ObservationVertex(row), vertex.owner());
                    } else if (owner instanceof EmissionMapping.Consumed link) {
                        consumed++;
                        assertEquals(EmissionResult.ArcKind.OPTIONAL_ADMITTED,
                            result.inferenceArcs().get(link.arcIndex()).kind());
                        assertEquals(NativeConnectorEvaluator.Status.ADMITTED,
                            result.inferenceArcs().get(link.arcIndex()).optionalReceipt().orElseThrow().status());
                    } else fail("An original R4 observation has no emitted or consumed owner");
                }
                assertEquals(17 - consumed, result.inferenceVertices().size());
                assertEquals(result.inferenceVertices().size() - 1, result.inferenceArcs().size());
                for (int index = 0; index < result.inferenceArcs().size(); index++) {
                    var arc = result.inferenceArcs().get(index);
                    if (arc.kind() == EmissionResult.ArcKind.OPTIONAL_ADMITTED) {
                        var left = assertInstanceOf(EmissionMapping.ObservationVertex.class,
                            result.mapping().vertexOwners().get(index));
                        var right = assertInstanceOf(EmissionMapping.ObservationVertex.class,
                            result.mapping().vertexOwners().get(index + 1));
                        assertTrue(right.observationIndex() - left.observationIndex() - 1 <= 16);
                        assertTrue(arc.sourceSpanMeters() <= 20);
                        var receipt = arc.optionalReceipt().orElseThrow();
                        assertEquals(arc.sourceSpanMeters(), receipt.sourceSpanMeters(), 1e-10);
                        assertEquals(arc.lengthMeters(), receipt.emittedLengthMeters(), 1e-10);
                        assertEquals(arc.q(), receipt.sourceWeightedLoss(), 1e-10);
                    }
                }
                assertEquals(nativeRows.get(16).cumulativeGroundDistanceMeters()
                    - nativeRows.get(0).cumulativeGroundDistanceMeters(),
                    result.sourceIntervalMassMeters(), 1e-10);
                assertTrue(result.work().workUsed() <= result.work().workLimit());
                assertTrue(result.work().pairVisits() <= result.work().pairLimit());
                assertTrue(result.peakRetainedBytes() <= AttemptMemoryLedger.MAX_BYTES);
                var ordinaryBySeed = List.of(allOrdinaryCost(input, binding, 12),
                    allOrdinaryCost(input, binding, 8), allOrdinaryCost(input, binding, 4));
                double ordinaryBaseline = ordinaryBySeed.stream().mapToDouble(Double::doubleValue)
                    .min().orElseThrow();
                assertTrue(result.firstStage().total() <= ordinaryBaseline + 1e-8,
                    "Exact optional solve must include every all-ordinary three-seed control");
                double arcC = result.inferenceArcs().stream().mapToDouble(EmissionResult.Arc::c).sum();
                double arcT = result.inferenceArcs().stream().mapToDouble(EmissionResult.Arc::t).sum();
                double firstOffset = result.inferenceVertices().get(0).offsetRasterPixels().orElseThrow();
                var selectedSeed = List.of(12.0, 8.0, 4.0).stream().filter(seed ->
                    Math.abs(result.firstStage().c() - arcC - .16 * Math.abs(firstOffset - seed)) < 1e-9
                        && Math.abs(result.firstStage().t() - arcT - .12 * Math.abs(firstOffset - seed)) < 1e-9)
                    .findFirst().orElseThrow();
                assertEquals(result.firstStage().total(), result.inferenceArcs().stream()
                    .mapToDouble(arc -> arc.q() + arc.c() + arc.t() + arc.k()).sum()
                    + .28 * Math.abs(firstOffset - selectedSeed), 1e-8);
                System.out.println("R4 Legacy N=17 retained=19 M=" + result.inferenceVertices().size()
                    + " consumed=" + consumed + " J=" + result.stitched().total()
                    + " ordinaryBaselines12_8_4=" + ordinaryBySeed + " work=" + result.work().workUsed()
                    + " pairs=" + result.work().pairVisits()
                    + " peakBytes=" + result.peakRetainedBytes() + " seed=" + selectedSeed
                    + " vertices=" + result.inferenceVertices().stream()
                        .map(vertex -> vertex.owner() + "@" + vertex.position())
                        .toList()
                    + " arcs=" + result.inferenceArcs().stream()
                        .map(arc -> arc.kind() + ":" + arc.sourceSpanMeters() + "m")
                        .toList());
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    private static double allOrdinaryCost(SampledEmissionInput input,
            LegacyEmissionBinding binding, double seed) {
        var context = input.context();
        double pitch = context.nativePitchMeters().orElseThrow();
        double cost = 0, priorSlope = 0;
        MetricPoint previous = null, beforePrevious = null;
        for (int row = 0; row < binding.observations().size(); row++) {
            var observation = binding.observations().get(row);
            var profile = observation.measurementProfile();
            double offset = observation.producerPeaks().get(0).peak().offsetPx();
            var raster = new RasterPoint(profile.anchorScreen().x + profile.normalScreen().x * offset,
                profile.anchorScreen().y + profile.normalScreen().y * offset);
            MetricPoint point = context.rasterTransform().pixelCenterToMetric(raster.x(), raster.y());
            if (row == 0) {
                double departure = Math.abs(offset - seed);
                cost += .28 * departure;
                priorSlope = offset - seed;
            } else {
                var earlier = binding.observations().get(row - 1);
                double previousOffset = earlier.producerPeaks().get(0).peak().offsetPx();
                double slope = offset - previousOffset;
                var grid = context.nativeGrid();
                var from = grid.toGrid(context.rasterTransform().metricToPixelCenter(previous));
                var to = grid.toGrid(context.rasterTransform().metricToPixelCenter(point));
                var integral = BilinearSegmentIntegral.evaluate(grid, from, to,
                    CancellationProbe.NONE, () -> { });
                double mean = integral.status() == BilinearSegmentIntegral.Status.COMPLETE
                    ? integral.mean().orElseThrow() : 0;
                double sourceSpan = observation.profile().cumulativeGroundDistanceMeters()
                    - earlier.profile().cumulativeGroundDistanceMeters();
                cost += sourceSpan / pitch * (1 - mean);
                cost += .16 * Math.abs(offset - previousOffset);
                cost += .12 * Math.abs(slope - priorSlope);
                if (beforePrevious != null) {
                    double ax = previous.xMeters() - beforePrevious.xMeters();
                    double ay = previous.yMeters() - beforePrevious.yMeters();
                    double bx = point.xMeters() - previous.xMeters();
                    double by = point.yMeters() - previous.yMeters();
                    double angle = Math.atan2(ax * by - ay * bx, ax * bx + ay * by);
                    cost += angle * angle / Math.max(1,
                        (beforePrevious.distanceTo(previous) + previous.distanceTo(point)) / (2 * pitch));
                }
                priorSlope = slope;
            }
            beforePrevious = previous;
            previous = point;
        }
        return cost;
    }

    private record Crop(EvidenceSnapshot evidence, CorridorTraceInput input, ProfileChainage chainage) { }

    private static Crop crop(PhysicalGeometryFixture.R4SyntheticFrame source) {
        int minX = 18, minY = 17, width = 93, height = 86, fullWidth = 128;
        double[] original = source.fullScalarGrid().copiedValues();
        boolean[] originalValid = source.fullScalarGrid().copiedValidity();
        double[] cropped = new double[width * height];
        boolean[] valid = new boolean[cropped.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int fullIndex = (minY + y) * fullWidth + minX + x;
            int cropIndex = y * width + x;
            cropped[cropIndex] = original[fullIndex];
            valid[cropIndex] = originalValid[fullIndex];
        }
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50, 14),
            new GeographicPoint(49.99, 13.99), new GeographicPoint(50.01, 14.02));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(minX, minY), 1, 0, 0, 1);
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(width, height, cropped, valid, lineage);
        var bounds = MetricRegion.rectangle(minX, minY, minX + width - 1, minY + height - 1);
        var evidence = new EvidenceSnapshot("legacy-r4-crop", frame, transform,
            EvidenceResolution.nativeSource(1, 1), bounds, bounds, Map.of(FIELD, field), "R4 synthetic crop");
        List<DetachedProfileSamplingLocation> locations = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        double cumulative = 0.0;
        Point2D.Double previous = null;
        for (Point2D.Double anchor : source.anchors()) {
            if (previous != null) cumulative += previous.distance(anchor);
            distances.add(cumulative);
            RasterPoint local = new RasterPoint(anchor.x - minX, anchor.y - minY);
            MetricPoint point = transform.pixelCenterToMetric(local.x(), local.y());
            locations.add(new DetachedProfileSamplingLocation(frame.toGeographic(point), point, local,
                frame, transform, cumulative));
            previous = anchor;
        }
        return new Crop(evidence, new CorridorTraceInput(locations, 1),
            new ProfileChainage(distances, cumulative));
    }
}
