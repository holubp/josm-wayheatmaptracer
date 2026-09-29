package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DistortionCertificate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Public synthetic numerical-frame witnesses; no real capture coordinates or identities. */
class V022FrozenFrameTest {
    private static final GeographicPoint ORIGIN = new GeographicPoint(38.48, 20.0);
    private static final GeographicPoint SOUTH_WEST = new GeographicPoint(38.47, 19.99);
    private static final GeographicPoint NORTH_EAST = new GeographicPoint(38.49, 20.01);
    // Independently evaluated StrictMath WGS84 witness: the old host Math east scale is one ULP higher.
    private static final double EAST = 0x1.31246c5aeb003p22;
    private static final double NORTH = 0x1.84315cb56121dp22;
    private static final double BOUND = 0x1.21d928301f8ep-13;

    @Test
    void newCaptureAndFrozenRoundtripPreserveDeterministicNumericalFrame() {
        LocalMetricFrame source = frame();
        assertEquals(EAST, source.distortionCertificate().eastMetersPerRadian());
        assertEquals(NORTH, source.distortionCertificate().northMetersPerRadian());
        assertEquals(BOUND, source.distortionCertificate().maximumRelativeDistanceError());
        GeographicPoint point = new GeographicPoint(38.485, 20.005);
        assertEquals(new MetricPoint(0x1.b448cf307990ap8, 0x1.1583920ec657fp9), source.toMetric(point));
        FrozenReplayInput original = input(source);
        FrozenReplayInput restored = FrozenReplayCodec.decode(FrozenReplayCodec.encode(original));
        assertEquals(source, restored.evidence().coordinateFrame());
        assertEquals(source.toMetric(point), restored.evidence().coordinateFrame().toMetric(point));
        assertEquals(source.toGeographic(source.toMetric(point)),
                restored.evidence().coordinateFrame().toGeographic(source.toMetric(point)));
        assertEquals(original.evidence().canonicalHash(), restored.evidence().canonicalHash());
        assertEquals(original.canonicalHash(), restored.canonicalHash());
    }

    @Test
    void strictConversionsMatchIndependentBinary64LiteralWitnesses() {
        LocalMetricFrame source = frame();
        // Exact rational arithmetic, rounded to binary64 after each prescribed operation.
        // Adjacent input coordinates exercise subtract-before-convert cancellation.
        double[][] forward = {
            {0x1.33d70a3d70a3ep5, 0x1.4000000000001p4, 0x1.54d8e1dddf438p-32, 0x1.b19d943714f57p-31},
            {0x1.33d70a3d70a3cp5, 0x1.3ffffffffffffp4, -0x1.54d8e1dddf438p-32, -0x1.b19d943714f57p-31},
            {38.485, 20.005, 0x1.b448cf307990ap8, 0x1.1583920ec657fp9},
            {38.475, 19.995, -0x1.b448cf307990ap8, -0x1.1583920ec4a64p9}
        };
        for (double[] row : forward) {
            assertEquals(new MetricPoint(row[2], row[3]),
                    source.toMetric(new GeographicPoint(row[0], row[1])));
        }
        // Tiny metric deltas round back to the origin or one longitude ULP;
        // inverse division, conversion multiplication and origin addition remain ordered.
        double[][] inverse = {
            {0x1p-33, 0x1p-33, 0x1.33d70a3d70a3dp5, 0x1.4p4},
            {0x1p-32, 0x1p-32, 0x1.33d70a3d70a3dp5, 0x1.4000000000001p4},
            {0.1, -0.1, 0x1.33d709c48779fp5, 0x1.40000133a35dap4}
        };
        for (double[] row : inverse) {
            assertEquals(new GeographicPoint(row[2], row[3]),
                    source.toGeographic(new MetricPoint(row[0], row[1])));
        }
    }

    @Test
    void exactRestorationRejectsOneBitCoefficientAndProofTampering() {
        LocalMetricFrame good = assertDoesNotThrow(() -> restore(EAST, NORTH, BOUND));
        assertEquals(frame(), good);
        for (double east : new double[] {Math.nextUp(EAST), Math.nextDown(EAST), 0, -1,
                Double.NaN, Double.POSITIVE_INFINITY, EAST * 1.01}) {
            assertThrows(IllegalArgumentException.class, () -> restore(east, NORTH, BOUND));
        }
        assertThrows(IllegalArgumentException.class, () -> restore(EAST, Math.nextDown(NORTH), BOUND));
        assertThrows(IllegalArgumentException.class, () -> restore(EAST, NORTH, Math.nextDown(BOUND)));
        assertThrows(IllegalArgumentException.class, () -> restore(EAST, NORTH, 0.002));
        assertThrows(IllegalArgumentException.class, () -> restore(EAST, NORTH, Double.NaN));
    }

    @Test
    void evidenceIdentityBindsActualMetreCoefficients() throws Exception {
        LocalMetricFrame source = frame();
        EvidenceSnapshot evidence = input(source).evidence();
        String original = evidence.canonicalHash();
        // Test-only hostile-state injection catches omission from the identity, independently of restore admission.
        var field = DistortionCertificate.class.getDeclaredField("eastMetersPerRadian");
        field.setAccessible(true);
        field.setDouble(source.distortionCertificate(), Math.nextUp(source.distortionCertificate().eastMetersPerRadian()));
        assertNotEquals(original, evidence.canonicalHash());
    }

    @Test
    void identityBindsDomainEvenWhenBoundAndCoefficientsAgree() {
        GeographicPoint origin = new GeographicPoint(0, 0);
        LocalMetricFrame first = LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(-0.001, -0.002), new GeographicPoint(0.001, 0.002));
        LocalMetricFrame second = LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(-0.001, -0.003), new GeographicPoint(0.001, 0.001));
        assertEquals(first.distortionCertificate().maximumRelativeDistanceError(),
                second.distortionCertificate().maximumRelativeDistanceError());
        assertEquals(first.distortionCertificate().eastMetersPerRadian(),
                second.distortionCertificate().eastMetersPerRadian());
        assertNotEquals(input(first).evidence().canonicalHash(), input(second).evidence().canonicalHash());
    }

    @Test
    void planIdentityBindsActualMetreCoefficients() throws Exception {
        LocalMetricFrame source = frame();
        AlignmentEditPlan plan = plan(input(source));
        String original = plan.canonicalHash();
        var field = DistortionCertificate.class.getDeclaredField("northMetersPerRadian");
        field.setAccessible(true);
        field.setDouble(source.distortionCertificate(), Math.nextUp(NORTH));
        assertNotEquals(original, plan.canonicalHash());
    }

    @Test
    void codecVersionsAndExportMetadataFollowActualFrameRepresentation() throws IOException {
        for (boolean legacy : new boolean[] {false, true}) {
            LocalMetricFrame source = legacy ? LocalMetricFrame.legacyCertifiedEquirectangular(
                    ORIGIN, SOUTH_WEST, NORTH_EAST) : frame();
            FrozenReplayInput base = input(source);
            byte[] frozen = FrozenReplayCodec.encode(base);
            assertEquals(legacy ? 2 : 3, headerVersion(frozen));
            FrozenReplayInput restored = FrozenReplayCodec.decode(frozen);
            assertEquals(!legacy, restored.evidence().coordinateFrame().hasCompleteNumericalIdentity());
            assertEquals(base.canonicalHash(), restored.canonicalHash());
            Format15Bundle bundle = Format15ProductionBundleFactory.create("synthetic", base);
            String identities = new String(bundle.artifact("frozen-input-identities.json").bytes(),
                    StandardCharsets.UTF_8);
            assertTrue(identities.contains("\"codecVersion\":" + headerVersion(frozen)), identities);
            assertEquals(legacy ? 1 : 2, headerVersion(FrozenReplayCodec.encodeEditPlan(plan(base))));
            NetworkSnapshotCapture.Specification authority = scope(base);
            assertEquals(authority, FrozenReplayCodec.decodeAuthority(FrozenReplayCodec.encodeAuthority(authority)));
            assertEquals(plan(base), FrozenReplayCodec.decodeEditPlan(FrozenReplayCodec.encodeEditPlan(plan(base))));
        }
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.encodedVersion(new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.encodedVersion(
                ByteBuffer.allocate(8).putInt(0).putInt(3).array()));
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.encodedVersion(
                ByteBuffer.allocate(8).putInt(0x57545250).putInt(4).array()));
    }

    @Test
    void exactFramePayloadRejectsCoefficientTamperingAndLegacyDowngrade() {
        FrozenReplayInput base = input(frame());
        byte[] frozen = FrozenReplayCodec.encode(base);
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.decode(
                replaceDouble(frozen, EAST, Math.nextUp(EAST))));
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.decode(
                replaceDouble(frozen, BOUND, Math.nextDown(BOUND))));
        byte[] downgraded = frozen.clone();
        ByteBuffer.wrap(downgraded).putInt(4, 2);
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.decode(downgraded));
        byte[] plan = FrozenReplayCodec.encodeEditPlan(plan(base));
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.decodeEditPlan(
                replaceDouble(plan, NORTH, Math.nextUp(NORTH))));
        byte[] authority = FrozenReplayCodec.encodeAuthority(scope(base));
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.decodeAuthority(
                replaceDouble(authority, EAST, Math.nextDown(EAST))));
        byte[] wrongAuthorityVersion = authority.clone();
        ByteBuffer.wrap(wrongAuthorityVersion).putInt(4, 2);
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.decodeAuthority(wrongAuthorityVersion));
        assertThrows(IllegalArgumentException.class, () -> LocalMetricFrame.restoreCertified(
                LocalMetricFrame.STRICT_PROJECTION_ID, "wgs84-local-tangent-v1-analytic-bound",
                ORIGIN, SOUTH_WEST, NORTH_EAST, BOUND, EAST, NORTH));
        assertThrows(IllegalArgumentException.class, () -> LocalMetricFrame.restoreCertified(
                LocalMetricFrame.LEGACY_PROJECTION_ID, "wgs84-local-tangent-v2-strictmath-bound",
                ORIGIN, SOUTH_WEST, NORTH_EAST, BOUND, EAST, NORTH));
        assertThrows(IllegalArgumentException.class, () -> LocalMetricFrame.restoreCertified(
                LocalMetricFrame.STRICT_PROJECTION_ID, "wgs84-local-tangent-v2-strictmath-bound",
                ORIGIN, SOUTH_WEST, new GeographicPoint(38.491, 20.01), BOUND, EAST, NORTH));
    }

    @Test
    void intervalEnvelopeReadsExplicitLegacyAndCompleteFramesWithoutPromotion() throws IOException {
        for (boolean legacy : new boolean[] {true, false}) {
            LocalMetricFrame source = legacy ? LocalMetricFrame.legacyCertifiedEquirectangular(
                    ORIGIN, SOUTH_WEST, NORTH_EAST) : frame();
            FrozenReplayInput base = input(source);
            byte[] bytes = intervalEnvelope(base, legacy ? 2 : 3);
            FrozenIntervalReplayCodec.Payload read = FrozenIntervalReplayCodec.decode(bytes);
            assertEquals(base.canonicalHash(), read.shared().canonicalHash());
            assertEquals(source, read.authority().metricFrame());
            assertEquals(!legacy, read.shared().evidence().coordinateFrame().hasCompleteNumericalIdentity());
            assertThrows(IllegalArgumentException.class, () -> FrozenIntervalReplayCodec.decode(
                    intervalEnvelope(base, legacy ? 3 : 2)));
        }
    }

    @Test
    void scopeAndEditPlanRoundtripsRetainTheSameCompleteFrame() {
        LocalMetricFrame source = frame();
        assertEquals(EAST, source.distortionCertificate().eastMetersPerRadian());
        FrozenReplayInput base = input(source);
        NetworkSnapshotCapture.Specification scope = scope(base);
        assertEquals(scope, FrozenReplayCodec.decodeAuthority(FrozenReplayCodec.encodeAuthority(scope)));
        AlignmentEditPlan plan = plan(base);
        assertEquals(plan, FrozenReplayCodec.decodeEditPlan(FrozenReplayCodec.encodeEditPlan(plan)));
    }

    @Test
    void newlyCertifiedDomainsRejectPolesAndExcessiveDistortion() {
        assertThrows(IllegalArgumentException.class, () -> LocalMetricFrame.certifiedEquirectangular(
                new GeographicPoint(90, 0), new GeographicPoint(90, 0), new GeographicPoint(90, 0)));
        assertThrows(IllegalArgumentException.class, () -> LocalMetricFrame.certifiedEquirectangular(
                ORIGIN, new GeographicPoint(38, 19), new GeographicPoint(39, 21)));
        assertThrows(IllegalArgumentException.class, () -> LocalMetricFrame.certifiedEquirectangular(
                new GeographicPoint(0, 0), new GeographicPoint(-0.01, -100), new GeographicPoint(0.01, 100)));
        LocalMetricFrame crossing = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(10, 179.95),
                new GeographicPoint(9.99, 179.8), new GeographicPoint(10.01, -179.8));
        assertTrue(crossing.distortionCertificate().acceptable());
        assertTrue(LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50, 0),
                new GeographicPoint(49.953, -0.001), new GeographicPoint(50.047, 0.001))
                .distortionCertificate().maximumRelativeDistanceError() < 0.001);
        assertThrows(IllegalArgumentException.class, () -> LocalMetricFrame.certifiedEquirectangular(
                new GeographicPoint(50, 0), new GeographicPoint(49.951, -0.001),
                new GeographicPoint(50.049, 0.001)));
    }

    private static LocalMetricFrame restore(double east, double north, double bound) {
        return LocalMetricFrame.restoreCertified(LocalMetricFrame.STRICT_PROJECTION_ID,
                "wgs84-local-tangent-v2-strictmath-bound", ORIGIN, SOUTH_WEST, NORTH_EAST,
                bound, east, north);
    }

    private static int headerVersion(byte[] bytes) throws IOException {
        try (DataInputStream stream = new DataInputStream(new ByteArrayInputStream(bytes))) {
            stream.readInt();
            return stream.readInt();
        }
    }

    private static byte[] replaceDouble(byte[] bytes, double original, double replacement) {
        byte[] pattern = ByteBuffer.allocate(8).putDouble(original).array();
        byte[] changed = bytes.clone();
        int matches = 0;
        for (int at = 0; at <= bytes.length - pattern.length; at++) {
            if (Arrays.equals(pattern, 0, pattern.length, bytes, at, at + pattern.length)) {
                ByteBuffer.wrap(changed).putDouble(at, replacement);
                matches++;
            }
        }
        assertEquals(1, matches, "wire parameter located independently of metadata offsets");
        return changed;
    }

    /** Literal published envelope layout, independent of the interval production writer. */
    private static byte[] intervalEnvelope(FrozenReplayInput base, int version) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x57544952);
            out.writeInt(version);
            byte[][] parts = {FrozenReplayCodec.encode(base), FrozenReplayCodec.encodeAuthority(scope(base))};
            for (byte[] part : parts) {
                out.writeInt(part.length);
                out.write(part);
            }
            out.writeUTF("a".repeat(64)); // partition proof
            out.writeInt(1);
            byte[] request = FrozenReplayCodec.encodeRequestOnly(base.request());
            out.writeInt(request.length);
            out.write(request);
            out.writeDouble(base.request().profileChainage().sourceOriginGroundMeters());
            out.writeUTF("b".repeat(64)); // scalar output unavailable in this codec-only fixture
            out.writeUTF("c".repeat(64)); // final output unavailable
            out.writeInt(0); // no retained routes
            out.writeInt(0); // default choice
            out.writeInt(3); // UNCHANGED_NOOP
            out.writeInt(0); // valid declared interval reason
            out.writeUTF("d".repeat(64)); // preview
            out.writeBoolean(false); // no apply plan
        }
        return bytes.toByteArray();
    }

    static LocalMetricFrame frame() {
        return LocalMetricFrame.certifiedEquirectangular(ORIGIN, SOUTH_WEST, NORTH_EAST);
    }

    private static NetworkSnapshotCapture.Specification scope(FrozenReplayInput base) {
        NetworkSnapshot before = base.network();
        var closure = before.closure();
        return new NetworkSnapshotCapture.Specification(before.snapshotId(), before.datasetIdentity(),
                before.sourceGeneration(), base.request().selectedWayKey(), base.request().selectedRange(),
                base.evidence().coordinateFrame(), closure.collisionEnvelope(), closure.editRegion(),
                closure.editableWayOccurrences(), closure.editableExistingKeys(), closure.movableExistingNodeKeys(),
                closure.removableExistingNodeKeys(), closure.protectedExistingNodeKeys(),
                closure.mayCreateNodes(), base.request().permissions());
    }

    static FrozenReplayInput input(LocalMetricFrame frame) {
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
        MetricRegion region = MetricRegion.rectangle(0, 0, 3, 3);
        Map<PrimitiveKey, DetachedPrimitive> primitives = Map.of(
                first, new DetachedNode(first, frame.toGeographic(new MetricPoint(1, 1)), Map.of(), false, false),
                last, new DetachedNode(last, frame.toGeographic(new MetricPoint(2, 1)), Map.of(), false, false),
                way, new DetachedWay(way, List.of(first, last), Map.of("highway", "path"), false, false));
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
                "synthetic-frame", primitives.keySet(), Set.of(way), Set.of(), Set.of(first, last), Set.of(),
                Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region,
                true, true, true, true);
        NetworkSnapshot network = new NetworkSnapshot("network", SnapshotRole.CAPTURED_BEFORE,
                "synthetic-frame", 1, closure, primitives, Map.of(first, Set.of(way), last, Set.of(way), way, Set.of()));
        double[] values = new double[16];
        boolean[] valid = new boolean[16];
        Arrays.fill(values, 0.5);
        Arrays.fill(valid, true);
        ScalarEvidenceField scalar = new ScalarEvidenceField(4, 4, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                    EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
                    EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        EvidenceResolution resolution = EvidenceResolution.nativeSource(1, 1);
        EvidenceSnapshot evidence = new EvidenceSnapshot("evidence", frame,
                RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1), resolution,
                region, MetricRegion.rectangle(-0.5, -0.5, 3.5, 3.5), Map.of("native", scalar), "synthetic");
        TraceRequest request = new TraceRequest(way, new OccurrenceRange(0, 1), TrackerMode.PROBABILISTIC,
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(7),
                new TraceBudgets(16, 100_000, 100_000, 4, 4), evidence.snapshotId(), evidence.canonicalHash(),
                network.snapshotId(), network.canonicalHash(), "settings", "a".repeat(64), "synthetic",
                1, new ProfileChainage(List.of(0.0, 1.0), 1), resolution, Optional.empty());
        return new FrozenReplayInput(request, evidence, network,
                new ModernTracePipeline.Options("native", GeometryCleanupConfig.disabled(), "synthetic", 0));
    }

    static AlignmentEditPlan plan(FrozenReplayInput input) {
        NetworkSnapshot before = input.network();
        PrimitiveKey way = input.request().selectedWayKey();
        DetachedWay selected = (DetachedWay) before.primitives().get(way);
        PrimitiveKey inserted = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 1);
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(before.primitives());
        values.put(inserted, new DetachedNode(inserted,
                input.evidence().coordinateFrame().toGeographic(new MetricPoint(1.5, 1.1)), Map.of(), false, true));
        values.put(way, new DetachedWay(way, List.of(selected.nodeKeys().get(0), inserted, selected.nodeKeys().get(1)),
                selected.tags(), false, true));
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>(before.incomingReferrerWatches());
        watches.put(inserted, Set.of(way));
        NetworkSnapshot after = new NetworkSnapshot("after", SnapshotRole.PROPOSED_AFTER,
                before.datasetIdentity(), before.sourceGeneration(), before.closure(), values, watches);
        List<GeographicPoint> preview = ((DetachedWay) values.get(way)).nodeKeys().stream()
                .map(key -> ((DetachedNode) values.get(key)).coordinate()).toList();
        return new AlignmentEditPlan(way, input.request().selectedRange(), before, after,
                input.evidence().coordinateFrame(), input.request().permissions(), "settings",
                input.evidence().canonicalHash(), "a".repeat(64), "synthetic-route", Map.of(way, preview),
                new ValidationReport(ValidationReport.Disposition.APPLICABLE, List.of()));
    }
}
