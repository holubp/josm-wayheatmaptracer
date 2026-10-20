package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Immutable, separately unit-labelled work for the opt-in raw-one partial bound. */
public record CrossStreamWorkAccounting(long stageCertificates, long certificateFallbacks,
        long boundQueries, long boundFallbacks, long finiteTests, long magnitudeTests,
        long arithmeticOps, long outwardSteps, long partialAdditions, long strictComparisons,
        long boundCheckpoints, long skippedStreams, long skippedLogicalPrefixes,
        long firstHeadingEvaluations, long secondHeadingEvaluations, long wrapEvaluations,
        long huberEvaluations, long tripleEvaluations, long ceilingChecks, long boundUnits) {
    public static final String VERSION = "terminal-path-count-cross-stream-raw1-v3";

    /** Rejects contradictory outcomes and overflow rather than inventing completed work. */
    public CrossStreamWorkAccounting {
        if (stageCertificates < 0 || certificateFallbacks < 0 || boundQueries < 0 || boundFallbacks < 0
                || finiteTests < 0 || magnitudeTests < 0 || arithmeticOps < 0 || outwardSteps < 0
                || partialAdditions < 0 || strictComparisons < 0 || boundCheckpoints < 0
                || skippedStreams < 0 || skippedLogicalPrefixes < 0 || firstHeadingEvaluations < 0
                || secondHeadingEvaluations < 0 || wrapEvaluations < 0 || huberEvaluations < 0
                || tripleEvaluations < 0 || ceilingChecks < 0 || boundUnits < 0) {
            throw new IllegalArgumentException("Cross-stream work must be nonnegative");
        }
        long outcomes = Math.addExact(boundFallbacks, skippedStreams);
        long minimumUnits = Math.addExact(Math.multiplyExact(128L, stageCertificates),
                Math.multiplyExact(64L, boundQueries));
        if (boundUnits != 0) minimumUnits = Math.addExact(8L, minimumUnits);
        if (certificateFallbacks > stageCertificates || outcomes > boundQueries
                || skippedLogicalPrefixes != skippedStreams || partialAdditions > boundQueries
                || strictComparisons > partialAdditions || skippedStreams > strictComparisons
                || boundUnits < minimumUnits || (boundUnits != 0 && (boundUnits < 8 || (boundUnits - 8) % 64 != 0))) {
            throw new IllegalArgumentException("Cross-stream work accounting is inconsistent");
        }
    }
}
