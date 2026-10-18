package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V022BObservationRendererDiagnosticTest {
    @TempDir Path directory;

    @Test void freshOutputScopePreservesUnrelatedParentFiles() throws Exception {
        Path unrelated = directory.resolve("unrelated.txt");
        Files.writeString(unrelated, "preserve this");
        Path scope = BObservationRendererDiagnosticTool.prepareOutputDirectory(directory);
        assertEquals(directory.resolve("receipt"), scope);
        assertTrue(Files.isDirectory(scope));
        try (var files = Files.list(scope)) { assertEquals(0, files.count()); }
        assertEquals("preserve this", Files.readString(unrelated));
    }

    @Test void existingScopeCannotRelabelOrDeleteEarlierGeometry() throws Exception {
        Path scope = Files.createDirectory(directory.resolve("receipt"));
        Path binding = scope.resolve("binding.json");
        Path geometry = scope.resolve("bound-geometry.csv");
        Files.writeString(binding, "original binding");
        Files.writeString(geometry, "original public geometry");
        assertThrows(FileAlreadyExistsException.class,
                () -> BObservationRendererDiagnosticTool.prepareOutputDirectory(directory));
        assertEquals("original binding", Files.readString(binding));
        assertEquals("original public geometry", Files.readString(geometry));
    }

    @Test void savedMapRescoreUsesTheOriginalGroupedEnergyRecurrence() throws Exception {
        List<InferenceProfile> profiles = energyProfiles();
        List<Integer> states = List.of(0, 1, 0);
        EvidenceModelParameters parameters = EvidenceModelParameters.defaults();
        double expected = originalMapEnergy(profiles, states, parameters);

        Method rescore = BObservationRendererDiagnosticTool.class.getDeclaredMethod(
                "rescoreSavedMap", List.class, List.class, EvidenceModelParameters.class);
        rescore.setAccessible(true);
        Object result = rescore.invoke(null, profiles, states, parameters);
        assertEquals(Double.doubleToRawLongBits(expected),
                Double.doubleToRawLongBits((double) value(result, "energy")));
        List<?> rows = (List<?>) value(result, "rows");
        assertEquals(profiles.size(), rows.size());
        Object middle = rows.get(1);
        assertTrue(Double.isFinite((double) value(middle, "unaryEnergy")));
        assertTrue(Double.isFinite((double) value(middle, "guideEnergy")));
        assertTrue(Double.isFinite((double) value(middle, "pairOrientationEnergy")));
        assertTrue(Double.isFinite((double) value(middle, "tripleTurnEnergy")));
        assertEquals("upper", value(middle, "branchLabel"));
    }

    @Test void branchWitnessesUseOnlyAdmittedCellsAndPreserveExactAnchors() throws Exception {
        List<InferenceProfile> profiles = witnessProfiles();
        List<ProbabilisticStateLattice> lattices = witnessLattices();
        List<Integer> saved = witnessSavedStates();

        Method build = BObservationRendererDiagnosticTool.class.getDeclaredMethod(
                "buildBranchWitnesses", List.class, List.class, List.class, List.class);
        build.setAccessible(true);
        List<List<MetricPoint>> sourceBranches = List.of(
                List.of(new MetricPoint(40, 28), new MetricPoint(80, 28)),
                List.of(new MetricPoint(40, 36), new MetricPoint(80, 36)));
        List<?> witnesses = (List<?>) build.invoke(null, profiles, lattices, saved, sourceBranches);
        assertEquals(2, witnesses.size());
        for (Object witness : witnesses) {
            List<Integer> states = (List<Integer>) value(witness, "stateIndices");
            List<MetricPoint> points = (List<MetricPoint>) value(witness, "points");
            assertEquals(profiles.size(), states.size());
            assertEquals(profiles.size(), points.size());
            assertEquals(3, states.get(40), "the exact anchored profile must retain its recorded state");
            for (int profile = 0; profile < states.size(); profile++) {
                assertTrue(states.get(profile) >= 0 && states.get(profile) < lattices.get(profile).cells().size());
                assertEquals(profiles.get(profile).point(states.get(profile)), points.get(profile));
            }
        }
        assertEquals(1, ((List<Integer>) value(witnesses.get(0), "stateIndices")).get(0));
        assertEquals(5, ((List<Integer>) value(witnesses.get(1), "stateIndices")).get(0));
    }

    @Test void routeAdmissionRejectsAChordAcrossAExcludedGap() throws Exception {
        MetricRegion union = new MetricRegion(List.of(
                List.of(new MetricPoint(0, -1), new MetricPoint(1, -1),
                        new MetricPoint(1, 1), new MetricPoint(0, 1)),
                List.of(new MetricPoint(3, -1), new MetricPoint(4, -1),
                        new MetricPoint(4, 1), new MetricPoint(3, 1))));
        List<MetricPoint> contained = List.of(new MetricPoint(.2, 0), new MetricPoint(.8, 0));
        List<MetricPoint> crossing = List.of(new MetricPoint(1, 0), new MetricPoint(3, 0));
        assertTrue((boolean) invokeStatic("routeAdmitted", new Class<?>[] {List.class, MetricRegion.class},
                contained, union));
        assertTrue(union.contains(crossing.get(0)) && union.contains(crossing.get(1)),
                "the regression must have individually admitted endpoints");
        assertFalse((boolean) invokeStatic("routeAdmitted", new Class<?>[] {List.class, MetricRegion.class},
                crossing, union), "the connecting segment crosses the non-admitted gap");
    }

    @Test void branchWitnessIsUnavailableWhenTheOriginalBranchHasNoTarget() throws Exception {
        Method build = BObservationRendererDiagnosticTool.class.getDeclaredMethod(
                "buildBranchWitnesses", List.class, List.class, List.class, List.class);
        build.setAccessible(true);
        List<List<MetricPoint>> incompleteBranches = List.of(
                List.of(new MetricPoint(40, 28), new MetricPoint(40.5, 28)),
                List.of(new MetricPoint(40, 36), new MetricPoint(40.5, 36)));
        Object result = build.invoke(null, witnessProfiles(), witnessLattices(), witnessSavedStates(), incompleteBranches);
        assertTrue(((List<?>) result).isEmpty(), "a missing source-curve target cannot fall back to the saved MAP state");
    }

    @Test void objectiveRescoreRequiresBothFrozenModelIdentityBindings() throws Exception {
        var kernel = new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(64, 1, 7));
        String identity = kernel.identityHash();
        Map<String, Object> good = Map.of("modelHash", identity,
                "rawGeometry", Map.of("modelHash", identity));
        Map<String, Object> wrongTop = Map.of("modelHash", "0".repeat(64),
                "rawGeometry", Map.of("modelHash", identity));
        Map<String, Object> wrongGraph = Map.of("modelHash", identity,
                "rawGeometry", Map.of("modelHash", "1".repeat(64)));
        Class<?>[] signature = {BIdentifiedObservationKernel.class, Map.class};
        assertTrue((boolean) invokeStatic("modelIdentityMatches", signature, kernel, good));
        assertFalse((boolean) invokeStatic("modelIdentityMatches", signature, kernel, wrongTop));
        assertFalse((boolean) invokeStatic("modelIdentityMatches", signature, kernel, wrongGraph));
    }

    @Test void objectiveEvidenceUsesBoundedJsonLinesBeyondTheSingleReceiptLimit() throws Exception {
        Path output = Files.createDirectory(directory.resolve("evidence-ok"));
        List<Map<String, Object>> rows = List.of(Map.of("path", "map", "payload", "a".repeat(36_000)),
                Map.of("path", "lower-branch", "payload", "b".repeat(36_000)));
        Object receipt = invokeStatic("writeObjectiveEvidence", new Class<?>[] {Path.class, List.class}, output, rows);
        Path evidence = output.resolve("objective-evidence.jsonl");
        byte[] bytes = Files.readAllBytes(evidence);
        assertTrue(bytes.length > 64 * 1024, "combined rows should exceed the old single-object limit");
        assertEquals(2, value(receipt, "rowCount"));
        assertEquals((long) bytes.length, value(receipt, "byteCount"));
        assertEquals(BIdentificationReceipt.hash(bytes), value(receipt, "sha256"));
        assertEquals(2, Files.readAllLines(evidence).size());
    }

    @Test void objectiveEvidenceRejectsOversizeRowsAndInventoriesWithoutPublishingPartialFiles() throws Exception {
        Path overlong = Files.createDirectory(directory.resolve("evidence-line-too-long"));
        assertEvidenceWriteFails(overlong, List.of(Map.of("payload", "x".repeat(64 * 1024))));

        Path tooMany = Files.createDirectory(directory.resolve("evidence-too-many-rows"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 244; index++) rows.add(Map.of("index", index));
        assertEvidenceWriteFails(tooMany, rows);

        Path tooLarge = Files.createDirectory(directory.resolve("evidence-total-too-large"));
        List<Map<String, Object>> largeRows = new ArrayList<>();
        for (int index = 0; index < 243; index++)
            largeRows.add(Map.of("index", index, "payload", "z".repeat(10_000)));
        assertEvidenceWriteFails(tooLarge, largeRows);
    }

    private static void assertEvidenceWriteFails(Path output, List<Map<String, Object>> rows) throws Exception {
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invokeStatic("writeObjectiveEvidence", new Class<?>[] {Path.class, List.class}, output, rows));
        assertFalse(Files.exists(output.resolve("objective-evidence.jsonl")));
        try (var files = Files.list(output)) {
            assertEquals(0, files.count(), "a rejected write must leave no partial or temporary artifact");
        }
    }

    private static Object invokeStatic(String name, Class<?>[] signature, Object... arguments) throws Exception {
        Method method = BObservationRendererDiagnosticTool.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private static Object value(Object target, String name) throws Exception {
        Method accessor = target.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(target);
    }

    private static double originalMapEnergy(List<InferenceProfile> profiles, List<Integer> states,
            EvidenceModelParameters parameters) throws Exception {
        double first = originalEnergy("profileEnergy", profiles, 0, states.get(0), parameters);
        if (profiles.size() == 1) return first;
        double energy = first + originalEnergy("profileEnergy", profiles, 1, states.get(1), parameters)
                + originalEnergy("pairEnergy", profiles, 0, states.get(0), states.get(1), parameters);
        for (int index = 2; index < profiles.size(); index++) {
            double increment = originalEnergy("profileEnergy", profiles, index, states.get(index), parameters)
                    + originalEnergy("pairEnergy", profiles, index - 1, states.get(index - 1), states.get(index), parameters)
                    + originalEnergy("tripleEnergy", profiles, index, states.get(index - 2), states.get(index - 1),
                            states.get(index), parameters);
            energy += increment;
        }
        return energy;
    }

    private static double originalEnergy(String name, Object... arguments) throws Exception {
        Class<?>[] signature = name.equals("profileEnergy")
                ? new Class<?>[] {List.class, int.class, int.class, EvidenceModelParameters.class}
                : name.equals("pairEnergy")
                        ? new Class<?>[] {List.class, int.class, int.class, int.class, EvidenceModelParameters.class}
                        : new Class<?>[] {List.class, int.class, int.class, int.class, int.class,
                                EvidenceModelParameters.class};
        Method method = ProbabilisticInference.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        return (double) method.invoke(null, arguments);
    }

    private static List<InferenceProfile> energyProfiles() {
        List<InferenceProfile> profiles = new ArrayList<>();
        List<LateralStateCell> cells = List.of(new LateralStateCell(-1, 2, false, true, "lower"),
                new LateralStateCell(1, 2, false, true, "upper"));
        for (int index = 0; index < 3; index++) {
            profiles.add(new InferenceProfile(index, new MetricPoint(index, 0), new MetricPoint(0, 1),
                    cells, new double[] {0.25 + index * 0.1, 0.5 + index * 0.2}, List.of(), 0,
                    ObservationOwnership.DIRECT_TWO_SIDED, false));
        }
        return List.copyOf(profiles);
    }

    private static List<InferenceProfile> witnessProfiles() {
        List<InferenceProfile> profiles = new ArrayList<>();
        List<LateralStateCell> cells = witnessLattices().get(0).cells();
        for (int index = 0; index < 81; index++) {
            List<LateralStateCell> profileCells = index == 40
                    ? List.of(cells.get(0), cells.get(1), cells.get(2),
                            new LateralStateCell(0, 1, true, true, "center"), cells.get(4), cells.get(5), cells.get(6))
                    : cells;
            profiles.add(new InferenceProfile(40 + index * .5, new MetricPoint(40 + index * .5, 32),
                    new MetricPoint(0, 1), profileCells, new double[profileCells.size()], List.of(), 0,
                    ObservationOwnership.DIRECT_TWO_SIDED, false));
        }
        return List.copyOf(profiles);
    }

    private static List<ProbabilisticStateLattice> witnessLattices() {
        List<LateralStateCell> cells = List.of(new LateralStateCell(-6, 1, false, true, "lower"),
                new LateralStateCell(-4, 1, false, true, "lower"),
                new LateralStateCell(-2, 1, false, true, "lower"),
                new LateralStateCell(0, 1, false, true, "center"),
                new LateralStateCell(2, 1, false, true, "upper"),
                new LateralStateCell(4, 1, false, true, "upper"),
                new LateralStateCell(6, 1, false, true, "upper"));
        ObservationComponent missing = new ObservationComponent("missing", "missing",
                ObservationComponent.Kind.MISSING, 1, false);
        List<ProbabilisticStateLattice> lattices = new ArrayList<>();
        for (int index = 0; index < 81; index++) {
            List<LateralStateCell> profileCells = index == 40
                    ? List.of(cells.get(0), cells.get(1), cells.get(2),
                            new LateralStateCell(0, 1, true, true, "center"), cells.get(4), cells.get(5), cells.get(6))
                    : cells;
            lattices.add(new ProbabilisticStateLattice(profileCells, List.of(missing), 1, false));
        }
        return List.copyOf(lattices);
    }

    private static List<Integer> witnessSavedStates() {
        List<Integer> states = new ArrayList<>(81);
        for (int index = 0; index < 81; index++) states.add(3);
        return List.copyOf(states);
    }
}
