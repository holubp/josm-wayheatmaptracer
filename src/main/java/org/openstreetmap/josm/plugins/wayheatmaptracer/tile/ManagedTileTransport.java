package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

/** Encapsulates the only credentialed plugin-direct network boundary. */
public interface ManagedTileTransport {
    /**
     * Executes one bounded request without logging credentials or response bodies.
     *
     * @param request immutable request identity and policy
     * @param credentials short-lived credential snapshot
     * @return controlled bounded response
     */
    TransportResponse execute(TileRequest request, CredentialSnapshot credentials);
}
