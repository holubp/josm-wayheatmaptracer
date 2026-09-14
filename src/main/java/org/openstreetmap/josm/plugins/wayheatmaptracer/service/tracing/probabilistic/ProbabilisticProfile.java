package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

/** One complete scalar cross-section and its detached physical geometry. */
public record ProbabilisticProfile(
    int profileIndex,
    double chainageMeters,
    MetricPoint anchor,
    MetricPoint normalUnit,
    double minimumOffsetMeters,
    double maximumOffsetMeters,
    double sourcePitchMeters,
    boolean nativePitchKnown,
    double noiseFloor,
    List<Sample> samples,
    List<Mode> modes,
    List<CensoredMode> censoredModes,
    OptionalDouble exactAnchorOffsetMeters,
    ImageOrientationSupport orientationSupport
) {
    /** A scalar sample already mapped from the source palette. */
    public record Sample(double offsetMeters, double intensity, boolean valid) {
        /** Validates physical offset and normalized valid intensity. */
        public Sample {
            if (!Double.isFinite(offsetMeters) || valid && (!Double.isFinite(intensity)
                || intensity < 0.0 || intensity > 1.0)) {
                throw new IllegalArgumentException("Scalar profile sample is invalid");
            }
        }
    }

    /** Deterministically extracted elementary or grouped-parent observation hypothesis. */
    public record Mode(String id, String evidenceLineage, double coreMinimumMeters,
        double coreMaximumMeters, double localizationSigmaMeters, double existenceConfidence,
        double localizationConfidence, List<Double> peakOffsetsMeters,
        List<Double> nestedCenterOffsetsMeters, boolean groupedParent,
        ImageOrientationSupport orientationSupport) {
        /** Retains mode fixtures that predate branch-local image orientation. */
        public Mode(String id, String evidenceLineage, double coreMinimumMeters,
            double coreMaximumMeters, double localizationSigmaMeters, double existenceConfidence,
            double localizationConfidence, List<Double> peakOffsetsMeters,
            List<Double> nestedCenterOffsetsMeters, boolean groupedParent) {
            this(id, evidenceLineage, coreMinimumMeters, coreMaximumMeters,
                localizationSigmaMeters, existenceConfidence, localizationConfidence,
                peakOffsetsMeters, nestedCenterOffsetsMeters, groupedParent,
                ImageOrientationSupport.unknown(
                    ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT));
        }

        /** Validates a finite core, confidence and immutable modal positions. */
        public Mode {
            if (blank(id) || blank(evidenceLineage) || !Double.isFinite(coreMinimumMeters)
                || !Double.isFinite(coreMaximumMeters) || coreMinimumMeters > coreMaximumMeters
                || !Double.isFinite(localizationSigmaMeters) || localizationSigmaMeters <= 0.0
                || !unit(existenceConfidence) || !unit(localizationConfidence)
                || peakOffsetsMeters == null || nestedCenterOffsetsMeters == null
                || orientationSupport == null
                || peakOffsetsMeters.stream().anyMatch(value -> !Double.isFinite(value))
                || nestedCenterOffsetsMeters.stream().anyMatch(value -> !Double.isFinite(value))) {
                throw new IllegalArgumentException("Measured mode is invalid");
            }
            peakOffsetsMeters = List.copyOf(peakOffsetsMeters);
            nestedCenterOffsetsMeters = List.copyOf(nestedCenterOffsetsMeters);
        }

        /** Returns the deterministic center of the observed high-core interval. */
        public double coreCenterMeters() {
            return 0.5 * (coreMinimumMeters + coreMaximumMeters);
        }

        /** Returns the specified direct localization strength. */
        public double strength() {
            return existenceConfidence * localizationConfidence;
        }
    }

    /** One-sided, position-incomplete evidence at a decision boundary. */
    public record CensoredMode(String id, String evidenceLineage, CensorSide side,
        double observedBoundaryMeters, double existenceConfidence, boolean gradientSupported) {
        /** Validates one censored observation. */
        public CensoredMode {
            if (blank(id) || blank(evidenceLineage) || side == null
                || !Double.isFinite(observedBoundaryMeters) || !unit(existenceConfidence)) {
                throw new IllegalArgumentException("Censored mode is invalid");
            }
        }

        /** Returns its deliberately reduced mixture strength. */
        public double strength() {
            return 0.25 * existenceConfidence;
        }
    }

    /** Side at which positional support leaves the measured decision window. */
    public enum CensorSide { LEFT, RIGHT }

    /** Retains the point-direction constructor for deterministic graph fixtures. */
    public ProbabilisticProfile(int profileIndex, double chainageMeters, MetricPoint anchor,
        MetricPoint normalUnit, double minimumOffsetMeters, double maximumOffsetMeters,
        double sourcePitchMeters, boolean nativePitchKnown, double noiseFloor, List<Sample> samples,
        List<Mode> modes, List<CensoredMode> censoredModes, OptionalDouble exactAnchorOffsetMeters,
        List<Double> supportedDirectionsRadians, double orientationCertainty) {
        this(profileIndex, chainageMeters, anchor, normalUnit, minimumOffsetMeters,
            maximumOffsetMeters, sourcePitchMeters, nativePitchKnown, noiseFloor, samples, modes,
            censoredModes, exactAnchorOffsetMeters,
            ImageOrientationSupport.legacy(supportedDirectionsRadians, orientationCertainty));
    }

    /** Deeply copies profile evidence and enforces a unit normal and ordered scalar axis. */
    public ProbabilisticProfile {
        if (profileIndex < 0 || !Double.isFinite(chainageMeters) || chainageMeters < 0.0
            || anchor == null || normalUnit == null || !Double.isFinite(minimumOffsetMeters)
            || !Double.isFinite(maximumOffsetMeters) || minimumOffsetMeters >= maximumOffsetMeters
            || !Double.isFinite(sourcePitchMeters) || sourcePitchMeters <= 0.0
            || !unit(noiseFloor) || samples == null || samples.isEmpty() || modes == null
            || censoredModes == null || exactAnchorOffsetMeters == null
            || orientationSupport == null) {
            throw new IllegalArgumentException("Probabilistic profile is incomplete");
        }
        double norm = Math.hypot(normalUnit.xMeters(), normalUnit.yMeters());
        if (Math.abs(norm - 1.0) > 1e-9) {
            throw new IllegalArgumentException("Profile normal must be a unit vector");
        }
        samples = samples.stream().sorted(Comparator.comparingDouble(Sample::offsetMeters)).toList();
        for (int index = 1; index < samples.size(); index++) {
            if (samples.get(index).offsetMeters() <= samples.get(index - 1).offsetMeters()) {
                throw new IllegalArgumentException("Scalar sample offsets must be unique and ordered");
            }
        }
        modes = List.copyOf(modes);
        censoredModes = List.copyOf(censoredModes);
        if (exactAnchorOffsetMeters.isPresent() && (!Double.isFinite(exactAnchorOffsetMeters.getAsDouble())
            || exactAnchorOffsetMeters.getAsDouble() < minimumOffsetMeters
            || exactAnchorOffsetMeters.getAsDouble() > maximumOffsetMeters)) {
            throw new IllegalArgumentException("Exact anchor is outside the decision window");
        }
    }

    /** Returns representative bearings for compatibility diagnostics. */
    public List<Double> supportedDirectionsRadians() {
        return orientationSupport.supportedDirectionsRadians();
    }

    /** Returns image-orientation certainty. */
    public double orientationCertainty() {
        return orientationSupport.certainty();
    }

    /** Returns whether configured image-orientation work exhausted its descriptor budget. */
    public boolean orientationResourceLimited() {
        return orientationSupport.status() == ImageOrientationSupport.Status.RESOURCE_LIMIT
            || modes.stream().anyMatch(mode -> mode.orientationSupport().status()
                == ImageOrientationSupport.Status.RESOURCE_LIMIT);
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
