package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
        assertEquals("a885277acac15259cf7018a78b7e9d08841ada202f219be32d32dade0d94144e", sha256(actual));
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
