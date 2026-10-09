package org.openstreetmap.josm.plugins.wayheatmaptracer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import javax.swing.SwingUtilities;
import javax.swing.JDialog;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.OrdinaryActionBenchmarkObserver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoverySettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileAddress;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginDirectories;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Runs one private, offline, real JOSM GUI action in a fresh process. This is
 * invoked by the fixed Python benchmark runner, never by a normal plugin UI.
 */
public final class BenchmarkHostMain {
    private BenchmarkHostMain() { }

    record FixtureSource(long generation) {
        FixtureSource {
            if (generation < 0L) {
                throw new IllegalArgumentException("Frozen source generation must be non-negative");
            }
        }

        ManagedHeatmapConfig config() {
            return fixtureConfig(generation);
        }

        void saveCacheGenerationPreference() {
            Config.getPref().putLong("wayheatmaptracer.cacheBuster", generation);
        }

        String sourceIdentity() {
            return "managed-selected-hot-g" + generation;
        }

        ManagedTileGeneration tileGeneration() {
            return new ManagedTileGeneration(generation);
        }
    }

    static FixtureSource fixtureSource(long generation) {
        return new FixtureSource(generation);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException("archive osm tiles receipt plugin nonce case version mode required");
        }
        Path archive = Path.of(args[0]), osm = Path.of(args[1]), tiles = Path.of(args[2]);
        Path receipt = Path.of(args[3]);
        System.setProperty("wayheatmaptracer.benchmark.offline", "true");
        System.setProperty("wayheatmaptracer.benchmark.pluginJar", args[4]);
        System.setProperty("wayheatmaptracer.benchmark.receipt", receipt.toString());
        System.setProperty("wayheatmaptracer.benchmark.nonce", args[5]);
        System.setProperty("wayheatmaptracer.benchmark.case", args[6].split(":", 2)[0]);
        System.setProperty("wayheatmaptracer.benchmark.version", args[6].split(":", 2)[1]);
        if (!args[7].equals("preview") && !args[7].equals("cancel")) {
            throw new IllegalArgumentException("Unknown benchmark host mode");
        }
        System.setProperty("wayheatmaptracer.benchmark.mode", args[7]);
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Real JOSM GUI display is unavailable");
        }
        MainApplication.main(new String[] {"--offline=ALL"});
        DataSet dataSet;
        try (InputStream stream = Files.newInputStream(osm)) {
            dataSet = OsmReader.parseDataSet(stream, NullProgressMonitor.INSTANCE);
        }
        var historical = FrozenReplayCodec.decode(Format15ArchiveReader.read(archive)
                .artifact("frozen-input.bin").orElseThrow(() -> new IllegalStateException(
                        "Original benchmark input requires a single frozen-input.bin" )).bytes());
        FixtureSource fixtureSource = fixtureSource(historical.network().sourceGeneration());
        DetachedWay expected = (DetachedWay) historical.network().primitives().get(
                historical.request().selectedWayKey());
        Way selected = (Way) dataSet.getPrimitiveById(expected.key().id(), OsmPrimitiveType.WAY);
        if (selected == null || selected.getNodesCount() != expected.nodeKeys().size()
                || !selected.getKeys().equals(expected.tags()) || selected.isModified() != expected.modified()) {
            throw new IllegalStateException("Original selected way payload differs");
        }
        for (int index = 0; index < selected.getNodesCount(); index++) {
            Node node = selected.getNode(index);
            DetachedNode frozen = (DetachedNode) historical.network().primitives().get(
                    expected.nodeKeys().get(index));
            if (node.getUniqueId() != frozen.key().id() || !node.getKeys().equals(frozen.tags())
                    || node.isModified() != frozen.modified()
                    || Double.doubleToRawLongBits(node.lat()) != Double.doubleToRawLongBits(
                            frozen.coordinate().latitudeDegrees())
                    || Double.doubleToRawLongBits(node.lon()) != Double.doubleToRawLongBits(
                            frozen.coordinate().longitudeDegrees())) {
                throw new IllegalStateException("Original selected node payload differs");
            }
        }
        var range = historical.request().selectedRange();
        List<OsmPrimitive> selectedPrimitives = new ArrayList<>();
        selectedPrimitives.add(selected);
        if (range.firstIndex() != 0 || range.lastIndex() != selected.getNodesCount() - 1) {
            selectedPrimitives.add(selected.getNode(range.firstIndex()));
            selectedPrimitives.add(selected.getNode(range.lastIndex()));
        }
        dataSet.setSelected(selectedPrimitives);
        ManagedHeatmapConfig config = fixtureSource.config();
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        PluginPreferences.save(config);
        fixtureSource.saveCacheGenerationPreference();
        PluginPreferences.saveTracingSettings(new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false,
                AlignmentSourceMode.MANAGED_TILES));
        PluginPreferences.saveGeometryCleanup(GeometryCleanupConfig.disabled());
        config = PluginPreferences.load();
        var selection = SelectionResolver.resolve(dataSet, false);
        if (selection.startIndex() != range.firstIndex() || selection.endIndex() != range.lastIndex()) {
            throw new IllegalStateException("Original selected range differs");
        }
        AlignmentConfig slideConfig = new AlignmentConfig(config, GeometryCleanupConfig.disabled());
        var seed = onEventThread(() -> new LiveBPreviewService().captureManagedSeed(dataSet, selection,
                slideConfig, fixtureSource.sourceIdentity()));
        var actual = seed.network();
        var frozen = historical.network();
        if (!actual.closure().equals(frozen.closure())
                || !actual.primitives().equals(frozen.primitives())
                || !actual.incomingReferrerWatches().equals(frozen.incomingReferrerWatches())
                || actual.sourceGeneration() != frozen.sourceGeneration()) {
            throw new IllegalStateException("Original live network context differs");
        }
        seedTiles(tiles, fixtureSource.tileGeneration());
        PluginInformation info = new PluginInformation(
                Path.of(System.getProperty("wayheatmaptracer.benchmark.pluginJar")).toFile());
        SwingUtilities.invokeAndWait(() -> {
            new WayHeatmapTracerPlugin(info);
            OsmDataLayer layer = new OsmDataLayer(dataSet, "benchmark-original-layer", null);
            MainApplication.getLayerManager().addLayer(layer);
            MainApplication.getLayerManager().setActiveLayer(layer);
            // The registered, ordinary menu action is invoked through the real UI owner.
            MainApplication.getMenu().moreToolsMenu.getMenuComponents();
        });
        SwingUtilities.invokeAndWait(() -> {
            var menu = MainApplication.getMenu().moreToolsMenu;
            for (var component : menu.getMenuComponents()) {
                if (component instanceof javax.swing.JMenuItem item
                        && item.getAction() instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction action
                        && action.forcedAlignmentMode() == null) {
                    action.actionPerformed(null);
                    return;
                }
            }
            throw new IllegalStateException("Registered ordinary alignment action is missing");
        });
        if (args[7].equals("cancel")) {
            SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    if (window instanceof JDialog dialog && dialog.isVisible()
                            && dialog.getTitle().endsWith("alignment preview")) {
                        OrdinaryActionBenchmarkObserver.cancellationRequested();
                        dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog,
                                java.awt.event.WindowEvent.WINDOW_CLOSING));
                        return;
                    }
                }
                throw new IllegalStateException("Cancellable production progress dialog is unavailable");
            });
        }
        for (int second = 0; second < 900; second++) {
            if (Files.isRegularFile(receipt)) {
                System.exit(0);
            }
            TimeUnit.SECONDS.sleep(1);
        }
        throw new IllegalStateException("Real preview did not publish within host deadline");
    }

    static <T> T onEventThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeAndWait(task);
        }
        return task.get();
    }

    private static ManagedHeatmapConfig fixtureConfig(long generation) {
        return new ManagedHeatmapConfig("fixture-key", "fixture-policy", "fixture-signature",
                "fixture-session", "all", "hot", "", ".*", AlignmentMode.PRECISE_SHAPE,
                TrackerMode.PROBABILISTIC, false, false, false, false, false, false,
                false, false, false, false, 7, 4, 3, InferenceMode.RAW_HIGH_RESOLUTION,
                15, 14, 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, generation);
    }

    private static void seedTiles(Path index, ManagedTileGeneration generation) throws Exception {
        List<String> lines = Files.readAllLines(index);
        if (lines.size() != 11 || !lines.get(0).equals("zoom\tx\ty\tsha256\trelativePath")) {
            throw new IllegalStateException("Selected Hot tile inventory is incomplete");
        }
        ManagedTileCache cache = new ManagedTileCache(
                PluginDirectories.ensurePluginDataDirectory().toPath().resolve("managed-source-tile-cache"),
                new TileDecoderClassifier());
        int z14 = 0, z15 = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 5) throw new IllegalStateException("Invalid tile record");
            int zoom = Integer.parseInt(parts[0]);
            ManagedTileAddress address = new ManagedTileAddress("all", "hot", zoom,
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            Path png = index.toAbsolutePath().getParent().resolve(parts[4]).normalize();
            if (!png.startsWith(index.toAbsolutePath().getParent())) {
                throw new IllegalStateException("Tile escapes fixture directory");
            }
            byte[] bytes = Files.readAllBytes(png);
            if (bytes.length > 1_048_576 || !HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)).equals(parts[3])) {
                throw new IllegalStateException("Selected Hot tile hash or size differs");
            }
            if (!new TileDecoderClassifier().decodeAndClassify(address, "image/png", bytes).usable()) {
                throw new IllegalStateException("Selected Hot tile is not usable PNG");
            }
            Path destination = cache.path(generation, address);
            Files.createDirectories(destination.getParent());
            Files.write(destination, bytes);
            if (zoom == 14) z14++; else if (zoom == 15) z15++;
        }
        if (z14 != 4 || z15 != 6) {
            throw new IllegalStateException("Selected Hot zoom inventory differs");
        }
    }
}
