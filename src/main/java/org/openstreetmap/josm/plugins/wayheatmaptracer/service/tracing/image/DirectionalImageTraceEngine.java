package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceEngine;

/** Direct image-engine adapter over immutable detached snapshots only. */
public final class DirectionalImageTraceEngine implements TraceEngine {
    private final String fieldName;

    /** Creates the engine for one named scalar field in the common snapshot raster frame. */
    public DirectionalImageTraceEngine(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("Directional image field name is required");
        }
        this.fieldName = fieldName;
    }

    @Override
    public TraceHypothesisSet trace(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network, CancellationProbe cancellation) {
        try {
            validate(request, evidence, network);
            cancellation.checkpoint();
            DirectionalImageSearchResult result = new DirectionalImageSearch().solve(
                new DirectionalImageSearchProblem(evidence, fieldName, selectedEndpoints(request, evidence, network),
                    request.budgets(), request.permissions(), DirectionalImageSearchProblem.Scope.ORDINARY, false),
                cancellation);
            return hypotheses(request, evidence.fields().get(fieldName), evidence, result);
        } catch (CancellationException exception) {
            return new TraceHypothesisSet(TrackerMode.DIRECTIONAL_IMAGE, List.of(),
                TraceHypothesisSet.Status.CANCELLED, false, 0, 0, "cancelled");
        }
    }

    private static TraceHypothesisSet hypotheses(TraceRequest request, ScalarEvidenceField field,
        EvidenceSnapshot evidence, DirectionalImageSearchResult result) {
        List<TraceHypothesis> hypotheses = new ArrayList<>();
        for (int index = 0; index < result.paths().size(); index++) {
            DirectionalImagePath path = result.paths().get(index);
            Map<String, Double> diagnostics = new LinkedHashMap<>();
            diagnostics.put("gridPitchMeters", request.evidenceResolution().effectivePitchMeters() / 2.0);
            diagnostics.put("widerAttempt", result.widerAttempt() ? 1.0 : 0.0);
            diagnostics.put("acquisitionAttempts", 0.0);
            diagnostics.put("headingCount", (double) path.headingsRadians().size());
            hypotheses.add(new TraceHypothesis("directional-image-" + index,
                path.branchSignature() + "-" + index, path.points(), support(path.points(), evidence, field),
                path.objective(), 0.0, diagnostics));
        }
        TraceHypothesisSet.Status status = switch (result.status()) {
            case COMPLETE -> TraceHypothesisSet.Status.COMPLETE;
            case AMBIGUOUS -> TraceHypothesisSet.Status.AMBIGUOUS;
            case NO_ROUTE -> TraceHypothesisSet.Status.NO_ROUTE;
            case RESOURCE_LIMIT -> TraceHypothesisSet.Status.RESOURCE_LIMIT;
        };
        return new TraceHypothesisSet(TrackerMode.DIRECTIONAL_IMAGE, hypotheses, status,
            result.alternativesTruncated(), result.evaluatedStates(), result.evaluatedTransitions(), result.explanation());
    }

    private static List<ObservationOwnership> support(List<MetricPoint> points, EvidenceSnapshot evidence,
        ScalarEvidenceField field) {
        List<ObservationOwnership> support = new ArrayList<>(points.size());
        for (MetricPoint point : points) {
            if (!evidence.routePositionAuthorized(point)) {
                support.add(ObservationOwnership.NO_RASTER);
                continue;
            }
            var raster = evidence.transform().metricToPixelCenter(point);
            int x = (int) Math.round(raster.x());
            int y = (int) Math.round(raster.y());
            OptionalDouble intensity = x < 0 || x >= field.width() || y < 0 || y >= field.height()
                ? OptionalDouble.empty() : field.sample(x, y);
            support.add(intensity.isEmpty() ? ObservationOwnership.NO_RASTER
                : intensity.getAsDouble() > 1e-3 ? ObservationOwnership.DIRECT_TWO_SIDED
                    : ObservationOwnership.NO_SIGNAL_VALID_RASTER);
        }
        return List.copyOf(support);
    }

    private static List<MetricPoint> selectedEndpoints(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network) {
        DetachedPrimitive primitive = network.primitives().get(request.selectedWayKey());
        if (!(primitive instanceof DetachedWay way) || request.selectedRange().size() < 2
            || request.selectedRange().lastIndex() >= way.nodeKeys().size()) {
            throw new IllegalArgumentException("Selected occurrence range is absent from the network snapshot");
        }
        return List.of(point(way.nodeKeys().get(request.selectedRange().firstIndex()), network, evidence),
            point(way.nodeKeys().get(request.selectedRange().lastIndex()), network, evidence));
    }

    private static MetricPoint point(PrimitiveKey key, NetworkSnapshot network, EvidenceSnapshot evidence) {
        DetachedPrimitive primitive = network.primitives().get(key);
        if (!(primitive instanceof DetachedNode node)) {
            throw new IllegalArgumentException("Selected way node is absent from the network snapshot");
        }
        return evidence.coordinateFrame().toMetric(node.coordinate());
    }

    private static void validate(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network) {
        if (request == null || evidence == null || network == null || request.engine() != TrackerMode.DIRECTIONAL_IMAGE
            || !request.evidenceSnapshotId().equals(evidence.snapshotId())
            || !request.evidenceContentHash().equals(evidence.canonicalHash())
            || !request.networkSnapshotId().equals(network.snapshotId())
            || !request.networkContentHash().equals(network.canonicalHash())
            || !request.evidenceResolution().equals(evidence.resolution()) || network.role() != SnapshotRole.CAPTURED_BEFORE) {
            throw new IllegalArgumentException("Directional image request does not match immutable snapshots");
        }
    }
}
