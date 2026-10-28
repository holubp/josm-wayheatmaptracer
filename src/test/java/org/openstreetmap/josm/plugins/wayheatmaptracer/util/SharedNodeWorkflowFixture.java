package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Public synthetic capture and production inference shared by workflow/replay regressions. */
public record SharedNodeWorkflowFixture(DataSet data, Way way, List<Node> nodes,
        SelectionContext selection, AlignmentConfig config, LiveBPreviewService.VisibleRaster raster,
        LiveBPreviewService.Captured initial) {
    public static SharedNodeWorkflowFixture create() throws Exception {
        return create(AlignmentMode.PRECISE_SHAPE, false);
    }
    public static SharedNodeWorkflowFixture create(AlignmentMode mode, boolean longSelection) throws Exception {
        return create(mode, longSelection, false);
    }
    public static SharedNodeWorkflowFixture create(AlignmentMode mode, boolean longSelection, boolean partialBoundary) throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        DataSet data = new DataSet();
        List<Node> nodes = new ArrayList<>();
        double[] east = longSelection ? java.util.stream.IntStream.rangeClosed(0, 40)
                .mapToDouble(i -> -80 + 4 * i).toArray() : new double[] {-20, -12, 12, 20};
        for (int i = 0; i < east.length; i++) {
            Node node = node(i + 1, east[i], 0); data.addPrimitive(node); nodes.add(node);
        }
        Way way = new Way(); way.setNodes(nodes); way.setOsmId(10, 1); way.setModified(false);
        data.addPrimitive(way);
        for (int i : longSelection ? List.of(10, 30) : List.of(1, 2)) {
            Node end = node(100 + i, east[i], -8); data.addPrimitive(end);
            Way receiver = new Way();
            if (longSelection) {
                Node north = node(400 + i, east[i], 8); data.addPrimitive(north);
                receiver.setNodes(List.of(end, nodes.get(i), north));
            } else receiver.setNodes(List.of(nodes.get(i), end));
            receiver.setOsmId(200 + i, 1); receiver.setModified(false); data.addPrimitive(receiver);
        }
        int first = partialBoundary ? 1 : 0, last = partialBoundary ? nodes.size() - 2 : nodes.size() - 1;
        SelectionContext selection = new SelectionContext(way, first, last, nodes.subList(first, last + 1),
                Set.of(nodes.get(first), nodes.get(last)));
        var heatmap = new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*",
                mode, TrackerMode.CORRIDOR_AWARE, false, false, false,
                false, false, false, false, false, false, false, 7, 4, 3,
                InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56,
                IntensitySamplingMode.COLOR_MAPPING, 0L);
        var config = new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
        int halfExtent = longSelection ? 100 : 50;
        int width = halfExtent * 12; int[] pixels = new int[width * width];
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                double eastPosition = (x - width / 2.0) / 6;
                // Return to the untouched fixed island at x=8m; constant offset there
                // would correctly fail the production direct-support/ownership guard.
                double center = partialBoundary ? 0 : longSelection ? 4 * Math.min(1,
                        Math.min(Math.max(0, (eastPosition + 80) / 16), Math.max(0, (8 - eastPosition) / 16))) : 4;
                double distance = (width / 2.0 - y) / 6 - center;
                int gray = (int) Math.round(255 * (0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44)));
                pixels[y * width + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        var raster = new LiveBPreviewService.VisibleRaster(width, width, pixels,
                -halfExtent, -halfExtent, halfExtent, halfExtent,
                1, 1, OptionalDouble.of(1), "synthetic-workflow", "EPSG:3857");
        var initial = edt(() -> new LiveBPreviewService().capture(data, selection, raster, config,
                false, RecoveryPermissions.disabled(7.01)));
        return new SharedNodeWorkflowFixture(data, way, List.copyOf(nodes), selection, config, raster, initial);
    }

    public LiveBPreviewService.Computed compute(Map<Integer, PrimitiveKey> choices) throws Exception {
        return new LiveBPreviewService().compute(capture(choices), CancellationProbe.NONE);
    }
    public LiveBPreviewService.Captured capture(Map<Integer, PrimitiveKey> choices) throws Exception {
        var intent = choices.isEmpty() ? SharedNodeDisconnectionIntent.none()
                : new SharedNodeDisconnectionIntent(key(way), initial.specification().selectedRange(),
                        choices, initial.disconnectionSource().canonicalHash());
        var permissions = new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.FIXED, false, intent);
        return edt(() -> new LiveBPreviewService().capture(data, selection, raster, config, false, permissions));
    }

    public static PrimitiveKey key(org.openstreetmap.josm.data.osm.OsmPrimitive value) {
        return PrimitiveKey.existing(value instanceof Node ? PrimitiveKey.Type.NODE : PrimitiveKey.Type.WAY,
                value.getUniqueId());
    }
    public static <T> T edt(Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return work.call();
        FutureTask<T> task = new FutureTask<>(work); SwingUtilities.invokeAndWait(task);
        try { return task.get(); } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
    private static Node node(long id, double east, double north) {
        Node node = new Node(new LatLon(Math.toDegrees(north / 6_378_137), Math.toDegrees(east / 6_378_137)));
        node.setOsmId(id, 1); node.setModified(false); return node;
    }
}
