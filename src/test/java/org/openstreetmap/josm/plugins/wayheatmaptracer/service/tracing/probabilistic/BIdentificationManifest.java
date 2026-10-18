package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.InputStream;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.ObservationManifestJson;

/** Immutable preregistration validator; candidate equations are supplied by reviewed design. */
final class BIdentificationManifest {
    static final String REVIEWED_MANIFEST_SHA256 = "8fb47902e402bded101cd239d52b1f67655616f12ba6d0d7a35918b203a0aa77";
    static final Path REVIEWED_SOURCE_REGISTRY_PATH = Path.of(
            "superpowers/docs/plans/2026-10-18-b-observation-reviewed-source.json");
    private static final int MAX_REGISTRY_BYTES = 65_536;
    private static final int MAX_PUBLIC_INDEX_BYTES = 65_536;
    private static final String PUBLIC_INDEX_RESOURCE = "/b-observation-identification/holdout-index.json";
    record Seal(java.nio.file.Path path, String manifestHash, String sourceHash,
            String catalogHash, BIdentificationManifest manifest, Path registryPath, String registryHash) {
        void verifyUnchanged() throws Exception {
            if(!REVIEWED_SOURCE_REGISTRY_PATH.toAbsolutePath().normalize()
                    .equals(registryPath.toAbsolutePath().normalize()))
                throw new IllegalStateException("Reviewed source registry path changed");
            byte[] manifestBytes=readBoundedNoFollow(path,MAX_REGISTRY_BYTES,"manifest");
            Map<String,String> currentMembers=currentSourceMembers();
            byte[] publicIndexBytes=readPublicIndexBytes();
            BIdentificationCatalog catalog=BIdentificationCatalog.publicCatalog(publicIndexBytes);
            if(!manifestHash.equals(BIdentificationReceipt.hash(manifestBytes))
                    || !sourceHash.equals(BIdentificationManifest.sourceHash(currentMembers))
                    || !catalogHash.equals(catalog.canonicalHash()))
                throw new IllegalStateException("Registered experiment changed before evaluation");
            verifyRegistryUnchanged(registryPath,registryHash);
            validateReviewedSourceRegistry(readBoundedNoFollow(registryPath,MAX_REGISTRY_BYTES,"registry"),
                    currentMembers,manifestBytes,catalog.canonicalHash(),publicIndexBytes,
                    castObject(manifest.fields().get("holdoutPolicy")));
        }
    }
    static Seal register(java.nio.file.Path path,String reviewedSourceHash) throws Exception {
        byte[] bytes=readBoundedNoFollow(path,MAX_REGISTRY_BYTES,"manifest");
        String hash=BIdentificationReceipt.hash(bytes);
        if(!REVIEWED_MANIFEST_SHA256.equals(hash))throw new IllegalArgumentException("Unreviewed family/equations/grid/capability mutation");
        var manifest=read(bytes);
        if(integerValue(manifest.fields().get("schema"),2,2)!=2)
            throw new IllegalArgumentException("Historical schema one is read-only");
        Map<String,String> sourceMembers=currentSourceMembers();
        String source=sourceHash(sourceMembers);
        if(reviewedSourceHash==null||!reviewedSourceHash.matches("[0-9a-f]{64}")
                ||!source.equals(reviewedSourceHash))
            throw new IllegalArgumentException("Caller source hash does not match actual source inventory");
        byte[] publicIndexBytes=readPublicIndexBytes();
        BIdentificationCatalog catalog=BIdentificationCatalog.publicCatalog(publicIndexBytes);
        Map<?,?> holdout=object(manifest.fields().get("holdoutPolicy"));
        if(!PUBLIC_INDEX_RESOURCE.equals(holdout.get("publicIndexResource"))
                ||!catalog.publicIndexHash().equals(holdout.get("publicIndexSha256"))
                ||publicIndexBytes.length!=integerValue(holdout.get("publicIndexBytes"),1,MAX_PUBLIC_INDEX_BYTES))
            throw new IllegalArgumentException("Actual public index does not match the frozen manifest");
        String catalogHash=catalog.canonicalHash();
        if(!catalogHash.equals(manifest.fields().get("catalogHash")))
            throw new IllegalArgumentException("Source or catalog binding changed");
        Path registryPath=REVIEWED_SOURCE_REGISTRY_PATH.toAbsolutePath().normalize();
        byte[] registryBytes=readBoundedNoFollow(registryPath,MAX_REGISTRY_BYTES,"registry");
        ReviewedSourceRegistry registry=validateReviewedSourceRegistry(registryBytes,sourceMembers,bytes,
                catalogHash,publicIndexBytes,castObject(manifest.fields().get("holdoutPolicy")));
        return new Seal(path.toAbsolutePath().normalize(),hash,source,catalogHash,manifest,registryPath,
                registry.registryHash());
    }
    record ReviewedSourceRegistry(String registryHash, String manifestSha256, String sourceSha256,
            String catalogHash, String publicIndexSha256, int publicIndexBytes,
            Map<String,String> sourceMembers, Map<String,Object> holdoutPolicy) {
        ReviewedSourceRegistry {
            sourceMembers=java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sourceMembers));
            @SuppressWarnings("unchecked") Map<String,Object> frozen=(Map<String,Object>)freeze(holdoutPolicy);
            holdoutPolicy=frozen;
        }
    }
    static ReviewedSourceRegistry validateReviewedSourceRegistry(byte[] registryBytes,
            Map<String,String> currentSourceMembers, byte[] currentManifestBytes, String actualCatalogHash,
            byte[] currentPublicIndexBytes, Map<String,Object> expectedHoldoutPolicy) throws Exception {
        if(registryBytes==null||registryBytes.length==0||registryBytes.length>MAX_REGISTRY_BYTES
                ||currentSourceMembers==null||currentManifestBytes==null||currentManifestBytes.length==0
                ||actualCatalogHash==null||!actualCatalogHash.matches("[0-9a-f]{64}")
                ||currentPublicIndexBytes==null||currentPublicIndexBytes.length==0
                ||currentPublicIndexBytes.length>MAX_PUBLIC_INDEX_BYTES||expectedHoldoutPolicy==null)
            throw new IllegalArgumentException("Bounded actual registry bindings are required");
        Map<String,Object> fields=ObservationManifestJson.parse(registryBytes);
        if(!fields.keySet().equals(Set.of("schema","sourceMembers","sourceSha256","manifestSha256",
                "catalogHash","publicIndexSha256","publicIndexBytes","holdoutPolicy")))
            throw new IllegalArgumentException("Unknown or absent reviewed-source registry field");
        if(integerValue(fields.get("schema"),1,1)!=1)
            throw new IllegalArgumentException("Unsupported reviewed-source registry schema");
        Map<String,String> expectedMembers=expectedSourceMembers(currentSourceMembers);
        Map<?,?> registeredMembers=object(fields.get("sourceMembers"));
        if(!registeredMembers.keySet().equals(expectedMembers.keySet()))
            throw new IllegalArgumentException("Reviewed source inventory paths changed");
        for(var entry:expectedMembers.entrySet()) {
            Object registered=registeredMembers.get(entry.getKey());
            if(!(registered instanceof String hash)||!hash.matches("[0-9a-f]{64}")||!entry.getValue().equals(hash))
                throw new IllegalArgumentException("Reviewed source member changed: "+entry.getKey());
        }
        String source=sourceHash(expectedMembers);
        requireHash(fields.get("sourceSha256"),source,"source aggregate");
        requireHash(fields.get("manifestSha256"),BIdentificationReceipt.hash(currentManifestBytes),"manifest");
        requireHash(fields.get("catalogHash"),actualCatalogHash,"catalog");
        String indexHash=BIdentificationReceipt.hash(currentPublicIndexBytes);
        requireHash(fields.get("publicIndexSha256"),indexHash,"public index");
        if(integerValue(fields.get("publicIndexBytes"),1,MAX_PUBLIC_INDEX_BYTES)!=currentPublicIndexBytes.length)
            throw new IllegalArgumentException("Public index byte count changed");
        Map<?,?> registeredHoldout=object(fields.get("holdoutPolicy"));
        if(!registeredHoldout.equals(expectedHoldoutPolicy))
            throw new IllegalArgumentException("Sealed holdout metadata changed");
        byte[] canonical=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
        if(!java.util.Arrays.equals(canonical,registryBytes))
            throw new IllegalArgumentException("Reviewed source registry must be canonical JSON");
        Map<String,Object> frozenFields=new LinkedHashMap<>();
        fields.forEach((key,value)->frozenFields.put(key,freeze(value)));
        return new ReviewedSourceRegistry(BIdentificationReceipt.hash(registryBytes),
                BIdentificationReceipt.hash(currentManifestBytes),source,actualCatalogHash,indexHash,
                currentPublicIndexBytes.length,expectedMembers,castObject(frozenFields.get("holdoutPolicy")));
    }
    static void verifyRegistryUnchanged(Path registryPath,String expectedRegistryHash) throws Exception {
        if(registryPath==null||expectedRegistryHash==null||!expectedRegistryHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Registry seal required");
        byte[] current=readBoundedNoFollow(registryPath,MAX_REGISTRY_BYTES,"registry");
        if(!expectedRegistryHash.equals(BIdentificationReceipt.hash(current)))
            throw new IllegalStateException("Reviewed source registry changed before evaluation");
    }
    private static Map<String,String> currentSourceMembers() throws Exception {
        if(BIdentificationReceipt.SOURCE_FILES.size()!=27
                ||new java.util.HashSet<>(BIdentificationReceipt.SOURCE_FILES).size()!=27)
            throw new IllegalStateException("Reviewed source inventory must contain exactly 27 unique files");
        Map<String,String> result=new TreeMap<>();
        for(String relative:BIdentificationReceipt.SOURCE_FILES) {
            String hash=BIdentificationReceipt.sourceMemberHash(relative);
            if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalStateException("Invalid source member hash");
            result.put(relative,hash);
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    private static Map<String,String> expectedSourceMembers(Map<String,String> members) {
        if(members.size()!=27||new java.util.HashSet<>(BIdentificationReceipt.SOURCE_FILES).size()!=27
                ||!members.keySet().equals(new java.util.HashSet<>(BIdentificationReceipt.SOURCE_FILES)))
            throw new IllegalArgumentException("Exact 27-member reviewed source inventory required");
        Map<String,String> copy=new TreeMap<>();
        for(String member:BIdentificationReceipt.SOURCE_FILES) {
            String hash=members.get(member);
            if(hash==null||!hash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid reviewed source member hash");
            copy.put(member,hash);
        }
        return java.util.Collections.unmodifiableMap(copy);
    }
    private static String sourceHash(Map<String,String> members) {
        return BIdentificationReceipt.hash(BIdentificationReceipt.canonical(members).getBytes(StandardCharsets.UTF_8));
    }
    private static void requireHash(Object value,String expected,String label) {
        if(!(value instanceof String hash)||!hash.matches("[0-9a-f]{64}")||!expected.equals(hash))
            throw new IllegalArgumentException("Reviewed "+label+" binding changed");
    }
    private static byte[] readPublicIndexBytes() throws Exception {
        try(InputStream stream=BIdentificationCatalog.class.getResourceAsStream(PUBLIC_INDEX_RESOURCE)) {
            if(stream==null)throw new SecurityException("Validated public HOLDOUT index is absent");
            byte[] bytes=stream.readNBytes(MAX_PUBLIC_INDEX_BYTES+1);
            if(bytes.length==0||bytes.length>MAX_PUBLIC_INDEX_BYTES||stream.read()!=-1)
                throw new SecurityException("Public HOLDOUT index exceeds its bound");
            return bytes;
        }
    }
    private static byte[] readBoundedNoFollow(Path path,int maximum,String kind) throws Exception {
        if(path==null||maximum<1||java.nio.file.Files.isSymbolicLink(path)
                ||!java.nio.file.Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Unsafe or absent reviewed "+kind);
        try(InputStream stream=java.nio.file.Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=stream.readNBytes(maximum+1);
            if(bytes.length==0||bytes.length>maximum||stream.read()!=-1)
                throw new IllegalArgumentException("Reviewed "+kind+" exceeds its bound");
            return bytes;
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> castObject(Object value) {
        return (Map<String,Object>)value;
    }
    private final Map<String, Object> values;
    private BIdentificationManifest(Map<String, Object> values) { this.values = values; }
    static BIdentificationManifest read(byte[] bytes) throws Exception {
        Map<String, Object> values = ObservationManifestJson.parse(bytes);
        int schema = integerValue(values.get("schema"), 1, 2);
        Set<String> schemaOneFields = Set.of("schema", "familyId", "catalogHash", "featureVersion",
                "features", "equations", "units", "capabilities", "parameterGrid", "maxScenes",
                "maxFitSeconds", "existingAttemptCaps", "acceptanceOwners", "resultDirectory");
        Set<String> schemaTwoFields = Set.of("schema", "familyId", "catalogHash", "featureVersion",
                "features", "equations", "units", "capabilities", "parameterGrid", "maxScenes",
                "maxFitSeconds", "existingAttemptCaps", "acceptanceOwners", "resultDirectory",
                "selectionProtocol", "selectCohort", "rendererRevision", "retiredSelectScope",
                "trainingControlOrder", "holdoutPolicy");
        if (!(schema == 1 ? schemaOneFields : schemaTwoFields).equals(values.keySet()))
            throw new IllegalArgumentException("Unknown or absent manifest field");
        if (schema == 2) validateSchemaTwo(values);
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
    private static void validateSchemaTwo(Map<String,Object> values) {
        if (!"area-presence-core-position-v2".equals(values.get("familyId")))
            throw new IllegalArgumentException("Schema two family identity mismatch");
        if (!"debec23907badfaa467fac91cb0da5f3d8771e357ea1faa6f618d60c852d7557".equals(values.get("catalogHash")))
            throw new IllegalArgumentException("Schema two catalog identity mismatch");
        if (!"train-feasible-top4-score-order-v2".equals(values.get("selectionProtocol"))
                || !"select-polynomial-v1".equals(values.get("selectCohort"))
                || !"native-observations-production-resample-v6-strict-math".equals(values.get("rendererRevision"))
                || !"INELIGIBLE_DIAGNOSTIC_NO_SCORE_NO_PAIRS_NO_COVERAGE".equals(values.get("retiredSelectScope")))
            throw new IllegalArgumentException("Schema two selection policy mismatch");
        Map<?,?> training = object(values.get("trainingControlOrder"));
        if (!training.equals(Map.of(
                "scoreAccumulation", "original-TRAIN-catalog-order",
                "configurationGeometry", "score-G-lambda-B",
                "sceneGeometry", "selector-first-pitch-desc-variant-contrast-side",
                "cutoff", "four-fully-feasible-then-lower-geometry-untested",
                "encounteredFailure", "abort-entire-run-no-candidate-no-clock-reset")))
            throw new IllegalArgumentException("Schema two training control order mismatch");
        Map<?,?> holdout = object(values.get("holdoutPolicy"));
        if (!holdout.keySet().equals(Set.of("kind", "generationId", "publicIndexResource", "publicIndexSha256",
                "publicIndexBytes", "payloadSha256", "payloadBytes", "generatorSha256", "protocolSha256"))
                || !"sealed-unseen-v2".equals(holdout.get("kind"))
                || !"sealed-observation-v2-generation-v2".equals(holdout.get("generationId"))
                || !"/b-observation-identification/holdout-index.json".equals(holdout.get("publicIndexResource"))
                || !"97b134e7c983b698679258f244ee8431696bb34257615ce27675e19c21f6b092".equals(holdout.get("publicIndexSha256"))
                || integerValue(holdout.get("publicIndexBytes"), 19960, 19960) != 19960
                || !"5d6b14c5f35fa6266d05bc11b508d3e0f675ed11b95a5c8b1e533d88f8d5aacd".equals(holdout.get("payloadSha256"))
                || integerValue(holdout.get("payloadBytes"), 65493, 65493) != 65493
                || !"df94890b9d93dd878e845072e9aa956d95cc602f2fefccb1285c61a95ccbd85a".equals(holdout.get("generatorSha256"))
                || !"d78ad0ffd78000a70cd042f21d43f956405b8d334a46960285dd7342412b21f8".equals(holdout.get("protocolSha256")))
            throw new IllegalArgumentException("Schema two sealed holdout policy mismatch");
        if (!List.of("BACKGROUND", "PROMINENCE", "RMS_DESCRIPTOR", "FILTER_DESCRIPTOR", "RAW_CENTER",
                "B3_CENTER", "B5_CENTER", "NATIVE_PITCH", "LEFT_DIRECT_SPAN", "RIGHT_DIRECT_SPAN",
                "SHOULDER_MINIMUM", "SHOULDER_MAXIMUM").equals(values.get("features")))
            throw new IllegalArgumentException("Schema two feature inventory mismatch");

        Map<?,?> equations = object(values.get("equations"));
        if (!equations.keySet().equals(Set.of("joint", "weakLimit", "zeroSupport", "uniform", "censored",
                "anchors", "L0", "score", "selection", "acceptance", "bounds")))
            throw new IllegalArgumentException("Schema two equation inventory mismatch");
        requireEquation(equations, "joint", "L0=1m; c_m=original RAW center; v_m=max_k |c_k-c_m| over available original RAW/B3/B5 centers; h_m=(z_m-a_m)/2+v_m+rho_m/2; N_m=max(original RMS descriptor,original filter descriptor); d_m=(D_L+D_R)/(40m); T_ms=max(0,1-|u_s-c_m|/h_m); V_ms=1 iff u_s is inside the original own shoulder and existing scalar interpolation is available; e_ms=max(0,I_s-b_m-lambda*N_m) when V_ms=1 else 0; E_m=sum_s e_ms*Delta_s/L0; Z_m=sum_s V_ms*T_ms*Delta_s; f_ms=V_ms*T_ms/Z_m iff Z_m>0 and E_m>0; H_m=G*(1+B*d_m)*E_m iff Z_m>0 else 0; A_ms=H_m*f_ms iff H_m>0 else 0; R=1-sum_c q_c; D=1+sum_m H_m; q_0=R/D; q_m=R*H_m/D; p_s=q_0*U_s+sum_m q_m*f_ms+sum_c q_c*f_cs; U_s=1/sum_s Delta_s");
        requireEquation(equations, "zeroSupport", "Missing required original receipts or pitch support, and grouped-parent exclusion, retain their explicit unavailable outcome and zero measured mass before eligible finite-receipt checks. Then zero available own-shoulder cells gives UNAVAILABLE_ADMITTED_SHOULDER_SUPPORT; next Z=0 gives NO_ADMITTED_TENT_SUPPORT; next E=0 gives OBSERVED_ZERO_EXCESS. Each has H=q_m=0 and no normalized measured row. Invalid interpolation or outside own shoulder has V=0. Positive E with Z=0 remains NO_ADMITTED_TENT_SUPPORT; never evaluate 0*undefined.");
        requireEquation(equations, "selection", "Accumulate proper scores for all32 configurations in original TRAIN catalog order. Apply cheap controls, then complete TRAIN geometry in score/G/lambda/B configuration order and selector-first/pitch-descending/variant/contrast/side scene order; a configuration is FEASIBLE only when all original TRAIN cases and required applicable controls finish. Rank only FEASIBLE finite-score configurations; evaluate at most four fully feasible configurations on fresh SELECT after the family, source, catalog and manifest are frozen and the fully feasible shortlist is fixed. Lower geometry after the four-feasible cutoff remains UNTESTED. Any encountered error aborts the entire run with no candidate and no clock reset. Preserve the unchanged baseline comparison within 1e-12; freeze one candidate, run unchanged regressions, then sealed HOLDOUT once. No regression or HOLDOUT reselection.");
        requireEquation(equations, "weakLimit", "For fixed finite receipts and masks, uniformly vanishing e makes E, H and q_m tend to zero; the measured contribution disappears continuously. With all measured fields zero, the remainder is exactly R*U plus unchanged censored contributions. Availability and receipt changes are separate discontinuities.");
        requireEquation(equations, "uniform", "U=1/sum(all admitted cell widths), including invalid cells; configured full plus/minus25m domain and V3 state admission unchanged");
        requireEquation(equations, "censored", "Original effective q_c and original normalized f_c from the one shared baseline producer, exactly unchanged; no posterior reconstruction");
        requireEquation(equations, "anchors", "Conditioned exact anchors have unary=0 and log integration measure=0; no observation grants movement/Apply/endpoint/cleanup authority");
        requireEquation(equations, "L0", "L0=1 metre; unary=-log(L0*p), existing log floor1e-300 only; FB cell measure=width/L0; negative unary valid");
        requireEquation(equations, "score", "Unique curve/profile intersection categorical proper log score -log(p_target*cellWidth); physical chainage weighting, equal scene then geometry/source group then contrast family. Ambiguous labels diagnostic only, no invented probabilities.");
        requireEquation(equations, "acceptance", "Existing physical shape bounds unchanged; matched resampling per-mode mass delta<=0.02, center displacement<=factual source pitch or explicitly sampling-only descriptor, conditional IQR shrink<=10%; missing correspondence fails. Actual production raw+final geometry and unchanged safety required.");
        requireEquation(equations, "bounds", "32configs,512scenes,4096profiles/scene,8M extraction and8M mode-cell work/scene/config,60min FIT_SELECT; <=64MiB live scene+scratch within unchanged attempt ledger; <=2GiB disk cache; no holdout before artifact validation");

        Map<?,?> units = object(values.get("units"));
        if (!units.equals(Map.ofEntries(
                Map.entry("joint", "1/metre"), Map.entry("mass", "dimensionless"),
                Map.entry("scalar", "normalized scalar in [0,1]"), Map.entry("length", "metre"),
                Map.entry("L0", "metre"), Map.entry("Delta", "metre"), Map.entry("c", "metre"),
                Map.entry("a,z,h,rho", "metre"), Map.entry("N", "normalized scalar"),
                Map.entry("d", "dimensionless"), Map.entry("E", "dimensionless"),
                Map.entry("Z", "metre"), Map.entry("G", "inverse normalized scalar"),
                Map.entry("lambda", "dimensionless"), Map.entry("B", "dimensionless"),
                Map.entry("H,q", "dimensionless"), Map.entry("A,f,p,U", "1/metre"))))
            throw new IllegalArgumentException("Schema two units mismatch");
        Map<?,?> capabilities = object(values.get("capabilities"));
        if (!capabilities.equals(Map.of(
                "native", "rho=factual native ground pitch; tagged original RAW plus at least one filtered center and original high-core endpoints required; correlated stage receipts share one physical source lineage",
                "unknownNative", "rho=max actual adjacent physical sample gap in own shoulder; sampling descriptor only, never native uncertainty/independent information; separate empirical phase/resampling gates",
                "groupedParent", "No original elementary receipt means unavailable measured field; no fabricated background/stage centers. Record missing capability and fail any required comparison, never hidden baseline substitution.",
                "connectedCertificate", "Unavailable; diagnosticChord cannot authorize evidence")))
            throw new IllegalArgumentException("Schema two capability inventory mismatch");

        if (integerValue(values.get("maxScenes"), 512, 512) != 512
                || integerValue(values.get("maxFitSeconds"), 3600, 3600) != 3600)
            throw new IllegalArgumentException("Schema two caps changed");
        Map<?,?> owners = object(values.get("acceptanceOwners"));
        if (owners.size() != 20) throw new IllegalArgumentException("Schema two OBS ownership changed");
        String[] expectedOwners = {
                "V022BIdentificationManifestTest and independent numerical review",
                "V022BIdentificationCatalogTest split/source alias isolation",
                "V022BIdentificationManifestTest immutable family and finite grid",
                "V022BIdentificationFactsTest and V022BModeProvenanceTest",
                "facts-v3 typed measurements and original stage receipts",
                "V022BIdentificationFactsTest source correlation/unavailable precision",
                "positive connection certificate unavailable, no kernel consumption",
                "V022BObservationMixtureTest normalization/zero support",
                "V022BCensoredRowReuseTest frozen baseline censored parity",
                "V022BObservationMixtureTest full invalid-cell uniform",
                "V022BObservationMixtureTest L0/anchors/negative unary",
                "identification runner all-config proper-score receipts",
                "runner sealed candidate, manifest and source hashes",
                "all eight catalog contrasts plus fixed physical regressions",
                "production raw/final baseline/candidate comparison and bypass mutation",
                "V022BIdentificationCatalogTest guarded heldout access",
                "production component diagnostics and unavailable capability reporting",
                "owned preallocation/cancellation tests through kernel and production engine",
                "strict bounded runner receipts and deterministic rerun",
                "independent whole-delta audit and completion-matrix reconciliation"};
        for (int i = 1; i <= expectedOwners.length; i++)
            if (!expectedOwners[i - 1].equals(owners.get("OBS-%02d".formatted(i))))
                throw new IllegalArgumentException("Schema two OBS ownership changed");
        if (!"b-observation-identification/results".equals(values.get("resultDirectory")))
            throw new IllegalArgumentException("Schema two result directory changed");

        if (!(values.get("parameterGrid") instanceof List<?> grid) || grid.size() != 32)
            throw new IllegalArgumentException("Schema two requires the exact 32-configuration grid");
        Set<String> seen = new java.util.HashSet<>();
        int index = 0;
        for (int gain : new int[]{1,4,16,64}) for (int lambda : new int[]{1,2}) for (int span : new int[]{0,1,3,7}) {
            Map<?,?> row = object(grid.get(index++));
            if (!row.keySet().equals(Set.of("G", "lambda", "B")))
                throw new IllegalArgumentException("Schema two grid columns changed");
            String key = integerValue(row.get("G"), 1, 64) + ":" + integerValue(row.get("lambda"), 1, 2)
                    + ":" + integerValue(row.get("B"), 0, 7);
            if (!key.equals(gain + ":" + lambda + ":" + span) || !seen.add(key))
                throw new IllegalArgumentException("Schema two grid order, value or uniqueness changed");
        }
    }
    private static void requireEquation(Map<?,?> equations, String name, String expected) {
        if (!expected.equals(equations.get(name))) throw new IllegalArgumentException("Schema two equation changed: " + name);
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
        integerValue(value, minimum, maximum);
    }
    private static int integerValue(Object value,int minimum,int maximum) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue() || number.intValue() < minimum
                || number.intValue() > maximum) throw new IllegalArgumentException("Integer outside budget");
        return number.intValue();
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
