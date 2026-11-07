package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernNumericalPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Explicit recorded B solver policy; historical requests retain the existing exact default. */
public enum BPolicy {
    EXACT_DEFAULT, ADAPTIVE_MULTIMODAL_V1, ADAPTIVE_MULTIMODAL_V2, DENSE_ADMITTED_V3, INVENTORY_PREP_V4,
    INVENTORY_FRONTIER_V5, INVENTORY_FRONTIER_BUDGETED_V6;

    /** Retained-graph posterior, fixed retention rules and one cumulative-budget refinement. */
    public static final String VERSION = "adaptive-multimodal-retained-density-v1/"
            + "cached-pair-exact-scored-work-v3/one-all-rival-refinement-v1";
    /** Same retained graphs and objective, with exact local predecessor-stream merging. */
    public static final String CURRENT_VERSION = "adaptive-multimodal-retained-density-v2/"
            + "cached-pair-stream-merge-scored-work-v4/one-all-rival-refinement-v1";
    public static final String DENSE_ADMISSION_VERSION="budget-admitted-dense-or-conditional-v3/"+DenseBAdmission.VERSION;
    public static final String FULL_EXECUTION_VERSION=DENSE_ADMISSION_VERSION+"/full/cached-pair-stream-merge-scored-work-v4";
    public static final String COMPACT_EXECUTION_VERSION=DENSE_ADMISSION_VERSION+"/compact/"+CURRENT_VERSION;
    public static final String INVENTORY_PREP_VERSION=DENSE_ADMISSION_VERSION+"/"+ProbabilisticProfileFactory.OWNED_INVENTORY_VERSION;
    public static final String INVENTORY_FULL_VERSION=INVENTORY_PREP_VERSION+"/full/cached-pair-stream-merge-scored-work-v4";
    public static final String INVENTORY_COMPACT_VERSION=INVENTORY_PREP_VERSION+"/compact/"+CURRENT_VERSION;
    /** New source retention only; all historical policy strings remain immutable. */
    public static final String FRONTIER_RETENTION_VERSION=CURRENT_VERSION+"/source-uncertainty-frontier-v1";
    public static final String INVENTORY_FRONTIER_VERSION=INVENTORY_PREP_VERSION+"/source-uncertainty-frontier-v1";
    public static final String FRONTIER_FULL_VERSION=INVENTORY_FRONTIER_VERSION+"/full/cached-pair-stream-merge-scored-work-v4";
    public static final String FRONTIER_COMPACT_VERSION=INVENTORY_FRONTIER_VERSION+"/compact/"+FRONTIER_RETENTION_VERSION;
    public static final String BUDGETED_FRONTIER_VERSION=INVENTORY_FRONTIER_VERSION+"/proven-unaffordable-refinement-v1";
    public static final String BUDGETED_FRONTIER_FULL_VERSION=BUDGETED_FRONTIER_VERSION+"/full/cached-pair-stream-merge-scored-work-v4";
    public static final String BUDGETED_FRONTIER_COMPACT_VERSION=BUDGETED_FRONTIER_VERSION+"/compact/"+FRONTIER_RETENTION_VERSION;

    public String version() {
        return switch(this) {
            case EXACT_DEFAULT -> ProbabilisticExecutionPolicy.CURRENT_VERSION;
            case ADAPTIVE_MULTIMODAL_V1 -> VERSION;
            case ADAPTIVE_MULTIMODAL_V2 -> CURRENT_VERSION;
            case DENSE_ADMITTED_V3 -> DENSE_ADMISSION_VERSION;
            case INVENTORY_PREP_V4 -> INVENTORY_PREP_VERSION;
            case INVENTORY_FRONTIER_V5 -> INVENTORY_FRONTIER_VERSION;
            case INVENTORY_FRONTIER_BUDGETED_V6 -> BUDGETED_FRONTIER_VERSION;
        };
    }
    ProbabilisticExecutionPolicy.Mode solverMode() {
        return switch(this) {
            case ADAPTIVE_MULTIMODAL_V1 -> ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V3;
            case ADAPTIVE_MULTIMODAL_V2, DENSE_ADMITTED_V3, INVENTORY_PREP_V4, INVENTORY_FRONTIER_V5, INVENTORY_FRONTIER_BUDGETED_V6 -> ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4;
            case EXACT_DEFAULT -> throw new IllegalArgumentException("Exact default is not an adaptive policy");
        };
    }
    public String fullExecutionVersion() {
        if(this==DENSE_ADMITTED_V3)return FULL_EXECUTION_VERSION;
        if(this==INVENTORY_PREP_V4)return INVENTORY_FULL_VERSION;
        if(this==INVENTORY_FRONTIER_V5)return FRONTIER_FULL_VERSION;
        if(this==INVENTORY_FRONTIER_BUDGETED_V6)return BUDGETED_FRONTIER_FULL_VERSION;
        throw new IllegalArgumentException("Dense admission policy required");
    }
    public String compactExecutionVersion() {
        if(this==DENSE_ADMITTED_V3)return COMPACT_EXECUTION_VERSION;
        if(this==INVENTORY_PREP_V4)return INVENTORY_COMPACT_VERSION;
        if(this==INVENTORY_FRONTIER_V5)return FRONTIER_COMPACT_VERSION;
        if(this==INVENTORY_FRONTIER_BUDGETED_V6)return BUDGETED_FRONTIER_COMPACT_VERSION;
        throw new IllegalArgumentException("Dense admission policy required");
    }

    public static String exactParameterHash() {
        return sourceParameterHash("managed-live-probabilistic-v2");
    }
    public static String visibleExactParameterHash() { return sourceParameterHash("live-probabilistic-v2"); }
    /** New capture selection only; frozen requests continue selecting their recorded policy. */
    public static String newCaptureParameterHash(String knownExactHash) {
        return newCaptureParameterHash(knownExactHash, INVENTORY_PREP_V4);
    }
    /** Explicit source-bound opt-in; the ordinary capture default remains historical V4. */
    public static String newCaptureParameterHash(String knownExactHash, BPolicy policy) {
        if(!exactParameterHash().equals(knownExactHash)&&!visibleExactParameterHash().equals(knownExactHash))
            throw new IllegalArgumentException("New B capture requires a known current source domain");
        if(policy!=INVENTORY_PREP_V4&&policy!=INVENTORY_FRONTIER_V5&&policy!=INVENTORY_FRONTIER_BUDGETED_V6)
            throw new IllegalArgumentException("New B capture requires an explicit inventory policy");
        return adaptiveParameterHash(knownExactHash,policy);
    }
    private static String sourceParameterHash(String domain) {
        return hash(domain, EvidenceModelParameters.defaults().version() + "/"
                + ProbabilisticTraceEngine.ReliabilityPolicy.DIRECT_LONGITUDINAL_V2 + "/"
                + ModernNumericalPolicy.SHARED_COST);
    }
    /** Complete named policy identity, selected from frozen values during ordinary replay. */
    public static String adaptiveParameterHash() {
        return hash("live-probabilistic-adaptive-v1", exactParameterHash(), VERSION);
    }
    /** Explicit revision; the original no-argument API retains its captured V1 identity. */
    public static String adaptiveParameterHash(BPolicy policy) { return adaptiveParameterHash(exactParameterHash(),policy); }
    private static String adaptiveParameterHash(String original) {
        return hash("live-probabilistic-adaptive-v1", original, VERSION);
    }
    private static String adaptiveParameterHash(String original,BPolicy policy) {
        if(policy==null||policy==EXACT_DEFAULT) throw new IllegalArgumentException("An explicit adaptive revision is required");
        return switch(policy) {
            case ADAPTIVE_MULTIMODAL_V1 -> adaptiveParameterHash(original);
            case ADAPTIVE_MULTIMODAL_V2 -> hash("live-probabilistic-adaptive-v2",original,CURRENT_VERSION);
            case DENSE_ADMITTED_V3 -> hash("live-probabilistic-dense-admitted-v3",original,DENSE_ADMISSION_VERSION);
            case INVENTORY_PREP_V4 -> hash("live-probabilistic-inventory-prep-v4",original,INVENTORY_PREP_VERSION);
            case INVENTORY_FRONTIER_V5 -> hash("live-probabilistic-inventory-frontier-v5",original,INVENTORY_FRONTIER_VERSION);
            case INVENTORY_FRONTIER_BUDGETED_V6 -> hash("live-probabilistic-inventory-frontier-budgeted-v6",original,BUDGETED_FRONTIER_VERSION);
            default -> throw new IllegalArgumentException("An explicit opt-in revision is required");
        };
    }
    public static TraceRequest adaptiveRequest(TraceRequest r) {
        return adaptiveRequest(r,ADAPTIVE_MULTIMODAL_V1);
    }
    public static TraceRequest adaptiveRequest(TraceRequest r,BPolicy policy) {
        if(policy==INVENTORY_FRONTIER_BUDGETED_V6 && (r==null
                ||r.engine()!=TrackerMode.PROBABILISTIC
                ||r.geometryMode()!=org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE))
            throw new IllegalArgumentException("Budgeted frontier requires direct B Precise Shape");
        if (r == null || r.engine() != TrackerMode.PROBABILISTIC
                || !r.parameterHash().equals(exactParameterHash()) && !r.parameterHash().equals(visibleExactParameterHash()))
            throw new IllegalArgumentException("Adaptive B requires the known original parameter policy");
        return new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(), r.geometryMode(),
                r.permissions(), r.budgets(), r.evidenceSnapshotId(), r.evidenceContentHash(),
                r.networkSnapshotId(), r.networkContentHash(), r.settingsHash(), adaptiveParameterHash(r.parameterHash(),policy),
                r.samplerId(), r.configuredSampleStepMeters(), r.profileChainage(), r.evidenceResolution(),
                r.corridorInput(), r.disconnectionSource());
    }
    public static BPolicy recorded(TraceRequest source) {
        if (source == null) throw new IllegalArgumentException("Frozen request is required");
        if(BUDGETED_FRONTIER_VERSION.equals(adaptiveVersion(source.parameterHash()))) {
            if(source.engine()!=TrackerMode.PROBABILISTIC
                    ||source.geometryMode()!=org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE)
                throw new IllegalArgumentException("Budgeted frontier requires direct B Precise Shape");
            return INVENTORY_FRONTIER_BUDGETED_V6;
        }
        if(source.engine()!=TrackerMode.PROBABILISTIC)return EXACT_DEFAULT;
        String version=adaptiveVersion(source.parameterHash());
        return VERSION.equals(version)?ADAPTIVE_MULTIMODAL_V1:CURRENT_VERSION.equals(version)?ADAPTIVE_MULTIMODAL_V2:
                DENSE_ADMISSION_VERSION.equals(version)?DENSE_ADMITTED_V3:INVENTORY_PREP_VERSION.equals(version)?INVENTORY_PREP_V4:
                INVENTORY_FRONTIER_VERSION.equals(version)?INVENTORY_FRONTIER_V5:EXACT_DEFAULT;
    }
    public static boolean isAdaptiveHash(String hash) { return adaptiveVersion(hash)!=null; }
    public static String adaptiveVersion(String hash) {
        if(adaptiveParameterHash(INVENTORY_FRONTIER_BUDGETED_V6).equals(hash)
                ||adaptiveParameterHash(visibleExactParameterHash(),INVENTORY_FRONTIER_BUDGETED_V6).equals(hash))return BUDGETED_FRONTIER_VERSION;
        if(adaptiveParameterHash().equals(hash)||adaptiveParameterHash(visibleExactParameterHash()).equals(hash))return VERSION;
        if(adaptiveParameterHash(ADAPTIVE_MULTIMODAL_V2).equals(hash)
                ||adaptiveParameterHash(visibleExactParameterHash(),ADAPTIVE_MULTIMODAL_V2).equals(hash))return CURRENT_VERSION;
        if(adaptiveParameterHash(DENSE_ADMITTED_V3).equals(hash)
                ||adaptiveParameterHash(visibleExactParameterHash(),DENSE_ADMITTED_V3).equals(hash))return DENSE_ADMISSION_VERSION;
        if(adaptiveParameterHash(INVENTORY_PREP_V4).equals(hash)
                ||adaptiveParameterHash(visibleExactParameterHash(),INVENTORY_PREP_V4).equals(hash))return INVENTORY_PREP_VERSION;
        if(adaptiveParameterHash(INVENTORY_FRONTIER_V5).equals(hash)
                ||adaptiveParameterHash(visibleExactParameterHash(),INVENTORY_FRONTIER_V5).equals(hash))return INVENTORY_FRONTIER_VERSION;
        return null;
    }
    static boolean isAdaptiveVersion(String version) { return VERSION.equals(version)||CURRENT_VERSION.equals(version)||COMPACT_EXECUTION_VERSION.equals(version)||INVENTORY_COMPACT_VERSION.equals(version)||FRONTIER_RETENTION_VERSION.equals(version)||FRONTIER_COMPACT_VERSION.equals(version)||BUDGETED_FRONTIER_COMPACT_VERSION.equals(version); }
    static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':'); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
