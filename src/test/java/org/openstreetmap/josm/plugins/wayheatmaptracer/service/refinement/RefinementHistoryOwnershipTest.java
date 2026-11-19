package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementSessionTest.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.*;

class RefinementHistoryOwnershipTest {
    @Test void cancelledWorkersKeepDiscardedForwardInputChargedUntilBothSettle() throws Exception {
        edt(() -> assertUnsettledInputPins(false));
    }

    @Test void cancelledSmoothingWorkersKeepReplacedOwnerChargedUntilBothSettle() throws Exception {
        edt(() -> assertUnsettledInputPins(true));
    }

    private static void assertUnsettledInputPins(boolean smoothing) {
        var large = RefinementPayload.capture(result(new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB)),
            RefinementLimits.defaults().maxPayloadBytes());
        var first = new Candidate(new CandidateId("large-blue"), large, List.of(), "large-blue");
        var second = new Candidate(new CandidateId("large-purple"), large, List.of(), "large-purple");
        long cap = large.retainedBytes() + 128_000;
        var session = new RefinementSession(root(), config(AlignmentMode.PRECISE_SHAPE), "s", new Object(),
            new RefinementLimits(8,32,cap));
        var initial = smoothing ? session.beginSmoothing("Medium") : session.begin(TrackerMode.CORRIDOR_AWARE);
        Stage largeStage = Stage.child(initial, List.of(first,second), first.id());
        assertTrue(publishReserved(session, initial, largeStage));
        session.workerSettled(initial); // synchronous synthetic producer has returned

        var workers = new ArrayDeque<Runnable>();
        var tickets = new ArrayList<OperationTicket>();
        for (int index = 0; index < 2; index++) {
            var ticket = smoothing ? session.beginSmoothing("Strong") : session.begin(TrackerMode.PROBABILISTIC);
            session.reserve(ticket, 1, 1024);
            var input = new AtomicReference<>(session.current());
            workers.add(() -> {
                assertSame(large, input.get().candidates().get(0).payload());
                assertSame(large, input.get().candidates().get(1).payload());
                assertFalse(session.publish(ticket, child(ticket, "cancelled-late")));
                input.set(null); // release the controlled worker's exact parent before acknowledgment
            });
            tickets.add(ticket);
            session.cancel(ticket);
        }
        if (!smoothing) session.back().orElseThrow();
        var replacement = smoothing ? session.beginSmoothing("Strong") : session.begin(TrackerMode.CORRIDOR_AWARE);
        assertTrue(publishReserved(session, replacement, child(replacement, "small-replacement")));
        assertFalse(session.history().stream().anyMatch(stage -> stage.id().equals(largeStage.id())));
        assertTrue(session.retainedBytes() >= large.retainedBytes(), "Discarded worker input is still owned");
        assertTrue(session.retainedBytes() < 2 * large.retainedBytes(), "Two pins and two candidates share one real raster");
        long beforeSettlement = session.availablePayloadBytes();
        assertTrue(beforeSettlement < large.retainedBytes() / 2, "Pinned input bytes cannot be reused");
        session.workerSettled(replacement); // this synchronous producer has finished; cancelled workers have not
        assertEquals(beforeSettlement, session.availablePayloadBytes());

        var refused = session.begin(TrackerMode.PROBABILISTIC);
        assertEquals(RefusalReason.PAYLOAD_LIMIT, assertThrows(RefinementSession.Refused.class,
            () -> session.reserve(refused, 1, large.retainedBytes())).reason());
        session.workerSettled(refused); // preflight refusal created no worker or payload
        assertTrue(session.canApply());
        workers.remove().run();
        session.workerSettled(tickets.get(0));
        assertEquals(beforeSettlement + 1024, session.availablePayloadBytes(),
            "Settling only one worker cannot release the other worker's shared input");
        workers.remove().run();
        session.workerSettled(tickets.get(1));
        long afterSettlement = session.availablePayloadBytes();
        assertTrue(afterSettlement - beforeSettlement >= large.retainedBytes());
        session.workerSettled(tickets.get(1));
        assertEquals(afterSettlement, session.availablePayloadBytes(), "Settlement is idempotent");
        var admitted = session.begin(TrackerMode.PROBABILISTIC);
        assertEquals(large.retainedBytes(), session.reserve(admitted, 1, large.retainedBytes()).additionalBytes());
        session.cancel(admitted);
        session.workerSettled(admitted); // no worker was started for the final capacity probe
    }

    @Test void exactPreflightBoundAlsoCoversNonemptySharedCandidateLists() {
        var raw = new CenterlineCandidate("blue", 1, List.of(new Point2D.Double(5,6)), List.of(0d));
        var base = result(null);
        var source = new AlignmentResult(null, null, List.of(raw), base.sourcePolyline(), base.previewPolyline(),
            List.of(), null, null);
        long estimate = RefinementPayload.estimateBytes(source);
        var payload = RefinementPayload.capture(source, estimate);
        assertTrue(payload.retainedBytes() <= estimate,
            "The pre-allocation bound must cover the owned snapshot, including aliased candidate lists");
    }

    @Test void syntheticExtremeRasterDimensionsSaturateBeforePixelRead() {
        BufferedImage extreme = new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB) {
            @Override public int getWidth() { return Integer.MAX_VALUE; }
            @Override public int getHeight() { return Integer.MAX_VALUE; }
            @Override public int[] getRGB(int x, int y, int width, int height, int[] rgb, int offset, int stride) {
                throw new AssertionError("Refused preflight must never read pixels");
            }
        };
        assertEquals(Long.MAX_VALUE, RefinementPayload.estimateBytes(result(extreme)));
        assertEquals(RefusalReason.PAYLOAD_LIMIT, assertThrows(RefinementSession.Refused.class,
            () -> RefinementPayload.capture(result(extreme), 64L * 1024 * 1024)).reason());
    }
    @Test void immutableMosaicSnapshotPreservesSharedImageIdentityAndEstimatesBeforeAllocation() {
        BufferedImage image = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,0xffabcdef);
        var parameters = new SamplingParameters(15, 50, 1, 8, 1, 8, 1);
        Map<String, BufferedImage> tileImages = new HashMap<>(); tileImages.put("tile", image);
        var mosaic = new TileMosaic("blue", 15, 0, 0, image, List.of(), tileImages, parameters, false, 1);
        Map<String, TileMosaic> mosaics = new HashMap<>(); mosaics.put("blue@15", mosaic);
        var set = new TileMosaicSet(InferenceMode.STABLE_FIXED_SCALE, 15,15,15,15,256,mosaics,parameters,parameters);
        var base = result(image);
        var source = new AlignmentResult(null, image, List.of(), base.sourcePolyline(), base.previewPolyline(),
            List.of(), null, set);
        long estimate = RefinementPayload.estimateBytes(source);
        assertTrue(estimate > 32L * 32 * 4);
        var payload = RefinementPayload.capture(source, estimate);
        assertTrue(payload.retainedBytes() <= estimate);
        mosaics.clear(); tileImages.clear(); image.setRGB(0,0,0);
        var view = payload.result();
        assertSame(view.capturedHeatmap(), view.tileMosaics().require("blue").image());
        assertSame(view.capturedHeatmap(), view.tileMosaics().require("blue").tileImages().get("tile"));
        assertThrows(UnsupportedOperationException.class, () -> view.tileMosaics().mosaics().clear());
        view.tileMosaics().require("blue").image().setRGB(0,0,0);
        assertEquals(0xffabcdef, payload.result().tileMosaics().require("blue").image().getRGB(0,0));
    }
    @Test void reservationRefusesBeforeCopiesRollsBackAndRejectsUnderestimatedPublication() throws Exception {
        edt(() -> {
            var session = session(AlignmentMode.PRECISE_SHAPE);
            long retained = session.retainedBytes();
            var tooMany = session.begin(TrackerMode.CORRIDOR_AWARE);
            assertEquals(RefusalReason.CANDIDATE_LIMIT, assertThrows(RefinementSession.Refused.class,
                () -> session.reserve(tooMany, 33, 100)).reason());
            assertEquals(0, session.reservedBytes());
            assertTrue(session.canApply());
            var tooLarge = session.begin(TrackerMode.PROBABILISTIC);
            assertEquals(RefusalReason.PAYLOAD_LIMIT, assertThrows(RefinementSession.Refused.class,
                () -> session.reserve(tooLarge, 1, Long.MAX_VALUE)).reason());
            assertEquals(retained, session.retainedBytes());
            var old = session.begin(TrackerMode.CORRIDOR_AWARE);
            Reservation receipt = session.reserve(old, 1, 1000);
            assertEquals(old, receipt.ticket());
            assertEquals(1000, session.reservedBytes());
            session.cancel(old);
            assertEquals(1000, session.reservedBytes(), "Cancellation cannot assume the worker has released its payload");
            var fresh = session.begin(TrackerMode.PROBABILISTIC);
            assertThrows(IllegalStateException.class, () -> session.reserve(old, 1, 1000));
            session.reserve(fresh, 1, 1); // deliberately insufficient; publish must measure the actual stage
            assertFalse(session.publish(fresh, child(fresh, "underestimated")));
            assertEquals(RefusalReason.PAYLOAD_LIMIT, session.lastFailure().orElseThrow().reason());
            assertEquals(1001, session.reservedBytes());
            session.workerSettled(old);
            session.workerSettled(fresh);
            assertEquals(0, session.reservedBytes());
            assertEquals(retained, session.retainedBytes());
            assertTrue(session.canApply());
        });
    }
    @Test void limitsRefuseWithoutEvictingParentOrConsumingOperationAndKeepRootApply() throws Exception {
        edt(() -> {
            var session = session(AlignmentMode.PRECISE_SHAPE);
            for (int i = 1; i < 8; i++) {
                var ticket = session.begin(TrackerMode.CORRIDOR_AWARE);
                assertTrue(publishReserved(session, ticket, child(ticket, "c" + i)));
            }
            assertEquals(RefusalReason.STAGE_LIMIT, assertThrows(RefinementSession.Refused.class,
                () -> session.begin(TrackerMode.PROBABILISTIC)).reason());
            assertTrue(session.canApply());
            assertEquals(8, session.history().size());
            for (int i = 1; i < 8; i++) assertTrue(session.back().isPresent());
            assertEquals("blue", session.current().selected().value());
            assertTrue(session.canApply());
        });
    }

    @Test void payloadSharingChargedOnceAndFailedCandidateAndByteReservationsRollback() throws Exception {
        edt(() -> {
            RefinementPayload payload = RefinementPayload.capture(result(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)),
                RefinementLimits.defaults().maxPayloadBytes());
            var first = new Candidate(new CandidateId("one"), payload, List.of(), "one");
            var second = new Candidate(new CandidateId("two"), payload, List.of(), "two");
            Stage root = Stage.root(List.of(first, second), first.id());
            var session = new RefinementSession(root, config(AlignmentMode.PRECISE_SHAPE), "s", new Object(),
                new RefinementLimits(8, 2, payload.retainedBytes() + 4096));
            long retained = session.retainedBytes();
            assertTrue(retained < 2 * payload.retainedBytes(), "The real shared raster must fit only once");
            var ticket = session.begin(TrackerMode.CORRIDOR_AWARE);
            assertFalse(publishReserved(session, ticket, Stage.child(ticket,
                List.of(candidate("a"), candidate("b"), candidate("c")), new CandidateId("a"))));
            assertEquals(RefusalReason.CANDIDATE_LIMIT, session.lastFailure().orElseThrow().reason());
            assertEquals(retained, session.retainedBytes());
            assertTrue(session.canApply());
            var next = session.begin(TrackerMode.PROBABILISTIC);
            var large = new Candidate(new CandidateId("large"), result(new BufferedImage(100,100,2)), List.of(), "large");
            assertFalse(publishReserved(session, next, Stage.child(next, List.of(large), large.id())));
            assertEquals(RefusalReason.PAYLOAD_LIMIT, session.lastFailure().orElseThrow().reason());
            assertEquals(retained, session.retainedBytes());
            assertEquals(1, session.history().size());
            assertTrue(session.terminalizeForApply());
        });
    }

    @Test void preflightRejectsOversizedRasterBeforeSnapshotAndRootOverflowRetainsApply() throws Exception {
        BufferedImage image = new BufferedImage(16,16,2);
        assertThrows(RefinementSession.Refused.class, () -> RefinementPayload.capture(result(image), 32));
        edt(() -> {
            var root = root();
            assertEquals(RefusalReason.PAYLOAD_LIMIT, assertThrows(RefinementSession.Refused.class,
                () -> new RefinementSession(root, config(AlignmentMode.PRECISE_SHAPE), "s", new Object(),
                    new RefinementLimits(8, 32, 1))).reason());
            assertEquals("blue", root.selected().value());
            assertEquals(List.of(new org.openstreetmap.josm.data.coor.EastNorth(1,2),
                new org.openstreetmap.josm.data.coor.EastNorth(3,4)), root.candidates().get(0).result().previewPolyline(),
                "Fallible history creation leaves the external Legacy result available to its existing Apply flow");
        });
    }

    @Test void defensivePayloadFreezesInputAndReturnedImagesPointsAndOriginalNodeValues() {
        BufferedImage image = new BufferedImage(2,2,2);
        image.setRGB(0,0,0xff123456);
        Point2D.Double point = new Point2D.Double(5,6);
        var candidate = new CenterlineCandidate("blue", 1, List.of(point), List.of(0d));
        Node node = new Node(new LatLon(50,14));
        Way way = new Way(); way.setNodes(List.of(node, new Node(new LatLon(50.1,14.1))));
        var nodes = new ArrayList<>(way.getNodes());
        var selection = new SelectionContext(way, 0, 1, nodes, Set.of(node));
        var base = result(image);
        var result = new AlignmentResult(selection, image, List.of(candidate), base.sourcePolyline(),
            base.previewPolyline(), List.of(), null, null);
        var payload = RefinementPayload.capture(result, 1_000_000);
        image.setRGB(0,0,0); point.x = 999; nodes.clear(); node.setCoor(new LatLon(40,10));
        assertEquals(0xff123456, payload.result().capturedHeatmap().getRGB(0,0));
        assertEquals(5, payload.result().candidates().get(0).screenPoints().get(0).x);
        assertEquals(2, payload.result().selection().segmentNodes().size());
        assertSame(node, payload.result().selection().segmentNodes().get(0));
        assertEquals(50, payload.sourceNodes().get(0).latitude());
        assertEquals(14, payload.sourceNodes().get(0).longitude());
        payload.result().capturedHeatmap().setRGB(0,0,0);
        payload.result().candidates().get(0).screenPoints().get(0).x = -5;
        assertEquals(0xff123456, payload.result().capturedHeatmap().getRGB(0,0));
        assertEquals(5, payload.result().candidates().get(0).screenPoints().get(0).x);
    }
}
