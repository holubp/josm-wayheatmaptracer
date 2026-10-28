package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SharedNodeDisconnectionIntent;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;

/** Real capture/inference/planner authority shared only by the transaction regressions. */
record DisconnectionTransactionFixture(DataSet data, Way selected, List<Node> originals,
        List<Way> receivers, AlignmentEditPlan plan, LiveNetworkSnapshotValidator validator,
        LiveBPreviewService.Captured captured, LiveBPreviewService.VisibleRaster raster,
        VisibleSourceEpoch epoch) {
    static DisconnectionTransactionFixture create(AlignmentMode mode, boolean partialBoundary) {
        return create(mode, partialBoundary, false);
    }
    static DisconnectionTransactionFixture create(AlignmentMode mode, boolean partialBoundary, boolean multiway) {
        DataSet data = new DataSet();
        List<Node> nodes = new ArrayList<>();
        double[] east = {-20, -12, 12, 20};
        for (int i = 0; i < east.length; i++) {
            Node node = node(i + 1, east[i], 0);
            data.addPrimitive(node);
            nodes.add(node);
        }
        Way selected = way(10, nodes);
        selected.put("highway", "path");
        selected.put("name", "selected");
        selected.setModified(false);
        data.addPrimitive(selected);
        List<Way> receivers = new ArrayList<>();
        for (int occurrence : List.of(1, 2)) {
            Node end = node(30 + occurrence, east[occurrence], -8);
            data.addPrimitive(end);
            Way receiver = way(20 + occurrence, List.of(nodes.get(occurrence), end));
            data.addPrimitive(receiver);
            receivers.add(receiver);
        }
        if (multiway) {
            Node end = node(39, -12, -12); data.addPrimitive(end);
            Way receiver = way(29, List.of(nodes.get(1), end)); data.addPrimitive(receiver); receivers.add(receiver);
        }
        int first = partialBoundary ? 1 : 0, last = partialBoundary ? 2 : 3;
        SelectionContext selection = new SelectionContext(selected, first, last,
                nodes.subList(first, last + 1), Set.of(nodes.get(first), nodes.get(last)));
        var heatmap = new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*", mode,
                TrackerMode.CORRIDOR_AWARE, false, false, false, false, false, false,
                false, false, false, false, 7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION,
                15, 15, 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        AlignmentConfig config = new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
        VisibleSourceEpoch epoch = new VisibleSourceEpoch();
        LiveBPreviewService.VisibleRaster raster = raster(partialBoundary ? 0 : 4, epoch);
        LiveBPreviewService service = new LiveBPreviewService();
        AtomicReference<LiveBPreviewService.Captured> captured = new AtomicReference<>();
        V022AtomicApplyTest.onEdt(() -> {
            var initial = service.capture(data, selection, raster, config, false,
                    RecoveryPermissions.disabled(7.01));
            var intent = new SharedNodeDisconnectionIntent(key(selected), new OccurrenceRange(first, last),
                    Map.of(1, key(nodes.get(1)), 2, key(nodes.get(2))),
                    initial.disconnectionSource().canonicalHash());
            captured.set(service.capture(data, selection, raster, config, false,
                    new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.FIXED, false, intent)));
        });
        var computed = service.compute(captured.get(), CancellationProbe.NONE);
        assertTrue(!computed.partitioned(), "all chosen islands must be recomputed");
        var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        assertTrue(assessment.applyAvailable(), assessment.detail());
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        assertEquals(2, plan.disconnectedOccurrenceReplacements().size());
        assertEquals(partialBoundary ? 2 : 0, plan.after().proposedPortReplacements().size());
        AtomicReference<NetworkSnapshotCapture.CapturedSnapshot> receipt = new AtomicReference<>();
        V022AtomicApplyTest.onEdt(() -> receipt.set(NetworkSnapshotCapture.captureBound(
                data, captured.get().specification())));
        return new DisconnectionTransactionFixture(data, selected, List.copyOf(nodes), List.copyOf(receivers),
                plan, new LiveNetworkSnapshotValidator(receipt.get(), plan,
                        () -> plan.before().sourceGeneration()), captured.get(), raster, epoch);
    }

    ApplyAlignmentEditPlanCommand command(ApplyAlignmentEditPlanCommand.MutationProbe probe) {
        return new ApplyAlignmentEditPlanCommand(data, plan, validator, "Disconnect and align", probe);
    }

    VisibleSourceLockedApplyValidator visibleValidator(java.util.function.Consumer<String> reporter) {
        return new VisibleSourceLockedApplyValidator(validator, new LiveBPreviewService(), captured,
                () -> raster, epoch, () -> { }, reporter);
    }

    void assertDetachedWithoutLiveReferences(org.openstreetmap.josm.data.osm.OsmPrimitive primitive) {
        assertTrue(primitive.getDataSet() == null);
        assertTrue(data.getWays().stream().noneMatch(way -> way.getNodes().contains(primitive)));
        assertTrue(data.getRelations().stream().noneMatch(relation -> relation.getMembers().stream()
                .anyMatch(member -> member.getMember() == primitive)));
    }

    static PrimitiveKey key(org.openstreetmap.josm.data.osm.OsmPrimitive primitive) {
        return PrimitiveKey.existing(primitive instanceof Node ? PrimitiveKey.Type.NODE : PrimitiveKey.Type.WAY,
                primitive.getUniqueId());
    }

    private static Node node(long id, double east, double north) {
        Node node = new Node(new LatLon(Math.toDegrees(north / 6_378_137.0),
                Math.toDegrees(east / 6_378_137.0)));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static Way way(long id, List<Node> nodes) {
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(id, 1);
        way.setModified(false);
        return way;
    }

    private static LiveBPreviewService.VisibleRaster raster(double shift, VisibleSourceEpoch epoch) {
        int width = 600;
        int[] pixels = new int[width * width];
        for (int y = 0; y < width; y++) {
            double distance = (width / 2.0 - y) / 6.0 - shift;
            int gray = (int) Math.round(255 * (0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44)));
            java.util.Arrays.fill(pixels, y * width, (y + 1) * width,
                    0xff000000 | gray << 16 | gray << 8 | gray);
        }
        return new LiveBPreviewService.VisibleRaster(width, width, pixels, -50, -50, 50, 50,
                1, 1, OptionalDouble.of(1), "synthetic-disconnection-transaction", "EPSG:3857",
                epoch.captureStable());
    }
}
