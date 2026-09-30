package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

class V022WeakSignalReliabilityTest {
    @Test
    void manyPeakExtractionReleasesHelperScratchBetweenPeaks() {
        List<LocalScalarProfileExtractor.Sample> samples = new ArrayList<>();
        for (int index = 0; index <= 160; index++) {
            double phase = index % 16;
            double intensity = 0.02 + 0.85 * Math.exp(-0.5
                    * Math.pow((phase - 8.0) / 1.2, 2.0));
            samples.add(new LocalScalarProfileExtractor.Sample(index * 0.25,
                    intensity, true));
        }
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();

        LocalScalarProfileExtractor.Result result = new LocalScalarProfileExtractor().extract(
                samples, 1.0, EvidenceModelParameters.defaults().localization(), owner);

        assertTrue(result.modes().size() >= 5, "fixture must exercise repeated peak helpers");
        assertTrue(owner.currentBytes() > 0L, "retained mode graph remains charged");
        assertTrue(owner.peakBytes() > owner.currentBytes(),
                "profile and per-peak scratch must be released after extraction");
        assertTrue(owner.peakBytes() - owner.currentBytes() < 20_000L,
                "helper scratch is simultaneous rather than cumulative across peaks");
        owner.close();
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void scalarAmplitudeReliabilityFallsContinuouslyWithProminence() {
        LocalScalarProfileExtractor extractor = new LocalScalarProfileExtractor();
        double strong = extractedMode(extractor, 0.80).scalarAmplitudeReliability();
        double faint = extractedMode(extractor, 0.08).scalarAmplitudeReliability();
        double trace = extractedMode(extractor, 0.008).scalarAmplitudeReliability();

        assertTrue(strong > faint);
        assertTrue(faint > trace);
        assertTrue(trace > 0.0, "positive admitted evidence must attenuate continuously");
    }

    @Test
    void corroborationCannotOverrideTheAbsoluteAmplitudeCap() {
        assertEquals(Math.pow(0.90, 64.0),
                ProbabilisticProfile.Mode.combineReliability(0.90, 0.0), 1e-12);
        assertEquals(0.6561, ProbabilisticProfile.Mode.combineReliability(0.90, 1.0), 1e-12);
        assertEquals(0.00008000000000002,
                ProbabilisticProfile.Mode.combineReliability(0.10, 0.80), 1e-12);
        assertEquals(0.0, ProbabilisticProfile.Mode.combineReliability(0.0, 1.0), 0.0);
        assertTrue(ProbabilisticProfile.Mode.combineReliability(0.20, 1.0)
                > ProbabilisticProfile.Mode.combineReliability(0.20, 0.0));
    }

    @Test
    void zeroReliabilityTransfersAllMeasuredPriorToUniformWithoutAForceFloor() {
        ProbabilisticProfile profile = profile(mode("weak", 0.0, 0.0));
        ProbabilisticStateLattice lattice = new ProbabilisticStateBuilder().build(profile, 24)
                .lattice().orElseThrow();

        double[] priors = ProbabilisticObservationModel.effectiveComponentPriors(profile,
                lattice.components());
        int measured = componentIndex(lattice, ObservationComponent.Kind.MEASURED);
        int missing = componentIndex(lattice, ObservationComponent.Kind.MISSING);

        assertEquals(0.0, priors[measured], 0.0);
        assertEquals(1.0, priors[missing], 1e-12);
        assertEquals(1.0, java.util.Arrays.stream(priors).sum(), 1e-12);
    }

    @Test
    void tinyPositiveReliabilityRetainsPositiveMeasuredMassWithoutSubtractiveRounding() {
        for (double reliability : List.of(Math.scalb(1.0, -64), Math.scalb(1.0, -128),
                Math.scalb(1.0, -512))) {
            ProbabilisticProfile profile = profile(mode("tiny", 0.5, reliability));
            ProbabilisticStateLattice lattice = new ProbabilisticStateBuilder().build(profile, 24)
                    .lattice().orElseThrow();
            double[] priors = ProbabilisticObservationModel.effectiveComponentPriors(profile,
                    lattice.components());
            int measured = componentIndex(lattice, ObservationComponent.Kind.MEASURED);

            assertTrue(priors[measured] > 0.0);
            assertEquals(lattice.components().get(measured).priorWeight() * reliability,
                    priors[measured], 0.0);
            assertEquals(1.0, java.util.Arrays.stream(priors).sum(), 1e-12);
        }
    }

    @Test
    void reliabilityTemperingKeepsTheObservationMixtureNormalized() {
        ProbabilisticProfile profile = profile(mode("weak", 0.35, 0.06));
        ProbabilisticStateLattice lattice = new ProbabilisticStateBuilder().build(profile, 24)
                .lattice().orElseThrow();

        InferenceProfile evaluated = new ProbabilisticObservationModel().evaluate(
                profile, lattice, EvidenceModelParameters.defaults());
        double integral = 0.0;
        for (int state = 0; state < evaluated.cells().size(); state++) {
            integral += Math.exp(-evaluated.unaryCost(state))
                    * evaluated.cells().get(state).quadratureWidthMeters();
        }

        assertEquals(1.0, integral, 1e-12,
                "attenuation must remain a normalized observation model");
    }

    @Test
    void reliabilityChangesPriorsWithoutChangingTheAdmittedLattice() {
        ProbabilisticStateBuilder builder = new ProbabilisticStateBuilder();
        ProbabilisticStateLattice reliable = builder.build(profile(mode("branch", 0.35, 1.0)), 24)
                .lattice().orElseThrow();
        ProbabilisticStateLattice attenuated = builder.build(profile(mode("branch", 0.35, 0.0)), 24)
                .lattice().orElseThrow();

        assertEquals(reliable.cells(), attenuated.cells());
        assertEquals(reliable.components(), attenuated.components());
    }

    @Test
    void directContinuationRequiresMeasuredUnambiguousTriplets() {
        ImageOrientationSupport measured = measuredHorizontal();
        ImageOrientationSupport unavailable = ImageOrientationSupport.unknown(
                ImageOrientationSupport.Status.INVALID_CENTER);
        List<ProbabilisticProfile> coherent = profileSeries(
                List.of(0.0, 0.0, 0.0, 0.0, 0.0), measured, -1);
        List<ProbabilisticProfile> alternating = profileSeries(
                List.of(-3.0, 3.0, -3.0, 3.0, -3.0), measured, -1);
        List<ProbabilisticProfile> interrupted = profileSeries(
                List.of(0.0, 0.0, 0.0, 0.0, 0.0), measured, 2);
        interrupted = replaceOrientation(interrupted, 2, unavailable);
        List<ProbabilisticProfile> tied = profileSeriesWithDuplicateModes(measured);

        ProbabilisticProfile.Mode coherentCenter = appliedCenter(coherent);
        ProbabilisticProfile.Mode alternatingCenter = appliedCenter(alternating);
        ProbabilisticProfile.Mode interruptedCenter = appliedCenter(interrupted);
        ProbabilisticProfile.Mode tiedCenter = appliedCenter(tied);

        assertTrue(coherentCenter.branchCoherence() > 0.95);
        assertTrue(alternatingCenter.branchCoherence() < 1e-6);
        assertEquals(0.0, interruptedCenter.branchCoherence(), 0.0);
        assertEquals(0.0, tiedCenter.branchCoherence(), 0.0,
                "equal reciprocal alternatives must not manufacture continuation");
    }

    @Test
    void directContinuationUsesTheSamePhysicalSupportAtAllSupportedSampleSteps() {
        List<Double> reliabilities = List.of(0.20, 0.24, 0.25, 0.26, 0.5, 1.56, 5.0, 10.0).stream()
                .map(step -> appliedCenter(coherentSeries(step)).branchCoherence())
                .toList();

        reliabilities.forEach(value -> assertTrue(value > 0.999999,
                "bounded physical continuation must normalize observed kernel mass: " + reliabilities));
        assertEquals(reliabilities.get(0), reliabilities.get(reliabilities.size() - 1), 1e-12);
    }

    @Test
    void unobservedConnectionCannotContributeDirectContinuation() {
        List<ProbabilisticProfile> profiles = coherentSeries(5.0);

        ProbabilisticProfile.Mode center = LongitudinalModeReliability.apply(profiles,
                (leftProfile, leftMode, rightProfile, rightMode, cancellation) -> 0.0,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                        .CancellationProbe.NONE)
                .profiles().get(profiles.size() / 2).modes().get(0);

        assertEquals(0.0, center.branchCoherence(), 0.0,
                "endpoint modes cannot bridge an interval without direct raster evidence");
    }

    @Test
    void profileGapBeyondPhysicalWindowCannotContributeDirectContinuation() {
        List<ProbabilisticProfile> profiles = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            profiles.add(profileAt(index, index * 25.0,
                    List.of(modeAt(index, 0, 0.0, measuredHorizontal()))));
        }

        assertEquals(0.0, appliedCenter(profiles).branchCoherence(), 0.0);
    }

    @Test
    void censoredNeighborCannotContributeDirectContinuation() {
        List<ProbabilisticProfile> profiles = new ArrayList<>(profileSeries(
                List.of(0.0, 0.0, 0.0, 0.0, 0.0), measuredHorizontal(), -1));
        profiles.set(1, censoredProfileAt(1, 10.0));

        assertEquals(0.0, appliedCenter(profiles).branchCoherence(), 0.0);
    }

    @Test
    void associationBudgetFailsBeforeQuadraticAllocation() {
        List<ProbabilisticProfile.Mode> left = manyModes(1_001, 0);
        List<ProbabilisticProfile.Mode> right = manyModes(1_000, 1);

        assertThrows(LongitudinalModeReliability.ResourceLimitException.class,
                () -> LongitudinalModeReliability.apply(List.of(
                        profileAt(0, 0.0, left), profileAt(1, 1.0, right)),
                        org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                                .CancellationProbe.NONE));
    }

    @Test
    void pairScorePointScratchHasAnExactAdmissionBoundaryAndCleansUpOnRefusal() {
        List<ProbabilisticProfile> profiles = List.of(
                profileAt(0, 0.0, List.of(modeAt(0, 0, 0.0, measuredHorizontal()))),
                profileAt(1, 1.0, List.of(modeAt(1, 0, 0.0, measuredHorizontal()))));

        AttemptMemoryLedger below = new AttemptMemoryLedger(223);
        AttemptMemoryLedger.Owner belowOwner = below.rootOwner();
        ProbabilisticInference.MemoryLimit refused = assertThrows(
                ProbabilisticInference.MemoryLimit.class,
                () -> LongitudinalModeReliability.apply(profiles,
                    (leftProfile, leftMode, rightProfile, rightMode, cancellation) -> 1.0,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                        .CancellationProbe.NONE, belowOwner));
        assertEquals(64L, refused.limitCause().requestedBytes());
        assertEquals(160L, refused.limitCause().currentBytes());
        assertEquals(223L, refused.limitCause().limitBytes());
        assertEquals(160L, below.peakBytes());
        assertEquals(0L, below.currentBytes());

        AttemptMemoryLedger exact = new AttemptMemoryLedger(224);
        AttemptMemoryLedger.Owner exactOwner = exact.rootOwner();
        ProbabilisticInference.MemoryLimit later = assertThrows(
                ProbabilisticInference.MemoryLimit.class,
                () -> LongitudinalModeReliability.apply(profiles,
                    (leftProfile, leftMode, rightProfile, rightMode, cancellation) -> 1.0,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                        .CancellationProbe.NONE, exactOwner));
        assertEquals(224L, later.limitCause().currentBytes(),
                "the pair-score point scratch fits exactly before the next helper is refused");
        assertEquals(224L, exact.peakBytes());
        assertEquals(0L, exact.currentBytes());
    }

    @Test
    void directContinuationChecksCancellationDuringAssociation() {
        assertThrows(CancellationException.class, () -> LongitudinalModeReliability.apply(
                profileSeries(List.of(0.0, 0.0, 0.0), measuredHorizontal(), -1),
                () -> { throw new CancellationException("test"); }));
    }

    @Test
    void rasterIntervalTraversalFindsBrieflyCrossedUnsupportedCellInBothDirections() {
        ScalarEvidenceField field = fieldWithUnsupportedCell(1, 0);
        RasterMetricTransform transform = identityTransform();
        MetricPoint start = new MetricPoint(0.1, 0.09);
        MetricPoint end = new MetricPoint(2.1, 2.09);

        assertFalse(ProbabilisticProfileFactory.supportsEveryCrossedInterpolationCell(
                field, transform, start, end,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                        .CancellationProbe.NONE, ignored -> { }));
        assertFalse(ProbabilisticProfileFactory.supportsEveryCrossedInterpolationCell(
                field, transform, end, start,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                        .CancellationProbe.NONE, ignored -> { }));
    }

    @Test
    void rasterIntervalTraversalHandlesGridBoundariesAndCornersDeterministically() {
        RasterMetricTransform transform = identityTransform();
        ScalarEvidenceField belowBoundaryUnsupported = fieldWithUnsupportedCell(1, 0);
        ScalarEvidenceField ownedBoundaryUnsupported = fieldWithUnsupportedCell(1, 1);
        MetricPoint horizontalStart = new MetricPoint(0.1, 1.0);
        MetricPoint horizontalEnd = new MetricPoint(2.1, 1.0);
        MetricPoint diagonalStart = new MetricPoint(0.1, 0.1);
        MetricPoint diagonalEnd = new MetricPoint(2.1, 2.1);

        assertTrue(ProbabilisticProfileFactory.supportsEveryCrossedInterpolationCell(
                belowBoundaryUnsupported, transform, horizontalStart, horizontalEnd,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                        .CancellationProbe.NONE, ignored -> { }));
        assertFalse(ProbabilisticProfileFactory.supportsEveryCrossedInterpolationCell(
                ownedBoundaryUnsupported, transform, horizontalStart, horizontalEnd,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                        .CancellationProbe.NONE, ignored -> { }));
        assertTrue(ProbabilisticProfileFactory.supportsEveryCrossedInterpolationCell(
                belowBoundaryUnsupported, transform, diagonalStart, diagonalEnd,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                        .CancellationProbe.NONE, ignored -> { }));
        assertFalse(ProbabilisticProfileFactory.supportsEveryCrossedInterpolationCell(
                ownedBoundaryUnsupported, transform, diagonalStart, diagonalEnd,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                        .CancellationProbe.NONE, ignored -> { }));
    }

    @Test
    void rasterTraversalAdmitsItsCompleteBudgetBeforeGeneratingEvents() {
        ScalarEvidenceField field = fieldWithUnsupportedCell(0, 2);
        RasterMetricTransform transform = identityTransform();
        int[] cancellationChecks = {0};

        LongitudinalModeReliability.ResourceLimitException failure = assertThrows(
                LongitudinalModeReliability.ResourceLimitException.class,
                () -> ProbabilisticProfileFactory.supportsEveryCrossedInterpolationCell(
                        field, transform, new MetricPoint(0.1, 0.1),
                        new MetricPoint(2.1, 2.1),
                        () -> {
                            cancellationChecks[0]++;
                            return false;
                        },
                        ignored -> { throw new LongitudinalModeReliability.ResourceLimitException(); }));

        assertEquals("weak-signal association work limit", failure.getMessage());
        assertEquals(2, cancellationChecks[0],
                "only constant endpoint validation may precede budget admission");
    }

    @Test
    void rasterTraversalChecksCancellationWhileGeneratingGridEvents() {
        ScalarEvidenceField field = fieldWithUnsupportedCell(0, 2);
        RasterMetricTransform transform = identityTransform();
        int[] cancellationChecks = {0};

        assertThrows(CancellationException.class,
                () -> ProbabilisticProfileFactory.supportsEveryCrossedInterpolationCell(
                        field, transform, new MetricPoint(0.1, 0.1),
                        new MetricPoint(2.1, 2.1),
                        () -> ++cancellationChecks[0] == 3, ignored -> { }));
    }

    @Test
    void modeReliabilityAttenuatesOrientationPairEnergyWithoutChangingItsStatus() {
        String branch = "weak";
        ImageOrientationSupport vertical = new ImageOrientationSupport(
                ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                List.of(new ImageOrientationSupport.AngularMode(
                        Math.PI / 2.0, Math.PI / 2.0, Math.PI / 2.0, 1.0)), 1.0);
        List<LateralStateCell> cells = List.of(
                new LateralStateCell(0.0, 1.0, false, true, branch));
        InferenceProfile first = new InferenceProfile(0.0, new MetricPoint(0.0, 0.0),
                new MetricPoint(0.0, 1.0), cells, new double[] {0.0},
                Map.of(branch, vertical), Map.of(branch, 1.0),
                ObservationOwnership.DIRECT_TWO_SIDED, false, new double[][] {new double[0]});
        InferenceProfile weak = new InferenceProfile(10.0, new MetricPoint(10.0, 0.0),
                new MetricPoint(0.0, 1.0), cells, new double[] {0.0},
                Map.of(branch, vertical), Map.of(branch, 0.1),
                ObservationOwnership.DIRECT_TWO_SIDED, false, new double[][] {new double[0]});
        EvidenceModelParameters parameters = new EvidenceModelParameters(
                "weak-orientation-reliability-test", 0.0, 0.0, 0.0, 1.0, 0.0,
                Math.toRadians(30.0), 1.0, 1e-9);

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(
                List.of(first, weak), parameters, TraceBudgets.defaults());

        assertEquals(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                weak.orientationSupport(0).status());
        assertEquals(0.1, weak.orientationReliability(0), 0.0);
        assertEquals(1.0, result.mapPath().orElseThrow().energy(), 1e-12);
    }

    private static ScalarEvidenceField fieldWithUnsupportedCell(int unsupportedX,
            int unsupportedY) {
        int width = 4;
        int height = 4;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        boolean[] interpolation = new boolean[(width - 1) * (height - 1)];
        java.util.Arrays.fill(values, 0.5);
        java.util.Arrays.fill(valid, true);
        java.util.Arrays.fill(interpolation, true);
        interpolation[unsupportedY * (width - 1) + unsupportedX] = false;
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "cell-traversal",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        return new ScalarEvidenceField(width, height, values, valid, interpolation, lineage);
    }

    private static RasterMetricTransform identityTransform() {
        return new RasterMetricTransform("interval-cell-traversal",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0.0, 0.0), 1.0, 0.0, 0.0, 1.0, 1.0);
    }

    private static ProbabilisticProfile.Mode appliedCenter(List<ProbabilisticProfile> profiles) {
        return LongitudinalModeReliability.apply(profiles,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE)
                .profiles().get(profiles.size() / 2).modes().get(0);
    }

    private static List<ProbabilisticProfile> coherentSeries(double stepMeters) {
        List<ProbabilisticProfile> result = new ArrayList<>();
        for (int index = 0; index < 81; index++) {
            result.add(profileAt(index, index * stepMeters,
                    List.of(modeAt(index, 0, 0.0, measuredHorizontal()))));
        }
        return List.copyOf(result);
    }

    private static List<ProbabilisticProfile> profileSeries(List<Double> centers,
            ImageOrientationSupport support, int unavailableIndex) {
        List<ProbabilisticProfile> result = new ArrayList<>();
        for (int index = 0; index < centers.size(); index++) {
            ImageOrientationSupport selected = index == unavailableIndex
                    ? ImageOrientationSupport.unknown(ImageOrientationSupport.Status.INVALID_CENTER)
                    : support;
            result.add(profileAt(index, index * 10.0,
                    List.of(modeAt(index, 0, centers.get(index), selected))));
        }
        return List.copyOf(result);
    }

    private static ProbabilisticProfile censoredProfileAt(int index, double chainage) {
        return new ProbabilisticProfile(index, chainage, new MetricPoint(chainage, 0.0),
                new MetricPoint(0.0, 1.0), -7.0, 7.0, 1.0, true, 0.02,
                List.of(new ProbabilisticProfile.Sample(-7.0, 0.02, true),
                        new ProbabilisticProfile.Sample(0.0, 0.10, true),
                        new ProbabilisticProfile.Sample(7.0, 0.02, true)),
                List.of(), List.of(new ProbabilisticProfile.CensoredMode(
                        "censored-" + index, "censored-lineage-" + index,
                        ProbabilisticProfile.CensorSide.LEFT, -7.0, 1.0, true)),
                OptionalDouble.empty(), measuredHorizontal());
    }

    private static List<ProbabilisticProfile.Mode> manyModes(int count, int profile) {
        List<ProbabilisticProfile.Mode> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(modeAt(profile, index, (index % 14) - 7.0, measuredHorizontal()));
        }
        return List.copyOf(result);
    }

    private static List<ProbabilisticProfile> replaceOrientation(
            List<ProbabilisticProfile> profiles, int index, ImageOrientationSupport support) {
        List<ProbabilisticProfile> result = new ArrayList<>(profiles);
        ProbabilisticProfile.Mode mode = result.get(index).modes().get(0);
        result.set(index, profileAt(index, index * 10.0, List.of(modeAt(index, 0,
                mode.coreCenterMeters(), support))));
        return List.copyOf(result);
    }

    private static List<ProbabilisticProfile> profileSeriesWithDuplicateModes(
            ImageOrientationSupport support) {
        List<ProbabilisticProfile> result = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            result.add(profileAt(index, index * 10.0, List.of(
                    modeAt(index, 0, 0.0, support), modeAt(index, 1, 0.0, support))));
        }
        return List.copyOf(result);
    }

    private static ProbabilisticProfile profileAt(int index, double chainage,
            List<ProbabilisticProfile.Mode> modes) {
        return new ProbabilisticProfile(index, chainage, new MetricPoint(chainage, 0.0),
                new MetricPoint(0.0, 1.0), -7.0, 7.0, 1.0, true, 0.02,
                List.of(new ProbabilisticProfile.Sample(-7.0, 0.02, true),
                        new ProbabilisticProfile.Sample(0.0, 0.10, true),
                        new ProbabilisticProfile.Sample(7.0, 0.02, true)),
                modes, List.of(), OptionalDouble.empty(), measuredHorizontal());
    }

    private static ProbabilisticProfile.Mode modeAt(int profile, int mode, double center,
            ImageOrientationSupport support) {
        return new ProbabilisticProfile.Mode("p" + profile + "-m" + mode,
                "lineage-" + profile + "-" + mode, center - 0.25, center + 0.25,
                0.5, 1.0, 1.0, List.of(center), List.of(center), false,
                support, 0.5, 1.0, 1.0);
    }

    private static ImageOrientationSupport measuredHorizontal() {
        return new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                List.of(new ImageOrientationSupport.AngularMode(0.0, 0.0, 0.0, 1.0)), 1.0);
    }

    private static LocalScalarProfileExtractor.Mode extractedMode(
            LocalScalarProfileExtractor extractor, double amplitude) {
        List<LocalScalarProfileExtractor.Sample> samples = new ArrayList<>();
        for (int index = -12; index <= 12; index++) {
            double offset = index * 0.5;
            double intensity = 0.02 + amplitude * Math.exp(-0.5 * offset * offset);
            samples.add(new LocalScalarProfileExtractor.Sample(offset, intensity, true));
        }
        return extractor.extract(samples, 1.0, EvidenceModelParameters.Localization.defaults())
                .modes().get(0);
    }

    private static ProbabilisticProfile profile(ProbabilisticProfile.Mode mode) {
        List<ProbabilisticProfile.Sample> samples = new ArrayList<>();
        for (int index = -8; index <= 8; index++) {
            double offset = index * 0.5;
            samples.add(new ProbabilisticProfile.Sample(offset,
                    0.02 + 0.3 * Math.exp(-0.5 * offset * offset), true));
        }
        return new ProbabilisticProfile(0, 0.0, new MetricPoint(0.0, 0.0),
                new MetricPoint(0.0, 1.0), -4.0, 4.0, 1.0, true, 0.02,
                samples, List.of(mode), List.of(), OptionalDouble.empty(), List.of(0.0), 1.0);
    }

    private static ProbabilisticProfile.Mode mode(String id, double amplitude, double reliability) {
        return new ProbabilisticProfile.Mode(id, "lineage-" + id, -0.25, 0.25, 0.5,
                1.0, 1.0, List.of(0.0), List.of(0.0), false,
                ImageOrientationSupport.unknown(
                        ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT),
                amplitude, 0.0, reliability);
    }

    private static int componentIndex(ProbabilisticStateLattice lattice,
            ObservationComponent.Kind kind) {
        for (int index = 0; index < lattice.components().size(); index++) {
            if (lattice.components().get(index).kind() == kind) {
                return index;
            }
        }
        throw new AssertionError("missing component " + kind);
    }
}
