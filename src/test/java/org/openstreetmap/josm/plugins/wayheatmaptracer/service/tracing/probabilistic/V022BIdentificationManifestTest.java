package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V022BIdentificationManifestTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String valid() {
        StringBuilder owners = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            if (i > 1) owners.append(',');
            owners.append('"').append("OBS-%02d".formatted(i)).append("\":\"retained-test-").append(i).append('"');
        }
        // Protocol fixture only: deliberately not an approved numerical family.
        return """
            {"schema":1,"familyId":"validator-fixture","catalogHash":"%s",
            "featureVersion":"b-identification-facts-3","features":["PROMINENCE"],
            "equations":{"joint":"fixture only","weakLimit":"zero","zeroSupport":"q0",
              "uniform":"full admitted domain including invalid","censored":"frozen",
              "anchors":"zero unary and log measure","L0":"1 metre"},
            "units":{"joint":"1/metre","mass":"dimensionless"},
            "capabilities":{"native":"measured","unknownNative":"sampling descriptor only"},
            "parameterGrid":[{"gain":1}],"maxScenes":512,"maxFitSeconds":3600,
            "existingAttemptCaps":true,"acceptanceOwners":{%s},
            "resultDirectory":"identification/results"}
            """.formatted("a".repeat(64), owners);
    }
    @Test void manifestRejectsMissingEquationsOrUnits() throws Exception {
        assertFalse(BIdentificationManifest.read(bytes(valid())).fields().isEmpty());
        assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(valid().replace("\"units\"", "\"missingUnits\""))));
        assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(valid().replace("\"weakLimit\"", "\"missingWeakLimit\""))));
    }
    @Test void modelCannotConsumeTruthOrPrivateTargets() {
        for (String feature : new String[]{"Truth", "privateTarget", "MAP_PATH", "unknown"})
            assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(valid().replace("PROMINENCE",feature))));
    }
    @Test void familyCannotChangeAfterFreeze() throws Exception {
        var manifest = BIdentificationManifest.read(bytes(valid()));
        assertThrows(UnsupportedOperationException.class, () -> manifest.fields().put("familyId", "changed"));
        @SuppressWarnings("unchecked") var equations = (java.util.Map<String,Object>) manifest.fields().get("equations");
        assertThrows(UnsupportedOperationException.class, () -> equations.put("joint", "changed"));
    }
    @Test void searchDomainAndWorkBudgetAreFinite() {
        for (String altered : new String[]{valid().replace("3600", "3601"),
                valid().replace("512", "513"),valid().replace("\"existingAttemptCaps\":true", "\"existingAttemptCaps\":false"),
                valid().replace("[{\"gain\":1}]", "[" + "{\"gain\":1},".repeat(32) + "{\"gain\":1}]"),
                valid().replace("{\"gain\":1}", "{\"gain\":1e999}")})
            assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(altered)));
    }
    @Test void unknownSourceCapabilityIsExplicit() {
        assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(valid().replace("unknownNative", "missing"))));
    }
    @Test void allTwentySpecObligationsHaveAnAcceptanceOwner() {
        assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(valid().replace("OBS-20", "OBS-21"))));
    }
    @Test void parserRejectsDuplicateKeysUnsafePathsAndUnboundedInput() {
        for (String altered : new String[]{valid().replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                valid().replace("identification/results", "../private"), valid().replace("identification/results", "/tmp/out"),
                "[".repeat(17) + "0" + "]".repeat(17)," ".repeat(65_537)})
            assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(altered)));
    }
    @Test void registeredCandidateUsesCurrentCatalogAndExactThirtyTwoConfigurations() throws Exception {
        var manifest = BIdentificationManifest.read(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "superpowers/docs/plans/2026-10-16-b-observation-identification-manifest.json")));
        assertEquals(BIdentificationCatalog.publicCatalog().canonicalHash(),manifest.fields().get("catalogHash"));
        assertEquals("joint-core-excess-v1",manifest.fields().get("familyId"));
        @SuppressWarnings("unchecked") var grid = (java.util.List<java.util.Map<String,Object>>) manifest.fields().get("parameterGrid");
        var actual = new java.util.HashSet<String>();
        for (var row : grid) actual.add(row.get("G")+":"+row.get("lambda")+":"+row.get("B"));
        assertEquals(32,grid.size());
        for (int gain : new int[]{1,4,16,64}) for (int lambda : new int[]{1,2}) for (int span : new int[]{0,1,3,7})
            assertTrue(actual.contains(gain+":"+lambda+":"+span));
    }
    @Test void candidateSealRejectsChangedEquationsGridAndSourceBeforeEvaluation() throws Exception {
        var input=java.nio.file.Path.of("superpowers/docs/plans/2026-10-16-b-observation-identification-manifest.json");
        String current=java.nio.file.Files.readString(input);
        var altered=temporary.resolve("manifest.json");
        java.nio.file.Files.writeString(altered,current.replace("joint-core-excess-v1","joint-core-excess-v2"));
        var changedFamily=temporary.resolve("changed-family.json");
        java.nio.file.Files.copy(altered,changedFamily);
        java.nio.file.Files.writeString(altered,current);
        var frozen=BIdentificationManifest.register(altered,BIdentificationReceipt.sourceHash());
        java.nio.file.Files.writeString(altered,current+" ");
        assertAll(
                ()->assertThrows(Exception.class,()->BIdentificationManifest.register(changedFamily,BIdentificationReceipt.sourceHash())),
                ()->assertThrows(Exception.class,()->BIdentificationManifest.register(input,"0".repeat(64))),
                ()->assertThrows(Exception.class,frozen::verifyUnchanged));
    }
}
