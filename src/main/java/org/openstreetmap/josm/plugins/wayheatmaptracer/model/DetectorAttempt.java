package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/**
 * Auditable outcome of one requested color-to-intensity mapping.
 *
 * @param sourcePalette configured or managed source palette
 * @param mappingName detector mapping name
 * @param trackerMode tracker implementation used
 * @param status terminal attempt status
 * @param candidateIds produced candidate identifiers
 * @param reasonCode machine-readable reason
 * @param reason user-readable explanation
 */
public record DetectorAttempt(
    String sourcePalette,
    String mappingName,
    TrackerMode trackerMode,
    DetectorAttemptStatus status,
    List<String> candidateIds,
    String reasonCode,
    String reason
) {
    /** Makes identifiers immutable and null-safe. */
    public DetectorAttempt {
        sourcePalette = sourcePalette == null ? "" : sourcePalette;
        mappingName = mappingName == null ? "" : mappingName;
        trackerMode = trackerMode == null ? TrackerMode.LEGACY_V02 : trackerMode;
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        reasonCode = reasonCode == null ? "" : reasonCode;
        reason = reason == null ? "" : reason;
    }

    /**
     * Returns whether candidates from this attempt may be applied.
     *
     * @return true only for the applicable terminal status
     */
    public boolean applicable() {
        return status == DetectorAttemptStatus.APPLICABLE;
    }
}
