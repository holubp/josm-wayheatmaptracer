package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;

/** Offline provider that exposes only immutable evidence and network snapshots embedded in a bundle. */
public record FrozenReplayProvider(EvidenceSnapshot evidence, NetworkSnapshot network) {
    /** Proves both snapshots are detached before replay can consume them. */
    public FrozenReplayProvider {
        if (evidence == null || network == null) {
            throw new IllegalArgumentException("Frozen replay requires embedded evidence and network snapshots");
        }
        DetachedValueVerifier.verify(java.util.List.of(evidence, network));
    }

    /** Always false: replay cannot contact a tile source or infer credentials. */
    public boolean allowsNetwork() {
        return false;
    }

    /** Rejects any attempt to fill missing pixels during replay. */
    public void requireNoAcquisition(String missingAsset) {
        if (missingAsset != null && !missingAsset.isBlank()) {
            throw new IllegalStateException("Offline replay cannot acquire missing asset: " + missingAsset);
        }
    }
}
