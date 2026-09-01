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
 * @param eligibleIntervalCount independently eligible cleanup intervals
 * @param changedIntervalCount intervals that produced accepted geometric changes
 * @param frozenIntervalCount local defect neighborhoods retained exactly
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
    OptionalDouble worstFitRetention,
    int eligibleIntervalCount,
    int changedIntervalCount,
    int frozenIntervalCount
) {
    /**
     * Creates a report without format-13 interval counters.
     *
     * <p>This compatibility constructor keeps older tests and bundle readers source compatible;
     * unavailable interval counts remain zero rather than being inferred.</p>
     *
     * @param parentCandidateId stable raw candidate id
     * @param outcome terminal cleanup outcome
     * @param reasonCode primary reason code
     * @param reasons detailed reason codes
     * @param beforePointCount raw point count
     * @param smoothedPointCount post-smoothing point count
     * @param afterPointCount final point count
     * @param acceptedSmoothingPasses accepted smoothing passes
     * @param smoothingBacktrackCount smoothing backtracks
     * @param attemptedChordCount attempted simplification chords
     * @param acceptedChordCount accepted simplification chords
     * @param containmentFailureCount containment failures
     * @param fitBefore fit before cleanup
     * @param fitAfter fit after cleanup
     * @param maximumDisplacementProjectionUnits maximum projected displacement
     * @param maximumRemovedDeviationMeters maximum removed-point deviation
     * @param worstFitRetention worst accepted fit retention
     */
    public CandidateGeometryCleanup(
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
        this(parentCandidateId, outcome, reasonCode, reasons, beforePointCount, smoothedPointCount,
            afterPointCount, acceptedSmoothingPasses, smoothingBacktrackCount, attemptedChordCount,
            acceptedChordCount, containmentFailureCount, fitBefore, fitAfter,
            maximumDisplacementProjectionUnits, maximumRemovedDeviationMeters, worstFitRetention,
            0, 0, 0);
    }
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
            || eligibleIntervalCount < 0 || changedIntervalCount < 0 || frozenIntervalCount < 0
            || changedIntervalCount > eligibleIntervalCount
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
        return outcome == Outcome.CLEANED || outcome == Outcome.PARTIALLY_CLEANED;
    }


    /**
     * Returns a copy with factual format-13 interval processing counts.
     *
     * @param eligibleIntervals independently processable intervals
     * @param changedIntervals intervals that produced accepted changes
     * @param frozenIntervals protected or defective neighborhoods retained exactly
     * @return report with the supplied interval summary
     */
    public CandidateGeometryCleanup withIntervalSummary(
        int eligibleIntervals,
        int changedIntervals,
        int frozenIntervals
    ) {
        return new CandidateGeometryCleanup(parentCandidateId, outcome, reasonCode, reasons,
            beforePointCount, smoothedPointCount, afterPointCount, acceptedSmoothingPasses,
            smoothingBacktrackCount, attemptedChordCount, acceptedChordCount,
            containmentFailureCount, fitBefore, fitAfter, maximumDisplacementProjectionUnits,
            maximumRemovedDeviationMeters, worstFitRetention, eligibleIntervals, changedIntervals,
            frozenIntervals);
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
        /** This candidate contains safe changes from only the locally eligible intervals. */
        PARTIALLY_CLEANED,
        /** Cleanup input or proposed geometry failed closed. */
        REJECTED
    }
}
