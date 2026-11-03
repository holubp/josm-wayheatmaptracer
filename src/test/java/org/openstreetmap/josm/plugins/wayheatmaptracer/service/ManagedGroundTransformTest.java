package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class ManagedGroundTransformTest {
    @Test void wholeTileFramePreservesCenterPhaseAndBoundsLatitudeExtremePitchAndCurve() {
        var projection = Projections.getProjectionByCode("EPSG:3857");
        int zoom = 15;
        var source = List.of(projection.latlon2eastNorth(new LatLon(49.44, 14.0)),
                projection.latlon2eastNorth(new LatLon(49.4401, 14.0002)));
        var parameters = new TileHeatmapSampler.SamplingParameters(zoom, 49.44,
                TileHeatmapSampler.metersPerPixel(zoom, 49.44), 28.0, 6.0, 56, 12);
        double scale = parameters.metersPerPixel()
                / TileHeatmapSampler.REFERENCE_VIEW_METERS_PER_PIXEL
                * TileHeatmapSampler.REFERENCE_RASTER_SCALE;
        var owner = AttemptMemoryLedger.production().rootOwner();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try {
            var receipt = ManagedSourceRequestReceipt.captureBeforeFetch(source, "hot", zoom,
                    parameters, scale, projection,
                    point -> world(projection.eastNorth2latlon(point), zoom), budget, owner);
            int width = (receipt.maxTileX() - receipt.minTileX() + 1) * TileHeatmapSampler.TILE_SIZE;
            int height = (receipt.maxTileY() - receipt.minTileY() + 1) * TileHeatmapSampler.TILE_SIZE;
            var mosaic = new TileHeatmapSampler.TileMosaic("hot", zoom,
                    receipt.minTileX() * (double) TileHeatmapSampler.TILE_SIZE,
                    receipt.minTileY() * (double) TileHeatmapSampler.TILE_SIZE,
                    new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB),
                    List.of(), Map.of(), parameters, true, scale);
            var transform = ManagedGroundTransform.fromSource(mosaic, receipt, budget, owner);
            RasterPoint origin = new RasterPoint(0.0, 0.0);
            double worldSize = TileHeatmapSampler.TILE_SIZE * Math.scalb(1.0, zoom);
            var centerWorld = new Point2D.Double(mosaic.originWorldPxX() + 0.5,
                    mosaic.originWorldPxY() + 0.5);
            var expectedGeographic = geographic(centerWorld, worldSize);
            assertEquals(transform.frame().toMetric(expectedGeographic), transform.metricAt(origin));
            RasterPoint fractional = new RasterPoint(1.25 * scale, 2.75 * scale);
            RasterPoint roundTrip = transform.rasterAt(transform.metricAt(fractional));
            assertEquals(fractional.x(), roundTrip.x(), 1e-5);
            assertEquals(fractional.y(), roundTrip.y(), 1e-5);
            double k = 2.0 * Math.PI / worldSize;
            double eastScale = transform.frame().distortionCertificate().eastMetersPerRadian();
            double northScale = transform.frame().distortionCertificate().northMetersPerRadian();
            for (double y : List.of(mosaic.originWorldPxY() + 0.5,
                    mosaic.originWorldPxY() + height - 0.5)) {
                double latitude = geographic(new Point2D.Double(centerWorld.x, y), worldSize)
                        .latitudeDegrees();
                double localPitch = k * Math.max(eastScale,
                        northScale * Math.cos(Math.toRadians(latitude)));
                assertTrue(transform.maximumNativePixelPitchMeters() >= localPitch);
                RasterPoint localRaster = new RasterPoint(0.0,
                        (y - mosaic.originWorldPxY() - 0.5) * scale);
                assertEquals(localPitch, transform.nativePixelPitchAt(localRaster), 1e-8);
                assertEquals(transform.nativePixelPitchAt(localRaster) / scale,
                        transform.virtualPixelPitchAt(localRaster), 0.0);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> transform.nativePixelPitchAt(new RasterPoint(-scale, 0)));
            RasterPoint start = new RasterPoint(20.0 * scale, 30.0 * scale);
            RasterPoint end = new RasterPoint(200.0 * scale, 300.0 * scale);
            RasterPoint mid = new RasterPoint((start.x() + end.x()) * 0.5,
                    (start.y() + end.y()) * 0.5);
            double actualDeviation = Math.abs(transform.metricAt(mid).yMeters()
                    - (transform.metricAt(start).yMeters()
                        + transform.metricAt(end).yMeters()) * 0.5);
            assertTrue(transform.maximumNorthChordErrorMeters(start, end) >= actualDeviation);
        } finally {
            owner.close();
        }
    }

    private static Point2D.Double world(LatLon point, int zoom) {
        double size = TileHeatmapSampler.TILE_SIZE * Math.scalb(1.0, zoom);
        double sine = Math.sin(Math.toRadians(point.lat()));
        return new Point2D.Double((point.lon() + 180.0) / 360.0 * size,
                (0.5 - Math.log((1.0 + sine) / (1.0 - sine)) / (4.0 * Math.PI)) * size);
    }

    private static GeographicPoint geographic(Point2D.Double world, double size) {
        return new GeographicPoint(
                Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * world.y / size)))),
                world.x / size * 360.0 - 180.0);
    }
}
