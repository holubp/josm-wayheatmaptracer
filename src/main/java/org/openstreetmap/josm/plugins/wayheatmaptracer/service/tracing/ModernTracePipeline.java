package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.CandidateRankingPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedRefitter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image.DirectionalImageTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine;

/**
 * Runs one modern detached engine through common image refinement, final-geometry assessment,
 * and physical cross-engine ranking.
 *
 * <p>This class has no JOSM live-object dependency. Capture and edit-plan construction remain
 * separate ownership boundaries, which makes the numerical pipeline replayable and cancellable.</p>
 */
public final class ModernTracePipeline {
    /** Immutable pipeline options derived from one slide-time configuration. */
    public record Options(String fieldName, GeometryCleanupConfig cleanup,
            Set<Integer> protectedIndices, String sourceTier, int mappingPreference) {
        /** Validates and copies common final-processing settings. */
        public Options {
            if (fieldName == null || fieldName.isBlank() || cleanup == null
                    || protectedIndices == null || sourceTier == null || sourceTier.isBlank()
                    || mappingPreference < 0) {
                throw new IllegalArgumentException("Modern pipeline options are incomplete");
            }
            protectedIndices = Set.copyOf(protectedIndices);
        }
    }

    /** One final route and the common physical evidence used to rank it. */
    public record Route(TraceHypothesis hypothesis, FinalGeometryEvaluator.Result quality,
            ImageSupportedLocalCleanup.Status cleanupStatus, boolean geometryChanged) {
        /** Validates a final route with matching identity. */
        public Route {
            if (hypothesis == null || quality == null || cleanupStatus == null
                    || !quality.id().equals(hypothesis.id())) {
                throw new IllegalArgumentException("Modern final route is inconsistent");
            }
        }
    }

    /** Engine result plus final routes ordered by common physical quality. */
    public record Result(TraceHypothesisSet inference, List<Route> routes) {
        /** Copies the ranked route list. */
        public Result {
            if (inference == null || routes == null) {
                throw new IllegalArgumentException("Modern pipeline result is incomplete");
            }
            routes = List.copyOf(routes);
        }
    }

    private final TraceEngine corridorProposalEngine;

    /**
     * Creates a pipeline with an explicit detached A proposal engine.
     *
     * @param corridorProposalEngine detached A adapter used by A and Hybrid
     */
    public ModernTracePipeline(TraceEngine corridorProposalEngine) {
        if (corridorProposalEngine == null) {
            throw new IllegalArgumentException("A proposal engine is required");
        }
        this.corridorProposalEngine = corridorProposalEngine;
    }

    /** Runs inference and every common post-inference stage on immutable inputs. */
    public Result run(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network,
            Options options, CancellationProbe cancellation) {
        if (request == null || evidence == null || network == null || options == null
                || cancellation == null) {
            throw new IllegalArgumentException("Modern pipeline inputs are incomplete");
        }
        TraceEngine engine = engine(request.engine(), options.fieldName());
        TraceHypothesisSet inference = engine.trace(request, evidence, network, cancellation);
        if (inference.hypotheses().isEmpty()) {
            return new Result(inference, List.of());
        }
        ScalarEvidenceField scalar = evidence.fields().get(options.fieldName());
        if (scalar == null) {
            throw new IllegalArgumentException("Configured scalar field is unavailable");
        }
        double pitch = evidence.resolution().effectivePitchMeters();
        ImageCostField image = new ImageCostField(scalar, evidence.transform(),
                evidence.decisionRegion(), pitch);
        List<Route> routes = new ArrayList<>();
        for (TraceHypothesis hypothesis : inference.hypotheses()) {
            cancellation.checkpoint();
            routes.add(finalizeRoute(hypothesis, inference, image, evidence, options, pitch));
        }
        return new Result(inference, rank(routes, request.engine(), options));
    }

    private TraceEngine engine(TrackerMode mode, String fieldName) {
        return switch (mode) {
            case CORRIDOR_AWARE -> corridorProposalEngine;
            case PROBABILISTIC -> new ProbabilisticTraceEngine(fieldName);
            case HYBRID -> new HybridTraceEngine(corridorProposalEngine,
                    new ProbabilisticTraceEngine(fieldName));
            case DIRECTIONAL_IMAGE -> new DirectionalImageTraceEngine(fieldName);
            case LEGACY_V02 -> throw new IllegalArgumentException(
                    "Legacy tracing does not use the modern detached pipeline");
        };
    }

    private static Route finalizeRoute(TraceHypothesis source, TraceHypothesisSet inference,
            ImageCostField image, EvidenceSnapshot evidence, Options options, double pitch) {
        List<String> occurrenceIds = new ArrayList<>(source.points().size());
        Map<String, MetricPoint> assignments = new LinkedHashMap<>();
        for (int index = 0; index < source.points().size(); index++) {
            String id = source.id() + "@" + index;
            occurrenceIds.add(id);
            assignments.put(id, source.points().get(index));
        }
        Set<Integer> protectedIndices = withRouteEndpoints(options.protectedIndices(),
                source.points().size());
        ImageSupportedLocalCleanup.Mode mode = cleanupMode(options.cleanup().mode());
        ImageSupportedLocalCleanup.Result cleanup = new ImageSupportedLocalCleanup().clean(
                new ImageSupportedLocalCleanup.Request(occurrenceIds, source.points(),
                    protectedIndices, image, evidence.decisionRegion(), mode,
                    ImageSupportedRefitter.Config.defaults(pitch),
                    options.cleanup().simplificationDeviationMeters(), assignments, List.of()));
        List<MetricPoint> finalPoints = cleanup.status() == ImageSupportedLocalCleanup.Status.REJECTED
                ? source.points() : cleanup.points();
        boolean changed = !finalPoints.equals(source.points());
        List<ObservationOwnership> support = remapSupport(source, finalPoints);
        Map<String, Double> diagnostics = new LinkedHashMap<>(source.diagnostics());
        diagnostics.put("commonFinalProcessing", 1.0);
        diagnostics.put("cleanupChanged", changed ? 1.0 : 0.0);
        diagnostics.put("cleanupStatus", (double) cleanup.status().ordinal());
        TraceHypothesis finalized = new TraceHypothesis(source.id(), source.branchSignature(),
                finalPoints, support, source.objective(), source.posteriorProbability(), diagnostics);
        FinalGeometryEvaluator.Result quality = new FinalGeometryEvaluator().evaluate(
                new FinalGeometryEvaluator.Request(finalized.id(), finalized.points(), image, pitch,
                    protectedAfterReduction(source.points().size(), finalPoints.size(),
                        protectedIndices), List.of(), changed,
                    inference.status() == TraceHypothesisSet.Status.AMBIGUOUS,
                    inference.alternativesTruncated(),
                    inference.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT));
        return new Route(finalized, quality, cleanup.status(), changed);
    }

    private static ImageSupportedLocalCleanup.Mode cleanupMode(GeometryCleanupMode mode) {
        return switch (mode) {
            case NONE -> ImageSupportedLocalCleanup.Mode.OFF;
            case REDUCE_POINTS_ONLY -> ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY;
            case CONSTRAINED_SMOOTH_AND_REDUCE -> ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE;
        };
    }

    private static List<ObservationOwnership> remapSupport(TraceHypothesis source,
            List<MetricPoint> finalPoints) {
        if (finalPoints.size() == source.points().size()) {
            return source.support();
        }
        List<ObservationOwnership> result = new ArrayList<>(finalPoints.size());
        for (MetricPoint point : finalPoints) {
            int nearest = 0;
            double distance = Double.POSITIVE_INFINITY;
            for (int index = 0; index < source.points().size(); index++) {
                double candidate = point.distanceTo(source.points().get(index));
                if (candidate < distance) {
                    nearest = index;
                    distance = candidate;
                }
            }
            result.add(source.support().get(nearest));
        }
        return List.copyOf(result);
    }

    private static Set<Integer> protectedAfterReduction(int beforeCount, int afterCount,
            Set<Integer> protectedBefore) {
        Set<Integer> result = new LinkedHashSet<>();
        if (protectedBefore.contains(0)) {
            result.add(0);
        }
        if (protectedBefore.contains(beforeCount - 1)) {
            result.add(afterCount - 1);
        }
        return Set.copyOf(result);
    }

    private static Set<Integer> withRouteEndpoints(Set<Integer> protectedIndices, int pointCount) {
        Set<Integer> result = new LinkedHashSet<>(protectedIndices);
        result.add(0);
        result.add(pointCount - 1);
        return Set.copyOf(result);
    }

    private static List<Route> rank(List<Route> routes, TrackerMode engine, Options options) {
        CandidateRankingPolicy ranking = new CandidateRankingPolicy();
        Map<String, Route> byId = new LinkedHashMap<>();
        List<CandidateRankingPolicy.Candidate> candidates = new ArrayList<>();
        for (Route route : routes) {
            FinalGeometryEvaluator.Result quality = route.quality();
            byId.put(route.hypothesis().id(), route);
            long hard = quality.findings().stream()
                    .filter(finding -> finding.severity() == FinalGeometryEvaluator.Severity.HARD_BLOCK)
                    .count();
            int branch = quality.has(FinalGeometryEvaluator.FindingCode.BRANCH_SWITCH)
                    || quality.has(FinalGeometryEvaluator.FindingCode.AMBIGUOUS_BRANCH) ? 1 : 0;
            int severe = Math.toIntExact(Math.min(Integer.MAX_VALUE,
                    quality.findings().size() - branch));
            CandidateRankingPolicy.PhysicalQuality physical = new CandidateRankingPolicy.PhysicalQuality(
                    quality.directlySupportedLengthMeters() + 1.0e-6 >= quality.totalLengthMeters(),
                    hard > 0, severe, branch, 0, quality.directlySupportedLengthMeters(),
                    quality.totalLengthMeters(), quality.worstUnsupportedSpanMeters(),
                    quality.meanImageCenterCost(), 0.0, quality.bendPreservingRoughness(),
                    options.mappingPreference(), route.hypothesis().diagnostics()
                        .getOrDefault("guideReliability", 0.0));
            candidates.add(new CandidateRankingPolicy.Candidate(route.hypothesis().id(), engine,
                    options.sourceTier(), physical, route.hypothesis().objective(),
                    route.hypothesis().points().size(), route.geometryChanged(),
                    route.hypothesis().branchSignature(), route.geometryChanged()));
        }
        List<Route> ordered = new ArrayList<>();
        for (CandidateRankingPolicy.Candidate candidate : ranking.rank(candidates)) {
            ordered.add(byId.get(candidate.id()));
        }
        return List.copyOf(ordered);
    }
}
