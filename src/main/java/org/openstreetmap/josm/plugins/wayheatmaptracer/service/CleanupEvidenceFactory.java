package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalShapeEvidence;

/** Builds compact shared and candidate-specific evidence for optional geometry cleanup. */
final class CleanupEvidenceFactory {
    private static final long PROFILE_FIXED_BYTES = 160L;
    private static final long SAMPLE_BYTES = 33L;

    private CleanupEvidenceFactory() {
        // Utility class.
    }

    /**
     * Builds one detector-level scalar frame without retaining image rasters or access data.
     *
     * @param detectorMode detector or scalar mapping identifier
     * @param profiles extracted fine-level corridor profiles
     * @param sourcePixelPitchRasterPx source-pixel pitch in sampled-raster pixels
     * @param groundMetersPerRasterPixel factual slide-time ground metres per sampled-raster pixel
     * @return bounded immutable shared frame, or an empty frame when the cap would be exceeded
     */
    static CleanupSamplingFrame samplingFrame(
        String detectorMode,
        List<CorridorProfile> profiles,
        double sourcePixelPitchRasterPx,
        double groundMetersPerRasterPixel
    ) {
        double retainedSourcePixelPitch = Double.isFinite(sourcePixelPitchRasterPx)
            && sourcePixelPitchRasterPx > 0.0 ? sourcePixelPitchRasterPx : 1.0;
        long estimatedBytes = profiles.stream().mapToLong(profile -> PROFILE_FIXED_BYTES
            + SAMPLE_BYTES * profile.source().intensitySamples().size()).sum();
        if (estimatedBytes > CleanupSamplingFrame.MAX_ESTIMATED_BYTES) {
            return new CleanupSamplingFrame(detectorMode, List.of(), groundMetersPerRasterPixel);
        }
        List<CleanupSamplingProfile> retained = new ArrayList<>(profiles.size());
        for (CorridorProfile profile : profiles) {
            List<RenderedHeatmapSampler.IntensitySample> samples = profile.source().intensitySamples();
            int count = samples.size();
            double[] offsets = new double[count];
            double[] nativeIntensity = new double[count];
            double[] lightIntensity = new double[count];
            double[] standardIntensity = new double[count];
            boolean[] valid = new boolean[count];
            for (int index = 0; index < count; index++) {
                RenderedHeatmapSampler.IntensitySample sample = samples.get(index);
                offsets[index] = sample.offsetPx();
                nativeIntensity[index] = sample.nativeIntensity();
                lightIntensity[index] = sample.lightFilteredIntensity();
                standardIntensity[index] = sample.standardFilteredIntensity();
                valid[index] = sample.insideRaster();
            }
            retained.add(new CleanupSamplingProfile(
                profile.index(),
                profile.source().cumulativeGroundDistanceMeters(),
                profile.source().anchorWithinRaster(),
                retainedSourcePixelPitch,
                profile.source().projectedLateralTransform().orElse(null),
                offsets,
                nativeIntensity,
                lightIntensity,
                standardIntensity,
                valid));
        }
        return new CleanupSamplingFrame(detectorMode, retained, groundMetersPerRasterPixel);
    }

    /**
     * Builds rows for one selected corridor and validates cleanup eligibility.
     *
     * @param frame shared detector sampling frame
     * @param track selected elementary or sparse-parent corridor
     * @param tube profile-aligned robust corridor tube
     * @param completeLongitudinalEvidence whether coverage permits an applicable candidate
     * @param expectedProfileCount fine-level profile count before a possible memory-cap fallback
     * @return immutable candidate-specific evidence with a typed eligibility status
     */
    static CandidateCleanupEvidence candidateEvidence(
        CleanupSamplingFrame frame,
        CorridorTrack track,
        LongitudinalCorridorTube tube,
        boolean completeLongitudinalEvidence,
        int expectedProfileCount
    ) {
        if (frame.profiles().isEmpty() && expectedProfileCount > 0) {
            return CandidateCleanupEvidence.skipped(
                frame, List.of(), CleanupEvidenceStatus.MEMORY_LIMIT_EXCEEDED);
        }
        List<CandidateCleanupProfile> rows = new ArrayList<>(tube.slices().size());
        double sourcePixel = frame.profiles().isEmpty() ? 1.0
            : frame.profiles().get(0).sourcePixelPitchRasterPx();
        List<LocalShapeEvidence> shapeEvidence = new LocalShapeEvidenceEvaluator()
            .evaluate(track, tube, sourcePixel);
        for (CorridorTubeSlice slice : tube.slices()) {
            CorridorTrackPoint point = track.points().get(slice.profileIndex());
            CleanupEvidenceProvenance provenance = point == null
                ? CleanupEvidenceProvenance.UNSUPPORTED
                : point.support() == CorridorPointSupport.DIRECT_UNION
                    ? CleanupEvidenceProvenance.DIRECT
                    : CleanupEvidenceProvenance.BOUNDED_INTERPOLATION;
            boolean supported = provenance != CleanupEvidenceProvenance.UNSUPPORTED && slice.hasIntervals();
            double turnSupport = slice.motionSupportReason().startsWith("supported-apex")
                ? slice.motionSupport() : 0.0;
            double authorizedMotionSupport = provenance == CleanupEvidenceProvenance.DIRECT
                ? slice.motionSupport() : 0.0;
            double authorizedTurnSupport = provenance == CleanupEvidenceProvenance.DIRECT
                ? turnSupport : 0.0;
            LocalShapeEvidence shape = shapeEvidence.get(slice.profileIndex());
            rows.add(new CandidateCleanupProfile(
                slice.profileIndex(),
                supported ? slice.coreMinPx() : Double.NaN,
                supported ? slice.coreMaxPx() : Double.NaN,
                supported ? slice.shoulderMinPx() : Double.NaN,
                supported ? slice.shoulderMaxPx() : Double.NaN,
                slice.centerOffsetPx(),
                slice.uncertaintyPx(),
                provenance,
                authorizedMotionSupport,
                authorizedTurnSupport,
                slice.scaleConflict(),
                provenance == CleanupEvidenceProvenance.DIRECT ? shape.cleanupIntervention() : 0.0,
                provenance == CleanupEvidenceProvenance.DIRECT ? shape.bendProtection() : 0.0,
                shape.ambiguityScore()));
        }
        if (!completeLongitudinalEvidence) {
            return CandidateCleanupEvidence.skipped(
                frame, rows, CleanupEvidenceStatus.INCOMPLETE_LONGITUDINAL_EVIDENCE);
        }
        return CandidateCleanupEvidence.validated(frame, rows);
    }

    /**
     * Checks the complete per-detector retained-evidence budget before candidate rows are allocated.
     *
     * @param frame shared detector frame
     * @param candidateCount possible candidate-specific row sets
     * @param profileCount rows in each candidate set
     * @return true when shared and candidate-specific estimates fit the named cap
     */
    static boolean withinRetentionBudget(
        CleanupSamplingFrame frame,
        int candidateCount,
        int profileCount
    ) {
        if (candidateCount < 0 || profileCount < 0) {
            throw new IllegalArgumentException("Cleanup evidence counts must be non-negative");
        }
        long candidateBytes;
        try {
            candidateBytes = Math.multiplyExact(120L, Math.multiplyExact((long) candidateCount, profileCount));
        } catch (ArithmeticException ex) {
            return false;
        }
        return frame.estimatedBytes() <= CleanupSamplingFrame.MAX_ESTIMATED_BYTES - candidateBytes;
    }
}
