package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

class V022ProbabilisticStateTest {
    @Test
    void t013FullProfileStatesDoNotDependOnCorridorATrackMembership() {
        ProbabilisticProfile profile = profile(List.of(mode("discarded-by-a", 2.25)), List.of(), OptionalDouble.empty());

        StateSpaceBuildResult result = new ProbabilisticStateBuilder().build(profile, 96);

        assertEquals(StateSpaceBuildResult.Status.COMPLETE, result.status());
        assertTrue(result.lattice().orElseThrow().cells().stream()
            .anyMatch(cell -> Math.abs(cell.offsetMeters() - 2.25) < 1e-9));
    }

    @Test
    void t014EveryMandatoryPeakCenterAndAnchorIsRetained() {
        ProbabilisticProfile.Mode mode = new ProbabilisticProfile.Mode("fork", "lineage-fork",
            -1.0, 1.0, 0.6, 0.9, 0.8, List.of(-3.25, 3.25), List.of(-0.25, 0.0, 0.25), false);
        ProbabilisticProfile profile = profile(List.of(mode), List.of(), OptionalDouble.empty());

        List<Double> offsets = new ProbabilisticStateBuilder().build(profile, 96).lattice().orElseThrow()
            .cells().stream().map(LateralStateCell::offsetMeters).toList();

        for (double required : List.of(-3.25, 3.25, -0.25, 0.0, 0.25)) {
            assertTrue(offsets.stream().anyMatch(value -> Math.abs(value - required) < 1e-9), "missing " + required);
        }
    }

    @Test
    void t015FixedAnchorIsAnExactConditionedStateWithUnitMeasure() {
        ProbabilisticProfile profile = profile(List.of(mode("signal", 0.0)), List.of(), OptionalDouble.of(1.375));

        ProbabilisticStateLattice lattice = new ProbabilisticStateBuilder().build(profile, 96)
            .lattice().orElseThrow();

        assertEquals(1, lattice.cells().size());
        assertEquals(1.375, lattice.cells().get(0).offsetMeters(), 0.0);
        assertEquals(1.0, lattice.cells().get(0).quadratureWidthMeters(), 0.0);
        assertTrue(lattice.cells().get(0).exactAnchor());
        InferenceProfile conditioned = new ProbabilisticObservationModel().evaluate(profile, lattice,
            EvidenceModelParameters.defaults());
        assertEquals(0.0, conditioned.unaryCost(0), 0.0);
    }

    @Test
    void t016MissingAndCensoredComponentsRemainExplicit() {
        ProbabilisticProfile.CensoredMode clipped = new ProbabilisticProfile.CensoredMode(
            "right-edge", "lineage-edge", ProbabilisticProfile.CensorSide.RIGHT, 4.0, 0.8, true);
        ProbabilisticProfile profile = profile(List.of(), List.of(clipped), OptionalDouble.empty());

        ProbabilisticStateLattice lattice = new ProbabilisticStateBuilder().build(profile, 96)
            .lattice().orElseThrow();

        assertTrue(lattice.components().stream().anyMatch(component -> component.kind() == ObservationComponent.Kind.MISSING));
        assertTrue(lattice.components().stream().anyMatch(component -> component.kind() == ObservationComponent.Kind.CENSORED));
        assertFalse(lattice.components().stream().anyMatch(component -> component.kind() == ObservationComponent.Kind.MEASURED));
        InferenceProfile evaluated = new ProbabilisticObservationModel().evaluate(profile, lattice,
            EvidenceModelParameters.defaults());
        for (double[] responsibilities : evaluated.componentResponsibilities()) {
            assertEquals(1.0, java.util.Arrays.stream(responsibilities).sum(), 1e-12);
        }
    }

    @Test
    void t016ParentAndChildInterpretationsUseSeparateMixtures() {
        ProbabilisticProfile.Mode child = mode("child", -2.0);
        ProbabilisticProfile.Mode parent = new ProbabilisticProfile.Mode("parent", "lineage-parent",
            -0.5, 0.5, 0.75, 0.8, 0.7, List.of(0.0), List.of(0.0), true);
        ProbabilisticProfile profile = profile(List.of(child, parent), List.of(), OptionalDouble.empty());
        ProbabilisticStateBuilder builder = new ProbabilisticStateBuilder();

        ProbabilisticStateLattice elementary = builder.build(profile, 96,
            ObservationFamily.ELEMENTARY).lattice().orElseThrow();
        ProbabilisticStateLattice grouped = builder.build(profile, 96,
            ObservationFamily.GROUPED_PARENT).lattice().orElseThrow();

        assertTrue(elementary.components().stream()
            .filter(component -> component.kind() == ObservationComponent.Kind.MEASURED)
            .noneMatch(ObservationComponent::groupedParent));
        assertTrue(grouped.components().stream()
            .filter(component -> component.kind() == ObservationComponent.Kind.MEASURED)
            .allMatch(ObservationComponent::groupedParent));
    }

    @Test
    void t017MandatoryStateOverflowFailsWithoutDroppingModes() {
        List<ProbabilisticProfile.Mode> modes = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            modes.add(mode("m" + index, -5.5 + index));
        }
        ProbabilisticProfile profile = profile(modes, List.of(), OptionalDouble.empty());

        StateSpaceBuildResult result = new ProbabilisticStateBuilder().build(profile, 8);

        assertEquals(StateSpaceBuildResult.Status.STATE_LIMIT, result.status());
        assertEquals(12, result.retainedMandatoryOffsets().size());
        assertTrue(result.lattice().isEmpty());
    }

    @Test
    void t018DiscretizationIsStableBoundedAndCoversTheDecisionWindow() {
        ProbabilisticProfile profile = profile(List.of(mode("signal", 1.25)), List.of(), OptionalDouble.empty());
        ProbabilisticStateBuilder builder = new ProbabilisticStateBuilder();

        ProbabilisticStateLattice first = builder.build(profile, 9).lattice().orElseThrow();
        ProbabilisticStateLattice second = builder.build(profile, 9).lattice().orElseThrow();

        assertEquals(first, second);
        assertTrue(first.cells().size() <= 9);
        assertEquals(-6.0, first.cells().get(0).offsetMeters(), 1e-12);
        assertEquals(6.0, first.cells().get(first.cells().size() - 1).offsetMeters(), 1e-12);
        assertTrue(first.resolutionLimited());
    }

    @Test
    void hardStateCeilingCannotBeRaisedByCallerBudget() {
        List<ProbabilisticProfile.Sample> samples = new ArrayList<>();
        for (int index = -120; index <= 120; index++) {
            double offset = index * 0.5;
            samples.add(new ProbabilisticProfile.Sample(offset, Math.exp(-offset * offset / 4.0), true));
        }
        ProbabilisticProfile wide = new ProbabilisticProfile(0, 0.0, new MetricPoint(0.0, 0.0),
            new MetricPoint(0.0, 1.0), -60.0, 60.0, 1.0, true, 0.02, samples,
            List.of(mode("signal", 0.0)), List.of(), OptionalDouble.empty(), List.of(0.0), 1.0);

        ProbabilisticStateLattice lattice = new ProbabilisticStateBuilder().build(wide, 192)
            .lattice().orElseThrow();

        assertTrue(lattice.cells().size() <= 96);
        assertTrue(lattice.resolutionLimited());
    }

    private static ProbabilisticProfile profile(List<ProbabilisticProfile.Mode> modes,
        List<ProbabilisticProfile.CensoredMode> censored, OptionalDouble anchor) {
        List<ProbabilisticProfile.Sample> samples = new ArrayList<>();
        for (int index = -12; index <= 12; index++) {
            double offset = index * 0.5;
            samples.add(new ProbabilisticProfile.Sample(offset, Math.exp(-offset * offset / 4.0), true));
        }
        return new ProbabilisticProfile(0, 0.0, new MetricPoint(0.0, 0.0), new MetricPoint(0.0, 1.0),
            -6.0, 6.0, 1.0, true, 0.02, samples, modes, censored, anchor, List.of(0.0), 1.0);
    }

    private static ProbabilisticProfile.Mode mode(String id, double center) {
        return new ProbabilisticProfile.Mode(id, "lineage-" + id, center - 0.25, center + 0.25,
            0.5, 0.9, 0.9, List.of(center), List.of(center), false);
    }
}
