package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Arrays;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Immutable detector-level scalar evidence for one sampled cross-section.
 *
 * <p>Primitive arrays keep the retained cleanup working set compact. Every input array is copied,
 * and values are exposed only through indexed accessors.</p>
 */
public final class CleanupSamplingProfile {
    private final int profileIndex;
    private final double cumulativeGroundDistanceMeters;
    private final boolean anchorWithinRaster;
    private final double sourcePixelPitchRasterPx;
    private final ProjectedLateralTransform projectedLateralTransform;
    private final double[] offsetsPx;
    private final double[] nativeIntensity;
    private final double[] lightFilteredIntensity;
    private final double[] standardFilteredIntensity;
    private final boolean[] insideRaster;

    /**
     * Creates one retained scalar profile.
     *
     * @param profileIndex zero-based profile index
     * @param cumulativeGroundDistanceMeters monotonic chainage in metres
     * @param anchorWithinRaster whether the profile anchor was inside the sampled raster
     * @param sourcePixelPitchRasterPx native source-pixel pitch in sampled-raster pixels
     * @param projectedLateralTransform slide-time offset transform, or {@code null} when unavailable
     * @param offsetsPx sampled lateral offsets in raster pixels
     * @param nativeIntensity native scalar intensity values
     * @param lightFilteredIntensity B3 scalar intensity values
     * @param standardFilteredIntensity B5 scalar intensity values
     * @param insideRaster validity mask shared by all intensity arrays
     */
    public CleanupSamplingProfile(
        int profileIndex,
        double cumulativeGroundDistanceMeters,
        boolean anchorWithinRaster,
        double sourcePixelPitchRasterPx,
        ProjectedLateralTransform projectedLateralTransform,
        double[] offsetsPx,
        double[] nativeIntensity,
        double[] lightFilteredIntensity,
        double[] standardFilteredIntensity,
        boolean[] insideRaster
    ) {
        int sampleCount = offsetsPx == null ? -1 : offsetsPx.length;
        if (profileIndex < 0 || !Double.isFinite(cumulativeGroundDistanceMeters)
            || cumulativeGroundDistanceMeters < 0.0 || !Double.isFinite(sourcePixelPitchRasterPx)
            || sourcePixelPitchRasterPx <= 0.0 || sampleCount < 0 || nativeIntensity == null
            || lightFilteredIntensity == null || standardFilteredIntensity == null || insideRaster == null
            || nativeIntensity.length != sampleCount || lightFilteredIntensity.length != sampleCount
            || standardFilteredIntensity.length != sampleCount || insideRaster.length != sampleCount) {
            throw new IllegalArgumentException("Cleanup sampling profile fields must be aligned and finite");
        }
        for (int index = 0; index < sampleCount; index++) {
            if (!Double.isFinite(offsetsPx[index]) || !Double.isFinite(nativeIntensity[index])
                || !Double.isFinite(lightFilteredIntensity[index])
                || !Double.isFinite(standardFilteredIntensity[index])) {
                throw new IllegalArgumentException("Cleanup scalar samples must be finite");
            }
        }
        this.profileIndex = profileIndex;
        this.cumulativeGroundDistanceMeters = cumulativeGroundDistanceMeters;
        this.anchorWithinRaster = anchorWithinRaster;
        this.sourcePixelPitchRasterPx = sourcePixelPitchRasterPx;
        this.projectedLateralTransform = projectedLateralTransform;
        this.offsetsPx = Arrays.copyOf(offsetsPx, sampleCount);
        this.nativeIntensity = Arrays.copyOf(nativeIntensity, sampleCount);
        this.lightFilteredIntensity = Arrays.copyOf(lightFilteredIntensity, sampleCount);
        this.standardFilteredIntensity = Arrays.copyOf(standardFilteredIntensity, sampleCount);
        this.insideRaster = Arrays.copyOf(insideRaster, sampleCount);
    }

    /**
     * Returns the zero-based profile index.
     *
     * @return profile index
     */
    public int profileIndex() { return profileIndex; }

    /**
     * Returns monotonic physical chainage.
     *
     * @return chainage in ground metres
     */
    public double cumulativeGroundDistanceMeters() { return cumulativeGroundDistanceMeters; }

    /**
     * Reports source-anchor raster validity.
     *
     * @return true when the anchor was inside the raster
     */
    public boolean anchorWithinRaster() { return anchorWithinRaster; }

    /**
     * Returns native source-pixel pitch.
     *
     * @return pitch in sampled-raster pixels
     */
    public double sourcePixelPitchRasterPx() { return sourcePixelPitchRasterPx; }

    /**
     * Returns the retained slide-time transform.
     *
     * @return transform, or {@code null} when unavailable
     */
    public ProjectedLateralTransform projectedLateralTransform() { return projectedLateralTransform; }

    /**
     * Returns the number of retained lateral samples.
     *
     * @return sample count
     */
    public int sampleCount() { return offsetsPx.length; }

    /**
     * Returns one lateral offset.
     *
     * @param index sample index in {@code [0, sampleCount)}
     * @return sampled-raster offset in pixels
     * @throws IndexOutOfBoundsException when {@code index} is outside the profile
     */
    public double offsetPxAt(int index) { return offsetsPx[index]; }

    /**
     * Returns one native scalar intensity.
     *
     * @param index sample index in {@code [0, sampleCount)}
     * @return native scalar intensity
     * @throws IndexOutOfBoundsException when {@code index} is outside the profile
     */
    public double nativeIntensityAt(int index) { return nativeIntensity[index]; }

    /**
     * Returns one B3-filtered scalar intensity.
     *
     * @param index sample index in {@code [0, sampleCount)}
     * @return B3 scalar intensity
     * @throws IndexOutOfBoundsException when {@code index} is outside the profile
     */
    public double lightFilteredIntensityAt(int index) { return lightFilteredIntensity[index]; }

    /**
     * Returns one B5-filtered scalar intensity.
     *
     * @param index sample index in {@code [0, sampleCount)}
     * @return B5 scalar intensity
     * @throws IndexOutOfBoundsException when {@code index} is outside the profile
     */
    public double standardFilteredIntensityAt(int index) { return standardFilteredIntensity[index]; }

    /**
     * Reports one sample's raster validity.
     *
     * @param index sample index in {@code [0, sampleCount)}
     * @return true for valid raster support
     * @throws IndexOutOfBoundsException when {@code index} is outside the profile
     */
    public boolean insideRasterAt(int index) { return insideRaster[index]; }

    /**
     * Projects an offset using only the retained slide-time transform.
     *
     * @param offsetPx lateral sampled-raster offset
     * @return projected point
     * @throws IllegalStateException when the profile has no retained transform
     */
    public EastNorth projectedPointAtOffset(double offsetPx) {
        if (projectedLateralTransform == null) {
            throw new IllegalStateException("Slide-time projected lateral transform is unavailable");
        }
        return projectedLateralTransform.atOffset(offsetPx);
    }

    /**
     * Returns a conservative retained-memory estimate.
     *
     * @return estimated retained bytes
     */
    public long estimatedBytes() {
        return 160L + 33L * sampleCount();
    }
}
