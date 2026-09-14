package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.ScalarEvidenceFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;

class V022EvidenceSnapshotTest {
    @Test
    void t007ConstructorInputsAndReturnedArraysCannotMutateSnapshot() {
        double[] values = {0.2, 0.8, 0.4, 0.6};
        boolean[] valid = {true, true, true, true};
        ScalarEvidenceField field = new ScalarEvidenceField(2, 2, values, valid, lineage("hot"));
        EvidenceSnapshot snapshot = snapshot(Map.of("hot", field));

        values[1] = 0.0;
        valid[1] = false;
        double[] returned = field.copiedValues();
        returned[1] = 0.1;

        assertEquals(0.8, field.sample(1, 0).orElseThrow(), 0.0);
        assertArrayEquals(new double[] {0.2, 0.8, 0.4, 0.6}, field.copiedValues());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.fields().put("x", field));
    }

    @Test
    void t008DetachedSnapshotTypeGraphContainsNoLiveJosmOrCredentialTypes() {
        EvidenceSnapshot value = snapshot(Map.of("hot", field("hot")));

        DetachedValueVerifier.verify(value);
        assertThrows(IllegalArgumentException.class, () -> DetachedValueVerifier.verifyType(
            org.openstreetmap.josm.data.osm.DataSet.class));
    }

    @Test
    void t009NonlinearPaletteMappingOccursBeforeScalarFilter() {
        int[] argb = {0xff000000, 0xff000000, 0xff000000, 0xff000000, 0xffff0000,
            0xff000000, 0xff000000, 0xff000000, 0xff000000};
        boolean[] valid = {true, true, true, true, true, true, true, true, true};
        ScalarEvidenceField filtered = ScalarEvidenceFactory.mapThenConvolve(3, 3, argb, valid,
            pixel -> {
                int red = pixel >>> 16 & 0xff;
                int green = pixel >>> 8 & 0xff;
                int blue = pixel & 0xff;
                return red == 255 && green == 0 && blue == 0 ? 1.0 : 0.0;
            }, lineage("bluered"), new double[] {1, 2, 1});

        assertEquals(0.25, filtered.sample(1, 1).orElseThrow(), 1e-12);
        assertEquals(EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING,
            filtered.lineage().derivationKind());
        assertTrue(filtered.lineage().filtered());
    }

    @Test
    void t010InvalidAndOutOfFieldKernelSupportStayMissing() {
        ScalarEvidenceField field = new ScalarEvidenceField(3, 3,
            new double[] {1, 1, 1, 1, 0, 1, 1, 1, 1},
            new boolean[] {true, true, true, true, false, true, true, true, true}, lineage("hot"));

        ScalarEvidenceField filtered = field.convolveSeparable(new double[] {1, 2, 1});

        assertTrue(filtered.sample(1, 1).isEmpty());
        assertTrue(filtered.sample(0, 0).isEmpty());
    }

    @Test
    void t011HaloCanSupportFilteringButCannotAdmitRoutePointOrSegment() {
        EvidenceSnapshot snapshot = snapshot(Map.of("hot", field("hot")));
        MetricPoint haloOnly = new MetricPoint(0.9, 0.5);

        assertTrue(snapshot.evidenceRegion().contains(haloOnly));
        assertFalse(snapshot.routePositionAuthorized(haloOnly));
        assertFalse(snapshot.routeSegmentAuthorized(new MetricPoint(0.5, 0.5), haloOnly));
    }

    @Test
    void t012DuplicatePaletteLineageDoesNotIncreaseIndependentEvidenceCount() {
        ScalarEvidenceField hot = field("hot");
        ScalarEvidenceField copy = new ScalarEvidenceField(hot.width(), hot.height(), hot.copiedValues(),
            hot.copiedValidity(), lineage("hot-copy"));
        EvidenceSnapshot snapshot = snapshot(Map.of("hot", hot, "hot-copy", copy));

        assertEquals(2, snapshot.fields().size());
        assertEquals(1, snapshot.independentEvidenceGroups().size());
    }

    @Test
    void transformCertificateChangesEvidenceIdentity() {
        EvidenceSnapshot original = snapshot(Map.of("hot", field("hot")));
        RasterMetricTransform transform = original.transform();
        EvidenceSnapshot remeasured = new EvidenceSnapshot(original.snapshotId(), original.coordinateFrame(),
            new RasterMetricTransform(transform.transformId(), transform.originKind(), transform.origin(),
                transform.xAxisEastMetersPerSourcePixel(), transform.xAxisNorthMetersPerSourcePixel(),
                transform.yAxisEastMetersPerSourcePixel(), transform.yAxisNorthMetersPerSourcePixel(),
                transform.rasterPixelsPerSourcePixel(),
                new RasterTransformCertificate("independently-declared-affine-v2", 0.0, 0.0, 0)),
            original.resolution(), original.decisionRegion(), original.evidenceRegion(),
            original.fields(), original.sourceIdentity());

        assertNotEquals(original.canonicalHash(), remeasured.canonicalHash());
    }

    @Test
    void resamplingProvenanceChangesEvidenceIdentity() {
        EvidenceSnapshot direct = snapshot(Map.of("hot", field("hot")));
        EvidenceSnapshot resampled = new EvidenceSnapshot(direct.snapshotId(), direct.coordinateFrame(),
                direct.transform(), direct.resolution().resampledTo(1.0),
                direct.decisionRegion(), direct.evidenceRegion(), direct.fields(),
                RasterResamplingProvenance.exactInverseBilinear(
                        "constructed-local-metric-affine-v1", "test-affine-v1",
                        3, 3, 2, 2),
                direct.sourceIdentity());

        assertNotEquals(direct.canonicalHash(), resampled.canonicalHash());
    }

    @Test
    void exactTransformParametersChangeEvidenceIdentity() {
        EvidenceSnapshot direct = snapshot(Map.of("hot", field("hot")));
        RasterResamplingProvenance first = RasterResamplingProvenance.exactInverseBilinear(
                "visible-web-mercator-projection-bounds-v1", "bounds-a",
                3, 3, 2, 2);
        RasterResamplingProvenance second = RasterResamplingProvenance.exactInverseBilinear(
                "visible-web-mercator-projection-bounds-v1", "bounds-b",
                3, 3, 2, 2);
        EvidenceSnapshot firstSnapshot = new EvidenceSnapshot(direct.snapshotId(),
                direct.coordinateFrame(), direct.transform(),
                direct.resolution().resampledTo(1.0), direct.decisionRegion(),
                direct.evidenceRegion(), direct.fields(), first, direct.sourceIdentity());
        EvidenceSnapshot secondSnapshot = new EvidenceSnapshot(direct.snapshotId(),
                direct.coordinateFrame(), direct.transform(),
                direct.resolution().resampledTo(1.0), direct.decisionRegion(),
                direct.evidenceRegion(), direct.fields(), second, direct.sourceIdentity());

        assertNotEquals(firstSnapshot.canonicalHash(), secondSnapshot.canonicalHash());
    }

    @Test
    void interpolationCellSupportIsImmutableAndChangesEvidenceIdentity() {
        double[] values = {0.2, 0.8, 0.4, 0.6};
        boolean[] valid = {true, true, true, true};
        boolean[] unsupportedCell = {false};
        ScalarEvidenceField supported = new ScalarEvidenceField(
                2, 2, values, valid, new boolean[] {true}, lineage("hot"));
        ScalarEvidenceField unsupported = new ScalarEvidenceField(
                2, 2, values, valid, unsupportedCell, lineage("hot"));
        EvidenceSnapshot supportedSnapshot = snapshot(Map.of("hot", supported));
        EvidenceSnapshot unsupportedSnapshot = snapshot(Map.of("hot", unsupported));

        unsupportedCell[0] = true;
        boolean[] returned = unsupported.copiedInterpolationValidity();
        returned[0] = true;

        assertFalse(unsupported.supportsInterpolationCell(0, 0));
        assertTrue(unsupported.sampleBilinear(0.5, 0.5).isEmpty());
        assertNotEquals(supportedSnapshot.canonicalHash(), unsupportedSnapshot.canonicalHash());
    }

    private static ScalarEvidenceField field(String palette) {
        return new ScalarEvidenceField(2, 2, new double[] {0.2, 0.8, 0.4, 0.6},
            new boolean[] {true, true, true, true}, lineage(palette));
    }

    private static EvidenceFieldLineage lineage(String palette) {
        return new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING, palette, EvidenceCorrelationGroup.STRAVA_RENDERINGS, false);
    }

    private static EvidenceSnapshot snapshot(Map<String, ScalarEvidenceField> fields) {
        GeographicPoint origin = new GeographicPoint(42.0, 19.0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        return new EvidenceSnapshot("evidence-fixture", frame,
            RasterMetricTransform.visible(new MetricPoint(0, fields.values().iterator().next().height() - 1.0), 1.0),
            EvidenceResolution.nativeSource(1.2, 1.0),
            MetricRegion.rectangle(0.2, 0.2, 0.8, 0.8), MetricRegion.rectangle(0.0, 0.0, 1.0, 1.0),
            fields, "fixture-source");
    }
}
