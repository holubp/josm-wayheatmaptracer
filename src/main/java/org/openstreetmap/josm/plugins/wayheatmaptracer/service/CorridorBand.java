package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;

/**
 * One cross-sectional corridor observation derived from nested relative-intensity intervals.
 *
 * @param id deterministic profile-local identifier
 * @param centerOffsetPx robust lateral center in sampled-raster pixels
 * @param shoulderMinPx outer low-level boundary
 * @param shoulderMaxPx outer low-level boundary
 * @param coreMinPx high-level core boundary
 * @param coreMaxPx high-level core boundary
 * @param nestedCentersPx interval midpoint at every contributing relative level
 * @param peakIntensity maximum B5 intensity inside the corridor
 * @param noiseFloor robust local background estimate
 * @param valleyRatio minimum intensity between child cores divided by the weaker child peak
 * @param gradientStrength normalized mean shoulder-boundary gradient
 * @param gradientBalance similarity of the left and right boundary gradients
 * @param scaleAgreement agreement between native, B3, and B5 intensity evidence
 * @param signalExistenceConfidence confidence that a real heat trail exists
 * @param localizationConfidence confidence that its center is well localized
 * @param uncertaintyPx conservative center uncertainty in sampled-raster pixels
 * @param parentHypothesis whether this band combines multiple high-level child cores
 * @param childIds profile-local child identifiers for a parent hypothesis
 * @param boundaryCompleteness whether the sampled search captured both shoulder and core boundaries
 * @param boundarySide side on which boundary evidence is censored
 */
public record CorridorBand(
    String id,
    double centerOffsetPx,
    double shoulderMinPx,
    double shoulderMaxPx,
    double coreMinPx,
    double coreMaxPx,
    List<Double> nestedCentersPx,
    double peakIntensity,
    double noiseFloor,
    double valleyRatio,
    double gradientStrength,
    double gradientBalance,
    double scaleAgreement,
    double signalExistenceConfidence,
    double localizationConfidence,
    double uncertaintyPx,
    boolean parentHypothesis,
    List<String> childIds,
    BoundaryCompleteness boundaryCompleteness,
    BoundarySide boundarySide
) {
    /**
     * Creates a complete corridor observation with detailed gradient evidence.
     *
     * @param id deterministic profile-local identifier
     * @param centerOffsetPx robust lateral center
     * @param shoulderMinPx outer minimum boundary
     * @param shoulderMaxPx outer maximum boundary
     * @param coreMinPx core minimum boundary
     * @param coreMaxPx core maximum boundary
     * @param nestedCentersPx nested interval centers
     * @param peakIntensity peak filtered intensity
     * @param noiseFloor local noise floor
     * @param valleyRatio normalized valley ratio
     * @param gradientStrength normalized boundary gradient
     * @param gradientBalance left/right gradient balance
     * @param scaleAgreement raw/B3/B5 agreement
     * @param signalExistenceConfidence signal-existence confidence
     * @param localizationConfidence localization confidence
     * @param uncertaintyPx center uncertainty
     * @param parentHypothesis whether this combines children
     * @param childIds child identifiers
     */
    public CorridorBand(
        String id, double centerOffsetPx, double shoulderMinPx, double shoulderMaxPx,
        double coreMinPx, double coreMaxPx, List<Double> nestedCentersPx,
        double peakIntensity, double noiseFloor, double valleyRatio,
        double gradientStrength, double gradientBalance, double scaleAgreement,
        double signalExistenceConfidence, double localizationConfidence, double uncertaintyPx,
        boolean parentHypothesis, List<String> childIds
    ) {
        this(id, centerOffsetPx, shoulderMinPx, shoulderMaxPx, coreMinPx, coreMaxPx,
            nestedCentersPx, peakIntensity, noiseFloor, valleyRatio, gradientStrength, gradientBalance,
            scaleAgreement, signalExistenceConfidence, localizationConfidence, uncertaintyPx,
            parentHypothesis, childIds, BoundaryCompleteness.COMPLETE, BoundarySide.NONE);
    }

    /**
     * Creates a corridor observation when detailed gradient evidence is unavailable.
     *
     * @param id deterministic profile-local identifier
     * @param centerOffsetPx robust lateral center
     * @param shoulderMinPx outer minimum boundary
     * @param shoulderMaxPx outer maximum boundary
     * @param coreMinPx core minimum boundary
     * @param coreMaxPx core maximum boundary
     * @param nestedCentersPx nested interval centers
     * @param peakIntensity peak filtered intensity
     * @param noiseFloor local noise floor
     * @param valleyRatio normalized valley ratio
     * @param signalExistenceConfidence signal-existence confidence
     * @param localizationConfidence localization confidence
     * @param uncertaintyPx center uncertainty
     * @param parentHypothesis whether this combines children
     * @param childIds child identifiers
     */
    public CorridorBand(
        String id,
        double centerOffsetPx,
        double shoulderMinPx,
        double shoulderMaxPx,
        double coreMinPx,
        double coreMaxPx,
        List<Double> nestedCentersPx,
        double peakIntensity,
        double noiseFloor,
        double valleyRatio,
        double signalExistenceConfidence,
        double localizationConfidence,
        double uncertaintyPx,
        boolean parentHypothesis,
        List<String> childIds
    ) {
        this(id, centerOffsetPx, shoulderMinPx, shoulderMaxPx, coreMinPx, coreMaxPx,
            nestedCentersPx, peakIntensity, noiseFloor, valleyRatio, 0.0, 0.0, 0.0,
            signalExistenceConfidence, localizationConfidence, uncertaintyPx, parentHypothesis, childIds,
            BoundaryCompleteness.COMPLETE, BoundarySide.NONE);
    }

    /**
     * Makes nested evidence immutable and validates interval ordering.
     */
    public CorridorBand {
        nestedCentersPx = List.copyOf(nestedCentersPx);
        childIds = List.copyOf(childIds);
        if (boundaryCompleteness == null || boundarySide == null) {
            throw new IllegalArgumentException("Corridor boundary evidence must not be null");
        }
        if (shoulderMinPx > shoulderMaxPx || coreMinPx > coreMaxPx) {
            throw new IllegalArgumentException("Corridor interval bounds must be ordered");
        }
        if ((boundaryCompleteness == BoundaryCompleteness.COMPLETE) != (boundarySide == BoundarySide.NONE)) {
            throw new IllegalArgumentException("Complete corridor evidence must use boundary side NONE");
        }
    }

    /**
     * Returns the outer corridor width.
     *
     * @return shoulder width in sampled-raster pixels
     */
    public double shoulderWidthPx() {
        return shoulderMaxPx - shoulderMinPx;
    }

    /**
     * Returns the high-level core width.
     *
     * @return core width in sampled-raster pixels
     */
    public double coreWidthPx() {
        return coreMaxPx - coreMinPx;
    }

    /**
     * Returns whether this observation has a two-sided localized center.
     *
     * @return true for complete cores, including shoulder-only censoring
     */
    public boolean hasMeasuredCenter() {
        return boundaryCompleteness.hasMeasuredCenter();
    }

    /** Classifies which nested corridor boundaries were captured inside the search window. */
    public enum BoundaryCompleteness {
        /** Both shoulder and core are safely enclosed. */
        COMPLETE(true),
        /** A low-threshold shoulder reaches an edge while the high core remains enclosed. */
        SHOULDER_CENSORED(true),
        /** At least one high-core boundary leaves the search window. */
        CORE_CENSORED(false),
        /** The high core reaches both search boundaries. */
        FULLY_CENSORED(false);

        private final boolean measuredCenter;

        BoundaryCompleteness(boolean measuredCenter) {
            this.measuredCenter = measuredCenter;
        }

        /**
         * Returns whether this class supports direct positional evidence.
         *
         * @return true when both high-core boundaries are safely enclosed
         */
        public boolean hasMeasuredCenter() {
            return measuredCenter;
        }
    }

    /** Identifies the search side on which a corridor boundary is censored. */
    public enum BoundarySide {
        /** No boundary is censored. */
        NONE,
        /** The negative-offset boundary is censored. */
        LEFT,
        /** The positive-offset boundary is censored. */
        RIGHT,
        /** Both lateral boundaries are censored. */
        BOTH;

        /**
         * Creates a side value from independent boundary flags.
         *
         * @param left whether the negative-offset boundary is censored
         * @param right whether the positive-offset boundary is censored
         * @return canonical side value
         */
        static BoundarySide of(boolean left, boolean right) {
            if (left && right) {
                return BOTH;
            }
            if (left) {
                return LEFT;
            }
            return right ? RIGHT : NONE;
        }
    }
}
