package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedLaplacianResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedSimplificationResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;

/**
 * Regression coverage for cleanup-stage retained size, deterministic work, and warm timing ratios.
 *
 * <p>Wall-clock measurements are intentionally only ratio guards with substantial slack: Termux and
 * shared CI machines have noisy clocks. The retained-evidence and chord-count assertions are the
 * primary deterministic scaling contract.</p>
 */
class GeometryCleanupPerformanceTest {
    private static final GeometryCleanupConfig CONFIG = GeometryCleanupPreset.STRONG.apply();
    private static final HeatmapConstrainedLaplacianSmoother SMOOTHER =
        new HeatmapConstrainedLaplacianSmoother();
    private static final HeatmapConstrainedSimplifier SIMPLIFIER = new HeatmapConstrainedSimplifier();

    @Test
    void ordinaryTracesKeepCleanupEvidenceAndWorkNearLinearAcrossProfileCounts() {
        Measurement small = measure(128);
        Measurement medium = measure(256);
        Measurement large = measure(512);

        assertTrue(medium.retainedBytes() <= small.retainedBytes() * 2L + 512L, metrics(small, medium));
        assertTrue(large.retainedBytes() <= medium.retainedBytes() * 2L + 512L, metrics(medium, large));
        assertTrue(small.simplificationAttempts() <= 4L * small.profiles(), metrics(small));
        assertTrue(medium.simplificationAttempts() <= 4L * medium.profiles(), metrics(medium));
        assertTrue(large.simplificationAttempts() <= 4L * large.profiles(), metrics(large));
        assertTrue(small.acceptedPasses() <= CONFIG.laplacianPassCount(), metrics(small));
        assertTrue(medium.acceptedPasses() <= CONFIG.laplacianPassCount(), metrics(medium));
        assertTrue(large.acceptedPasses() <= CONFIG.laplacianPassCount(), metrics(large));

        // Warm medians deliberately allow scheduler and GC noise while still detecting large regressions.
        assertStageScaling(small, medium);
        assertStageScaling(medium, large);
    }

    private static Measurement measure(int profileCount) {
        for (int iteration = 0; iteration < 3; iteration++) {
            run(profileCount);
        }
        List<Long> evidenceTimings = new ArrayList<>();
        List<Long> smoothingTimings = new ArrayList<>();
        List<Long> reductionTimings = new ArrayList<>();
        Run last = null;
        for (int iteration = 0; iteration < 7; iteration++) {
            last = run(profileCount);
            evidenceTimings.add(last.evidenceNanos());
            smoothingTimings.add(last.smoothingNanos());
            reductionTimings.add(last.reductionNanos());
        }
        evidenceTimings.sort(Long::compareTo);
        smoothingTimings.sort(Long::compareTo);
        reductionTimings.sort(Long::compareTo);
        return new Measurement(profileCount, last.retainedBytes(), last.smoothing().metrics().acceptedPassCount(),
            last.reduction().metrics().attemptedChordCount(),
            evidenceTimings.get(evidenceTimings.size() / 2),
            smoothingTimings.get(smoothingTimings.size() / 2),
            reductionTimings.get(reductionTimings.size() / 2));
    }

    private static Run run(int profileCount) {
        long evidenceStarted = System.nanoTime();
        List<EastNorth> geometry = new ArrayList<>();
        List<CleanupSamplingProfile> samples = new ArrayList<>();
        List<CandidateCleanupProfile> rows = new ArrayList<>();
        double[] offsets = offsets();
        for (int index = 0; index < profileCount; index++) {
            double x = index * 1.5;
            double center = 0.15;
            geometry.add(new EastNorth(x, center + 0.35 * Math.sin(index * Math.PI / 3.0)));
            double[] intensity = intensity(offsets, center);
            boolean[] valid = new boolean[offsets.length];
            java.util.Arrays.fill(valid, true);
            samples.add(new CleanupSamplingProfile(index, x, true, 1.0,
                new ProjectedLateralTransform(new EastNorth(x, 0.0), 0.0, 1.0),
                offsets, intensity, intensity, intensity, valid));
            rows.add(new CandidateCleanupProfile(index, center - 0.8, center + 0.8,
                center - 4.0, center + 4.0, center, 1.0, CleanupEvidenceProvenance.DIRECT,
                0.0, 0.0, false));
        }
        CandidateCleanupEvidence evidence = CandidateCleanupEvidence.complete(
            new CleanupSamplingFrame("performance", samples, 1.0), rows);
        long evidenceNanos = System.nanoTime() - evidenceStarted;
        List<HeatmapConstrainedLaplacianSmoother.ProtectedInterval> smoothingIntervals = List.of(
            new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, profileCount - 1));
        long smoothingStarted = System.nanoTime();
        HeatmapConstrainedLaplacianResult smoothing = SMOOTHER.smooth(
            geometry, smoothingIntervals, java.util.Set.of(), evidence, CONFIG);
        long smoothingNanos = System.nanoTime() - smoothingStarted;
        long reductionStarted = System.nanoTime();
        HeatmapConstrainedSimplificationResult reduction = SIMPLIFIER.simplify(smoothing.geometry(), List.of(
            new HeatmapConstrainedSimplifier.ProtectedInterval(0, profileCount - 1)),
            java.util.Set.of(), evidence, CONFIG);
        long reductionNanos = System.nanoTime() - reductionStarted;
        return new Run(evidence.samplingFrame().estimatedBytes() + evidence.estimatedCandidateBytes(),
            smoothing, reduction, evidenceNanos, smoothingNanos, reductionNanos);
    }

    private static double[] offsets() {
        double[] result = new double[33];
        for (int index = 0; index < result.length; index++) {
            result[index] = index - 16;
        }
        return result;
    }

    private static double[] intensity(double[] offsets, double center) {
        double[] result = new double[offsets.length];
        for (int index = 0; index < offsets.length; index++) {
            double delta = offsets[index] - center;
            result[index] = 0.02 + 0.98 * Math.exp(-0.5 * delta * delta / 3.0);
        }
        return result;
    }

    private static String metrics(Measurement... values) {
        return java.util.Arrays.toString(values);
    }

    private static void assertStageScaling(Measurement smaller, Measurement larger) {
        long slackNanos = 100_000_000L;
        assertTrue(larger.evidenceNanos() <= smaller.evidenceNanos() * 6L + slackNanos,
            metrics(smaller, larger));
        assertTrue(larger.smoothingNanos() <= smaller.smoothingNanos() * 6L + slackNanos,
            metrics(smaller, larger));
        assertTrue(larger.reductionNanos() <= smaller.reductionNanos() * 6L + slackNanos,
            metrics(smaller, larger));
    }

    private record Run(
        long retainedBytes,
        HeatmapConstrainedLaplacianResult smoothing,
        HeatmapConstrainedSimplificationResult reduction,
        long evidenceNanos,
        long smoothingNanos,
        long reductionNanos
    ) {
    }

    private record Measurement(
        int profiles,
        long retainedBytes,
        int acceptedPasses,
        int simplificationAttempts,
        long evidenceNanos,
        long smoothingNanos,
        long reductionNanos
    ) {
    }
}
