package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;

/** Strict physical and execution-counter gates for production replay results. */
final class ProductionReplayValidator {
    private static final double ENDPOINT_TOLERANCE_METERS = 1.0e-6;

    private ProductionReplayValidator() {
    }

    static void validateScalar(Format15ReplayRunner.Result result) {
        TraceHypothesisSet inference = result.inference();
        if (inference.status() == TraceHypothesisSet.Status.CANCELLED
                || inference.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT
                || inference.status() == TraceHypothesisSet.Status.NO_ROUTE
                || inference.hypotheses().isEmpty()
                || inference.alternativesTruncated()
                || inference.evaluatedStates() <= 0
                || inference.evaluatedTransitions() <= 0) {
            throw new ReplayMismatchException("scalar-production-invariant-failed");
        }
    }

    static void validateFinal(Format15ReplayRunner.Result result, FrozenReplayInput input,
            Format15CorpusManifest.ExpectedRoute expected) {
        validateScalar(result);
        if (result.routes().isEmpty()) {
            throw new ReplayMismatchException("final-route-missing");
        }
        var route = result.routes().get(0);
        var quality = route.quality();
        List<MetricPoint> points = route.hypothesis().points();
        if (quality.disposition() == FinalGeometryEvaluator.Disposition.HARD_BLOCKED
                || !(quality.totalLengthMeters() > 0.0)
                || !(quality.directlySupportedLengthMeters() > 0.0)
                || quality.totalLengthMeters() + 1.0e-9 < expected.minimumLengthMeters()
                || quality.directlySupportedLengthMeters() + 1.0e-9
                        < expected.minimumDirectSupportMeters()) {
            throw new ReplayMismatchException("final-physical-invariant-failed");
        }
        List<MetricPoint> endpoints = capturedEndpoints(input);
        if (points.get(0).distanceTo(endpoints.get(0)) > ENDPOINT_TOLERANCE_METERS
                || points.get(points.size() - 1).distanceTo(endpoints.get(1))
                        > ENDPOINT_TOLERANCE_METERS) {
            throw new ReplayMismatchException("fixed-endpoint-invariant-failed");
        }
        if (expected.interiorMeanYMinimum().isPresent()
                || expected.interiorMeanYMaximum().isPresent()) {
            if (points.size() < 3) {
                throw new ReplayMismatchException("interior-route-invariant-failed");
            }
            double mean = points.subList(1, points.size() - 1).stream()
                    .mapToDouble(MetricPoint::yMeters).average().orElse(Double.NaN);
            if (!Double.isFinite(mean)
                    || (expected.interiorMeanYMinimum().isPresent()
                            && mean < expected.interiorMeanYMinimum().getAsDouble())
                    || (expected.interiorMeanYMaximum().isPresent()
                            && mean > expected.interiorMeanYMaximum().getAsDouble())) {
                throw new ReplayMismatchException("interior-route-invariant-failed");
            }
        }
    }

    private static List<MetricPoint> capturedEndpoints(FrozenReplayInput input) {
        DetachedPrimitive primitive = input.network().primitives()
                .get(input.request().selectedWayKey());
        if (!(primitive instanceof DetachedWay way)
                || input.request().selectedRange().lastIndex() >= way.nodeKeys().size()) {
            throw new ReplayMismatchException("selected-way-input-missing");
        }
        DetachedPrimitive first = input.network().primitives().get(
                way.nodeKeys().get(input.request().selectedRange().firstIndex()));
        DetachedPrimitive last = input.network().primitives().get(
                way.nodeKeys().get(input.request().selectedRange().lastIndex()));
        if (!(first instanceof DetachedNode firstNode)
                || !(last instanceof DetachedNode lastNode)) {
            throw new ReplayMismatchException("selected-endpoint-input-missing");
        }
        return List.of(input.evidence().coordinateFrame().toMetric(firstNode.coordinate()),
                input.evidence().coordinateFrame().toMetric(lastNode.coordinate()));
    }
}
