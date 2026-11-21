package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Worker-local logical admission over actual immutable identity inventories, independent of physical leases.
 * The caller admits the small inventory-reference workspace before constructing this view.
 */
final class MarginalPayloadAdmission {
    private final List<Map<Object,Long>> retained;
    private final long allowance;
    private final CancellationProbe cancellation;
    private long used;
    MarginalPayloadAdmission(List<Map<Object,Long>> baseline,long allowance,CancellationProbe cancellation) {
        if(allowance<0)throw new IllegalArgumentException("Negative admission allowance");
        this.allowance=allowance;this.cancellation=Objects.requireNonNull(cancellation);retained=new ArrayList<>(baseline.size()+32);retained.addAll(baseline);
    }
    /** Checks before copying a prospective payload; does not consume the allowance on refusal. */
    long require(Map<Object,Long> prospective,long headerBytes) {
        if(headerBytes<0)throw new IllegalArgumentException("Negative container metadata");
        long additional=RefinementPayload.add(marginal(prospective),headerBytes);
        if(additional>allowance-used)throw new RefinementSession.Refused(RefinementModels.RefusalReason.PAYLOAD_LIMIT);
        return additional;
    }
    /** Commit actual copied identities, never the temporary forecast identities. */
    void admit(Map<Object,Long> actual,long headerBytes,long admittedBound) {
        long additional=require(actual,headerBytes);
        if(additional>admittedBound)throw new IllegalStateException("Copied payload exceeds prospective marginal inventory");
        used=Math.addExact(used,additional);retained.add(actual);
    }
    private long marginal(Map<Object,Long> values) {
        long additional=0;
        for(var entry:values.entrySet()) {
            cancellation.checkpoint();if(entry.getValue()==null||entry.getValue()<0)throw new IllegalArgumentException("Negative identity charge");long prior=0;
            for(var inventory:retained){cancellation.checkpoint();Long previous=inventory.get(entry.getKey());if(previous!=null)prior=Math.max(prior,previous);}
            if(entry.getValue()>prior)additional=RefinementPayload.add(additional,entry.getValue()-prior);
        }
        return additional;
    }
    long used(){return used;}
}
