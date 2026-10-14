package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;

class OrdinaryActionBenchmarkObserverTest {
    @Test
    void normalActionDoesNotEvaluateBenchmarkGeometry() {
        String key = "wayheatmaptracer.benchmark.receipt";
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertDoesNotThrow(() -> OrdinaryActionBenchmarkObserver.previewReady("single", null,
                    () -> { throw new AssertionError("Normal action must not inspect benchmark geometry"); }));
            assertDoesNotThrow(() -> OrdinaryActionBenchmarkObserver.previewReadyInterval(null,
                    () -> { throw new AssertionError("Normal action must not inspect interval composition"); }));
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @Test
    void planlessProductionPreviewPublishesNativeEvidenceAndStillFailsRequiredPlanGate(@TempDir Path directory)
            throws Exception {
        publishPlanlessPreview(directory, false);
    }

    private static void publishPlanlessPreview(Path directory, boolean showWindow) throws Exception {
        Map<String, String> properties = Map.of("receipt", directory.resolve("publication.json").toString(),
                "nonce", "native-observer-test", "case", "N1", "version", "candidate");
        Map<String, String> previous = new java.util.HashMap<>();
        properties.forEach((key, value) -> {
            String name = "wayheatmaptracer.benchmark." + key;
            previous.put(name, System.getProperty(name)); System.setProperty(name, value);
        });
        javax.swing.JDialog[] previewWindow = {null};
        try {
            NativeFixture fixture = planlessFixture();
            var assessment = fixture.assessment();
            assertTrue(assessment.plan().isEmpty()); assertFalse(assessment.applyAvailable());
            var owner = fixture.computed(); var batch = owner.intervalBatch();
            var input = new FrozenReplayInput(owner.request(), owner.evidence(), owner.captured().network(), owner.options());
            DiagnosticsRegistry.setLastModernBundle(Format15ProductionBundleFactory.withCurrentNumericalPolicy(
                    Format15ProductionBundleFactory.createLiveIntervals("native-planless-observer", batch, assessment,
                            Map.of(), new Format15ProductionBundleFactory.ManagedTileSourceReceipt(0, 15, "a".repeat(64)),
                            Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW, null, null),
                    input, input.request().engine(), batch.runs().stream().map(run -> run.result().inference()).toList()));
            var entry = OrdinaryActionBenchmarkObserver.class.getDeclaredMethod("previewReadyInterval",
                    LiveBPreviewService.Computed.class, java.util.function.Supplier.class);
            entry.setAccessible(true);
            OrdinaryActionBenchmarkObserver.actionStarted();
            var clock = OrdinaryActionBenchmarkObserver.class.getDeclaredField("startedNanos"); clock.setAccessible(true);
            long started = clock.getLong(null); AtomicLong suppliedAt = new AtomicLong();
            SwingUtilities.invokeAndWait(() -> {
                if (showWindow) {
                    var dialog = new javax.swing.JDialog((java.awt.Frame) null, "Read-only interval test", false);
                    previewWindow[0] = dialog;
                    dialog.add(new javax.swing.JLabel("No production route; Apply unavailable"));
                    dialog.pack(); dialog.setVisible(true);
                }
                assertDoesNotThrow(() -> entry.invoke(null, owner,
                        (java.util.function.Supplier<FixedIntervalEditPlanComposer.Assessment>) () -> {
                            if (showWindow) assertTrue(previewWindow[0].isShowing());
                            suppliedAt.set(System.nanoTime()); return assessment;
                        }));
                if (showWindow) {
                    assertTrue(previewWindow[0].isShowing(), "Observation preserves the visible preview");
                    assertFalse(previewWindow[0].isModal());
                }
            });
            Path receipt = directory.resolve("publication.json");
            String json = Files.readString(receipt);
            assertTrue(json.contains("\"planAvailable\":false")); assertTrue(json.contains("\"applyAvailable\":false"));
            assertTrue(json.contains("\"finalGeometrySha256\":\"" + expectedGeometryHash(assessment.selectedWayPreview()) + "\""));
            var matcher = java.util.regex.Pattern.compile("\"totalNanos\":([0-9]+)").matcher(json); assertTrue(matcher.find());
            assertTrue(started + Long.parseLong(matcher.group(1)) <= suppliedAt.get(), "clock stops before observation/export");
            Path archivePath = directory.resolve("publication.json.format15.zip");
            var archive = Format15ArchiveReader.read(archivePath);
            assertFalse(archive.artifact("private/interval-frozen-edit-plan.bin").isPresent());
            String index = new String(archive.artifact("interval-production.json").orElseThrow().bytes(), StandardCharsets.UTF_8);
            assertTrue(index.contains("\"status\":\"PREVIEW\"")); assertTrue(index.contains("\"applyAvailable\":false"));
            assertTrue(index.contains("\"reason\":\"NO_PRODUCTION_ROUTE\""));
            assertTrue(archive.artifact("private/interval-composed-preview.json").isPresent());
            assertThrows(IllegalArgumentException.class,
                    () -> new FixedIntervalEditPlanComposer().compose(batch, Map.of(0, 0)),
                    "An explicit choice for an empty route list must remain invalid");
            assertTrue(index.contains("\"chosenRouteIndex\":-1"));
            Map<String, Format15Artifact> forgedArtifacts = new java.util.LinkedHashMap<>(archive.artifacts());
            forgedArtifacts.put("interval-production.json", Format15Artifact.text("interval-production.json",
                    index.replace("\"chosenRouteIndex\":-1", "\"chosenRouteIndex\":0")));
            Path forgedPath = directory.resolve("forged-empty-route-choice.zip");
            Format15BundleWriter.write(new Format15Bundle(archive.buildIdentity(), archive.sourceIdentityHash(),
                    archive.parameterHash(), forgedArtifacts), forgedPath);
            var forgedArchive = Format15ArchiveReader.read(forgedPath);
            var forgedChoice = assertThrows(ReplayMismatchException.class,
                    () -> Format15ReplayRunner.replayIntervals(forgedArchive,
                            forgedArchive.sourceIdentityHash(), forgedArchive.parameterHash()));
            assertEquals("strict-interval-index-choice-mismatch", forgedChoice.getMessage());
            byte[] invalidChoiceWire = archive.artifact("private/frozen-interval-input.bin").orElseThrow().bytes();
            ByteBuffer wire = ByteBuffer.wrap(invalidChoiceWire);
            assertEquals(0x57544952, wire.getInt()); assertEquals(3, wire.getInt());
            skipPart(wire); skipPart(wire); skipText(wire);
            assertTrue(wire.getInt() > 0); skipPart(wire); wire.getDouble(); skipText(wire); skipText(wire);
            assertEquals(0, wire.getInt(), "This actual run has no production route");
            int choiceOffset = wire.position(); assertEquals(0, wire.getInt());
            wire.putInt(choiceOffset, 1); // An explicitly forged wire choice must not be repaired.
            forgedArtifacts = new java.util.LinkedHashMap<>(archive.artifacts());
            forgedArtifacts.put("private/frozen-interval-input.bin", Format15Artifact.binary(
                    "private/frozen-interval-input.bin", invalidChoiceWire));
            Path forgedWirePath = directory.resolve("forged-empty-route-wire-choice.zip");
            Format15BundleWriter.write(new Format15Bundle(archive.buildIdentity(), archive.sourceIdentityHash(),
                    archive.parameterHash(), forgedArtifacts), forgedWirePath);
            var forgedWireArchive = Format15ArchiveReader.read(forgedWirePath);
            var forgedWireChoice = assertThrows(ReplayMismatchException.class,
                    () -> Format15ReplayRunner.replayIntervals(forgedWireArchive,
                            forgedWireArchive.sourceIdentityHash(), forgedWireArchive.parameterHash()));
            assertEquals("strict-interval-replay-input-malformed", forgedWireChoice.getMessage());
            var replay = Format15ReplayRunner.replayIntervals(archive, archive.sourceIdentityHash(), archive.parameterHash());
            assertEquals("MATCH", replay.outputComponentStatus()); assertTrue(replay.assessment().plan().isEmpty());
            assertEquals(assessment.selectedWayPreview(), replay.assessment().selectedWayPreview());
            var rejected = assertThrows(IllegalStateException.class, () -> BenchmarkIntervalDecisionComparison
                    .verifyNativePublication(archive, input, true, fixture.dataSet(), 7.01));
            assertEquals("Native interval edit plan is required", rejected.getMessage());
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (previewWindow[0] != null) previewWindow[0].dispose(); });
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }

    private static void skipPart(ByteBuffer wire) {
        int length = wire.getInt(); wire.position(wire.position() + length);
    }

    private static void skipText(ByteBuffer wire) {
        int length = Short.toUnsignedInt(wire.getShort()); wire.position(wire.position() + length);
    }

    /** Isolated real Swing verification of the production observation seam, without starting JOSM. */
    public static final class PlanlessWindowProcess {
        public static void main(String[] args) {
            try {
                if (java.awt.GraphicsEnvironment.isHeadless()) throw new AssertionError("A display is required");
                publishPlanlessPreview(Path.of(args[0]), true);
                System.out.println("planless-observation-visible-modeless-native-replay-and-required-plan-rejection: PASS");
                System.exit(0);
            } catch (Throwable failure) {
                System.err.println("planless-observation-check-failed: " + failure.getClass().getSimpleName());
                System.exit(1);
            }
        }
    }

    private record NativeFixture(DataSet dataSet, LiveBPreviewService.Computed computed,
            FixedIntervalEditPlanComposer.Assessment assessment) { }

    private static NativeFixture planlessFixture() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences()); ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        DataSet data = new DataSet(); List<Node> nodes = new ArrayList<>();
        double degrees = 180.0 / Math.PI / 6_378_137.0;
        for (int i = 0; i < 9; i++) {
            Node node = new Node(new LatLon(0, degrees * 10 * i)); node.setOsmId(i + 1, 1); node.setModified(false);
            data.addPrimitive(node); nodes.add(node);
        }
        Way selected = new Way(); selected.setNodes(nodes); selected.setOsmId(20, 1); selected.put("highway", "path");
        selected.setModified(false); data.addPrimitive(selected);
        Node south = new Node(new LatLon(-40 * degrees, 0)), north = new Node(new LatLon(40 * degrees, 0));
        south.setOsmId(21, 1); north.setOsmId(22, 1); south.setModified(false); north.setModified(false);
        data.addPrimitive(south); data.addPrimitive(north);
        Way receiver = new Way(); receiver.setNodes(List.of(south, nodes.get(0), north)); receiver.setOsmId(23, 1);
        receiver.put("highway", "residential"); receiver.setModified(false); data.addPrimitive(receiver); data.setSelected(selected);
        var config = new ManagedHeatmapConfig("fixture-key", "fixture-policy", "fixture-signature", "fixture-session",
                "all", "hot", "", ".*", AlignmentMode.PRECISE_SHAPE, TrackerMode.PROBABILISTIC,
                false, false, false, false, false, false, false, false, false, false,
                7, 4, 3, InferenceMode.RAW_HIGH_RESOLUTION, 15, 14, 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0);
        var service = new LiveBPreviewService(); LiveBPreviewService.ManagedCaptureSeed[] seed = {null};
        SwingUtilities.invokeAndWait(() -> seed[0] = service.captureManagedSeed(data, SelectionResolver.resolve(data, false),
                new AlignmentConfig(config, GeometryCleanupConfig.disabled()), "managed-selected-hot-g0"));
        assertFalse(seed[0].intervalPartition().fixedIslands().isEmpty());
        var image = new java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) image.setRGB(x, y, 0xff000000);
        boolean[] valid = new boolean[600 * 600]; Arrays.fill(valid, true); double equator = Math.scalb(256.0, 15) / 2;
        var captured = service.attachManagedRaster(seed[0], new ManagedModernPreviewSource.Raster(image, valid,
                SupportedInputRasterTransform.webMercator(15, equator - 150, equator - 150, 2),
                "hot", 15, "managed-selected-hot-g0", new ManagedTileGeneration(0)));
        var computed = service.compute(captured, CancellationProbe.NONE); assertTrue(computed.partitioned());
        return new NativeFixture(data, computed, new FixedIntervalEditPlanComposer().compose(computed.intervalBatch(), Map.of()));
    }

    private static String expectedGeometryHash(List<GeographicPoint> points) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(ByteBuffer.allocate(8).putLong(points.size()).array());
        for (var point : points) {
            digest.update(ByteBuffer.allocate(8).putLong(Double.doubleToRawLongBits(point.latitudeDegrees())).array());
            digest.update(ByteBuffer.allocate(8).putLong(Double.doubleToRawLongBits(point.longitudeDegrees())).array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
