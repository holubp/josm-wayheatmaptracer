package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayCapability;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NonTransportSemanticWitness;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyVisibleProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

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
            var extended = new FrozenReplayInput(base.request(), base.evidence(), base.network(),
                    base.options(), base.planSearchRadiusMeters() + 1.0);
            byte[] versionFive = FrozenReplayCodec.encode(extended);
            assertEquals(5, FrozenReplayCodec.encodedVersion(versionFive));
            var extendedCopy = FrozenReplayCodec.decode(versionFive);
            assertEquals(extended.canonicalHash(), extendedCopy.canonicalHash());
            assertEquals(extended.planSearchRadiusMeters(), extendedCopy.planSearchRadiusMeters());
            assertEquals(!legacy, extendedCopy.evidence().coordinateFrame().hasCompleteNumericalIdentity());
            assertTrue(extendedCopy.request().permissions().sharedNodeDisconnectionIntent().isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.encodedVersion(new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.encodedVersion(
                ByteBuffer.allocate(8).putInt(0).putInt(3).array()));
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.encodedVersion(
                ByteBuffer.allocate(8).putInt(0x57545250).putInt(FrozenReplayCodec.VERSION + 1).array()));
    }

    @Test
    void additiveWitnessKeepsBothHistoricalFrameRepresentationsReadable() {
        for (LocalMetricFrame source : List.of(frame(), LocalMetricFrame.legacyCertifiedEquirectangular(
                ORIGIN, SOUTH_WEST, NORTH_EAST))) {
            FrozenReplayInput base = input(source);
            NetworkSnapshot core = base.network();
            var witness = new NonTransportSemanticWitness(core.canonicalHash(), core.datasetIdentity(),
                    core.sourceGeneration(), Map.of());
            var network = new NetworkSnapshot(core.snapshotId(), core.role(), core.datasetIdentity(),
                    core.sourceGeneration(), core.closure(), core.primitives(),
                    core.incomingReferrerWatches(), witness);
            var enriched = new FrozenReplayInput(base.request(), base.evidence(), network, base.options());
            var restored = FrozenReplayCodec.decode(FrozenReplayCodec.encode(enriched));
            assertEquals(enriched.canonicalHash(), restored.canonicalHash());
            assertEquals(network, restored.network());
            assertEquals(source, restored.evidence().coordinateFrame());
            AlignmentEditPlan edit = plan(enriched);
            assertEquals(edit, FrozenReplayCodec.decodeEditPlan(FrozenReplayCodec.encodeEditPlan(edit)));
        }
    }

    @Test
    void payloadCompletenessUsesNewIdentityAndVersionEightWithoutChangingLegacyWitnesses() {
        FrozenReplayInput base = input(frame());
        NetworkSnapshot core = base.network();
        PrimitiveKey relationKey = PrimitiveKey.existing(PrimitiveKey.Type.RELATION, 991);
        PrimitiveKey receiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 992);
        PrimitiveKey unloaded = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 993);
        List<NonTransportSemanticWitness.Member> members = List.of(
                new NonTransportSemanticWitness.Member(receiver, "", true),
                new NonTransportSemanticWitness.Member(unloaded, "", false));
        var legacyRow = new NonTransportSemanticWitness.RelationEvidence(relationKey,
                Map.of("type", "route", "route", "bicycle"), members,
                false, false, false, Set.of());
        var newRow = new NonTransportSemanticWitness.RelationEvidence(relationKey,
                Map.of("type", "route", "route", "bicycle"), members,
                false, false, false, Set.of(), Optional.of(true));
        var legacyWitness = new NonTransportSemanticWitness(core.canonicalHash(), core.datasetIdentity(),
                core.sourceGeneration(), Map.of(relationKey, legacyRow));
        var newWitness = new NonTransportSemanticWitness(core.canonicalHash(), core.datasetIdentity(),
                core.sourceGeneration(), Map.of(relationKey, newRow));
        var falseRow = new NonTransportSemanticWitness.RelationEvidence(relationKey,
                Map.of("type", "route", "route", "bicycle"), members,
                false, false, false, Set.of(), Optional.of(false));
        var falseWitness = new NonTransportSemanticWitness(core.canonicalHash(), core.datasetIdentity(),
                core.sourceGeneration(), Map.of(relationKey, falseRow));
        assertEquals(legacyWitness.canonicalHash(), new NonTransportSemanticWitness(core.canonicalHash(),
                core.datasetIdentity(), core.sourceGeneration(), Map.of(relationKey,
                        new NonTransportSemanticWitness.RelationEvidence(relationKey,
                                legacyRow.tags(), members, false, false, false, Set.of(), Optional.empty())))
                .canonicalHash(), "the historical empty-fact identity remains byte-for-byte stable");
        assertNotEquals(legacyWitness.canonicalHash(), newWitness.canonicalHash());
        assertTrue(legacyWitness.hasSameKnownFactsAs(newWitness),
                "legacy unknown facts may validate against a fresh capture without gaining authority");
        assertTrue(legacyWitness.hasSameKnownFactsAs(falseWitness));
        assertEquals(falseWitness.canonicalHash(), new NonTransportSemanticWitness(core.canonicalHash(),
                core.datasetIdentity(), core.sourceGeneration(), Map.of(relationKey,
                        new NonTransportSemanticWitness.RelationEvidence(relationKey,
                                falseRow.tags(), members, false, false, false, Set.of(), Optional.of(false))))
                .canonicalHash());
        assertNotEquals(newWitness.canonicalHash(), falseWitness.canonicalHash());
        assertFalse(newWitness.hasSameKnownFactsAs(legacyWitness),
                "a new expected fact cannot be discarded by a later capture");
        assertFalse(newWitness.hasSameKnownFactsAs(falseWitness),
                "a changed payload fact invalidates the witness");
        assertThrows(IllegalArgumentException.class, () -> new NonTransportSemanticWitness.RelationEvidence(
                relationKey, Map.of("type", "route", "route", "bicycle"), members,
                true, false, false, Set.of(), Optional.of(false)));

        FrozenReplayInput legacyInput = withWitness(base, legacyWitness);
        byte[] legacyBytes = FrozenReplayCodec.encode(legacyInput);
        assertEquals(4, FrozenReplayCodec.encodedVersion(legacyBytes));
        assertEquals(legacyInput.canonicalHash(), FrozenReplayCodec.decode(legacyBytes).canonicalHash());

        FrozenReplayInput newInput = withWitness(base, newWitness);
        byte[] newBytes = FrozenReplayCodec.encode(newInput);
        assertEquals(8, FrozenReplayCodec.encodedVersion(newBytes));
        FrozenReplayInput decoded = FrozenReplayCodec.decode(newBytes);
        assertEquals(newInput.canonicalHash(), decoded.canonicalHash());
        assertEquals(newWitness, decoded.network().semanticWitness());
        FrozenReplayInput falseInput = withWitness(base, falseWitness);
        FrozenReplayInput decodedFalse = FrozenReplayCodec.decode(FrozenReplayCodec.encode(falseInput));
        assertEquals(falseWitness, decodedFalse.network().semanticWitness());
        assertEquals(falseInput.canonicalHash(), decodedFalse.canonicalHash());
        AlignmentEditPlan editPlan = plan(newInput);
        assertEquals(editPlan, FrozenReplayCodec.decodeEditPlan(FrozenReplayCodec.encodeEditPlan(editPlan)));
    }

    @Test
    void versionEightLegacyAllocationPresenceRoundTripsWithAndWithoutParentPolicy() {
        FrozenReplayInput base = input(frame());
        FrozenLegacyBInput legacy = FrozenLegacyBInput.visible(legacyVisibleInput(), List.of(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid
                        .LegacyTopologyPlanner.Occurrence(0, false, false),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid
                        .LegacyTopologyPlanner.Occurrence(1, false, false)), List.of(), false, 1.0);

        FrozenReplayInput legacyV6 = withLegacy(base, legacy);
        byte[] v6 = FrozenReplayCodec.encode(legacyV6);
        assertEquals(6, FrozenReplayCodec.encodedVersion(v6));
        assertTrue(FrozenReplayCodec.decode(v6).legacyB().parentPolicy().isEmpty());
        FrozenReplayInput legacyV7 = withLegacy(base, legacy.withParentPolicy(
                new FrozenLegacyBInput.ParentPolicy(List.of(), false)));
        byte[] v7 = FrozenReplayCodec.encode(legacyV7);
        assertEquals(7, FrozenReplayCodec.encodedVersion(v7));
        assertEquals(legacyV7.legacyB().parentPolicy(), FrozenReplayCodec.decode(v7).legacyB().parentPolicy());

        PrimitiveKey relationKey = PrimitiveKey.existing(PrimitiveKey.Type.RELATION, 991);
        PrimitiveKey receiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 992);
        PrimitiveKey unloaded = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 993);
        var row = new NonTransportSemanticWitness.RelationEvidence(relationKey,
                Map.of("type", "route", "route", "bicycle"), List.of(
                        new NonTransportSemanticWitness.Member(receiver, "", true),
                        new NonTransportSemanticWitness.Member(unloaded, "", false)),
                false, false, false, Set.of(), Optional.of(true));
        var witness = new NonTransportSemanticWitness(base.network().canonicalHash(),
                base.network().datasetIdentity(), base.network().sourceGeneration(), Map.of(relationKey, row));
        FrozenReplayInput payloadWithoutPolicy = withLegacy(withWitness(base, witness), legacy);
        byte[] v8WithoutPolicy = FrozenReplayCodec.encode(payloadWithoutPolicy);
        assertEquals(8, FrozenReplayCodec.encodedVersion(v8WithoutPolicy));
        assertTrue(FrozenReplayCodec.decode(v8WithoutPolicy).legacyB().parentPolicy().isEmpty());

        FrozenLegacyBInput allocatedLegacy = legacy.withParentPolicy(
                new FrozenLegacyBInput.ParentPolicy(List.of(), false));
        FrozenReplayInput payloadWithPolicy = withLegacy(withWitness(base, witness), allocatedLegacy);
        FrozenReplayInput decoded = FrozenReplayCodec.decode(FrozenReplayCodec.encode(payloadWithPolicy));
        assertEquals(allocatedLegacy.parentPolicy(), decoded.legacyB().parentPolicy());
        assertEquals(witness, decoded.network().semanticWitness());
    }

    @Test
    void payloadCompletenessWithExplicitDisconnectionRoundTripsEveryAuthorityEnvelope() {
        ExplicitDisconnectionFixture fixture = explicitDisconnectionFixture();
        var frozen = fixture.input();
        assertFalse(frozen.request().permissions().sharedNodeDisconnectionIntent().isEmpty());
        assertEquals(Optional.of(true), frozen.network().semanticWitness().relations().values().iterator().next()
                .payloadComplete());

        byte[] frozenBytes = FrozenReplayCodec.encode(frozen);
        assertEquals(8, FrozenReplayCodec.encodedVersion(frozenBytes));
        assertEquals(frozen.canonicalHash(), FrozenReplayCodec.decode(frozenBytes).canonicalHash());

        byte[] requestBytes = FrozenReplayCodec.encodeRequestOnly(frozen.request());
        assertEquals(2, ByteBuffer.wrap(requestBytes).getInt(4));
        assertEquals(frozen.request(), FrozenReplayCodec.decodeRequestOnly(requestBytes, frozen.evidence()));

        byte[] authorityBytes = FrozenReplayCodec.encodeAuthority(fixture.specification());
        assertEquals(4, ByteBuffer.wrap(authorityBytes).getInt(4));
        assertEquals(fixture.specification(), FrozenReplayCodec.decodeAuthority(authorityBytes));

        byte[] editPlanBytes = FrozenReplayCodec.encodeEditPlan(fixture.plan());
        assertEquals(5, ByteBuffer.wrap(editPlanBytes).getInt(4));
        assertEquals(fixture.plan(), FrozenReplayCodec.decodeEditPlan(editPlanBytes));
    }

    private static FrozenReplayInput withWitness(FrozenReplayInput base,
            NonTransportSemanticWitness witness) {
        NetworkSnapshot core = base.network();
        NetworkSnapshot network = new NetworkSnapshot(core.snapshotId(), core.role(), core.datasetIdentity(),
                core.sourceGeneration(), core.closure(), core.primitives(), core.incomingReferrerWatches(), witness);
        return new FrozenReplayInput(base.request(), base.evidence(), network, base.options());
    }

    private static FrozenReplayInput withLegacy(FrozenReplayInput base, FrozenLegacyBInput legacy) {
        return new FrozenReplayInput(base.request(), base.evidence(), base.network(), base.options(),
                base.planSearchRadiusMeters(), legacy);
    }

    private static LegacyVisibleProfileSampler.Input legacyVisibleInput() {
        int[] pixels = {0x00123456, 0xFF112233, 0x8000FF00, 0x00000001,
                0xFFFFFFFF, 0xFF000000, 0x7FABCDEF, 0xFF010203,
                0xFF040506, 0xFF070809, 0xFF0A0B0C, 0xFF0D0E0F};
        var raster = new LiveBPreviewService.VisibleRaster(4, 3, pixels,
                0, 0, 4.0 / 6.0, 0.5, 1, 1, OptionalDouble.empty(), "visible-synthetic", "EPSG:3857");
        var anchors = List.of(
                new LegacyVisibleProfileSampler.Anchor(new ProjectedPoint(.5, 0), new RasterPoint(3, 3), 0),
                new LegacyVisibleProfileSampler.Anchor(new ProjectedPoint(2.5, -2), new RasterPoint(15, 15), 10));
        return new LegacyVisibleProfileSampler.Input(raster, anchors, List.of("hot", "blue"),
                new LegacyVisibleProfileSampler.Options(5, 1, 1.75, IntensitySamplingMode.COLOR_MAPPING));
    }

    private static ExplicitDisconnectionFixture explicitDisconnectionFixture() {
        FrozenReplayInput base = input(frame());
        NetworkSnapshot original = base.network();
        PrimitiveKey selectedKey = base.request().selectedWayKey();
        DetachedWay selected = (DetachedWay) original.primitives().get(selectedKey);
        PrimitiveKey first = selected.nodeKeys().get(0);
        PrimitiveKey last = selected.nodeKeys().get(1);
        PrimitiveKey third = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 5);
        PrimitiveKey receiverKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 4);
        PrimitiveKey relationKey = PrimitiveKey.existing(PrimitiveKey.Type.RELATION, 6);
        Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>(original.primitives());
        primitives.put(third, new DetachedNode(third,
                base.evidence().coordinateFrame().toGeographic(new MetricPoint(2, 2)), Map.of(), false, false));
        primitives.put(receiverKey, new DetachedWay(receiverKey, List.of(first, third),
                Map.of("highway", "path"), false, false));
        var closure = original.closure();
        var primitiveKeys = new java.util.LinkedHashSet<>(closure.primitiveKeys());
        primitiveKeys.add(third);
        primitiveKeys.add(receiverKey);
        var expandedClosure = new ClosureDescriptor(closure.scope(), closure.queryVersion(), primitiveKeys,
                closure.editableExistingKeys(), closure.movableExistingNodeKeys(),
                closure.protectedExistingNodeKeys(), closure.removableExistingNodeKeys(),
                closure.editableWayOccurrences(), closure.externalPorts(), closure.collisionEnvelope(),
                closure.editRegion(), closure.mayCreateNodes(), closure.wayReferrersComplete(),
                closure.relationReferrersComplete(), closure.nearbyGeometryComplete());
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>();
        watches.put(first, Set.of(selectedKey, receiverKey));
        watches.put(last, Set.of(selectedKey));
        watches.put(third, Set.of(receiverKey));
        watches.put(selectedKey, Set.of());
        // The route witness is independent of the shared junction being disconnected. A route
        // relation on the receiver way itself would correctly make this disconnection ineligible.
        watches.put(receiverKey, Set.of());
        NetworkSnapshot core = new NetworkSnapshot(original.snapshotId(), original.role(),
                original.datasetIdentity(), original.sourceGeneration(), expandedClosure, primitives, watches);
        PrimitiveKey unrelated = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 7);
        var relation = new NonTransportSemanticWitness.RelationEvidence(relationKey,
                Map.of("type", "route", "route", "bicycle"), List.of(
                        new NonTransportSemanticWitness.Member(unrelated, "", false)),
                false, false, false, Set.of(), Optional.of(true));
        var witness = new NonTransportSemanticWitness(core.canonicalHash(), core.datasetIdentity(),
                core.sourceGeneration(), Map.of(relationKey, relation));
        NetworkSnapshot source = new NetworkSnapshot(core.snapshotId(), core.role(), core.datasetIdentity(),
                core.sourceGeneration(), expandedClosure, primitives, watches, witness);
        var intent = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.SharedNodeDisconnectionIntent(
                selectedKey, new OccurrenceRange(0, 1), Map.of(0, first), source.canonicalHash());
        var permissions = new RecoveryPermissions(false, 7, 7, JunctionPolicy.FIXED, false, intent);
        TraceRequest oldRequest = base.request();
        TraceRequest request = new TraceRequest(oldRequest.selectedWayKey(), oldRequest.selectedRange(),
                oldRequest.engine(), oldRequest.geometryMode(), permissions, oldRequest.budgets(),
                oldRequest.evidenceSnapshotId(), oldRequest.evidenceContentHash(), source.snapshotId(),
                source.canonicalHash(), oldRequest.settingsHash(), oldRequest.parameterHash(),
                oldRequest.samplerId(), oldRequest.configuredSampleStepMeters(), oldRequest.profileChainage(),
                oldRequest.evidenceResolution(), oldRequest.corridorInput(), source);
        FrozenReplayInput input = new FrozenReplayInput(request, base.evidence(), source, base.options(),
                base.planSearchRadiusMeters());

        var specification = new NetworkSnapshotCapture.Specification(source.snapshotId(), source.datasetIdentity(),
                source.sourceGeneration(), selectedKey, new OccurrenceRange(0, 1), base.evidence().coordinateFrame(),
                expandedClosure.collisionEnvelope(), expandedClosure.editRegion(),
                expandedClosure.editableWayOccurrences(), expandedClosure.editableExistingKeys(),
                expandedClosure.movableExistingNodeKeys(), expandedClosure.removableExistingNodeKeys(),
                expandedClosure.protectedExistingNodeKeys(), expandedClosure.mayCreateNodes(), permissions,
                List.of(), false).withDisconnectionSource(source);

        PrimitiveKey replacement = PrimitiveKey.planned(PrimitiveKey.Type.NODE, Long.MIN_VALUE);
        Map<PrimitiveKey, DetachedPrimitive> afterPrimitives = new LinkedHashMap<>(primitives);
        GeographicPoint firstCoordinate = ((DetachedNode) primitives.get(first)).coordinate();
        afterPrimitives.put(replacement, new DetachedNode(replacement, firstCoordinate, Map.of(), false, true));
        afterPrimitives.put(selectedKey, new DetachedWay(selectedKey, List.of(replacement, last),
                selected.tags(), false, true));
        Map<PrimitiveKey, Set<PrimitiveKey>> afterWatches = new LinkedHashMap<>(watches);
        afterWatches.put(first, Set.of(receiverKey));
        afterWatches.put(replacement, Set.of(selectedKey));
        NetworkSnapshot after = new NetworkSnapshot("after", SnapshotRole.PROPOSED_AFTER,
                source.datasetIdentity(), source.sourceGeneration(), expandedClosure, afterPrimitives,
                afterWatches, witness);
        List<GeographicPoint> preview = List.of(firstCoordinate,
                ((DetachedNode) afterPrimitives.get(last)).coordinate());
        AlignmentEditPlan plan = new AlignmentEditPlan(selectedKey, new OccurrenceRange(0, 1), source, after,
                base.evidence().coordinateFrame(), permissions, oldRequest.settingsHash(),
                base.evidence().canonicalHash(), oldRequest.parameterHash(), "explicit-payload-route",
                Map.of(selectedKey, preview), new ValidationReport(ValidationReport.Disposition.APPLICABLE, List.of()),
                source, Map.of(0, replacement), AlignmentMode.PRECISE_SHAPE);
        return new ExplicitDisconnectionFixture(input, specification, plan);
    }

    private record ExplicitDisconnectionFixture(FrozenReplayInput input,
            NetworkSnapshotCapture.Specification specification, AlignmentEditPlan plan) { }

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
    void benchmarkIntervalComparatorAllowsOnlyRunLocalDatasetIdentity() throws IOException {
        FrozenReplayInput original = input(frame());
        FrozenReplayInput secondRun = withDatasetIdentity(original, "second-run-local-dataset");
        AlignmentEditPlan originalPlan = plan(original);
        AlignmentEditPlan secondPlan = plan(secondRun);
        assertNotEquals(original.canonicalHash(), secondRun.canonicalHash());
        assertNotEquals(originalPlan.canonicalHash(), secondPlan.canonicalHash());
        var originalPartition = SelectedWayIntervalPartitioner.partition(original.network(), scope(original));
        var secondPartition = SelectedWayIntervalPartitioner.partition(secondRun.network(), scope(secondRun));
        assertNotEquals(FrozenReplayCodec.partitionProofHash(originalPartition),
                FrozenReplayCodec.partitionProofHash(secondPartition));
        Format15Archive first = benchmarkIntervalArchive(original, originalPlan, null);
        Format15Archive equal = benchmarkIntervalArchive(secondRun, secondPlan, null);
        assertDoesNotThrow(() -> BenchmarkIntervalDecisionComparison.compare(first, equal));

        AlignmentEditPlan alteredSettings = new AlignmentEditPlan(secondPlan.selectedWayKey(),
                secondPlan.selectedRange(), secondPlan.before(), secondPlan.after(),
                secondPlan.metricFrame(), secondPlan.permissions(), "changed-settings",
                secondPlan.evidenceHash(), secondPlan.parameterHash(), secondPlan.routeIdentity(),
                secondPlan.finalPreviewWays(), secondPlan.validation());
        assertThrows(IllegalStateException.class, () -> BenchmarkIntervalDecisionComparison.compare(
                first, benchmarkIntervalArchive(secondRun, alteredSettings, null)));
        assertThrows(IllegalStateException.class, () -> BenchmarkIntervalDecisionComparison.compare(
                first, benchmarkIntervalArchive(secondRun, secondPlan, "f".repeat(64))));
    }

    @Test
    void scopeAndEditPlanRoundtripsRetainTheSameCompleteFrame() {
        LocalMetricFrame source = frame();
        assertEquals(EAST, source.distortionCertificate().eastMetersPerRadian());
        FrozenReplayInput base = input(source);
        NetworkSnapshotCapture.Specification scope = scope(base);
        assertEquals(scope, FrozenReplayCodec.decodeAuthority(FrozenReplayCodec.encodeAuthority(scope)));
        org.junit.jupiter.api.Assertions.assertFalse(FrozenReplayCodec.decodeAuthority(
                FrozenReplayCodec.encodeAuthority(scope)).fixedReceiverGeometry());
        var fixedReceiver = scope.withFixedReceiverGeometry();
        assertEquals(fixedReceiver, FrozenReplayCodec.decodeAuthority(FrozenReplayCodec.encodeAuthority(fixedReceiver)));
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

    private static FrozenReplayInput withDatasetIdentity(FrozenReplayInput base, String identity) {
        NetworkSnapshot old = base.network();
        NetworkSnapshot network = new NetworkSnapshot(old.snapshotId(), old.role(), identity,
                old.sourceGeneration(), old.closure(), old.primitives(), old.incomingReferrerWatches());
        TraceRequest request = base.request();
        TraceRequest rebound = new TraceRequest(request.selectedWayKey(), request.selectedRange(),
                request.engine(), request.geometryMode(), request.permissions(), request.budgets(),
                request.evidenceSnapshotId(), request.evidenceContentHash(),
                request.networkSnapshotId(), network.canonicalHash(), request.settingsHash(),
                request.parameterHash(), request.samplerId(), request.configuredSampleStepMeters(),
                request.profileChainage(), request.evidenceResolution(), request.corridorInput());
        return new FrozenReplayInput(rebound, base.evidence(), network, base.options());
    }

    private static Format15Archive benchmarkIntervalArchive(FrozenReplayInput base,
            AlignmentEditPlan plan, String proofOverride) throws IOException {
        String proof = proofOverride == null ? FrozenReplayCodec.partitionProofHash(
                SelectedWayIntervalPartitioner.partition(base.network(), scope(base))) : proofOverride;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x57544952);
            out.writeInt(3);
            byte[][] parts = {FrozenReplayCodec.encode(base), FrozenReplayCodec.encodeAuthority(scope(base))};
            for (byte[] part : parts) {
                out.writeInt(part.length);
                out.write(part);
            }
            out.writeUTF(proof);
            out.writeInt(1);
            byte[] request = FrozenReplayCodec.encodeRequestOnly(base.request());
            out.writeInt(request.length);
            out.write(request);
            out.writeDouble(base.request().profileChainage().sourceOriginGroundMeters());
            out.writeUTF("b".repeat(64));
            out.writeUTF("c".repeat(64));
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(3);
            out.writeInt(0);
            out.writeUTF("d".repeat(64));
            out.writeBoolean(true);
            out.writeUTF(plan.canonicalHash());
        }
        String inputName = FrozenIntervalReplayCodec.ARTIFACT;
        String planName = "private/interval-frozen-edit-plan.bin";
        return new Format15Archive(15, "synthetic", "synthetic", "synthetic", Map.of(
                inputName, Format15Artifact.binary(inputName, bytes.toByteArray()),
                planName, Format15Artifact.binary(planName, FrozenReplayCodec.encodeEditPlan(plan))),
                new ReplayCapability(15, Set.of(), List.of()));
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
