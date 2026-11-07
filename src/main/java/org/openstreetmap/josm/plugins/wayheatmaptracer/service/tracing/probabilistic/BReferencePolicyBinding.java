package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyProfileNormalPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernNumericalPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Reference;

/** Explicit frozen parent capability; the guarded factual child request is never rewritten. */
public final class BReferencePolicyBinding {
    public static final String VERSION="legacy-b-reference-inventory-policy-v1";
    private static final String VISIBLE_OLD=oldParent(false),MANAGED_OLD=oldParent(true);
    private static final String VISIBLE_NEW=bind(VISIBLE_OLD),MANAGED_NEW=bind(MANAGED_OLD);
    private BReferencePolicyBinding() { }
    private static String oldParent(boolean managed) {
        String identity="legacy-then-b-v2/"+LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1.id()+"/"
                +EvidenceModelParameters.defaults().version()+"/"+ProbabilisticTraceEngine.ReliabilityPolicy.DIRECT_LONGITUDINAL_V2
                +"/"+ModernNumericalPolicy.SHARED_COST;
        return BPolicy.hash((managed?"managed-live-":"live-")+"legacy_then_b-v2",identity);
    }
    private static String bind(String original){return BPolicy.hash(VERSION,original,BPolicy.INVENTORY_PREP_VERSION);}
    /** Applies only while capturing a known current visible or managed parent. */
    public static String newParentParameterHash(String knownOldParentHash) {
        if(VISIBLE_OLD.equals(knownOldParentHash))return VISIBLE_NEW;
        if(MANAGED_OLD.equals(knownOldParentHash))return MANAGED_NEW;
        throw new IllegalArgumentException("New Legacy B parent requires its known original source policy");
    }
    public static boolean isNewParentHash(String hash){return VISIBLE_NEW.equals(hash)||MANAGED_NEW.equals(hash);}
    /** Pure identity resolution. Engine authority validation must precede use of this capability. */
    public static BPolicy recordedPolicy(Reference reference) {
        if(reference==null)throw new IllegalArgumentException("A factual reference is required");
        return reference.originalRequest().engine()==TrackerMode.LEGACY_THEN_B
                &&isNewParentHash(reference.originalRequest().parameterHash())?BPolicy.INVENTORY_PREP_V4:BPolicy.EXACT_DEFAULT;
    }
}
