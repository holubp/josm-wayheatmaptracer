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
        long items = ReplayOutputAdmission.rootItems(output);
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
        ReplayOutputCanonical.writeRoute(data, route);
    }

    private static void writePointId(DataOutputStream data, FinalRoutePointId id)
            throws IOException {
        ReplayOutputCanonical.writePointId(data, id);
    }

    private static void writePrimitiveKey(DataOutputStream data, PrimitiveKey key)
            throws IOException {
        ReplayOutputCanonical.writePrimitiveKey(data, key);
    }

    private static void writePoint(DataOutputStream data, MetricPoint point) throws IOException {
        ReplayOutputCanonical.writePoint(data, point);
    }

    private static void writeQuality(DataOutputStream data, FinalGeometryEvaluator.Result quality)
            throws IOException {
        ReplayOutputCanonical.writeQuality(data, quality);
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
        ReplayOutputCanonical.writeFiniteDouble(data, value);
    }
}
