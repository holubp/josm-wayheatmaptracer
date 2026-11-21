package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

class LegacyReviewOwnerTest {
    @BeforeAll static void setup() {
        var preferences = new org.openstreetmap.josm.data.Preferences();
        preferences.enableSaveOnPut(false);
        Config.setPreferencesInstance(preferences);
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }
    @Test void legacyReviewAndCandidateSelectionDoNotReadOrConvertTheRetainedRaster() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = fixture();
            try (var owner = LegacyReviewOwner.begin(f.data(), f.selection(), f.config())) {
                owner.captured(f.result(), f.frame());
                owner.select(f.second());
                assertSame(f.second(), owner.selectedCandidate());
                assertSame(f.result(), owner.originalResult());
                assertEquals(0, f.reads().get(), "Review/Legacy Apply must use the already computed output");
                assertEquals(List.of(new EastNorth(0, 2), new EastNorth(10, 3), new EastNorth(20, 2)),
                        owner.selectedCandidate().finalPreviewPoints(), "Legacy movement rights must not be reset to frozen OSM positions");
                assertEquals(f.config(), owner.originalConfig());
                owner.requireOriginalAttemptCurrent();
                assertEquals(0, f.reads().get());
            }
        });
    }
    @Test void freshnessBarriersDoNotModifyPrimitivesOrNotifyOrTouchUndoHistory() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = fixture();
            f.data().getNodes().forEach(node -> node.setModified(false));
            f.data().getWays().forEach(way -> way.setModified(false));
            var changes = new AtomicInteger();
            var listener = new org.openstreetmap.josm.data.osm.event.DataSetListenerAdapter(event -> changes.incrementAndGet());
            f.data().addDataSetListener(listener);
            var undo = org.openstreetmap.josm.data.UndoRedoHandler.getInstance();
            int commands = undo.getUndoCommands().size();
            try (var owner = LegacyReviewOwner.begin(f.data(), f.selection(), f.config())) {
                owner.captured(f.result(), f.frame());
                owner.requireOriginalAttemptCurrent(); owner.select(f.second());
                owner.requireOriginalAttemptCurrent();
                assertFalse(f.data().isModified()); assertFalse(f.selection().way().isModified());
                assertTrue(f.selection().segmentNodes().stream().noneMatch(Node::isModified));
                assertEquals(0, changes.get()); assertEquals(commands, undo.getUndoCommands().size());
                assertEquals(0, f.reads().get());
            } finally { f.data().removeDataSetListener(listener); }
        });
    }
    @Test void unrelatedDatasetMutationAndRestorationCannotMintFreshBootstrapAuthority() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = fixture();
            try (var owner = LegacyReviewOwner.begin(f.data(), f.selection(), f.config())) {
                owner.captured(f.result(), f.frame());
                Node unrelated = new Node(new LatLon(.01, .01));
                f.data().addPrimitive(unrelated);
                unrelated.put("name", "temporary"); unrelated.remove("name");
                assertThrows(IllegalStateException.class, owner::requireOriginalAttemptCurrent);
                assertThrows(IllegalStateException.class, () -> owner.freezeVisibleSource("synthetic-original"));
                assertEquals(0, f.reads().get(), "Refusal must precede pixel freezing or native conversion");
                assertSame(f.result(), owner.originalResult(), "Bootstrap refusal retains factual Legacy review");
            }
        });
    }
    @Test void backgroundBatchedTagChangeAndRestorationInvalidatesBeforeBootstrap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = fixture();
            try (var owner = LegacyReviewOwner.begin(f.data(), f.selection(), f.config())) {
                owner.captured(f.result(), f.frame());
                var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread mutation = new Thread(() -> {
                    try {
                        f.data().update(() -> {
                            f.selection().way().put("name", "temporary");
                            f.selection().way().remove("name");
                        });
                    } catch (Throwable failure) { error.set(failure); }
                });
                mutation.start();
                try { mutation.join(); } catch (InterruptedException failure) { throw new AssertionError(failure); }
                assertNull(error.get(), "Dataset listener must accept background delivery without calling EDT-owned UI");
                assertThrows(IllegalStateException.class, owner::requireOriginalAttemptCurrent);
                assertEquals(0, f.reads().get());
            }
        });
    }
    @Test void bootstrapWaitsForEarlierBatchEventDeliveryBeforeReadingTheStamp() throws Exception {
        Fixture f = fixture();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var owner = new java.util.concurrent.atomic.AtomicReference<LegacyReviewOwner>();
        var eventThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var refusal = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var listenerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var earlier = new org.openstreetmap.josm.data.osm.event.DataSetListenerAdapter(event -> {
            entered.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Event gate timed out");
            } catch (Throwable failure) { listenerFailure.set(failure); }
        });
        SwingUtilities.invokeAndWait(() -> {
            eventThread.set(Thread.currentThread()); f.data().addDataSetListener(earlier);
            owner.set(LegacyReviewOwner.begin(f.data(), f.selection(), f.config()));
            owner.get().captured(f.result(), f.frame());
        });
        Thread mutation = new Thread(() -> f.data().update(() -> {
            f.selection().way().put("name", "temporary"); f.selection().way().remove("name");
        }));
        try {
            mutation.start(); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            SwingUtilities.invokeLater(() -> {
                started.countDown();
                try { owner.get().requireOriginalAttemptCurrent(); }
                catch (Throwable failure) { refusal.set(failure); }
                finally { finished.countDown(); }
            });
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            boolean waitingForBatch = false; long deadline = System.nanoTime() + 5_000_000_000L;
            while (!waitingForBatch && finished.getCount() != 0 && System.nanoTime() < deadline) {
                Thread thread = eventThread.get();
                waitingForBatch = thread.getState() == Thread.State.WAITING
                    && Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals("org.openstreetmap.josm.data.osm.DataSet")
                        && frame.getMethodName().equals("beginUpdate"));
                Thread.onSpinWait();
            }
            assertTrue(waitingForBatch, "Bootstrap must wait for prior batch event delivery; a shared read lock can race it");
        } finally {
            release.countDown(); mutation.join(5000);
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> { owner.get().close(); f.data().removeDataSetListener(earlier); });
        }
        assertFalse(mutation.isAlive()); assertNull(listenerFailure.get());
        assertInstanceOf(IllegalStateException.class, refusal.get()); assertEquals(0, f.reads().get());
    }
    @Test void mutationDuringOriginalAttemptIsNeverRecertifiedWhenResultArrives() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = fixture();
            try (var owner = LegacyReviewOwner.begin(f.data(), f.selection(), f.config())) {
                f.selection().way().put("name", "changed-before-result");
                owner.captured(f.result(), f.frame());
                assertThrows(IllegalStateException.class, owner::requireOriginalAttemptCurrent);
                assertSame(f.result(), owner.originalResult());
                assertEquals(0, f.reads().get());
            }
        });
    }
    @Test void missingSourceReceiptKeepsLegacyReviewAndRefusesBeforeReadingPixels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = fixture();
            try (var owner = LegacyReviewOwner.begin(f.data(), f.selection(), f.config())) {
                var old = f.frame();
                owner.captured(f.result(), new LegacyVisibleSource(old.minimumEast(), old.minimumNorth(),
                        old.maximumEast(), old.maximumNorth(), old.projectionUnitsPerViewPixel(),
                        old.groundMetersPerViewPixel(), old.nativePitchMeters(), old.projectionCode(), null));
                owner.requireOriginalAttemptCurrent();
                assertThrows(IllegalStateException.class, () -> owner.freezeVisibleSource("synthetic-original"));
                assertSame(f.result(), owner.originalResult()); assertEquals(0, f.reads().get());
            }
        });
    }
    @Test void pluginSettingChangeAndRestorationCannotRecertifyOriginalSource() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = fixture();
            try (var owner = LegacyReviewOwner.begin(f.data(), f.selection(), f.config())) {
                owner.captured(f.result(), f.frame());
                String key = "wayheatmaptracer.activity";
                String original = Config.getPref().get(key, "all");
                Config.getPref().put(key, "ride"); Config.getPref().put(key, original);
                assertThrows(IllegalStateException.class, owner::requireOriginalAttemptCurrent);
                assertSame(f.result(), owner.originalResult()); assertEquals(0, f.reads().get());
            }
        });
    }
    @Test void sourceEpochChangesRejectFreezingAndNeverRecaptureCurrentViewport() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = fixture();
            try (var owner = LegacyReviewOwner.begin(f.data(), f.selection(), f.config())) {
                owner.captured(f.result(), f.frame());
                f.epoch().sourceChanged();
                assertThrows(IllegalStateException.class, () -> owner.freezeVisibleSource("synthetic-original"));
                assertEquals(0, f.reads().get());
            }
        });
    }
    @Test void explicitFreezeUsesOriginalCaptureMetadataAndCreatesImmutablePixelInput() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = fixture();
            try (var owner = LegacyReviewOwner.begin(f.data(), f.selection(), f.config())) {
                owner.captured(f.result(), f.frame());
                var frozen = owner.freezeVisibleSource("synthetic-original");
                assertEquals(1, f.reads().get());
                assertEquals(-2, frozen.minimumEast()); assertEquals(8, frozen.maximumNorth());
                assertEquals(.5, frozen.projectionUnitsPerViewPixel());
                assertEquals(.5, frozen.groundMetersPerViewPixel());
                f.result().capturedHeatmap().setRGB(0, 0, 0xffffffff);
                assertEquals(0xff123456, frozen.argb()[0]);
            }
        });
    }
    @Test void closedOrOffEdtOwnerCannotBootstrap() throws Exception {
        Fixture f = fixture();
        assertThrows(IllegalStateException.class, () -> LegacyReviewOwner.begin(f.data(), f.selection(), f.config()));
        SwingUtilities.invokeAndWait(() -> {
            var owner = LegacyReviewOwner.begin(f.data(), f.selection(), f.config());
            owner.captured(f.result(), f.frame()); owner.close(); owner.close();
            assertThrows(IllegalStateException.class, owner::requireOriginalAttemptCurrent);
            assertThrows(IllegalStateException.class, () -> owner.freezeVisibleSource("synthetic-original"));
            assertEquals(0, f.reads().get());
        });
    }
    @Test void ordinaryLegacyAuthorityChecksKnownVisibleReceiptWithoutReadingPixels() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var f=fixture();try(var owner=LegacyReviewOwner.begin(f.data(),f.selection(),f.config())) {
                owner.captured(f.result(),f.frame());owner.requireLegacyAuthorityCurrent();assertEquals(0,f.reads().get());
                f.epoch().sourceChanged();owner.requireOriginalAttemptCurrent();
                assertThrows(IllegalStateException.class,owner::requireLegacyAuthorityCurrent);assertEquals(0,f.reads().get());
            }
        });
    }
    @Test void unavailableRefinementReceiptDoesNotInvalidateOrdinaryLegacyAuthority() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var f=fixture();var old=f.frame();
            var unsupported=new LegacyVisibleSource(old.minimumEast(),old.minimumNorth(),old.maximumEast(),old.maximumNorth(),
                old.projectionUnitsPerViewPixel(),old.groundMetersPerViewPixel(),old.nativePitchMeters(),old.projectionCode(),null);
            try(var owner=LegacyReviewOwner.begin(f.data(),f.selection(),f.config())) {
                owner.captured(f.result(),unsupported);
                assertThrows(IllegalStateException.class,()->owner.freezeVisibleSource("unsupported-proof"));
                assertDoesNotThrow(owner::requireLegacyAuthorityCurrent);assertEquals(0,f.reads().get());
            }
        });
    }
    private record Fixture(DataSet data, SelectionContext selection, AlignmentConfig config,
            AlignmentResult result, CenterlineCandidate second, LegacyVisibleSource frame,
            VisibleSourceEpoch epoch, AtomicInteger reads) { }
    private static Fixture fixture() {
        DataSet data = new DataSet();
        List<Node> nodes = List.of(new Node(new LatLon(0, 0)), new Node(new LatLon(0, .0001)),
                new Node(new LatLon(0, .0002)));
        nodes.forEach(data::addPrimitive); Way way = new Way(); way.setNodes(nodes); data.addPrimitive(way);
        // A real shared endpoint, with the original Legacy junction-movement opt-in enabled.
        Node branchEnd = new Node(new LatLon(.0001, 0)); data.addPrimitive(branchEnd);
        Way branch = new Way(); branch.setNodes(List.of(nodes.get(0), branchEnd)); data.addPrimitive(branch);
        data.setSelected(way);
        var selection = org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver.resolve(data, true);
        var config = AlignmentConfig.withoutCleanup(new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*",
                AlignmentMode.PRECISE_SHAPE, TrackerMode.LEGACY_V02,
                false, false, false, false, false, false, false, false, true, false,
                7, 4, 3, InferenceMode.RAW_HIGH_RESOLUTION, 15, 14, 7.01, 1.56,
                IntensitySamplingMode.COLOR_MAPPING, 0));
        List<EastNorth> original = nodes.stream().map(Node::getEastNorth).toList();
        var first = new CenterlineCandidate("legacy-hot", 1, List.of(), List.of())
                .withFinalPreviewPoints(original);
        var second = new CenterlineCandidate("legacy-blue", .9, List.of(), List.of())
                .withFinalPreviewPoints(List.of(new EastNorth(0, 2), new EastNorth(10, 3), new EastNorth(20, 2)));
        AtomicInteger reads = new AtomicInteger();
        BufferedImage image = new BufferedImage(384, 192, BufferedImage.TYPE_INT_ARGB) {
            @Override public int[] getRGB(int x, int y, int w, int h, int[] rgb, int offset, int stride) {
                reads.incrementAndGet(); return super.getRGB(x, y, w, h, rgb, offset, stride);
            }
        };
        image.setRGB(0, 0, 0xff123456);
        var result = new AlignmentResult(selection, image, List.of(first, second), original,
                original, List.of(), null, null);
        var epoch = new VisibleSourceEpoch();
        var frame = new LegacyVisibleSource(-2, -8, 30, 8, .5, .5, OptionalDouble.of(2.4),
                "EPSG:3857", epoch.captureStable());
        return new Fixture(data, selection, config, result, second, frame, epoch, reads);
    }
}
