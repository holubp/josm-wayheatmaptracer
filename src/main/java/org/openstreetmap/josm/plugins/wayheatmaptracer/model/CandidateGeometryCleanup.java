package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Compact immutable report for one candidate's optional final-preview geometry cleanup.
 *
 * @param parentCandidateId stable raw candidate id, empty only when cleanup was not requested
 * @param outcome terminal cleanup outcome
 * @param reasonCode stable primary reason code
 * @param reasons ordered detailed reason codes
 * @param beforePointCount raw final-preview point count
 * @param smoothedPointCount point count after smoothing and before reduction
 * @param afterPointCount final cleaned point count
 * @param acceptedSmoothingPasses accepted constrained-Laplacian passes
 * @param smoothingBacktrackCount rejected smoothing step sizes
 * @param attemptedChordCount constrained simplification chords considered
 * @param acceptedChordCount constrained simplification chords accepted
 * @param containmentFailureCount rejected corridor-containment checks
 * @param fitBefore mean scalar heatmap fit before cleanup
 * @param fitAfter mean scalar heatmap fit after cleanup
 * @param maximumDisplacementProjectionUnits greatest accepted smoothing displacement
 * @param maximumRemovedDeviationMeters greatest accepted point-removal deviation in ground metres
 * @param worstFitRetention worst accepted simplification fit ratio
 */
public record CandidateGeometryCleanup(
    String parentCandidateId,
    Outcome outcome,
    String reasonCode,
    List<String> reasons,
    int beforePointCount,
    int smoothedPointCount,
    int afterPointCount,
    int acceptedSmoothingPasses,
    int smoothingBacktrackCount,
    int attemptedChordCount,
    int acceptedChordCount,
    int containmentFailureCount,
    double fitBefore,
    double fitAfter,
    double maximumDisplacementProjectionUnits,
    OptionalDouble maximumRemovedDeviationMeters,
    OptionalDouble worstFitRetention
) {
    /** Validates counts, ratios, optional metrics, and immutable reason data. */
    public CandidateGeometryCleanup {
        parentCandidateId = Objects.requireNonNull(parentCandidateId, "parentCandidateId");
        outcome = Objects.requireNonNull(outcome, "outcome");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        reasons = List.copyOf(reasons);
        maximumRemovedDeviationMeters = Objects.requireNonNull(
            maximumRemovedDeviationMeters, "maximumRemovedDeviationMeters");
        worstFitRetention = Objects.requireNonNull(worstFitRetention, "worstFitRetention");
        if (beforePointCount < 0 || smoothedPointCount < 0 || afterPointCount < 0
            || acceptedSmoothingPasses < 0 || smoothingBacktrackCount < 0
            || attemptedChordCount < 0 || acceptedChordCount < 0
            || acceptedChordCount > attemptedChordCount || containmentFailureCount < 0
            || !ratio(fitBefore) || !ratio(fitAfter)
            || !nonNegative(maximumDisplacementProjectionUnits)
            || maximumRemovedDeviationMeters.isPresent()
                && !nonNegative(maximumRemovedDeviationMeters.orElseThrow())
            || worstFitRetention.isPresent() && !ratio(worstFitRetention.orElseThrow())) {
            throw new IllegalArgumentException("Candidate cleanup report metrics are invalid");
        }
    }

    /**
     * Returns the neutral report used by candidates created without cleanup.
     *
     * @return neutral report
     */
    public static CandidateGeometryCleanup notRequested() {
        return new CandidateGeometryCleanup("", Outcome.NOT_REQUESTED, "not-requested", List.of(),
            0, 0, 0, 0, 0, 0, 0, 0, 1.0, 1.0, 0.0,
            OptionalDouble.empty(), OptionalDouble.empty());
    }

    /**
     * Reports whether this candidate is the generated cleaned sibling.
     *
     * @return true for cleaned output
     */
    public boolean cleanedCandidate() {
        return outcome == Outcome.CLEANED;
    }

    private static boolean nonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static boolean ratio(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    /** Terminal state of one requested cleanup attempt. */
    public enum Outcome {
        /** Cleanup was disabled for the slide. */
        NOT_REQUESTED,
        /** Candidate mode or retained evidence was ineligible. */
        SKIPPED,
        /** Cleanup ran safely but made no geometric change. */
        UNCHANGED,
        /** Cleanup succeeded and a separate cleaned sibling is available. */
        CLEANED_ALTERNATIVE_AVAILABLE,
        /** This candidate contains the successful cleaned geometry. */
        CLEANED,
        /** Cleanup input or proposed geometry failed closed. */
        REJECTED
    }
}
