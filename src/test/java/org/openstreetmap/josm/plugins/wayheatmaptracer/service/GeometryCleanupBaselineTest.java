package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;

/** Freezes unit-explicit v0.18.1 inputs and raw candidate invariants before cleanup changes. */
class GeometryCleanupBaselineTest {
    @Test
    void acceptanceFixturesAreUnitExplicitAndComplete() {
        List<GeometryCleanupAcceptanceFixtures.Fixture> fixtures = GeometryCleanupAcceptanceFixtures.all();

        assertEquals(Set.of(
            "ripple-3m-to-6m", "bend-6m", "bend-10m", "curve-20m", "sine", "switchback",
            "weak-holes", "medium-holes", "sparse-union", "wandering-outlier",
            "parallel-lane-vs-carriageway", "z13-coarse-step", "z15-reference-step", "z16-fine-step",
            "protected-anchor-control", "topology-crossing-control"
        ), fixtures.stream().map(GeometryCleanupAcceptanceFixtures.Fixture::name)
            .collect(java.util.stream.Collectors.toSet()));
        assertTrue(fixtures.stream().allMatch(fixture ->
            fixture.profiles().size() == GeometryCleanupAcceptanceFixtures.PROFILE_COUNT));
        assertTrue(fixtures.stream().allMatch(fixture ->
            fixture.metresPerSourcePixel() > 0.0 && fixture.profileStepMetres() > 0.0));
        assertTrue(fixtures.stream().allMatch(fixture -> fixture.profiles().stream().allMatch(profile ->
            profile.cumulativeGroundDistanceMeters() >= 0.0)));
        assertTrue(fixtures.stream().anyMatch(fixture -> !fixture.protectedAnchorIndexes().isEmpty()));
        assertTrue(fixtures.stream().anyMatch(fixture -> !fixture.connectedControl().isEmpty()));
    }

    @Test
    void representativeRawCandidatesRemainInternallyConsistent() {
        Set<String> representativeNames = Set.of(
            "ripple-3m-to-6m", "switchback", "weak-holes",
            "parallel-lane-vs-carriageway", "protected-anchor-control");
        for (GeometryCleanupAcceptanceFixtures.Fixture fixture : GeometryCleanupAcceptanceFixtures.all().stream()
            .filter(value -> representativeNames.contains(value.name())).toList()) {
            CorridorAwareTracker.TrackingResult result = new CorridorAwareTracker().trackDetailed(
                fixture.profiles(), 1.0);

            assertFalse(result.candidates().isEmpty(), fixture.name());
            assertEquals(result.candidates().size(), result.optimizations().size(), fixture.name());
            for (CenterlineCandidate candidate : result.candidates()) {
                assertFalse(candidate.id().isBlank(), fixture.name());
                assertEquals(GeometryCleanupAcceptanceFixtures.PROFILE_COUNT,
                    candidate.offsetsPx().size(), fixture.name() + ":" + candidate.id());
                assertEquals(candidate.offsetsPx().size(), candidate.screenPoints().size(),
                    fixture.name() + ":" + candidate.id());
                assertTrue(Double.isFinite(candidate.score()), fixture.name() + ":" + candidate.id());
                assertTrue(candidate.offsetsPx().stream().allMatch(Double::isFinite),
                    fixture.name() + ":" + candidate.id());
                CorridorCenterlineOptimizer.OptimizationResult optimization =
                    result.optimizations().get(candidate.id());
                assertTrue(optimization != null, fixture.name() + ":" + candidate.id());
                assertEquals(candidate.offsetsPx(), optimization.offsetsPx(),
                    fixture.name() + ":" + candidate.id());
                assertEquals(candidate.offsetsPx().size(), optimization.costs().size(),
                    fixture.name() + ":" + candidate.id());
            }
        }
    }
}
