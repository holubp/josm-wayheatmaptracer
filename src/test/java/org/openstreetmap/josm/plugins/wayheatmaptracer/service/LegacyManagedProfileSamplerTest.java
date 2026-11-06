package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacySeedAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

class LegacyManagedProfileSamplerTest {
    @Test void commonNormalMetadataOwnerKeepsOnlyIncrementalManagedPayloadAfterScratchClose() throws Exception {
        var input = managedNormalOwnershipInput();
        long retained = 1024 + 64L * input.sourcePolyline().size() + 16L * input.detectors().size() + 128L * input.mosaics().size();
        long scratch = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBProductionRunner
                .normalPolicyWorkingBytes(input.sourcePolyline().size()) + 128L * (input.mosaics().size() + input.detectors().size());
        var ledger = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger(retained + scratch);
        var parent = ledger.rootOwner();
        try {
            var accounted = invokeNormalMetadataOwner(parent, retained, scratch, CancellationProbe.NONE,
                    () -> input.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 1)));
            var result = (LegacyManagedProfileSampler.Input) accounted.value();
            assertEquals(retained, ledger.currentBytes(), "Only the incremental metadata reservation survives");
            assertEquals(retained + scratch, ledger.peakBytes());
            assertTrue(retained < scratch);
            assertNotSame(input.mosaics(), result.mosaics());
            for (String key : input.mosaics().keySet()) assertSame(input.mosaics().get(key), result.mosaics().get(key),
                    "Borrowed mosaic/argb payload must not be copied or charged again");
            var coLease = parent.retain(result);
            assertEquals(retained, coLease.bytes());
            coLease.close();
            accounted.owner().close();
            assertEquals(0, ledger.currentBytes(), "The actual metadata owner must release before parent cleanup");
            assertFalse(parent.isClosed());
        } finally { parent.close(); }
    }

    @Test void commonNormalMetadataOwnerRefusesTightBudgetBeforeConstructionAndReleasesReservation() throws Exception {
        var input = managedNormalOwnershipInput();
        long retained = 1024 + 64L * input.sourcePolyline().size() + 16L * input.detectors().size() + 128L * input.mosaics().size();
        long scratch = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBProductionRunner
                .normalPolicyWorkingBytes(input.sourcePolyline().size()) + 128L * (input.mosaics().size() + input.detectors().size());
        var ledger = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger(retained + scratch - 1);
        var parent = ledger.rootOwner();
        var constructions = new AtomicInteger();
        try {
            assertThrows(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.ResourceLimitException.class,
                    () -> invokeNormalMetadataOwner(parent, retained, scratch, CancellationProbe.NONE, () -> {
                        constructions.incrementAndGet();
                        return input.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 1));
                    }));
            assertEquals(0, constructions.get());
            assertEquals(0, ledger.currentBytes(), "Failed prepayment must not leave the retained reservation");
            assertFalse(parent.isClosed());
        } finally { parent.close(); }
    }

    @Test void commonNormalMetadataOwnerReleasesAfterConstructionCancellationAndPreservesFactoryFailure() throws Exception {
        var input = managedNormalOwnershipInput();
        long retained = 1024 + 64L * input.sourcePolyline().size() + 16L * input.detectors().size() + 128L * input.mosaics().size();
        long scratch = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBProductionRunner
                .normalPolicyWorkingBytes(input.sourcePolyline().size()) + 128L * (input.mosaics().size() + input.detectors().size());
        var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        var parent = ledger.rootOwner();
        var constructions = new AtomicInteger();
        try {
            assertThrows(CancellationException.class, () -> invokeNormalMetadataOwner(parent, retained, scratch, () -> true, () -> {
                constructions.incrementAndGet();
                return input.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 1));
            }));
            assertEquals(1, constructions.get());
            assertEquals(0, ledger.currentBytes(), "Cancellation after allocation must release both reservations");
            var primary = new IllegalStateException("synthetic-normal-construction-failure");
            var failure = assertThrows(IllegalStateException.class, () -> invokeNormalMetadataOwner(parent, retained, scratch,
                    CancellationProbe.NONE, () -> { throw primary; }));
            assertSame(primary, failure);
            assertEquals(0, ledger.currentBytes(), "Construction failure must close the actual child before outer cleanup");
            assertFalse(parent.isClosed());
        } finally { parent.close(); }
    }

    private static LegacyManagedProfileSampler.Input managedNormalOwnershipInput() {
        var fixture = fixture(COLORS);
        return LegacyManagedProfileSampler.freeze(fixture.set(), fixture.source(), "EPSG:3857", "hot",
                List.of("hot"), IntensitySamplingMode.COLOR_MAPPING, 1.75);
    }

    private static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.Accounted<?> invokeNormalMetadataOwner(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner parent,
            long retained, long scratch, CancellationProbe cancellation, java.util.function.Supplier<?> construct) throws Exception {
        var method = LiveBPreviewService.class.getDeclaredMethod("retainLegacyNormalMetadata",
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner.class,
                long.class, long.class, CancellationProbe.class, java.util.function.Supplier.class);
        method.setAccessible(true);
        try {
            return (org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.Accounted<?>) method.invoke(
                    null, parent, retained, scratch, cancellation, construct);
        } catch (java.lang.reflect.InvocationTargetException wrapper) {
            if (wrapper.getCause() instanceof RuntimeException runtime) throw runtime;
            if (wrapper.getCause() instanceof Error error) throw error;
            throw wrapper;
        }
    }

    @Test void liveNormalResolutionHandlesGeometryFallbackBeforeNativeShapeAndKeepsImmediateMultiDetectorInput() {
        var fixture = fixture(List.of("hot", "blue"));
        var extreme = LegacyManagedProfileSampler.freeze(fixture.set(),
                List.of(new ProjectedPoint(-1e308, 0), new ProjectedPoint(1e308, 0)), "EPSG:3857", "hot",
                List.of("hot"), IntensitySamplingMode.COLOR_MAPPING, 1.75);
        var resolved = extreme.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 1));
        assertEquals(LegacyProfileNormalPolicy.IMMEDIATE_V1, resolved.normalPolicy());
        assertEquals("non-finite-geometry", resolved.normalPolicyFallbackReason());
        assertEquals(List.of(0, 1), resolved.fixedSourceIndices());
        var decoded = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.LegacySourceCodec.decodeManaged(
                org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.LegacySourceCodec.encode(resolved));
        assertEquals(resolved.normalPolicy(), decoded.normalPolicy());
        assertEquals(resolved.normalPolicyFallbackReason(), decoded.normalPolicyFallbackReason());
        assertEquals(resolved.fixedSourceIndices(), decoded.fixedSourceIndices());
        var original = LegacyManagedProfileSampler.freeze(fixture.set(), fixture.source(), "EPSG:3857", "hot",
                List.of("hot", "blue"), IntensitySamplingMode.COLOR_MAPPING, 1.75);
        assertEquals(original, original.withNormalPolicy(LegacyProfileNormalPolicy.IMMEDIATE_V1, List.of()));
    }

    @ParameterizedTest @ValueSource(strings = {"hot", "all-colors-combined"})
    void explicitProtectedPolicyReachesActualManagedAndAggregateSampler(String detector) {
        var fixture = fixture(COLORS);
        var mosaic = fixture.set().require("hot");
        var source = new java.util.ArrayList<EastNorth>();
        for (int i = 0; i < 9; i++) source.add(worldPoint(fixture.projection(),
                mosaic.originWorldPxX() + 100.5 + i * 10,
                mosaic.originWorldPxY() + 256.5 + 8 * Math.exp(-Math.pow(i - 4, 2) / 2.0)));
        var projected = source.stream().map(p -> new ProjectedPoint(p.east(), p.north())).toList();
        var original = LegacyManagedProfileSampler.freeze(fixture.set(), projected, "EPSG:3857", "hot",
                List.of(detector), IntensitySamplingMode.COLOR_MAPPING, 1.75);
        var explicit = original.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 4, 8));
        var sampler = TileHeatmapSampler.numericalOnly();
        var expected = detector.equals("all-colors-combined")
                ? sampler.sampleAggregatedProfiles(fixture.set(), ZOOM, source, fixture.projection(), CancellationProbe.NONE,
                    LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 4, 8))
                : sampler.sampleProfiles(mosaic, source, detector, IntensitySamplingMode.COLOR_MAPPING,
                    fixture.projection(), CancellationProbe.NONE, LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 4, 8));
        var actual = new LegacyManagedProfileSampler().sample(explicit, CancellationProbe.NONE).get(0).profiles();
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) assertEquals(expected.get(i).normalScreen(), actual.get(i).normalScreen(), "profile" + i);
        assertEquals(expected, actual);
        var ordinary = new LegacyManagedProfileSampler().sample(original, CancellationProbe.NONE).get(0).profiles();
        assertTrue(java.util.stream.IntStream.range(0, actual.size()).anyMatch(i ->
                Double.doubleToRawLongBits(actual.get(i).normalScreen().x)
                    != Double.doubleToRawLongBits(ordinary.get(i).normalScreen().x)), "The oracle must exercise changed normals");
        assertEquals(original.sourcePolyline(), explicit.sourcePolyline());
        assertEquals(ordinary.size(), actual.size());
    }

    @ParameterizedTest @ValueSource(strings = {"hot", "all-colors-combined"})
    void admissionUsesFactualReferenceMosaicEvenWhenSummaryParametersDiffer(String detector) {
        var fixture = fixture(COLORS);
        var original = LegacyManagedProfileSampler.freeze(fixture.set(), fixture.source(), "EPSG:3857", "hot",
                List.of(detector), IntensitySamplingMode.COLOR_MAPPING, 1.75);
        var summary = new TileHeatmapSampler.SamplingParameters(ZOOM, 0, 1, .389, 40, 1, 1);
        var input = new LegacyManagedProfileSampler.Input(original.inferenceMode(), original.requestedInferenceZoom(),
                original.requestedValidationZoom(), original.inferenceZoom(), original.validationZoom(), original.tileSize(),
                original.mosaics(), summary, original.validationParameters(), original.sourcePolyline(),
                original.projectionCode(), original.selectedPalette(), original.detectors(), original.intensityMode(),
                original.trackerNormalizationRasterPx());
        var actual = new LegacyManagedProfileSampler().sample(input, CancellationProbe.NONE).get(0).profiles();
        var shape = LegacyManagedProfileSampler.samplingShape(input);
        assertEquals(actual.size(), shape.profiles());
        assertTrue(actual.stream().allMatch(profile -> profile.intensitySamples().size() == shape.samplesPerProfile()));
    }
    @Test void admissionShapeMatchesNativeSamplingAcrossRoundingAndProjectionCases() {
        var fixture = fixture(List.of("hot"));
        var mosaic = fixture.set().require("hot");
        var sampler = TileHeatmapSampler.numericalOnly();
        var parameters = mosaic.parameters();
        var highLatitude = fixture.projection().latlon2eastNorth(new LatLon(70, 0));
        for (var origin : List.of(fixture.sourceEastNorth().get(0), highLatitude)) {
            for (double length : new double[] {0, 8.999999, 9, 9.000001, 1000}) {
                var source = List.of(origin, new EastNorth(origin.east() + length, origin.north()));
                var shape = TileHeatmapSampler.legacySamplingShape(PolylineMath.length(source), source.size(), parameters);
                assertEquals(PolylineMath.resampleBySpacing(source, Math.max(4, parameters.sampleStepMeters())).size(),
                        shape.profiles(), "Preserve the independent pre-existing native resampling convention");
                var profiles = sampler.sampleProfiles(mosaic, source, "hot", IntensitySamplingMode.COLOR_MAPPING,
                        fixture.projection(), CancellationProbe.NONE);
                assertEquals(profiles.size(), shape.profiles());
                assertTrue(profiles.stream().allMatch(profile -> profile.intensitySamples().size() == shape.samplesPerProfile()));
            }
        }
        var source = List.of(highLatitude, highLatitude, highLatitude);
        var shape = TileHeatmapSampler.legacySamplingShape(0, 3, parameters);
        assertEquals(3, shape.profiles());
        assertEquals(3, sampler.sampleProfiles(mosaic, source, "hot", IntensitySamplingMode.COLOR_MAPPING,
                fixture.projection(), CancellationProbe.NONE).size());
        assertThrows(IllegalArgumentException.class,
                () -> TileHeatmapSampler.legacySamplingShape(Double.MAX_VALUE, 2, parameters));
        var ordinary = new TileHeatmapSampler.SamplingParameters(ZOOM, 70, 1, 7.01, 1.56, 6, 1);
        assertEquals(10, TileHeatmapSampler.legacySamplingShape(1740, 86, ordinary).samplesPerProfile(),
                "Native/reference units produce ten samples, not the old native-pixel estimate of148");
    }

    @Test void frozenManagedAdmissionUsesTheSameNativeShapeWithoutChangingSampling() {
        var fixture = fixture(List.of("hot"));
        var frozen = LegacyManagedProfileSampler.freeze(fixture.set(), fixture.source(), "EPSG:3857", "hot",
                List.of("hot"), IntensitySamplingMode.COLOR_MAPPING, 1.75);
        var shape = LegacyManagedProfileSampler.samplingShape(frozen);
        var actual = new LegacyManagedProfileSampler().sample(frozen, CancellationProbe.NONE).get(0).profiles();
        assertEquals(actual.size(), shape.profiles());
        assertTrue(actual.stream().allMatch(profile -> profile.intensitySamples().size() == shape.samplesPerProfile()));
    }
    private static final int ZOOM = 15;
    private static final List<String> COLORS = List.of("hot", "blue", "bluered", "purple", "gray");

    @BeforeAll static void setProjection() {
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test void offlineNumericalSamplerCannotAcquireTiles() {
        var sampler = TileHeatmapSampler.numericalOnly();
        var failure = assertThrows(IllegalStateException.class, () -> sampler.prepare(null,
                List.of(new EastNorth(0, 0), new EastNorth(20, 0)), List.of("hot")));
        assertEquals("Detached Legacy sampling cannot acquire tiles", failure.getMessage());
    }

    @Test void frozenSelectedMosaicMatchesNativeProfilesTrackerAndProjectionAfterSwitch() {
        var fixture = fixture(COLORS);
        var nativeSampler = new TileHeatmapSampler();
        var expected = nativeSampler.sampleProfiles(fixture.set().require("hot"), fixture.sourceEastNorth(),
                "hot", config(IntensitySamplingMode.COLOR_MAPPING));
        var expectedProjected = nativeSampler.projectCandidate(fixture.set().require("hot"),
                List.of(new Point2D.Double(600, 1536))).get(0);
        var frozen = LegacyManagedProfileSampler.freeze(fixture.set(), fixture.source(), "EPSG:3857", "hot",
                List.of("hot", "blue"), IntensitySamplingMode.COLOR_MAPPING, 1.75);
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:4326"));
        try {
            var actual = new LegacyManagedProfileSampler().sample(frozen, CancellationProbe.NONE);
            assertEquals(List.of("hot", "blue"), actual.stream().map(
                    LegacyManagedProfileSampler.DetectorProfiles::detector).toList());
            assertEquals(expected, actual.get(0).profiles());
            var expectedCandidates = new RidgeTracker().track(expected, 1.75);
            var actualCandidates = new LegacySeedAdapter().track(actual.get(0).profiles(),
                    frozen.trackerNormalizationRasterPx());
            assertEquals(expectedCandidates.size(), actualCandidates.size());
            for (int i = 0; i < expectedCandidates.size(); i++) {
                assertEquals(expectedCandidates.get(i).offsetsPx(), actualCandidates.get(i).offsetsPx());
                assertEquals(expectedCandidates.get(i).score(), actualCandidates.get(i).score());
            }
            var actualProjected = new LegacyManagedProfileSampler().project(frozen, "hot",
                    List.of(new RasterPoint(600, 1536))).get(0);
            assertEquals(expectedProjected.east(), actualProjected.east(), 0.0);
            assertEquals(expectedProjected.north(), actualProjected.north(), 0.0);
        } finally {
            ProjectionRegistry.setProjection(fixture.projection());
        }
    }

    @Test void frozenArraysAndMapsAreDefensivelyCopied() {
        var fixture = fixture(List.of("hot"));
        var input = LegacyManagedProfileSampler.freeze(fixture.set(), fixture.source(), "EPSG:3857", "hot",
                List.of("hot"), IntensitySamplingMode.DIRECT_VALUE, 2.25);
        var mosaic = input.mosaics().get("hot@15");
        int originalPixel = mosaic.argb()[0];
        int[] leaked = mosaic.argb();
        leaked[0] ^= 0x00FFFFFF;
        assertEquals(originalPixel, mosaic.argb()[0]);
        assertThrows(UnsupportedOperationException.class, () -> input.mosaics().clear());
        assertEquals(2.25, input.trackerNormalizationRasterPx());
        var direct = new LegacyManagedProfileSampler().sample(input, CancellationProbe.NONE).get(0);
        assertEquals("hot@15", direct.referenceMosaicKey());
        assertFalse(direct.profiles().isEmpty());
    }

    @Test void aggregateUsesAllFiveMatchingMosaicsAndRejectsIncompleteOrMismatchedFrames() {
        var fixture = fixture(COLORS);
        var nativeProfiles = new TileHeatmapSampler().sampleAggregatedProfiles(fixture.set(), ZOOM,
                fixture.sourceEastNorth());
        var input = LegacyManagedProfileSampler.freeze(fixture.set(), fixture.source(), "EPSG:3857", "hot",
                List.of("all-colors-combined"), IntensitySamplingMode.COLOR_MAPPING, 1.0);
        var profiles = new LegacyManagedProfileSampler().sample(input, CancellationProbe.NONE).get(0);
        assertEquals("hot@15", profiles.referenceMosaicKey());
        assertEquals(nativeProfiles, profiles.profiles());
        assertFalse(profiles.profiles().isEmpty());

        var incomplete = fixture(List.of("hot", "blue", "bluered", "purple"));
        var missing = LegacyManagedProfileSampler.freeze(incomplete.set(), incomplete.source(), "EPSG:3857", "hot",
                List.of("all-colors-combined"), IntensitySamplingMode.COLOR_MAPPING, 1.0);
        assertThrows(IllegalStateException.class,
                () -> new LegacyManagedProfileSampler().sample(missing, CancellationProbe.NONE));

        var mismatched = fixture(COLORS, true);
        var wrongFrame = LegacyManagedProfileSampler.freeze(mismatched.set(), mismatched.source(), "EPSG:3857", "hot",
                List.of("all-colors-combined"), IntensitySamplingMode.COLOR_MAPPING, 1.0);
        assertThrows(IllegalStateException.class,
                () -> new LegacyManagedProfileSampler().sample(wrongFrame, CancellationProbe.NONE));
    }

    @Test void directIntensityIsPassedToNativeSamplerAndCancellationIsCooperative() {
        var fixture = fixture(List.of("hot"));
        var input = LegacyManagedProfileSampler.freeze(fixture.set(), fixture.source(), "EPSG:3857", "hot",
                List.of("hot"), IntensitySamplingMode.DIRECT_VALUE, 1.0);
        var nativeProfiles = new TileHeatmapSampler().sampleProfiles(fixture.set().require("hot"),
                fixture.sourceEastNorth(), "hot", config(IntensitySamplingMode.DIRECT_VALUE));
        assertEquals(nativeProfiles, new LegacyManagedProfileSampler().sample(input, CancellationProbe.NONE)
                .get(0).profiles());
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> new LegacyManagedProfileSampler().sample(input,
                () -> checks.incrementAndGet() > 1));
    }

    @Test void projectionCodeAndFrozenIdentityAreRequired() {
        var fixture = fixture(List.of("hot"));
        var invalidProjection = LegacyManagedProfileSampler.freeze(fixture.set(), fixture.source(),
                "invalid-projection-code", "hot", List.of("hot"), IntensitySamplingMode.COLOR_MAPPING, 1.0);
        assertThrows(IllegalArgumentException.class, () -> new LegacyManagedProfileSampler()
                .sample(invalidProjection, CancellationProbe.NONE));
        assertThrows(IllegalArgumentException.class, () -> LegacyManagedProfileSampler.freeze(
                fixture.set(), fixture.source(), "EPSG:3857", "hot", List.of("hot", "hot"),
                IntensitySamplingMode.COLOR_MAPPING, 1.0));
    }

    private static Fixture fixture(List<String> colors) { return fixture(colors, false); }

    private static ManagedHeatmapConfig config(IntensitySamplingMode intensityMode) {
        return new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*",
                AlignmentMode.MOVE_EXISTING_NODES, TrackerMode.LEGACY_V02,
                false, false, false, false, false, false, true, false, false, false,
                18, 4, 3.0, InferenceMode.STABLE_FIXED_SCALE, ZOOM, ZOOM,
                28.0, 6.0, intensityMode, 0L);
    }

    private static Fixture fixture(List<String> colors, boolean mismatch) {
        Projection projection = Projections.getProjectionByCode("EPSG:3857");
        double world = TileHeatmapSampler.TILE_SIZE * Math.pow(2, ZOOM);
        double centerX = (0 + 180) / 360.0 * world;
        double centerY = world / 2.0;
        double originX = Math.floor((centerX - 256) / 512) * 512;
        double originY = Math.floor((centerY - 256) / 512) * 512;
        var source = List.of(worldPoint(projection, originX + 100.5, originY + 256.5),
                worldPoint(projection, originX + 110.5, originY + 256.5));
        var params = new TileHeatmapSampler.SamplingParameters(ZOOM, 0,
                TileHeatmapSampler.metersPerPixel(ZOOM, 0), 28, 6, 56, 12);
        var mosaics = new LinkedHashMap<String, TileHeatmapSampler.TileMosaic>();
        for (int index = 0; index < colors.size(); index++) {
            String color = colors.get(index);
            int[] pixels = new int[512 * 512];
            for (int y = 0; y < 512; y++) {
                int value = Math.max(0, 255 - Math.abs(y - 256) * 12);
                int pixel = switch (color) {
                    case "blue" -> 0xFF000000 | (Math.max(0, value - 160) << 16)
                            | (Math.max(0, value - 80) << 8) | value;
                    case "purple" -> 0xFF000000 | (value << 16) | (Math.max(0, value - 30) << 8) | value;
                    case "gray" -> 0xFF000000 | (value << 16) | (value << 8) | value;
                    case "bluered" -> (y < 256 ? 0xFFFF2020 : 0xFF2020FF);
                    default -> 0xFF000000 | (value << 16) | (Math.max(0, value - 80) << 8)
                            | Math.max(0, value - 160);
                };
                for (int x = 0; x < 512; x++) pixels[y * 512 + x] = pixel;
            }
            var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 512, 512, pixels, 0, 512);
            double x = originX + (mismatch && index == 1 ? 1 : 0);
            var mosaic = new TileHeatmapSampler.TileMosaic(color, ZOOM, x, originY, image,
                    List.of(), Map.of(), params, true, 1.0);
            mosaics.put(color + "@" + ZOOM, mosaic);
        }
        var set = new TileHeatmapSampler.TileMosaicSet(InferenceMode.STABLE_FIXED_SCALE,
                ZOOM, ZOOM, ZOOM, ZOOM, 512, mosaics, params, params);
        return new Fixture(set, source.stream().map(point -> new ProjectedPoint(point.east(), point.north())).toList(),
                source, projection);
    }

    private static EastNorth worldPoint(Projection projection, double x, double y) {
        double scale = TileHeatmapSampler.TILE_SIZE * Math.pow(2, ZOOM);
        double lon = x / scale * 360 - 180;
        double mercator = Math.PI * (1 - 2 * y / scale);
        double lat = Math.toDegrees(Math.atan(Math.sinh(mercator)));
        return projection.latlon2eastNorth(new LatLon(lat, lon));
    }

    private record Fixture(TileHeatmapSampler.TileMosaicSet set, List<ProjectedPoint> source,
            List<EastNorth> sourceEastNorth, Projection projection) { }
}
