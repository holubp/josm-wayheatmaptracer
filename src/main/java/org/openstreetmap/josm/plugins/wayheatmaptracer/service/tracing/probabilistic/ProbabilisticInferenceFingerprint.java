package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Arrays;

/** Canonical exact-output fingerprint used to gate behavior-preserving B solver optimization. */
public final class ProbabilisticInferenceFingerprint {
    private ProbabilisticInferenceFingerprint() {
    }

    /** Serializes every solver result field whose change would alter observable inference behavior. */
    public static String capture(ProbabilisticInferenceResult result) {
        if (result == null) {
            throw new IllegalArgumentException("Inference result is required");
        }
        StringBuilder value = new StringBuilder(result.status().name());
        append(value, result.logPartition());
        value.append("|map=");
        result.mapPath().ifPresent(path -> appendPath(value, path));
        value.append('|').append(result.posteriorUsable()).append('|')
                .append(result.alternativeSearchTruncated()).append('|')
                .append(result.evaluatedPairVisits()).append('|').append(result.evaluatedTransitions())
                .append('|').append(result.gapSummary());
        appendPaths(value, result.rawPaths());
        appendPaths(value, result.distinctPaths());
        appendArrays(value, result.positionMarginals());
        appendArrays(value, result.componentMarginals());
        appendArrays(value, result.forwardPositionMarginals());
        appendArrays(value, result.backwardPositionMarginals());
        value.append('|').append(result.credibleSets()).append('|').append(result.explanation());
        return value.toString();
    }

    private static void appendPaths(StringBuilder value, java.util.List<ProbabilisticPath> paths) {
        value.append('|');
        for (ProbabilisticPath path : paths) {
            appendPath(value, path);
            value.append(';');
        }
    }

    private static void appendPath(StringBuilder value, ProbabilisticPath path) {
        value.append(Arrays.toString(path.stateIndices())).append(':');
        append(value, path.energy());
        append(value, path.logBaseMeasure());
        append(value, path.conditionalPosteriorMass());
        value.append(path.branchSignature()).append(':');
        path.points().forEach(point -> { append(value, point.xMeters()); append(value, point.yMeters()); });
    }

    private static void appendArrays(StringBuilder value, java.util.List<double[]> arrays) {
        value.append('|');
        for (double[] array : arrays) {
            for (double number : array) {
                append(value, number);
            }
            value.append(';');
        }
    }

    private static void append(StringBuilder value, double number) {
        value.append(':').append(Double.toHexString(number));
    }
}
