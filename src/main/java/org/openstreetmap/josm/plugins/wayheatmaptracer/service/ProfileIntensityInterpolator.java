package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/**
 * Interpolates scalar cross-section evidence without crossing invalid raster support.
 */
final class ProfileIntensityInterpolator {
    private static final double OFFSET_EPSILON = 1e-9;

    private ProfileIntensityInterpolator() {
        // Utility class.
    }

    /**
     * Interpolates raw, B3, and B5 intensity at one continuous lateral offset.
     *
     * @param profile sampled cross-section containing ordered or unordered scalar samples
     * @param offsetPx requested lateral offset in sampled-raster pixels
     * @return interpolated evidence, or empty when the offset is outside valid adjacent support
     */
    static Optional<InterpolatedIntensity> interpolate(
        RenderedHeatmapSampler.CrossSectionProfile profile,
        double offsetPx
    ) {
        if (!Double.isFinite(offsetPx)) {
            return Optional.empty();
        }
        List<IntensitySample> samples = profile.intensitySamples().stream()
            .sorted(Comparator.comparingDouble(IntensitySample::offsetPx))
            .toList();
        for (IntensitySample sample : samples) {
            if (Math.abs(sample.offsetPx() - offsetPx) <= OFFSET_EPSILON) {
                return sample.insideRaster() ? Optional.of(fromSample(sample)) : Optional.empty();
            }
        }
        for (int index = 1; index < samples.size(); index++) {
            IntensitySample left = samples.get(index - 1);
            IntensitySample right = samples.get(index);
            if (offsetPx <= left.offsetPx() || offsetPx >= right.offsetPx()) {
                continue;
            }
            if (!left.insideRaster() || !right.insideRaster()) {
                return Optional.empty();
            }
            double width = right.offsetPx() - left.offsetPx();
            if (width <= OFFSET_EPSILON) {
                return Optional.empty();
            }
            double fraction = (offsetPx - left.offsetPx()) / width;
            return Optional.of(new InterpolatedIntensity(
                interpolate(left.nativeIntensity(), right.nativeIntensity(), fraction),
                interpolate(left.lightFilteredIntensity(), right.lightFilteredIntensity(), fraction),
                interpolate(left.standardFilteredIntensity(), right.standardFilteredIntensity(), fraction)
            ));
        }
        return Optional.empty();
    }

    private static InterpolatedIntensity fromSample(IntensitySample sample) {
        return new InterpolatedIntensity(sample.nativeIntensity(), sample.lightFilteredIntensity(),
            sample.standardFilteredIntensity());
    }

    private static double interpolate(double left, double right, double fraction) {
        return left + fraction * (right - left);
    }

    /**
     * Scalar evidence at one continuous lateral offset.
     *
     * @param nativeIntensity unfiltered palette-mapped intensity
     * @param lightFilteredIntensity B3-filtered intensity
     * @param standardFilteredIntensity B5-filtered intensity
     */
    record InterpolatedIntensity(
        double nativeIntensity,
        double lightFilteredIntensity,
        double standardFilteredIntensity
    ) {
        /**
         * Returns the established raw/B3/B5 blend used by corridor optimization.
         *
         * @return blended scalar intensity
         */
        double scaleIntensity() {
            return 0.30 * nativeIntensity + 0.30 * lightFilteredIntensity + 0.40 * standardFilteredIntensity;
        }
    }
}
