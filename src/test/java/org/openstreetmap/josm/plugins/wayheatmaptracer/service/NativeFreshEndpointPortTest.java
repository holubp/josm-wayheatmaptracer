package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.DoubleBinaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedScalarProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.Domain;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.RowState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.SourceClass;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.Status;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class NativeFreshEndpointPortTest {
    private static final double RIDGE_Y = 32.0;
    private static final double CONNECTOR_LEFT = 10.0;
    private static final double CONNECTOR_RIGHT = 30.0;

    static List<org.junit.jupiter.params.provider.Arguments> freshPortClasses() {
        return List.of(
            org.junit.jupiter.params.provider.Arguments.of(SourceClass.GAP, SourceClass.GAP),
            org.junit.jupiter.params.provider.Arguments.of(SourceClass.GAP, SourceClass.CENSORED),
            org.junit.jupiter.params.provider.Arguments.of(SourceClass.CENSORED, SourceClass.GAP),
            org.junit.jupiter.params.provider.Arguments.of(SourceClass.CENSORED, SourceClass.CENSORED)
        );
    }

    @ParameterizedTest
    @MethodSource("freshPortClasses")
    void freshMeasuredPortsAdmitEveryGapAndCensoredEndpointPair(SourceClass fromClass, SourceClass toClass) {
        try (Fixture fixture = fixture(straightGaussian(), (x, y) -> true)) {
            RowState from = state(0, fromClass);
            RowState to = state(1, toClass);
            try (var evaluator = new NativeConnectorEvaluator(
                    fixture.input(), new Domain(List.of(from, to), List.of()))) {
                var receipt = evaluator.evaluate(from, to);

                assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
                assertEquals(fromClass, receipt.from().sourceClass());
                assertEquals(toClass, receipt.to().sourceClass());
                assertNull(receipt.from().peakOwner());
                assertNull(receipt.to().peakOwner());
                assertTrue(receipt.scalarEnvelopeComplete());
                assertTrue(receipt.measuredNativeContinuationComplete());
                assertTrue(receipt.probes().stream().allMatch(probe -> probe.selectedMode() != null));
            }
        }
    }

    @Test
    void uniformBrightCrossSectionCannotSupplyAUniqueFreshPort() {
        try (Fixture fixture = fixture((x, y) -> 1.0, (x, y) -> true)) {
            RowState from = state(0, SourceClass.GAP);
            RowState to = state(1, SourceClass.CENSORED);
            try (var evaluator = new NativeConnectorEvaluator(
                    fixture.input(), new Domain(List.of(from, to), List.of()))) {
                var receipt = evaluator.evaluate(from, to);

                assertNotEquals(Status.ADMITTED, receipt.status(), receipt::toString);
                assertFalse(receipt.probes().isEmpty());
                assertEquals(Status.REJECTED, receipt.status());
                assertTrue(receipt.probes().stream().noneMatch(probe -> probe.selectedMode() != null), receipt::toString);
            }
        }
    }

    @Test
    void darkConnectorCannotBeAdmittedFromFreshPorts() {
        try (Fixture fixture = fixture((x, y) -> 0.0, (x, y) -> true)) {
            RowState from = state(0, SourceClass.GAP);
            RowState to = state(1, SourceClass.CENSORED);
            try (var evaluator = new NativeConnectorEvaluator(
                    fixture.input(), new Domain(List.of(from, to), List.of()))) {
                var receipt = evaluator.evaluate(from, to);

                assertNotEquals(Status.ADMITTED, receipt.status(), receipt::toString);
                assertFalse(receipt.scalarEnvelopeComplete(), receipt::toString);
            }
        }
    }

    @Test
    void invalidCellOnConnectorCannotBeBridgedByFreshPorts() {
        try (Fixture fixture = fixture(straightGaussian(), (x, y) -> !(x == 20 && y == 32))) {
            RowState from = state(0, SourceClass.GAP);
            RowState to = state(1, SourceClass.CENSORED);
            try (var evaluator = new NativeConnectorEvaluator(
                    fixture.input(), new Domain(List.of(from, to), List.of()))) {
                var receipt = evaluator.evaluate(from, to);

                assertNotEquals(Status.ADMITTED, receipt.status(), receipt::toString);
                assertFalse(receipt.scalarEnvelopeComplete(), receipt::toString);
            }
        }
    }

    private static RowState state(int row, SourceClass sourceClass) {
        return new RowState(row, 0, 0.0, sourceClass, null);
    }

    private static DoubleBinaryOperator straightGaussian() {
        return (x, y) -> Math.exp(-0.5 * Math.pow(y - RIDGE_Y, 2.0));
    }

    private static Fixture fixture(DoubleBinaryOperator intensity, BiPredicate<Integer, Integer> validity) {
        int width = 64;
        int height = 64;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                values[y * width + x] = intensity.applyAsDouble(x, y);
                valid[y * width + x] = validity.test(x, y);
            }
        }

        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, 0.0), 1.0, 0.0, 0.0, 1.0);
        MetricRegion decision = MetricRegion.rectangle(0.0, 0.0, 63.0, 63.0);
        GeographicPoint geographicOrigin = new GeographicPoint(0.0, 0.0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(geographicOrigin,
            new GeographicPoint(-0.01, -0.01), new GeographicPoint(0.01, 0.01));
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "native",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        EvidenceSnapshot evidence = new EvidenceSnapshot("fresh-endpoint-port-test", frame, transform,
            EvidenceResolution.nativeSource(1.0, 1.0), decision,
            MetricRegion.rectangle(0.0, 0.0, 63.0, 63.0), Map.of("native", field), "declared-only");

        List<DetachedProfileSamplingLocation> locations = List.of(
            location(evidence, CONNECTOR_LEFT, 0.0),
            location(evidence, CONNECTOR_RIGHT, 20.0));
        CorridorTraceInput trace = new CorridorTraceInput(locations, 1.0);
        ProfileChainage chainage = new ProfileChainage(List.of(0.0, 20.0), 20.0);
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        EmissionWorkBudget work = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        SampledEmissionInput input;
        try {
            input = new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(
                evidence, "native", trace, chainage, 8.0, work, owner);
        } catch (RuntimeException failure) {
            owner.close();
            assertEquals(0L, ledger.currentBytes());
            throw failure;
        }
        return new Fixture(ledger, owner, input);
    }

    private static DetachedProfileSamplingLocation location(
        EvidenceSnapshot evidence,
        double rasterX,
        double chainage
    ) {
        RasterPoint raster = new RasterPoint(rasterX, RIDGE_Y);
        MetricPoint metric = evidence.transform().pixelCenterToMetric(raster.x(), raster.y());
        return new DetachedProfileSamplingLocation(evidence.coordinateFrame().toGeographic(metric), metric,
            raster, evidence.coordinateFrame(), evidence.transform(), chainage);
    }

    private record Fixture(
        AttemptMemoryLedger ledger,
        AttemptMemoryLedger.Owner owner,
        SampledEmissionInput input
    ) implements AutoCloseable {
        @Override
        public void close() {
            input.close();
            owner.close();
            assertEquals(0L, ledger.currentBytes());
        }
    }
}
