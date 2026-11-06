package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyManagedProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyTrackerPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyVisibleProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

class LegacySourceCodecTest {
    @Test void visibleSourceRoundTripsPixelsIncludingTransparentRgbAndOrderedSettings() {
        var original = visibleInput();
        byte[] bytes = LegacySourceCodec.encode(original);
        var decoded = LegacySourceCodec.decodeVisible(bytes);

        assertEquals(original.anchors(), decoded.anchors());
        assertEquals(original.detectors(), decoded.detectors());
        assertEquals(original.options(), decoded.options());
        assertArrayEquals(original.raster().argb(), decoded.raster().argb());
        assertEquals(original.anchors(), decoded.anchors());
        assertEquals(original.detectors(), decoded.detectors());
        assertEquals(0x00123456, decoded.raster().argb()[0]);
        assertEquals("EPSG:3857", decoded.raster().projectionCode());
        assertTrue(bytes.length <= Format15Safety.MAX_ARTIFACT_BYTES);
    }

    @Test void managedSourceRoundTripsInsertionOrderAllMosaicFieldsAndDefensivePixels() {
        var original = managedInput();
        byte[] bytes = LegacySourceCodec.encode(original);
        var decoded = LegacySourceCodec.decodeManaged(bytes);

        assertEquals(original.inferenceMode(), decoded.inferenceMode());
        assertEquals(original.requestedInferenceZoom(), decoded.requestedInferenceZoom());
        assertEquals(original.requestedValidationZoom(), decoded.requestedValidationZoom());
        assertEquals(original.inferenceZoom(), decoded.inferenceZoom());
        assertEquals(original.validationZoom(), decoded.validationZoom());
        assertEquals(original.tileSize(), decoded.tileSize());
        assertEquals(new java.util.ArrayList<>(original.mosaics().keySet()),
                new java.util.ArrayList<>(decoded.mosaics().keySet()));
        assertEquals(original.sourcePolyline(), decoded.sourcePolyline());
        assertEquals(original.projectionCode(), decoded.projectionCode());
        assertEquals(original.selectedPalette(), decoded.selectedPalette());
        assertEquals(original.detectors(), decoded.detectors());
        assertEquals(original.intensityMode(), decoded.intensityMode());
        assertEquals(original.trackerNormalizationRasterPx(), decoded.trackerNormalizationRasterPx());
        for (String key : original.mosaics().keySet()) {
            var expected = original.mosaics().get(key);
            var actual = decoded.mosaics().get(key);
            assertEquals(expected.color(), actual.color());
            assertEquals(expected.zoom(), actual.zoom());
            assertEquals(expected.originWorldPxX(), actual.originWorldPxX());
            assertEquals(expected.originWorldPxY(), actual.originWorldPxY());
            assertEquals(expected.parameters(), actual.parameters());
            assertEquals(expected.stableInferenceRaster(), actual.stableInferenceRaster());
            assertEquals(expected.virtualRasterScale(), actual.virtualRasterScale());
            assertArrayEquals(expected.argb(), actual.argb());
        }
        assertEquals(0x00123456, decoded.mosaics().get("hot@15").argb()[0]);
        assertTrue(bytes.length <= Format15Safety.MAX_ARTIFACT_BYTES);
    }

    @Test void rejectsUnknownTruncatedTrailingNonfiniteOversizedAndOverBudgetInputs() {
        byte[] valid = LegacySourceCodec.encode(visibleInput());
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(new byte[0]));
        byte[] unknown = valid.clone();
        ByteBuffer.wrap(unknown).putInt(0, 0);
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(unknown));
        assertThrows(IllegalArgumentException.class,
                () -> LegacySourceCodec.decodeVisible(java.util.Arrays.copyOf(valid, valid.length - 1)));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(trailing));
        byte[] nonfinite = valid.clone();
        ByteBuffer.wrap(nonfinite).putDouble(17, Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(nonfinite));
        byte[] oversizedDimension = valid.clone();
        ByteBuffer.wrap(oversizedDimension).putInt(9, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(oversizedDimension));
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(
                new byte[Format15Safety.MAX_ARTIFACT_BYTES + 1]));
    }

    @Test void codecRejectsPrivateMetadataAndPreservesStableDetectorAndMosaicOrder() {
        var unsafe = new LegacyVisibleProfileSampler.Input(
                visibleInput().raster(), visibleInput().anchors(), List.of("hot"),
                visibleInput().options());
        var privateRaster = new LiveBPreviewService.VisibleRaster(unsafe.raster().width(), unsafe.raster().height(),
                unsafe.raster().argb(), unsafe.raster().minimumEast(), unsafe.raster().minimumNorth(),
                unsafe.raster().maximumEast(), unsafe.raster().maximumNorth(),
                unsafe.raster().projectionUnitsPerViewPixel(), unsafe.raster().groundMetersPerViewPixel(),
                OptionalDouble.empty(), "https://example.invalid/?signature=private", "EPSG:3857");
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.encode(
                new LegacyVisibleProfileSampler.Input(privateRaster, unsafe.anchors(), unsafe.detectors(),
                        unsafe.options())));
        assertEquals(List.of("all-colors-combined", "hot", "blue"), managedInput().detectors());
        assertEquals(List.of("hot@15", "blue@15"),
                new java.util.ArrayList<>(managedInput().mosaics().keySet()));
    }

    @Test void protectedNormalPolicyRoundTripsBothSourceKindsWithoutChangingHistoricalBytes() throws Exception {
        byte[] visibleV1 = LegacySourceCodec.encode(visibleInput());
        byte[] managedV1 = LegacySourceCodec.encode(managedInput());
        assertEquals(1, ByteBuffer.wrap(visibleV1).getInt(4));
        assertEquals(1, ByteBuffer.wrap(managedV1).getInt(4));
        assertArrayEquals(visibleV1, LegacySourceCodec.encode(LegacySourceCodec.decodeVisible(visibleV1)));
        assertArrayEquals(managedV1, LegacySourceCodec.encode(LegacySourceCodec.decodeManaged(managedV1)));
        byte[] visibleV2 = protectedSource(visibleV1, "legacy-protected-secant3-v1", List.of(0, 1));
        byte[] managedV2 = protectedSource(managedV1, "legacy-protected-secant3-v1", List.of(0, 1));
        assertArrayEquals(visibleV2, LegacySourceCodec.encode(LegacySourceCodec.decodeVisible(visibleV2)));
        assertArrayEquals(managedV2, LegacySourceCodec.encode(LegacySourceCodec.decodeManaged(managedV2)));
    }

    @Test void trackerPolicyV3RoundTripsPitchAndOldVersionsRemainOriginal() {
        assertEquals(24.0, policyVisibleInput().factualNativeSourcePitchRasterPx().orElseThrow());
        var visible = policyVisibleInput().withTrackerPolicy(LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_V1,
                OptionalDouble.of(24.0), "");
        var visibleBytes = LegacySourceCodec.encode(visible);
        assertEquals(3, ByteBuffer.wrap(visibleBytes).getInt(4));
        var decodedVisible = LegacySourceCodec.decodeVisible(visibleBytes);
        assertEquals(LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_V1, decodedVisible.trackerPolicy());
        assertEquals(24.0, decodedVisible.nativeSourcePitchRasterPx().orElseThrow());
        assertEquals(6.0, decodedVisible.options().trackerNormalizationRasterPx());
        var frozenVisible = FrozenLegacyBInput.visible(visible, List.of(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid
                        .LegacyTopologyPlanner.Occurrence(0, false, false),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid
                        .LegacyTopologyPlanner.Occurrence(1, false, false)), List.of(), false, 0);
        assertEquals(LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_V1, frozenVisible.trackerPolicy());
        assertEquals(24.0, frozenVisible.nativeSourcePitchRasterPx().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> policyVisibleInput().withTrackerPolicy(
                LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_V1, OptionalDouble.of(25.0), ""));

        var managed = managedInput().withTrackerPolicy(LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_V1,
                OptionalDouble.of(6.0), "");
        var managedBytes = LegacySourceCodec.encode(managed);
        assertEquals(3, ByteBuffer.wrap(managedBytes).getInt(4));
        var decodedManaged = LegacySourceCodec.decodeManaged(managedBytes);
        assertEquals(LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_V1, decodedManaged.trackerPolicy());
        assertEquals(6.0, decodedManaged.nativeSourcePitchRasterPx().orElseThrow());

        var withValidationMosaic = new LinkedHashMap<>(managedInput().mosaics());
        var validation = managedInput().validationParameters();
        withValidationMosaic.put("hot@13", new LegacyManagedProfileSampler.Mosaic("hot", 13, 1000, 2000,
                2, 2, new int[] {1, 2, 3, 4}, validation, false, 900.0));
        var managedWithValidation = new LegacyManagedProfileSampler.Input(managedInput().inferenceMode(),
                managedInput().requestedInferenceZoom(), managedInput().requestedValidationZoom(),
                managedInput().inferenceZoom(), managedInput().validationZoom(), managedInput().tileSize(),
                withValidationMosaic, managedInput().inferenceParameters(), validation,
                managedInput().sourcePolyline(), managedInput().projectionCode(), managedInput().selectedPalette(),
                managedInput().detectors(), managedInput().intensityMode(), managedInput().trackerNormalizationRasterPx());
        assertEquals(6.0, managedWithValidation.nativeSourcePitchRasterPx().orElseThrow(),
                "Pitch comes from the selected inference mosaic, not the validation zoom");

        var oldVisible = LegacySourceCodec.decodeVisible(LegacySourceCodec.encode(visibleInput()));
        var oldManaged = LegacySourceCodec.decodeManaged(LegacySourceCodec.encode(managedInput()));
        assertEquals(LegacyTrackerPolicy.ORIGINAL_V02, oldVisible.trackerPolicy());
        assertTrue(oldVisible.nativeSourcePitchRasterPx().isEmpty());
        assertEquals(LegacyTrackerPolicy.ORIGINAL_V02, oldManaged.trackerPolicy());
        assertEquals(6.0, oldManaged.nativeSourcePitchRasterPx().orElseThrow());
    }

    @Test void missingPitchFallsBackToOriginalAndV3DecoderRejectsUnknownOrNonfinitePolicyData() {
        var old = policyVisibleInput();
        var unknownRaster = new LiveBPreviewService.VisibleRaster(old.raster().width(), old.raster().height(),
                old.raster().argb(), old.raster().minimumEast(), old.raster().minimumNorth(),
                old.raster().maximumEast(), old.raster().maximumNorth(), old.raster().projectionUnitsPerViewPixel(),
                old.raster().groundMetersPerViewPixel(), OptionalDouble.empty(), old.raster().sourceIdentity(),
                old.raster().projectionCode());
        var unknown = new LegacyVisibleProfileSampler.Input(unknownRaster, old.anchors(), old.detectors(),
                new LegacyVisibleProfileSampler.Options(5, 1, 6.0, IntensitySamplingMode.COLOR_MAPPING));
        var fallback = org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyBSourceCapture
                .withLiveTrackerPolicy(unknown);
        assertEquals(LegacyTrackerPolicy.ORIGINAL_V02, fallback.trackerPolicy());
        assertTrue(fallback.nativeSourcePitchRasterPx().isEmpty());
        assertEquals("native-pitch-unavailable", fallback.trackerPolicyFallbackReason());
        var fallbackBytes = LegacySourceCodec.encode(fallback);
        assertEquals(3, ByteBuffer.wrap(fallbackBytes).getInt(4));
        var frozenFallback = LegacySourceCodec.decodeVisible(fallbackBytes);
        assertEquals(LegacyTrackerPolicy.ORIGINAL_V02, frozenFallback.trackerPolicy());
        assertTrue(frozenFallback.nativeSourcePitchRasterPx().isEmpty());
        assertEquals("native-pitch-unavailable", frozenFallback.trackerPolicyFallbackReason());

        byte[] valid = LegacySourceCodec.encode(policyVisibleInput().withTrackerPolicy(
                LegacyTrackerPolicy.NATIVE_RECIPROCAL_SUPPORT_V1, OptionalDouble.of(24.0), ""));
        byte[] unknownBytes = valid.clone();
        int idLength = ByteBuffer.wrap(unknownBytes).getInt(12);
        assertTrue(idLength > 0);
        java.util.Arrays.fill(unknownBytes, 16, 16 + idLength, (byte) 'x');
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(unknownBytes));

        byte[] nonfinite = valid.clone();
        int pitchOffset = 17 + idLength;
        ByteBuffer.wrap(nonfinite).putDouble(pitchOffset, Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(nonfinite));
        byte[] mismatchedPitch = valid.clone();
        ByteBuffer.wrap(mismatchedPitch).putDouble(pitchOffset, 25.0);
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(mismatchedPitch),
                "A V3 pitch claim must agree with captured visible raster metadata");
    }

    @Test void protectedNormalPolicyRejectsUnknownMissingDuplicatedAndWrongKindMetadata() throws Exception {
        byte[] original = LegacySourceCodec.encode(visibleInput());
        for (byte[] invalid : List.of(
                protectedSource(original, "unknown-policy", List.of(0, 1)),
                protectedSource(original, "legacy-protected-secant3-v1", List.of(0)),
                protectedSource(original, "legacy-protected-secant3-v1", List.of(0, 0, 1)),
                protectedSource(original, "legacy-protected-secant3-v1", List.of(0, 2)))) {
            assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(invalid));
        }
        byte[] valid = protectedSource(original, "legacy-protected-secant3-v1", List.of(0, 1));
        assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeManaged(valid));
        assertThrows(IllegalArgumentException.class,
                () -> LegacySourceCodec.decodeVisible(java.util.Arrays.copyOf(valid, valid.length - 1)));
    }

    @Test void explicitPolicyScratchIsReservedBeforeUntrustedPolicyTextIsMaterialized() throws Exception {
        byte[] original = LegacySourceCodec.encode(visibleInput());
        byte[] malformed = java.util.Arrays.copyOf(original, original.length + 4);
        ByteBuffer.wrap(malformed).putInt(4, 2).putInt(original.length, Integer.MAX_VALUE);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var limit = new IllegalArgumentException("synthetic-policy-scratch-limit");
        var failure = assertThrows(IllegalArgumentException.class, () -> LegacySourceCodec.decodeVisible(malformed, bytes -> {
            if (calls.incrementAndGet() == 2) throw limit;
        }));
        assertSame(limit, failure, "New policy parsing must reserve scratch before even its text allocation");
        assertEquals(2, calls.get());
    }

    @Test void explicitImmediateFallbackRoundTripsItsActualReasonAndOriginalAuthority() {
        var old = visibleInput();
        var point = old.anchors().get(0);
        var fallback = new LegacyVisibleProfileSampler.Input(old.raster(), List.of(point, point), old.detectors(), old.options())
                .withNormalPolicy(org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1,
                    List.of(0, 1));
        byte[] encoded = LegacySourceCodec.encode(fallback);
        assertEquals(2, ByteBuffer.wrap(encoded).getInt(4));
        var decoded = LegacySourceCodec.decodeVisible(encoded);
        assertEquals(fallback.normalPolicy(), decoded.normalPolicy());
        assertEquals("degenerate-geometry", decoded.normalPolicyFallbackReason());
        assertEquals(List.of(0, 1), decoded.fixedSourceIndices());
        assertArrayEquals(encoded, LegacySourceCodec.encode(decoded));
    }

    private static byte[] protectedSource(byte[] original, String policy, List<Integer> fixed) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        byte[] header = original.clone();
        ByteBuffer.wrap(header).putInt(4, 2);
        bytes.write(header);
        try (var out = new java.io.DataOutputStream(bytes)) {
            byte[] text = policy.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(text.length);
            out.write(text);
            out.writeInt(fixed.size());
            for (int index : fixed) out.writeInt(index);
            out.writeBoolean(false);
        }
        return bytes.toByteArray();
    }

    private static LegacyVisibleProfileSampler.Input visibleInput() {
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

    private static LegacyVisibleProfileSampler.Input policyVisibleInput() {
        var old = visibleInput();
        var captured = old.raster();
        var raster = new LiveBPreviewService.VisibleRaster(captured.width(), captured.height(), captured.argb(),
                captured.minimumEast(), captured.minimumNorth(), captured.maximumEast(), captured.maximumNorth(),
                captured.projectionUnitsPerViewPixel(), captured.groundMetersPerViewPixel(), OptionalDouble.of(4.0),
                captured.sourceIdentity(), captured.projectionCode());
        return new LegacyVisibleProfileSampler.Input(raster, old.anchors(), old.detectors(),
                new LegacyVisibleProfileSampler.Options(5, 1, 6.0, IntensitySamplingMode.COLOR_MAPPING));
    }

    private static LegacyManagedProfileSampler.Input managedInput() {
        var params = new TileHeatmapSampler.SamplingParameters(15, 49.0, .5, 28, 6, 56, 12);
        var mosaics = new LinkedHashMap<String, LegacyManagedProfileSampler.Mosaic>();
        mosaics.put("hot@15", new LegacyManagedProfileSampler.Mosaic("hot", 15, 1000, 2000,
                2, 2, new int[] {0x00123456, 0xFFFFFFFF, 0xFF010203, 0xFF040506},
                params, true, 6));
        mosaics.put("blue@15", new LegacyManagedProfileSampler.Mosaic("blue", 15, 1000, 2000,
                2, 2, new int[] {0x00010203, 0xFFABCDEF, 0xFF112233, 0xFF445566},
                params, true, 6));
        return new LegacyManagedProfileSampler.Input(InferenceMode.STABLE_FIXED_SCALE, 15, 13, 15, 13,
                512, mosaics, params,
                new TileHeatmapSampler.SamplingParameters(13, 49.0, 2, 28, 6, 14, 3),
                List.of(new ProjectedPoint(1, 2), new ProjectedPoint(3, 4)), "EPSG:3857", "hot",
                List.of("all-colors-combined", "hot", "blue"), IntensitySamplingMode.COLOR_MAPPING, 1.75);
    }
}
