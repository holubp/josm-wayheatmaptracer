package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;

/**
 * Applies read-only nearby-way compatibility as a soft candidate-ranking prior.
 */
public final class CorridorAssignmentService {
    /** Calibrated physical reference that keeps contextual displacement costs width-independent. */
    private static final double ASSIGNMENT_NORMALIZATION_METERS = 7.01;
    /**
     * Creates a stateless corridor assignment service.
     */
    public CorridorAssignmentService() {
        // Stateless service.
    }

    /**
     * Scores candidate geometry against the selected way and reserved nearby corridors.
     *
     * @param candidates projected corridor candidates
     * @param selectedWay selected OSM way
     * @param selectedGeometry original selected-segment geometry
     * @param contexts nearby parallel OSM ways
     * @return adjusted candidates and assignment diagnostics
     */
    public AssignmentResult assign(
        List<CenterlineCandidate> candidates,
        Way selectedWay,
        List<EastNorth> selectedGeometry,
        List<ParallelWayContext> contexts
    ) {
        if (contexts.isEmpty()) {
            return new AssignmentResult(candidates, List.of());
        }
        double scale = ASSIGNMENT_NORMALIZATION_METERS;
        List<CenterlineCandidate> adjusted = new ArrayList<>(candidates.size());
        List<AssignmentDecision> decisions = new ArrayList<>(candidates.size());
        for (CenterlineCandidate candidate : candidates) {
            if (candidate.eastNorthPoints().isEmpty()) {
                adjusted.add(candidate);
                continue;
            }
            double sourceDistance = meanDistance(candidate.eastNorthPoints(), selectedGeometry);
            double reservationPenalty = 0.0;
            List<Long> reservedBy = new ArrayList<>();
            for (ParallelWayContext context : contexts) {
                double candidateDistance = meanDistance(candidate.eastNorthPoints(), context.geometry());
                double reservationRadius = Math.max(2.0, context.meanDistanceMeters() * 0.45);
                double compatibility = tagCompatibility(selectedWay, context);
                if (candidateDistance < reservationRadius) {
                    reservationPenalty += (reservationRadius - candidateDistance) * compatibility;
                    reservedBy.add(context.wayId());
                }
            }
            double normalizedCost = (sourceDistance + reservationPenalty) / scale;
            CenterlineCandidate scored = candidate.withScore(candidate.score() - normalizedCost * 0.20)
                .withId(candidate.id() + "/osm-context");
            adjusted.add(scored);
            decisions.add(new AssignmentDecision(candidate.id(), sourceDistance, reservationPenalty,
                normalizedCost, reservedBy));
        }
        return new AssignmentResult(adjusted, decisions);
    }

    private double meanDistance(List<EastNorth> points, List<EastNorth> geometry) {
        return points.stream().mapToDouble(point -> ParallelWayContextResolver.distanceToPolyline(point, geometry))
            .average().orElse(Double.POSITIVE_INFINITY);
    }

    private double tagCompatibility(Way selected, ParallelWayContext context) {
        String selectedHighway = selected.get("highway");
        String otherHighway = context.tags().get("highway");
        double compatibility = selectedHighway != null && selectedHighway.equals(otherHighway) ? 1.0 : 0.65;
        if ("yes".equals(selected.get("oneway")) && "yes".equals(context.tags().get("oneway"))) {
            compatibility += 0.20;
        }
        return compatibility;
    }

    /**
     * Candidate assignment output.
     *
     * @param candidates softly re-ranked candidates
     * @param decisions per-candidate assignment evidence
     */
    public record AssignmentResult(List<CenterlineCandidate> candidates, List<AssignmentDecision> decisions) {
        /** Makes result collections immutable. */
        public AssignmentResult {
            candidates = List.copyOf(candidates);
            decisions = List.copyOf(decisions);
        }
    }

    /**
     * Redacted assignment evidence for one candidate.
     *
     * @param candidateId original candidate identifier
     * @param sourceDistanceMeters mean displacement from selected geometry
     * @param reservationPenaltyMeters contextual corridor reservation penalty
     * @param normalizedCost final assignment cost
     * @param reservedByWayIds contextual ways whose corridor the candidate approached
     */
    public record AssignmentDecision(
        String candidateId,
        double sourceDistanceMeters,
        double reservationPenaltyMeters,
        double normalizedCost,
        List<Long> reservedByWayIds
    ) {
        /** Makes referenced way identifiers immutable. */
        public AssignmentDecision {
            reservedByWayIds = List.copyOf(reservedByWayIds);
        }
    }
}
