package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;

/** Qualified same-image Engine A geometry used only as a capped structural prior in Engine B. */
public final class ProbabilisticStructuralGuide {
    /** Maximum unweighted Huber contribution at one profile state. */
    public static final double MAXIMUM_COST = 4.0;
    private static final int MINIMUM_DIRECT_PROFILES = 7;
    private static final double MINIMUM_DIRECT_SPAN_METERS = 10.0;

    private final List<Section> sections;

    private ProbabilisticStructuralGuide(List<Section> sections) {
        this.sections = List.copyOf(sections);
    }

    /**
     * Extracts reliable direct A sections aligned to the immutable request chainage.
     * Ambiguous alternatives, aggregate defects, junction ambiguity, and truncated A runs do not guide B.
     */
    public static Optional<ProbabilisticStructuralGuide> qualified(
            TraceRequest request, TraceHypothesisSet corridor, NetworkSnapshot network) {
        if (request == null || corridor == null || network == null
                || request.engine() != org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.HYBRID
                || corridor.engine() != org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.CORRIDOR_AWARE
                || corridor.status() != TraceHypothesisSet.Status.COMPLETE
                || corridor.alternativesTruncated() || corridor.hypotheses().size() != 1
                || hasInternalProtectedOccurrence(request, network)) {
            return Optional.empty();
        }
        TraceHypothesis hypothesis = corridor.hypotheses().get(0);
        int profileCount = request.profileChainage().cumulativeGroundMeters().size();
        if (hypothesis.points().size() != profileCount || hypothesis.support().size() != profileCount
                || notExplicitlyClear(hypothesis, "scaleConflictFraction")
                || notExplicitlyClear(hypothesis, "localDefect")
                || notExplicitlyClear(hypothesis, "junctionAmbiguity")
                || notExplicitlyClear(hypothesis, "competingPersistentMode")) {
            return Optional.empty();
        }
        List<Section> sections = new ArrayList<>();
        int start = -1;
        for (int index = 0; index <= profileCount; index++) {
            boolean direct = index < profileCount
                && hypothesis.support().get(index) == ObservationOwnership.DIRECT_TWO_SIDED;
            if (direct && start < 0) {
                start = index;
            } else if (!direct && start >= 0) {
                addQualifiedSection(sections, hypothesis, request, start, index - 1);
                start = -1;
            }
        }
        return sections.isEmpty() ? Optional.empty()
            : Optional.of(new ProbabilisticStructuralGuide(sections));
    }

    /** Adds bounded guide costs without changing scalar evidence or ownership. */
    public InferenceProfile apply(InferenceProfile profile, double sourcePitchMeters) {
        if (profile == null || !Double.isFinite(sourcePitchMeters) || sourcePitchMeters <= 0.0) {
            throw new IllegalArgumentException("Inference profile is required");
        }
        double[] costs = new double[profile.cells().size()];
        if (profile.ownership() != ObservationOwnership.DIRECT_TWO_SIDED
                || profile.entirelyMissing()) {
            return profile.withStructuralGuideCosts(costs);
        }
        Section section = sections.stream().filter(candidate -> candidate.contains(profile.chainageMeters()))
            .findFirst().orElse(null);
        if (section == null) {
            return profile.withStructuralGuideCosts(costs);
        }
        for (int state = 0; state < costs.length; state++) {
            double normalized = distanceToPolyline(profile.point(state), section.points())
                / sourcePitchMeters;
            costs[state] = Math.min(MAXIMUM_COST, EvidenceModelParameters.huber(normalized));
        }
        return profile.withStructuralGuideCosts(costs);
    }

    /** Returns the number of independently qualified direct sections. */
    public int sectionCount() {
        return sections.size();
    }

    private static void addQualifiedSection(List<Section> sections, TraceHypothesis hypothesis,
            TraceRequest request, int first, int last) {
        List<Double> chainage = request.profileChainage().cumulativeGroundMeters();
        if (last - first + 1 < MINIMUM_DIRECT_PROFILES
                || chainage.get(last) - chainage.get(first) < MINIMUM_DIRECT_SPAN_METERS) {
            return;
        }
        sections.add(new Section(chainage.get(first), chainage.get(last),
            hypothesis.points().subList(first, last + 1)));
    }

    private static boolean hasInternalProtectedOccurrence(TraceRequest request, NetworkSnapshot network) {
        if (!(network.primitives().get(request.selectedWayKey()) instanceof DetachedWay way)
                || request.selectedRange().lastIndex() >= way.nodeKeys().size()) {
            return true;
        }
        for (int index = request.selectedRange().firstIndex() + 1;
                index < request.selectedRange().lastIndex(); index++) {
            if (network.closure().protectedExistingNodeKeys().contains(way.nodeKeys().get(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean notExplicitlyClear(TraceHypothesis hypothesis, String key) {
        Double value = hypothesis.diagnostics().get(key);
        return value == null || !Double.isFinite(value) || value != 0.0;
    }

    private static double distanceToPolyline(MetricPoint point, List<MetricPoint> polyline) {
        double result = Double.POSITIVE_INFINITY;
        for (int index = 1; index < polyline.size(); index++) {
            result = Math.min(result, distanceToSegment(point, polyline.get(index - 1), polyline.get(index)));
        }
        return result;
    }

    private static double distanceToSegment(MetricPoint point, MetricPoint start, MetricPoint end) {
        double dx = end.xMeters() - start.xMeters();
        double dy = end.yMeters() - start.yMeters();
        double lengthSquared = dx * dx + dy * dy;
        if (!(lengthSquared > 0.0)) {
            return point.distanceTo(start);
        }
        double fraction = ((point.xMeters() - start.xMeters()) * dx
            + (point.yMeters() - start.yMeters()) * dy) / lengthSquared;
        double bounded = Math.max(0.0, Math.min(1.0, fraction));
        return point.distanceTo(new MetricPoint(start.xMeters() + bounded * dx,
            start.yMeters() + bounded * dy));
    }

    private record Section(double firstChainageMeters, double lastChainageMeters,
            List<MetricPoint> points) {
        Section {
            points = List.copyOf(points);
        }

        boolean contains(double chainageMeters) {
            return chainageMeters >= firstChainageMeters - 1.0e-9
                && chainageMeters <= lastChainageMeters + 1.0e-9;
        }
    }
}
