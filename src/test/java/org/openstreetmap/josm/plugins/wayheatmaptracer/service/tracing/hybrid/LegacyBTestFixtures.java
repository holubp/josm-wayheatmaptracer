package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

/** Explicit closed-world synthetic input; never a substitute for live capture authority. */
final class LegacyBTestFixtures {
    record Fixture(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network,
            List<MetricPoint> original) { }

    static Fixture fixture() {
        return fixture(false);
    }

    static Fixture fixture(boolean sharedInterior) {
        return fixture(sharedInterior, 0, 5);
    }

    static Fixture alignedEndpointFixture() {
        return fixture(false, -6, 0);
    }

    private static Fixture fixture(boolean sharedInterior, double originalMiddleY, double ridgeY) {
        var origin = new GeographicPoint(42, 19);
        var frame = LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        var first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        var middle = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        var last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 3);
        var way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 4);
        Map<PrimitiveKey,DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(first, new DetachedNode(first, origin, Map.of(), false, false));
        values.put(middle, new DetachedNode(middle, frame.toGeographic(new MetricPoint(10, originalMiddleY)), Map.of(), false, false));
        values.put(last, new DetachedNode(last, frame.toGeographic(new MetricPoint(20, 0)), Map.of(), false, false));
        values.put(way, new DetachedWay(way, List.of(first, middle, last), Map.of("highway", "path"), false, false));
        if (sharedInterior) {
            var sideNode = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 5);
            var sideWay = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 6);
            values.put(sideNode, new DetachedNode(sideNode, frame.toGeographic(new MetricPoint(10, 10)),
                    Map.of(), false, false));
            values.put(sideWay, new DetachedWay(sideWay, List.of(middle, sideNode),
                    Map.of("highway", "path"), false, false));
        }
        var footprint = MetricRegion.rectangle(-50.5, -50.5, 50.5, 50.5);
        var edit = MetricRegion.rectangle(-10, -10, 30, 10);
        var closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
                "legacy-b-test-v1", values.keySet(), Set.of(way, middle), Set.of(middle), Set.of(first, last),
                Set.of(), Map.of(way, List.of(new OccurrenceRange(0, 2))), List.of(),
                footprint, edit, true, true, true, true);
        var network = new NetworkSnapshot("network", SnapshotRole.CAPTURED_BEFORE, "synthetic-dataset", 1,
                closure, values, V022SnapshotFixtures.closedWorldReferrerWatches(values));
        int size = 101;
        double[] intensity = new double[size * size];
        boolean[] valid = new boolean[intensity.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < size; y++) {
            double distance = (y - 50) - ridgeY;
            double signal = .02 + .8 * Math.exp(-distance * distance / 2);
            Arrays.fill(intensity, y * size, (y + 1) * size, signal);
        }
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var resolution = EvidenceResolution.nativeSource(1, 1);
        var evidence = new EvidenceSnapshot("evidence", frame,
                new RasterMetricTransform("legacy-b-raster", RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                        new MetricPoint(-50, -50), 1, 0, 0, 1, 1), resolution,
                MetricRegion.rectangle(-40, -40, 40, 40), footprint,
                Map.of("synthetic", new ScalarEvidenceField(size, size, intensity, valid, lineage)), "synthetic-source");
        var source = List.of(first, middle, last).stream()
                .map(key -> frame.toMetric(((DetachedNode) values.get(key)).coordinate())).toList();
        var request = new TraceRequest(way, new OccurrenceRange(0, 2), TrackerMode.LEGACY_THEN_B,
                AlignmentMode.PRECISE_SHAPE, sharedInterior
                    ? new RecoveryPermissions(false, 4, 4, JunctionPolicy.LEGACY_BOUNDED_MOVE, false)
                    : RecoveryPermissions.disabled(4), TraceBudgets.defaults(),
                evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
                "settings", "legacy-then-b-v1", "synthetic-sampler", 2,
                new ProbabilisticProfileFactory().profileChainage(source, 2), resolution);
        return new Fixture(request, evidence, network, source);
    }

    static LegacyBModels.Section section(Fixture fixture, double centerY) {
        var points = List.of(fixture.original().get(0),
                new MetricPoint(fixture.original().get(1).xMeters(), centerY), fixture.original().get(2));
        return new LegacyBModels.Section("part-0", "legacy-0", new OccurrenceRange(0, 2), points,
                Map.of(0, 0, 1, 1, 2, 2), false);
    }
}
