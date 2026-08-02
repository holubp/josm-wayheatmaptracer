package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

class CorridorExtractorTest {
    private final CorridorExtractor extractor = new CorridorExtractor();

    @Test
    void centersBroadCoreFromNestedBoundariesInsteadOfBrightestPixel() {
        CrossSectionProfile profile = profile(
            new double[] {0.0, 0.08, 0.42, 0.86, 1.0, 0.88, 0.84, 0.42, 0.08, 0.0},
            new double[] {0.0, 0.08, 0.42, 0.86, 0.90, 0.90, 0.86, 0.42, 0.08, 0.0}
        );

        CorridorProfile extracted = extractor.extract(0, profile);

        assertTrue(extracted.supported());
        assertFalse(extracted.bands().isEmpty());
        CorridorBand band = extracted.bands().get(0);
        assertEquals(0.0, band.centerOffsetPx(), 0.51);
        assertTrue(band.signalExistenceConfidence() > 0.5);
        assertTrue(band.localizationConfidence() > 0.0);
    }

    @Test
    void appliesSameRelativeExtractionToMediumSignal() {
        CrossSectionProfile profile = profile(
            new double[] {0.01, 0.02, 0.08, 0.19, 0.22, 0.19, 0.08, 0.02, 0.01},
            new double[] {0.01, 0.02, 0.08, 0.18, 0.20, 0.18, 0.08, 0.02, 0.01}
        );

        CorridorProfile extracted = extractor.extract(0, profile);

        assertEquals(1, extracted.bands().size());
        assertEquals(0.0, extracted.bands().get(0).centerOffsetPx(), 0.51);
        assertTrue(extracted.bands().get(0).signalExistenceConfidence() > 0.0);
    }

    @Test
    void interpolatesThresholdBoundariesBetweenSourceSamples() {
        CrossSectionProfile profile = profile(
            new double[] {0.0, 0.4, 1.0, 0.4, 0.0},
            new double[] {0.0, 0.4, 1.0, 0.4, 0.0}
        );

        CorridorBand band = extractor.extract(0, profile).bands().get(0);

        assertEquals(-2.0 / 3.0, band.shoulderMinPx(), 1e-9);
        assertEquals(2.0 / 3.0, band.shoulderMaxPx(), 1e-9);
        assertEquals(-2.0 / 15.0, band.coreMinPx(), 1e-9);
        assertEquals(2.0 / 15.0, band.coreMaxPx(), 1e-9);
        assertEquals(0.0, band.centerOffsetPx(), 1e-9);
    }

    @Test
    void retainsChildrenAndParentWhenHighCoresShareShoulder() {
        CrossSectionProfile profile = profile(
            new double[] {0.0, 0.0, 0.0, 0.64, 1.0, 0.68, 0.66, 0.68, 0.98, 0.64, 0.0, 0.0, 0.0},
            new double[] {0.0, 0.0, 0.0, 0.64, 1.0, 0.68, 0.66, 0.68, 0.98, 0.64, 0.0, 0.0, 0.0}
        );

        CorridorProfile extracted = extractor.extract(0, profile);

        assertEquals(3, extracted.bands().size());
        CorridorBand parent = extracted.bands().stream().filter(CorridorBand::parentHypothesis).findFirst().orElseThrow();
        assertEquals(2, parent.childIds().size());
        assertTrue(parent.valleyRatio() > 0.6);
    }

    @Test
    void distinguishesUnsupportedAndNumericallyEmptyProfiles() {
        CrossSectionProfile unsupported = new CrossSectionProfile(new EastNorth(0, 0), point(), normal(), List.of(), true, List.of());
        CrossSectionProfile empty = profile(new double[] {0.0, 0.0, 0.0}, new double[] {0.0, 0.0, 0.0});

        assertFalse(extractor.extract(0, unsupported).supported());
        assertTrue(extractor.extract(1, empty).supported());
        assertTrue(extractor.extract(1, empty).bands().isEmpty());
    }

    @Test
    void invalidRasterSampleSplitsRatherThanBridgesCorridorEvidence() {
        List<IntensitySample> samples = List.of(
            new IntensitySample(-2.0, 0.0, 0.0, 0.0, true),
            new IntensitySample(-1.0, 1.0, 1.0, 1.0, true),
            new IntensitySample(0.0, 0.0, 0.0, 0.0, false),
            new IntensitySample(1.0, 1.0, 1.0, 1.0, true),
            new IntensitySample(2.0, 0.0, 0.0, 0.0, true)
        );
        CrossSectionProfile profile = new CrossSectionProfile(new EastNorth(0, 0), point(), normal(),
            List.of(), true, samples);

        CorridorProfile extracted = extractor.extract(0, profile);

        assertEquals(2, extracted.bands().size());
        assertTrue(extracted.bands().get(0).shoulderMaxPx() < extracted.bands().get(1).shoulderMinPx());
    }

    @Test
    void profileEvidenceIsImmutable() {
        List<IntensitySample> mutable = new ArrayList<>();
        mutable.add(new IntensitySample(0.0, 1.0, 1.0, 1.0, true));
        CrossSectionProfile profile = new CrossSectionProfile(new EastNorth(0, 0), point(), normal(), List.of(), true, mutable);
        mutable.clear();

        assertEquals(1, profile.intensitySamples().size());
        assertThrows(UnsupportedOperationException.class,
            () -> profile.intensitySamples().add(new IntensitySample(1.0, 0.0, 0.0, 0.0, true)));
    }

    private CrossSectionProfile profile(double[] nativeValues, double[] filteredValues) {
        List<IntensitySample> samples = new ArrayList<>();
        double start = -(nativeValues.length - 1) / 2.0;
        for (int i = 0; i < nativeValues.length; i++) {
            samples.add(new IntensitySample(start + i, nativeValues[i], filteredValues[i], filteredValues[i], true));
        }
        return new CrossSectionProfile(new EastNorth(0, 0), point(), normal(), List.of(), true, samples);
    }

    private Point2D.Double point() {
        return new Point2D.Double(0.0, 0.0);
    }

    private Point2D.Double normal() {
        return new Point2D.Double(0.0, 1.0);
    }
}
