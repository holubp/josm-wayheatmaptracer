package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Original selected-source search footprint frozen before managed tile acquisition. */
public final class ManagedSourceRequestReceipt {
    private static final int MAX_SOURCE_SEGMENTS = 4096;
    private final Projection projection;
    private final List<EastNorth> originalSource;
    private final String selectedColor;
    private final int zoom;
    private final int minTileX, maxTileX, minTileY, maxTileY;
    private final double nativeSearchHalfWidth;
    private final double virtualRasterScale;
    private final TileHeatmapSampler.SamplingParameters parameters;
    // MetricRegion is used only as a polygon predicate; these coordinates are world pixels, not metres.
    private final MetricRegion originalWorldPixelDecisionRegion;
    private final AttemptMemoryLedger.Owner owner;

    private ManagedSourceRequestReceipt(Projection projection, List<EastNorth> originalSource,
            String selectedColor, int zoom, int minTileX, int maxTileX, int minTileY,
            int maxTileY, double nativeSearchHalfWidth, double virtualRasterScale,
            TileHeatmapSampler.SamplingParameters parameters,
            MetricRegion originalWorldPixelDecisionRegion,
            AttemptMemoryLedger.Owner owner) {
        this.projection = projection;
        this.originalSource = originalSource;
        this.selectedColor = selectedColor;
        this.zoom = zoom;
        this.minTileX = minTileX;
        this.maxTileX = maxTileX;
        this.minTileY = minTileY;
        this.maxTileY = maxTileY;
        this.nativeSearchHalfWidth = nativeSearchHalfWidth;
        this.virtualRasterScale = virtualRasterScale;
        this.parameters = parameters;
        this.originalWorldPixelDecisionRegion = originalWorldPixelDecisionRegion;
        this.owner = owner;
    }

    static ManagedSourceRequestReceipt captureBeforeFetch(List<EastNorth> selectedSource,
            String selectedColor, int zoom, TileHeatmapSampler.SamplingParameters parameters,
            double virtualRasterScale, Projection projection,
            Function<EastNorth, Point2D.Double> worldProjection,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner parent) {
        Objects.requireNonNull(selectedSource, "original selected source");
        Objects.requireNonNull(parameters, "effective sampling");
        Objects.requireNonNull(projection, "captured projection");
        Objects.requireNonNull(worldProjection, "source world projection");
        Objects.requireNonNull(budget, "shared work budget");
        Objects.requireNonNull(parent, "attempt owner");
        if (!"EPSG:3857".equals(projection.toCode()) || selectedColor == null
                || selectedColor.isBlank() || selectedSource.size() < 2
                || selectedSource.size() - 1 > MAX_SOURCE_SEGMENTS
                || !Double.isFinite(virtualRasterScale) || virtualRasterScale <= 0.0
                || parameters.zoom() != zoom) {
            throw new IllegalArgumentException("Managed source search cannot be certified");
        }
        var shape = TileHeatmapSampler.legacySamplingShape(
                PolylineMath.length(selectedSource), selectedSource.size(), parameters);
        double radius = Math.max(1, (int) Math.round(shape.referenceHalfWidthPx()
                * TileHeatmapSampler.REFERENCE_RASTER_SCALE)) / virtualRasterScale;
        if (!Double.isFinite(radius) || radius <= 0.0) {
            throw new IllegalArgumentException("Managed original search radius is invalid");
        }
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var owner = parent.child("managed-source-request");
        boolean complete = false;
        try {
            long bytes = Math.addExact(4096L, Math.multiplyExact((long) selectedSource.size(), 1536L));
            var reservation = owner.reserve(bytes);
            List<EastNorth> original = List.copyOf(selectedSource);
            List<Point2D.Double> world = new ArrayList<>(original.size());
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            double worldSize = TileHeatmapSampler.TILE_SIZE * Math.scalb(1.0, zoom);
            for (EastNorth point : original) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                Point2D.Double projected = worldProjection.apply(point);
                if (projected == null || !Double.isFinite(projected.x) || !Double.isFinite(projected.y)
                        || projected.x < 0 || projected.x > worldSize
                        || projected.y < 0 || projected.y > worldSize) {
                    throw new IllegalArgumentException("Original managed source leaves Web Mercator world");
                }
                world.add(new Point2D.Double(projected.x, projected.y));
                minX = Math.min(minX, projected.x); maxX = Math.max(maxX, projected.x);
                minY = Math.min(minY, projected.y); maxY = Math.max(maxY, projected.y);
            }
            List<List<MetricPoint>> quads = new ArrayList<>(world.size() - 1);
            for (int i = 1; i < world.size(); i++) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                Point2D.Double a = world.get(i - 1), b = world.get(i);
                double dx = b.x - a.x, dy = b.y - a.y;
                double length = Math.hypot(dx, dy);
                if (!Double.isFinite(length) || length <= 0.0) {
                    throw new IllegalArgumentException("Original managed source has an unresolved zero-length segment");
                }
                double nx = -dy / length * radius, ny = dx / length * radius;
                quads.add(List.of(new MetricPoint(a.x + nx, a.y + ny),
                        new MetricPoint(b.x + nx, b.y + ny),
                        new MetricPoint(b.x - nx, b.y - ny),
                        new MetricPoint(a.x - nx, a.y - ny)));
            }
            double margin = Math.max(64.0, parameters.halfWidthPx() * 3.0);
            int left = Math.toIntExact((long) Math.floor((minX - margin) / TileHeatmapSampler.TILE_SIZE));
            int right = Math.toIntExact((long) Math.floor((maxX + margin) / TileHeatmapSampler.TILE_SIZE));
            int top = Math.toIntExact((long) Math.floor((minY - margin) / TileHeatmapSampler.TILE_SIZE));
            int bottom = Math.toIntExact((long) Math.floor((maxY + margin) / TileHeatmapSampler.TILE_SIZE));
            var receipt = new ManagedSourceRequestReceipt(projection, original, selectedColor,
                    zoom, left, right, top, bottom, radius, virtualRasterScale, parameters,
                    new MetricRegion(quads), owner);
            reservation.adopt(receipt);
            complete = true;
            return receipt;
        } finally {
            if (!complete) owner.close();
        }
    }

    void requireMatches(TileHeatmapSampler.TileMosaic mosaic) {
        if (owner.isClosed() || mosaic == null || !selectedColor.equals(mosaic.color())
                || zoom != mosaic.zoom() || mosaic.parameters() != parameters
                || Double.doubleToLongBits(mosaic.virtualRasterScale())
                    != Double.doubleToLongBits(virtualRasterScale)
                || mosaic.originWorldPxX() != minTileX * (double) TileHeatmapSampler.TILE_SIZE
                || mosaic.originWorldPxY() != minTileY * (double) TileHeatmapSampler.TILE_SIZE
                || mosaic.image().getWidth() != (maxTileX - minTileX + 1) * TileHeatmapSampler.TILE_SIZE
                || mosaic.image().getHeight() != (maxTileY - minTileY + 1) * TileHeatmapSampler.TILE_SIZE) {
            throw new IllegalArgumentException("Prepared managed mosaic differs from original source request");
        }
    }

    public boolean authorizesVirtualRasterSegment(TileHeatmapSampler.TileMosaic mosaic,
            RasterPoint start, RasterPoint end, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        requireMatches(mosaic);
        Objects.requireNonNull(start, "raster start");
        Objects.requireNonNull(end, "raster end");
        Objects.requireNonNull(budget, "shared work budget");
        Objects.requireNonNull(scratchOwner, "scratch owner");
        budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, 1);
        MetricPoint a = toWorld(mosaic, start), b = toWorld(mosaic, end);
        return originalWorldPixelDecisionRegion.containsSegmentWithBoundedEvents(a, b,
                () -> { budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, 1); return false; },
                units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units), scratchOwner);
    }

    private static MetricPoint toWorld(TileHeatmapSampler.TileMosaic mosaic, RasterPoint point) {
        double scale = mosaic.virtualRasterScale();
        return new MetricPoint(mosaic.originWorldPxX() + point.x() / scale + 0.5,
                mosaic.originWorldPxY() + point.y() / scale + 0.5);
    }

    public Projection capturedProjection() { return projection; }
    public List<EastNorth> originalSource() { return originalSource; }
    public String selectedColor() { return selectedColor; }
    public int zoom() { return zoom; }
    public double nativeSearchHalfWidth() { return nativeSearchHalfWidth; }
    public int minTileX() { return minTileX; }
    public int maxTileX() { return maxTileX; }
    public int minTileY() { return minTileY; }
    public int maxTileY() { return maxTileY; }
    public MetricRegion originalWorldPixelDecisionRegion() { return originalWorldPixelDecisionRegion; }
    void close() { owner.close(); }
}
