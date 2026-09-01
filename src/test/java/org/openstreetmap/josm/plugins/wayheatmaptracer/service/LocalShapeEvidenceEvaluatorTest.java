package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalShapeEvidence;

class LocalShapeEvidenceEvaluatorTest {
    @Test
    void separatesAlternatingResidualFromSupportedQuadraticBend() {
        LocalShapeEvidenceEvaluator evaluator = new LocalShapeEvidenceEvaluator();
        LongitudinalCorridorTube wrinkle = tube(
            index -> (index & 1) == 0 ? -0.6 : 0.6,
            index -> 0.0, 1.0, 0.0);
        LongitudinalCorridorTube bend = tube(
            index -> 0.012 * (index - 15.0) * (index - 15.0),
            index -> 0.012 * (index - 15.0) * (index - 15.0), 1.0, 0.7);

        LocalShapeEvidence wrinkleCenter = evaluator.evaluate(wrinkle, 1.0).get(15);
        LocalShapeEvidence bendCenter = evaluator.evaluate(bend, 1.0).get(15);

        assertTrue(wrinkleCenter.wrinkleScore() > 0.45, wrinkleCenter.toString());
        assertTrue(wrinkleCenter.cleanupIntervention() > 0.25, wrinkleCenter.toString());
        assertTrue(bendCenter.bendScore() > 0.50, bendCenter.toString());
        assertTrue(bendCenter.bendProtection() > bendCenter.cleanupIntervention(), bendCenter.toString());
    }

    @Test
    void fixedScaleBankIsSourcePixelInvariant() {
        LocalShapeEvidenceEvaluator evaluator = new LocalShapeEvidenceEvaluator();
        LongitudinalCorridorTube base = tube(
            index -> index * 0.1 + ((index & 1) == 0 ? -0.4 : 0.4),
            index -> index * 0.1, 1.0, 0.0);
        LongitudinalCorridorTube doubled = tube(
            index -> index * 0.2 + ((index & 1) == 0 ? -0.8 : 0.8),
            index -> index * 0.2, 2.0, 0.0);

        LocalShapeEvidence first = evaluator.evaluate(base, 1.0).get(15);
        LocalShapeEvidence second = evaluator.evaluate(doubled, 2.0).get(15);

        assertEquals(first.residualAmplitudeSourcePx(), second.residualAmplitudeSourcePx(), 1e-9);
        assertEquals(first.wrinkleScore(), second.wrinkleScore(), 1e-9);
        assertEquals(List.of(6.0, 10.0, 20.0), LocalShapeEvidenceEvaluator.ANALYSIS_RADII_METERS);
    }

    @Test
    void boundariesAndScaleConflictsAbstainWithoutInventingEvidence() {
        LocalShapeEvidenceEvaluator evaluator = new LocalShapeEvidenceEvaluator();
        LongitudinalCorridorTube conflict = tube(
            index -> (index & 1) == 0 ? -0.6 : 0.6,
            index -> 0.0, 1.0, 0.0, true);

        List<LocalShapeEvidence> evidence = evaluator.evaluate(conflict, 1.0);

        assertEquals(LocalShapeEvidence.Decision.UNAVAILABLE, evidence.get(0).decision());
        assertEquals(0.0, evidence.get(15).cleanupIntervention(), 0.0);
        assertTrue(evidence.get(15).ambiguityScore() > 0.0);
    }

    private static LongitudinalCorridorTube tube(
        IntToDoubleFunction local,
        IntToDoubleFunction channel,
        double sourcePixel,
        double motionSupport
    ) {
        return tube(local, channel, sourcePixel, motionSupport, false);
    }

    private static LongitudinalCorridorTube tube(
        IntToDoubleFunction local,
        IntToDoubleFunction channel,
        double sourcePixel,
        double motionSupport,
        boolean scaleConflict
    ) {
        List<CorridorTubeSlice> slices = new ArrayList<>();
        for (int index = 0; index <= 30; index++) {
            double localValue = local.applyAsDouble(index);
            double channelValue = channel.applyAsDouble(index);
            slices.add(new CorridorTubeSlice(index, index * 2.0, localValue, 0.0,
                localValue, 0.0, channelValue, 0.0, sourcePixel,
                motionSupport, motionSupport > 0.0 ? "coherent-direction" : "reversing-noise",
                0.0, channelValue - sourcePixel, channelValue + sourcePixel,
                channelValue - 2.0 * sourcePixel, channelValue + 2.0 * sourcePixel,
                sourcePixel, 1.0, scaleConflict, false,
                channelValue, channelValue, channelValue, true));
        }
        return new LongitudinalCorridorTube(slices);
    }
}
