package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayCapability;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyBSourceCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyProfileNormalPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyTrackerPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyVisibleProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyTopologyPlanner.Occurrence;

class LegacyNumericalPolicyReceiptTest {
    private static final String SOURCE_HASH = "b".repeat(64);

    @Test void v3TrackerPolicyIsBoundForImmediateProtectedAndFallbackSources() {
        var base = V022FrozenFrameTest.input(V022FrozenFrameTest.frame());
        var selected = v3Source(false, false);
        var immediate = frozenInput(base, selected);
        var immediateReceipt = receipt(immediate);
        String bPolicy = bPolicy(immediate);
        assertTrue(text(immediateReceipt).contains("legacy-then-b-v3/" + LegacyProfileNormalPolicy.IMMEDIATE_V1.id() + "/none/"
                + LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1.id() + "/24.0/none/" + bPolicy),
                text(immediateReceipt));

        var protectedInput = frozenInput(base, v3Source(true, false));
        var protectedReceipt = receipt(protectedInput);
        assertTrue(text(protectedReceipt).contains("legacy-then-b-v3/"
                + LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1.id() + "/none/"
                + LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1.id() + "/24.0/none/" + bPolicy));

        var fallbackInput = frozenInput(base, v3Source(false, true));
        var fallbackReceipt = receipt(fallbackInput);
        assertTrue(text(fallbackReceipt).contains("legacy-then-b-v3/" + LegacyProfileNormalPolicy.IMMEDIATE_V1.id()
                + "/none/legacy-v02/unavailable/"
                + "native-pitch-unavailable/" + bPolicy));

        assertRejectedReceiptMutation(immediate, immediateReceipt,
                LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1.id(),
                LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_V1.id());
        assertRejectedReceiptMutation(protectedInput, protectedReceipt, "/24.0/none/", "/25.0/none/");
        assertRejectedReceiptMutation(fallbackInput, fallbackReceipt,
                "/unavailable/native-pitch-unavailable/", "/unavailable/none/");

        var oldImmediate = frozenInput(base, v1Source());
        var oldV1Receipt = Format15NumericalPolicyReceipt.current(SOURCE_HASH, oldImmediate.canonicalHash(),
                oldImmediate.request().parameterHash(), TrackerMode.LEGACY_THEN_B, TrackerMode.LEGACY_THEN_B);
        assertArrayEquals(oldV1Receipt.bytes(), receipt(oldImmediate).bytes(), "V1 receipt bytes remain unchanged");

        var oldProtected = frozenInput(base, v1Source().withNormalPolicy(
                LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 1)));
        var oldV2Base = Format15NumericalPolicyReceipt.current(SOURCE_HASH, oldProtected.canonicalHash(),
                oldProtected.request().parameterHash(), TrackerMode.LEGACY_THEN_B, TrackerMode.LEGACY_THEN_B);
        String oldV2Text = text(oldV2Base).replace("\"inferencePolicy\":\"legacy-then-b-v1/" + bPolicy(oldProtected) + "\"",
                "\"inferencePolicy\":\"legacy-then-b-v2/"
                        + LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1.id() + "/" + bPolicy(oldProtected) + "\"");
        assertEquals(oldV2Text, text(receipt(oldProtected)), "V2 receipt layout remains unchanged");
    }

    @Test void pairedPoliciesRoundTripAndAttestUnderEitherNormalPolicy() {
        var base = V022FrozenFrameTest.input(V022FrozenFrameTest.frame());
        for (var policy : List.of(LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V2,
                LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V3,
                LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V4)) {
        for (boolean protectedNormals : List.of(false, true)) {
            var selected = v3Source(protectedNormals, false).withTrackerPolicy(
                    policy,
                    OptionalDouble.of(24.0), "");
            var decoded = LegacySourceCodec.decodeVisible(LegacySourceCodec.encode(selected));
            var frozen = frozenInput(base, decoded);
            assertEquals(policy, frozen.legacyB().trackerPolicy());
            assertEquals(24.0, frozen.legacyB().nativeSourcePitchRasterPx().orElseThrow());
            assertEquals(6.0, decoded.options().trackerNormalizationRasterPx());
            var bound = receipt(frozen);
            assertTrue(text(bound).contains("/" + selected.trackerPolicy().id() + "/24.0/none/"));
            assertRejectedReceiptMutation(frozen, bound, selected.trackerPolicy().id(),
                    LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1.id());
        }
        }
    }

    private static void assertRejectedReceiptMutation(FrozenReplayInput input, Format15Artifact receipt,
            String old, String replacement) {
        String changed = text(receipt).replace(old, replacement);
        assertNotEquals(text(receipt), changed, "Mutation must target the bound V3 field");
        var tampered = Format15Artifact.text(Format15NumericalPolicyReceipt.ARTIFACT, changed);
        assertThrows(ReplayMismatchException.class, () -> Format15NumericalPolicyReceipt.validate(
                archive(input, tampered), input, input.canonicalHash()));
    }

    private static Format15Artifact receipt(FrozenReplayInput input) {
        var base = Format15NumericalPolicyReceipt.current(SOURCE_HASH, input.canonicalHash(),
                input.request().parameterHash(), TrackerMode.LEGACY_THEN_B, TrackerMode.LEGACY_THEN_B);
        return Format15NumericalPolicyReceipt.withLegacySourcePolicy(base, TrackerMode.LEGACY_THEN_B,
                input.legacyB());
    }

    private static String bPolicy(FrozenReplayInput input) {
        String base = text(Format15NumericalPolicyReceipt.current(SOURCE_HASH, input.canonicalHash(),
                input.request().parameterHash(), TrackerMode.LEGACY_THEN_B, TrackerMode.LEGACY_THEN_B));
        String marker = "\"inferencePolicy\":\"legacy-then-b-v1/";
        int start = base.indexOf(marker);
        assertTrue(start >= 0, "Base receipt must retain the historical Legacy-then-B policy");
        start += marker.length();
        int end = base.indexOf('"', start);
        return base.substring(start, end);
    }

    private static Format15Archive archive(FrozenReplayInput input, Format15Artifact receipt) {
        return new Format15Archive(15, "synthetic", SOURCE_HASH, input.request().parameterHash(),
                Map.of(Format15NumericalPolicyReceipt.ARTIFACT, receipt),
                new ReplayCapability(15, Set.of(), List.of()));
    }

    private static FrozenReplayInput frozenInput(FrozenReplayInput base, LegacyVisibleProfileSampler.Input source) {
        var legacy = FrozenLegacyBInput.visible(source, List.of(
                new Occurrence(0, false, false), new Occurrence(1, false, false)), List.of(), false, 0);
        var old = base.request();
        var request = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest(
                old.selectedWayKey(), old.selectedRange(), TrackerMode.LEGACY_THEN_B, old.geometryMode(),
                old.permissions(), old.budgets(), old.evidenceSnapshotId(), old.evidenceContentHash(),
                old.networkSnapshotId(), old.networkContentHash(), old.settingsHash(), old.parameterHash(),
                old.samplerId(), old.configuredSampleStepMeters(), old.profileChainage(), old.evidenceResolution(),
                old.corridorInput());
        return new FrozenReplayInput(request, base.evidence(), base.network(), base.options(),
                base.planSearchRadiusMeters(), legacy);
    }

    private static LegacyVisibleProfileSampler.Input v1Source() {
        return source(OptionalDouble.empty()).input();
    }

    private static LegacyVisibleProfileSampler.Input v3Source(boolean protectedNormals, boolean fallback) {
        var source = source(fallback ? OptionalDouble.empty() : OptionalDouble.of(4.0)).input();
        if (fallback) return LegacyBSourceCapture.withLiveTrackerPolicy(source);
        source = source.withTrackerPolicy(LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1,
                OptionalDouble.of(24.0), "");
        return protectedNormals ? source.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1,
                List.of(0, 1)) : source;
    }

    private static Source source(OptionalDouble nativePitchMeters) {
        int[] pixels = new int[12];
        var raster = new LiveBPreviewService.VisibleRaster(4, 3, pixels, 0, 0, 4.0 / 6.0, 0.5,
                1, 1, nativePitchMeters, "receipt-synthetic", "EPSG:3857");
        var anchors = List.of(
                new LegacyVisibleProfileSampler.Anchor(new ProjectedPoint(.5, 0),
                        new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(3, 3), 0),
                new LegacyVisibleProfileSampler.Anchor(new ProjectedPoint(2.5, -2),
                        new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(15, 15), 10));
        var input = new LegacyVisibleProfileSampler.Input(raster, anchors, List.of("hot"),
                new LegacyVisibleProfileSampler.Options(5, 1, 6, org.openstreetmap.josm.plugins.wayheatmaptracer.model
                        .IntensitySamplingMode.COLOR_MAPPING));
        return new Source(input);
    }

    private static String text(Format15Artifact artifact) {
        return new String(artifact.bytes(), StandardCharsets.UTF_8);
    }

    private record Source(LegacyVisibleProfileSampler.Input input) { }
}
