package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.ObservationManifestJson;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022BIdentificationCatalogTest {
    private static final String FRESH_COHORT = "/select-polynomial-v1/";
    private static final String PROSPECTIVE_RENDERER_REVISION =
            "native-observations-production-resample-v6-strict-math";
    @Test
    void registersFreshSelectAndReplacementHoldoutMetadataWithoutKeepingRetiredHoldout() {
        byte[] dummyIndex = dummyPublicIndex();
        BIdentificationCatalog catalog = BIdentificationCatalog.publicCatalog(dummyIndex);
        BIdentificationCatalog repeated = BIdentificationCatalog.publicCatalog(dummyIndex);
        assertEquals(468, catalog.caseCount());
        assertEquals(468, catalog.inventory(BIdentificationCatalog.EvaluationAccess.catalogView()).size());
        assertEquals(catalog.canonicalHash(), repeated.canonicalHash());

        List<BIdentificationCatalog.CaseDefinition> definitions = catalog.definitions();
        List<BIdentificationCatalog.CaseDefinition> fresh = definitions.stream()
                .filter(definition -> definition.key().id().contains(FRESH_COHORT)).toList();
        assertEquals(244, definitions.size(), "preselection scene API excludes diagnostic and sealed rows");
        assertEquals(356, catalog.activeDefinitionCount());
        assertEquals(112, definitions.stream().filter(d -> d.key().split() == BIdentificationCatalog.Split.TRAIN).count());
        assertEquals(20, definitions.stream().filter(d -> d.key().split() == BIdentificationCatalog.Split.REGRESSION).count());
        assertEquals(112, definitions.stream().filter(d -> d.key().split() == BIdentificationCatalog.Split.SELECT).count());
        assertEquals(0, definitions.stream().filter(d -> d.key().split() == BIdentificationCatalog.Split.INELIGIBLE_DIAGNOSTIC).count());
        var inventory = catalog.inventory(BIdentificationCatalog.EvaluationAccess.catalogView());
        assertEquals(112, inventory.stream().filter(item -> item.split() == BIdentificationCatalog.Split.HOLDOUT).count());
        assertEquals(112, inventory.stream().filter(item -> item.split() == BIdentificationCatalog.Split.INELIGIBLE_DIAGNOSTIC).count());
        assertTrue(inventory.stream().filter(item -> item.split() == BIdentificationCatalog.Split.HOLDOUT)
                .allMatch(item -> item.id().startsWith("b-holdout-v2/")),
                "HOLDOUT inventory may contain only indexed markers");
        assertTrue(inventory.stream().filter(item -> item.split() == BIdentificationCatalog.Split.HOLDOUT)
                .allMatch(item -> catalog.definitions().stream().noneMatch(active -> active.key().id().equals(item.id()))),
                "HOLDOUT metadata is inventory-only");
        assertEquals(112, fresh.size());

        Set<String> oldIds = new HashSet<>();
        definitions.stream().filter(definition -> definition.key().split() != BIdentificationCatalog.Split.SELECT)
                .forEach(definition -> oldIds.add(definition.key().id()));
        Set<String> freshHashes = new HashSet<>();
        Set<String> oldGeneratorGroups = new HashSet<>();
        Set<String> oldGeometryGroups = new HashSet<>();
        Set<String> oldSourceGroups = new HashSet<>();
        definitions.stream().filter(definition -> definition.key().split() != BIdentificationCatalog.Split.SELECT).forEach(definition -> {
            oldGeneratorGroups.add(definition.key().generatorFamilyId());
            oldGeometryGroups.add(definition.key().geometryGroupId());
            oldSourceGroups.add(definition.key().sourceGroupId());
        });
        for (var definition : fresh) {
            assertEquals(BIdentificationCatalog.Split.SELECT, definition.key().split());
            assertTrue(definition.key().id().contains(FRESH_COHORT));
            assertFalse(oldIds.contains(definition.key().id()));
            assertTrue(definition.key().generatorFamilyId().contains("select-polynomial-v1"));
            assertTrue(definition.key().geometryGroupId().contains("select-polynomial-v1"));
            assertTrue(definition.key().sourceGroupId().contains("select-polynomial-v1"));
            assertFalse(oldGeneratorGroups.contains(definition.key().generatorFamilyId()));
            assertFalse(oldGeometryGroups.contains(definition.key().geometryGroupId()));
            assertFalse(oldSourceGroups.contains(definition.key().sourceGroupId()));
            assertTrue(freshHashes.add(BIdentificationCatalog.recipeHash(definition)),
                    "fresh scene recipes must have deterministic, non-aliased identities");
            var other = repeated.definitions().stream()
                    .filter(candidate -> candidate.key().id().equals(definition.key().id())).findFirst().orElseThrow();
            assertEquals(BIdentificationCatalog.recipeHash(definition), BIdentificationCatalog.recipeHash(other));
        }
        assertEquals(112, freshHashes.size());
        var preselection = BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.FIT_SELECT);
        var retired = inventory.stream().filter(item -> item.split() == BIdentificationCatalog.Split.INELIGIBLE_DIAGNOSTIC)
                .findFirst().orElseThrow();
        assertThrows(SecurityException.class, () -> catalog.keyForMaterialization(retired.id(), preselection));
        var holdout = inventory.stream().filter(item -> item.split() == BIdentificationCatalog.Split.HOLDOUT)
                .findFirst().orElseThrow();
        assertThrows(SecurityException.class, () -> catalog.keyForMaterialization(holdout.id(), preselection));
        assertEquals(0, preselection.rasterReads());
        assertEquals(0, preselection.labelReads());
    }

    @Test
    void freshMetadataResolvesExactlyWithoutOpeningRasterOrLabels() {
        BIdentificationCatalog catalog = BIdentificationCatalog.publicCatalog(dummyPublicIndex());
        var access = BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.FIT_SELECT);
        var metadataAccess = BIdentificationCatalog.EvaluationAccess.catalogView();
        var item = catalog.inventory(metadataAccess).stream()
                .filter(candidate -> candidate.id().contains(FRESH_COHORT)).findFirst().orElseThrow();
        var key = catalog.keyForMaterialization(item.id(), access);
        var definition = BIdentificationCatalog.definition(key, access);
        assertEquals(item.id(), definition.key().id());
        assertEquals(item.recipeHash(), BIdentificationCatalog.recipeHash(definition));
        assertEquals(0, access.rasterReads());
        assertEquals(0, access.labelReads());

        var altered = new BIdentificationCatalog.CaseKey(key.id(), key.generatorFamilyId(), key.geometryGroupId(),
                key.sourceGroupId(), key.split(), key.recipeId(), key.seed() + 1);
        assertThrows(SecurityException.class, () -> BIdentificationCatalog.definition(altered, access));
        assertEquals(0, access.rasterReads());
        assertEquals(0, access.labelReads());
    }

    @Test
    void freshSelectMetadataDescribesThePreregisteredPolynomialWithoutMaterializingIt() {
        var catalog = BIdentificationCatalog.publicCatalog(dummyPublicIndex());
        var fresh = catalog.definitions().stream()
                .filter(definition -> definition.key().id().contains(FRESH_COHORT)).toList();
        assertEquals(112, fresh.size());
        assertTrue(fresh.stream().allMatch(definition -> definition.equation().contains("F(x)")));
        assertTrue(fresh.stream().allMatch(definition -> definition.key().geometryGroupId().contains("select-polynomial-v1")));
        assertTrue(fresh.stream().allMatch(definition -> definition.capabilities().contains("certified-metric-frame")));
        assertTrue(fresh.stream().filter(definition -> !(definition.contrastId().equals("palette-semantic-vs-rendered-source")
                && definition.side().equals("right")))
                .allMatch(definition -> definition.capabilities().contains("typed-native-resolution")));
        assertTrue(fresh.stream().filter(definition -> definition.contrastId().equals("palette-semantic-vs-rendered-source")
                && definition.side().equals("right"))
                .allMatch(definition -> definition.capabilities().contains("rendered-only-resolution")));
        var access = BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.FIT_SELECT);
        assertEquals(0, access.rasterReads());
        assertEquals(0, access.labelReads());
    }

    @Test
    void rendererUsesProspectiveStrictMathIdentityAndTrainOnlyColdWarmInputsMatch() throws Exception {
        assertEquals(PROSPECTIVE_RENDERER_REVISION, BIdentificationCatalog.RENDERER_REVISION);
        var definition = BIdentificationCatalog.trainDiagnosticCatalog().definitions().stream()
                .filter(candidate -> candidate.key().split() == BIdentificationCatalog.Split.TRAIN)
                .filter(candidate -> candidate.contrastId().equals("weak-coherent-curve-vs-disconnected-flecks"))
                .filter(candidate -> candidate.variantId().equals("v0") && candidate.side().equals("left"))
                .findFirst().orElseThrow();
        var access = BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.FIT_SELECT);

        var cold = trainFingerprint(definition, access);
        var warm = trainFingerprint(definition, access);
        assertEquals(cold.nativeHash(), warm.nativeHash());
        assertEquals(cold.nativeFrame(), warm.nativeFrame());
        assertEquals(cold.factsHash(), warm.factsHash());
        assertEquals(2, access.rasterReads());
        assertEquals(2, access.labelReads());
        assertNotEquals("", cold.nativeHash());
        System.out.println("TRAIN_RENDER_RECEIPT " + BIdentificationReceipt.canonical(Map.of(
                "caseId", definition.key().id(),
                "rendererRevision", BIdentificationCatalog.RENDERER_REVISION,
                "nativeHash", cold.nativeHash(),
                "nativeFrame", cold.nativeFrame(),
                "factsHash", cold.factsHash())));
    }

    @Test
    void originalTrainMetadataMatchesImmutablePreAmendmentOracle() throws Exception {
        byte[] oracleBytes;
        try (var input = getClass().getResourceAsStream(
                "/b-observation-identification/original-train-metadata.tsv")) {
            assertTrue(input != null, "frozen original TRAIN metadata oracle is required");
            oracleBytes = input.readAllBytes();
        }
        List<String> expected = new String(oracleBytes, StandardCharsets.UTF_8).lines().toList();
        List<String> observed = BIdentificationCatalog.trainDiagnosticCatalog().definitions().stream()
                .filter(definition -> definition.key().split() == BIdentificationCatalog.Split.TRAIN)
                .map(V022BIdentificationCatalogTest::metadataLine).sorted().toList();
        assertEquals(112, expected.size());
        assertEquals(expected, observed,
                "original TRAIN case identity, seed, variant, reversal, equation and capabilities are frozen");
    }

    @Test
    void originalTrainRightApexKeepsItsHalfPixelSamplePhase() {
        var catalog = BIdentificationCatalog.trainDiagnosticCatalog();
        var rightV1 = catalog.definitions().stream()
                .filter(definition -> definition.key().split() == BIdentificationCatalog.Split.TRAIN)
                .filter(definition -> definition.contrastId().equals("resolved-short-apex-vs-stair-step-alias"))
                .filter(definition -> definition.variantId().equals("v1") && definition.side().equals("right"))
                .findFirst().orElseThrow();
        var access = BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.EXTRACT);
        var rightScene = BIdentificationScenes.render(rightV1.key(), access);
        assertEquals(0.25, rightV1.phase());
        assertEquals(0.5, rightV1.sourcePitchMeters());
        // Original v1 right-apex phase is ((0.25 + 0.5) % 1) * 0.5 = 0.375 m.
        // The original native raster origin is (-32 - phase, 62 + phase).
        assertEquals(new MetricPoint(-32.375, 62.375),
                rightScene.evidence().transform().pixelCenterToMetric(0, 0));
    }

    @Test
    void originalTrainAndRegressionFleckTruthKeepsAssociationNotApplicable() {
        var catalog = BIdentificationCatalog.trainDiagnosticCatalog();
        var trainRight = catalog.definitions().stream()
                .filter(definition -> definition.key().split() == BIdentificationCatalog.Split.TRAIN)
                .filter(definition -> definition.contrastId().equals("weak-coherent-curve-vs-disconnected-flecks"))
                .filter(definition -> definition.variantId().equals("v0") && definition.side().equals("right"))
                .findFirst().orElseThrow();
        var regressionRight = catalog.definitions().stream()
                .filter(definition -> definition.key().split() == BIdentificationCatalog.Split.REGRESSION)
                .filter(definition -> definition.contrastId().equals("weak-coherent-curve-vs-disconnected-flecks"))
                .filter(definition -> definition.variantId().equals("regression-b"))
                .findFirst().orElseThrow();
        var access = BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.EXTRACT);
        assertEquals(BIdentificationCatalog.AssociationLabel.NOT_APPLICABLE,
                BIdentificationScenes.render(trainRight.key(), access).truth().regions().get(0).association());
        assertEquals(BIdentificationCatalog.AssociationLabel.NOT_APPLICABLE,
                BIdentificationScenes.render(regressionRight.key(), access).truth().regions().get(0).association());
    }

    @Test
    void fixedRegressionRowsAreMaterializableForDiagnosticsButNeverEnterSelectPairs() {
        var catalog = BIdentificationCatalog.publicCatalog(dummyPublicIndex());
        var regressions = catalog.definitions().stream()
                .filter(definition -> definition.key().split() == BIdentificationCatalog.Split.REGRESSION).toList();
        assertEquals(20, regressions.size());
        for (var phase : List.of(BIdentificationCatalog.Phase.EXTRACT, BIdentificationCatalog.Phase.FIT_SELECT)) {
            var access = BIdentificationCatalog.EvaluationAccess.preselection(phase);
            for (var regression : regressions) {
                var key = catalog.keyForMaterialization(regression.key().id(), access);
                assertEquals(regression, BIdentificationCatalog.definition(key, access));
            }
            var sample = regressions.get(0);
            var scene = BIdentificationScenes.render(sample.key(), access);
            assertFalse(scene.source().isEmpty());
            assertEquals(1, access.rasterReads());
            assertEquals(1, access.labelReads());
        }
        var select = catalog.definitions().stream()
                .filter(definition -> definition.key().split() == BIdentificationCatalog.Split.SELECT)
                .findFirst().orElseThrow();
        var mixedPairInventory = List.of(select, regressions.get(0));
        assertThrows(IllegalArgumentException.class,
                () -> BObservationIdentificationTool.requiredPairs(mixedPairInventory, CancellationProbe.NONE));
        var holdoutMarker = catalog.inventory(BIdentificationCatalog.EvaluationAccess.catalogView()).stream()
                .filter(item -> item.split() == BIdentificationCatalog.Split.HOLDOUT).findFirst().orElseThrow();
        assertThrows(SecurityException.class, () -> catalog.keyForMaterialization(holdoutMarker.id(),
                BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.EXTRACT)));
    }

    private static String metadataLine(BIdentificationCatalog.CaseDefinition definition) {
        var key = definition.key();
        return String.join("\t", key.id(), key.generatorFamilyId(), key.geometryGroupId(), key.sourceGroupId(),
                key.recipeId(), Long.toString(key.seed()), definition.contrastId(), definition.side(),
                definition.variantId(), decimal(definition.phase()),
                decimal(definition.sourcePitchMeters()), Integer.toString(definition.oversampling()),
                Boolean.toString(definition.reversed()), definition.equation(),
                String.join(";", definition.capabilities()));
    }

    private static String decimal(double value) {
        String text = Double.toString(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static RendererFingerprint trainFingerprint(BIdentificationCatalog.CaseDefinition definition,
            BIdentificationCatalog.EvaluationAccess access) throws Exception {
        var scene = BIdentificationScenes.render(definition.key(), access);
        var source = BObservationIdentificationTool.sourceKey(definition, scene);
        var cancellation = CancellationProbe.NONE;
        var factory = new ProbabilisticProfileFactory();
        try (var owner = new AttemptMemoryLedger(64L * 1024 * 1024).rootOwner()) {
            var profiles = factory.create(scene.source(), factory.profileChainage(scene.source(), 1.5), 25, true,
                    scene.evidence(), scene.evidence().fields().get("scalar"), EvidenceModelParameters.defaults(),
                    cancellation, true, owner);
            var facts = new BIdentificationFactsExtractor().extract(scene.evidence(), profiles, cancellation, owner);
            var lattices = BObservationIdentificationTool.admitLattices(
                    profiles, EvidenceModelParameters.defaults(), cancellation, owner).lattices();
            return new RendererFingerprint(source.nativeHash(), source.nativeFrame(),
                    BIdentificationReceipt.factsHash(facts, lattices, cancellation));
        }
    }

    @Test
    void publicIndexParserRejectsMalformedAndNoncanonicalMetadata() {
        byte[] valid = dummyPublicIndex();
        String json = new String(valid, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> BIdentificationCatalog.publicCatalog(
                json.replaceFirst("sealed-observation-v2", "unregistered-v2").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> BIdentificationCatalog.publicCatalog(
                json.replaceFirst("\"side\":\"left\"", "\"side\":\"unknown\"")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> BIdentificationCatalog.publicCatalog(
                (json + " ").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> BIdentificationCatalog.publicCatalog(new byte[65_537]));
        assertThrows(IllegalArgumentException.class, () -> BIdentificationCatalog.publicCatalog((byte[]) null));
        assertEquals("97b134e7c983b698679258f244ee8431696bb34257615ce27675e19c21f6b092",
                BIdentificationCatalog.publicCatalog().publicIndexHash());
    }

    @Test
    void publicIndexParserRejectsAdversarialSchemaReferencesAndPrivateFields() {
        assertRejectedIndex(index -> index.put("unexpected", true));
        assertRejectedIndex(index -> index.put("recipes", List.of("private recipe values are forbidden")));
        assertRejectedIndex(index -> index.remove("schema"));
        assertRejectedIndex(index -> object(index.get("payload")).put("privatePayload", "forbidden"));
        assertRejectedIndex(index -> object(object(index.get("groups")).get("g0")).remove("sourceGroupId"));
        assertRejectedIndex(index -> object(object(index.get("groups")).get("g0"))
                .put("contrast", "unregistered-contrast"));
        assertRejectedIndex(index -> object(object(index.get("groups")).get("g0"))
                .put("generatorGroupId", "b-observation-holdout-v2-geometry-g0"));
        assertRejectedIndex(index -> object(object(index.get("variants")).get("v0"))
                .put("phase", 0.125));
        assertRejectedIndex(index -> object(object(index.get("variants")).get("v0"))
                .put("oversampling", 1.0));
        assertRejectedIndex(index -> object(object(index.get("variants")).get("v0"))
                .put("privateGeometry", List.of(29.0, 31.0)));
        assertRejectedIndex(index -> {
            Map<String, Object> entry = object(list(index.get("entries")).get(0));
            entry.put("side", "middle");
        });
        assertRejectedIndex(index -> {
            Map<String, Object> entry = object(list(index.get("entries")).get(0));
            entry.put("variant", "v8");
        });
        assertRejectedIndex(index -> {
            Map<String, Object> entry = object(list(index.get("entries")).get(0));
            entry.put("group", "g7");
        });
        assertRejectedIndex(index -> {
            Map<String, Object> entry = object(list(index.get("entries")).get(0));
            entry.put("id", "b-holdout-v2/g0/v0/right");
        });
        assertRejectedIndex(index -> object(list(index.get("entries")).get(0)).remove("recipeHash"));
        assertRejectedIndex(index -> object(list(index.get("entries")).get(0)).put("recipeHash", "not-a-hash"));
        assertRejectedIndex(index -> {
            Map<String, Object> entry = object(list(index.get("entries")).get(0));
            entry.put("privateRecipeGeometry", "not part of public metadata");
        });
        assertRejectedIndex(index -> list(index.get("entries")).remove(0));
        assertRejectedIndex(index -> {
            List<Object> entries = list(index.get("entries"));
            object(entries.get(1)).put("recipeHash", object(entries.get(0)).get("recipeHash"));
        });

        String duplicateKey = new String(dummyPublicIndex(), StandardCharsets.UTF_8)
                .replaceFirst("\"schema\":1", "\"schema\":1,\"schema\":1");
        assertThrows(IllegalArgumentException.class, () -> BIdentificationCatalog.publicCatalog(
                duplicateKey.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void publicIndexAcceptsProducerDefinedZeroPayloadAndBindsExactReviewedBytes() throws Exception {
        byte[] emptyPayloadIndex = mutatedDummyIndex(index -> {
            Map<String, Object> payload = object(index.get("payload"));
            payload.put("byteCount", 0);
            payload.put("sha256", hash(""));
        });
        assertEquals(468, BIdentificationCatalog.publicCatalog(emptyPayloadIndex).caseCount());
        assertRejectedIndex(index -> object(index.get("payload")).put("byteCount", -1));
        assertRejectedIndex(index -> object(index.get("payload")).put("byteCount", true));

        byte[] reviewedBytes;
        try (var input = getClass().getResourceAsStream("/b-observation-identification/holdout-index.json")) {
            assertTrue(input != null, "reviewed public index resource is required");
            reviewedBytes = input.readAllBytes();
        }
        assertEquals(hash(reviewedBytes), BIdentificationCatalog.publicCatalog().publicIndexHash());
        assertEquals("97b134e7c983b698679258f244ee8431696bb34257615ce27675e19c21f6b092",
                hash(reviewedBytes));
    }

    @Test
    void retiredSelectAndSealedMarkersCannotEnterSceneOrScoringInventories() {
        BIdentificationCatalog catalog = BIdentificationCatalog.publicCatalog(dummyPublicIndex());
        var catalogAccess = BIdentificationCatalog.EvaluationAccess.catalogView();
        var oldSelect = catalog.inventory(catalogAccess).stream()
                .filter(item -> item.split() == BIdentificationCatalog.Split.INELIGIBLE_DIAGNOSTIC)
                .findFirst().orElseThrow();
        var sealedMarker = catalog.inventory(catalogAccess).stream()
                .filter(item -> item.split() == BIdentificationCatalog.Split.HOLDOUT)
                .findFirst().orElseThrow();
        assertTrue(catalog.definitions().stream()
                .noneMatch(definition -> definition.key().split() == BIdentificationCatalog.Split.INELIGIBLE_DIAGNOSTIC));
        for (BIdentificationCatalog.Phase phase : List.of(
                BIdentificationCatalog.Phase.EXTRACT, BIdentificationCatalog.Phase.FIT_SELECT)) {
            var access = BIdentificationCatalog.EvaluationAccess.preselection(phase);
            assertThrows(SecurityException.class, () -> catalog.keyForMaterialization(oldSelect.id(), access));
            assertThrows(SecurityException.class, () -> catalog.keyForMaterialization(sealedMarker.id(), access));
            assertEquals(0, access.rasterReads());
            assertEquals(0, access.labelReads());
        }
        assertThrows(SecurityException.class,
                () -> BIdentificationCatalog.EvaluationAccess.holdout("candidate-hash", "manifest-hash"));

        var retiredKey = new BIdentificationCatalog.CaseKey(oldSelect.id(), "old-generator", "old-geometry",
                "old-source", BIdentificationCatalog.Split.SELECT, "old-recipe", 0L);
        var selectionAccess = BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.FIT_SELECT);
        assertThrows(SecurityException.class, () -> BIdentificationCatalog.definition(retiredKey, selectionAccess));
        assertEquals(0, selectionAccess.rasterReads());
        assertEquals(0, selectionAccess.labelReads());
    }

    private static void assertRejectedIndex(Consumer<Map<String, Object>> mutation) {
        assertThrows(IllegalArgumentException.class,
                () -> BIdentificationCatalog.publicCatalog(mutatedDummyIndex(mutation)));
    }

    private static byte[] mutatedDummyIndex(Consumer<Map<String, Object>> mutation) {
        try {
            Map<String, Object> index = deepMap(ObservationManifestJson.parse(dummyPublicIndex()));
            mutation.accept(index);
            return (BIdentificationReceipt.canonical(index) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new AssertionError("Dummy public-index mutation failed", failure);
        }
    }

    private static Map<String, Object> deepMap(Map<String, Object> original) {
        Map<String, Object> copy = new TreeMap<>();
        original.forEach((key, value) -> copy.put(key, deepValue(value)));
        return copy;
    }

    private static Object deepValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> copy = new TreeMap<>();
            nested.forEach((key, item) -> copy.put((String) key, deepValue(item)));
            return copy;
        }
        if (value instanceof List<?> nested) {
            List<Object> copy = new ArrayList<>(nested.size());
            nested.forEach(item -> copy.add(deepValue(item)));
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) { return (List<Object>) value; }

    @Test
    void groupedAliasesAcrossIdentityRolesCannotCrossSplits() {
        var all = BIdentificationCatalog.publicCatalog(dummyPublicIndex()).definitions();
        var train = all.stream().filter(d -> d.key().split() == BIdentificationCatalog.Split.TRAIN)
                .findFirst().orElseThrow();
        var select = all.stream().filter(d -> d.key().split() == BIdentificationCatalog.Split.SELECT)
                .findFirst().orElseThrow();
        var aliasedKey = new BIdentificationCatalog.CaseKey(select.key().id(), select.key().generatorFamilyId(),
                train.key().geometryGroupId(), select.key().sourceGroupId(), select.key().split(),
                select.key().recipeId(), select.key().seed());
        var aliased = new BIdentificationCatalog.CaseDefinition(aliasedKey, select.contrastId(), select.side(),
                select.variantId(), select.phase(), select.sourcePitchMeters(), select.oversampling(),
                select.reversed(), select.equation(), select.capabilities());
        assertThrows(IllegalArgumentException.class, () -> new BIdentificationCatalog(List.of(train, aliased)));
    }

    private static byte[] dummyPublicIndex() {
        List<String> contrasts = List.of("concentrated-band-vs-diffuse-band",
                "displaced-strong-corridor-vs-nearby-faint-strand",
                "multiple-physical-pitches-vs-reversed-source-order", "palette-semantic-vs-rendered-source",
                "resolved-short-apex-vs-stair-step-alias",
                "sparse-crossing-noisy-strands-vs-persistent-parallel",
                "weak-coherent-curve-vs-disconnected-flecks");
        double[] phases = {0.0, 0.25, 0.5, 0.75, 0.25, 0.75, 0.0, 0.5};
        double[] pitches = {0.5, 0.5, 1.0, 1.0, 1.75, 1.75, 0.5, 1.75};
        int[] oversampling = {1, 2, 1, 2, 1, 2, 2, 1};
        Map<String, Object> index = new TreeMap<>();
        index.put("schema", 1);
        index.put("version", "sealed-observation-v2");
        index.put("generatorHash", hash("dummy-generator"));
        index.put("protocolHash", hash("dummy-protocol"));
        index.put("split", "HOLDOUT");
        index.put("payload", Map.of("sha256", hash("dummy-payload"), "byteCount", 12));
        Map<String, Object> groups = new TreeMap<>();
        for (int g = 0; g < 7; g++) {
            String id = "g" + g;
            groups.put(id, Map.of("contrast", contrasts.get(g),
                    "generatorGroupId", "b-observation-holdout-v2-generator-" + id,
                    "geometryGroupId", "b-observation-holdout-v2-geometry-" + id,
                    "sourceGroupId", "b-observation-holdout-v2-source-" + id));
        }
        index.put("groups", groups);
        Map<String, Object> variants = new TreeMap<>();
        for (int v = 0; v < 8; v++) variants.put("v" + v, Map.of(
                "phase", phases[v], "sourcePitchMeters", pitches[v], "oversampling", oversampling[v]));
        index.put("variants", variants);
        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        for (int g = 0; g < 7; g++) for (int v = 0; v < 8; v++) for (String side : List.of("left", "right")) {
            String id = "b-holdout-v2/g" + g + "/v" + v + "/" + side;
            entries.add(Map.of("id", id, "group", "g" + g, "variant", "v" + v,
                    "side", side, "recipeHash", hash("dummy-recipe/" + id)));
        }
        entries.sort(java.util.Comparator.comparing(entry -> (String) entry.get("id")));
        index.put("entries", entries);
        return (canonicalJson(index) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String canonicalJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof List<?> list) return "[" + list.stream().map(V022BIdentificationCatalogTest::canonicalJson)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        if (value instanceof Map<?, ?> map) return "{" + map.entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> (String) entry.getKey()))
                .map(entry -> quote((String) entry.getKey()) + ":" + canonicalJson(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(",")) + "}";
        throw new IllegalArgumentException("Unsupported dummy fixture value");
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hash(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record RendererFingerprint(String nativeHash, Object nativeFrame, String factsHash) { }
}
