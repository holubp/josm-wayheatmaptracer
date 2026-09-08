package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

/** Offline replay context exposing only validated local artifacts and no acquisition API. */
public final class Format15ReplayContext {
    private final Format15Archive archive;

    Format15ReplayContext(Format15Archive archive) {
        this.archive = archive;
    }

    /** Returns the immutable archive metadata available to the replay operation. */
    public Format15Archive archive() {
        return archive;
    }

    /** Always false; replay has no network-capable dependency. */
    public boolean allowsNetwork() {
        return false;
    }

    /** Rejects an attempted network acquisition instead of silently filling evidence. */
    public void requireNetwork(String asset) {
        throw new IllegalStateException("Offline replay cannot acquire missing asset");
    }
}
