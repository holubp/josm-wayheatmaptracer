package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Shared field writers for unchanged root fingerprints and output-component evidence. */
final class ReplayOutputCanonical {
    private ReplayOutputCanonical() { }

    static void writeHypothesis(DataOutputStream data, TraceHypothesis hypothesis)
            throws IOException {
        writeHypothesisIdentityAndScore(data, hypothesis);
        writeHypothesisGeometry(data, hypothesis);
        writeHypothesisSupport(data, hypothesis);
        writeHypothesisDiagnostics(data, hypothesis);
    }

    static void writeHypothesisIdentityAndScore(DataOutputStream data,
            TraceHypothesis hypothesis) throws IOException {
        ScalarReplayFingerprint.writeString(data, hypothesis.id());
        ScalarReplayFingerprint.writeString(data, hypothesis.branchSignature());
        data.writeLong(Double.doubleToLongBits(hypothesis.objective()));
        data.writeBoolean(hypothesis.posteriorProbability().isPresent());
        if (hypothesis.posteriorProbability().isPresent()) {
            data.writeLong(Double.doubleToLongBits(hypothesis.posteriorProbability().getAsDouble()));
        }
    }

    static void writeHypothesisGeometry(DataOutputStream data, TraceHypothesis hypothesis)
            throws IOException {
        data.writeInt(hypothesis.points().size());
        for (MetricPoint point : hypothesis.points()) writePoint(data, point);
    }

    static void writeHypothesisSupport(DataOutputStream data, TraceHypothesis hypothesis)
            throws IOException {
        data.writeInt(hypothesis.support().size());
        for (ObservationOwnership ownership : hypothesis.support()) {
            if (ownership == null) throw new IllegalArgumentException("scalar-output-invalid");
            ScalarReplayFingerprint.writeString(data, ownership.name());
        }
    }

    static void writeHypothesisDiagnostics(DataOutputStream data, TraceHypothesis hypothesis)
            throws IOException {
        Map<String, Double> diagnostics = new TreeMap<>(hypothesis.diagnostics());
        data.writeInt(diagnostics.size());
        for (Map.Entry<String, Double> entry : diagnostics.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || !Double.isFinite(entry.getValue())) {
                throw new IllegalArgumentException("scalar-output-invalid");
            }
            ScalarReplayFingerprint.writeString(data, entry.getKey());
            data.writeLong(Double.doubleToLongBits(entry.getValue()));
        }
    }

    static void writeRoute(DataOutputStream data, ModernTracePipeline.Route route)
            throws IOException {
        writeHypothesis(data, route.rawHypothesis());
        writeHypothesis(data, route.hypothesis());
        data.writeInt(route.pointIds().size());
        for (FinalRoutePointId id : route.pointIds()) {
            writePointId(data, id);
            writePoint(data, route.assignments().get(id));
            ObservationOwnership ownership = route.sourceOwnership().get(id);
            if (ownership == null) throw new IllegalArgumentException("final-output-invalid");
            ScalarReplayFingerprint.writeString(data, ownership.name());
        }
        writeQuality(data, route.quality());
        ScalarReplayFingerprint.writeString(data, route.cleanupStatus().name());
        data.writeBoolean(route.geometryChanged());
    }

    static void writePointId(DataOutputStream data, FinalRoutePointId id) throws IOException {
        if (id instanceof ExistingWayNodeOccurrence existing) {
            data.writeByte(0);
            writePrimitiveKey(data, existing.wayKey());
            writePrimitiveKey(data, existing.nodeKey());
            data.writeInt(existing.originalOccurrenceIndex());
        } else if (id instanceof GeneratedCandidatePoint generated) {
            data.writeByte(1);
            ScalarReplayFingerprint.writeString(data, generated.candidateId());
            data.writeInt(generated.originalPointIndex());
        } else {
            throw new IllegalArgumentException("final-output-invalid");
        }
    }

    static void writePrimitiveKey(DataOutputStream data, PrimitiveKey key) throws IOException {
        if (key == null) throw new IllegalArgumentException("final-output-invalid");
        ScalarReplayFingerprint.writeString(data, key.type().name());
        ScalarReplayFingerprint.writeString(data, key.identityKind().name());
        data.writeLong(key.id());
    }

    static void writePoint(DataOutputStream data, MetricPoint point) throws IOException {
        if (point == null) throw new IllegalArgumentException("final-output-invalid");
        writeFiniteDouble(data, point.xMeters());
        writeFiniteDouble(data, point.yMeters());
    }

    static void writeQuality(DataOutputStream data, FinalGeometryEvaluator.Result quality)
            throws IOException {
        if (quality == null || quality.disposition() == null || quality.findings() == null) {
            throw new IllegalArgumentException("final-output-invalid");
        }
        ScalarReplayFingerprint.writeString(data, quality.id());
        ScalarReplayFingerprint.writeString(data, quality.disposition().name());
        data.writeInt(quality.findings().size());
        for (FinalGeometryEvaluator.Finding finding : quality.findings()) {
            if (finding == null || finding.code() == null || finding.severity() == null) {
                throw new IllegalArgumentException("final-output-invalid");
            }
            ScalarReplayFingerprint.writeString(data, finding.code().name());
            ScalarReplayFingerprint.writeString(data, finding.severity().name());
            data.writeInt(finding.firstVertex());
            data.writeInt(finding.lastVertex());
            writeFiniteDouble(data, finding.amplitudeMeters());
        }
        writeFiniteDouble(data, quality.totalLengthMeters());
        writeFiniteDouble(data, quality.directlySupportedLengthMeters());
        writeFiniteDouble(data, quality.worstUnsupportedSpanMeters());
        double cost = quality.meanImageCenterCost();
        boolean unavailable = quality.has(FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY);
        if (unavailable != (cost == Double.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("final-output-invalid");
        }
        if (unavailable) data.writeLong(Double.doubleToLongBits(Double.POSITIVE_INFINITY));
        else writeFiniteDouble(data, cost);
        writeFiniteDouble(data, quality.bendPreservingRoughness());
    }

    static void writeFiniteDouble(DataOutputStream data, double value) throws IOException {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("final-output-invalid");
        data.writeLong(Double.doubleToLongBits(value));
    }
}
