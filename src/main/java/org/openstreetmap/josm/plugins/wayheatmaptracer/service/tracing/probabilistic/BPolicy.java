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
    EXACT_DEFAULT, ADAPTIVE_MULTIMODAL_V1, ADAPTIVE_MULTIMODAL_V2;

    /** Retained-graph posterior, fixed retention rules and one cumulative-budget refinement. */
    public static final String VERSION = "adaptive-multimodal-retained-density-v1/"
            + "cached-pair-exact-scored-work-v3/one-all-rival-refinement-v1";
    /** Same retained graphs and objective, with exact local predecessor-stream merging. */
    public static final String CURRENT_VERSION = "adaptive-multimodal-retained-density-v2/"
            + "cached-pair-stream-merge-scored-work-v4/one-all-rival-refinement-v1";

    public String version() {
        return switch(this) {
            case EXACT_DEFAULT -> ProbabilisticExecutionPolicy.CURRENT_VERSION;
            case ADAPTIVE_MULTIMODAL_V1 -> VERSION;
            case ADAPTIVE_MULTIMODAL_V2 -> CURRENT_VERSION;
        };
    }
    ProbabilisticExecutionPolicy.Mode solverMode() {
        return switch(this) {
            case ADAPTIVE_MULTIMODAL_V1 -> ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V3;
            case ADAPTIVE_MULTIMODAL_V2 -> ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4;
            case EXACT_DEFAULT -> throw new IllegalArgumentException("Exact default is not an adaptive policy");
        };
    }

    public static String exactParameterHash() {
        return sourceParameterHash("managed-live-probabilistic-v2");
    }
    public static String visibleExactParameterHash() { return sourceParameterHash("live-probabilistic-v2"); }
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
        return policy==ADAPTIVE_MULTIMODAL_V1 ? adaptiveParameterHash(original)
                : hash("live-probabilistic-adaptive-v2",original,CURRENT_VERSION);
    }
    public static TraceRequest adaptiveRequest(TraceRequest r) {
        return adaptiveRequest(r,ADAPTIVE_MULTIMODAL_V1);
    }
    public static TraceRequest adaptiveRequest(TraceRequest r,BPolicy policy) {
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
        if(source.engine()!=TrackerMode.PROBABILISTIC)return EXACT_DEFAULT;
        String version=adaptiveVersion(source.parameterHash());
        return VERSION.equals(version)?ADAPTIVE_MULTIMODAL_V1:CURRENT_VERSION.equals(version)?ADAPTIVE_MULTIMODAL_V2:EXACT_DEFAULT;
    }
    public static boolean isAdaptiveHash(String hash) { return adaptiveVersion(hash)!=null; }
    public static String adaptiveVersion(String hash) {
        if(adaptiveParameterHash().equals(hash)||adaptiveParameterHash(visibleExactParameterHash()).equals(hash))return VERSION;
        if(adaptiveParameterHash(ADAPTIVE_MULTIMODAL_V2).equals(hash)
                ||adaptiveParameterHash(visibleExactParameterHash(),ADAPTIVE_MULTIMODAL_V2).equals(hash))return CURRENT_VERSION;
        return null;
    }
    static boolean isAdaptiveVersion(String version) { return VERSION.equals(version)||CURRENT_VERSION.equals(version); }
    private static String hash(String... values) {
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
