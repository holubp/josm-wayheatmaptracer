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
        appendPaths(value, "raw", result.rawPaths());
        appendPaths(value, "distinct", result.distinctPaths());
        appendArrays(value, "position", result.positionMarginals());
        appendArrays(value, "component", result.componentMarginals());
        appendArrays(value, "forward", result.forwardPositionMarginals());
        appendArrays(value, "backward", result.backwardPositionMarginals());
        value.append('|').append(result.credibleSets()).append('|').append(result.explanation());
        return value.toString();
    }

    private static void appendPaths(StringBuilder value, String label, java.util.List<ProbabilisticPath> paths) {
        value.append('|').append(label).append('#').append(paths.size()).append('=');
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
        value.append("|points#").append(path.points().size()).append('=');
        path.points().forEach(point -> { append(value, point.xMeters()); append(value, point.yMeters()); });
    }

    private static void appendArrays(StringBuilder value, String label, java.util.List<double[]> arrays) {
        value.append('|').append(label).append('#').append(arrays.size()).append('=');
        for (double[] array : arrays) {
            value.append('[').append(array.length).append(']');
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
