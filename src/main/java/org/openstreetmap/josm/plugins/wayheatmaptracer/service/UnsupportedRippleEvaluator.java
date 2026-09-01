package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;
import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalShapeEvidence;

/** Adapts shared multiscale local-shape evidence to format-10 optimizer diagnostics and costs. */
final class UnsupportedRippleEvaluator {
    /**
     * Evaluates the common fixed physical scale bank for one selected corridor track.
     *
     * @param track corridor identity and direct/interpolated provenance
     * @param tube profile-aligned robust corridor references
     * @param sourcePixelSizePx native source-pixel pitch in sampled-raster pixels
     * @param ignoredLegacyScaleMeters retained only for source/binary compatibility
     * @param enabled whether cleanup-only regularization is active
     * @return immutable profile-aligned compatibility decisions
     */
    List<RippleSupport> evaluate(
        CorridorTrack track,
        LongitudinalCorridorTube tube,
        double sourcePixelSizePx,
        double ignoredLegacyScaleMeters,
        boolean enabled
    ) {
        if (!enabled) {
            return java.util.stream.IntStream.range(0, tube.slices().size())
                .mapToObj(RippleSupport::disabled).toList();
        }
        double sourcePixel = validSourcePixel(sourcePixelSizePx);
        return new LocalShapeEvidenceEvaluator().evaluate(track, tube, sourcePixel).stream()
            .map(evidence -> adapt(evidence, sourcePixel)).toList();
    }

    /** Evaluates synthetic fixtures whose profiles are all directly observed. */
    List<RippleSupport> evaluate(
        LongitudinalCorridorTube tube,
        double sourcePixelSizePx,
        double ignoredLegacyScaleMeters,
        boolean enabled
    ) {
        return evaluate(null, tube, sourcePixelSizePx, ignoredLegacyScaleMeters, enabled);
    }

    private RippleSupport adapt(LocalShapeEvidence evidence, double sourcePixel) {
        double trendAuthorization = evidence.wrinkleScore() <= 0.0 ? 0.0
            : clamp(evidence.cleanupIntervention() / evidence.wrinkleScore());
        return new RippleSupport(evidence.profileIndex(), evidence.bendProtection(),
            evidence.bendProtection(), evidence.wrinkleScore(), evidence.residualAmplitudeSourcePx(),
            Math.max(evidence.residualAmplitudeSourcePx(), Math.abs(evidence.residualSourcePx())),
            evidence.directCoverage(), evidence.trendCenterSourcePx() * sourcePixel,
            evidence.trendSlopeSourcePxPerMeter() * sourcePixel,
            evidence.trendUncertaintySourcePx() * sourcePixel, trendAuthorization,
            evidence.wrinkleScore(), evidence.reversalSpacingMeters(), evidence.reversalCount(),
            evidence.reason().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
    }

    private double validSourcePixel(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Immutable format-10 physical-window classification and intervention evidence. */
    record RippleSupport(
        int profileIndex,
        double support,
        double supportedTurnWeight,
        double shortScaleExposure,
        double residualAmplitudeSourcePixels,
        double maximumResidualSourcePixels,
        double directCoverage,
        double trendCenterOffsetPx,
        double trendSlopePxPerMeter,
        double trendUncertaintyPx,
        double trendAuthorization,
        double unsupportedWeight,
        double reversalSpacingMeters,
        int reversalCount,
        String reason
    ) {
        /** Validates bounded compatibility metrics and typed reason text. */
        RippleSupport {
            reason = Objects.requireNonNull(reason, "reason");
            if (profileIndex < 0 || reversalCount < 0
                || residualAmplitudeSourcePixels < 0.0 || maximumResidualSourcePixels < 0.0
                || trendUncertaintyPx < 0.0
                || !(Double.isNaN(reversalSpacingMeters)
                    || Double.isFinite(reversalSpacingMeters) && reversalSpacingMeters >= 0.0)) {
                throw new IllegalArgumentException("Ripple diagnostics are invalid");
            }
            for (double value : List.of(support, supportedTurnWeight, shortScaleExposure,
                    directCoverage, trendAuthorization, unsupportedWeight)) {
                if (!ratio(value)) {
                    throw new IllegalArgumentException("Ripple weights must be in [0, 1]");
                }
            }
            for (double value : List.of(trendCenterOffsetPx, trendSlopePxPerMeter,
                    residualAmplitudeSourcePixels, maximumResidualSourcePixels, trendUncertaintyPx)) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Ripple values must be finite");
                }
            }
        }

        static RippleSupport disabled(int index) {
            return new RippleSupport(index, 1.0, 1.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, Double.NaN, 0, "disabled");
        }

        private static boolean ratio(double value) {
            return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
        }
    }
}
