package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022LocalizationCorrectionTest {
    private static final int WIDTH = 121;
    private static final int HEIGHT = 81;

    @Test
    void productionOrientationCostUsesTheSelectedLateralBranchLocation() {
        double slope = Math.tan(Math.toRadians(30.0));
        EvidenceSnapshot evidence = snapshot((x, y) -> Math.min(1.0, 0.02
            + 0.08 * gaussian(y, 50.0, 0.9)
            + 0.11 * gaussian(y, 56.0 + slope * (x - 60.0), 0.9)));
        List<MetricPoint> source = List.of(new MetricPoint(60, 50), new MetricPoint(70, 50));
        EvidenceModelParameters parameters = new EvidenceModelParameters(
            "branch-local-orientation-test", 0.0, 1.0, 0.0, 4.0, 0.0,
            Math.toRadians(30.0), 1.0, 1e-9);
        List<ProbabilisticProfile> profiles = new ProbabilisticProfileFactory().create(
            source, 10.0, 16.0, false, evidence, evidence.fields().get("scalar"),
            parameters, CancellationProbe.NONE);
        List<InferenceProfile> evaluated = new ArrayList<>();
        for (ProbabilisticProfile profile : profiles) {
            ProbabilisticStateLattice lattice = new ProbabilisticStateBuilder().build(profile, 96)
                .lattice().orElseThrow();
            evaluated.add(new ProbabilisticObservationModel().evaluate(profile, lattice, parameters));
        }
        List<Double> firstCenters = modeCenters(profiles.get(0));
        ProbabilisticProfile.Mode firstInclined = profiles.get(0).modes().stream()
            .max(Comparator.comparingDouble(ProbabilisticProfile.Mode::coreCenterMeters))
            .orElseThrow();

        List<InferenceProfile> inclined = new ArrayList<>();
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            ProbabilisticProfile.Mode branch = profiles.get(profileIndex).modes().stream()
                .max(Comparator.comparingDouble(ProbabilisticProfile.Mode::coreCenterMeters))
                .orElseThrow();
            InferenceProfile evaluatedProfile = evaluated.get(profileIndex);
            int state = java.util.stream.IntStream.range(0, evaluatedProfile.cells().size()).boxed()
                .filter(index -> evaluatedProfile.cells().get(index).branchLabel().equals(branch.id()))
                .min(Comparator.comparingDouble(index -> Math.abs(
                    evaluatedProfile.cells().get(index).offsetMeters() - branch.coreCenterMeters())))
                .orElseThrow();
            LateralStateCell cell = evaluatedProfile.cells().get(state);
            inclined.add(new InferenceProfile(evaluatedProfile.chainageMeters(),
                evaluatedProfile.anchor(), evaluatedProfile.normalUnit(),
                List.of(new LateralStateCell(cell.offsetMeters(), 1.0, false, true,
                    cell.branchLabel())), new double[] {0.0},
                Map.of(cell.branchLabel(), evaluatedProfile.orientationSupport(state)),
                evaluatedProfile.ownership(), false, new double[][] {new double[0]}));
        }

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(inclined,
            parameters, org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults());
        List<MetricPoint> points = result.mapPath().orElseThrow().points();

        assertAll(
            () -> assertEquals(2, firstCenters.size()),
            () -> assertEquals(0.0, firstCenters.get(0), 0.55),
            () -> assertEquals(6.0, firstCenters.get(1), 0.55),
            () -> assertTrue(firstInclined.orientationSupport().supportedDirectionsRadians().stream()
                .anyMatch(bearing -> undirectedDistance(bearing, Math.toRadians(30.0)) < 0.03)),
            () -> assertTrue(points.get(0).yMeters() > 54.0),
            () -> assertTrue(points.get(1).yMeters() > 59.0),
            () -> assertTrue(result.mapPath().orElseThrow().energy() < 0.01,
                "the production orientation cost must accept the inclined branch: "
                    + result.mapPath().orElseThrow().energy()));
    }

    @Test
    void explicitLegacyPointDirectionsReachProductionEvaluationAndPairEnergy() {
        EvidenceModelParameters parameters = new EvidenceModelParameters(
            "legacy-orientation-compatibility-test", 0.0, 0.0, 0.0, 1.0, 0.0,
            Math.toRadians(30.0), 1.0, 1e-9);
        List<InferenceProfile> evaluated = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            ProbabilisticProfile profile = new ProbabilisticProfile(index, index * 10.0,
                new MetricPoint(index * 10.0, 0.0), new MetricPoint(0.0, 1.0),
                -1.0, 1.0, 1.0, true, 0.02,
                List.of(new ProbabilisticProfile.Sample(-1.0, 0.8, true),
                    new ProbabilisticProfile.Sample(0.0, 0.8, true),
                    new ProbabilisticProfile.Sample(1.0, 0.8, true)),
                List.of(new ProbabilisticProfile.Mode("legacy", "legacy-lineage",
                    -0.25, 0.25, 0.5, 1.0, 1.0, List.of(0.0), List.of(0.0), false)),
                List.of(), OptionalDouble.of(0.0), List.of(Math.PI / 2.0), 1.0);
            ProbabilisticStateLattice lattice = new ProbabilisticStateBuilder().build(profile, 8)
                .lattice().orElseThrow();
            evaluated.add(new ProbabilisticObservationModel().evaluate(profile, lattice, parameters));
        }

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(evaluated,
            parameters, org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults());

        assertEquals(ImageOrientationSupport.Status.LEGACY_POINT_DIRECTIONS,
            evaluated.get(0).orientationSupport(0).status());
        assertEquals(10.0, result.mapPath().orElseThrow().energy(), 1e-12);
    }

    @Test
    void localModeAdmissionIsInvariantToADistantCompetitorAndRejectsNoiseAndFlatness() {
        EvidenceSnapshot weakOnly = snapshot((x, y) -> 0.02 + 0.08 * gaussian(y, 46.0, 1.2));
        EvidenceSnapshot withCompetitor = snapshot((x, y) -> Math.min(1.0, 0.02
            + 0.08 * gaussian(y, 46.0, 1.2) + 0.98 * gaussian(y, 55.0, 1.2)));
        EvidenceSnapshot noisy = snapshot((x, y) -> 0.02 + (y % 2 == 0 ? 0.0 : 0.01));
        EvidenceSnapshot flat = snapshot((x, y) -> 0.02);

        List<Double> alone = modeCenters(centerProfile(weakOnly));
        List<Double> together = modeCenters(centerProfile(withCompetitor));
        ProbabilisticProfile noisyProfile = centerProfile(noisy);
        ProbabilisticProfile flatProfile = centerProfile(flat);

        assertAll(
            () -> assertEquals(1, alone.size()),
            () -> assertEquals(-4.0, alone.get(0), 0.55),
            () -> assertTrue(together.stream().anyMatch(value -> Math.abs(value + 4.0) <= 0.55),
                "the unchanged weak local mode must survive a distant ridge"),
            () -> assertTrue(noisyProfile.modes().isEmpty(), "alternating sample noise is not a mode"),
            () -> assertTrue(flatProfile.modes().isEmpty(), "flat evidence is not a mode"));
    }

    @Test
    void descriptorResourceExhaustionPropagatesThroughTheActualEngine() {
        EngineFixture fixture = engineFixture();
        EvidenceModelParameters limited = withDescriptorBudget(36);
        TraceHypothesisSet exhausted = new ProbabilisticTraceEngine("scalar", limited).trace(
            fixture.request(), fixture.evidence(), fixture.network(), CancellationProbe.NONE);
        TraceHypothesisSet cancelled = new ProbabilisticTraceEngine("scalar").trace(
            fixture.request(), fixture.evidence(), fixture.network(), () -> true);
        TraceHypothesisSet normal = new ProbabilisticTraceEngine("scalar").trace(
            fixture.request(), fixture.evidence(), fixture.network(), CancellationProbe.NONE);

        assertAll(
            () -> assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, exhausted.status()),
            () -> assertTrue(exhausted.alternativesTruncated()),
            () -> assertTrue(exhausted.explanation().contains("orientation descriptor")),
            () -> assertEquals(TraceHypothesisSet.Status.CANCELLED, cancelled.status()),
            () -> assertEquals(TraceHypothesisSet.Status.COMPLETE, normal.status()),
            () -> assertFalse(normal.hypotheses().isEmpty()));
    }

    private static ProbabilisticProfile centerProfile(EvidenceSnapshot evidence) {
        return new ProbabilisticProfileFactory().create(
            List.of(new MetricPoint(10, 50), new MetricPoint(110, 50)),
            10.0, 7.0, false, evidence, evidence.fields().get("scalar"))
            .stream().min(Comparator.comparingDouble(profile ->
                Math.abs(profile.anchor().xMeters() - 60.0))).orElseThrow();
    }

    private static List<Double> modeCenters(ProbabilisticProfile profile) {
        return profile.modes().stream().map(ProbabilisticProfile.Mode::coreCenterMeters)
            .sorted().toList();
    }

    private static EvidenceModelParameters withDescriptorBudget(int budget) {
        EvidenceModelParameters defaults = EvidenceModelParameters.defaults();
        EvidenceModelParameters.Localization localization = defaults.localization();
        return new EvidenceModelParameters("descriptor-budget-test", defaults.dataWeight(),
            defaults.centerWeight(), defaults.turnWeight(), defaults.orientationWeight(),
            defaults.guideWeight(), defaults.turnScaleRadians(), defaults.temperature(),
            defaults.ambiguityEnergyDelta(), new EvidenceModelParameters.Localization(
                localization.orientationHeadingCount(), localization.minimumOrientationRayMeters(),
                localization.orientationRayLengthPitches(), localization.maximumOrientationStepPitches(),
                localization.minimumOrientationValidFraction(), localization.orientationBackgroundQuantile(),
                localization.orientationProminenceFraction(), budget,
                localization.localModeProminenceFraction(), localization.localModeShoulderFraction(),
                localization.localModeCoreFraction(), localization.routeProfileHalfWidthMeters()));
    }

    private static EngineFixture engineFixture() {
        EvidenceSnapshot evidence = snapshot((x, y) -> 0.02 + 0.8 * gaussian(y, 50.0, 1.2));
        LocalMetricFrame frame = evidence.coordinateFrame();
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 101);
        PrimitiveKey last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 102);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 103);
        Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>();
        primitives.put(first, new DetachedNode(first, frame.toGeographic(new MetricPoint(10, 50)),
            Map.of(), false, false));
        primitives.put(last, new DetachedNode(last, frame.toGeographic(new MetricPoint(110, 50)),
            Map.of(), false, false));
        primitives.put(way, new DetachedWay(way, List.of(first, last), Map.of("highway", "path"),
            false, false));
        MetricRegion region = MetricRegion.rectangle(0, 0, WIDTH - 1, HEIGHT - 1);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
            "localization-correction-v1", primitives.keySet(), Set.of(way), Set.of(), Set.of(first, last),
            Set.of(), Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region,
            false, true, true, true);
        NetworkSnapshot network = new NetworkSnapshot("localization-network",
            SnapshotRole.CAPTURED_BEFORE, "localization-dataset", 1, closure, primitives,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                .closedWorldReferrerWatches(primitives));
        ProbabilisticProfileFactory factory = new ProbabilisticProfileFactory();
        List<MetricPoint> source = List.of(new MetricPoint(10, 50), new MetricPoint(110, 50));
        var chainage = factory.profileChainage(source, 10.0);
        TraceRequest request = new TraceRequest(way, new OccurrenceRange(0, 1),
            TrackerMode.PROBABILISTIC, AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(7.0),
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),
            evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
            "settings", "parameters", "sampler", 10.0, chainage, evidence.resolution());
        return new EngineFixture(request, evidence, network);
    }

    private static EvidenceSnapshot snapshot(Intensity intensity) {
        double[] values = new double[WIDTH * HEIGHT];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                values[y * WIDTH + x] = Math.max(0.0, Math.min(1.0, intensity.value(x, y)));
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
        RasterMetricTransform transform = new RasterMetricTransform("localization-correction-raster-v1",
            RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, new MetricPoint(0, 0),
            1, 0, 0, 1, 1);
        MetricRegion region = MetricRegion.rectangle(0, 0, WIDTH - 1, HEIGHT - 1);
        return new EvidenceSnapshot("localization-evidence", frame, transform,
            EvidenceResolution.nativeSource(1, 1), region, region, Map.of("scalar", field),
            "synthetic-localization-correction");
    }

    private static double gaussian(double value, double center, double sigma) {
        double normalized = (value - center) / sigma;
        return Math.exp(-0.5 * normalized * normalized);
    }

    private static double undirectedDistance(double first, double second) {
        double difference = Math.abs(first - second) % Math.PI;
        return Math.min(difference, Math.PI - difference);
    }

    private record EngineFixture(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network) { }

    @FunctionalInterface
    private interface Intensity {
        double value(double x, double y);
    }
}
