package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

/** Freezes prepared managed source mosaics for detached production Legacy sampling and projection. */
public final class LegacyManagedProfileSampler {
    private static final List<String> AGGREGATE_COLORS = List.of("hot", "blue", "bluered", "purple", "gray");
    private static final int MAX_MOSAIC_PIXELS = 64 * 1024 * 1024;

    /** Immutable used-mosaic raster and sampling identity; acquisition history is intentionally absent. */
    public record Mosaic(String color, int zoom, double originWorldPxX, double originWorldPxY,
            int width, int height, int[] argb, TileHeatmapSampler.SamplingParameters parameters,
            boolean stableInferenceRaster, double virtualRasterScale) {
        public Mosaic {
            if (color == null || color.isBlank() || zoom < 0 || zoom > 30
                    || !Double.isFinite(originWorldPxX) || !Double.isFinite(originWorldPxY)
                    || width <= 0 || height <= 0 || (long) width * height > MAX_MOSAIC_PIXELS
                    || argb == null || argb.length != (long) width * height
                    || !Double.isFinite(virtualRasterScale) || virtualRasterScale <= 0.0
                    || parameters == null || parameters.zoom() != zoom
                    || !Double.isFinite(parameters.latitude()) || Math.abs(parameters.latitude()) > 85.05112878
                    || !positive(parameters.metersPerPixel()) || !positive(parameters.halfWidthMeters())
                    || !positive(parameters.sampleStepMeters()) || parameters.halfWidthPx() <= 0
                    || parameters.stepPx() <= 0 || parameters.stepPx() > parameters.halfWidthPx()) {
                throw new IllegalArgumentException("Frozen managed mosaic metadata is invalid");
            }
            argb = argb.clone();
        }

        @Override public int[] argb() { return argb.clone(); }

        String key() { return color + "@" + zoom; }
    }

    /** Detached prepared source evidence, with no tile history, configuration object, or live JOSM state. */
    public record Input(InferenceMode inferenceMode, int requestedInferenceZoom, int requestedValidationZoom,
            int inferenceZoom, int validationZoom, int tileSize, Map<String, Mosaic> mosaics,
            TileHeatmapSampler.SamplingParameters inferenceParameters,
            TileHeatmapSampler.SamplingParameters validationParameters,
            List<ProjectedPoint> sourcePolyline, String projectionCode, String selectedPalette,
            List<String> detectors, IntensitySamplingMode intensityMode,
            double trackerNormalizationRasterPx) {
        public Input {
            Objects.requireNonNull(inferenceMode, "inferenceMode");
            Objects.requireNonNull(inferenceParameters, "inferenceParameters");
            Objects.requireNonNull(validationParameters, "validationParameters");
            Objects.requireNonNull(intensityMode, "intensityMode");
            if (!validZoom(requestedInferenceZoom) || !validZoom(requestedValidationZoom)
                    || !validZoom(inferenceZoom) || !validZoom(validationZoom)
                    || tileSize <= 0 || mosaics == null || mosaics.isEmpty()
                    || inferenceParameters.zoom() != inferenceZoom
                    || validationParameters.zoom() != validationZoom
                    || sourcePolyline == null || sourcePolyline.size() < 2
                    || projectionCode == null || projectionCode.isBlank()
                    || selectedPalette == null || selectedPalette.isBlank()
                    || detectors == null || detectors.isEmpty()
                    || !positive(trackerNormalizationRasterPx)) {
                throw new IllegalArgumentException("Frozen managed Legacy input is incomplete");
            }
            var copiedMosaics = new LinkedHashMap<String, Mosaic>();
            mosaics.forEach((key, mosaic) -> {
                if (key == null || mosaic == null || !key.equals(mosaic.key())
                        || copiedMosaics.putIfAbsent(key, mosaic) != null) {
                    throw new IllegalArgumentException("Frozen managed mosaic keys must match their identities");
                }
            });
            mosaics = Collections.unmodifiableMap(copiedMosaics);
            sourcePolyline = List.copyOf(sourcePolyline);
            detectors = List.copyOf(detectors);
            if (sourcePolyline.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Frozen source geometry is incomplete");
            }
            var detectorIds = new HashSet<String>();
            for (String detector : detectors) {
                if (detector == null || detector.isBlank() || !detectorIds.add(detector)) {
                    throw new IllegalArgumentException("Detector ids must be nonblank and unique");
                }
            }
            if (!mosaics.containsKey(selectedPalette + "@" + inferenceZoom)) {
                throw new IllegalArgumentException("Selected-palette inference mosaic is missing");
            }
            for (var entry : mosaics.entrySet()) {
                if (entry.getValue().width() <= 0 || entry.getValue().height() <= 0) {
                    throw new IllegalArgumentException("Frozen mosaic dimensions must be positive");
                }
            }
        }
    }

    /** Profiles for one detector and the actual numerical mosaic defining its sampling frame. */
    public record DetectorProfiles(String detector, String referenceMosaicKey,
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        public DetectorProfiles {
            if (detector == null || detector.isBlank() || referenceMosaicKey == null
                    || referenceMosaicKey.isBlank() || profiles == null) {
                throw new IllegalArgumentException("Managed detector result identity is incomplete");
            }
            profiles = List.copyOf(profiles);
        }
    }

    private final TileHeatmapSampler tileSampler;

    public LegacyManagedProfileSampler() { this(TileHeatmapSampler.numericalOnly()); }

    /** Plans native counts without constructing source images or profile/tracker arrays. */
    public static TileHeatmapSampler.LegacySamplingShape samplingShape(Input input) {
        Objects.requireNonNull(input);
        if (input.detectors().size() != 1) {
            throw new IllegalArgumentException("One detector is required for native sampling admission");
        }
        String referenceKey;
        if ("all-colors-combined".equals(input.detectors().get(0))) {
            requireAggregateMode(input);
            referenceKey = aggregateReferenceKey(input);
        } else {
            referenceKey = input.selectedPalette() + "@" + input.inferenceZoom();
        }
        var reference = input.mosaics().get(referenceKey);
        double projectedLength = 0;
        for (int index = 1; index < input.sourcePolyline().size(); index++) {
            var first = input.sourcePolyline().get(index - 1);
            var second = input.sourcePolyline().get(index);
            projectedLength += Point2D.distance(first.east(), first.north(), second.east(), second.north());
        }
        return TileHeatmapSampler.legacySamplingShape(projectedLength, input.sourcePolyline().size(), reference.parameters());
    }

    LegacyManagedProfileSampler(TileHeatmapSampler tileSampler) {
        this.tileSampler = Objects.requireNonNull(tileSampler, "tileSampler");
    }

    /** Copies only the prepared mosaics and metadata used by production sampling. */
    public static Input freeze(TileHeatmapSampler.TileMosaicSet prepared,
            List<ProjectedPoint> sourcePolyline, String projectionCode, String selectedPalette,
            List<String> detectors, IntensitySamplingMode intensityMode,
            double trackerNormalizationRasterPx) {
        Objects.requireNonNull(prepared, "prepared");
        var frozen = new LinkedHashMap<String, Mosaic>();
        prepared.mosaics().forEach((key, mosaic) -> {
            int width = mosaic.image().getWidth();
            int height = mosaic.image().getHeight();
            int[] argb = mosaic.image().getRGB(0, 0, width, height, null, 0, width);
            frozen.put(key, new Mosaic(mosaic.color(), mosaic.zoom(), mosaic.originWorldPxX(),
                    mosaic.originWorldPxY(), width, height, argb, mosaic.parameters(),
                    mosaic.stableInferenceRaster(), mosaic.virtualRasterScale()));
        });
        return new Input(prepared.inferenceMode(), prepared.requestedInferenceZoom(),
                prepared.requestedValidationZoom(), prepared.inferenceZoom(), prepared.validationZoom(),
                prepared.tileSize(), frozen, prepared.inferenceParameters(), prepared.validationParameters(),
                sourcePolyline, projectionCode, selectedPalette, detectors, intensityMode,
                trackerNormalizationRasterPx);
    }

    /** Runs each requested detector in order against the frozen actual managed mosaics. */
    public List<DetectorProfiles> sample(Input input, CancellationProbe cancellation) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(cancellation, "cancellation");
        Projection projection = capturedProjection(input.projectionCode());
        TileHeatmapSampler.TileMosaicSet prepared = reconstruct(input);
        List<EastNorth> source = eastNorth(input.sourcePolyline());
        var results = new ArrayList<DetectorProfiles>(input.detectors().size());
        for (String detector : input.detectors()) {
            cancellation.checkpoint();
            if ("all-colors-combined".equals(detector)) {
                if (input.intensityMode() != IntensitySamplingMode.COLOR_MAPPING) {
                    throw new IllegalArgumentException("All-color aggregation requires native color mapping");
                }
                var profiles = tileSampler.sampleAggregatedProfiles(prepared, input.inferenceZoom(), source,
                        projection, cancellation);
                results.add(new DetectorProfiles(detector,
                        aggregateReferenceKey(input), profiles));
            } else {
                String referenceKey = input.selectedPalette() + "@" + input.inferenceZoom();
                var profiles = tileSampler.sampleProfiles(prepared.require(input.selectedPalette()), source,
                        detector, input.intensityMode(), projection, cancellation);
                results.add(new DetectorProfiles(detector, referenceKey, profiles));
            }
        }
        return List.copyOf(results);
    }

    /** Projects candidate points in the detector's exact frozen managed sampling frame. */
    public List<ProjectedPoint> project(Input input, String detector, List<RasterPoint> rasterPoints) {
        Objects.requireNonNull(input, "input");
        if (!input.detectors().contains(detector) || rasterPoints == null) {
            throw new IllegalArgumentException("Candidate detector or raster points are invalid");
        }
        Mosaic reference;
        if ("all-colors-combined".equals(detector)) {
            requireAggregateMode(input);
            reference = input.mosaics().get(aggregateReferenceKey(input));
        } else {
            reference = input.mosaics().get(input.selectedPalette() + "@" + input.inferenceZoom());
        }
        List<Point2D.Double> points = rasterPoints.stream().map(point -> {
            Objects.requireNonNull(point, "raster point");
            return new Point2D.Double(point.x(), point.y());
        }).toList();
        return tileSampler.projectCandidate(reference.zoom(), reference.originWorldPxX(), reference.originWorldPxY(),
                        reference.virtualRasterScale(), points, capturedProjection(input.projectionCode()))
                .stream().map(point -> new ProjectedPoint(point.east(), point.north())).toList();
    }

    private static TileHeatmapSampler.TileMosaicSet reconstruct(Input input) {
        var mosaics = new LinkedHashMap<String, TileHeatmapSampler.TileMosaic>();
        input.mosaics().forEach((key, frozen) -> {
            BufferedImage image = new BufferedImage(frozen.width(), frozen.height(), BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, frozen.width(), frozen.height(), frozen.argb(), 0, frozen.width());
            mosaics.put(key, new TileHeatmapSampler.TileMosaic(frozen.color(), frozen.zoom(),
                    frozen.originWorldPxX(), frozen.originWorldPxY(), image, List.of(), Map.of(),
                    frozen.parameters(), frozen.stableInferenceRaster(), frozen.virtualRasterScale()));
        });
        return new TileHeatmapSampler.TileMosaicSet(input.inferenceMode(), input.requestedInferenceZoom(),
                input.requestedValidationZoom(), input.inferenceZoom(), input.validationZoom(), input.tileSize(),
                mosaics, input.inferenceParameters(), input.validationParameters());
    }

    private static List<EastNorth> eastNorth(List<ProjectedPoint> points) {
        return points.stream().map(point -> new EastNorth(point.east(), point.north())).toList();
    }

    private static Projection capturedProjection(String code) {
        Projection projection = Projections.getProjectionByCode(code);
        if (projection == null) throw new IllegalArgumentException("Captured projection is unavailable");
        return projection;
    }

    private static String aggregateReferenceKey(Input input) {
        return aggregateReferenceColor(input) + "@" + input.inferenceZoom();
    }

    private static String aggregateReferenceColor(Input input) {
        for (String color : input.mosaics().values().stream()
                .filter(mosaic -> mosaic.zoom() == input.inferenceZoom())
                .map(Mosaic::color).toList()) {
            if (AGGREGATE_COLORS.contains(color)) return color;
        }
        throw new IllegalStateException("All-color aggregation has no matching base source mosaic");
    }

    private static void requireAggregateMode(Input input) {
        if (input.intensityMode() != IntensitySamplingMode.COLOR_MAPPING) {
            throw new IllegalArgumentException("All-color aggregation requires native color mapping");
        }
    }

    private static boolean validZoom(int zoom) { return zoom >= 0 && zoom <= 30; }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0.0; }
}
