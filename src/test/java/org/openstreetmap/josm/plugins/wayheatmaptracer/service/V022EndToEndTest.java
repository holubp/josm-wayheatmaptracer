package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** End-to-end production-source assertions required by the v0.22 release plan. */
class V022EndToEndTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void T166_managedAndVisibleSourcesKeepDistinctDetachedEvidenceLineage() throws Exception {
        Fixture fixture = fixture();
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] visible = new LiveBPreviewService.Captured[1];
        LiveBPreviewService.ManagedCaptureSeed[] managedSeed = new LiveBPreviewService.ManagedCaptureSeed[1];
        SwingUtilities.invokeAndWait(() -> {
            visible[0] = service.capture(fixture.dataSet(), fixture.selection(), visibleRaster(), visibleConfig());
            managedSeed[0] = service.captureManagedSeed(fixture.dataSet(), fixture.selection(), managedConfig(),
                    "managed-test");
        });

        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        LiveBPreviewService.Captured managed = service.attachManagedRaster(managedSeed[0],
                new ManagedModernPreviewSource.Raster(image, new boolean[] {true, true, true, true},
                        SupportedInputRasterTransform.webMercator(15, 0.0, 0.0, 2.0), "hot", 15,
                        "managed-test"));

        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,
                service.captureEvidence(visible[0], CancellationProbe.NONE).fields()
                        .get("selected-visible-source").lineage().acquisitionKind());
        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                service.captureEvidence(managed, CancellationProbe.NONE).fields().get("selected-visible-source").lineage()
                        .acquisitionKind());
        assertFalse(visible[0].managedRaster() != null);
        assertFalse(managed.raster() != null);
    }

    @Test
    void T167_previewCarriesRequestedCleanupModeIntoDetachedPipelineInput() throws Exception {
        Fixture fixture = fixture();
        GeometryCleanupConfig cleanup = GeometryCleanupPreset.BALANCED
                .apply(GeometryCleanupMode.REDUCE_POINTS_ONLY);
        AlignmentConfig config = new AlignmentConfig(visibleConfig().heatmap(), cleanup);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];

        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), visibleRaster(), config));

        assertEquals(cleanup, captured[0].cleanup());
        GeometryCleanupConfig smooth = GeometryCleanupPreset.BALANCED
                .apply(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE);
        LiveBPreviewService.Captured[] smoothed = new LiveBPreviewService.Captured[1];
        LiveBPreviewService.ManagedCaptureSeed[] managedSeed = new LiveBPreviewService.ManagedCaptureSeed[1];
        SwingUtilities.invokeAndWait(() -> {
            smoothed[0] = new LiveBPreviewService().capture(fixture.dataSet(), fixture.selection(),
                    visibleRaster(), new AlignmentConfig(visibleConfig().heatmap(), smooth));
            managedSeed[0] = new LiveBPreviewService().captureManagedSeed(fixture.dataSet(), fixture.selection(),
                    new AlignmentConfig(managedConfig().heatmap(), cleanup), "managed-cleanup");
        });
        LiveBPreviewService.Captured managed = new LiveBPreviewService().attachManagedRaster(managedSeed[0],
                new ManagedModernPreviewSource.Raster(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB),
                        new boolean[] {true, true, true, true},
                        SupportedInputRasterTransform.webMercator(15, 0.0, 0.0, 2.0), "hot", 15,
                        "managed-cleanup"));
        assertEquals(cleanup, managedSeed[0].cleanup());
        assertEquals(cleanup, managed.cleanup());
        LiveBPreviewService.Captured[] off = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> off[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), visibleRaster(), visibleConfig()));
        var offResult = new LiveBPreviewService().compute(off[0], CancellationProbe.NONE);
        var reducedResult = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
        var smoothResult = new LiveBPreviewService().compute(smoothed[0], CancellationProbe.NONE);
        assertFalse(offResult.pipeline().routes().isEmpty());
        assertFalse(reducedResult.pipeline().routes().isEmpty());
        assertFalse(smoothResult.pipeline().routes().isEmpty());
        assertEquals(ImageSupportedLocalCleanup.Status.SKIPPED,
                offResult.pipeline().routes().get(0).cleanupStatus());
        assertFalse(ImageSupportedLocalCleanup.Status.SKIPPED ==
                reducedResult.pipeline().routes().get(0).cleanupStatus());
        assertFalse(ImageSupportedLocalCleanup.Status.SKIPPED ==
                smoothResult.pipeline().routes().get(0).cleanupStatus());
    }

    private static Fixture fixture() {
        DataSet dataSet = new DataSet();
        Node a = loadedNode(1, 0.0, longitude(-8));
        Node b = loadedNode(2, 0.0, longitude(8));
        Way way = new Way();
        way.setNodes(List.of(a, b));
        way.setOsmId(10, 1);
        way.setModified(false);
        dataSet.addPrimitive(a);
        dataSet.addPrimitive(b);
        dataSet.addPrimitive(way);
        return new Fixture(dataSet, new SelectionContext(way, 0, 1, List.of(a, b), Set.of(a, b)));
    }

    private static LiveBPreviewService.VisibleRaster visibleRaster() {
        int width = 600;
        int height = 600;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distance = (y - 288.0) / RenderedHeatmapSampler.RASTER_SCALE;
            int gray = (int) Math.round(255.0 * (0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44)));
            int pixel = 0xff000000 | gray << 16 | gray << 8 | gray;
            java.util.Arrays.fill(argb, y * width, (y + 1) * width, pixel);
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb, -50.0, -50.0,
                50.0, 50.0, 1.0, 1.0, OptionalDouble.of(1.0), "visible-test", "EPSG:3857");
    }

    private static AlignmentConfig visibleConfig() {
        return config("", TrackerMode.PROBABILISTIC);
    }

    private static AlignmentConfig managedConfig() {
        return config("key", TrackerMode.CORRIDOR_AWARE);
    }

    private static AlignmentConfig config(String accessKey, TrackerMode trackerMode) {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig(accessKey, "policy", "signature", "session",
                "all", "hot", "", ".*", AlignmentMode.PRECISE_SHAPE, trackerMode, false, false,
                false, false, false, false, false, false, false, false, 7, 4, 3.0,
                InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static double longitude(double meters) {
        return Math.toDegrees(meters / 6_378_137.0);
    }

    private static Node loadedNode(long id, double lat, double lon) {
        Node node = new Node(new LatLon(lat, lon));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private record Fixture(DataSet dataSet, SelectionContext selection) {
    }
}
