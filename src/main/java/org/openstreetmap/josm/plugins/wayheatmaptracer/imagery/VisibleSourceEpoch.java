package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

/** Tracks publication of pixels and display settings in one plugin-owned visible layer. */
public final class VisibleSourceEpoch {
    private long revision;
    private int pendingLoads;

    /** One stable observation bound to this exact source owner. */
    public record Receipt(VisibleSourceEpoch owner, long revision) { }

    /** Marks the start of a tile job before it can publish cache or tile pixels. */
    public synchronized void beginLoad() {
        revision = Math.incrementExact(revision);
        pendingLoads = Math.incrementExact(pendingLoads);
    }

    /** Marks completion after the loader and layer have published their tile state. */
    public synchronized void finishLoad() {
        if (pendingLoads <= 0) {
            throw new IllegalStateException("Visible tile load was not registered");
        }
        revision = Math.incrementExact(revision);
        pendingLoads--;
    }

    /** Invalidates receipts for a cache, filter, or display change. */
    public synchronized void sourceChanged() {
        revision = Math.incrementExact(revision);
    }

    /** Captures a source revision only when no tile publication is in flight. */
    public synchronized Receipt captureStable() {
        if (pendingLoads != 0) {
            throw new IllegalStateException("Visible heatmap tiles are still loading");
        }
        return new Receipt(this, revision);
    }

    /** Rejects a changed owner, pending tile load, or changed source revision. */
    public synchronized void requireCurrent(Receipt receipt) {
        if (receipt == null || receipt.owner() != this || pendingLoads != 0
                || receipt.revision() != revision) {
            throw new IllegalStateException("Visible heatmap source changed after capture");
        }
    }
}
