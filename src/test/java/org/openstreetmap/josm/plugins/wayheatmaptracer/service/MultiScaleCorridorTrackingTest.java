package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.MultiScaleProfileSet.ScaleProfileLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/** Integrated fine/coarse corridor identity and persistence regressions. */
class MultiScaleCorridorTrackingTest {
    @Test
    void compatibleCoarseEvidenceStabilizesBroadAlternatingFineCore() {
        MultiScaleProfileSet profiles = profileSet(
            index -> index % 2 == 0 ? -0.75 : 0.75,
            index -> 0.0,
            index -> 0.0,
            false
        );

        CorridorAwareTracker.TrackingResult result = new CorridorAwareTracker()
            .trackDetailed(profiles, 1.0, JunctionContext.empty());

        assertFalse(result.candidates().isEmpty());
        CenterlineCandidate best = result.candidates().get(0);
        assertTrue(best.evidence().scalePersistence() >= 0.85,
            "scale persistence=" + best.evidence().scalePersistence());
        assertTrue(highFrequencyRms(best.offsetsPx()) <= 0.25,
            "high-frequency RMS=" + highFrequencyRms(best.offsetsPx()) + " offsets=" + best.offsetsPx()
                + " evidence=" + best.evidence() + " scales=" + result.scaleEvidence());
    }

    @Test
    void persistentFineWeakStrandSurvivesWhenCoarseLevelsHaveNoSignal() {
        MultiScaleProfileSet profiles = profileSet(index -> 2.0, index -> Double.NaN,
            index -> Double.NaN, true);

        CorridorAwareTracker.TrackingResult result = new CorridorAwareTracker()
            .trackDetailed(profiles, 1.0, JunctionContext.empty());

        assertFalse(result.candidates().isEmpty());
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.evidence().supportedProfiles() >= 20));
    }

    @Test
    void coarseParentMergeDoesNotCollapseParallelFineChildren() {
        List<ScaleProfileLevel> levels = List.of(
            new ScaleProfileLevel(0, 1, 0.0, parallelProfiles(false)),
            new ScaleProfileLevel(1, 2, 1.0, parallelProfiles(false)),
            new ScaleProfileLevel(2, 4, Math.sqrt(5.0), parallelProfiles(true))
        );

        CorridorAwareTracker.TrackingResult result = new CorridorAwareTracker()
            .trackDetailed(new MultiScaleProfileSet(levels, 0L, 0L), 1.0, JunctionContext.empty());

        List<Double> meanOffsets = result.candidates().stream()
            .filter(candidate -> !candidate.offsetsPx().isEmpty())
            .map(candidate -> candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).average().orElse(0.0))
            .toList();
        assertTrue(meanOffsets.stream().anyMatch(value -> value <= -4.0), "missing left child: " + meanOffsets);
        assertTrue(meanOffsets.stream().anyMatch(value -> value >= 4.0), "missing right child: " + meanOffsets);
    }

    @Test
    void retainedSineAmplitudeStaysAboveNinetyPercent() {
        MultiScaleProfileSet profiles = profileSet(
            index -> 6.0 * Math.sin(index * Math.PI / 10.0),
            index -> 5.8 * Math.sin(index * Math.PI / 10.0),
            index -> 5.5 * Math.sin(index * Math.PI / 10.0),
            false
        );

        CorridorAwareTracker.TrackingResult result = new CorridorAwareTracker()
            .trackDetailed(profiles, 1.0, JunctionContext.empty());

        assertFalse(result.candidates().isEmpty());
        double amplitude = result.candidates().stream()
            .mapToDouble(candidate -> amplitude(candidate.offsetsPx()))
            .max().orElse(0.0);
        assertTrue(amplitude >= 5.4, "retained sine amplitude=" + amplitude);
    }

    private MultiScaleProfileSet profileSet(
        IntToDoubleFunction fineCenter,
        IntToDoubleFunction mediumCenter,
        IntToDoubleFunction coarseCenter,
        boolean weak
    ) {
        return new MultiScaleProfileSet(List.of(
            new ScaleProfileLevel(0, 1, 0.0, singleProfiles(fineCenter, weak, 1)),
            new ScaleProfileLevel(1, 2, 1.0, singleProfiles(mediumCenter, false, 2)),
            new ScaleProfileLevel(2, 4, Math.sqrt(5.0), singleProfiles(coarseCenter, false, 4))
        ), 0L, 0L);
    }

    private List<RenderedHeatmapSampler.CrossSectionProfile> singleProfiles(
        IntToDoubleFunction centerFunction,
        boolean weak,
        int pitch
    ) {
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double center = centerFunction.applyAsDouble(i);
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = -12; offset <= 12; offset += pitch) {
                boolean noSignal = Double.isNaN(center);
                double distance = noSignal ? Double.POSITIVE_INFINITY : Math.abs(offset - center);
                double peak = weak ? 0.18 : 0.92;
                double intensity = distance <= 1.5 * pitch ? peak
                    : (distance <= 3.5 * pitch ? peak * 0.48 : 0.02);
                if (noSignal) {
                    intensity = 0.02;
                }
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            profiles.add(profile(i, samples));
        }
        return profiles;
    }

    private List<RenderedHeatmapSampler.CrossSectionProfile> parallelProfiles(boolean merged) {
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = -16; offset <= 16; offset += merged ? 4 : 2) {
                double distance = merged ? Math.abs(offset) : Math.min(Math.abs(offset + 7.0), Math.abs(offset - 7.0));
                double intensity = distance <= (merged ? 8.0 : 2.0) ? 0.92
                    : (distance <= (merged ? 10.0 : 4.0) ? 0.45 : 0.02);
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            profiles.add(profile(i, samples));
        }
        return profiles;
    }

    private RenderedHeatmapSampler.CrossSectionProfile profile(int index, List<IntensitySample> samples) {
        Point2D.Double anchor = new Point2D.Double(index * 5.0, 0.0);
        return new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(anchor.x, anchor.y), anchor,
            new Point2D.Double(0.0, 1.0), List.of(), true, samples);
    }

    private double highFrequencyRms(List<Double> values) {
        double sum = 0.0;
        for (int i = 1; i < values.size() - 1; i++) {
            double residual = values.get(i) - (values.get(i - 1) + values.get(i + 1)) / 2.0;
            sum += residual * residual;
        }
        return values.size() < 3 ? 0.0 : Math.sqrt(sum / (values.size() - 2));
    }

    private double amplitude(List<Double> values) {
        return values.isEmpty() ? 0.0
            : (values.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
                - values.stream().mapToDouble(Double::doubleValue).min().orElseThrow()) / 2.0;
    }
}
