package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

class CorridorConfidenceTest {
    @Test
    void confidenceChangesContinuouslyAcrossSparseMediumAndStrongSignals() {
        CorridorExtractor extractor = new CorridorExtractor();
        double sparse = confidence(extractor, 0.12);
        double medium = confidence(extractor, 0.35);
        double strong = confidence(extractor, 0.90);

        assertTrue(sparse > 0.0);
        assertTrue(sparse < medium);
        assertTrue(medium < strong);
    }

    private double confidence(CorridorExtractor extractor, double peak) {
        List<IntensitySample> samples = new ArrayList<>();
        for (int offset = -8; offset <= 8; offset++) {
            double intensity = Math.abs(offset) <= 1 ? peak : 0.01;
            samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
        }
        RenderedHeatmapSampler.CrossSectionProfile profile = new RenderedHeatmapSampler.CrossSectionProfile(
            new EastNorth(0, 0), new Point2D.Double(0, 0), new Point2D.Double(0, 1),
            List.of(), true, samples);
        return extractor.extract(0, profile).bands().get(0).signalExistenceConfidence();
    }
}
