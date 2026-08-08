package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Immutable output of heatmap-constrained Laplacian smoothing.
 *
 * <p>Displacements use projection units deliberately. The smoother has no projection service and
 * therefore must not relabel projected-coordinate distances as ground metres.</p>
 *
 * @param geometry copied output geometry, or the copied input geometry when unchanged/rejected
 * @param status whether smoothing changed geometry, made no change, or rejected the input
 * @param failureReasons typed constraints that prevented or limited smoothing
 * @param metrics deterministic smoothing and evidence metrics
 */
public record HeatmapConstrainedLaplacianResult(
    List<EastNorth> geometry,
    Status status,
    List<FailureReason> failureReasons,
    Metrics metrics
) {
    /** Copies geometry and diagnostic collections so callers cannot mutate the result. */
    public HeatmapConstrainedLaplacianResult {
        Objects.requireNonNull(geometry, "geometry");
        status = Objects.requireNonNull(status, "status");
        Objects.requireNonNull(failureReasons, "failureReasons");
        metrics = Objects.requireNonNull(metrics, "metrics");
        List<EastNorth> copied = new ArrayList<>(geometry.size());
        for (EastNorth point : geometry) {
            if (point == null || !Double.isFinite(point.east()) || !Double.isFinite(point.north())) {
                throw new IllegalArgumentException("Result geometry must contain only finite points");
            }
            copied.add(new EastNorth(point.east(), point.north()));
        }
        geometry = List.copyOf(copied);
        failureReasons = List.copyOf(failureReasons);
    }

    /** Overall smoothing outcome. */
    public enum Status {
        /** At least one point moved while all constraints remained satisfied. */
        APPLIED,
        /** Input was valid but smoothing was disabled or no authorized pass changed it. */
        UNCHANGED,
        /** Input or source topology was unsafe, incomplete, or inconsistent. */
        REJECTED
    }

    /** Typed reasons why smoothing was skipped, backtracked, limited, or rejected. */
    public enum FailureReason {
        /** The selected cleanup mode does not request Laplacian smoothing. */
        MODE_DISABLED,
        /** Geometry has too few points, non-finite coordinates, or invalid interval bounds. */
        INVALID_GEOMETRY,
        /** Cleanup evidence is not eligible for candidate cleanup. */
        INELIGIBLE_EVIDENCE,
        /** Evidence rows, transforms, or physical chainages do not align with geometry. */
        MISALIGNED_EVIDENCE,
        /** No direct, unprotected, conflict-free row authorized a displacement. */
        NO_AUTHORIZED_MOVEMENT,
        /** A proposed point left the selected corridor shoulder. */
        CORRIDOR_CONTAINMENT,
        /** A proposed point failed raw, B3, or B5 fit retention. */
        FIT_RETENTION,
        /** A proposed pass retained too little directly supported turn amplitude. */
        SUPPORTED_TURN_RETENTION,
        /** The source or proposed geometry self-intersects. */
        SELF_INTERSECTION,
        /** A proposed segment reverses against the source segment direction. */
        FOLDBACK,
        /** All bounded backtracking attempts for a pass were rejected. */
        BACKTRACK_LIMIT_REACHED
    }

    /**
     * Unit-explicit smoothing metrics.
     *
     * @param pointCount unchanged geometry point count
     * @param acceptedPassCount simultaneous smoothing passes accepted
     * @param backtrackCount rejected step sizes tried before acceptance/termination
     * @param protectedPointCount unique explicit and interval-endpoint protections
     * @param authorizedPointCount direct rows eligible to propose movement
     * @param containmentFailureCount rejected containment checks
     * @param fitRetentionFailureCount rejected raw/B3/B5 checks
     * @param displacementP50ProjectionUnits median point displacement in projection units
     * @param displacementP95ProjectionUnits 95th-percentile displacement in projection units
     * @param maximumDisplacementProjectionUnits maximum displacement in projection units
     * @param fitBefore mean raw/B3/B5 scalar fit before smoothing
     * @param fitAfter mean raw/B3/B5 scalar fit after smoothing
     * @param supportedTurnRetention minimum retained directly supported local-turn amplitude
     */
    public record Metrics(
        int pointCount,
        int acceptedPassCount,
        int backtrackCount,
        int protectedPointCount,
        int authorizedPointCount,
        int containmentFailureCount,
        int fitRetentionFailureCount,
        double displacementP50ProjectionUnits,
        double displacementP95ProjectionUnits,
        double maximumDisplacementProjectionUnits,
        double fitBefore,
        double fitAfter,
        double supportedTurnRetention
    ) {
        /** Validates counts and finite non-negative/dimensionless metrics. */
        public Metrics {
            if (pointCount < 0 || acceptedPassCount < 0 || backtrackCount < 0
                || protectedPointCount < 0 || authorizedPointCount < 0
                || containmentFailureCount < 0 || fitRetentionFailureCount < 0
                || !nonNegative(displacementP50ProjectionUnits)
                || !nonNegative(displacementP95ProjectionUnits)
                || !nonNegative(maximumDisplacementProjectionUnits)
                || !ratio(fitBefore) || !ratio(fitAfter) || !ratio(supportedTurnRetention)) {
                throw new IllegalArgumentException("Laplacian smoothing metrics are invalid");
            }
        }

        private static boolean nonNegative(double value) {
            return Double.isFinite(value) && value >= 0.0;
        }

        private static boolean ratio(double value) {
            return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
        }
    }
}
