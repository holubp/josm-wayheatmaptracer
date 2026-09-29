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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

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
        EvidenceSnapshot strongOnly = snapshot((x, y) -> 0.02 + 0.80 * gaussian(y, 46.0, 1.2));
        EvidenceSnapshot weakOnly = snapshot((x, y) -> 0.02 + 0.08 * gaussian(y, 46.0, 1.2));
        EvidenceSnapshot withCompetitor = snapshot((x, y) -> Math.min(1.0, 0.02
            + 0.08 * gaussian(y, 46.0, 1.2) + 0.98 * gaussian(y, 55.0, 1.2)));
        EvidenceSnapshot noisy = snapshot((x, y) -> 0.02 + (y % 2 == 0 ? 0.0 : 0.01));
        EvidenceSnapshot flat = snapshot((x, y) -> 0.02);

        ProbabilisticProfile strongProfile = centerProfile(strongOnly);
        ProbabilisticProfile weakProfile = centerProfile(weakOnly);
        ProbabilisticProfile competitorProfile = centerProfile(withCompetitor);
        List<Double> alone = modeCenters(weakProfile);
        List<Double> together = modeCenters(competitorProfile);
        ProbabilisticProfile noisyProfile = centerProfile(noisy);
        ProbabilisticProfile flatProfile = centerProfile(flat);
        ProbabilisticProfile.Mode strongMode = nearestMode(strongProfile, -4.0);
        ProbabilisticProfile.Mode weakMode = nearestMode(weakProfile, -4.0);
        ProbabilisticProfile.Mode competitorWeakMode = nearestMode(competitorProfile, -4.0);

        assertAll(
            () -> assertEquals(1, alone.size()),
            () -> assertEquals(-4.0, alone.get(0), 0.55),
            () -> assertTrue(together.stream().anyMatch(value -> Math.abs(value + 4.0) <= 0.55),
                "the unchanged weak local mode must survive a distant ridge"),
            () -> assertTrue(weakMode.positionalReliability() + 1e-12 >= Math.pow(
                weakMode.scalarAmplitudeReliability(), 64.0)),
            () -> assertTrue(weakMode.positionalReliability()
                <= Math.pow(weakMode.scalarAmplitudeReliability(), 4.0) + 1e-12),
            () -> assertTrue(strongMode.positionalReliability() > weakMode.positionalReliability()),
            () -> assertEquals(weakMode.scalarAmplitudeReliability(),
                competitorWeakMode.scalarAmplitudeReliability(), 0.03),
            () -> assertEquals(weakMode.positionalReliability(),
                competitorWeakMode.positionalReliability(), 0.05),
            () -> assertTrue(noisyProfile.modes().isEmpty(), "alternating sample noise is not a mode"),
            () -> assertTrue(flatProfile.modes().isEmpty(), "flat evidence is not a mode"));
    }

    @Test
    void strongTwelveMeterKinkSurvivesTheActualFactoryObservationAndSolverPath() {
        EvidenceSnapshot evidence = snapshot((x, y) ->
            0.02 + 0.80 * gaussian(y, shortKinkY(x), 0.9));
        EngineFixture fixture = engineFixture(evidence, 5.0);

        ModernTracePipeline pipeline = new ModernTracePipeline(
                (request, ignoredEvidence, network, cancellation) -> {
                    throw new AssertionError("Engine A must not run for an explicit B request");
                });
        ModernTracePipeline.Result completed = pipeline.run(fixture.request(), fixture.evidence(),
                fixture.network(), new ModernTracePipeline.Options("scalar",
                        GeometryCleanupConfig.disabled(), "native", 0), CancellationProbe.NONE);
        List<MetricPoint> points = completed.routes().get(0).hypothesis().points();
        MetricPoint apex = points.stream().min(Comparator.comparingDouble(point ->
            Math.abs(point.xMeters() - 60.0))).orElseThrow();

        assertTrue(completed.inference().status() == TraceHypothesisSet.Status.COMPLETE
                || completed.inference().status() == TraceHypothesisSet.Status.AMBIGUOUS,
            () -> "supported kink produced no reviewable route: "
                + completed.inference().explanation());
        assertTrue(apex.yMeters() > 53.0, "strong short kink was flattened: " + apex);
        assertTrue(completed.routes().get(0).quality().disposition()
                != FinalGeometryEvaluator.Disposition.HARD_BLOCKED);
        assertFalse(completed.routes().get(0).quality().has(
                FinalGeometryEvaluator.FindingCode.UNSUPPORTED_ISOLATED_EXCURSION));
    }

    @Test
    void faintConcentratedTwelveMeterKinkRetainsItsSupportedApexAndFixedEndpoints() {
        EvidenceSnapshot evidence = snapshot((x, y) ->
                0.02 + 0.08 * gaussian(y, shortKinkY(x), 0.9));
        ModernTracePipeline.Route route = tracedRoute(evidence,
                GeometryCleanupConfig.disabled().withMode(
                        GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE), 3.0);
        List<MetricPoint> points = route.hypothesis().points();
        MetricPoint apex = points.stream().min(Comparator.comparingDouble(point ->
                Math.abs(point.xMeters() - 60.0))).orElseThrow();

        assertTrue(apex.yMeters() > 52.0, "faint supported kink was flattened: " + points);
        assertEquals(new MetricPoint(10.0, 50.0), points.get(0));
        assertEquals(new MetricPoint(110.0, 50.0), points.get(points.size() - 1));
        assertEquals(ImageSupportedLocalCleanup.Status.SKIPPED, route.cleanupStatus());
        assertFalse(route.quality().has(
                FinalGeometryEvaluator.FindingCode.UNSUPPORTED_ISOLATED_EXCURSION));
    }

    @Test
    void faintCoherentCurveMovesWhileAlternatingFlecksLoseHighFrequencyZigzags() {
        EvidenceSnapshot coherent = snapshot((x, y) -> 0.02 + 0.08 * gaussian(y,
            50.0 + 3.0 * Math.sin(Math.PI * (x - 10.0) / 100.0), 0.9));
        EvidenceSnapshot alternating = snapshot((x, y) -> 0.02 + 0.08 * gaussian(y,
            50.0 + ((int) Math.floor(x / 10.0) % 2 == 0 ? -3.0 : 3.0), 0.9));

        GeometryCleanupConfig requestedCleanup = GeometryCleanupConfig.disabled().withMode(
                GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE);
        ModernTracePipeline.Route coherentRoute = tracedRoute(coherent, requestedCleanup);
        ModernTracePipeline.Route alternatingRoute = tracedRoute(alternating, requestedCleanup);
        List<MetricPoint> coherentPoints = coherentRoute.hypothesis().points();
        List<MetricPoint> alternatingPoints = alternatingRoute.hypothesis().points();
        double coherentExcursion = coherentPoints.stream()
            .mapToDouble(point -> Math.abs(point.yMeters() - 50.0)).max().orElseThrow();
        double coherentCorrelation = shapeCorrelation(coherentPoints,
                point -> 3.0 * Math.sin(Math.PI * (point.xMeters() - 10.0) / 100.0));
        int alternatingReversals = abruptReversalCount(alternatingPoints);
        double alternatingExcursion = alternatingPoints.stream()
                .mapToDouble(point -> Math.abs(point.yMeters() - 50.0)).max().orElseThrow();
        double alternatingJump = maximumAdjacentLateralJump(alternatingPoints);
        double alternatingShortWaveRms = shortWaveRms(alternatingPoints);

        assertTrue(coherentExcursion >= 2.5,
            "faint spatially coherent evidence lost amplitude: " + coherentPoints);
        assertTrue(coherentCorrelation >= 0.95,
            "faint spatially coherent evidence lost its shape: " + coherentPoints);
        assertTrue(alternatingReversals <= 1,
            "alternating faint flecks retained reversals: " + alternatingPoints);
        assertTrue(alternatingExcursion <= 0.75,
            "alternating faint flecks caused a broad lateral drift: " + alternatingPoints);
        assertTrue(alternatingJump <= 0.75,
            "alternating faint flecks retained an adjacent jump: " + alternatingPoints);
        assertTrue(alternatingShortWaveRms <= 0.5,
            "alternating faint flecks retained short-wave energy: " + alternatingPoints);
        assertEquals(ImageSupportedLocalCleanup.Status.SKIPPED, coherentRoute.cleanupStatus());
        assertEquals(ImageSupportedLocalCleanup.Status.SKIPPED, alternatingRoute.cleanupStatus());
        assertEquals(1.0, coherentRoute.hypothesis().diagnostics()
                .get("cleanupSuppressedForDirectReliability"));
        assertEquals(1.0, alternatingRoute.hypothesis().diagnostics()
                .get("cleanupSuppressedForDirectReliability"));
        assertTrue(coherentRoute.hypothesis().support().stream()
                .anyMatch(ownership -> ownership == ObservationOwnership.DIRECT_TWO_SIDED));
        assertFalse(alternatingRoute.hypothesis().support().stream()
                .anyMatch(ownership -> ownership == ObservationOwnership.DIRECT_TWO_SIDED),
                "positive background pixels must not claim direct support");
    }

    @Test
    void directReliabilityUsesPhysicalWindowsAcrossSamplingStepsAndSourcePitches() {
        Intensity curve = (x, y) -> 0.02 + 0.08 * gaussian(y,
                50.0 + 3.0 * Math.sin(Math.PI * (x - 10.0) / 100.0), 0.9);
        List<MetricPoint> source = List.of(new MetricPoint(10, 50), new MetricPoint(110, 50));
        ProbabilisticProfileFactory factory = new ProbabilisticProfileFactory();

        double stepFive = centerReliability(factory.create(source, 5.0, 7.0, false,
                snapshot(curve, 1.0), snapshot(curve, 1.0).fields().get("scalar")));
        EvidenceSnapshot pitchOne = snapshot(curve, 1.0);
        EvidenceSnapshot pitchTwo = snapshot(curve, 2.0);
        double stepTen = centerReliability(factory.create(source, 10.0, 7.0, false,
                pitchOne, pitchOne.fields().get("scalar")));
        double coarseSource = centerReliability(factory.create(source, 10.0, 7.0, false,
                pitchTwo, pitchTwo.fields().get("scalar")));

        assertTrue(stepFive > 0.02 && stepTen > 0.02 && coarseSource > 0.01);
        assertEquals(stepTen, stepFive, 0.025,
                "physical continuation should not depend strongly on profile spacing");
        assertEquals(stepTen, coarseSource, 0.05,
                "source-pixel uncertainty may soften, but not erase, coherent evidence");
    }

    @Test
    void directReliabilityRejectsBlankInvalidAndUnauthorizedLongitudinalGaps() {
        Intensity ridge = (x, y) -> 0.02 + 0.08 * gaussian(y, 50.0, 0.9);
        Intensity blankGap = (x, y) -> x >= 57.0 && x <= 58.0 ? 0.02 : ridge.value(x, y);
        MetricRegion fullRegion = MetricRegion.rectangle(0, 0, WIDTH - 1, HEIGHT - 1);
        MetricRegion notchedDecision = new MetricRegion(List.of(
                List.of(new MetricPoint(0, 0), new MetricPoint(56, 0),
                        new MetricPoint(56, HEIGHT - 1), new MetricPoint(0, HEIGHT - 1)),
                List.of(new MetricPoint(59, 0), new MetricPoint(WIDTH - 1, 0),
                        new MetricPoint(WIDTH - 1, HEIGHT - 1),
                        new MetricPoint(59, HEIGHT - 1))));
        EvidenceSnapshot complete = snapshot(ridge, 1.0, (x, y) -> true, fullRegion);
        EvidenceSnapshot blank = snapshot(blankGap, 1.0, (x, y) -> true, fullRegion);
        EvidenceSnapshot invalid = snapshot(ridge, 1.0,
                (x, y) -> x < 57 || x > 58, fullRegion);
        EvidenceSnapshot unauthorized = snapshot(ridge, 1.0,
                (x, y) -> true, notchedDecision);
        List<MetricPoint> source = List.of(new MetricPoint(10, 50), new MetricPoint(110, 50));
        ProbabilisticProfileFactory factory = new ProbabilisticProfileFactory();

        double completeSupport = centerMode(factory.create(source, 5.0, 7.0, false,
                complete, complete.fields().get("scalar"))).branchCoherence();
        double blankSupport = centerMode(factory.create(source, 5.0, 7.0, false,
                blank, blank.fields().get("scalar"))).branchCoherence();
        double invalidSupport = centerMode(factory.create(source, 5.0, 7.0, false,
                invalid, invalid.fields().get("scalar"))).branchCoherence();
        double unauthorizedSupport = centerMode(factory.create(source, 5.0, 7.0, false,
                unauthorized, unauthorized.fields().get("scalar"))).branchCoherence();

        assertTrue(completeSupport > 0.9);
        assertEquals(0.0, blankSupport, 0.0,
                "blank pixels between modes cannot count as direct continuation");
        assertEquals(0.0, invalidSupport, 0.0,
                "invalid raster cells between modes cannot count as direct continuation");
        assertEquals(0.0, unauthorizedSupport, 0.0,
                "a decision-region notch must break direct continuation");
    }

    @Test
    void hybridBaselinePolicyDoesNotConsumeDirectLongitudinalReliability() {
        EvidenceSnapshot evidence = snapshot((x, y) -> 0.02 + 0.08 * gaussian(y,
                50.0 + ((int) Math.floor(x / 10.0) % 2 == 0 ? -3.0 : 3.0), 0.9));
        List<MetricPoint> source = List.of(new MetricPoint(10, 50), new MetricPoint(110, 50));
        ProbabilisticProfileFactory factory = new ProbabilisticProfileFactory();
        var chainage = factory.profileChainage(source, 10.0);
        List<ProbabilisticProfile> direct = factory.create(source, chainage, 7.0, false,
                evidence, evidence.fields().get("scalar"), EvidenceModelParameters.defaults(),
                CancellationProbe.NONE, true);
        List<ProbabilisticProfile> baseline = factory.create(source, chainage, 7.0, false,
                evidence, evidence.fields().get("scalar"), EvidenceModelParameters.defaults(),
                CancellationProbe.NONE, false);
        ProbabilisticProfile.Mode directMode = centerMode(direct);
        ProbabilisticProfile.Mode baselineMode = centerMode(baseline);
        double expectedBaseline = baselineMode.scalarAmplitudeReliability()
                + (1.0 - baselineMode.scalarAmplitudeReliability())
                        * baselineMode.branchCoherence();

        assertTrue(directMode.positionalReliability() < 1e-10);
        assertEquals(expectedBaseline, baselineMode.positionalReliability(), 1e-12);
        assertTrue(baselineMode.positionalReliability() > directMode.positionalReliability());
    }

    @Test
    void allMissingActualEngineReturnsNoRouteWithoutExportingUnusablePaths() {
        EngineFixture fixture = engineFixture(snapshot((x, y) -> 0.02));

        TraceHypothesisSet result = new ProbabilisticTraceEngine("scalar").trace(
                fixture.request(), fixture.evidence(), fixture.network(), CancellationProbe.NONE);

        assertEquals(TraceHypothesisSet.Status.NO_ROUTE, result.status());
        assertTrue(result.hypotheses().isEmpty());
        assertEquals("all profiles lack localized evidence", result.explanation());
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
            () -> assertFalse(exhausted.alternativesTruncated(),
                "native resource exhaustion is not an alternative-cap proof"),
            () -> assertTrue(exhausted.hypotheses().isEmpty()),
            () -> assertTrue(exhausted.explanation().contains("orientation descriptor")),
            () -> assertEquals(TraceHypothesisSet.Status.CANCELLED, cancelled.status()),
            () -> assertEquals(TraceHypothesisSet.Status.COMPLETE, normal.status()),
            () -> assertFalse(normal.hypotheses().isEmpty()));
    }

    private static double centerReliability(List<ProbabilisticProfile> profiles) {
        return centerMode(profiles).positionalReliability();
    }

    private static ProbabilisticProfile.Mode centerMode(List<ProbabilisticProfile> profiles) {
        ProbabilisticProfile profile = profiles.stream().min(Comparator.comparingDouble(candidate ->
                Math.abs(candidate.anchor().xMeters() - 60.0))).orElseThrow();
        return profile.modes().stream().max(Comparator.comparingDouble(
                ProbabilisticProfile.Mode::existenceConfidence)).orElseThrow();
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

    private static ProbabilisticProfile.Mode nearestMode(
        ProbabilisticProfile profile, double centerMeters) {
        return profile.modes().stream().min(Comparator.comparingDouble(mode ->
            Math.abs(mode.coreCenterMeters() - centerMeters))).orElseThrow();
    }

    private static ModernTracePipeline.Route tracedRoute(EvidenceSnapshot evidence,
            GeometryCleanupConfig cleanup) {
        return tracedRoute(evidence, cleanup, 10.0);
    }

    private static ModernTracePipeline.Route tracedRoute(EvidenceSnapshot evidence,
            GeometryCleanupConfig cleanup, double sampleStepMeters) {
        EngineFixture fixture = engineFixture(evidence, sampleStepMeters);
        ModernTracePipeline.Result result = new ModernTracePipeline(
                (request, ignoredEvidence, network, cancellation) -> {
                    throw new AssertionError("Engine A must not run for an explicit B request");
                }).run(fixture.request(), fixture.evidence(), fixture.network(),
                        new ModernTracePipeline.Options("scalar", cleanup, "native", 0),
                        CancellationProbe.NONE);
        assertTrue(result.inference().status() == TraceHypothesisSet.Status.COMPLETE
            || result.inference().status() == TraceHypothesisSet.Status.AMBIGUOUS,
            "unexpected trace status: " + result.inference().status());
        assertFalse(result.routes().isEmpty());
        return result.routes().get(0);
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
        return engineFixture(snapshot((x, y) -> 0.02 + 0.8 * gaussian(y, 50.0, 1.2)));
    }

    private static EngineFixture engineFixture(EvidenceSnapshot evidence) {
        return engineFixture(evidence, 10.0);
    }

    private static EngineFixture engineFixture(EvidenceSnapshot evidence, double sampleStepMeters) {
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
        var chainage = factory.profileChainage(source, sampleStepMeters);
        TraceRequest request = new TraceRequest(way, new OccurrenceRange(0, 1),
            TrackerMode.PROBABILISTIC, AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(7.0),
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),
            evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
            "settings", "parameters", "sampler", sampleStepMeters, chainage, evidence.resolution());
        return new EngineFixture(request, evidence, network);
    }

    private static EvidenceSnapshot snapshot(Intensity intensity) {
        return snapshot(intensity, 1.0);
    }

    private static EvidenceSnapshot snapshot(Intensity intensity, double sourcePitchMeters) {
        return snapshot(intensity, sourcePitchMeters, (x, y) -> true,
                MetricRegion.rectangle(0, 0, WIDTH - 1, HEIGHT - 1));
    }

    private static EvidenceSnapshot snapshot(Intensity intensity, double sourcePitchMeters,
            Validity validity, MetricRegion decisionRegion) {
        double[] values = new double[WIDTH * HEIGHT];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                values[y * WIDTH + x] = Math.max(0.0, Math.min(1.0, intensity.value(x, y)));
                valid[y * WIDTH + x] = validity.valid(x, y);
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
        MetricRegion evidenceRegion = MetricRegion.rectangle(0, 0, WIDTH - 1, HEIGHT - 1);
        return new EvidenceSnapshot("localization-evidence", frame, transform,
            EvidenceResolution.nativeSource(sourcePitchMeters, 1), decisionRegion, evidenceRegion,
            Map.of("scalar", field),
            "synthetic-localization-correction");
    }

    private static double gaussian(double value, double center, double sigma) {
        double normalized = (value - center) / sigma;
        return Math.exp(-0.5 * normalized * normalized);
    }

    private static double shortKinkY(double x) {
        if (x <= 54.0 || x >= 66.0) {
            return 50.0;
        }
        if (x <= 60.0) {
            return 50.0 + (2.0 / 3.0) * (x - 54.0);
        }
        return 54.0 - (2.0 / 3.0) * (x - 60.0);
    }

    private static double shapeCorrelation(List<MetricPoint> points, ExpectedOffset expected) {
        double meanActual = points.stream().mapToDouble(point -> point.yMeters() - 50.0)
                .average().orElseThrow();
        double meanExpected = points.stream().mapToDouble(expected::value).average().orElseThrow();
        double covariance = 0.0;
        double actualVariance = 0.0;
        double expectedVariance = 0.0;
        for (MetricPoint point : points) {
            double actual = point.yMeters() - 50.0 - meanActual;
            double target = expected.value(point) - meanExpected;
            covariance += actual * target;
            actualVariance += actual * actual;
            expectedVariance += target * target;
        }
        return covariance / Math.sqrt(actualVariance * expectedVariance);
    }

    private static double maximumAdjacentLateralJump(List<MetricPoint> points) {
        double maximum = 0.0;
        for (int index = 1; index < points.size(); index++) {
            maximum = Math.max(maximum,
                    Math.abs(points.get(index).yMeters() - points.get(index - 1).yMeters()));
        }
        return maximum;
    }

    private static double shortWaveRms(List<MetricPoint> points) {
        double sum = 0.0;
        int count = 0;
        for (int index = 1; index + 1 < points.size(); index++) {
            double secondDifference = points.get(index + 1).yMeters()
                    - 2.0 * points.get(index).yMeters()
                    + points.get(index - 1).yMeters();
            sum += secondDifference * secondDifference;
            count++;
        }
        return Math.sqrt(sum / count);
    }

    private static int abruptReversalCount(List<MetricPoint> points) {
        int count = 0;
        for (int index = 1; index + 1 < points.size(); index++) {
            double before = points.get(index).yMeters() - points.get(index - 1).yMeters();
            double after = points.get(index + 1).yMeters() - points.get(index).yMeters();
            if (before * after < 0.0 && Math.min(Math.abs(before), Math.abs(after)) >= 0.75) {
                count++;
            }
        }
        return count;
    }

    private static double undirectedDistance(double first, double second) {
        double difference = Math.abs(first - second) % Math.PI;
        return Math.min(difference, Math.PI - difference);
    }

    private record EngineFixture(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network) { }

    @FunctionalInterface
    private interface ExpectedOffset {
        double value(MetricPoint point);
    }

    @FunctionalInterface
    private interface Validity {
        boolean valid(int x, int y);
    }

    @FunctionalInterface
    private interface Intensity {
        double value(double x, double y);
    }
}
