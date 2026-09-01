package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupInterval;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupPointDisposition;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalPreviewCleanupContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;

class CleanupEligibilityPartitionerTest {
    @Test
    void unsupportedPointFreezesOnlyItsBoundaryAndKeepsDistantIntervalsEligible() {
        FinalPreviewCleanupContext context = context(9, 4, CleanupEvidenceProvenance.UNSUPPORTED);

        CleanupEligibilityPartitioner.Partition partition =
            new CleanupEligibilityPartitioner().partition(context);

        assertEquals(CleanupPointDisposition.PROTECTED_ANCHOR, partition.dispositions().get(0));
        assertEquals(CleanupPointDisposition.FROZEN_UNSUPPORTED, partition.dispositions().get(4));
        assertEquals(CleanupPointDisposition.PROTECTED_ANCHOR, partition.dispositions().get(8));
        assertEquals(2, partition.intervals().size());
        assertTrue(partition.intervals().stream().allMatch(CleanupInterval::smoothingEligible));
        assertTrue(partition.intervals().stream().allMatch(CleanupInterval::simplificationEligible));
        assertEquals(Set.of(0, 4, 8), partition.immutableIndexes());
    }

    @Test
    void boundedInterpolationAndNoSignalHaveDistinctTypedDispositions() {
        FinalPreviewCleanupContext interpolation = context(7, 3,
            CleanupEvidenceProvenance.BOUNDED_INTERPOLATION);
        FinalPreviewCleanupContext noSignal = context(7, 3, CleanupEvidenceProvenance.DIRECT, true);

        assertEquals(CleanupPointDisposition.FROZEN_INTERPOLATED,
            new CleanupEligibilityPartitioner().partition(interpolation).dispositions().get(3));
        assertEquals(CleanupPointDisposition.FROZEN_NO_SIGNAL,
            new CleanupEligibilityPartitioner().partition(noSignal).dispositions().get(3));
    }

    private static FinalPreviewCleanupContext context(
        int count,
        int defectIndex,
        CleanupEvidenceProvenance provenance
    ) {
        return context(count, defectIndex, provenance, false);
    }

    private static FinalPreviewCleanupContext context(
        int count,
        int defectIndex,
        CleanupEvidenceProvenance provenance,
        boolean noSignal
    ) {
        List<EastNorth> geometry = new ArrayList<>();
        List<CleanupSamplingProfile> samples = new ArrayList<>();
        List<CandidateCleanupProfile> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            geometry.add(new EastNorth(index, 0.0));
            double signal = noSignal && index == defectIndex ? 0.0 : 1.0;
            samples.add(new CleanupSamplingProfile(index, index, true, 1.0,
                new ProjectedLateralTransform(new EastNorth(index, 0.0), 0.0, 1.0),
                new double[] {-1.0, 0.0, 1.0}, new double[] {0.0, signal, 0.0},
                new double[] {0.0, signal, 0.0}, new double[] {0.0, signal, 0.0},
                new boolean[] {true, true, true}));
            CleanupEvidenceProvenance rowProvenance = index == defectIndex
                ? provenance : CleanupEvidenceProvenance.DIRECT;
            rows.add(rowProvenance == CleanupEvidenceProvenance.UNSUPPORTED
                ? new CandidateCleanupProfile(index, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    0.0, 1.0, rowProvenance, 0.0, 0.0, false)
                : new CandidateCleanupProfile(index, -0.5, 0.5, -1.0, 1.0,
                    0.0, 1.0, rowProvenance, 0.0, 0.0, false));
        }
        CandidateCleanupEvidence evidence = new CandidateCleanupEvidence(
            new CleanupSamplingFrame("test", samples, 1.0), rows,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceStatus.COMPLETE);
        return new FinalPreviewCleanupContext(geometry, Set.of(0, count - 1),
            List.of(new FinalPreviewCleanupContext.ProtectedInterval(0, count - 1)), evidence,
            java.util.stream.IntStream.range(0, count).boxed().toList(),
            FinalPreviewCleanupContext.Status.COMPLETE);
    }
}
