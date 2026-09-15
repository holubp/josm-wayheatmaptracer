package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Complete detached input required for strict offline scalar and final-geometry replay. */
public record FrozenReplayInput(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network, ModernTracePipeline.Options options) {
    /** Rejects mixed-attempt values before they reach a production engine. */
    public FrozenReplayInput {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(options, "options");
        if (!request.evidenceSnapshotId().equals(evidence.snapshotId())
                || !request.evidenceContentHash().equals(evidence.canonicalHash())
                || !request.networkSnapshotId().equals(network.snapshotId())
                || !request.networkContentHash().equals(network.canonicalHash())) {
            throw new IllegalArgumentException("Frozen replay input mixes request and snapshot identities");
        }
    }

    /** Stable identity of all engine-consumed frozen values. */
    public String canonicalHash() {
        return Format15Safety.sha256(FrozenReplayCodec.encode(this));
    }
}
