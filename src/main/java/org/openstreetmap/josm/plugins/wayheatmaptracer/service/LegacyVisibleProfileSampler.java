package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

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
            List<String> detectors, Options options, LegacyProfileNormalPolicy normalPolicy,
            List<Integer> fixedSourceIndices, String normalPolicyFallbackReason,
            LegacyTrackerPolicy trackerPolicy, OptionalDouble nativeSourcePitchRasterPx,
            String trackerPolicyFallbackReason) {
        public Input(LiveBPreviewService.VisibleRaster raster, List<Anchor> anchors,
                List<String> detectors, Options options) {
            this(raster, anchors, detectors, options, LegacyProfileNormalPolicy.IMMEDIATE_V1, List.of(), "",
                    LegacyTrackerPolicy.ORIGINAL_V02, factualNativePitch(raster), "");
        }

        public Input(LiveBPreviewService.VisibleRaster raster, List<Anchor> anchors,
                List<String> detectors, Options options, LegacyProfileNormalPolicy normalPolicy,
                List<Integer> fixedSourceIndices, String normalPolicyFallbackReason) {
            this(raster, anchors, detectors, options, normalPolicy, fixedSourceIndices, normalPolicyFallbackReason,
                    LegacyTrackerPolicy.ORIGINAL_V02, factualNativePitch(raster), "");
        }

        /** Returns the actual captured source-pixel pitch, independent of tracker normalization. */
        public OptionalDouble factualNativeSourcePitchRasterPx() { return factualNativePitch(raster); }

        /** Adds explicit tracker provenance after validating pitch against the captured visible source. */
        public Input withTrackerPolicy(LegacyTrackerPolicy policy, OptionalDouble nativePitch, String fallbackReason) {
            validateTrackerChoice(policy, nativePitch, fallbackReason, factualNativeSourcePitchRasterPx());
            return new Input(raster, anchors, detectors, options, normalPolicy, fixedSourceIndices,
                    normalPolicyFallbackReason, policy, nativePitch, fallbackReason);
        }

        /** Resolves live eligibility before freezing; malformed explicit replay claims never use this path. */
        public Input withNormalPolicy(LegacyProfileNormalPolicy policy, List<Integer> fixed) {
            Objects.requireNonNull(policy, "normalPolicy");
            fixed = validateNormalPolicy(policy, fixed, "", anchors.size());
            if (policy == LegacyProfileNormalPolicy.IMMEDIATE_V1) {
                return normalPolicy == policy && normalPolicyFallbackReason.isEmpty() ? this
                        : new Input(raster, anchors, detectors, options, LegacyProfileNormalPolicy.IMMEDIATE_V1,
                                List.of(), "", trackerPolicy, nativeSourcePitchRasterPx, trackerPolicyFallbackReason);
            }
            var source = anchors.stream().map(a -> new EastNorth(a.source().east(), a.source().north())).toList();
            var plan = LegacyProfileNormalFences.plan(source, anchors.size(), fixed);
            return new Input(raster, anchors, detectors, options,
                        plan.eligible() ? policy : LegacyProfileNormalPolicy.IMMEDIATE_V1,
                        fixed, plan.eligible() ? "" : plan.reason(), trackerPolicy, nativeSourcePitchRasterPx,
                        trackerPolicyFallbackReason);
        }
        public Input {
            Objects.requireNonNull(raster, "raster");
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(trackerPolicy, "trackerPolicy");
            nativeSourcePitchRasterPx = nativeSourcePitchRasterPx == null ? OptionalDouble.empty() : nativeSourcePitchRasterPx;
            trackerPolicyFallbackReason = validateTrackerChoice(trackerPolicy, nativeSourcePitchRasterPx,
                    trackerPolicyFallbackReason, factualNativePitch(raster));
            if (anchors == null || anchors.size() < 2 || detectors == null || detectors.isEmpty()) {
                throw new IllegalArgumentException("Legacy visible capture needs anchors and detectors");
            }
            fixedSourceIndices = validateNormalPolicy(normalPolicy, fixedSourceIndices, normalPolicyFallbackReason, anchors.size());
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
            if (normalPolicy == LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1 || !normalPolicyFallbackReason.isEmpty()) {
                var plan = LegacyProfileNormalFences.plan(anchors.stream().map(a -> new EastNorth(
                        a.source().east(), a.source().north())).toList(), anchors.size(), fixedSourceIndices);
                if (normalPolicy == LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1 ? !plan.eligible()
                        : plan.eligible() || !normalPolicyFallbackReason.equals(plan.reason())) {
                    throw new IllegalArgumentException("Invalid protected Legacy normal authority or fallback");
                }
            }
            for (String detector : detectors) {
                if (detector == null || detector.isBlank() || !seen.add(detector)) {
                    throw new IllegalArgumentException("Detector ids must be nonblank and unique");
                }
            }
        }

        private static OptionalDouble factualNativePitch(LiveBPreviewService.VisibleRaster raster) {
            if (raster == null || raster.nativePitchMeters().isEmpty()
                    || !Double.isFinite(raster.groundMetersPerViewPixel())
                    || raster.groundMetersPerViewPixel() <= 0.0) return OptionalDouble.empty();
            double groundPerRaster = raster.groundMetersPerViewPixel() / RenderedHeatmapSampler.RASTER_SCALE;
            double pitch = raster.nativePitchMeters().getAsDouble() / groundPerRaster;
            return Double.isFinite(pitch) && pitch > 0.0 ? OptionalDouble.of(pitch) : OptionalDouble.empty();
        }
    }

    static String validateTrackerChoice(LegacyTrackerPolicy policy, OptionalDouble pitch,
            String fallbackReason, OptionalDouble factualPitch) {
        Objects.requireNonNull(policy, "trackerPolicy");
        pitch = pitch == null ? OptionalDouble.empty() : pitch;
        String reason = fallbackReason == null ? "" : fallbackReason;
        if (!reason.isEmpty() && !reason.equals("native-pitch-unavailable")) {
            throw new IllegalArgumentException("Unknown Legacy tracker fallback reason");
        }
        if (pitch.isPresent() && (!Double.isFinite(pitch.getAsDouble()) || pitch.getAsDouble() <= 0.0)) {
            throw new IllegalArgumentException("Native source pitch must be finite and positive");
        }
        if (policy.requiresNativePitch()) {
            if (reason.length() > 0 || pitch.isEmpty() || factualPitch.isEmpty()
                    || Math.abs(pitch.getAsDouble() - factualPitch.getAsDouble())
                        > Math.max(1e-9, factualPitch.getAsDouble() * 1e-9)) {
                throw new IllegalArgumentException("Tracker policy pitch must match captured visible source metadata");
            }
        } else if (!reason.isEmpty() && (policy != LegacyTrackerPolicy.ORIGINAL_V02 || pitch.isPresent()
                || factualPitch.isPresent())) {
            throw new IllegalArgumentException("Native-pitch fallback must use the original policy without a pitch");
        } else if (pitch.isPresent() && (factualPitch.isEmpty()
                || Math.abs(pitch.getAsDouble() - factualPitch.getAsDouble())
                    > Math.max(1e-9, factualPitch.getAsDouble() * 1e-9))) {
            throw new IllegalArgumentException("Native source pitch differs from captured visible source metadata");
        }
        return reason;
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
            var profiles = input.normalPolicy() == LegacyProfileNormalPolicy.IMMEDIATE_V1
                    ? sampler.sampleProfilesOnAnchors(raster, anchors,
                    input.options().halfWidthPx(), input.options().stepPx(), detector,
                    RenderedHeatmapSampler.RASTER_SCALE, 1.0, input.options().intensityMode(),
                    projector, cancellation)
                    : sampler.sampleProfilesOnAnchorsWithWideSecants(raster, anchors,
                        input.options().halfWidthPx(), input.options().stepPx(), detector,
                        RenderedHeatmapSampler.RASTER_SCALE, 1.0, input.options().intensityMode(),
                        projector, cancellation, java.util.Set.copyOf(input.fixedSourceIndices()));
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

    static List<Integer> validateNormalPolicy(LegacyProfileNormalPolicy policy, List<Integer> fixed,
            String fallbackReason, int sourceCount) {
        Objects.requireNonNull(policy, "normalPolicy");
        if (fallbackReason == null || !fallbackReason.matches("[a-z0-9-]{0,96}")) {
            throw new IllegalArgumentException("Invalid Legacy normal fallback reason");
        }
        fixed = List.copyOf(fixed);
        if (policy == LegacyProfileNormalPolicy.IMMEDIATE_V1 && fallbackReason.isEmpty()) {
            if (!fixed.isEmpty()) throw new IllegalArgumentException("Default immediate normals cannot claim protected authority");
        } else {
            if (policy == LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1 && !fallbackReason.isEmpty()
                    || fixed.size() < 2 || fixed.get(0) != 0 || fixed.get(fixed.size() - 1) != sourceCount - 1) {
                throw new IllegalArgumentException("Protected Legacy normals need complete boundary authority");
            }
            int previous = -1;
            for (int index : fixed) {
                if (index <= previous || index >= sourceCount) {
                    throw new IllegalArgumentException("Legacy normal boundaries must be ordered unique source indices");
                }
                previous = index;
            }
        }
        return fixed;
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
