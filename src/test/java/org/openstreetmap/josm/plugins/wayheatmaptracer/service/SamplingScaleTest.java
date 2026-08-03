package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedImageryService;

class SamplingScaleTest {
    private static final double[] LATITUDES = {-70.0, -49.44, 0.0, 49.44, 70.0};

    @BeforeAll
    static void setProjection() {
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void tileResolutionMatchesGeographicAdjacentPixelOracle() {
        for (int tileSize : List.of(256, 512)) {
            for (int zoom = 10; zoom <= 16; zoom++) {
                for (double latitude : LATITUDES) {
                    double calculated = TileHeatmapSampler.metersPerPixel(zoom, latitude, tileSize);
                    double longitudeStep = 360.0 / (tileSize * Math.pow(2.0, zoom));
                    double geographic = new LatLon(latitude, -longitudeStep / 2.0)
                        .greatCircleDistance(new LatLon(latitude, longitudeStep / 2.0));
                    assertTrue(Math.abs(calculated - geographic) / geographic <= 0.0015,
                        "source m/px mismatch z" + zoom + " lat=" + latitude + " tile=" + tileSize);
                }
            }
        }
    }

    @Test
    void visibleGroundScaleSeparatesProjectedAndGroundUnitsAtEveryRasterScale() {
        Projection projection = ProjectionRegistry.getProjection();
        for (double latitude : LATITUDES) {
            EastNorth center = projection.latlon2eastNorth(new LatLon(latitude, 14.5));
            List<EastNorth> anchors = List.of(
                new EastNorth(center.east() - 100.0, center.north()),
                center,
                new EastNorth(center.east() + 100.0, center.north())
            );
            for (double projectedScale : List.of(0.09725, 0.1945, 0.389, 0.778, 1.556)) {
                ProjectionGroundScale ground = ProjectionGroundScale.measure(anchors, projectedScale);
                for (double rasterScale : List.of(1.0, 6.0, 24.0)) {
                    SamplingScale scale = SamplingScale.visible(projectedScale, rasterScale, ground,
                        VisibleSourceResolutionResolver.SourceResolution.unknown("test-unknown"),
                        rasterScale, "test-compatibility");
                    assertEquals(projectedScale * ground.representativeMetersPerProjectionUnit(),
                        scale.groundMetersPerViewPixel(), 1e-12);
                    assertEquals(scale.groundMetersPerViewPixel() / rasterScale,
                        scale.groundMetersPerRasterPixel(), 1e-12);
                    assertEquals(scale.groundMetersPerViewPixel(),
                        scale.groundMetersPerRasterPixel() * rasterScale, 1e-12);
                }
            }
        }
    }

    @Test
    void recognizedStravaZ15HasExpectedNativePixelFootprint() {
        double latitude = 49.44;
        Projection projection = ProjectionRegistry.getProjection();
        EastNorth center = projection.latlon2eastNorth(new LatLon(latitude, 14.5));
        ProjectionGroundScale ground = ProjectionGroundScale.measure(List.of(center), 0.389);
        ImageryInfo info = new ImageryInfo("Managed", "tms[15]:https://content-a.strava.com/identified/globalheat/all/hot/{zoom}/{x}/{y}.png");
        info.setId(ManagedImageryService.MANAGED_LAYER_ID);
        VisibleSourceResolutionResolver.SourceResolution resolution = new VisibleSourceResolutionResolver()
            .resolveMetadata(info, 15, latitude);
        SamplingScale scale = SamplingScale.visible(0.389, 6.0, ground, resolution, 6.0,
            "legacy-rendered-pixel-compatibility");

        assertTrue(scale.nativeResolutionKnown());
        assertEquals(512, resolution.tileSize().orElseThrow());
        assertEquals(0.253, scale.groundMetersPerViewPixel(), 0.002);
        assertEquals(0.0421, scale.groundMetersPerRasterPixel(), 0.0005);
        assertEquals(36.84, scale.nativeSourcePixelSizeRasterPx().orElseThrow(), 0.08);
    }

    @Test
    void managedSourcePixelFootprintDoublesForEachLowerZoom() {
        double previous = Double.NaN;
        for (int zoom = 16; zoom >= 13; zoom--) {
            SamplingScale scale = SamplingScale.managed(0.389, 6.0,
                TileHeatmapSampler.metersPerPixel(zoom, 49.44, 512));
            double current = scale.nativeSourcePixelSizeRasterPx().orElseThrow();
            if (Double.isFinite(previous)) {
                assertEquals(previous * 2.0, current, 1e-9);
            }
            previous = current;
        }
    }

    @Test
    void projectedRasterAndGeographicDistancesRoundTripAcrossResolutionMatrix() {
        Projection projection = ProjectionRegistry.getProjection();
        for (double latitude : LATITUDES) {
            EastNorth center = projection.latlon2eastNorth(new LatLon(latitude, 14.5));
            for (double projectedScale : List.of(0.09725, 0.389, 1.556)) {
                ProjectionGroundScale ground = ProjectionGroundScale.measure(List.of(center), projectedScale);
                for (double rasterScale : List.of(1.0, 6.0, 24.0)) {
                    for (double distanceMeters : List.of(1.0, 6.0, 28.0)) {
                        assertCardinalRoundTrip(projection, center, projectedScale, rasterScale,
                            ground.eastMetersPerProjectionUnitMedian(), distanceMeters, true);
                        assertCardinalRoundTrip(projection, center, projectedScale, rasterScale,
                            ground.northMetersPerProjectionUnitMedian(), distanceMeters, false);
                        assertDiagonalRoundTrip(projection, center, ground, distanceMeters);
                    }
                }
            }
        }
    }

    @Test
    void unitExplicitConversionsPreserveSignedGroundAndNativeSourceDisplacements() {
        for (int zoom = 13; zoom <= 16; zoom++) {
            double nativeMeters = TileHeatmapSampler.metersPerPixel(zoom, 49.44, 512);
            for (double rasterScale : List.of(1.0, 6.0, 24.0)) {
                SamplingScale scale = SamplingScale.managed(0.389, rasterScale, nativeMeters, zoom, 512);
                for (double groundMeters : List.of(-28.0, -1.0, 0.0, 1.0, 6.0, 28.0)) {
                    double rasterPixels = scale.rasterPixelsForGroundMeters(groundMeters);
                    assertEquals(groundMeters, scale.groundMetersForRasterPixels(rasterPixels), 1e-12);
                    assertEquals(groundMeters / nativeMeters,
                        scale.nativeSourcePixelsForRasterPixels(rasterPixels).orElseThrow(), 1e-12);
                }
            }
        }
    }

    @Test
    void visibleScaleMatrixPreservesPhysicalAndNativeDisplacementsAcrossZoomLatitudeAndOversampling() {
        Projection projection = ProjectionRegistry.getProjection();
        for (double latitude : List.of(0.0, 49.44, 70.0)) {
            EastNorth center = projection.latlon2eastNorth(new LatLon(latitude, 14.5));
            for (double projectedScale : List.of(0.1945, 0.389, 0.778)) {
                ProjectionGroundScale ground = ProjectionGroundScale.measure(List.of(center), projectedScale);
                for (double rasterScale : List.of(1.0, 6.0, 24.0)) {
                    for (int zoom = 13; zoom <= 16; zoom++) {
                        double nativeMeters = TileHeatmapSampler.metersPerPixel(zoom, latitude, 512);
                        var source = VisibleSourceResolutionResolver.SourceResolution.known(
                            zoom, 512, nativeMeters, "test-source");
                        double groundRaster = projectedScale
                            * ground.representativeMetersPerProjectionUnit() / rasterScale;
                        SamplingScale scale = SamplingScale.visible(projectedScale, rasterScale, ground, source,
                            nativeMeters / groundRaster, "native-source-pixel");

                        double sixMetersInRaster = scale.rasterPixelsForGroundMeters(6.0);
                        assertEquals(6.0, scale.groundMetersForRasterPixels(sixMetersInRaster), 1e-12);
                        assertEquals(6.0 / nativeMeters,
                            scale.nativeSourcePixelsForRasterPixels(sixMetersInRaster).orElseThrow(), 1e-12);
                        assertEquals(nativeMeters,
                            scale.groundMetersForRasterPixels(
                                scale.nativeSourcePixelSizeRasterPx().orElseThrow()), 1e-12);
                    }
                }
            }
        }
    }

    @Test
    void visibleSourceResolverUsesRecognizedAndGenericTileSizesWithoutGuessing() {
        VisibleSourceResolutionResolver resolver = new VisibleSourceResolutionResolver();
        ImageryInfo strava = new ImageryInfo("Strava",
            "tms[15]:https://content-a.strava.com/identified/globalheat/all/hot/{zoom}/{x}/{y}.png");
        VisibleSourceResolutionResolver.SourceResolution stravaResolution =
            resolver.resolveMetadata(strava, 15, 49.44);
        assertTrue(stravaResolution.known());
        assertEquals(512, stravaResolution.tileSize().orElseThrow());

        ImageryInfo generic = new ImageryInfo("Generic",
            "tms[15]:https://example.invalid/{zoom}/{x}/{y}.png");
        VisibleSourceResolutionResolver.SourceResolution genericResolution =
            resolver.resolveMetadata(generic, 15, 49.44);
        assertTrue(genericResolution.known());
        assertEquals(256, genericResolution.tileSize().orElseThrow());

        assertFalse(resolver.resolveMetadata(generic, -1, 49.44).known());
        assertFalse(resolver.resolveMetadata(generic, 15, 90.0).known());
    }

    @Test
    void explicitSlideTimeZoomControlsNativeResolution() {
        VisibleSourceResolutionResolver resolver = new VisibleSourceResolutionResolver();
        ImageryInfo strava = new ImageryInfo("Strava",
            "tms[16]:https://content-a.strava.com/identified/globalheat/all/hot/{zoom}/{x}/{y}.png");

        var renderedAtZ15 = resolver.resolveMetadata(strava, 15, 49.44);
        var renderedAtZ16 = resolver.resolveMetadata(strava, 16, 49.44);

        assertEquals(15, renderedAtZ15.zoom().orElseThrow());
        assertEquals(16, renderedAtZ16.zoom().orElseThrow());
        assertEquals(renderedAtZ15.metersPerPixel().orElseThrow() / 2.0,
            renderedAtZ16.metersPerPixel().orElseThrow(), 1e-12);
    }

    @Test
    void unknownSourceNeverFabricatesNativeResolution() {
        EastNorth center = ProjectionRegistry.getProjection().latlon2eastNorth(new LatLon(49.44, 14.5));
        SamplingScale scale = SamplingScale.visible(0.389, 6.0,
            ProjectionGroundScale.measure(List.of(center), 0.389),
            VisibleSourceResolutionResolver.SourceResolution.unknown("non-tile-visible-layer"),
            6.0, "legacy-rendered-pixel-compatibility");

        assertFalse(scale.nativeResolutionKnown());
        assertTrue(scale.nativeSourceMetersPerPixel().isEmpty());
        assertTrue(scale.nativeSourcePixelSizeRasterPx().isEmpty());
        assertTrue(scale.nativeSourcePixelsForRasterPixels(6.0).isEmpty());
    }

    @Test
    void rejectsInvalidScaleInputs() {
        assertThrows(IllegalArgumentException.class, () -> TileHeatmapSampler.metersPerPixel(-1, 0.0, 512));
        assertThrows(IllegalArgumentException.class, () -> TileHeatmapSampler.metersPerPixel(15, Double.NaN, 512));
        assertThrows(IllegalArgumentException.class, () -> TileHeatmapSampler.metersPerPixel(15, 0.0, 0));
        assertThrows(IllegalArgumentException.class, () -> ProjectionGroundScale.measure(List.of(), 0.389));
        assertThrows(IllegalArgumentException.class, () -> ProjectionGroundScale.measure(
            List.of(new EastNorth(0.0, 0.0)), 0.0));
        SamplingScale scale = SamplingScale.managed(0.389, 6.0, 1.5);
        assertThrows(IllegalArgumentException.class, () -> scale.groundMetersForRasterPixels(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> scale.rasterPixelsForGroundMeters(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> scale.nativeSourcePixelsForRasterPixels(Double.NaN));
    }

    private void assertCardinalRoundTrip(
        Projection projection,
        EastNorth center,
        double projectionUnitsPerViewPixel,
        double rasterScale,
        double axisGroundMetersPerProjectionUnit,
        double distanceMeters,
        boolean eastAxis
    ) {
        double projectedDisplacement = distanceMeters / axisGroundMetersPerProjectionUnit;
        double rasterPixels = projectedDisplacement / projectionUnitsPerViewPixel * rasterScale;
        double reconstructedProjection = rasterPixels / rasterScale * projectionUnitsPerViewPixel;
        for (int sign : List.of(-1, 1)) {
            EastNorth displaced = eastAxis
                ? new EastNorth(center.east() + sign * reconstructedProjection, center.north())
                : new EastNorth(center.east(), center.north() + sign * reconstructedProjection);
            double geographic = projection.eastNorth2latlon(center)
                .greatCircleDistance(projection.eastNorth2latlon(displaced));
            double tolerance = Math.max(0.05, distanceMeters * 0.0015);
            assertEquals(distanceMeters, geographic, tolerance,
                "round-trip mismatch lat=" + projection.eastNorth2latlon(center).lat()
                    + " raster=" + rasterScale + " east=" + eastAxis);
        }
    }

    private void assertDiagonalRoundTrip(
        Projection projection,
        EastNorth center,
        ProjectionGroundScale ground,
        double distanceMeters
    ) {
        double axisDistance = distanceMeters / Math.sqrt(2.0);
        double eastUnits = axisDistance / ground.eastMetersPerProjectionUnitMedian();
        double northUnits = axisDistance / ground.northMetersPerProjectionUnitMedian();
        for (int eastSign : List.of(-1, 1)) {
            for (int northSign : List.of(-1, 1)) {
                EastNorth displaced = new EastNorth(
                    center.east() + eastSign * eastUnits,
                    center.north() + northSign * northUnits);
                double geographic = projection.eastNorth2latlon(center)
                    .greatCircleDistance(projection.eastNorth2latlon(displaced));
                double tolerance = Math.max(0.05, distanceMeters * 0.0015);
                assertEquals(distanceMeters, geographic, tolerance,
                    "diagonal round-trip mismatch lat=" + projection.eastNorth2latlon(center).lat());
            }
        }
    }
}
