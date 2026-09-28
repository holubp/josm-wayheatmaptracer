package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Canonical, bounded final-geometry replay output fingerprint. */
final class FinalReplayFingerprint {
    static final int SCHEMA_VERSION = 1;
    private static final int MAX_ROUTES = 4_096;
    private static final int MAX_AGGREGATE_ITEMS = 500_000;

    private FinalReplayFingerprint() {
    }

    static String sha256(Format15ReplayRunner.Result output) {
        if (output == null || output.level()
                != org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.FINAL_GEOMETRY
                || output.routes().size() > MAX_ROUTES) {
            throw new IllegalArgumentException("final-output-invalid");
        }
        long items = aggregateItems(output);
        if (items > MAX_AGGREGATE_ITEMS) {
            throw new IllegalArgumentException("final-output-budget");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream data = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                data.writeInt(SCHEMA_VERSION);
                ScalarReplayFingerprint.writeString(data,
                    ScalarReplayFingerprint.sha256(output.inference()));
                data.writeInt(output.routes().size());
                for (ModernTracePipeline.Route route : output.routes()) {
                    writeRoute(data, route);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Final digest stream failed", exception);
        }
    }

    private static long aggregateItems(Format15ReplayRunner.Result output) {
        long items = hypothesisItems(output.inference().hypotheses());
        for (ModernTracePipeline.Route route : output.routes()) {
            if (route == null) {
                throw new IllegalArgumentException("final-output-invalid");
            }
            items = Math.addExact(items, hypothesisItems(List.of(
                route.rawHypothesis(), route.hypothesis())));
            items = Math.addExact(items, route.pointIds().size());
            items = Math.addExact(items, route.assignments().size());
            items = Math.addExact(items, route.sourceOwnership().size());
            items = Math.addExact(items, route.quality().findings().size());
            if (items > MAX_AGGREGATE_ITEMS) {
                throw new IllegalArgumentException("final-output-budget");
            }
        }
        return items;
    }

    private static long hypothesisItems(java.util.List<TraceHypothesis> hypotheses) {
        long items = hypotheses.size();
        for (TraceHypothesis hypothesis : hypotheses) {
            if (hypothesis == null) {
                throw new IllegalArgumentException("final-output-invalid");
            }
            items = checkedAdd(items, hypothesis.points().size());
            items = checkedAdd(items, hypothesis.support().size());
            items = checkedAdd(items, hypothesis.diagnostics().size());
        }
        return items;
    }

    private static long checkedAdd(long current, int added) {
        long result = Math.addExact(current, added);
        if (result > MAX_AGGREGATE_ITEMS) {
            throw new IllegalArgumentException("final-output-budget");
        }
        return result;
    }

    private static void writeRoute(DataOutputStream data, ModernTracePipeline.Route route)
            throws IOException {
        ScalarReplayFingerprint.writeHypothesis(data, route.rawHypothesis());
        ScalarReplayFingerprint.writeHypothesis(data, route.hypothesis());
        data.writeInt(route.pointIds().size());
        for (FinalRoutePointId id : route.pointIds()) {
            writePointId(data, id);
            writePoint(data, route.assignments().get(id));
            ObservationOwnership ownership = route.sourceOwnership().get(id);
            if (ownership == null) {
                throw new IllegalArgumentException("final-output-invalid");
            }
            ScalarReplayFingerprint.writeString(data, ownership.name());
        }
        writeQuality(data, route.quality());
        ScalarReplayFingerprint.writeString(data, route.cleanupStatus().name());
        data.writeBoolean(route.geometryChanged());
    }

    private static void writePointId(DataOutputStream data, FinalRoutePointId id)
            throws IOException {
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

    private static void writePrimitiveKey(DataOutputStream data, PrimitiveKey key)
            throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("final-output-invalid");
        }
        ScalarReplayFingerprint.writeString(data, key.type().name());
        ScalarReplayFingerprint.writeString(data, key.identityKind().name());
        data.writeLong(key.id());
    }

    private static void writePoint(DataOutputStream data, MetricPoint point) throws IOException {
        if (point == null) {
            throw new IllegalArgumentException("final-output-invalid");
        }
        writeDouble(data, point.xMeters());
        writeDouble(data, point.yMeters());
    }

    private static void writeQuality(DataOutputStream data, FinalGeometryEvaluator.Result quality)
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
            writeDouble(data, finding.amplitudeMeters());
        }
        writeDouble(data, quality.totalLengthMeters());
        writeDouble(data, quality.directlySupportedLengthMeters());
        writeDouble(data, quality.worstUnsupportedSpanMeters());
        writeImageCenterCost(data, quality);
        writeDouble(data, quality.bendPreservingRoughness());
    }

    private static void writeImageCenterCost(DataOutputStream data,
            FinalGeometryEvaluator.Result quality) throws IOException {
        double cost = quality.meanImageCenterCost();
        boolean unavailable = quality.has(FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY);
        if (unavailable != (cost == Double.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("final-output-invalid");
        }
        if (unavailable) {
            // The finding owns this canonical unavailable sentinel; it is not measured quality.
            // Keep finite schema-1 output bytes unchanged, including interval replay hashes.
            data.writeLong(Double.doubleToLongBits(Double.POSITIVE_INFINITY));
        } else {
            writeDouble(data, cost);
        }
    }

    private static void writeDouble(DataOutputStream data, double value) throws IOException {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("final-output-invalid");
        }
        data.writeLong(Double.doubleToLongBits(value));
    }
}
