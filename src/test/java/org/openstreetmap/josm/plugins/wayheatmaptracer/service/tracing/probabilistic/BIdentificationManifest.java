package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.ObservationManifestJson;

/** Immutable preregistration validator; candidate equations are supplied by reviewed design. */
final class BIdentificationManifest {
    static final String REVIEWED_MANIFEST_SHA256 = "6e44cdc2b75d6d960861bea90f0916a809ff10145341c7431dc52fcdedc16003";
    record Seal(java.nio.file.Path path, String manifestHash, String sourceHash,
            String catalogHash, BIdentificationManifest manifest) {
        void verifyUnchanged() throws Exception {
            if(!manifestHash.equals(BIdentificationReceipt.hash(java.nio.file.Files.readAllBytes(path)))
                    || !sourceHash.equals(BIdentificationReceipt.sourceHash())
                    || !catalogHash.equals(BIdentificationCatalog.publicCatalog().canonicalHash()))
                throw new IllegalStateException("Registered experiment changed before evaluation");
        }
    }
    static Seal register(java.nio.file.Path path,String reviewedSourceHash) throws Exception {
        if(java.nio.file.Files.isSymbolicLink(path)||java.nio.file.Files.size(path)>65_536)
            throw new IllegalArgumentException("Unsafe manifest");
        byte[] bytes=java.nio.file.Files.readAllBytes(path);
        String hash=BIdentificationReceipt.hash(bytes);
        if(!REVIEWED_MANIFEST_SHA256.equals(hash))throw new IllegalArgumentException("Unreviewed family/equations/grid/capability mutation");
        var manifest=read(bytes);
        String catalog=BIdentificationCatalog.publicCatalog().canonicalHash();
        String source=BIdentificationReceipt.sourceHash();
        if(!catalog.equals(manifest.fields().get("catalogHash"))||!source.equals(reviewedSourceHash))
            throw new IllegalArgumentException("Source or catalog binding changed");
        return new Seal(path.toAbsolutePath(),hash,source,catalog,manifest);
    }
    private final Map<String, Object> values;
    private BIdentificationManifest(Map<String, Object> values) { this.values = values; }
    static BIdentificationManifest read(byte[] bytes) throws Exception {
        Map<String, Object> values = ObservationManifestJson.parse(bytes);
        if (!values.keySet().equals(Set.of("schema", "familyId", "catalogHash", "featureVersion",
                "features", "equations", "units", "capabilities", "parameterGrid", "maxScenes",
                "maxFitSeconds", "existingAttemptCaps", "acceptanceOwners", "resultDirectory")))
            throw new IllegalArgumentException("Unknown or absent manifest field");
        integer(values.get("schema"),1,1);
        text(values.get("familyId"));
        if (!text(values.get("catalogHash")).matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Catalog hash required");
        if (!BIdentificationFacts.VERSION.equals(text(values.get("featureVersion"))))
            throw new IllegalArgumentException("Feature version mismatch");
        if (!(values.get("features") instanceof List<?> features) || features.isEmpty())
            throw new IllegalArgumentException("Feature inventory required");
        for (Object feature : features) BIdentificationFacts.Feature.valueOf(text(feature));
        if (features.stream().distinct().count() != features.size())
            throw new IllegalArgumentException("Duplicate feature");
        Map<?,?> equations = object(values.get("equations"));
        for (String name : List.of("joint","weakLimit","zeroSupport","uniform","censored","anchors","L0"))
            text(equations.get(name));
        Map<?,?> units = object(values.get("units"));
        for (String name : List.of("joint","mass")) text(units.get(name));
        Map<?,?> capabilities = object(values.get("capabilities"));
        for (String name : List.of("native","unknownNative")) text(capabilities.get(name));
        if (!(values.get("parameterGrid") instanceof List<?> grid) || grid.isEmpty() || grid.size() > 32)
            throw new IllegalArgumentException("Parameter grid must have 1 to 32 entries");
        Set<?> parameterNames = null;
        for (Object configuration : grid) {
            Map<?,?> parameters = object(configuration);
            if (parameters.isEmpty() || parameterNames != null && !parameterNames.equals(parameters.keySet()))
                throw new IllegalArgumentException("Inconsistent parameter configurations");
            parameterNames = parameters.keySet();
            for (var entry : parameters.entrySet()) {
                text(entry.getKey());
                if (!(entry.getValue() instanceof Number number) || !Double.isFinite(number.doubleValue()))
                    throw new IllegalArgumentException("Finite numeric parameters required");
            }
        }
        integer(values.get("maxScenes"),1,512);
        integer(values.get("maxFitSeconds"),1,3600);
        if (!Boolean.TRUE.equals(values.get("existingAttemptCaps")))
            throw new IllegalArgumentException("Existing attempt caps must be retained");
        Map<?,?> owners = object(values.get("acceptanceOwners"));
        if (owners.size() != 20) throw new IllegalArgumentException("All OBS obligations required");
        for (int i = 1; i <= 20; i++) text(owners.get("OBS-%02d".formatted(i)));
        String directory = text(values.get("resultDirectory"));
        if (!directory.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))
            throw new IllegalArgumentException("Only a relative safe result path is permitted");
        @SuppressWarnings("unchecked") Map<String,Object> frozen = (Map<String,Object>) freeze(values);
        return new BIdentificationManifest(frozen);
    }
    Map<String, Object> fields() { return values; }
    private static Map<?,?> object(Object value) {
        if (!(value instanceof Map<?,?> map)) throw new IllegalArgumentException("Object required");
        return map;
    }
    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 8192)
            throw new IllegalArgumentException("Bounded nonblank text required");
        return text;
    }
    private static void integer(Object value,int minimum,int maximum) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue() || number.intValue() < minimum
                || number.intValue() > maximum) throw new IllegalArgumentException("Integer outside budget");
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?,?> map) {
            Map<String,Object> result = new LinkedHashMap<>();
            map.forEach((key,item) -> result.put(text(key),freeze(item)));
            return java.util.Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) return list.stream().map(BIdentificationManifest::freeze).toList();
        return value;
    }
}
