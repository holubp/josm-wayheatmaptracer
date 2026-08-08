package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/**
 * Detector-level scalar sampling frame shared by every candidate from that detector run.
 *
 * @param detectorMode detector or scalar mapping name
 * @param profiles immutable profile-aligned scalar evidence
 * @param groundMetersPerRasterPixel factual slide-time ground scale, or {@link Double#NaN}
 *     for compatibility evidence that cannot support metre-based reduction
 */
public record CleanupSamplingFrame(
    String detectorMode,
    List<CleanupSamplingProfile> profiles,
    double groundMetersPerRasterPixel
) {
    /** Maximum retained cleanup evidence per detector, aligned with the scalar pyramid policy. */
    public static final long MAX_ESTIMATED_BYTES = 128L * 1024L * 1024L;

    /** Makes the profile list immutable and enforces ordering and memory bounds. */
    public CleanupSamplingFrame {
        if (detectorMode == null) {
            throw new IllegalArgumentException("Cleanup detector mode must not be null");
        }
        profiles = List.copyOf(profiles);
        if (!Double.isNaN(groundMetersPerRasterPixel)
            && (!Double.isFinite(groundMetersPerRasterPixel) || groundMetersPerRasterPixel <= 0.0)) {
            throw new IllegalArgumentException(
                "Cleanup ground metres per raster pixel must be positive or unavailable");
        }
        double previousDistance = -1.0;
        for (int index = 0; index < profiles.size(); index++) {
            CleanupSamplingProfile profile = profiles.get(index);
            if (profile.profileIndex() != index
                || profile.cumulativeGroundDistanceMeters() + 1e-9 < previousDistance) {
                throw new IllegalArgumentException("Cleanup sampling profiles must be index- and distance-aligned");
            }
            previousDistance = profile.cumulativeGroundDistanceMeters();
        }
        long bytes = profiles.stream().mapToLong(CleanupSamplingProfile::estimatedBytes).sum();
        if (bytes > MAX_ESTIMATED_BYTES) {
            throw new IllegalArgumentException("Cleanup sampling frame exceeds 128 MiB retained-evidence limit");
        }
    }

    /**
     * Creates compatibility evidence without a proven lateral ground scale.
     *
     * @param detectorMode detector or scalar mapping name
     * @param profiles immutable profile-aligned scalar evidence
     */
    public CleanupSamplingFrame(String detectorMode, List<CleanupSamplingProfile> profiles) {
        this(detectorMode, profiles, Double.NaN);
    }

    /**
     * Returns an empty frame used by legacy candidates.
     *
     * @return empty legacy frame
     */
    public static CleanupSamplingFrame empty() {
        return new CleanupSamplingFrame("", List.of(), Double.NaN);
    }

    /**
     * Reports whether metre-based lateral geometry checks are available.
     *
     * @return true when the slide retained a finite positive ground scale
     */
    public boolean hasGroundScale() {
        return Double.isFinite(groundMetersPerRasterPixel) && groundMetersPerRasterPixel > 0.0;
    }

    /**
     * Estimates retained memory conservatively.
     *
     * @return estimated bytes
     */
    public long estimatedBytes() {
        return profiles.stream().mapToLong(CleanupSamplingProfile::estimatedBytes).sum();
    }
}
