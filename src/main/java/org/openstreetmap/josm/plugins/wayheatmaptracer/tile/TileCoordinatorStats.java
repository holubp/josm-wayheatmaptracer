package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

/**
 * Credential-free bounded coordinator counters for diagnostics and tests.
 *
 * @param transportExecutions direct transport execution count
 * @param memoryHits positive memory-cache hits
 * @param diskHits validated disk-cache hits
 * @param negativeHits negative-cache hits
 * @param singleFlightJoins consumers joined to existing work
 * @param queued currently queued worker tasks
 * @param inFlight currently running worker tasks
 * @param authBlocked whether the active generation has an open authentication circuit
 * @param retrySuppressed requests suppressed by negative or circuit state
 */
public record TileCoordinatorStats(long transportExecutions, long memoryHits, long diskHits,
                                   long negativeHits, long singleFlightJoins, int queued,
                                   int inFlight, boolean authBlocked, long retrySuppressed) {
}
