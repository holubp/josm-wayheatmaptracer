package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;

class V022ProbabilisticPerformanceFixtureTest {
    @Test
    void completionRejectsDistinctLimitAboveRawLimit() {
        assertThrows(IllegalArgumentException.class,
            () -> new ProbabilisticInferenceResult.Completion(1, 2, 1,
                false, false, false));
    }

    @Test
    void completedResultRejectsRawPathCountThatDoesNotMatchTerminalProof() {
        ProbabilisticInferenceResult actual = twoTerminalResult();

        assertThrows(IllegalArgumentException.class, () -> copyWithCompletion(actual,
            actual.rawPaths().subList(0, 1), actual.distinctPaths(),
            actual.alternativeSearchTruncated(), actual.completion().orElseThrow()));
    }

    @Test
    void completedResultRejectsMoreDistinctPathsThanRequestedLimit() {
        ProbabilisticInferenceResult actual = twoTerminalResult();

        assertThrows(IllegalArgumentException.class, () -> copyWithCompletion(actual,
            actual.rawPaths(), actual.rawPaths(), actual.alternativeSearchTruncated(),
            actual.completion().orElseThrow()));
    }

    @Test
    void completedResultRejectsMismatchedRequestedDiversityFact() {
        ProbabilisticInferenceResult actual = twoTerminalResult();
        var proof = actual.completion().orElseThrow();
        var mismatched = new ProbabilisticInferenceResult.Completion(proof.effectiveRawLimit(),
            proof.effectiveDistinctLimit(), proof.completePathsAtSaturation(),
            proof.terminalCountSaturated(), proof.rawEnumerationCapped(), false);

        assertThrows(IllegalArgumentException.class, () -> copyWithCompletion(actual,
            actual.rawPaths(), actual.distinctPaths(), actual.alternativeSearchTruncated(), mismatched));
    }

    @Test
    void completedResultRejectsMismatchedAlternativeSearchTruncation() {
        ProbabilisticInferenceResult actual = twoTerminalResult();

        assertThrows(IllegalArgumentException.class, () -> copyWithCompletion(actual,
            actual.rawPaths(), actual.distinctPaths(), true, actual.completion().orElseThrow()));
    }

    @Test
    void completionProofChangesExactInferenceFingerprint() {
        InferenceProfile profile = V022ProbabilisticInferenceTest.profile(0,
            new double[] {-1, 1}, new double[] {1, 1}, new double[] {0, 1},
            new String[] {"one", "one"});
        TraceBudgets budgets = new TraceBudgets(96, 8_000_000, 128_000_000, 3, 1);
        ProbabilisticInferenceResult actual = new ProbabilisticInference().solve(List.of(profile),
            EvidenceModelParameters.withoutShapeTerms(), budgets);
        var altered = new ProbabilisticInferenceResult(actual.status(), actual.mapPath(),
            actual.rawPaths(), actual.distinctPaths(), actual.logPartition(),
            actual.positionMarginals(), actual.componentMarginals(),
            actual.forwardPositionMarginals(), actual.backwardPositionMarginals(),
            actual.credibleSets(), actual.posteriorUsable(), actual.alternativeSearchTruncated(),
            java.util.Optional.of(new ProbabilisticInferenceResult.Completion(4, 1, 2,
                false, false, true)), actual.evaluatedPairVisits(), actual.evaluatedTransitions(),
            actual.gapSummary(), actual.explanation());

        assertNotEquals(ProbabilisticInferenceFingerprint.capture(actual),
            ProbabilisticInferenceFingerprint.capture(altered));
    }

    @Test
    void frozenInferenceFingerprintIsRepeatableForUnequalStateProfiles() {
        var profiles = V022ProbabilisticInferenceTest.tinyProfiles();
        var solver = new ProbabilisticInference();
        var first = solver.solve(profiles, EvidenceModelParameters.defaults(), TraceBudgets.defaults());
        var second = solver.solve(profiles, EvidenceModelParameters.defaults(), TraceBudgets.defaults());

        String actual = ProbabilisticInferenceFingerprint.capture(first);
        assertEquals(actual, ProbabilisticInferenceFingerprint.capture(second));
        assertEquals("7eb984cba8cc3aac21225cb9d109cbc42610ca1880dc1c0a2ae79da35f191465", sha256(actual));
    }

    @Test
    void frozenUnequalFourProfileLatticeRetainsLexicalTieOrderAtEveryCap() {
        List<InferenceProfile> profiles = List.of(
            V022ProbabilisticInferenceTest.profile(0, new double[] {-1, 1}, new double[] {1, 1}, new double[] {0, 0}, new String[] {"a", "b"}),
            V022ProbabilisticInferenceTest.profile(1, new double[] {-1, 0, 1}, new double[] {1, 1, 1}, new double[] {0, 0, 0}, new String[] {"a", "b", "c"}),
            V022ProbabilisticInferenceTest.profile(2, new double[] {0}, new double[] {1}, new double[] {0}, new String[] {"a"}),
            V022ProbabilisticInferenceTest.profile(3, new double[] {-2, -1, 1, 2}, new double[] {1, 1, 1, 1}, new double[] {0, 0, 0, 0}, new String[] {"a", "b", "c", "d"}));
        ProbabilisticInference solver = new ProbabilisticInference();
        ProbabilisticInferenceResult result = solver.solve(profiles,
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults());

        assertArrayEquals(new int[] {0, 0, 0, 0}, result.rawPaths().get(0).stateIndices());
        String fingerprint = ProbabilisticInferenceFingerprint.capture(result);
        assertTrue(fingerprint.contains("|raw#"));
        assertTrue(fingerprint.contains("|points#"));
        assertEquals("c3b5e7e5db9467162f8dc467c909bc1de14b7558471346e4d3c497a94c97f275", sha256(fingerprint));
    }

    @Test
    void ancestryPreservesLexicalFirstPathAcrossRawAlternativeCaps() {
        List<InferenceProfile> profiles = List.of(
            V022ProbabilisticInferenceTest.profile(0, new double[] {-1, 1}, new double[] {1, 1}, new double[] {0, 0}, new String[] {"a", "b"}),
            V022ProbabilisticInferenceTest.profile(1, new double[] {-1, 0, 1}, new double[] {1, 1, 1}, new double[] {0, 0, 0}, new String[] {"a", "b", "c"}),
            V022ProbabilisticInferenceTest.profile(2, new double[] {0}, new double[] {1}, new double[] {0}, new String[] {"a"}),
            V022ProbabilisticInferenceTest.profile(3, new double[] {-2, -1, 1, 2}, new double[] {1, 1, 1, 1}, new double[] {0, 0, 0, 0}, new String[] {"a", "b", "c", "d"}));
        for (int cap : new int[] {1, 8, 32}) {
            TraceBudgets budgets = new TraceBudgets(96, 8_000_000, 128_000_000, cap, Math.min(cap, 8));
            ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
                EvidenceModelParameters.withoutShapeTerms(), budgets);
            assertArrayEquals(new int[] {0, 0, 0, 0}, result.rawPaths().get(0).stateIndices());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ProbabilisticInferenceResult twoTerminalResult() {
        InferenceProfile profile = V022ProbabilisticInferenceTest.profile(0,
            new double[] {-1, 1}, new double[] {1, 1}, new double[] {0, 1},
            new String[] {"one", "one"});
        TraceBudgets budgets = new TraceBudgets(96, 8_000_000, 128_000_000, 3, 1);
        return new ProbabilisticInference().solve(List.of(profile),
            EvidenceModelParameters.withoutShapeTerms(), budgets);
    }

    private static ProbabilisticInferenceResult copyWithCompletion(ProbabilisticInferenceResult source,
        List<ProbabilisticPath> rawPaths, List<ProbabilisticPath> distinctPaths,
        boolean alternativeSearchTruncated, ProbabilisticInferenceResult.Completion completion) {
        return new ProbabilisticInferenceResult(source.status(), source.mapPath(),
            rawPaths, distinctPaths, source.logPartition(), source.positionMarginals(),
            source.componentMarginals(), source.forwardPositionMarginals(),
            source.backwardPositionMarginals(), source.credibleSets(), source.posteriorUsable(),
            alternativeSearchTruncated, java.util.Optional.of(completion), source.evaluatedPairVisits(),
            source.evaluatedTransitions(), source.gapSummary(), source.explanation());
    }
}
