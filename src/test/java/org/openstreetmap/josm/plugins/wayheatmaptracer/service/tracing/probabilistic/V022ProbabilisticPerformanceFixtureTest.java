package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void frozenInferenceFingerprintIsRepeatableForUnequalStateProfiles() {
        var profiles = V022ProbabilisticInferenceTest.tinyProfiles();
        var solver = new ProbabilisticInference();
        var first = solver.solve(profiles, EvidenceModelParameters.defaults(), TraceBudgets.defaults());
        var second = solver.solve(profiles, EvidenceModelParameters.defaults(), TraceBudgets.defaults());

        String actual = ProbabilisticInferenceFingerprint.capture(first);
        assertEquals(actual, ProbabilisticInferenceFingerprint.capture(second));
        assertEquals("76b4c6f4271e36b17587bbcfcc8d080512979d2ae736b43667c315950973e36e", sha256(actual));
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
        assertEquals("c6398e6fa30a0945fd685ef08b29e041e590cc70d35fda07fa3e00bd0b8d3a83", sha256(fingerprint));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
