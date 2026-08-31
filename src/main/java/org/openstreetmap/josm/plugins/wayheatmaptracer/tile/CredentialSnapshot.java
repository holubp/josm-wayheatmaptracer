package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;

/** Short-lived credential container whose string representation is always redacted. */
public final class CredentialSnapshot {
    private final String cookieHeader;

    private CredentialSnapshot(String cookieHeader) {
        this.cookieHeader = cookieHeader == null ? "" : cookieHeader;
    }

    static CredentialSnapshot forTesting(String cookieHeader) {
        return new CredentialSnapshot(cookieHeader);
    }

    /**
     * Creates a credential snapshot from current managed settings.
     *
     * @param config managed settings containing access values
     * @return short-lived redacted credential container
     */
    public static CredentialSnapshot fromConfig(ManagedHeatmapConfig config) {
        return new CredentialSnapshot(config == null ? "" : config.toCookieHeader());
    }

    String cookieHeader() {
        return cookieHeader;
    }

    @Override
    public String toString() {
        return "CredentialSnapshot[REDACTED]";
    }
}
