package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Immutable output of heatmap-constrained point reduction.
 *
 * <p>Ground-metre metrics use the explicit slide-time lateral raster-to-ground scale retained by
 * cleanup evidence. They remain optional when reduction made no evaluable removal; an absent value
 * must not be interpreted as zero.</p>
 *
 * @param geometry copied retained geometry
 * @param retainedSourceIndexes source index represented by every retained point
 * @param status whether points were removed, retained, or the input was rejected
 * @param failureReasons typed constraints that prevented or rejected reduction
 * @param chordRejections deterministic per-chord rejection diagnostics
 * @param metrics unit-explicit reduction metrics
 */
public record HeatmapConstrainedSimplificationResult(
    List<EastNorth> geometry,
    List<Integer> retainedSourceIndexes,
    Status status,
    List<FailureReason> failureReasons,
    List<ChordRejection> chordRejections,
    Metrics metrics
) {
    /** Copies mutable inputs and validates source-index and geometry alignment. */
    public HeatmapConstrainedSimplificationResult {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(retainedSourceIndexes, "retainedSourceIndexes");
        status = Objects.requireNonNull(status, "status");
        Objects.requireNonNull(failureReasons, "failureReasons");
        Objects.requireNonNull(chordRejections, "chordRejections");
        metrics = Objects.requireNonNull(metrics, "metrics");
        if (geometry.size() != retainedSourceIndexes.size()) {
            throw new IllegalArgumentException("Retained geometry and source indexes must align");
        }
        List<EastNorth> copiedGeometry = new ArrayList<>(geometry.size());
        int previousIndex = -1;
        for (int index = 0; index < geometry.size(); index++) {
            EastNorth point = geometry.get(index);
            Integer sourceIndex = retainedSourceIndexes.get(index);
            if (point == null || !Double.isFinite(point.east()) || !Double.isFinite(point.north())
                || sourceIndex == null || sourceIndex <= previousIndex) {
                throw new IllegalArgumentException(
                    "Result geometry must be finite and source indexes strictly increasing");
            }
            copiedGeometry.add(new EastNorth(point.east(), point.north()));
            previousIndex = sourceIndex;
        }
        geometry = List.copyOf(copiedGeometry);
        retainedSourceIndexes = List.copyOf(retainedSourceIndexes);
        failureReasons = List.copyOf(failureReasons);
        chordRejections = List.copyOf(chordRejections);
    }

    /** Overall point-reduction outcome. */
    public enum Status {
        /** At least one source point was safely removed. */
        SIMPLIFIED,
        /** Input was valid but no replacement chord was accepted. */
        UNCHANGED,
        /** Input geometry, intervals, topology, or evidence were invalid. */
        REJECTED
    }

    /** Typed constraints and validation failures for simplification. */
    public enum FailureReason {
        /** Cleanup mode does not request point reduction. */
        MODE_DISABLED,
        /** Geometry has too few points, repeated points, or non-finite coordinates. */
        INVALID_GEOMETRY,
        /** Protected intervals overlap, exceed geometry, or are otherwise invalid. */
        INVALID_PROTECTED_INTERVALS,
        /** Candidate cleanup evidence is not complete. */
        INELIGIBLE_EVIDENCE,
        /** Evidence profiles, transforms, or chainages do not align with geometry. */
        MISALIGNED_EVIDENCE,
        /** Evidence lacks an explicit conversion from lateral raster displacement to ground metres. */
        MISSING_GROUND_SCALE,
        /** Maximum removed-point ground deviation exceeds the configured bound. */
        DEVIATION_LIMIT,
        /** A covered profile has no selected-corridor support. */
        UNSUPPORTED_GAP,
        /** A covered profile is outside retained raster support. */
        OFF_RASTER_GAP,
        /** A covered profile has no scalar heatmap signal. */
        NO_SIGNAL_GAP,
        /** No direct profile supports replacing the covered geometry. */
        NO_DIRECT_AUTHORIZATION,
        /** A replacement chord would leave the selected corridor. */
        CORRIDOR_CONTAINMENT,
        /** A replacement chord would worsen centerline bias beyond one quarter source pixel. */
        CENTER_RETENTION,
        /** A replacement chord would lose too much raw, B3, or B5 fit. */
        FIT_RETENTION,
        /** A replacement chord would retain less than 90 percent of supported turn amplitude. */
        SUPPORTED_TURN_RETENTION,
        /** A replacement chord would not advance monotonically through physical chainage. */
        NON_MONOTONIC_PROGRESS,
        /** Source geometry already crosses, touches, overlaps, or folds back on itself. */
        SOURCE_TOPOLOGY_UNSAFE,
        /** A replacement chord would introduce a crossing, touch, or collinear overlap. */
        TOPOLOGY_CONTACT,
        /** A replacement chord would reverse against source progress. */
        FOLDBACK
    }

    /**
     * Records why one protected-interval chord was not accepted.
     *
     * @param startSourceIndex inclusive source index at chord start
     * @param endSourceIndex inclusive source index at chord end
     * @param blockingProfileIndex blocking profile, or {@code -1} for an interval-wide reason
     * @param reason typed rejection reason
     */
    public record ChordRejection(
        int startSourceIndex,
        int endSourceIndex,
        int blockingProfileIndex,
        FailureReason reason
    ) {
        /** Validates source ordering and an optional in-range blocker. */
        public ChordRejection {
            reason = Objects.requireNonNull(reason, "reason");
            if (startSourceIndex < 0 || endSourceIndex <= startSourceIndex
                || blockingProfileIndex < -1
                || blockingProfileIndex >= 0
                    && (blockingProfileIndex < startSourceIndex || blockingProfileIndex > endSourceIndex)) {
                throw new IllegalArgumentException("Chord-rejection indexes are invalid");
            }
        }
    }

    /**
     * Unit-explicit simplification metrics.
     *
     * @param beforePointCount source point count
     * @param afterPointCount retained point count
     * @param protectedPointCount unique explicit and interval-endpoint protections
     * @param retainedSupportedAnchorCount directly supported turn/apex profiles retained
     * @param attemptedChordCount replacement chords considered
     * @param acceptedChordCount replacement chords accepted
     * @param containmentFailureCount rejected selected-corridor checks
     * @param maximumRemovedPointDeviationMeters maximum physical deviation, absent when no point was removed or scale is missing
     * @param worstFitRetention worst accepted raw/B3/B5 fit ratio, absent when no chord was evaluable
     * @param supportedAmplitudeRetention minimum retained supported-turn amplitude ratio
     */
    public record Metrics(
        int beforePointCount,
        int afterPointCount,
        int protectedPointCount,
        int retainedSupportedAnchorCount,
        int attemptedChordCount,
        int acceptedChordCount,
        int containmentFailureCount,
        OptionalDouble maximumRemovedPointDeviationMeters,
        OptionalDouble worstFitRetention,
        double supportedAmplitudeRetention
    ) {
        /** Validates counts, optional physical metrics, and dimensionless ratios. */
        public Metrics {
            maximumRemovedPointDeviationMeters = Objects.requireNonNull(
                maximumRemovedPointDeviationMeters, "maximumRemovedPointDeviationMeters");
            worstFitRetention = Objects.requireNonNull(worstFitRetention, "worstFitRetention");
            if (beforePointCount < 0 || afterPointCount < 0 || afterPointCount > beforePointCount
                || protectedPointCount < 0 || protectedPointCount > beforePointCount
                || retainedSupportedAnchorCount < 0 || retainedSupportedAnchorCount > afterPointCount
                || attemptedChordCount < 0 || acceptedChordCount < 0
                || acceptedChordCount > attemptedChordCount || containmentFailureCount < 0
                || !optionalNonNegative(maximumRemovedPointDeviationMeters)
                || !optionalRatio(worstFitRetention) || !ratio(supportedAmplitudeRetention)) {
                throw new IllegalArgumentException("Simplification metrics are invalid");
            }
        }

        private static boolean optionalNonNegative(OptionalDouble value) {
            return value.isEmpty() || Double.isFinite(value.orElseThrow()) && value.orElseThrow() >= 0.0;
        }

        private static boolean optionalRatio(OptionalDouble value) {
            return value.isEmpty() || ratio(value.orElseThrow());
        }

        private static boolean ratio(double value) {
            return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
        }
    }
}
