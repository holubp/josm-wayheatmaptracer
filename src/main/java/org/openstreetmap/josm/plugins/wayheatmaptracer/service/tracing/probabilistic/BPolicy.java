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
    EXACT_DEFAULT, ADAPTIVE_MULTIMODAL_V1;

    /** Retained-graph posterior, fixed retention rules and one cumulative-budget refinement. */
    public static final String VERSION = "adaptive-multimodal-retained-density-v1/"
            + "cached-pair-exact-scored-work-v3/one-all-rival-refinement-v1";

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
    private static String adaptiveParameterHash(String original) {
        return hash("live-probabilistic-adaptive-v1", original, VERSION);
    }
    public static TraceRequest adaptiveRequest(TraceRequest r) {
        if (r == null || r.engine() != TrackerMode.PROBABILISTIC
                || !r.parameterHash().equals(exactParameterHash()) && !r.parameterHash().equals(visibleExactParameterHash()))
            throw new IllegalArgumentException("Adaptive B requires the known original parameter policy");
        return new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(), r.geometryMode(),
                r.permissions(), r.budgets(), r.evidenceSnapshotId(), r.evidenceContentHash(),
                r.networkSnapshotId(), r.networkContentHash(), r.settingsHash(), adaptiveParameterHash(r.parameterHash()),
                r.samplerId(), r.configuredSampleStepMeters(), r.profileChainage(), r.evidenceResolution(),
                r.corridorInput(), r.disconnectionSource());
    }
    public static BPolicy recorded(TraceRequest source) {
        if (source == null) throw new IllegalArgumentException("Frozen request is required");
        return source.engine() == TrackerMode.PROBABILISTIC && isAdaptiveHash(source.parameterHash())
                ? ADAPTIVE_MULTIMODAL_V1 : EXACT_DEFAULT;
    }
    public static boolean isAdaptiveHash(String hash) {
        return adaptiveParameterHash().equals(hash)
                || adaptiveParameterHash(visibleExactParameterHash()).equals(hash);
    }
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
