package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Common preview representation of actual sequential Hybrid output; native B runs remain separate. */
public final class LegacyBModernResultAdapter {
    private LegacyBModernResultAdapter() { }

    /**
     * Carries each exact assembled assessment into ordinary preview/Apply without rerunning an
     * engine. The compatibility objective is the factual Legacy parent's score, diagnostic only;
     * no whole-way B posterior, objective or execution receipt is synthesized.
     */
    public static ModernTracePipeline.Result adapt(LegacyBProductionRunner.Result result,
            LegacyBProductionRunner.Input input, ModernTracePipeline.Options options,
            CancellationProbe cancellation) {
        Objects.requireNonNull(result); Objects.requireNonNull(input);
        Objects.requireNonNull(options); Objects.requireNonNull(cancellation);
        if (!options.fieldName().equals(input.fieldName())) {
            throw new IllegalArgumentException("Hybrid preview must retain its assessed image field");
        }
        var image = ImageCostField.fromEvidence(input.evidence(), input.fieldName());
        var routes = new ArrayList<ModernTracePipeline.Route>();
        var hypotheses = new ArrayList<TraceHypothesis>();
        for (var assessed : result.assessments()) {
            cancellation.checkpoint();
            var candidate = assessed.candidate();
            var parent = result.alternatives().stream().filter(alternative ->
                    alternative.id().equals(candidate.parentSeedId())).findFirst().orElseThrow(() ->
                        new IllegalArgumentException("Hybrid preview lost its actual Legacy parent"));
            var seed = parent.seed().orElseThrow(() ->
                    new IllegalArgumentException("Rejected Legacy output cannot own an assembled preview"));
            var support = support(candidate.points(), candidate.originalToVertex(), input, image, cancellation);
            var rawSupport = support(seed.points(), seed.originalToVertex(), input, image, cancellation);
            boolean refined = candidate.sections().stream().anyMatch(section -> section.source() == LegacyBPipeline.Source.B);
            Map<String, Double> diagnostics = Map.of("legacyNativeScore", parent.score(),
                    "legacyParentRank", (double) parent.nativeRank(), "hasBRefinement", refined ? 1.0 : 0.0);
            var raw = new TraceHypothesis(candidate.id(), seed.id(), seed.points(),
                    rawSupport, parent.score(), OptionalDouble.empty(), diagnostics);
            var finalHypothesis = new TraceHypothesis(candidate.id(), candidate.id(), candidate.points(),
                    support, parent.score(), OptionalDouble.empty(), diagnostics);
            var ownership = new LinkedHashMap<FinalRoutePointId, ObservationOwnership>();
            for (int vertex = 0; vertex < assessed.pointIds().size(); vertex++) {
                ownership.put(assessed.pointIds().get(vertex), support.get(vertex));
            }
            routes.add(new ModernTracePipeline.Route(raw, finalHypothesis, assessed.pointIds(),
                    assessed.assignments(), ownership, assessed.quality(), ImageSupportedLocalCleanup.Status.SKIPPED,
                    !candidate.points().equals(seed.points())));
            hypotheses.add(finalHypothesis);
        }
        long states = 0, transitions = 0;
        boolean ambiguous = false;
        for (var section : result.batch().sections()) {
            if (section.execution().isPresent()) {
                var nativeResult = section.execution().orElseThrow().run().result();
                states = Math.addExact(states, nativeResult.evaluatedStates());
                transitions = Math.addExact(transitions, nativeResult.evaluatedTransitions());
                ambiguous |= nativeResult.status() == TraceHypothesisSet.Status.AMBIGUOUS;
            }
        }
        var status = hypotheses.isEmpty() ? TraceHypothesisSet.Status.NO_ROUTE
                : result.batch().status() == LegacyBPipeline.Status.RESOURCE_LIMIT
                        ? TraceHypothesisSet.Status.RESOURCE_LIMIT
                        : ambiguous || result.batch().status() == LegacyBPipeline.Status.PARTIAL
                                ? TraceHypothesisSet.Status.AMBIGUOUS : TraceHypothesisSet.Status.COMPLETE;
        var inference = new TraceHypothesisSet(TrackerMode.LEGACY_THEN_B, hypotheses, status,
                result.batch().truncated(), states, transitions,
                "Actual Legacy-to-B composition; reported states and transitions count native B sections only");
        return new ModernTracePipeline.Result(inference,
                ModernTracePipeline.rankRoutes(routes, TrackerMode.LEGACY_THEN_B, options));
    }

    private static List<ObservationOwnership> support(List<MetricPoint> points,
            Map<Integer, Integer> mapping, LegacyBProductionRunner.Input input, ImageCostField image,
            CancellationProbe cancellation) {
        var protectedVertices = new java.util.HashSet<Integer>();
        for (var entry : mapping.entrySet()) {
            int original = entry.getKey();
            int local = original - input.request().selectedRange().firstIndex();
            if (input.occurrences().get(local).fixed() || input.frozenRanges().stream()
                    .anyMatch(range -> original >= range.firstIndex() && original <= range.lastIndex())) {
                protectedVertices.add(entry.getValue());
            }
        }
        var result = new ArrayList<ObservationOwnership>();
        for (int vertex = 0; vertex < points.size(); vertex++) {
            cancellation.checkpoint();
            if (protectedVertices.contains(vertex)) {
                result.add(ObservationOwnership.FIXED_TOPOLOGY_ONLY);
                continue;
            }
            var before = points.get(Math.max(0, vertex - 1));
            var after = points.get(Math.min(points.size() - 1, vertex + 1));
            var tangent = new MetricPoint(after.xMeters() - before.xMeters(), after.yMeters() - before.yMeters());
            result.add(before.equals(after) ? ObservationOwnership.NO_RASTER
                    : image.sampleRoute(points.get(vertex), tangent).map(ImageCostField.RouteSample::ownership)
                            .orElse(ObservationOwnership.NO_RASTER));
        }
        return List.copyOf(result);
    }
}
