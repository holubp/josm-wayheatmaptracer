package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupInterval;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupPointDisposition;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalPreviewCleanupContext;

/** Partitions reconciled cleanup evidence into immutable boundaries and local eligible intervals. */
public final class CleanupEligibilityPartitioner {
    private static final double MIN_SIGNAL = 1e-6;
    private static final double MIN_SMOOTHING_SPAN_METERS = 3.0;

    /** Creates a stateless local cleanup eligibility partitioner. */
    public CleanupEligibilityPartitioner() {
    }

    /**
     * Classifies every point and returns deterministic adjacent boundary intervals.
     *
     * @param context globally reconciled final-preview cleanup context
     * @return immutable local eligibility partition
     */
    public Partition partition(FinalPreviewCleanupContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.complete()) {
            throw new IllegalArgumentException("Cleanup eligibility requires a complete context");
        }
        List<CleanupPointDisposition> dispositions = new ArrayList<>(context.geometry().size());
        LinkedHashSet<Integer> immutable = new LinkedHashSet<>();
        for (int index = 0; index < context.geometry().size(); index++) {
            CleanupPointDisposition disposition = disposition(context, index);
            dispositions.add(disposition);
            if (disposition.immutable()) {
                immutable.add(index);
            }
        }
        immutable.add(0);
        immutable.add(context.geometry().size() - 1);
        List<Integer> boundaries = immutable.stream().sorted().toList();
        List<CleanupInterval> intervals = new ArrayList<>();
        for (int boundary = 1; boundary < boundaries.size(); boundary++) {
            int start = boundaries.get(boundary - 1);
            int end = boundaries.get(boundary);
            if (end <= start) {
                continue;
            }
            int directInterior = 0;
            for (int index = start + 1; index < end; index++) {
                if (dispositions.get(index) == CleanupPointDisposition.DIRECT_USABLE) {
                    directInterior++;
                }
            }
            CleanupSamplingProfile startProfile = context.evidence().samplingFrame().profiles().get(start);
            CleanupSamplingProfile endProfile = context.evidence().samplingFrame().profiles().get(end);
            double spanMeters = endProfile.cumulativeGroundDistanceMeters()
                - startProfile.cumulativeGroundDistanceMeters();
            double sourcePixelGroundMeters = averageSourcePixelGroundMeters(context, start, end);
            double requiredSmoothingSpan = Math.max(MIN_SMOOTHING_SPAN_METERS,
                2.0 * sourcePixelGroundMeters);
            intervals.add(new CleanupInterval(start, end,
                startProfile.cumulativeGroundDistanceMeters(), endProfile.cumulativeGroundDistanceMeters(),
                directInterior, directInterior >= 2 && spanMeters > 0.0,
                directInterior >= 3 && spanMeters + 1e-9 >= requiredSmoothingSpan,
                directInterior >= 1 && spanMeters > 0.0,
                List.of(dispositions.get(start).name(), dispositions.get(end).name())));
        }
        return new Partition(dispositions, intervals, immutable);
    }

    private static CleanupPointDisposition disposition(FinalPreviewCleanupContext context, int index) {
        if (context.protectedIndexes().contains(index)) {
            return CleanupPointDisposition.PROTECTED_ANCHOR;
        }
        CandidateCleanupProfile row = context.evidence().profiles().get(index);
        CleanupSamplingProfile sample = context.evidence().samplingFrame().profiles().get(index);
        if (row.provenance() == CleanupEvidenceProvenance.BOUNDED_INTERPOLATION) {
            return CleanupPointDisposition.FROZEN_INTERPOLATED;
        }
        if (row.provenance() != CleanupEvidenceProvenance.DIRECT) {
            return CleanupPointDisposition.FROZEN_UNSUPPORTED;
        }
        if (!sample.anchorWithinRaster()) {
            return CleanupPointDisposition.FROZEN_OFF_RASTER;
        }
        if (!hasSignal(sample)) {
            return CleanupPointDisposition.FROZEN_NO_SIGNAL;
        }
        if (row.scaleConflict()) {
            return CleanupPointDisposition.FROZEN_SCALE_CONFLICT;
        }
        return CleanupPointDisposition.DIRECT_USABLE;
    }

    private static double averageSourcePixelGroundMeters(
        FinalPreviewCleanupContext context,
        int start,
        int end
    ) {
        double groundScale = context.evidence().samplingFrame().groundMetersPerRasterPixel();
        if (!Double.isFinite(groundScale) || groundScale <= 0.0) {
            return 0.0;
        }
        double sum = 0.0;
        int count = 0;
        for (int index = start; index <= end; index++) {
            double pitch = context.evidence().samplingFrame().profiles().get(index)
                .sourcePixelPitchRasterPx();
            if (Double.isFinite(pitch) && pitch > 0.0) {
                sum += pitch * groundScale;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static boolean hasSignal(CleanupSamplingProfile sample) {
        for (int index = 0; index < sample.sampleCount(); index++) {
            if (sample.insideRasterAt(index)
                && Math.max(sample.nativeIntensityAt(index), Math.max(sample.lightFilteredIntensityAt(index),
                    sample.standardFilteredIntensityAt(index))) > MIN_SIGNAL) {
                return true;
            }
        }
        return false;
    }

    /**
     * Complete local eligibility result.
     *
     * @param dispositions one disposition per context point
     * @param intervals adjacent intervals bounded by immutable points
     * @param immutableIndexes all protected and frozen point indexes
     */
    public record Partition(
        List<CleanupPointDisposition> dispositions,
        List<CleanupInterval> intervals,
        Set<Integer> immutableIndexes
    ) {
        /** Copies all collections and validates point coverage. */
        public Partition {
            dispositions = List.copyOf(dispositions);
            intervals = List.copyOf(intervals);
            immutableIndexes = Set.copyOf(immutableIndexes);
            if (dispositions.isEmpty()) {
                throw new IllegalArgumentException("Cleanup partition must contain points");
            }
        }
    }
}
