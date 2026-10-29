package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

class LegacyBSourceCaptureTest {
    private LegacyBSourceCapture.Settings settings() {
        return new LegacyBSourceCapture.Settings(12, 2, InferenceMode.STABLE_FIXED_SCALE,
                15, 13, 7.01, 2, IntensitySamplingMode.COLOR_MAPPING);
    }

    @Test void visibleCaptureUsesOriginalAnchorsAndNativeSixPixelNormalization() {
        var raster = new LiveBPreviewService.VisibleRaster(120, 120, new int[14400],
                0, 0, 20, 20, 1, 1, OptionalDouble.of(2), "synthetic", "EPSG:3857");
        var source = List.of(new ProjectedPoint(3, 10), new ProjectedPoint(17, 10));
        var input = LegacyBSourceCapture.visible(settings(), raster, source, "hot");
        assertEquals(2, input.anchors().size());
        assertEquals(source.get(0), input.anchors().get(0).source());
        assertEquals(18, input.anchors().get(0).raster().x());
        assertEquals(60, input.anchors().get(0).raster().y());
        assertEquals(6, input.options().trackerNormalizationRasterPx());
        assertEquals(12, input.options().halfWidthPx());
        assertEquals(2, input.options().stepPx());
        var projection = Projections.getProjectionByCode("EPSG:3857");
        double distance = projection.eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth(3, 10))
                .greatCircleDistance(projection.eastNorth2latlon(
                    new org.openstreetmap.josm.data.coor.EastNorth(17, 10)));
        assertEquals(distance, input.anchors().get(1).cumulativeGroundDistanceMeters(), 0);
        assertEquals(List.of("hot"), input.detectors());
    }

    @Test void visibleSamplingKeepsNativeClampsAndDirectDetectorIdentity() {
        var options = new LegacyBSourceCapture.Settings(1, 80, InferenceMode.STABLE_FIXED_SCALE,
                15, 13, 7.01, 2, IntensitySamplingMode.DIRECT_VALUE);
        var raster = new LiveBPreviewService.VisibleRaster(120, 120, new int[14400],
                0, 0, 20, 20, 1, 1, OptionalDouble.empty(), "synthetic", "EPSG:3857");
        var input = LegacyBSourceCapture.visible(options, raster,
                List.of(new ProjectedPoint(3, 10), new ProjectedPoint(17, 10)),
                IntensitySamplingMode.DIRECT_VALUE.detectorName());
        assertEquals(6, input.options().halfWidthPx());
        assertEquals(6, input.options().stepPx());
        assertEquals(IntensitySamplingMode.DIRECT_VALUE, input.options().intensityMode());
        assertThrows(IllegalArgumentException.class, () -> LegacyBSourceCapture.visible(options,
                raster, List.of(new ProjectedPoint(3, 10), new ProjectedPoint(17, 10)), "hot"));
    }

    @Test void numericalSettingsRejectNonfiniteAndInvalidWork() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyBSourceCapture.Settings(
                12, 2, InferenceMode.STABLE_FIXED_SCALE, 15, 13, Double.NaN, 2,
                IntensitySamplingMode.COLOR_MAPPING));
        assertThrows(IllegalArgumentException.class, () -> new LegacyBSourceCapture.Settings(
                12, 2, InferenceMode.STABLE_FIXED_SCALE, 15, 13, 7.01, 0,
                IntensitySamplingMode.COLOR_MAPPING));
    }

    @Test void captureGeometryCopiesExactLegacyOccurrencePolicyOnEdt() throws Exception {
        org.openstreetmap.josm.data.projection.ProjectionRegistry.setProjection(
                Projections.getProjectionByCode("EPSG:3857"));
        var first = new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(0, 0));
        var middle = new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(0, .001));
        var last = new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(0, .002));
        var way = new org.openstreetmap.josm.data.osm.Way();
        way.setNodes(List.of(first, middle, last));
        var other = new org.openstreetmap.josm.data.osm.Way();
        other.setNodes(List.of(middle, new org.openstreetmap.josm.data.osm.Node(
                new org.openstreetmap.josm.data.coor.LatLon(.001, .001))));
        var dataSet = new org.openstreetmap.josm.data.osm.DataSet();
        for (var node : List.of(first, middle, last, other.getNode(1))) { dataSet.addPrimitive(node); }
        dataSet.addPrimitive(way);
        dataSet.addPrimitive(other);
        var selection = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext(
                way, 0, 2, List.of(first, middle, last), Set.of(first, last));
        var result = new java.util.concurrent.atomic.AtomicReference<LegacyBSourceCapture.Geometry>();
        javax.swing.SwingUtilities.invokeAndWait(() -> result.set(LegacyBSourceCapture.geometry(selection, true, 1.5)));
        assertEquals(List.of(true, false, true), result.get().occurrences().stream().map(
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyTopologyPlanner.Occurrence::fixed).toList());
        assertTrue(result.get().occurrences().get(1).topologyAnchor());
        var original = result.get().originalProjected().get(1);
        middle.setCoor(new org.openstreetmap.josm.data.coor.LatLon(0, .003));
        assertEquals(original, result.get().originalProjected().get(1));
        assertTrue(result.get().simplify());
        assertEquals(1.5, result.get().projectedTolerance());
        assertThrows(IllegalStateException.class, () -> LegacyBSourceCapture.geometry(selection, false, 0));
    }

    @Test void acquiredManagedRasterKeepsNativePhaseScaleAndSamplingClamps() {
        var image = new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(30, 60, 0xFFEEFFFF);
        var valid = new boolean[128 * 128];
        Arrays.fill(valid, true);
        var transform = org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence
                .SupportedInputRasterTransform.webMercator(15, 8_388_544, 8_388_544, 1);
        var raster = new ManagedModernPreviewSource.Raster(image, valid, transform, "hot", 15,
                "synthetic", new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(1));
        var sources = new ManagedModernPreviewSource.SourceRasters(Map.of("hot", raster),
                new AlignmentTileSourcePlan("hot", Set.of("hot"), Set.of(), Set.of(15, 13), false));
        var source = List.of(new ProjectedPoint(-40, 0), new ProjectedPoint(40, 0));
        var input = LegacyBSourceCapture.managed(settings(), sources, source, "EPSG:3857", "hot");
        assertEquals(List.of("hot@15"), List.copyOf(input.mosaics().keySet()));
        var mosaic = input.mosaics().get("hot@15");
        assertEquals(8_388_544, mosaic.originWorldPxX());
        assertEquals(8_388_544, mosaic.originWorldPxY());
        assertEquals(6, mosaic.parameters().halfWidthPx());
        assertEquals(1, mosaic.parameters().stepPx());
        assertEquals(mosaic.parameters().metersPerPixel() / .389 * 6,
                mosaic.virtualRasterScale(), 0);
        assertEquals(0xFFEEFFFF, mosaic.argb()[60 * 128 + 30]);
        image.setRGB(30, 60, 0);
        assertEquals(0xFFEEFFFF, mosaic.argb()[60 * 128 + 30]);
        assertEquals(6, input.trackerNormalizationRasterPx());
        assertThrows(IllegalArgumentException.class, () -> LegacyBSourceCapture.managed(settings(),
                sources, source, "EPSG:3857", "all-colors-combined"));
    }
}
