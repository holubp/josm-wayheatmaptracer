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
    List<String> childIds
) {
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
            signalExistenceConfidence, localizationConfidence, uncertaintyPx, parentHypothesis, childIds);
    }

    /**
     * Makes nested evidence immutable and validates interval ordering.
     */
    public CorridorBand {
        nestedCentersPx = List.copyOf(nestedCentersPx);
        childIds = List.copyOf(childIds);
        if (shoulderMinPx > shoulderMaxPx || coreMinPx > coreMaxPx) {
            throw new IllegalArgumentException("Corridor interval bounds must be ordered");
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
}
