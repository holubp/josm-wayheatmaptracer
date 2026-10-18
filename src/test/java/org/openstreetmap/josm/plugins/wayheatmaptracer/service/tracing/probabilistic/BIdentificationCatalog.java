package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.ObservationManifestJson;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;

/** Frozen public scene inventory for offline B-observation identification. */
final class BIdentificationCatalog {
    enum Split { TRAIN, SELECT, INELIGIBLE_DIAGNOSTIC, HOLDOUT, REGRESSION }
    enum Phase { CATALOG, EXTRACT, FIT_SELECT, HOLDOUT }
    enum StructureLabel { OBSERVED_STRUCTURE, NO_STRUCTURE, UNKNOWN }
    enum RasterLabel { VALID, INVALID, CENSORED, MIXED }
    enum AssociationLabel { UNIQUE, AMBIGUOUS, NOT_APPLICABLE }

    record CaseKey(String id, String generatorFamilyId, String geometryGroupId, String sourceGroupId,
            Split split, String recipeId, long seed) {
        CaseKey {
            if (blank(id) || blank(generatorFamilyId) || blank(geometryGroupId) || blank(sourceGroupId)
                    || split == null || blank(recipeId)) {
                throw new IllegalArgumentException("Case identity and split are required");
            }
        }
    }

    record CaseDefinition(CaseKey key, String contrastId, String side, String variantId,
            double phase, double sourcePitchMeters, int oversampling, boolean reversed,
            String equation, List<String> capabilities) {
        CaseDefinition {
            Objects.requireNonNull(key, "key");
            if (blank(contrastId) || blank(side) || blank(variantId) || !Double.isFinite(phase)
                    || phase < 0 || phase >= 1 || !Double.isFinite(sourcePitchMeters)
                    || sourcePitchMeters <= 0 || oversampling < 1 || blank(equation)
                    || capabilities == null || capabilities.isEmpty()) {
                throw new IllegalArgumentException("Scene recipe is incomplete");
            }
            capabilities = List.copyOf(capabilities);
        }
    }

    record InventoryItem(String id, Split split, String recipeHash) { }

    record LabelRegion(MetricRegion region, StructureLabel structure, RasterLabel raster,
            AssociationLabel association, List<List<MetricPoint>> acceptableCurves) {
        LabelRegion {
            if (region == null || structure == null || raster == null || association == null
                    || acceptableCurves == null) throw new IllegalArgumentException("Truth labels are required");
            acceptableCurves = acceptableCurves.stream().map(List::copyOf).toList();
            if (association == AssociationLabel.AMBIGUOUS && acceptableCurves.size() < 2) {
                throw new IllegalArgumentException("Ambiguous labels require multiple acceptable branches");
            }
        }
    }

    record Truth(List<LabelRegion> regions) {
        Truth { regions = List.copyOf(regions); }
    }

    record Scene(EvidenceSnapshot evidence, List<MetricPoint> source, Truth truth) {
        Scene {
            if (evidence == null || source == null || source.size() < 2 || truth == null) {
                throw new IllegalArgumentException("Rendered scene is incomplete");
            }
            source = List.copyOf(source);
        }
    }

    private static final List<String> CONTRASTS = List.of(
            "concentrated-band-vs-diffuse-band",
            "displaced-strong-corridor-vs-nearby-faint-strand",
            "multiple-physical-pitches-vs-reversed-source-order",
            "palette-semantic-vs-rendered-source",
            "resolved-short-apex-vs-stair-step-alias",
            "sparse-crossing-noisy-strands-vs-persistent-parallel",
            "weak-coherent-curve-vs-disconnected-flecks");
    private static final List<String> INDEX_VARIANTS = List.of("v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7");
    private static final List<String> INDEX_CONTRASTS = List.of(
            "concentrated-band-vs-diffuse-band",
            "displaced-strong-corridor-vs-nearby-faint-strand",
            "multiple-physical-pitches-vs-reversed-source-order",
            "palette-semantic-vs-rendered-source",
            "resolved-short-apex-vs-stair-step-alias",
            "sparse-crossing-noisy-strands-vs-persistent-parallel",
            "weak-coherent-curve-vs-disconnected-flecks");
    private static final List<String> ORIGINAL_CONTRAST_ORDER = List.of(
            "weak-coherent-curve-vs-disconnected-flecks",
            "concentrated-band-vs-diffuse-band",
            "sparse-crossing-noisy-strands-vs-persistent-parallel",
            "resolved-short-apex-vs-stair-step-alias",
            "displaced-strong-corridor-vs-nearby-faint-strand",
            "blank-uniform-invalid-censored",
            "multiple-physical-pitches-vs-reversed-source-order",
            "palette-semantic-vs-rendered-source");
    static final String RENDERER_REVISION = "native-observations-production-resample-v6-strict-math";
    private static final int MAX_INDEX_BYTES = 65_536;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    private static final String PUBLIC_INDEX_RESOURCE = "/b-observation-identification/holdout-index.json";
    private static final double[] PHASES = {0.0, 0.25, 0.5, 0.75, 0.25, 0.75, 0.0, 0.5};
    private static final double[] PITCHES = {0.5, 0.5, 1.0, 1.0, 1.75, 1.75, 0.5, 1.75};
    private static final int[] OVERSAMPLING = {1, 2, 1, 2, 1, 2, 2, 1};

    private final List<CaseDefinition> allDefinitions;
    private final List<CaseDefinition> preHoldoutDefinitions;
    private final Map<String, CaseDefinition> byId;
    private final String publicIndexHash;

    BIdentificationCatalog(List<CaseDefinition> definitions) { this(definitions, ""); }

    private BIdentificationCatalog(List<CaseDefinition> definitions, String publicIndexHash) {
        if (definitions == null || definitions.isEmpty() || definitions.size() > 512) {
            throw new IllegalArgumentException("Catalog must contain 1 to 512 scenes");
        }
        this.allDefinitions = List.copyOf(definitions);
        this.publicIndexHash = Objects.requireNonNull(publicIndexHash, "publicIndexHash");
        Map<String, CaseDefinition> index = new LinkedHashMap<>();
        Map<String, Split> groupSplits = new HashMap<>();
        for (CaseDefinition definition : this.allDefinitions) {
            if (index.putIfAbsent(definition.key().id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate scene id");
            }
            checkGroupSplit(groupSplits, "generator", definition.key().generatorFamilyId(), definition.key().split());
            checkGroupSplit(groupSplits, "geometry", definition.key().geometryGroupId(), definition.key().split());
            checkGroupSplit(groupSplits, "source", definition.key().sourceGroupId(), definition.key().split());
            checkGroupSplit(groupSplits, "all-identities", definition.key().generatorFamilyId(), definition.key().split());
            checkGroupSplit(groupSplits, "all-identities", definition.key().geometryGroupId(), definition.key().split());
            checkGroupSplit(groupSplits, "all-identities", definition.key().sourceGroupId(), definition.key().split());
        }
        byId = Map.copyOf(index);
        preHoldoutDefinitions = this.allDefinitions.stream()
                .filter(definition -> definition.key().split() != Split.HOLDOUT
                        && definition.key().split() != Split.INELIGIBLE_DIAGNOSTIC).toList();
    }

    static BIdentificationCatalog publicCatalog() {
        try (InputStream stream = BIdentificationCatalog.class.getResourceAsStream(PUBLIC_INDEX_RESOURCE)) {
            if (stream == null) throw new SecurityException("Validated public HOLDOUT index is absent");
            byte[] index = stream.readNBytes(MAX_INDEX_BYTES + 1);
            if (index.length > MAX_INDEX_BYTES || stream.read() != -1) {
                throw new SecurityException("Public HOLDOUT index exceeds its bound");
            }
            return publicCatalog(index);
        } catch (IOException failure) {
            throw new SecurityException("Public HOLDOUT index is unavailable", failure);
        }
    }

    /** Build a metadata-only catalog from an exact public index. This grants no HOLDOUT value access. */
    static BIdentificationCatalog publicCatalog(byte[] publicIndex) {
        ParsedIndex parsed = parsePublicIndex(publicIndex);
        List<CaseDefinition> rows = registeredRows();
        rows.addAll(parsed.holdoutDefinitions());
        return new BIdentificationCatalog(rows, sha256(publicIndex));
    }

    /** Original TRAIN and fixed regressions for renderer parity tests, with no SELECT or HOLDOUT rows. */
    static BIdentificationCatalog trainDiagnosticCatalog() {
        List<CaseDefinition> rows = registeredRows().stream()
                .filter(d -> d.key().split() == Split.TRAIN || d.key().split() == Split.REGRESSION)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return new BIdentificationCatalog(rows);
    }

    private static List<CaseDefinition> registeredRows() {
        List<CaseDefinition> rows = new ArrayList<>();
        List<String> allContrasts = new ArrayList<>(CONTRASTS);
        allContrasts.add("blank-uniform-invalid-censored");
        for (String contrast : allContrasts) {
            boolean regressionOnly = contrast.equals("blank-uniform-invalid-censored");
            if (regressionOnly) {
                rows.add(regression(contrast, "left", "blank-valid", equation(contrast, "left", Split.REGRESSION)));
                rows.add(regression(contrast, "right", "uniform-valid", equation(contrast, "right", Split.REGRESSION)));
                rows.add(regression(contrast, "invalid", "invalid-gap", equation(contrast, "invalid", Split.REGRESSION)));
                rows.add(regression(contrast, "censored", "censored-edge", equation(contrast, "censored", Split.REGRESSION)));
                continue;
            }
            int contrastIndex = CONTRASTS.indexOf(contrast);
            int originalContrastIndex = ORIGINAL_CONTRAST_ORDER.indexOf(contrast);
            if (originalContrastIndex < 0) throw new IllegalStateException("Original TRAIN contrast is unregistered");
            for (int variant = 0; variant < PHASES.length; variant++) {
                String variantId = "v" + variant;
                for (String side : List.of("left", "right")) {
                    boolean originalReversed = contrast.equals("multiple-physical-pitches-vs-reversed-source-order")
                            ? side.equals("right") : (variant & 1) == 1;
                    rows.add(originalRecipeRow(contrast, side, variantId, variant, originalReversed,
                            Split.TRAIN, equation(contrast, side, Split.TRAIN), originalContrastIndex, 0));
                    rows.add(originalRecipeRow(contrast, side, variantId, variant, originalReversed,
                            Split.INELIGIBLE_DIAGNOSTIC, equation(contrast, side, Split.SELECT), originalContrastIndex, 1));
                    boolean selectReversed = contrast.equals("multiple-physical-pitches-vs-reversed-source-order")
                            && side.equals("right");
                    rows.add(recipeRow(contrast, side, variantId, variant, selectReversed, Split.SELECT,
                            selectEquation(contrast, side), contrastIndex, "select-polynomial-v1"));
                }
            }
            rows.add(regression(contrast, "left", "regression-a", equation(contrast, "left", Split.REGRESSION)));
            rows.add(regression(contrast, "right", "regression-b", equation(contrast, "right", Split.REGRESSION)));
            if (contrast.equals("resolved-short-apex-vs-stair-step-alias")) {
                for (String side : List.of("left","right")) rows.add(regression(contrast,side,
                        "six-metre-apex-"+side,equation(contrast,side,Split.REGRESSION)
                                .replace("/6)","/3)").replace("0.14*","0.80*")
                                + "; supported 6m apex; REGRESSION only"));
            }
        }
        return rows;
    }

    private static CaseDefinition originalRecipeRow(String contrast, String side, String variantId, int variant,
            boolean reversed, Split split, String equation, int originalContrastIndex, int oldSplitOrdinal) {
        String splitPath = oldSplitOrdinal == 0 ? "train" : "select";
        String id = contrast + "/" + splitPath + "/" + variantId + "/" + side;
        CaseKey key = new CaseKey(id, "generator:" + contrast + ":" + side + ":" + splitPath,
                "geometry:" + contrast + ":" + splitPath, "source:" + contrast + ":" + splitPath,
                split, "recipe:" + contrast + ":" + side,
                20261016L + originalContrastIndex * 1000L + oldSplitOrdinal * 100L + variant);
        return new CaseDefinition(key, contrast, side, variantId, PHASES[variant], PITCHES[variant],
                OVERSAMPLING[variant], reversed, equation, originalCapabilities(contrast));
    }

    private static CaseDefinition recipeRow(String contrast, String side, String variantId, int variant,
            boolean reversed, Split split, String equation, int contrastIndex, String cohort) {
        String splitPath = switch (split) {
            case TRAIN -> "train";
            case SELECT -> "select-polynomial-v1";
            case INELIGIBLE_DIAGNOSTIC -> "select";
            default -> throw new IllegalArgumentException("Not a registered calibration split");
        };
        String id = contrast + "/" + splitPath + "/" + variantId + "/" + side;
        String groupSuffix = cohort + ":" + contrast + ":" + side;
        CaseKey key = new CaseKey(id, "generator:" + groupSuffix,
                "geometry:" + cohort + ":" + contrast,
                "source:" + cohort + ":" + contrast, split,
                "recipe:" + cohort + ":" + contrast + ":" + side,
                20261018L + contrastIndex * 1000L + variant);
        return new CaseDefinition(key, contrast, side, variantId, PHASES[variant], PITCHES[variant],
                OVERSAMPLING[variant], reversed, equation, capabilities(contrast, side, split));
    }

    private record ParsedIndex(List<CaseDefinition> holdoutDefinitions) { }

    private static ParsedIndex parsePublicIndex(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_INDEX_BYTES || data[data.length - 1] != '\n') {
            throw new IllegalArgumentException("Public index size or encoding is invalid");
        }
        final Map<String, Object> index;
        try {
            index = ObservationManifestJson.parse(data);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Public index JSON is invalid", failure);
        }
        if (!(canonicalJson(index) + "\n").equals(new String(data, StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Public index is not canonical JSON");
        }
        requireKeys(index, Set.of("schema", "version", "generatorHash", "protocolHash", "split", "payload",
                "groups", "variants", "entries"), "index");
        if (integer(index.get("schema"), "schema") != 1
                || !"sealed-observation-v2".equals(index.get("version"))
                || !"HOLDOUT".equals(index.get("split"))) {
            throw new IllegalArgumentException("Public index protocol is not registered");
        }
        requireHash(index.get("generatorHash"), "generatorHash");
        requireHash(index.get("protocolHash"), "protocolHash");
        Map<String, Object> payload = object(index.get("payload"), "payload");
        requireKeys(payload, Set.of("sha256", "byteCount"), "payload");
        requireHash(payload.get("sha256"), "payload.sha256");
        long payloadBytes = integer(payload.get("byteCount"), "payload.byteCount");
        if (payloadBytes < 0 || payloadBytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Public payload byte count is out of bounds");
        }

        Map<String, Object> groups = object(index.get("groups"), "groups");
        Map<String, Object> variants = object(index.get("variants"), "variants");
        if (!groups.keySet().equals(indexKeys("g", 7)) || !variants.keySet().equals(indexKeys("v", 8))) {
            throw new IllegalArgumentException("Public index group or variant inventory is incomplete");
        }
        Set<String> allGroupIds = new HashSet<>();
        Map<String, Map<String, String>> normalizedGroups = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            String groupKey = "g" + i;
            Map<String, Object> group = object(groups.get(groupKey), "groups." + groupKey);
            requireKeys(group, Set.of("contrast", "generatorGroupId", "geometryGroupId", "sourceGroupId"),
                    "groups." + groupKey);
            Map<String, String> values = new LinkedHashMap<>();
            for (String field : List.of("contrast", "generatorGroupId", "geometryGroupId", "sourceGroupId")) {
                Object value = group.get(field);
                if (!(value instanceof String text) || !text.matches("[A-Za-z0-9:_-]{1,120}")) {
                    throw new IllegalArgumentException("Public group descriptor is malformed");
                }
                values.put(field, text);
            }
            if (!INDEX_CONTRASTS.get(i).equals(values.get("contrast"))) {
                throw new IllegalArgumentException("Public group contrast order is not registered");
            }
            if (!values.get("generatorGroupId").equals("b-observation-holdout-v2-generator-" + groupKey)
                    || !values.get("geometryGroupId").equals("b-observation-holdout-v2-geometry-" + groupKey)
                    || !values.get("sourceGroupId").equals("b-observation-holdout-v2-source-" + groupKey)) {
                throw new IllegalArgumentException("Public group identity is not registered");
            }
            for (String field : List.of("generatorGroupId", "geometryGroupId", "sourceGroupId")) {
                if (!allGroupIds.add(values.get(field))) {
                    throw new IllegalArgumentException("Public group identity aliases another group");
                }
            }
            if (new HashSet<>(List.of(values.get("generatorGroupId"), values.get("geometryGroupId"),
                    values.get("sourceGroupId"))).size() != 3) {
                throw new IllegalArgumentException("Public group identity fields alias");
            }
            normalizedGroups.put(groupKey, values);
        }

        for (int i = 0; i < 8; i++) {
            String variantKey = "v" + i;
            Map<String, Object> fields = object(variants.get(variantKey), "variants." + variantKey);
            requireKeys(fields, Set.of("phase", "sourcePitchMeters", "oversampling"), "variants." + variantKey);
            if (Double.doubleToLongBits(number(fields.get("phase"), "variant.phase"))
                    != Double.doubleToLongBits(PHASES[i])
                    || Double.doubleToLongBits(number(fields.get("sourcePitchMeters"), "variant.pitch"))
                    != Double.doubleToLongBits(PITCHES[i])
                    || integer(fields.get("oversampling"), "variant.oversampling") != OVERSAMPLING[i]) {
                throw new IllegalArgumentException("Public physical variant is not registered");
            }
        }

        Object rawEntries = index.get("entries");
        if (!(rawEntries instanceof List<?> entries) || entries.size() != 112) {
            throw new IllegalArgumentException("Public index must contain exactly 112 recipe entries");
        }
        Set<String> ids = new HashSet<>();
        Set<String> recipeHashes = new HashSet<>();
        Set<String> references = new HashSet<>();
        List<CaseDefinition> definitions = new ArrayList<>(112);
        String priorId = "";
        for (Object rawEntry : entries) {
            Map<String, Object> entry = object(rawEntry, "entry");
            requireKeys(entry, Set.of("id", "group", "variant", "side", "recipeHash"), "entry");
            String group = string(entry.get("group"), "entry.group");
            String variant = string(entry.get("variant"), "entry.variant");
            String side = string(entry.get("side"), "entry.side");
            String id = string(entry.get("id"), "entry.id");
            String expectedId = "b-holdout-v2/" + group + "/" + variant + "/" + side;
            String recipeHash = requireHash(entry.get("recipeHash"), "entry.recipeHash");
            String reference = group + "/" + variant + "/" + side;
            if (!groups.containsKey(group) || !variants.containsKey(variant)
                    || !(side.equals("left") || side.equals("right")) || !id.equals(expectedId)
                    || id.compareTo(priorId) <= 0 || !ids.add(id) || !recipeHashes.add(recipeHash)
                    || !references.add(reference)) {
                throw new IllegalArgumentException("Public index entry is invalid or duplicated");
            }
            priorId = id;
            int groupNumber = Integer.parseInt(group.substring(1));
            int variantNumber = Integer.parseInt(variant.substring(1));
            Map<String, String> groupValues = normalizedGroups.get(group);
            CaseKey key = new CaseKey(id, groupValues.get("generatorGroupId"),
                    groupValues.get("geometryGroupId"), groupValues.get("sourceGroupId"), Split.HOLDOUT,
                    recipeHash, 0L);
            Map<String, Object> variantFields = object(variants.get(variant), "variant");
            definitions.add(new CaseDefinition(key, groupValues.get("contrast"), side, variant,
                    number(variantFields.get("phase"), "phase"),
                    number(variantFields.get("sourcePitchMeters"), "sourcePitchMeters"),
                    (int) integer(variantFields.get("oversampling"), "oversampling"), false,
                    "sealed-public-index-only:" + recipeHash,
                    List.of("indexed-recipe-hash", "sealed-payload-unverified")));
        }
        if (references.size() != 112) throw new IllegalArgumentException("Public index entry coverage is incomplete");
        return new ParsedIndex(List.copyOf(definitions));
    }

    private static Set<String> indexKeys(String prefix, int count) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < count; i++) keys.add(prefix + i);
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(name + " must be an object");
        for (Object key : map.keySet()) if (!(key instanceof String)) {
            throw new IllegalArgumentException(name + " has a non-string key");
        }
        return (Map<String, Object>) map;
    }

    private static void requireKeys(Map<String, Object> object, Set<String> expected, String name) {
        if (!object.keySet().equals(expected)) throw new IllegalArgumentException(name + " schema mismatch");
    }

    private static String string(Object value, String name) {
        if (!(value instanceof String text)) throw new IllegalArgumentException(name + " must be a string");
        return text;
    }

    private static String requireHash(Object value, String name) {
        String hash = string(value, name);
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        return hash;
    }

    private static long integer(Object value, String name) {
        if (!(value instanceof Long) && !(value instanceof Integer) && !(value instanceof Short)
                && !(value instanceof Byte)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        Number number = (Number) value;
        double asDouble = number.doubleValue();
        long asLong = number.longValue();
        if (!Double.isFinite(asDouble) || asDouble != asLong) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return asLong;
    }

    private static double number(Object value, String name) {
        if (!(value instanceof Number number) || value instanceof Boolean || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be finite numeric data");
        }
        return number.doubleValue();
    }

    private static String canonicalJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quoteJson(text);
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (!Double.isFinite(d)) throw new IllegalArgumentException("Nonfinite index number");
            if (number instanceof Float || number instanceof Double) return Double.toString(d);
            return number.toString();
        }
        if (value instanceof List<?> list) {
            return "[" + list.stream().map(BIdentificationCatalog::canonicalJson)
                    .collect(java.util.stream.Collectors.joining(",")) + "]";
        }
        if (value instanceof Map<?, ?> map) {
            return "{" + map.entrySet().stream().sorted((a, b) -> ((String) a.getKey()).compareTo((String) b.getKey()))
                    .map(entry -> quoteJson((String) entry.getKey()) + ":" + canonicalJson(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",")) + "}";
        }
        throw new IllegalArgumentException("Unsupported public index JSON value");
    }

    private static String quoteJson(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    List<CaseDefinition> definitions() { return preHoldoutDefinitions; }
    int caseCount() { return allDefinitions.size(); }
    int activeDefinitionCount() { return (int) allDefinitions.stream()
            .filter(d -> d.key().split() != Split.INELIGIBLE_DIAGNOSTIC).count(); }
    List<String> contrastIds() { return CONTRASTS; }

    String publicIndexHash() { return publicIndexHash; }

    String canonicalHash() {
        StringBuilder canonical = new StringBuilder(RENDERER_REVISION).append('\n')
                .append("public-index-sha256=").append(publicIndexHash).append('\n');
        allDefinitions.stream().sorted(java.util.Comparator.comparing(d -> d.key().id())).forEach(definition ->
                canonical.append(recipeHash(definition)).append('\n'));
        return sha256(canonical.toString());
    }

    List<InventoryItem> inventory(EvaluationAccess access) {
        Objects.requireNonNull(access, "access");
        if (access.phase != Phase.CATALOG) {
            throw new SecurityException("Recipe inventory is available only in CATALOG phase");
        }
        return allDefinitions.stream().map(definition -> new InventoryItem(definition.key().id(),
                definition.key().split(), recipeHash(definition))).toList();
    }

    CaseKey keyForMaterialization(String id, EvaluationAccess access) {
        CaseDefinition definition = byId.get(id);
        if (definition == null) throw new SecurityException("Unknown catalog inventory id");
        access.requireKey(definition.key());
        return definition.key();
    }

    static CaseDefinition definition(CaseKey key, EvaluationAccess access) {
        if (access == null) throw new SecurityException("Evaluation access is required for scene recipes");
        access.requireKey(key);
        if (key.split() == Split.HOLDOUT) {
            throw new SecurityException("HOLDOUT recipes remain sealed until qualified Task5 access exists");
        }
        CaseDefinition definition = registeredRows().stream()
                .filter(candidate -> candidate.key().id().equals(key.id())).findFirst().orElse(null);
        if (definition == null || !definition.key().equals(key)) {
            throw new SecurityException("Unknown or modified scene key");
        }
        return definition;
    }

    static String recipeHash(CaseDefinition definition) {
        CaseKey key = definition.key();
        if (key.split() == Split.HOLDOUT) return key.recipeId();
        return sha256(RENDERER_REVISION + "\n" + key.id() + "\n" + key.generatorFamilyId() + "\n"
                + key.geometryGroupId() + "\n" + key.sourceGroupId() + "\n" + key.split() + "\n"
                + key.recipeId() + "\n" + key.seed() + "\n" + definition.contrastId() + "\n"
                + definition.side() + "\n" + definition.variantId() + "\n" + definition.equation() + "\n"
                + definition.phase() + ":" + definition.sourcePitchMeters() + ":"
                + definition.oversampling() + ":" + definition.reversed() + "\n"
                + String.join(",", definition.capabilities()));
    }

    static final class EvaluationAccess {
        private final Phase phase;
        private final String candidateHash;
        private final String manifestHash;
        private int rasterReads;
        private int labelReads;

        private EvaluationAccess(Phase phase, String candidateHash, String manifestHash) {
            this.phase = phase;
            this.candidateHash = candidateHash;
            this.manifestHash = manifestHash;
        }

        static EvaluationAccess catalogView() { return new EvaluationAccess(Phase.CATALOG, "", ""); }
        static EvaluationAccess preselection(Phase phase) {
            if (phase != Phase.EXTRACT && phase != Phase.FIT_SELECT) {
                throw new IllegalArgumentException("Preselection access requires EXTRACT or FIT_SELECT");
            }
            return new EvaluationAccess(phase, "", "");
        }
        static EvaluationAccess holdout(String candidateHash, String manifestHash) {
            throw new SecurityException("Hash strings do not establish validated frozen artifacts");
        }
        static EvaluationAccess holdout(FrozenEvaluationReceipt receipt) {
            if (receipt == null) throw new SecurityException("Validated frozen artifacts are required");
            return new EvaluationAccess(Phase.HOLDOUT,receipt.candidateHash,receipt.manifestHash);
        }

        synchronized void beforeMaterialization(CaseKey key) {
            requireKey(key);
            rasterReads++;
            labelReads++;
        }

        synchronized void requireKey(CaseKey key) {
            boolean permitted = phase == Phase.HOLDOUT
                    ? key.split() == Split.HOLDOUT && !blank(candidateHash) && !blank(manifestHash)
                    : (phase == Phase.EXTRACT || phase == Phase.FIT_SELECT)
                            && (key.split() == Split.TRAIN || key.split() == Split.SELECT
                                    || key.split() == Split.REGRESSION);
            if (!permitted) throw new SecurityException("Scene values are unavailable in this evaluation phase");
        }

        synchronized int rasterReads() { return rasterReads; }
        synchronized int labelReads() { return labelReads; }
        Phase phase() { return phase; }
    }

    /** Task4 may add a factory only when it verifies actual frozen artifacts, not hash syntax.
     * No such deliverable exists in Tasks1/2, so this capability cannot yet be minted. */
    static final class FrozenEvaluationReceipt {
        private final String candidateHash;
        private final String manifestHash;
        private FrozenEvaluationReceipt(String candidateHash,String manifestHash) {
            this.candidateHash=candidateHash;
            this.manifestHash=manifestHash;
        }
    }

    private static CaseDefinition regression(String contrast, String side, String variant, String equation) {
        String id = contrast + "/regression/" + variant;
        CaseKey key = new CaseKey(id, "generator:" + contrast + ":" + side + ":regression",
                "geometry:" + contrast + ":regression", "source:" + contrast + ":regression",
                Split.REGRESSION, "recipe:" + contrast + ":" + side, 20261016L + id.hashCode());
        return new CaseDefinition(key, contrast, side, variant, 0.0, 1.0, 1, false,
                equation, originalCapabilities(contrast));
    }

    static String equation(String contrast, String side, Split split) {
        String splitGeometry = switch (split) {
            case TRAIN -> " geometry-shift=0m;family-shape=0.8*sin(2*pi*x/120);seed-family=train";
            case SELECT, INELIGIBLE_DIAGNOSTIC ->
                    " geometry-shift=2.5m;family-shape=0.8*triangle(x/80);seed-family=select;diagnostic-only";
            case HOLDOUT -> " sealed-public-index-only";
            case REGRESSION -> " geometry-shift=0m;family-shape=0;fixed-regression";
        };
        return switch (contrast) {
            case "weak-coherent-curve-vs-disconnected-flecks" -> (side.equals("left") ? "left" : "right")
                    + ": 0.02 + 0.24*exp(-0.5*((y-(30+2*sin(2*pi*x/80)))/0.9)^2)"
                    + (side.equals("left") ? "; longitudinal=all x"
                            : "; longitudinal=min(1,sum exp(-0.5*((x-{24,60,96})/3)^2))");
            case "concentrated-band-vs-diffuse-band" -> side + ": 0.02 + (0.16/("
                    + (side.equals("left") ? "0.8" : "3.2") + "*sqrt(2*pi)))*exp(-0.5*((y-30)/"
                    + (side.equals("left") ? "0.8" : "3.2") + ")^2); equal integrated cross-section mass";
            case "sparse-crossing-noisy-strands-vs-persistent-parallel" -> side
                    + ": 0.02 + sum_i a_i(x)*exp(-0.5*((y-c_i(x))/0.8)^2);"
                    + (side.equals("left") ? " crossing/noisy c_i=26,30+4*sin(2*pi*x/48),34; valleys vary"
                            : " persistent c_i=26,34; stable valley");
            case "resolved-short-apex-vs-stair-step-alias" -> side
                    + ": 0.02 + 0.14*exp(-0.5*((y-c(x))/0.75)^2);"
                    + (side.equals("left") ? " c=30+3*max(0,1-|x-60|/6) (12m apex)"
                            : " c=30+3*max(0,1-|x-60|/6); same physical apex; native grid phase shifted by half a source pixel");
            case "displaced-strong-corridor-vs-nearby-faint-strand" -> side
                    + ": 0.02 + strong*G(y,strongY,1.1)+faint*G(y,faintY,0.9);"
                    + (side.equals("left") ? " strong=(0.70,39), faint=(0.09,27)"
                            : " strong=(0.55,27), faint=(0.06,34)") + "; full search +/-25m; no source-way preference";
            case "multiple-physical-pitches-vs-reversed-source-order" -> side
                    + ": 0.02 + 0.3*G(y,30+0.04*(x-60),1);"
                    + (side.equals("left") ? " equivalent metric scene, forward source order"
                            : " equivalent metric scene, reversed source order")
                    + "; vary native pitch 0.5/1/1.75m and oversampling 1/2";
            case "palette-semantic-vs-rendered-source" -> side + ": scalar source mapped before analysis;"
                    + " B=round(255*(0.02+0.65*G(y,c,1.2)+0.2*G(y,c+3,2.2))), R=G=0;"
                    + (side.equals("left") ? " scalar=RenderedHeatmapSampler.colorIntensity(R,G,B,blue)"
                            : " scalar=(0.2126R+0.7152G+0.0722B)/255") + "; same source pixels";
            case "blank-uniform-invalid-censored" -> switch (side) {
                case "left" -> "valid blank: intensity=0.02; valid=true";
                case "right" -> "uniform bright: intensity=0.80; valid=true";
                case "invalid" -> "invalid gap: intensity=0.02; valid=false for 48<=x<=72";
                default -> "censored edge: center=5.4m, 0.4m inside the search boundary at y=5m";
            };
            default -> throw new IllegalArgumentException("Unregistered contrast");
        } + splitGeometry;
    }

    private static String selectEquation(String contrast, String side) {
        String field = "F(x)=30+16*t^2*(1-t)^2*(5+7*(2*t-1)-6*(2*t-1)^2), t=x/120; "
                + "K(y,c,s)=StrictMath.exp(-0.5*((y-c)/s)^2); clamp[0,1]; ";
        return switch (contrast) {
            case "weak-coherent-curve-vs-disconnected-flecks" -> field + (side.equals("left")
                    ? ".02+.08*K(y,F(x),.9); truth UNIQUE F"
                    : ".02+.08*K(y,F(x),.9)*max_j W_j(x), j={13,41,74,106}, W=(1-((x-j)/1.6)^2)^3 for |x-j|<1.6 else0; truth UNKNOWN, no target path");
            case "concentrated-band-vs-diffuse-band" -> field + (side.equals("left")
                    ? ".02+.16*K(y,F(x),.65); truth UNIQUE F"
                    : ".02+.032*K(y,F(x),3.25); truth UNIQUE F; equal continuous excess integral");
            case "sparse-crossing-noisy-strands-vs-persistent-parallel" -> field + (side.equals("left")
                    ? ".02+.035*sum(K(y,F(x)+d(t),.28)), d={-2+4t,1.4-.7t,2-4t}; truth AMBIGUOUS three-curve union"
                    : ".02+.44*K(y,F(x)-4,.8)+.44*K(y,F(x)+4,.8); truth AMBIGUOUS two-curve union");
            case "resolved-short-apex-vs-stair-step-alias" -> field + (side.equals("left")
                    ? ".02+.30*K(y,F(x)+4.5*C((x-60)/6),.7), C(u)=(1-u^2)^3 for |u|<1 else0; truth UNIQUE apex curve"
                    : ".02+.14*K(y,pitch*floor(F(x)/pitch+.5),.7); truth UNIQUE underlying F");
            case "displaced-strong-corridor-vs-nearby-faint-strand" -> field + (side.equals("left")
                    ? ".02+.70*K(y,F(x)+14,1.1); truth UNIQUE F+14"
                    : ".02+.70*K(y,F(x)+14,1.1)+.035*K(y,30,.7); truth UNIQUE strong F+14");
            case "multiple-physical-pitches-vs-reversed-source-order" -> field
                    + ".02+.16*K(y,F(x),1.05); truth UNIQUE F; identical native field, source order="
                    + (side.equals("left") ? "forward" : "reversed");
            case "palette-semantic-vs-rendered-source" -> field + ".02+.16*K(y,F(x),1.05); truth UNIQUE F; "
                    + (side.equals("left") ? "known native scalar after palette mapping" : "rendered-only capability");
            default -> throw new IllegalArgumentException("Unregistered fresh SELECT contrast");
        };
    }

    static double selectCurve(double x) {
        double t = x / 120.0;
        double twiceMinusOne = 2.0 * t - 1.0;
        return 30.0 + 16.0 * t * t * (1.0 - t) * (1.0 - t)
                * (5.0 + 7.0 * twiceMinusOne - 6.0 * twiceMinusOne * twiceMinusOne);
    }

    static String equation(String contrast, String side) {
        return equation(contrast, side, Split.REGRESSION);
    }

    private static List<String> capabilities(String contrast, String side, Split split) {
        boolean renderedOnlyProxy = split == Split.SELECT
                && contrast.equals("palette-semantic-vs-rendered-source") && side.equals("right");
        List<String> common = new ArrayList<>(List.of("analytic-scalar", "certified-metric-frame"));
        common.add(renderedOnlyProxy ? "rendered-only-resolution" : "typed-native-resolution");
        if (contrast.equals("palette-semantic-vs-rendered-source")) {
            common.add("synthetic-source-capability-proxy");
            if (!renderedOnlyProxy) common.add("source-palette-metadata");
        } else {
            common.add("source-palette-metadata");
        }
        if (split == Split.SELECT && contrast.equals("weak-coherent-curve-vs-disconnected-flecks")
                && side.equals("right")) {
            common.add("structure-unknown");
            common.add("location-target-unavailable");
        }
        if (contrast.equals("sparse-crossing-noisy-strands-vs-persistent-parallel")) {
            common.add("multiple-acceptable-branches");
        }
        return List.copyOf(common);
    }

    private static List<String> originalCapabilities(String contrast) {
        List<String> common = new ArrayList<>(List.of("analytic-scalar", "certified-metric-frame",
                "typed-native-resolution", "source-palette-metadata"));
        if (contrast.equals("palette-semantic-vs-rendered-source")) common.add("synthetic-rgb-proxy");
        if (contrast.equals("sparse-crossing-noisy-strands-vs-persistent-parallel")) {
            common.add("multiple-acceptable-branches");
        }
        return List.copyOf(common);
    }

    private static void checkGroupSplit(Map<String, Split> groups, String kind, String group, Split split) {
        Split previous = groups.putIfAbsent(kind + ":" + group, split);
        if (previous != null && previous != split) {
            throw new IllegalArgumentException("Grouped scene aliases cannot cross splits");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
