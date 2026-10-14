package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;

class V022LocalizationEvidenceTest {
    private static final int WIDTH = 121;
    private static final int HEIGHT = 61;

    @Test
    void realFactoryMeasuresHorizontalImageDirectionWhenSourceWaySlopes() {
        EvidenceSnapshot evidence = snapshot((x, y) -> gaussian(y, 30.0, 1.8));
        List<MetricPoint> source = List.of(new MetricPoint(10, 25), new MetricPoint(110, 34));

        ProbabilisticProfile profile = new ProbabilisticProfileFactory()
            .create(source, 6.0, 8.0, false, evidence, evidence.fields().get("scalar"))
            .stream().filter(candidate -> !candidate.modes().isEmpty())
            .min(Comparator.comparingDouble(candidate -> Math.abs(candidate.anchor().xMeters() - 60.0)))
            .orElseThrow();

        assertTrue(profile.orientationCertainty() > 0.0);
        assertEquals(0.0, undirectedDistance(0.0, profile.supportedDirectionsRadians().get(0)), 0.03);
    }

    @Test
    void realFactoryRetainsWeakerLocallyProminentParallelMode() {
        EvidenceSnapshot evidence = snapshot((x, y) -> Math.min(1.0,
            0.6 * gaussian(y, 26.0, 1.2) + gaussian(y, 35.0, 1.2)));

        ProbabilisticProfile profile = centerProfile(evidence);
        List<Double> centers = profile.modes().stream()
            .map(ProbabilisticProfile.Mode::coreCenterMeters).sorted().toList();

        assertEquals(2, centers.size());
        assertEquals(-4.0, centers.get(0), 0.55);
        assertEquals(5.0, centers.get(1), 0.55);
        assertTrue(profile.samples().stream().anyMatch(sample -> sample.valid()
            && Math.abs(sample.offsetMeters() + 4.0) < 1e-9 && sample.intensity() > 0.59));
    }

    @Test
    void realFactoryRetainsEqualParallelModes() {
        EvidenceSnapshot evidence = snapshot((x, y) -> Math.min(1.0,
            0.9 * gaussian(y, 26.0, 1.2) + 0.9 * gaussian(y, 35.0, 1.2)));

        ProbabilisticProfile profile = centerProfile(evidence);

        assertEquals(2, profile.modes().size());
    }

    @Test
    void interpolatedAlternatingRasterDoesNotInventModesAtDifferentPhases() {
        EvidenceSnapshot evidence = snapshot((x, y) -> y % 2 == 0 ? 0.02 : 0.03);
        for (double phase : List.of(0.0, 0.25, 0.5, 0.75)) {
            for (double rise : List.of(0.0, 8.0)) {
                var profiles = new ProbabilisticProfileFactory().create(
                        List.of(new MetricPoint(10, 26 + phase), new MetricPoint(110, 26 + phase + rise)),
                        6.0, 7.01, false, evidence, evidence.fields().get("scalar"));
                assertTrue(profiles.stream().allMatch(profile -> profile.modes().isEmpty()),
                        "alternating raster phase=" + phase + " rise=" + rise);
            }
        }
    }

    @Test
    void nearbyStrongerParallelModeDoesNotEraseTheWeakCore() {
        EvidenceSnapshot evidence = snapshot((x, y) -> Math.min(1.0,
                0.02 + 0.08 * gaussian(y, 28, 0.6) + 0.9 * gaussian(y, 32, 0.6)));
        var profile = centerProfile(evidence);
        assertTrue(profile.modes().stream().anyMatch(mode -> Math.abs(mode.coreCenterMeters() + 2) < 0.75),
                "the nearby strong core must not become the weak core's noise");
        assertTrue(profile.modes().stream().anyMatch(mode -> Math.abs(mode.coreCenterMeters() - 2) < 0.75));
    }

    @Test
    void isolatedImpulsivePixelDoesNotPullTheCoherentWeakRidge() {
        EvidenceSnapshot evidence = snapshot((x, y) -> Math.min(1.0,
                0.02 + 0.08 * gaussian(y, 26, 0.9) + (x == 60 && y == 28 ? 0.9 : 0.0)));
        var profiles = new ProbabilisticProfileFactory().create(
                List.of(new MetricPoint(10, 26), new MetricPoint(110, 26)),
                5.0, 7.01, false, evidence, evidence.fields().get("scalar"));
        var parameters = EvidenceModelParameters.defaults();
        var proposals = ProbabilisticContinuationProposals.build(profiles,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,
                null, parameters.localization());
        var evaluated = profiles.stream().map(profile -> new ProbabilisticObservationModel().evaluate(profile,
                new ProbabilisticStateBuilder().buildWithProposal(profile, 96,
                    proposals[profile.profileIndex()], null).lattice().orElseThrow(), parameters)).toList();
        var result = new ProbabilisticInference().solve(evaluated, parameters,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),
                evidence.decisionRegion());
        var path = result.mapPath().orElseThrow();
        assertTrue(path.points().stream().allMatch(point -> Math.abs(point.yMeters() - 26) <= 1.0),
                "one impulsive neighbor must not displace a coherent ridge by more than a native pixel: "
                        + path.points());
    }

    @Test
    void sparseOverlappingStrandsDoNotInventShortWaveCorridorBends() {
        // Three equally weighted traces of the same straight corridor. Their
        // lateral errors sum to zero at every x, but their changing overlaps
        // form brighter local peaks to alternating sides of that corridor.
        EvidenceSnapshot evidence = snapshot((x, y) -> {
            double value = 0.02;
            for (int strand = 0; strand < 3; strand++) {
                double phase = 2.0 * Math.PI * (x - 10) / 24.0
                        + strand * 2.0 * Math.PI / 3.0;
                value += 0.06 * gaussian(y, 30 + 2.5 * Math.sin(phase), 0.65);
            }
            return value;
        });
        var path = measuredPath(evidence, List.of(new MetricPoint(10, 30), new MetricPoint(110, 30)), 2.0);
        double maximumDeviation = path.points().stream()
                .filter(point -> point.xMeters() >= 25 && point.xMeters() <= 95)
                .mapToDouble(point -> Math.abs(point.yMeters() - 30)).max().orElseThrow();
        assertTrue(maximumDeviation <= evidence.resolution().effectivePitchMeters(),
                "Changing overlaps must not invent bends beyond native localization uncertainty; deviation="
                        + maximumDeviation + " path=" + path.points());
    }

    @Test
    void unsupportedStraightContinuationDoesNotAcquireSamplingStaircaseTurns() {
        // The imagery observes the same straight route at both sides of a gap.
        // The gap has no positional evidence and must not gain invented support.
        // This tests geometry, not permission to Apply across unsupported data.
        EvidenceSnapshot evidence = snapshot((x, y) -> x < 35 || x > 85
                ? 0.02 + 0.6 * gaussian(y, 26 + 0.07 * (x - 10), 1.0) : 0.02);
        var path = measuredPath(evidence,
                List.of(new MetricPoint(10, 28), new MetricPoint(110, 28)), 1.5, false);
        double absoluteTurn = 0;
        for (int i = 1; i + 1 < path.points().size(); i++) {
            var a = path.points().get(i - 1);
            var b = path.points().get(i);
            var c = path.points().get(i + 1);
            if (b.xMeters() < 40 || b.xMeters() > 80) continue;
            double delta = Math.atan2(c.yMeters() - b.yMeters(), c.xMeters() - b.xMeters())
                    - Math.atan2(b.yMeters() - a.yMeters(), b.xMeters() - a.xMeters());
            absoluteTurn += Math.abs(Math.atan2(Math.sin(delta), Math.cos(delta)));
        }
        assertTrue(absoluteTurn < Math.toRadians(10),
                "An unsupported straight continuation must not become a staircase; turnRadians="
                        + absoluteTurn + " path=" + path.points());
    }

    @Test
    void faintConcentratedTwelveMeterBendRetainsItsSupportedApex() {
        EvidenceSnapshot evidence = snapshot((x, y) -> {
            double apex = 30 + 3 * Math.max(0, 1 - Math.abs(x - 60) / 6.0);
            return 0.02 + 0.08 * gaussian(y, apex, 0.65);
        });
        var path = measuredPath(evidence,
                List.of(new MetricPoint(10, 30), new MetricPoint(110, 30)), 1.5, true);
        double apex = path.points().stream().filter(point -> Math.abs(point.xMeters() - 60) <= 2)
                .mapToDouble(MetricPoint::yMeters).max().orElseThrow();
        assertTrue(apex >= 32,
                "A concentrated faint apex must remain within one native pixel of the observed 33 m apex: "
                        + apex);
    }

    private static ProbabilisticPath measuredPath(EvidenceSnapshot evidence,
            List<MetricPoint> source, double stepMeters) {
        return measuredPath(evidence, source, stepMeters, false);
    }

    private static ProbabilisticPath measuredPath(EvidenceSnapshot evidence,
            List<MetricPoint> source, double stepMeters, boolean fixedEndpoints) {
        var parameters = EvidenceModelParameters.defaults();
        var profiles = new ProbabilisticProfileFactory().create(source, stepMeters, 7.01,
                fixedEndpoints, evidence, evidence.fields().get("scalar"));
        var owner = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                .AttemptMemoryLedger.production().rootOwner();
        try {
            var proposals = ProbabilisticContinuationProposals.build(profiles,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,
                    owner, parameters.localization());
            var evaluated = profiles.stream().map(profile -> new ProbabilisticObservationModel().evaluate(profile,
                    new ProbabilisticStateBuilder().buildWithProposal(profile, 96,
                            proposals[profile.profileIndex()], owner).lattice().orElseThrow(), parameters)).toList();
            var result = new ProbabilisticInference().solve(evaluated, parameters,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),
                    evidence.decisionRegion(),
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,
                    owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            return result.mapPath().orElseThrow(() -> new AssertionError(
                    "Actual production execution returned " + result.status() + ": " + result.explanation()));
        } finally {
            owner.close();
        }
    }

    private static ProbabilisticProfile centerProfile(EvidenceSnapshot evidence) {
        return new ProbabilisticProfileFactory()
            .create(List.of(new MetricPoint(10, 30), new MetricPoint(110, 30)),
                6.0, 7.0, false, evidence, evidence.fields().get("scalar"))
            .stream().min(Comparator.comparingDouble(profile -> Math.abs(profile.anchor().xMeters() - 60.0)))
            .orElseThrow();
    }

    static EvidenceSnapshot snapshot(Intensity intensity) {
        double[] values = new double[WIDTH * HEIGHT];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                values[y * WIDTH + x] = Math.max(0.02, Math.min(1.0, intensity.value(x, y)));
            }
        }
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        ScalarEvidenceField field = new ScalarEvidenceField(WIDTH, HEIGHT, values, valid, lineage);
        GeographicPoint origin = new GeographicPoint(0, 0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        RasterMetricTransform transform = new RasterMetricTransform("localization-test-v1",
            RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, new MetricPoint(0, 0),
            1, 0, 0, 1, 1);
        return new EvidenceSnapshot("localization", frame, transform,
            EvidenceResolution.nativeSource(1, 1), MetricRegion.rectangle(1, 1, 119, 59),
            MetricRegion.rectangle(0, 0, 120, 60), Map.of("scalar", field), "synthetic-localization");
    }

    private static double gaussian(double value, double center, double sigma) {
        double normalized = (value - center) / sigma;
        return Math.exp(-0.5 * normalized * normalized);
    }

    private static double undirectedDistance(double first, double second) {
        double difference = Math.abs(first - second) % Math.PI;
        return Math.min(difference, Math.PI - difference);
    }

    @FunctionalInterface
    interface Intensity {
        double value(double x, double y);
    }
}
