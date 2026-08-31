package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

/** Controls positive cache reads and writes for one request. */
public enum TileCachePolicy {
    /** Read validated cache entries and write validated network responses. */
    USE_CACHE(true, true),
    /** Skip cache reads but write a validated network response. */
    BYPASS_READ_ALLOW_WRITE(false, true),
    /** Use transport only and retain no positive response. */
    NETWORK_ONLY_NO_WRITE(false, false);

    private final boolean read;
    private final boolean write;

    TileCachePolicy(boolean read, boolean write) {
        this.read = read;
        this.write = write;
    }

    boolean reads() {
        return read;
    }

    boolean writes() {
        return write;
    }
}
