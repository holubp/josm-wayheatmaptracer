package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

class V022KBestExtensionOrderingTest {
    @Test
    void cappedRunsMatchIndependentExhaustionOfTheSameRetainedPrefixes() {
        EvidenceModelParameters dataOnly = new EvidenceModelParameters("retained-prefix", 1.0,
            0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0);
        List<InferenceProfile> profiles = List.of(
            V022ProbabilisticInferenceTest.profile(0, new double[] {-1, 0, 1},
                new double[] {1, 1, 1}, new double[] {0.25, 0, 0.5}, new String[] {"s", "s", "s"}),
            V022ProbabilisticInferenceTest.profile(1, new double[] {-1, 0, 1},
                new double[] {1, 1, 1}, new double[] {0.5, 0.25, 0}, new String[] {"s", "s", "s"}),
            V022ProbabilisticInferenceTest.profile(2, new double[] {-1, 0, 1},
                new double[] {1, 1, 1}, new double[] {0, 0.75, 0.25}, new String[] {"s", "s", "s"}),
            V022ProbabilisticInferenceTest.profile(3, new double[] {-1, 0, 1},
                new double[] {1, 1, 1}, new double[] {0.25, 0, 0.5}, new String[] {"s", "s", "s"}),
            V022ProbabilisticInferenceTest.profile(4, new double[] {-1, 0, 1},
                new double[] {1, 1, 1}, new double[] {0, 0.5, 0.25}, new String[] {"s", "s", "s"}));
        for (int cap : new int[] {1, 8, 32}) {
            List<ReferencePath> expected = retainedPrefixOracle(profiles, cap);
            ProbabilisticInferenceResult actual = new ProbabilisticInference().solve(profiles,
                dataOnly, new TraceBudgets(96, 1_000, 1_000_000, cap, 1));
            assertEquals(expected.size(), actual.rawPaths().size());
            for (int index = 0; index < expected.size(); index++) {
                assertArrayEquals(expected.get(index).states(), actual.rawPaths().get(index).stateIndices());
                assertEquals(Double.doubleToRawLongBits(expected.get(index).energy()),
                    Double.doubleToRawLongBits(actual.rawPaths().get(index).energy()));
            }
        }
    }

    private static List<ReferencePath> retainedPrefixOracle(List<InferenceProfile> profiles, int cap) {
        // Independent explicit arrays: exhaust each next pair from only the prefixes
        // retained by that same pair on the previous layer, then apply the cap.
        List<ReferencePath> retained = new ArrayList<>();
        for (int first = 0; first < 3; first++) {
            for (int second = 0; second < 3; second++) {
                double energy = 0.5 * profiles.get(0).unaryCosts()[first]
                    + profiles.get(1).unaryCosts()[second];
                retained.add(new ReferencePath(new int[] {first, second}, energy));
            }
        }
        for (int layer = 2; layer < profiles.size(); layer++) {
            List<ReferencePath> next = new ArrayList<>();
            for (int prior = 0; prior < 3; prior++) {
                for (int state = 0; state < 3; state++) {
                    List<ReferencePath> extensions = new ArrayList<>();
                    double quadrature = layer == profiles.size() - 1 ? 0.5 : 1.0;
                    double increment = quadrature * profiles.get(layer).unaryCosts()[state];
                    for (ReferencePath prefix : retained) {
                        if (prefix.states()[layer - 1] != prior) continue;
                        int[] states = java.util.Arrays.copyOf(prefix.states(), layer + 1);
                        states[layer] = state;
                        extensions.add(new ReferencePath(states, prefix.energy() + increment));
                    }
                    extensions.sort(REFERENCE_ORDER);
                    next.addAll(extensions.subList(0, Math.min(cap, extensions.size())));
                }
            }
            retained = next;
        }
        retained.sort(REFERENCE_ORDER);
        return retained.subList(0, Math.min(cap, retained.size()));
    }

    private static final Comparator<ReferencePath> REFERENCE_ORDER = (first, second) -> {
        int energy = Double.compare(first.energy(), second.energy());
        if (energy != 0) return energy;
        for (int index = 0; index < first.states().length; index++) {
            int state = Integer.compare(first.states()[index], second.states()[index]);
            if (state != 0) return state;
        }
        return 0;
    };

    private record ReferencePath(int[] states, double energy) { }

    @Test
    void unprunedLatticeMatchesIndependentEnumerationAtRawCaps() {
        EvidenceModelParameters dataOnly = new EvidenceModelParameters("exhaustive-small", 1.0,
            0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0);
        List<InferenceProfile> profiles = List.of(
            V022ProbabilisticInferenceTest.profile(0, new double[] {-2, 2},
                new double[] {1, 2}, new double[] {0.25, 0}, new String[] {"left", "right"}),
            V022ProbabilisticInferenceTest.profile(2, new double[] {-2, 2},
                new double[] {1, 2}, new double[] {0.5, 0.75}, new String[] {"left", "right"}));
        V022ProbabilisticInferenceTest.OracleResult exhaustive =
            V022ProbabilisticInferenceTest.bruteForce(profiles, dataOnly);
        for (int cap : new int[] {1, 8, 32}) {
            ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
                dataOnly, new TraceBudgets(96, 100, 1_000, cap, Math.min(cap, 2)));
            assertEquals(Math.min(cap, exhaustive.paths().size()), result.rawPaths().size());
            for (int index = 0; index < result.rawPaths().size(); index++) {
                var expected = exhaustive.paths().get(index);
                var actual = result.rawPaths().get(index);
                assertArrayEquals(expected.states(), actual.stateIndices());
                assertEquals(Double.doubleToRawLongBits(expected.energy()),
                    Double.doubleToRawLongBits(actual.energy()));
                assertEquals(Double.doubleToRawLongBits(expected.logMeasure()),
                    Double.doubleToRawLongBits(actual.logBaseMeasure()));
            }
            assertEquals(Math.min(cap + 1, exhaustive.paths().size()),
                result.completion().orElseThrow().completePathsAtSaturation());
            assertEquals(exhaustive.logPartition(), result.logPartition(), 1e-12);
            for (int profile = 0; profile < profiles.size(); profile++) {
                assertArrayEquals(exhaustive.marginals().get(profile),
                    result.positionMarginals().get(profile), 1e-12);
            }
        }
    }

    @Test
    void roundedExtensionTieUsesLexicalOrder() {
        assertTrue(ProbabilisticInference.compareExtension(1.0e16 + 0.25, 0, 0,
            1.0e16, 1, 0) < 0);
        EvidenceModelParameters dataOnly = new EvidenceModelParameters("rounded-tie", 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0, 1.0, 0.0);
        List<InferenceProfile> profiles = List.of(
            V022ProbabilisticInferenceTest.profile(0, new double[] {0, 1},
                new double[] {1, 1}, new double[] {0.5, 0}, new String[] {"same", "same"}),
            V022ProbabilisticInferenceTest.profile(1, new double[] {0},
                new double[] {1}, new double[] {0}, new String[] {"same"}),
            V022ProbabilisticInferenceTest.profile(2, new double[] {0},
                new double[] {1}, new double[] {0}, new String[] {"same"}),
            V022ProbabilisticInferenceTest.profile(3, new double[] {0},
                new double[] {1}, new double[] {2.0e16}, new String[] {"same"}));
        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
            dataOnly, new TraceBudgets(96, 100, 1_000, 2, 1));

        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, result.status());
        assertEquals(Double.doubleToRawLongBits(result.rawPaths().get(0).energy()),
            Double.doubleToRawLongBits(result.rawPaths().get(1).energy()));
        assertArrayEquals(new int[] {0, 0, 0, 0}, result.rawPaths().get(0).stateIndices());
        assertArrayEquals(new int[] {1, 0, 0, 0}, result.rawPaths().get(1).stateIndices());
    }

    @Test
    void budgetStopsAtHistoricalLogicalExtensionBeforePublishingResult() {
        List<InferenceProfile> profiles = fourBinaryProfiles();
        ModernDiagnosticCounters.begin();
        try {
            ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
                EvidenceModelParameters.withoutShapeTerms(),
                new TraceBudgets(96, 100, 39, 2, 1));
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, result.status());
            assertEquals(40L, result.evaluatedTransitions());
            assertTrue(result.rawPaths().isEmpty());
            assertTrue(result.completion().isEmpty());
            assertEquals(7L, ModernDiagnosticCounters.snapshot()
                .get("inference.extensionDescriptors").longValue());
        } finally {
            ModernDiagnosticCounters.end();
        }
    }

    @Test
    void everyCancellationCheckpointReleasesScratchAndAncestry() {
        List<InferenceProfile> profiles = fourBinaryProfiles();
        TraceBudgets budgets = new TraceBudgets(96, 100, 1_000, 2, 1);
        int[] reached = {0};
        new ProbabilisticInference().solve(profiles, EvidenceModelParameters.withoutShapeTerms(),
            budgets, null, () -> { reached[0]++; return false; });
        assertTrue(reached[0] >= 6);
        for (int cancelAt = 1; cancelAt <= reached[0]; cancelAt++) {
            int threshold = cancelAt;
            int[] calls = {0};
            AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
            AttemptMemoryLedger.Owner owner = ledger.rootOwner();
            assertThrows(CancellationException.class, () -> new ProbabilisticInference().solve(
                profiles, EvidenceModelParameters.withoutShapeTerms(), budgets, null,
                () -> ++calls[0] >= threshold, owner));
            owner.close();
            assertEquals(0L, ledger.currentBytes());
        }
    }

    private static List<InferenceProfile> fourBinaryProfiles() {
        return List.of(0.0, 1.0, 2.0, 3.0).stream().map(chainage ->
            V022ProbabilisticInferenceTest.profile(chainage, new double[] {-1, 1},
                new double[] {1, 1}, new double[] {0, 0},
                new String[] {"same", "same"})).toList();
    }
}
