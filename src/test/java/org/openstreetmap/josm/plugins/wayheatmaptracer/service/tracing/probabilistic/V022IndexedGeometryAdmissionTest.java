package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

class V022IndexedGeometryAdmissionTest {
    private static final EvidenceModelParameters PARAMETERS = EvidenceModelParameters.withoutShapeTerms();
    private static final TraceBudgets BUDGETS = new TraceBudgets(96, 1_000_000L, 1_000_000L, 32, 1);
    private static final double[] POSITIONS = {-1.5, -0.5, 0.5, 1.5};
    private static final ImageOrientationSupport UNKNOWN = ImageOrientationSupport.unknown(
            ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT);

    @Test
    void eligibleLargeGeometryUsesIndexedAdmissionsAndMatchesIndependentPathEnumeration() {
        List<InferenceProfile> profiles = profiles(new int[] {4, 5, 4, 4});
        MetricRegion region = splitBandRegion();
        assertTrue(region.contains(profiles.get(0).point(1)), "lower band boundary is admitted");
        assertTrue(region.contains(profiles.get(0).point(2)), "upper band boundary is admitted");
        assertFalse(region.contains(new MetricPoint(0.0, 0.0)), "the nonconvex gap remains excluded");
        assertFalse(region.containsSegment(profiles.get(0).point(1), profiles.get(1).point(3)),
                "a segment crossing the nonconvex gap remains excluded");

        AttemptMemoryLedger ledger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        ModernDiagnosticCounters.begin();
        ProbabilisticInferenceResult result;
        Map<String, Number> counters;
        try {
            result = new ProbabilisticInference().solve(profiles, PARAMETERS, BUDGETS, region,
                    CancellationProbe.NONE, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            counters = ModernDiagnosticCounters.snapshot();
        } finally {
            owner.close();
            ModernDiagnosticCounters.end();
        }

        assertEquals(0L, ledger.currentBytes());
        assertTrue(result.mapPath().isPresent());
        List<ExpectedPath> expected = enumerateAdmitted(profiles, region);
        expected.sort(Comparator.comparingDouble(ExpectedPath::energy)
                .thenComparing(ExpectedPath::states, V022IndexedGeometryAdmissionTest::lexicographic));
        assertArrayEquals(expected.get(0).states(), result.mapPath().orElseThrow().stateIndices());
        assertEquals(Double.doubleToRawLongBits(expected.get(0).energy()),
                Double.doubleToRawLongBits(result.mapPath().orElseThrow().energy()));
        assertEquals(expectedLogPartition(expected), result.logPartition(), 1e-12);
        assertEquals(result.evaluatedTransitions(),
                result.workAccounting().orElseThrow().computedScores());
        assertTrue(count(counters, "optimization.geometryAdmission.indexedCalculations") > 0,
                "the eligible pair table computes admitted geometry answers");
        assertTrue(count(counters, "optimization.geometryAdmission.indexedReuses") > 0,
                "forward/backward and K-best reuse the same indexed adjacent-pair answers");
    }

    @Test
    void threeStateGraphKeepsTheOriginalAdmissionPathAndDiagnosticInventory() {
        List<InferenceProfile> profiles = profiles(new int[] {3, 3, 3, 3});
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        ModernDiagnosticCounters.begin();
        try {
            ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles, PARAMETERS,
                    BUDGETS, splitBandRegion(), CancellationProbe.NONE, owner,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            assertTrue(result.mapPath().isPresent());
            assertTrue(ModernDiagnosticCounters.snapshot().keySet().stream().noneMatch(
                    name -> name.startsWith("optimization.geometryAdmission.indexed")));
        } finally {
            owner.close();
            ModernDiagnosticCounters.end();
        }
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void indexedAnswersMatchLegacyMemoAndChargeExactlyTheLazyPrimitiveTable() {
        List<InferenceProfile> profiles = profiles(new int[] {4, 5, 4, 4});
        MetricRegion region = splitBandRegion();
        long pairCount = 56L;
        long expectedBytes = AttemptMemoryLedger.objectBytes(56)
                + AttemptMemoryLedger.arrayBytes(profiles.size(), Integer.BYTES)
                + AttemptMemoryLedger.arrayBytes(pairCount, Byte.BYTES);
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(expectedBytes);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        ModernDiagnosticCounters.begin();
        IndexedGeometryAdmissions indexed = IndexedGeometryAdmissions.createIfEligible(
                profiles, region, pairCount, owner);
        assertTrue(indexed != null);
        assertEquals(expectedBytes, ledger.currentBytes());
        assertEquals(0, ModernDiagnosticCounters.snapshot().size(),
                "the pair table allocates but does not evaluate future geometry eagerly");
        TransitionAdmissionMemo legacy = new TransitionAdmissionMemo(64);
        long admitted = 0;
        long rejected = 0;
        for (int profile = 0; profile + 1 < profiles.size(); profile++) {
            for (int start = 0; start < profiles.get(profile).cells().size(); start++) {
                for (int end = 0; end < profiles.get(profile + 1).cells().size(); end++) {
                    boolean expected = legacy.allowed(profile, start, end, profiles, region);
                    assertEquals(expected, indexed.allowed(profile, start, end, profiles, region));
                    assertEquals(expected, indexed.allowed(profile, start, end, profiles, region));
                    if (expected) admitted++; else rejected++;
                }
            }
        }
        assertTrue(admitted > 0 && rejected > 0, "the fixture exercises cached true and false answers");
        ModernDiagnosticCounters.begin();
        indexed.finish();
        Map<String, Number> counters = ModernDiagnosticCounters.snapshot();
        ModernDiagnosticCounters.end();
        assertEquals(pairCount, count(counters, "optimization.geometryAdmission.indexedCalculations"));
        assertEquals(pairCount, count(counters, "optimization.geometryAdmission.indexedReuses"));
        owner.close();
        assertEquals(0L, ledger.currentBytes());
        ModernDiagnosticCounters.end();
    }

    @Test
    void creationAndUnrelatedQueryDoNotInspectInvalidFutureCoordinates() {
        List<InferenceProfile> rows = new ArrayList<>(profiles(new int[] {4, 4, 4, 4}));
        List<LateralStateCell> invalidCells = new ArrayList<>();
        for (int state = 0; state < 4; state++) {
            invalidCells.add(new LateralStateCell(1e308, 1.0, false, true, "main"));
        }
        rows.set(2, new InferenceProfile(4.0, new MetricPoint(4.0, 1e308),
                new MetricPoint(0.0, 1.0), invalidCells, new double[4],
                Map.of("main", UNKNOWN), Map.of("main", 0.75),
                ObservationOwnership.DIRECT_TWO_SIDED, false, new double[4][0]));
        List<InferenceProfile> bound = List.copyOf(rows);
        MetricRegion region = splitBandRegion();
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        try {
            IndexedGeometryAdmissions indexed = IndexedGeometryAdmissions.createIfEligible(
                    bound, region, 48L, owner);
            assertTrue(indexed != null);
            assertTrue(indexed.allowed(0, 1, 1, bound, region));
            assertThrows(IllegalArgumentException.class,
                    () -> indexed.allowed(1, 1, 3, bound, region));
            assertThrows(IllegalArgumentException.class,
                    () -> indexed.allowed(1, 1, 3, bound, region),
                    "failed validation must not be cached as an ordinary rejected segment");
        } finally {
            owner.close();
        }
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void indexedAnswersAreBoundToProfileAndRegionIdentityAndPreserveInvalidIndexFailure() {
        List<InferenceProfile> profiles = profiles(new int[] {4, 4, 4, 4});
        MetricRegion split = splitBandRegion();
        MetricRegion broad = MetricRegion.rectangle(-1.0, -2.0, 7.0, 2.0);
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        IndexedGeometryAdmissions indexed = IndexedGeometryAdmissions.createIfEligible(
                profiles, split, 48L, owner);
        assertTrue(indexed != null);
        assertFalse(indexed.allowed(0, 1, 3, profiles, split));
        assertTrue(indexed.allowed(0, 1, 3, profiles, broad),
                "a different region cannot receive the cached answer from the bound region");
        List<InferenceProfile> shiftedProfiles = profiles(new int[] {4, 4, 4, 4}, 10.0);
        assertFalse(indexed.allowed(0, 1, 3, shiftedProfiles, broad),
                "a different profile-list identity cannot receive cached geometry answers");
        assertThrows(IndexOutOfBoundsException.class,
                () -> indexed.allowed(0, -1, 0, profiles, split));
        owner.close();
        assertEquals(0L, ledger.currentBytes());
        assertThrows(IllegalStateException.class,
                () -> indexed.allowed(0, 1, 1, profiles, split),
                "closed owners reject indexed hits without evaluating geometry");
    }

    @Test
    void ineligibleBudgetAndInitialReservationRefusalLeaveNoIndexedCharge() {
        assertEquals(-1L, IndexedGeometryAdmissions.checkedPairCount(
                0L, 46_341, 46_341, Long.MAX_VALUE),
                "the flat byte index refuses a product above Integer.MAX_VALUE before allocation");
        List<InferenceProfile> profiles = profiles(new int[] {4, 5, 4, 4});
        MetricRegion region = splitBandRegion();
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        assertTrue(IndexedGeometryAdmissions.createIfEligible(profiles, region, 55L, owner) == null);
        assertEquals(0L, ledger.currentBytes());
        owner.close();

        long bytes = AttemptMemoryLedger.objectBytes(56)
                + AttemptMemoryLedger.arrayBytes(profiles.size(), Integer.BYTES)
                + AttemptMemoryLedger.arrayBytes(56L, Byte.BYTES);
        AttemptMemoryLedger shortLedger = new AttemptMemoryLedger(bytes - 1L);
        AttemptMemoryLedger.Owner shortOwner = shortLedger.rootOwner();
        assertTrue(IndexedGeometryAdmissions.createIfEligible(profiles, region, 56L, shortOwner) == null);
        assertEquals(0L, shortLedger.currentBytes());
        shortOwner.close();
        assertEquals(0L, shortLedger.currentBytes());
        assertThrows(IllegalStateException.class,
                () -> IndexedGeometryAdmissions.createIfEligible(profiles, region, 56L, shortOwner),
                "a closed owner is not treated as an initial-reservation refusal");

        AttemptMemoryLedger boundedLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        AttemptMemoryLedger.Owner boundedOwner = boundedLedger.rootOwner();
        ModernDiagnosticCounters.begin();
        try {
            ProbabilisticInferenceResult refused = new ProbabilisticInference().solve(profiles, PARAMETERS,
                    new TraceBudgets(96, 55L, 1_000_000L, 32, 1), region, CancellationProbe.NONE,
                    boundedOwner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, refused.status());
            assertTrue(refused.explanation().contains("pair-visit budget exceeded"));
            assertFalse(ModernDiagnosticCounters.snapshot().keySet().stream().anyMatch(
                    name -> name.startsWith("optimization.geometryAdmission.indexed")));
        } finally {
            boundedOwner.close();
            ModernDiagnosticCounters.end();
        }
        assertEquals(0L, boundedLedger.currentBytes());
    }

    @Test
    void cancellationAfterIndexedQueriesReleasesItsTableAndPublishesActualCounts() {
        List<InferenceProfile> profiles = profiles(new int[] {4, 4, 4, 4, 4});
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        AtomicInteger checkpoints = new AtomicInteger();
        ModernDiagnosticCounters.begin();
        try {
            assertThrows(CancellationException.class, () -> new ProbabilisticInference().solve(profiles,
                    PARAMETERS, BUDGETS, splitBandRegion(),
                    () -> checkpoints.incrementAndGet() == 4, owner,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2));
            assertTrue(count(ModernDiagnosticCounters.snapshot(),
                    "optimization.geometryAdmission.indexedCalculations") > 0,
                    "cancellation follows actual lazy geometry evaluations");
        } finally {
            owner.close();
            ModernDiagnosticCounters.end();
        }
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void transitionRefusalAfterIndexedQueriesClosesTheTableAndKeepsTypedFailure() {
        List<InferenceProfile> profiles = profiles(new int[] {4, 4, 4, 4});
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        ModernDiagnosticCounters.begin();
        ProbabilisticInferenceResult result;
        Map<String, Number> counters;
        try {
            result = new ProbabilisticInference().solve(profiles, PARAMETERS,
                    new TraceBudgets(96, 1_000_000L, 1L, 32, 1), splitBandRegion(),
                    CancellationProbe.NONE, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            counters = ModernDiagnosticCounters.snapshot();
        } finally {
            owner.close();
            ModernDiagnosticCounters.end();
        }
        assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, result.status());
        assertTrue(result.explanation().contains("transition budget exceeded"));
        assertTrue(count(counters, "optimization.geometryAdmission.indexedCalculations") > 0);
        assertEquals(0L, ledger.currentBytes());
    }

    private static List<InferenceProfile> profiles(int[] stateCounts) {
        return profiles(stateCounts, 0.0);
    }

    private static List<InferenceProfile> profiles(int[] stateCounts, double verticalShift) {
        List<InferenceProfile> result = new ArrayList<>(stateCounts.length);
        for (int profile = 0; profile < stateCounts.length; profile++) {
            int count = stateCounts[profile];
            List<LateralStateCell> cells = new ArrayList<>(count);
            double[] unary = new double[count];
            for (int state = 0; state < count; state++) {
                double offset = count == 5 && state == 4 ? 0.0 : POSITIONS[state % POSITIONS.length];
                double width = 0.75 + 0.1 * ((profile + state) % 4);
                boolean exactAnchor = profile == 0 && state == 1;
                cells.add(new LateralStateCell(offset, width, exactAnchor, true, "main"));
                unary[state] = 0.03 * (state - 1.2) * (state - 1.2) + 0.002 * profile * state;
            }
            double[][] responsibilities = new double[count][0];
            result.add(new InferenceProfile(profile * 2.0, new MetricPoint(profile * 2.0, verticalShift),
                    new MetricPoint(0.0, 1.0), cells, unary, Map.of("main", UNKNOWN),
                    Map.of("main", 0.75), ObservationOwnership.DIRECT_TWO_SIDED,
                    false, responsibilities));
        }
        return List.copyOf(result);
    }

    private static MetricRegion splitBandRegion() {
        List<MetricPoint> lower = List.of(new MetricPoint(-1.0, -1.5), new MetricPoint(7.0, -1.5),
                new MetricPoint(7.0, -0.5), new MetricPoint(-1.0, -0.5));
        List<MetricPoint> upper = List.of(new MetricPoint(-1.0, 0.5), new MetricPoint(7.0, 0.5),
                new MetricPoint(7.0, 1.5), new MetricPoint(-1.0, 1.5));
        return new MetricRegion(List.of(lower, upper));
    }

    private static List<ExpectedPath> enumerateAdmitted(List<InferenceProfile> profiles, MetricRegion region) {
        List<ExpectedPath> result = new ArrayList<>();
        enumerateAdmitted(profiles, region, new int[profiles.size()], 0, result);
        return result;
    }

    private static void enumerateAdmitted(List<InferenceProfile> profiles, MetricRegion region,
            int[] states, int index, List<ExpectedPath> paths) {
        if (index < profiles.size()) {
            for (int state = 0; state < profiles.get(index).cells().size(); state++) {
                states[index] = state;
                enumerateAdmitted(profiles, region, states, index + 1, paths);
            }
            return;
        }
        for (int profile = 0; profile + 1 < profiles.size(); profile++) {
            MetricPoint start = profiles.get(profile).point(states[profile]);
            MetricPoint end = profiles.get(profile + 1).point(states[profile + 1]);
            if (!region.contains(start) || !region.contains(end) || !region.containsSegment(start, end)) return;
        }
        double energy = FrozenPairLocalEnergyReference.profileEnergy(profiles, 0, states[0], PARAMETERS)
                + FrozenPairLocalEnergyReference.profileEnergy(profiles, 1, states[1], PARAMETERS)
                + FrozenPairLocalEnergyReference.pairEnergy(profiles, 0, states[0], states[1], PARAMETERS);
        for (int profile = 2; profile < profiles.size(); profile++) {
            energy = energy + FrozenPairLocalEnergyReference.increment(profiles, profile,
                    states[profile - 2], states[profile - 1], states[profile], PARAMETERS);
        }
        double logMeasure = 0.0;
        for (int profile = 0; profile < profiles.size(); profile++) {
            logMeasure += StrictMath.log(profiles.get(profile).cells().get(states[profile])
                    .exactAnchor() ? 1.0 : profiles.get(profile).cells().get(states[profile])
                            .quadratureWidthMeters());
        }
        paths.add(new ExpectedPath(states.clone(), energy, logMeasure));
    }

    private static double expectedLogPartition(List<ExpectedPath> paths) {
        double maximum = paths.stream().mapToDouble(path -> -path.energy() / PARAMETERS.temperature()
                + path.logMeasure()).max().orElseThrow();
        double sum = 0.0;
        for (ExpectedPath path : paths) {
            sum += StrictMath.exp(-path.energy() / PARAMETERS.temperature() + path.logMeasure() - maximum);
        }
        return maximum + StrictMath.log(sum);
    }

    private static int lexicographic(int[] first, int[] second) {
        for (int index = 0; index < first.length; index++) {
            int compared = Integer.compare(first[index], second[index]);
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static long count(Map<String, Number> counters, String name) {
        return counters.getOrDefault(name, 0L).longValue();
    }

    private record ExpectedPath(int[] states, double energy, double logMeasure) { }
}
