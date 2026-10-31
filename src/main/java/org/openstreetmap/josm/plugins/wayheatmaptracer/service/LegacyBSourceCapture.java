package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyTopologyPlanner.Occurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Captures only the numerical settings and actual source pixels consumed by sequential Hybrid. */
public final class LegacyBSourceCapture {
    private LegacyBSourceCapture() { }

    /** Exact source coordinates and native Legacy fixed/topology policy, detached on the EDT. */
    public record Geometry(List<ProjectedPoint> originalProjected, List<Occurrence> occurrences,
            boolean simplify, double projectedTolerance) {
        public Geometry {
            originalProjected = List.copyOf(originalProjected);
            occurrences = List.copyOf(occurrences);
            if (originalProjected.size() < 2 || originalProjected.size() != occurrences.size()
                    || !Double.isFinite(projectedTolerance) || projectedTolerance < 0) {
                throw new IllegalArgumentException("Complete Legacy source policy is required");
            }
            for (int index = 0; index < occurrences.size(); index++) {
                if (occurrences.get(index).sourceIndex() != index) {
                    throw new IllegalArgumentException("Legacy source policy must retain occurrence order");
                }
            }
        }
    }

    /** Mirrors the standalone precise command's source-occurrence policy before worker dispatch. */
    public static Geometry geometry(SelectionContext selection, boolean simplify, double projectedTolerance) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Legacy source policy must be captured on the EDT");
        }
        Objects.requireNonNull(selection);
        var projected = new java.util.ArrayList<ProjectedPoint>();
        var occurrences = new java.util.ArrayList<Occurrence>();
        int last = selection.segmentNodes().size() - 1;
        for (int index = 0; index <= last; index++) {
            var node = selection.segmentNodes().get(index);
            var coordinate = node.getEastNorth();
            if (coordinate == null) { throw new IllegalArgumentException("Legacy source coordinate is unavailable"); }
            projected.add(new ProjectedPoint(coordinate.east(), coordinate.north()));
            occurrences.add(new Occurrence(index, selection.fixedNodes().contains(node),
                    index == 0 || index == last || node.hasKeys()
                            || node.getReferrers().stream().anyMatch(referrer -> referrer != selection.way())));
        }
        return new Geometry(projected, occurrences, simplify, projectedTolerance);
    }

    /** Credential-free slide-time choices; source-specific clamps remain those of native Legacy. */
    public record Settings(int visibleHalfWidthPx, int visibleStepPx, InferenceMode inferenceMode,
            int inferenceZoom, int validationZoom, double searchHalfWidthMeters,
            double sampleStepMeters, IntensitySamplingMode intensityMode) {
        public Settings {
            Objects.requireNonNull(inferenceMode);
            Objects.requireNonNull(intensityMode);
            if (visibleHalfWidthPx <= 0 || visibleStepPx <= 0
                    || inferenceZoom < 10 || inferenceZoom > 16
                    || validationZoom < 10 || validationZoom > 16
                    || !Double.isFinite(searchHalfWidthMeters) || searchHalfWidthMeters <= 0
                    || !Double.isFinite(sampleStepMeters) || sampleStepMeters <= 0) {
                throw new IllegalArgumentException("Legacy sampling settings are invalid");
            }
        }

        /** Copy at the configuration boundary; the worker never retains credentials. */
        public static Settings capture(ManagedHeatmapConfig config) {
            Objects.requireNonNull(config);
            return new Settings(config.crossSectionHalfWidthPx(), config.crossSectionStepPx(),
                    config.inferenceMode() == null ? InferenceMode.STABLE_FIXED_SCALE : config.inferenceMode(),
                    TileHeatmapSampler.effectiveInferenceZoom(config),
                    Math.max(10, Math.min(16, config.validationZoom())),
                    Math.max(2, config.searchHalfWidthMeters()), Math.max(.5, config.sampleStepMeters()),
                    config.intensitySamplingMode() == null ? IntensitySamplingMode.COLOR_MAPPING
                            : config.intensitySamplingMode());
        }
    }

    /** Freezes the exact original visible anchors, with native rendered-layer coordinate phase. */
    public static LegacyVisibleProfileSampler.Input visible(Settings settings,
            LiveBPreviewService.VisibleRaster raster, List<ProjectedPoint> source, String detector) {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(raster);
        requireDetector(settings, detector);
        if (source == null || source.size() < 2) {
            throw new IllegalArgumentException("Original projected source is required");
        }
        var projection = Projections.getProjectionByCode(raster.projectionCode());
        if (projection == null) { throw new IllegalArgumentException("Captured projection is unavailable"); }
        var projected = source.stream().map(point -> new EastNorth(point.east(), point.north())).toList();
        var pixels = source.stream().map(point -> new Point2D.Double(
                (point.east() - raster.minimumEast()) / raster.projectionUnitsPerViewPixel()
                        * RenderedHeatmapSampler.RASTER_SCALE,
                (raster.maximumNorth() - point.north()) / raster.projectionUnitsPerViewPixel()
                        * RenderedHeatmapSampler.RASTER_SCALE)).toList();
        var anchors = ProfileSamplingAnchor.pair(projected, pixels, projection).stream().map(anchor ->
                new LegacyVisibleProfileSampler.Anchor(new ProjectedPoint(
                        anchor.sourceMapCoordinate().east(), anchor.sourceMapCoordinate().north()),
                        new RasterPoint(anchor.rasterX(), anchor.rasterY()),
                        anchor.cumulativeGroundDistanceMeters())).toList();
        int halfWidth = Math.max(6, Math.min(120, settings.visibleHalfWidthPx()));
        int step = Math.min(halfWidth, Math.max(1, Math.min(32, settings.visibleStepPx())));
        return new LegacyVisibleProfileSampler.Input(raster, anchors, List.of(detector),
                new LegacyVisibleProfileSampler.Options(halfWidth, step,
                        RenderedHeatmapSampler.RASTER_SCALE, settings.intensityMode()));
    }

    private static void requireDetector(Settings settings, String detector) {
        if (detector == null || detector.isBlank() || !settings.intensityMode().usesColorMapping()
                && !detector.equals(settings.intensityMode().detectorName())) {
            throw new IllegalArgumentException("Detector must match its factual intensity source");
        }
    }

    /**
     * Prepares native Legacy sampling from the actual acquired inference rasters, with no extra
     * network requests. Runtime generation and aggregate-receipt validation remain the live owner's
     * responsibility; this numerical freeze grants no acquisition or edit authority.
     */
    public static LegacyManagedProfileSampler.Input managed(Settings settings,
            ManagedModernPreviewSource.SourceRasters sources, List<ProjectedPoint> source,
            String projectionCode, String detector) {
        try (var capture = new ManagedCapture(settings, sources, source, projectionCode,
                CancellationProbe.NONE, null, false)) {
            return capture.input(detector);
        }
    }

    /** Attempt-local native managed-source mosaics shared by all detector inputs in one frame. */
    public static final class ManagedCapture implements AutoCloseable {
        private static final long MOSAIC_OBJECT_FIELD_BYTES = 80;
        private final Settings settings;
        private final ManagedModernPreviewSource.SourceRasters sources;
        private final List<ProjectedPoint> source;
        private final String projectionCode;
        private final CancellationProbe cancellation;
        private final org.openstreetmap.josm.data.projection.Projection projection;
        private final TileHeatmapSampler.SamplingParameters inference;
        private final TileHeatmapSampler.SamplingParameters validation;
        private final AttemptMemoryLedger.Owner poolOwner;
        private final Map<String, LegacyManagedProfileSampler.Mosaic> mosaics = new LinkedHashMap<>();
        private boolean closed;

        public ManagedCapture(Settings settings, ManagedModernPreviewSource.SourceRasters sources,
                List<ProjectedPoint> source, String projectionCode, CancellationProbe cancellation,
                AttemptMemoryLedger.Owner attemptOwner) {
            this(settings, sources, source, projectionCode, cancellation,
                    Objects.requireNonNull(attemptOwner, "attemptOwner"), true);
        }

        private ManagedCapture(Settings settings, ManagedModernPreviewSource.SourceRasters sources,
                List<ProjectedPoint> source, String projectionCode, CancellationProbe cancellation,
                AttemptMemoryLedger.Owner attemptOwnerOrNull, boolean allocateOwner) {
            this.settings = Objects.requireNonNull(settings);
            this.sources = Objects.requireNonNull(sources);
            if (source == null || source.size() < 2 || projectionCode == null || projectionCode.isBlank()) {
                throw new IllegalArgumentException("Captured managed sampling frame is unavailable");
            }
            this.source = List.copyOf(source);
            this.projectionCode = projectionCode;
            this.cancellation = Objects.requireNonNull(cancellation);
            projection = Projections.getProjectionByCode(projectionCode);
            if (projection == null || sources.selectedRaster().zoom() != settings.inferenceZoom()) {
                throw new IllegalArgumentException("Captured managed sampling frame is unavailable");
            }
            double sumLatitude = 0;
            for (var point : this.source) {
                sumLatitude += projection.eastNorth2latlon(new EastNorth(point.east(), point.north())).lat();
            }
            double latitude = sumLatitude / this.source.size();
            inference = TileHeatmapSampler.legacySamplingParameters(settings.inferenceZoom(), latitude,
                    settings.searchHalfWidthMeters(), settings.sampleStepMeters());
            int validationZoom = Math.min(settings.inferenceZoom(), settings.validationZoom());
            validation = TileHeatmapSampler.legacySamplingParameters(validationZoom, latitude,
                    settings.searchHalfWidthMeters(), settings.sampleStepMeters());
            poolOwner = allocateOwner ? attemptOwnerOrNull.child("legacy-managed-capture") : null;
        }

        /** Builds one factual detector input while retaining one immutable mosaic per palette. */
        public synchronized LegacyManagedProfileSampler.Input input(String detector) {
            ensureOpen();
            requireDetector(settings, detector);
            boolean aggregate = "all-colors-combined".equals(detector);
            if (aggregate && sources.aggregateAvailability()
                    != ManagedModernPreviewSource.AggregateAvailability.COMPLETE) {
                throw new IllegalArgumentException("Complete acquired aggregate is required");
            }
            cancellation.checkpoint();
            var newlyBuilt = new ArrayList<String>();
            try {
                var inputMosaics = new LinkedHashMap<String, LegacyManagedProfileSampler.Mosaic>();
                // Preserve acquired order: it defines the aggregate's native reference palette.
                for (var entry : sources.palettes().entrySet()) {
                    if (!aggregate && !entry.getKey().equals(sources.plan().selectedColor())) { continue; }
                    String key = entry.getKey() + "@" + entry.getValue().zoom();
                    var mosaic = mosaics.get(key);
                    if (mosaic == null) {
                        newlyBuilt.add(key);
                        mosaic = captureMosaic(entry.getKey(), entry.getValue());
                        mosaics.put(key, mosaic);
                    }
                    inputMosaics.put(key, mosaic);
                }
                int validationZoom = Math.min(settings.inferenceZoom(), settings.validationZoom());
                return new LegacyManagedProfileSampler.Input(settings.inferenceMode(), settings.inferenceZoom(),
                        settings.validationZoom(), settings.inferenceZoom(), validationZoom, TileHeatmapSampler.TILE_SIZE,
                        inputMosaics, inference, validation, source, projectionCode, sources.plan().selectedColor(),
                        List.of(detector), settings.intensityMode(), RenderedHeatmapSampler.RASTER_SCALE);
            } catch (RuntimeException | Error failure) {
                for (int index = newlyBuilt.size() - 1; index >= 0; index--) {
                    String key = newlyBuilt.get(index);
                    var mosaic = mosaics.remove(key);
                    if (poolOwner != null && mosaic != null) {
                        // Release only this input's new mosaics; earlier complete inputs remain usable.
                        poolOwner.retainIfAbsent(mosaic).close();
                    }
                }
                throw failure;
            }
        }

        private LegacyManagedProfileSampler.Mosaic captureMosaic(String color, ManagedModernPreviewSource.Raster raster) {
            cancellation.checkpoint();
            int width = raster.image().getWidth();
            int height = raster.image().getHeight();
            int pixels = Math.multiplyExact(width, height);
            var retained = reserve(poolOwner, Math.addExact(AttemptMemoryLedger.arrayBytes(pixels, Integer.BYTES),
                    AttemptMemoryLedger.objectBytes(MOSAIC_OBJECT_FIELD_BYTES)));
            try (retained) {
                var supportReservation = reserve(poolOwner, AttemptMemoryLedger.arrayBytes(pixels, 1));
                try (supportReservation) {
                    boolean[] support = raster.validity();
                    for (int index = 0; index < support.length; index++) {
                        if ((index & 0x3fff) == 0) { cancellation.checkpoint(); }
                        if (!support[index]) { throw new IllegalArgumentException("Managed Legacy raster support is incomplete"); }
                    }
                }
                double nativePixelsPerWorldPixel = TileHeatmapSampler.TILE_SIZE / 256.0;
                if (!(raster.transform() instanceof SupportedInputRasterTransform.WebMercator transform)
                        || transform.rasterPixelsPerWorldPixel() != nativePixelsPerWorldPixel
                        || transform.zoom() != settings.inferenceZoom()) {
                    throw new IllegalArgumentException("Managed Legacy requires its exact native world-pixel frame");
                }
                cancellation.checkpoint();
                boolean stable = settings.inferenceMode().stableFixedScale();
                int dilation = stable ? TileHeatmapSampler.stableInferenceDilationRadius(raster.zoom()) : 0;
                var workingReservation = dilation > 0
                        ? reserve(poolOwner, stableInferenceWorkingBytes(pixels)) : null;
                try (workingReservation) {
                    BufferedImage image = stable
                            ? TileHeatmapSampler.stableInferenceImage(raster.image(), color, raster.zoom())
                            : raster.image();
                    cancellation.checkpoint();
                    var pixelsReservation = reserve(poolOwner, AttemptMemoryLedger.arrayBytes(pixels, Integer.BYTES));
                    try (pixelsReservation) {
                        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
                        cancellation.checkpoint();
                        double virtualScale = inference.metersPerPixel()
                                / TileHeatmapSampler.REFERENCE_VIEW_METERS_PER_PIXEL
                                * RenderedHeatmapSampler.RASTER_SCALE;
                        var mosaic = new LegacyManagedProfileSampler.Mosaic(color, raster.zoom(),
                                transform.inputWorldPixelBoundaryX() * nativePixelsPerWorldPixel,
                                transform.inputWorldPixelBoundaryY() * nativePixelsPerWorldPixel, width, height,
                                argb, inference, stable, virtualScale);
                        if (retained != null) { retained.adopt(mosaic); }
                        return mosaic;
                    }
                }
            }
        }

        private static AttemptMemoryLedger.Reservation reserve(AttemptMemoryLedger.Owner owner, long bytes) {
            return owner == null ? null : owner.reserve(bytes);
        }

        /** Three pixel arrays plus conservative BufferedImage/Raster/DataBuffer/sample-model metadata. */
        private static long stableInferenceWorkingBytes(int pixels) {
            return Math.addExact(
                    Math.addExact(AttemptMemoryLedger.arrayBytes(pixels, Integer.BYTES),
                            AttemptMemoryLedger.arrayBytes(pixels, Double.BYTES)),
                    Math.addExact(AttemptMemoryLedger.arrayBytes(pixels, Integer.BYTES),
                            Math.addExact(AttemptMemoryLedger.objectBytes(96) * 8,
                                    AttemptMemoryLedger.referenceArrayBytes(2))));
        }

        private void ensureOpen() {
            if (closed || poolOwner != null && poolOwner.isClosed()) {
                closed = true;
                mosaics.clear();
                throw new IllegalStateException("Managed capture is closed");
            }
        }

        @Override public synchronized void close() {
            if (closed) { return; }
            closed = true;
            mosaics.clear();
            if (poolOwner != null) { poolOwner.close(); }
        }
    }
}
