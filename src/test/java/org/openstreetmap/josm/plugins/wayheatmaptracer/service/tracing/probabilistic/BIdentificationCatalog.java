package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;

/** Frozen public scene inventory for offline B-observation identification. */
final class BIdentificationCatalog {
    enum Split { TRAIN, SELECT, HOLDOUT, REGRESSION }
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
            "weak-coherent-curve-vs-disconnected-flecks",
            "concentrated-band-vs-diffuse-band",
            "sparse-crossing-noisy-strands-vs-persistent-parallel",
            "resolved-short-apex-vs-stair-step-alias",
            "displaced-strong-corridor-vs-nearby-faint-strand",
            "blank-uniform-invalid-censored",
            "multiple-physical-pitches-vs-reversed-source-order",
            "palette-semantic-vs-rendered-source");
    static final String RENDERER_REVISION = "native-observations-production-resample-v5";
    private static final double[] PHASES = {0.0, 0.25, 0.5, 0.75, 0.25, 0.75, 0.0, 0.5};
    private static final double[] PITCHES = {0.5, 0.5, 1.0, 1.0, 1.75, 1.75, 0.5, 1.75};
    private static final int[] OVERSAMPLING = {1, 2, 1, 2, 1, 2, 2, 1};

    private final List<CaseDefinition> allDefinitions;
    private final List<CaseDefinition> preHoldoutDefinitions;
    private final Map<String, CaseDefinition> byId;

    BIdentificationCatalog(List<CaseDefinition> definitions) {
        if (definitions == null || definitions.isEmpty() || definitions.size() > 512) {
            throw new IllegalArgumentException("Catalog must contain 1 to 512 scenes");
        }
        this.allDefinitions = List.copyOf(definitions);
        Map<String, CaseDefinition> index = new LinkedHashMap<>();
        Map<String, Split> groupSplits = new HashMap<>();
        for (CaseDefinition definition : this.allDefinitions) {
            if (index.putIfAbsent(definition.key().id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate scene id");
            }
            checkGroupSplit(groupSplits, "generator", definition.key().generatorFamilyId(), definition.key().split());
            checkGroupSplit(groupSplits, "geometry", definition.key().geometryGroupId(), definition.key().split());
            checkGroupSplit(groupSplits, "source", definition.key().sourceGroupId(), definition.key().split());
        }
        byId = Map.copyOf(index);
        preHoldoutDefinitions = this.allDefinitions.stream()
                .filter(definition -> definition.key().split() != Split.HOLDOUT).toList();
    }

    static BIdentificationCatalog publicCatalog() {
        List<CaseDefinition> rows = new ArrayList<>();
        for (int contrastIndex = 0; contrastIndex < CONTRASTS.size(); contrastIndex++) {
            String contrast = CONTRASTS.get(contrastIndex);
            if (contrastIndex == 5) {
                rows.add(regression(contrast, "left", "blank-valid", equation(contrast, "left", Split.REGRESSION)));
                rows.add(regression(contrast, "right", "uniform-valid", equation(contrast, "right", Split.REGRESSION)));
                rows.add(regression(contrast, "invalid", "invalid-gap", equation(contrast, "invalid", Split.REGRESSION)));
                rows.add(regression(contrast, "censored", "censored-edge", equation(contrast, "censored", Split.REGRESSION)));
                continue;
            }
            for (Split split : List.of(Split.TRAIN, Split.SELECT, Split.HOLDOUT)) {
                for (int variant = 0; variant < PHASES.length; variant++) {
                    String variantId = "v" + variant;
                    for (String side : List.of("left", "right")) {
                        boolean reversed = contrast.equals("multiple-physical-pitches-vs-reversed-source-order")
                                ? side.equals("right") : (variant & 1) == 1;
                        String id = contrast + "/" + split.name().toLowerCase() + "/" + variantId + "/" + side;
                        String family = "generator:" + contrast + ":" + side + ":" + split.name().toLowerCase();
                        String geometry = "geometry:" + contrast + ":" + split.name().toLowerCase();
                        String source = "source:" + contrast + ":" + split.name().toLowerCase();
                        CaseKey key = new CaseKey(id, family, geometry, source, split,
                                "recipe:" + contrast + ":" + side, 20261016L + contrastIndex * 1000L
                                        + split.ordinal() * 100L + variant);
                        rows.add(new CaseDefinition(key, contrast, side, variantId, PHASES[variant],
                                PITCHES[variant], OVERSAMPLING[variant], reversed,
                                equation(contrast, side, split), capabilities(contrast)));
                    }
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
        return new BIdentificationCatalog(rows);
    }

    List<CaseDefinition> definitions() { return preHoldoutDefinitions; }
    int caseCount() { return allDefinitions.size(); }
    List<String> contrastIds() { return CONTRASTS; }

    String canonicalHash() {
        StringBuilder canonical = new StringBuilder(RENDERER_REVISION).append('\n');
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
        CaseDefinition definition = publicCatalog().byId.get(key.id());
        if (definition == null || !definition.key().equals(key)) {
            throw new SecurityException("Unknown or modified scene key");
        }
        return definition;
    }

    static String recipeHash(CaseDefinition definition) {
        CaseKey key = definition.key();
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
                            && key.split() != Split.HOLDOUT;
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
                equation, capabilities(contrast));
    }

    static String equation(String contrast, String side, Split split) {
        String splitGeometry = switch (split) {
            case TRAIN -> " geometry-shift=0m;family-shape=0.8*sin(2*pi*x/120);seed-family=train";
            case SELECT -> " geometry-shift=2.5m;family-shape=0.8*triangle(x/80);seed-family=select";
            case HOLDOUT -> " geometry-shift=-2.5m;family-shape=0.00045*(x-60)^2-0.75;seed-family=holdout";
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

    static String equation(String contrast, String side) {
        return equation(contrast, side, Split.REGRESSION);
    }

    private static List<String> capabilities(String contrast) {
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

    private static boolean sha256Hash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

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
}
