package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** G6-07 shared route-local scalar evidence invariants. */
class V022SharedRouteLocalizationTest {
    private static final MetricPoint HORIZONTAL = new MetricPoint(1, 0);
    private static final MetricPoint VERTICAL = new MetricPoint(0, 1);

    @Test
    void weakScaledCopyHasTheSameLocalizationCosts() {
        ImageCostField.RouteSample weak = field((x, y) -> gaussian(y, 0.08), (x, y) -> true)
                .sampleRoute(new MetricPoint(0, 0), HORIZONTAL).orElseThrow();
        ImageCostField.RouteSample strong = field((x, y) -> gaussian(y, 0.8), (x, y) -> true)
                .sampleRoute(new MetricPoint(0, 0), HORIZONTAL).orElseThrow();

        assertTrue(weak.directlyLocalized());
        assertTrue(strong.directlyLocalized());
        assertEquals(strong.presenceResponse(), weak.presenceResponse(), 1e-9);
        assertEquals(strong.centerCost(), weak.centerCost(), 1e-9);
        assertTrue(weak.rawIntensity() < strong.rawIntensity());
    }

    @Test
    void uniformBrightAndDarkFieldsAreValidButUnknown() {
        ImageCostField.RouteSample bright = field((x, y) -> 0.9, (x, y) -> true)
                .sampleRoute(new MetricPoint(0, 0), HORIZONTAL).orElseThrow();
        ImageCostField.RouteSample dark = field((x, y) -> 0.0, (x, y) -> true)
                .sampleRoute(new MetricPoint(0, 0), HORIZONTAL).orElseThrow();

        assertFalse(bright.directlyLocalized());
        assertFalse(dark.directlyLocalized());
        assertEquals(0.9, bright.rawIntensity(), 1e-9);
        assertEquals(0.0, dark.rawIntensity(), 1e-9);
    }

    @Test
    void costIncreasesAwayFromTheMeasuredCenter() {
        ImageCostField image = field((x, y) -> gaussian(y, 0.4), (x, y) -> true);
        ImageCostField.RouteSample center = image.sampleRoute(new MetricPoint(0, 0), HORIZONTAL).orElseThrow();
        ImageCostField.RouteSample displaced = image.sampleRoute(new MetricPoint(0, 2), HORIZONTAL).orElseThrow();

        assertTrue(center.directlyLocalized());
        assertTrue(displaced.imageEnergy() > center.imageEnergy());
        assertTrue(displaced.centerCost() > center.centerCost());
    }

    @Test
    void distantStrongerParallelModeDoesNotRemoveTheWeakCenter() {
        ImageCostField weakOnly = field((x, y) -> gaussian(y - 5, 0.35), (x, y) -> true);
        ImageCostField withCompetitor = field((x, y) -> Math.max(gaussian(y - 5, 0.35),
                gaussian(y + 4, 0.9)), (x, y) -> true);

        ImageCostField.RouteSample isolated = weakOnly.sampleRoute(new MetricPoint(0, 5), HORIZONTAL)
                .orElseThrow();
        ImageCostField.RouteSample retained = withCompetitor.sampleRoute(new MetricPoint(0, 5), HORIZONTAL)
                .orElseThrow();
        assertTrue(isolated.directlyLocalized());
        assertTrue(retained.directlyLocalized());
        assertEquals(isolated.centerCost(), retained.centerCost(), 1e-9);
    }

    @Test
    void missingBilinearCornerAndBoundaryCensoringCannotBecomeDirectEvidence() {
        ImageCostField missingCorner = field((x, y) -> gaussian(y, 0.8),
                (x, y) -> !(x == 50 && y == 50));
        assertTrue(missingCorner.sampleRoute(new MetricPoint(0.25, 0.25), HORIZONTAL).isEmpty());

        ImageCostField boundary = field((x, y) -> gaussian(y + 50, 0.8), (x, y) -> true);
        Optional<ImageCostField.RouteSample> censored = boundary.sampleRoute(new MetricPoint(0, -49.5), HORIZONTAL);
        assertTrue(censored.isPresent());
        assertFalse(censored.orElseThrow().directlyLocalized());
    }

    @Test
    void crossingBranchesUseTheirOwnRouteOrientation() {
        ImageCostField crossing = field((x, y) -> Math.min(1.0,
                0.05 + gaussian(y, 0.35) + gaussian(x, 0.25)), (x, y) -> true);

        ImageCostField.RouteSample horizontal = crossing.sampleRoute(new MetricPoint(0, 0), HORIZONTAL)
                .orElseThrow();
        ImageCostField.RouteSample vertical = crossing.sampleRoute(new MetricPoint(0, 0), VERTICAL)
                .orElseThrow();
        assertTrue(horizontal.directlyLocalized());
        assertTrue(vertical.directlyLocalized());
        assertTrue(horizontal.normal().distanceTo(vertical.normal()) > 1.0);
    }

    @Test
    void routeReversalAndRotationPreserveLocalization() {
        ImageCostField horizontalRidge = field((x, y) -> gaussian(y, 0.6), (x, y) -> true);
        ImageCostField verticalRidge = field((x, y) -> gaussian(x, 0.6), (x, y) -> true);

        ImageCostField.RouteSample forward = horizontalRidge.sampleRoute(new MetricPoint(0, 0), HORIZONTAL)
                .orElseThrow();
        ImageCostField.RouteSample reverse = horizontalRidge.sampleRoute(new MetricPoint(0, 0),
                new MetricPoint(-1, 0)).orElseThrow();
        ImageCostField.RouteSample rotated = verticalRidge.sampleRoute(new MetricPoint(0, 0), VERTICAL)
                .orElseThrow();
        assertTrue(forward.directlyLocalized());
        assertEquals(forward.imageEnergy(), reverse.imageEnergy(), 1e-9);
        assertEquals(forward.imageEnergy(), rotated.imageEnergy(), 1e-9);
    }

    @Test
    void invalidPaddingPreservesCensoringAtTheObservedSupportBoundary() {
        LocalScalarProfileExtractor extractor = new LocalScalarProfileExtractor();
        LocalScalarProfileExtractor.Result unpadded = extractor.extract(censoredSamples(false), 1.0,
                EvidenceModelParameters.Localization.defaults());
        LocalScalarProfileExtractor.Result padded = extractor.extract(censoredSamples(true), 1.0,
                EvidenceModelParameters.Localization.defaults());

        assertFalse(unpadded.censoredModes().isEmpty());
        assertEquals(unpadded.censoredModes(), padded.censoredModes());
    }

    @Test
    void distantInvalidPaddingPreservesAnAlreadyMeasuredShoulder() {
        LocalScalarProfileExtractor extractor = new LocalScalarProfileExtractor();
        LocalScalarProfileExtractor.Result unpadded = extractor.extract(
                measuredShoulderSamples(false), 1.0,
                EvidenceModelParameters.Localization.defaults());
        LocalScalarProfileExtractor.Result padded = extractor.extract(
                measuredShoulderSamples(true), 1.0,
                EvidenceModelParameters.Localization.defaults());

        assertFalse(unpadded.modes().isEmpty());
        assertEquals(unpadded.modes(), padded.modes());
        assertEquals(unpadded.censoredModes(), padded.censoredModes());
    }

    private static List<LocalScalarProfileExtractor.Sample> measuredShoulderSamples(
            boolean padded) {
        List<LocalScalarProfileExtractor.Sample> samples = new ArrayList<>();
        for (int index = 0; index <= 32; index++) {
            double offset = -8.0 + index * 0.5;
            double intensity = offset < 0.0
                    ? 0.1 + 0.8 * Math.exp(-offset * offset / 2.0)
                    : 0.7 + 0.2 * Math.exp(-offset * offset / 2.0);
            samples.add(new LocalScalarProfileExtractor.Sample(offset, intensity, true));
        }
        if (padded) {
            samples.add(new LocalScalarProfileExtractor.Sample(8.5, Double.NaN, false));
        }
        return samples;
    }

    private static List<LocalScalarProfileExtractor.Sample> censoredSamples(boolean padded) {
        List<LocalScalarProfileExtractor.Sample> samples = new ArrayList<>();
        int last = padded ? 32 : 16;
        for (int index = 0; index <= last; index++) {
            double offset = -8.0 + index * 0.5;
            boolean valid = offset <= 0.0;
            double intensity = valid ? 0.1 + 0.8 * Math.exp(-Math.pow(offset - 2.0, 2) / 8.0)
                    : Double.NaN;
            samples.add(new LocalScalarProfileExtractor.Sample(offset, intensity, valid));
        }
        return samples;
    }

    private static double gaussian(double distance, double amplitude) {
        return amplitude * Math.exp(-0.5 * distance * distance / 4.0);
    }

    private static ImageCostField field(BiFunction<Double, Double, Double> value,
            BiFunction<Integer, Integer, Boolean> support) {
        int size = 101;
        double[] values = new double[size * size];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int index = y * size + x;
                values[index] = value.apply((double) x - 50, (double) y - 50);
                valid[index] = support.apply(x, y);
            }
        }
        ScalarEvidenceField scalar = new ScalarEvidenceField(size, size, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "route-local-test",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(scalar, new RasterMetricTransform("route-local-unit-grid",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(-50, -50), 1, 0, 0, 1, 1),
                MetricRegion.rectangle(-50, -50, 50, 50), 1.0);
    }
}
