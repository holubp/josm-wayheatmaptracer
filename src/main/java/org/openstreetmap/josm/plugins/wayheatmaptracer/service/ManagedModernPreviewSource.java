package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRasterGrid;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CancellationToken;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileAddress;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileCachePolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TilePurpose;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileRequest;

/** Builds one detached selected-palette native managed raster through the shared coordinator. */
public final class ManagedModernPreviewSource {
    static final int TILE_SIZE = 512;
    private static final long MAX_INPUT_PIXELS = 16_777_216L;
    private final TileFetchCoordinator coordinator;

    public record Request(List<GeographicPoint> source, String activity, String palette, int zoom,
            ManagedTileGeneration generation, double searchRadiusMeters, double sampleStepMeters,
            String sourceIdentity) {
        public Request {
            source = List.copyOf(source);
            if (source.size() < 2 || activity == null || activity.isBlank() || palette == null
                    || palette.isBlank() || zoom < 0 || zoom > 22 || generation == null
                    || !Double.isFinite(searchRadiusMeters) || searchRadiusMeters <= 0.0
                    || !Double.isFinite(sampleStepMeters) || sampleStepMeters <= 0.0
                    || sourceIdentity == null || sourceIdentity.isBlank()) {
                throw new IllegalArgumentException("Managed preview request is incomplete");
            }
        }
    }

    public record Raster(BufferedImage image, boolean[] validity,
            SupportedInputRasterTransform transform, String palette, int zoom,
            String sourceIdentity) {
        public Raster {
            if (image == null || validity == null || validity.length != image.getWidth() * image.getHeight()
                    || transform == null || palette == null || palette.isBlank() || zoom < 0
                    || sourceIdentity == null || sourceIdentity.isBlank()) {
                throw new IllegalArgumentException("Managed preview raster is incomplete");
            }
            validity = validity.clone();
        }
        @Override public boolean[] validity() { return validity.clone(); }
    }

    /**
     * Creates the selected-source-only acquisition request, rejecting unsupported source plans
     * before the coordinator is touched.
     */
    public static Request selectedOnly(List<GeographicPoint> source, ManagedHeatmapConfig config,
            String sourceIdentity) {
        Objects.requireNonNull(config, "config");
        AlignmentTileSourcePlan plan = AlignmentTileSourcePlan.from(config);
        if (plan.aggregateDetectorRequested() || config.multiColorDetection()) {
            throw new IllegalArgumentException(
                    "Managed modern preview supports the selected palette only");
        }
        return new Request(source, config.activity(), plan.selectedColor(), config.inferenceZoom(),
                new ManagedTileGeneration(Math.max(0L, config.cacheBuster())),
                config.searchHalfWidthMeters(), config.sampleStepMeters(), sourceIdentity);
    }

    public ManagedModernPreviewSource(TileFetchCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Acquires only the selected native palette off the EDT. */
    public Raster acquire(Request request, CredentialSnapshot credentials,
            CancellationProbe cancellation) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Managed preview acquisition must execute off the EDT");
        }
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.checkpoint();
        requireActiveGeneration(request);
        TileBounds bounds = tileBounds(request);
        int width = Math.multiplyExact(bounds.width(), TILE_SIZE);
        int height = Math.multiplyExact(bounds.height(), TILE_SIZE);
        if ((long) width * height > MAX_INPUT_PIXELS) {
            throw new IllegalArgumentException("Managed preview capture exceeds the input-pixel cap");
        }
        BufferedImage mosaic = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        boolean[] valid = new boolean[width * height];
        Graphics2D graphics = mosaic.createGraphics();
        try {
            for (int x = bounds.minimumX(); x <= bounds.maximumX(); x++) {
                for (int y = bounds.minimumY(); y <= bounds.maximumY(); y++) {
                    cancellation.checkpoint();
                    CancellationToken requestCancellation = new CancellationToken();
                    TileRequest tile = new TileRequest(new ManagedTileAddress(request.activity(),
                            request.palette(), request.zoom(), x, y), request.generation(),
                            TilePurpose.ALIGNMENT_REQUIRED, TileCachePolicy.USE_CACHE,
                            Instant.now().plusSeconds(30), requestCancellation);
                    TileFetchResult result;
                    try (CancellationProbe.Registration ignored =
                            cancellation.onCancellation(requestCancellation::cancel)) {
                        result = coordinator.fetch(tile, credentials).toCompletableFuture().join();
                    }
                    cancellation.checkpoint();
                    if (result.status() == org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus.STALE_GENERATION) {
                        throw new IllegalStateException("Managed preview settings changed during acquisition");
                    }
                    int left = (x - bounds.minimumX()) * TILE_SIZE;
                    int top = (y - bounds.minimumY()) * TILE_SIZE;
                    if (result.usable()) {
                        graphics.drawImage(result.image(), left, top, null);
                        for (int row = top; row < top + TILE_SIZE; row++) {
                            java.util.Arrays.fill(valid, row * width + left,
                                    row * width + left + TILE_SIZE, true);
                        }
                    }
                }
            }
        } finally {
            graphics.dispose();
        }
        requireActiveGeneration(request);
        double originX = bounds.minimumX() * (double) TILE_SIZE;
        double originY = bounds.minimumY() * (double) TILE_SIZE;
        return new Raster(mosaic, valid, SupportedInputRasterTransform.webMercator(request.zoom(),
                originX / 2.0, originY / 2.0, 2.0), request.palette(), request.zoom(),
                request.sourceIdentity());
    }

    private void requireActiveGeneration(Request request) {
        if (!coordinator.isActiveGeneration(request.generation())) {
            throw new IllegalStateException("Managed preview settings generation is stale");
        }
    }

    private static TileBounds tileBounds(Request request) {
        MetricRasterGrid grid = managedOutputGrid(request.source(), request.zoom(),
                request.searchRadiusMeters());
        SupportedInputRasterTransform world = SupportedInputRasterTransform.webMercator(
                request.zoom(), 0.0, 0.0, 2.0);
        SupportedInputRasterTransform.RasterBounds support = world.boundsForMetricCell(
                grid.coordinateFrame(), grid.footprint().polygons().get(0));
        int minimumPixelX = checkedFloor(support.minimumX());
        int minimumPixelY = checkedFloor(support.minimumY());
        int maximumPixelX = Math.addExact(checkedFloor(support.maximumX()), 1);
        int maximumPixelY = Math.addExact(checkedFloor(support.maximumY()), 1);
        int minimumX = Math.floorDiv(minimumPixelX, TILE_SIZE);
        int minimumY = Math.floorDiv(minimumPixelY, TILE_SIZE);
        int maximumX = Math.floorDiv(maximumPixelX, TILE_SIZE);
        int maximumY = Math.floorDiv(maximumPixelY, TILE_SIZE);
        int tileCount = 1 << request.zoom();
        if (minimumX < 0 || minimumY < 0 || maximumX >= tileCount || maximumY >= tileCount) {
            throw new IllegalArgumentException("Managed preview crosses an unsupported tile boundary");
        }
        return new TileBounds(minimumX, minimumY, maximumX, maximumY);
    }

    static MetricRasterGrid managedOutputGrid(List<GeographicPoint> source, int zoom, double radius) {
        LocalMetricFrame frame = managedFrame(source, zoom, radius);
        return managedOutputGrid(frame, source.stream().map(frame::toMetric).toList(), zoom, radius);
    }

    static MetricRasterGrid managedOutputGrid(LocalMetricFrame frame, List<MetricPoint> source,
            int zoom, double radius) {
        double pitch = TileHeatmapSampler.metersPerPixel(zoom,
                frame.origin().latitudeDegrees());
        MetricRegion decision = MetricCorridorRegion.aroundPolyline(source, radius);
        double minX = decision.polygons().stream().flatMap(List::stream)
                .mapToDouble(MetricPoint::xMeters).min().orElseThrow() - pitch;
        double maxX = decision.polygons().stream().flatMap(List::stream)
                .mapToDouble(MetricPoint::xMeters).max().orElseThrow() + pitch;
        double minY = decision.polygons().stream().flatMap(List::stream)
                .mapToDouble(MetricPoint::yMeters).min().orElseThrow() - pitch;
        double maxY = decision.polygons().stream().flatMap(List::stream)
                .mapToDouble(MetricPoint::yMeters).max().orElseThrow() + pitch;
        int width = Math.max(2, checkedCeil((maxX - minX) / pitch));
        int height = Math.max(2, checkedCeil((maxY - minY) / pitch));
        return new MetricRasterGrid(frame, new MetricPoint(minX + 0.5 * pitch,
                maxY - 0.5 * pitch), 1.0, 0.0, 0.0, -1.0, pitch, width, height);
    }

    static LocalMetricFrame managedFrame(List<GeographicPoint> source, int zoom, double radius) {
        if (source == null || source.size() < 2 || source.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Managed preview source is incomplete");
        }
        double minLat = source.stream().mapToDouble(GeographicPoint::latitudeDegrees).min().orElseThrow();
        double maxLat = source.stream().mapToDouble(GeographicPoint::latitudeDegrees).max().orElseThrow();
        double minLon = source.stream().mapToDouble(GeographicPoint::longitudeDegrees).min().orElseThrow();
        double maxLon = source.stream().mapToDouble(GeographicPoint::longitudeDegrees).max().orElseThrow();
        double representativeLatitude = source.get(source.size() / 2).latitudeDegrees();
        double pitch = TileHeatmapSampler.metersPerPixel(zoom, representativeLatitude);
        double latitudeMargin = Math.max(0.02,
                Math.toDegrees((radius + 3.0 * pitch) / 6_378_137.0));
        double longitudeMargin = Math.max(0.02, latitudeMargin
                / Math.max(0.01, Math.cos(Math.toRadians(representativeLatitude))));
        GeographicPoint southwest = new GeographicPoint(minLat - latitudeMargin,
                minLon - longitudeMargin);
        GeographicPoint northeast = new GeographicPoint(maxLat + latitudeMargin,
                maxLon + longitudeMargin);
        return LocalMetricFrame.certifiedEquirectangular(source.get(source.size() / 2),
                southwest, northeast);
    }

    private static int checkedFloor(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Managed preview footprint exceeds coordinate limits");
        }
        return (int) Math.floor(value);
    }

    private static int checkedCeil(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Managed preview output grid exceeds coordinate limits");
        }
        return (int) Math.ceil(value);
    }

    private record TileBounds(int minimumX, int minimumY, int maximumX, int maximumY) {
        int width() { return maximumX - minimumX + 1; }
        int height() { return maximumY - minimumY + 1; }
    }
}
