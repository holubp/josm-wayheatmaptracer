package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

/** Replays production visible-layer Legacy profile sampling from one frozen detached capture. */
public final class LegacyVisibleProfileSampler {
    /** One capture-time projected source anchor paired with its raster location and factual chainage. */
    public record Anchor(ProjectedPoint source, RasterPoint raster, double cumulativeGroundDistanceMeters) {
        public Anchor {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(raster, "raster");
            if (!Double.isFinite(cumulativeGroundDistanceMeters) || cumulativeGroundDistanceMeters < 0.0) {
                throw new IllegalArgumentException("Anchor chainage must be finite and nonnegative");
            }
        }
    }

    /** Native visible sampler dimensions and exact Legacy tracker normalization. */
    public record Options(int halfWidthPx, int stepPx, double trackerNormalizationRasterPx,
            IntensitySamplingMode intensityMode) {
        public Options {
            if (halfWidthPx <= 0 || stepPx <= 0 || stepPx > halfWidthPx
                    || !Double.isFinite(trackerNormalizationRasterPx)
                    || trackerNormalizationRasterPx <= 0.0) {
                throw new IllegalArgumentException("Legacy visible sampling options are invalid");
            }
            Objects.requireNonNull(intensityMode, "intensityMode");
        }
    }

    /** Fully frozen visible source input; contains no live JOSM primitives or viewport objects. */
    public record Input(LiveBPreviewService.VisibleRaster raster, List<Anchor> anchors,
            List<String> detectors, Options options) {
        public Input {
            Objects.requireNonNull(raster, "raster");
            Objects.requireNonNull(options, "options");
            if (anchors == null || anchors.size() < 2 || detectors == null || detectors.isEmpty()) {
                throw new IllegalArgumentException("Legacy visible capture needs anchors and detectors");
            }
            anchors = List.copyOf(anchors);
            detectors = List.copyOf(detectors);
            var seen = new HashSet<String>();
            double previousChainage = -1.0;
            for (Anchor anchor : anchors) {
                Objects.requireNonNull(anchor, "anchor");
                if (anchor.cumulativeGroundDistanceMeters() < previousChainage) {
                    throw new IllegalArgumentException("Anchor chainage must be monotonically nondecreasing");
                }
                previousChainage = anchor.cumulativeGroundDistanceMeters();
                requireProjectedAssociation(raster, anchor);
            }
            for (String detector : detectors) {
                if (detector == null || detector.isBlank() || !seen.add(detector)) {
                    throw new IllegalArgumentException("Detector ids must be nonblank and unique");
                }
            }
        }
    }

    /** Profiles sampled for one requested detector, retaining detector order and empty results. */
    public record DetectorProfiles(String detector, List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        public DetectorProfiles {
            if (detector == null || detector.isBlank() || profiles == null) {
                throw new IllegalArgumentException("Detector profile result is incomplete");
            }
            profiles = List.copyOf(profiles);
        }
    }

    /** Samples every requested detector using the same visible raster numerics as AlignmentService. */
    public List<DetectorProfiles> sample(Input input, CancellationProbe cancellation) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(cancellation, "cancellation");
        var anchors = new ArrayList<ProfileSamplingAnchor>(input.anchors().size());
        for (Anchor anchor : input.anchors()) {
            anchors.add(new ProfileSamplingAnchor(new EastNorth(anchor.source().east(), anchor.source().north()),
                    anchor.raster().x(), anchor.raster().y(), anchor.cumulativeGroundDistanceMeters()));
        }
        var raster = input.raster().image();
        var result = new ArrayList<DetectorProfiles>(input.detectors().size());
        var sampler = new RenderedHeatmapSampler();
        var projector = projector(input.raster());
        for (String detector : input.detectors()) {
            cancellation.checkpoint();
            var profiles = sampler.sampleProfilesOnAnchors(raster, anchors,
                    input.options().halfWidthPx(), input.options().stepPx(), detector,
                    RenderedHeatmapSampler.RASTER_SCALE, 1.0, input.options().intensityMode(),
                    projector, cancellation);
            result.add(new DetectorProfiles(detector, profiles));
        }
        return List.copyOf(result);
    }

    /** Projects raster-space candidate positions through the slide-time visible capture transform. */
    public List<ProjectedPoint> project(Input input, List<RasterPoint> candidateRasterPoints) {
        Objects.requireNonNull(input, "input");
        if (candidateRasterPoints == null) {
            throw new IllegalArgumentException("Candidate raster points are required");
        }
        var raster = input.raster();
        return candidateRasterPoints.stream().map(point -> {
            Objects.requireNonNull(point, "candidate raster point");
            return new ProjectedPoint(raster.minimumEast()
                            + point.x() / RenderedHeatmapSampler.RASTER_SCALE
                                    * raster.projectionUnitsPerViewPixel(),
                    raster.maximumNorth()
                            - point.y() / RenderedHeatmapSampler.RASTER_SCALE
                                    * raster.projectionUnitsPerViewPixel());
        }).toList();
    }

    private static RenderedHeatmapSampler.RasterCoordinateProjector projector(
            LiveBPreviewService.VisibleRaster raster) {
        return (x, y) -> new EastNorth(raster.minimumEast()
                        + x / RenderedHeatmapSampler.RASTER_SCALE * raster.projectionUnitsPerViewPixel(),
                raster.maximumNorth()
                        - y / RenderedHeatmapSampler.RASTER_SCALE * raster.projectionUnitsPerViewPixel());
    }

    private static void requireProjectedAssociation(LiveBPreviewService.VisibleRaster raster, Anchor anchor) {
        double expectedEast = raster.minimumEast() + anchor.raster().x()
                / RenderedHeatmapSampler.RASTER_SCALE * raster.projectionUnitsPerViewPixel();
        double expectedNorth = raster.maximumNorth() - anchor.raster().y()
                / RenderedHeatmapSampler.RASTER_SCALE * raster.projectionUnitsPerViewPixel();
        if (!near(anchor.source().east(), expectedEast) || !near(anchor.source().north(), expectedNorth)) {
            throw new IllegalArgumentException("Projected and raster capture anchors do not correspond");
        }
    }

    private static boolean near(double actual, double expected) {
        double tolerance = Math.max(1.0e-9, 8.0 * Math.max(Math.ulp(actual), Math.ulp(expected)));
        return Math.abs(actual - expected) <= tolerance;
    }
}
