package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Original selected-source search footprint frozen before managed tile acquisition. */
public final class ManagedSourceRequestReceipt {
    static final int MAX_SOURCE_SEGMENTS = 4096;
    private final Projection projection;
    private final List<EastNorth> originalSource;
    private final String selectedColor;
    private final int zoom;
    private final int minTileX, maxTileX, minTileY, maxTileY;
    private final double nativeSearchHalfWidth;
    private final double virtualRasterScale;
    private final TileHeatmapSampler.SamplingParameters parameters;
    private final WorldPixelCapsuleRegion originalWorldPixelDecisionRegion;
    private final List<RasterPoint> originalWorldPoints;
    private final AttemptMemoryLedger.Owner owner;
    private final List<AttemptMemoryLedger.Owner> retainedOwners = new ArrayList<>();

    private ManagedSourceRequestReceipt(Projection projection, List<EastNorth> originalSource,
            String selectedColor, int zoom, int minTileX, int maxTileX, int minTileY,
            int maxTileY, double nativeSearchHalfWidth, double virtualRasterScale,
            TileHeatmapSampler.SamplingParameters parameters,
            WorldPixelCapsuleRegion originalWorldPixelDecisionRegion, List<RasterPoint> originalWorldPoints,
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
        this.originalWorldPoints = originalWorldPoints;
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
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, selectedSource.size());
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
            List<RasterPoint> sourceWorld = new ArrayList<>(world.size());
            for (Point2D.Double point : world) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                sourceWorld.add(new RasterPoint(point.x, point.y));
            }
            for (int i = 1; i < world.size(); i++) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                Point2D.Double a = world.get(i - 1), b = world.get(i);
                double dx = b.x - a.x, dy = b.y - a.y;
                double length = Math.hypot(dx, dy);
                if (!Double.isFinite(length) || length <= 0.0) {
                    throw new IllegalArgumentException("Original managed source has an unresolved zero-length segment");
                }
            }
            double margin = Math.max(64.0, parameters.halfWidthPx() * 3.0);
            int left = Math.toIntExact((long) Math.floor((minX - margin) / TileHeatmapSampler.TILE_SIZE));
            int right = Math.toIntExact((long) Math.floor((maxX + margin) / TileHeatmapSampler.TILE_SIZE));
            int top = Math.toIntExact((long) Math.floor((minY - margin) / TileHeatmapSampler.TILE_SIZE));
            int bottom = Math.toIntExact((long) Math.floor((maxY + margin) / TileHeatmapSampler.TILE_SIZE));
            var receipt = new ManagedSourceRequestReceipt(projection, original, selectedColor,
                    zoom, left, right, top, bottom, radius, virtualRasterScale, parameters,
                    new WorldPixelCapsuleRegion(sourceWorld, radius), List.copyOf(sourceWorld), owner);
            reservation.adopt(receipt);
            complete = true;
            return receipt;
        } catch (AttemptMemoryLedger.ResourceLimitException failure) {
            budget.failTerminally(failure);
            throw failure;
        } finally {
            if (!complete) owner.close();
        }
    }

    void requireMatches(TileHeatmapSampler.TileMosaic mosaic) {
        if (!isLive() || mosaic == null || !selectedColor.equals(mosaic.color())
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
        RasterPoint a = toWorld(mosaic, start), b = toWorld(mosaic, end);
        if (!insideRequestedTiles(a) || !insideRequestedTiles(b)) return false;
        return originalWorldPixelDecisionRegion.containsSegment(a, b, budget, scratchOwner);
    }

    private boolean insideRequestedTiles(RasterPoint point) {
        double tile = TileHeatmapSampler.TILE_SIZE;
        return point.x() >= minTileX * tile && point.x() <= (maxTileX + 1.0) * tile
                && point.y() >= minTileY * tile && point.y() <= (maxTileY + 1.0) * tile;
    }

    /** Sufficient curve-box authority in one original convex capsule; never whole-cell authority. */
    public boolean authorizesVirtualRasterBox(TileHeatmapSampler.TileMosaic mosaic,
            ManagedGroundTransform.LineBounds box, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        requireMatches(mosaic); budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        try (var scratch = scratchOwner.reserve(32_768)) {
            budget.charge(EmissionWorkBudget.Operation.REGION_QUERY,64);
            // Enclose the actual world-origin + virtual/scale + center-phase chain.
            double x0 = Math.nextDown(Math.nextDown(mosaic.originWorldPxX() + Math.nextDown(box.minimumX() / virtualRasterScale)) + .5);
            double x1 = Math.nextUp(Math.nextUp(mosaic.originWorldPxX() + Math.nextUp(box.maximumX() / virtualRasterScale)) + .5);
            double y0 = Math.nextDown(Math.nextDown(mosaic.originWorldPxY() + Math.nextDown(box.minimumY() / virtualRasterScale)) + .5);
            double y1 = Math.nextUp(Math.nextUp(mosaic.originWorldPxY() + Math.nextUp(box.maximumY() / virtualRasterScale)) + .5);
            var corners = List.of(new RasterPoint(x0,y0),new RasterPoint(x0,y1),new RasterPoint(x1,y0),new RasterPoint(x1,y1));
            for (var corner : corners) { budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, 1); if (!insideRequestedTiles(corner)) return false; }
            for (int i=1;i<originalWorldPoints.size();i++) {
                budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, 256);
                boolean all = true;
                for (var corner : corners) all &= exactCapsuleContains(originalWorldPoints.get(i-1), originalWorldPoints.get(i), corner, nativeSearchHalfWidth);
                if (all) return true;
            }
            return false;
        }
    }

    private static boolean exactCapsuleContains(RasterPoint a, RasterPoint b, RasterPoint p, double radius) {
        var ax=new java.math.BigDecimal(a.x()); var ay=new java.math.BigDecimal(a.y());
        var dx=new java.math.BigDecimal(b.x()).subtract(ax); var dy=new java.math.BigDecimal(b.y()).subtract(ay);
        var wx=new java.math.BigDecimal(p.x()).subtract(ax); var wy=new java.math.BigDecimal(p.y()).subtract(ay);
        var length=dx.multiply(dx).add(dy.multiply(dy)); var dot=wx.multiply(dx).add(wy.multiply(dy));
        var r=new java.math.BigDecimal(radius); var rr=r.multiply(r);
        if(dot.signum()<=0)return wx.multiply(wx).add(wy.multiply(wy)).compareTo(rr)<=0;
        if(dot.compareTo(length)>=0){wx=wx.subtract(dx);wy=wy.subtract(dy);return wx.multiply(wx).add(wy.multiply(wy)).compareTo(rr)<=0;}
        var cross=wx.multiply(dy).subtract(wy.multiply(dx));return cross.multiply(cross).compareTo(rr.multiply(length))<=0;
    }

    private static RasterPoint toWorld(TileHeatmapSampler.TileMosaic mosaic, RasterPoint point) {
        double scale = mosaic.virtualRasterScale();
        return new RasterPoint(mosaic.originWorldPxX() + point.x() / scale + 0.5,
                mosaic.originWorldPxY() + point.y() / scale + 0.5);
    }

    public Projection capturedProjection() { return projection; }
    public List<EastNorth> originalSource() { return originalSource; }
    /** Frozen EPSG:3857 world-pixel source vertices created before the first tile fetch. */
    public List<RasterPoint> originalWorldPoints() {
        if (!isLive()) throw new IllegalStateException("Original managed request is closed");
        return originalWorldPoints;
    }
    public String selectedColor() { return selectedColor; }
    public int zoom() { return zoom; }
    public double nativeSearchHalfWidth() { return nativeSearchHalfWidth; }
    public int minTileX() { return minTileX; }
    public int maxTileX() { return maxTileX; }
    public int minTileY() { return minTileY; }
    public int maxTileY() { return maxTileY; }
    boolean containsOriginalWorldPixel(RasterPoint point) {
        if (!isLive()) throw new IllegalStateException("Managed source request is closed");
        return originalWorldPixelDecisionRegion.contains(point);
    }
    boolean originalSearchWithin(double minX, double minY, double maxX, double maxY) {
        if (!isLive()) throw new IllegalStateException("Managed source request is closed");
        return originalWorldPixelDecisionRegion.withinRectangle(minX, minY, maxX, maxY);
    }
    void retainFor(AttemptMemoryLedger.Owner coOwner) {
        if (owner.isClosed()) throw new IllegalStateException("Original managed request is closed");
        coOwner.retain(this);
        synchronized (retainedOwners) { retainedOwners.add(coOwner); }
    }
    private boolean isLive() {
        if (!owner.isClosed()) return true;
        synchronized (retainedOwners) {
            return retainedOwners.stream().anyMatch(retainer -> !retainer.isClosed());
        }
    }
    void close() { owner.close(); }
}
