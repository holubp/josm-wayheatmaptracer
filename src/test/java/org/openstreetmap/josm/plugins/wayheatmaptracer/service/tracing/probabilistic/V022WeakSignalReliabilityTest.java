package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;

class V022WeakSignalReliabilityTest {
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
    void physicalCoherenceCanOnlyRaiseAmplitudeReliability() {
        assertEquals(0.90, ProbabilisticProfile.Mode.combineReliability(0.90, 0.0), 0.0);
        assertEquals(1.0, ProbabilisticProfile.Mode.combineReliability(0.90, 1.0), 0.0);
        assertTrue(ProbabilisticProfile.Mode.combineReliability(0.10, 0.80) > 0.10);
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
    void reliabilityChangesPriorsWithoutChangingTheAdmittedLattice() {
        ProbabilisticStateBuilder builder = new ProbabilisticStateBuilder();
        ProbabilisticStateLattice reliable = builder.build(profile(mode("branch", 0.35, 1.0)), 24)
                .lattice().orElseThrow();
        ProbabilisticStateLattice attenuated = builder.build(profile(mode("branch", 0.35, 0.0)), 24)
                .lattice().orElseThrow();

        assertEquals(reliable.cells(), attenuated.cells());
        assertEquals(reliable.components(), attenuated.components());
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
