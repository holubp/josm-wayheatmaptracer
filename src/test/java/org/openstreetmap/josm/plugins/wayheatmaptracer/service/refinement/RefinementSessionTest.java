package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Public, deterministic history and late-publication contract. No engines or OSM commands. */
class RefinementSessionTest {
    static AlignmentConfig config(AlignmentMode mode) {
        Config.setPreferencesInstance(new MemoryPreferences());
        return AlignmentConfig.withoutCleanup(PluginPreferences.load().withAlignmentMode(mode));
    }
    static Candidate candidate(String id) {
        return new Candidate(new CandidateId(id), result(null), List.of(), id);
    }
    static AlignmentResult result(java.awt.image.BufferedImage image) {
        var points = List.of(new EastNorth(1, 2), new EastNorth(3, 4));
        return new AlignmentResult(null, image, List.of(), points, points, List.of(), null, null);
    }
    static Stage root() { return Stage.root(List.of(candidate("blue"), candidate("purple")), new CandidateId("blue")); }
    static Stage child(OperationTicket ticket, String id) {
        return Stage.child(ticket, List.of(candidate(id)), new CandidateId(id));
    }
    static boolean publishReserved(RefinementSession session, OperationTicket ticket, Stage stage) {
        try { session.reserve(ticket, stage.candidates().size(), Math.max(1, session.availablePayloadBytes())); }
        catch (RefinementSession.Refused refused) { return false; }
        return session.publish(ticket, stage);
    }
    static RefinementSession session(AlignmentMode mode) {
        return new RefinementSession(root(), config(mode), "source-1", new Object(), RefinementLimits.defaults());
    }
    static void edt(Runnable action) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch (Throwable ex) { thrown.set(ex); } });
        if (thrown.get() instanceof AssertionError ex) throw ex;
        if (thrown.get() instanceof RuntimeException ex) throw ex;
        if (thrown.get() != null) throw new AssertionError(thrown.get());
    }

    @Test void rootAndRepeatedRefinementsPreserveBothFrozenModes() throws Exception {
        edt(() -> {
            for (AlignmentMode mode : AlignmentMode.values()) {
                var session = session(mode);
                assertEquals(StageKind.ROOT_LEGACY, session.current().kind());
                assertEquals(TrackerMode.LEGACY_V02, session.current().engine());
                assertTrue(session.back().isEmpty());
                for (TrackerMode engine : List.of(TrackerMode.PROBABILISTIC, TrackerMode.CORRIDOR_AWARE,
                        TrackerMode.CORRIDOR_AWARE, TrackerMode.PROBABILISTIC)) {
                    var ticket = session.begin(engine);
                    assertEquals(mode, ticket.mode());
                    assertTrue(publishReserved(session, ticket, child(ticket, engine.name())));
                }
                assertEquals(5, session.history().size());
            }
        });
    }

    @Test void backRestoresExactChoiceAndCancelledOrFailedBranchKeepsForwardHistory() throws Exception {
        edt(() -> {
            var session = session(AlignmentMode.PRECISE_SHAPE);
            session.select(new CandidateId("purple"));
            var first = session.begin(TrackerMode.CORRIDOR_AWARE);
            Stage child = child(first, "purple-A");
            assertTrue(publishReserved(session, first, child));
            session.confirmCurrent();
            assertEquals(new CandidateId("purple"), session.back().orElseThrow().selected());
            assertFalse(session.confirmed());
            session.select(new CandidateId("blue"));
            var cancelled = session.begin(TrackerMode.PROBABILISTIC);
            session.cancel(cancelled);
            assertEquals(child.id(), session.history().get(1).id());
            assertTrue(session.canApply());
            var failed = session.begin(TrackerMode.PROBABILISTIC);
            session.fail(failed, Outcome.FAILED);
            assertEquals(failed, session.lastFailure().orElseThrow().ticket());
            assertEquals(child.id(), session.history().get(1).id());
            var replacement = session.begin(TrackerMode.PROBABILISTIC);
            assertTrue(publishReserved(session, replacement, child(replacement, "blue-B")));
            assertEquals(2, session.history().size());
            assertNotEquals(child.id(), session.current().id());
            assertEquals("blue", session.current().parentChoice().orElseThrow().value());
        });
    }

    @Test void controlledQueueRejectsCancelledRestartedAndClosedPublications() throws Exception {
        edt(() -> {
            var session = session(AlignmentMode.MOVE_EXISTING_NODES);
            var queue = new ArrayDeque<Runnable>();
            var old = session.begin(TrackerMode.CORRIDOR_AWARE);
            queue.add(() -> assertFalse(session.publish(old, child(old, "late-old"))));
            assertThrows(IllegalStateException.class, session::back);
            assertThrows(IllegalStateException.class, () -> session.select(new CandidateId("purple")));
            assertFalse(session.terminalizeForApply());
            session.cancel(old);
            session.select(new CandidateId("purple"));
            var fresh = session.begin(TrackerMode.PROBABILISTIC);
            assertTrue(fresh.sequence() > old.sequence());
            session.cancel(old); // stale cancellation cannot cancel fresh work
            queue.remove().run();
            assertTrue(publishReserved(session, fresh, child(fresh, "fresh")));
            var closed = session.begin(TrackerMode.CORRIDOR_AWARE);
            queue.add(() -> assertFalse(session.publish(closed, child(closed, "late-close"))));
            session.close();
            queue.remove().run();
            assertFalse(session.canApply());
            assertThrows(IllegalStateException.class, () -> session.begin(TrackerMode.PROBABILISTIC));
        });
    }

    @Test void applyIsOneTerminalRequestAndInvalidatesCancelledLateWork() throws Exception {
        edt(() -> {
            var session = session(AlignmentMode.PRECISE_SHAPE);
            var old = session.begin(TrackerMode.PROBABILISTIC);
            session.cancel(old);
            assertTrue(session.terminalizeForApply());
            assertFalse(session.terminalizeForApply());
            assertFalse(session.publish(old, child(old, "late")));
            assertThrows(IllegalStateException.class, () -> session.select(new CandidateId("purple")));
        });
    }

    @Test void mutationsRequireEdtAndHistoricalRootsRefuseWithTypedReason() throws Exception {
        AtomicReference<RefinementSession> ref = new AtomicReference<>();
        edt(() -> ref.set(session(AlignmentMode.PRECISE_SHAPE)));
        assertThrows(IllegalStateException.class, () -> ref.get().begin(TrackerMode.CORRIDOR_AWARE));
        assertThrows(IllegalStateException.class, ref.get()::back);
        assertThrows(IllegalStateException.class, ref.get()::close);
        edt(() -> {
            var invalid = new Stage(new StageId(UUID.randomUUID()), Optional.empty(), Optional.empty(),
                StageKind.ROOT_LEGACY, TrackerMode.PROBABILISTIC, List.of(candidate("old")),
                new CandidateId("old"), Optional.empty());
            var session = new RefinementSession(invalid, config(AlignmentMode.PRECISE_SHAPE), "old", new Object(),
                RefinementLimits.defaults());
            var refusal = assertThrows(RefinementSession.Refused.class,
                () -> session.begin(TrackerMode.CORRIDOR_AWARE));
            assertEquals(RefusalReason.NON_LEGACY_ROOT, refusal.reason());
            assertTrue(session.canApply());
        });
    }

    @Test void correspondenceAndStageIdentityRejectInvalidTraversalAndSelection() {
        assertThrows(IllegalArgumentException.class, () -> new ParentArc(0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ParentArc(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Candidate(new CandidateId("bad"), result(null),
            List.of(new ParentArc(1, 2, .5), new ParentArc(0, 1, .5)), "bad"));
        assertThrows(IllegalArgumentException.class,
            () -> Stage.root(List.of(candidate("one")), new CandidateId("missing")));
        assertThrows(IllegalArgumentException.class,
            () -> Stage.root(List.of(candidate("one"), candidate("one")), new CandidateId("one")));
    }

    @Test void rootConfigForcesLegacyWhileKeepingSavedAndExplicitGeometryMode() throws Exception {
        edt(() -> {
            for (AlignmentMode setting : AlignmentMode.values()) {
                for (AlignmentMode override : new AlignmentMode[] {null, AlignmentMode.PRECISE_SHAPE,
                        AlignmentMode.MOVE_EXISTING_NODES}) {
                    AlignmentConfig saved = config(setting);
                    saved = new AlignmentConfig(saved.heatmap().withTrackerMode(TrackerMode.PROBABILISTIC), saved.cleanup());
                    AlignmentConfig frozen = RefinementSession.normalRootConfig(saved, override);
                    assertEquals(TrackerMode.LEGACY_V02, frozen.heatmap().trackerMode());
                    assertEquals(override == null ? setting : override, frozen.heatmap().alignmentMode());
                    assertEquals(TrackerMode.PROBABILISTIC, saved.heatmap().trackerMode());
                }
            }
        });
    }

    @Test void smoothingStagePinsOwnerChoiceAndExactUnsmoothedInputWithoutClaimingAnEngine() {
        Stage root = root();
        CandidateId selected = root.selected();
        SmoothingInput binding = new SmoothingInput(root.id(), selected, root.id(), selected, "Medium");
        Stage smooth = new Stage(new StageId(UUID.randomUUID()), Optional.of(root.id()), Optional.of(selected),
            StageKind.SMOOTH, root.engine(), List.of(candidate("smooth")), new CandidateId("smooth"), Optional.of(binding));
        SmoothingInput replacement = new SmoothingInput(smooth.id(), smooth.selected(), binding.unsmoothedParent(),
            binding.unsmoothedChoice(), "Strong");
        assertEquals(root.id(), replacement.unsmoothedParent());
        assertEquals(selected, replacement.unsmoothedChoice());
        assertEquals(smooth.id(), replacement.owner());
        assertEquals("Strong", replacement.preset());
        assertThrows(IllegalArgumentException.class, () -> new Stage(new StageId(UUID.randomUUID()),
            Optional.of(root.id()), Optional.of(new CandidateId("purple")), StageKind.SMOOTH, root.engine(),
            smooth.candidates(), smooth.selected(), Optional.of(binding)));
    }

    @Test void smoothingStrengthReplacesOneStageFromTheSameExactInputAndBackRestoresParent() throws Exception {
        edt(() -> {
            for (AlignmentMode mode : AlignmentMode.values()) {
                var session = session(mode);
                session.select(new CandidateId("purple"));
                Stage root = session.current();
                var medium = session.beginSmoothing("Medium");
                assertEquals(StageKind.SMOOTH, medium.kind());
                assertEquals(mode, medium.mode());
                assertEquals(root.id(), medium.smoothingInput().orElseThrow().unsmoothedParent());
                assertTrue(publishReserved(session, medium, child(medium, "purple-medium")));
                Stage previous = session.current();
                var cancelled = session.beginSmoothing("Strong");
                session.cancel(cancelled);
                assertEquals(previous, session.current());
                var failed = session.beginSmoothing("Strong");
                session.fail(failed, Outcome.FAILED);
                assertEquals(previous, session.current());
                var strong = session.beginSmoothing("Strong");
                var binding = strong.smoothingInput().orElseThrow();
                assertEquals(previous.id(), binding.owner());
                assertEquals(previous.selected(), binding.choice());
                assertEquals(root.id(), binding.unsmoothedParent());
                assertEquals(new CandidateId("purple"), binding.unsmoothedChoice());
                assertTrue(publishReserved(session, strong, child(strong, "purple-strong")));
                assertEquals(2, session.history().size());
                assertEquals(root.id(), session.current().parent().orElseThrow());
                assertEquals("Strong", session.current().smoothingInput().orElseThrow().preset());
                assertEquals(root, session.back().orElseThrow());
                var a = session.begin(TrackerMode.CORRIDOR_AWARE);
                assertTrue(publishReserved(session, a, child(a, "A")));
                var b = session.begin(TrackerMode.PROBABILISTIC);
                assertTrue(publishReserved(session, b, child(b, "B")));
                var smoothedB = session.beginSmoothing("Medium");
                assertTrue(publishReserved(session, smoothedB, child(smoothedB, "B-medium")));
                assertEquals("B", session.back().orElseThrow().selected().value());
                assertEquals("A", session.back().orElseThrow().selected().value());
                assertEquals("purple", session.back().orElseThrow().selected().value());
            }
        });
    }
}
