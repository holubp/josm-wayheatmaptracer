package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.SharedNodeWorkflowFixture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;

class V022SharedNodeDisconnectionWorkflowTest {
    @Test void actualButtonsKeepOccurrenceChoicesAndRejectedDiagnosticPublicationOwned() throws Exception {
        var f = SharedNodeWorkflowFixture.create();
        var requested = new java.util.ArrayList<org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions>();
        var published = new java.util.concurrent.atomic.AtomicInteger();
        try (var session = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController<
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.Computed>(
                javax.swing.SwingUtilities::invokeLater)) {
            SharedNodeWorkflowFixture.edt(() -> {
                var old = session.open(() -> { });
                var buttons = AlignWayAction.disconnectionChooserControls(f.initial().disconnectionSource(),
                        f.initial().specification().selectedWayKey(), f.initial().specification().selectedRange(),
                        f.initial().specification().permissions(), () -> {
                            if (!session.isCurrent(old)) throw new IllegalStateException("superseded");
                        }, () -> fail("selected choice must not close inertly"), requested::add,
                        failure -> AlignWayAction.closeAndPublishIfCurrent(session, old, published::incrementAndGet));
                buttons.choices().selectOccurrence(1, true); buttons.recompute().doClick();
                assertEquals(java.util.Set.of(1), requested.get(0).sharedNodeDisconnectionIntent().originalNodeKeys().keySet());
                buttons.choices().selectAllEligible(); buttons.recompute().doClick();
                assertEquals(java.util.Set.of(1, 2), requested.get(1).sharedNodeDisconnectionIntent().originalNodeKeys().keySet());
                var latest = session.open(() -> { });
                buttons.recompute().doClick();
                assertTrue(session.isCurrent(latest));
                assertEquals(0, published.get(), "superseded failure must not publish diagnostics");
                assertEquals(2, requested.size());
                var rejected = AlignWayAction.disconnectionChooserControls(f.initial().disconnectionSource(),
                        f.initial().specification().selectedWayKey(), f.initial().specification().selectedRange(),
                        f.initial().specification().permissions(), () -> { throw new IllegalStateException("source changed"); },
                        () -> fail("stale choice must fail"), ignored -> fail("stale source must not restart"),
                        failure -> AlignWayAction.closeAndPublishIfCurrent(session, latest, published::incrementAndGet));
                rejected.choices().selectAllEligible(); rejected.recompute().doClick();
                assertEquals(1, published.get());
                assertFalse(session.isCurrent(latest));
                return null;
            });
        }
    }

    @Test void existingCapturePolicyRejectsNonFixedDisconnectionAuthority() throws Exception {
        var f = SharedNodeWorkflowFixture.create();
        var chosen = f.capture(Map.of(1, SharedNodeWorkflowFixture.key(f.nodes().get(1))));
        var permissions = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions(false, 7.01, 7.01,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy.LEGACY_BOUNDED_MOVE, false,
                chosen.specification().permissions().sharedNodeDisconnectionIntent());
        var failure = assertThrows(IllegalArgumentException.class, () -> SharedNodeWorkflowFixture.edt(() ->
                new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService()
                        .capture(f.data(), f.selection(), f.raster(), f.config(), false, permissions)));
        assertEquals("Disconnection requires selected-way-only fixed-junction policy", failure.getMessage());
    }

    @Test void actualRecomputeButtonRestoresEmptyAuthorityAndInvalidatesConfirmedPlan() throws Exception {
        var f = SharedNodeWorkflowFixture.create();
        var disconnected = f.compute(Map.of(1, SharedNodeWorkflowFixture.key(f.nodes().get(1)),
                2, SharedNodeWorkflowFixture.key(f.nodes().get(2))));
        var previous = PreviewReviewState.fromEditPlan("old", new ModernSingleWayEditPlanAdapter()
                .adapt(disconnected, 0)).confirm();
        assertTrue(previous.canApply());
        var ready = new java.util.concurrent.CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.Computed>();
        try (var session = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController<
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.Computed>(javax.swing.SwingUtilities::invokeLater)) {
            var old = session.open(() -> { });
            SharedNodeWorkflowFixture.edt(() -> {
                var buttons = AlignWayAction.disconnectionChooserControls(disconnected.captured().disconnectionSource(),
                        disconnected.request().selectedWayKey(), disconnected.request().selectedRange(),
                        disconnected.request().permissions(), () -> assertTrue(session.isCurrent(old)),
                        () -> fail("clearing an existing choice must restart"), permissions -> {
                            assertTrue(permissions.sharedNodeDisconnectionIntent().isEmpty());
                            assertTrue(session.close(old));
                            var fresh = session.open(() -> { });
                            session.startDetached(fresh, () -> {
                                var capture = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService()
                                        .capture(f.data(), f.selection(), f.raster(), f.config(), false, permissions);
                                assertNotSame(disconnected.captured(), capture);
                                return captured(capture);
                            }, new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService()::compute,
                                    attempt -> { result.set(attempt.result()); ready.countDown(); });
                        }, failure -> fail(failure));
                buttons.choices().selectOccurrence(1, false);
                buttons.choices().selectOccurrence(2, false);
                buttons.recompute().doClick();
                return null;
            });
            assertTrue(ready.await(45, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(result.get().request().permissions().sharedNodeDisconnectionIntent().isEmpty());
            var composed = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer()
                    .compose(result.get().intervalBatch(), Map.of());
            // The original fixed junctions lie outside this fixture's shifted ridge.
            // Restoring them truthfully yields a blocked fresh preview, never the old confirmed plan.
            assertTrue(composed.plan().isEmpty());
            var displayed = new AlignWayAction.IntervalPreviewState(result.get());
            assertNull(displayed.review());
            assertFalse(displayed.applyAvailable());
            assertSame(f.nodes().get(1), f.way().getNode(1));
            assertSame(f.nodes().get(2), f.way().getNode(2));
        }
    }

    @Test void actualChooserButtonsInitializeCurrentChoicesAndKeepCancelAndEmptyDefaultInert() throws Exception {
        var f = SharedNodeWorkflowFixture.create();
        var chosen = f.capture(Map.of(1, SharedNodeWorkflowFixture.key(f.nodes().get(1))));
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        SharedNodeWorkflowFixture.edt(() -> {
            var current = AlignWayAction.disconnectionChooserControls(chosen.disconnectionSource(),
                    chosen.specification().selectedWayKey(), chosen.specification().selectedRange(),
                    chosen.specification().permissions(), () -> { }, closes::incrementAndGet,
                    ignored -> fail("Cancel must not restart"), failure -> fail(failure));
            assertEquals(java.util.Set.of(1), current.choices().selectedIntent().originalNodeKeys().keySet());
            current.cancel().doClick();
            var empty = AlignWayAction.disconnectionChooserControls(f.initial().disconnectionSource(),
                    chosen.specification().selectedWayKey(), chosen.specification().selectedRange(),
                    f.initial().specification().permissions(), () -> { }, closes::incrementAndGet,
                    ignored -> fail("empty-to-empty must not restart"), failure -> fail(failure));
            empty.recompute().doClick();
            assertEquals(2, closes.get());
            return null;
        });
    }

    @Test void taggedSharedNodeIsExplainedAndCannotBeIncludedBySelectAll() throws Exception {
        var f = SharedNodeWorkflowFixture.create();
        SharedNodeWorkflowFixture.edt(() -> { f.nodes().get(1).put("barrier", "gate"); return null; });
        var fresh = f.capture(Map.of());
        SharedNodeWorkflowFixture.edt(() -> {
            var panel = new org.openstreetmap.josm.plugins.wayheatmaptracer.ui.SharedNodeDisconnectionPanel(
                    fresh.disconnectionSource(), SharedNodeWorkflowFixture.key(f.way()), fresh.specification().selectedRange());
            assertTrue(panel.choices().stream().filter(row -> row.occurrenceIndex() == 1)
                    .findFirst().orElseThrow().eligibility().explanation().contains("manual"));
            panel.selectAllEligible();
            assertEquals(java.util.Set.of(2), panel.selectedIntent().originalNodeKeys().keySet());
            assertThrows(IllegalArgumentException.class, () -> panel.selectOccurrence(1, true));
            return null;
        });
        assertThrows(IllegalStateException.class,
                () -> f.capture(Map.of(1, SharedNodeWorkflowFixture.key(f.nodes().get(1)))));
    }

    @Test void freshOwnedRecomputePublishesOnlyChosenAttemptAndCancelLeavesOriginalNodes() throws Exception {
        var f = SharedNodeWorkflowFixture.create();
        var service = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var published = new java.util.concurrent.CountDownLatch(1);
        var latest = new java.util.concurrent.atomic.AtomicReference<org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.Computed>();
        try (var session = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController<
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.Computed>(javax.swing.SwingUtilities::invokeLater)) {
            var first = session.open(() -> { });
            SharedNodeWorkflowFixture.edt(() -> session.startDetached(first, () -> captured(f.initial()),
                    (input, context) -> { entered.countDown(); release.await(); context.checkpoint();
                        return service.compute(input, context); }, attempt -> fail("cancelled old attempt published")));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var chosen = f.capture(Map.of(1, SharedNodeWorkflowFixture.key(f.nodes().get(1)),
                    2, SharedNodeWorkflowFixture.key(f.nodes().get(2))));
            var second = session.open(() -> { });
            assertFalse(session.close(first), "stale close must not cancel the new recompute");
            SharedNodeWorkflowFixture.edt(() -> session.startDetached(second, () -> captured(chosen),
                    service::compute, attempt -> { latest.set(attempt.result()); published.countDown(); }));
            release.countDown();
            assertTrue(published.await(45, java.util.concurrent.TimeUnit.SECONDS));
            assertSame(chosen, latest.get().captured());
            assertEquals(java.util.Set.of(1, 2), latest.get().request().permissions()
                    .sharedNodeDisconnectionIntent().originalNodeKeys().keySet());
            assertSame(f.nodes().get(1), f.way().getNode(1));
            assertTrue(session.close(second));
            assertSame(f.nodes().get(2), f.way().getNode(2));
        } finally { release.countDown(); }
    }

    private static org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob.CapturedAttempt<
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.Captured> captured(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.Captured input) {
        return new org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob.CapturedAttempt<>(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob.AttemptSnapshot(
                        input.network().snapshotId(), "synthetic-source", input.settingsHash(), input.network().canonicalHash()), input);
    }
    @Test void chooserPreservesConnectionsByDefaultAndSelectsOnlyExplicitOccurrences() throws Exception {
        var f = SharedNodeWorkflowFixture.create();
        SharedNodeWorkflowFixture.edt(() -> {
            var panel = new org.openstreetmap.josm.plugins.wayheatmaptracer.ui.SharedNodeDisconnectionPanel(
                    f.initial().disconnectionSource(), SharedNodeWorkflowFixture.key(f.way()),
                    f.initial().specification().selectedRange());
            assertTrue(panel.selectedIntent().isEmpty());
            assertEquals(2, panel.choices().size());
            panel.selectOccurrence(1, true);
            assertEquals(java.util.Set.of(1), panel.selectedIntent().originalNodeKeys().keySet());
            assertEquals(1, panel.affectedConnectionCount());
            panel.selectAllEligible();
            assertEquals(java.util.Set.of(1, 2), panel.selectedIntent().originalNodeKeys().keySet());
            assertEquals(2, panel.affectedConnectionCount());
            // Closing a chooser discards the panel, and the next chooser starts preserved.
            var next = new org.openstreetmap.josm.plugins.wayheatmaptracer.ui.SharedNodeDisconnectionPanel(
                    f.initial().disconnectionSource(), SharedNodeWorkflowFixture.key(f.way()),
                    f.initial().specification().selectedRange());
            assertTrue(next.selectedIntent().isEmpty());
            assertSame(f.nodes().get(1), f.way().getNode(1));
            return null;
        });
    }
    @Test void explicitDisconnectionRequiresConfirmationEvenWithCompleteGeometry() throws Exception {
        var fixture = SharedNodeWorkflowFixture.create();
        var computed = fixture.compute(Map.of(1, SharedNodeWorkflowFixture.key(fixture.nodes().get(1)),
                2, SharedNodeWorkflowFixture.key(fixture.nodes().get(2))));
        var plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        // Complete support and no contact review must still require disconnection consent.
        plan = new AlignmentEditPlan(plan.selectedWayKey(), plan.selectedRange(), plan.before(),
                plan.after(), plan.metricFrame(), plan.permissions(), plan.settingsHash(), plan.evidenceHash(),
                plan.parameterHash(), plan.routeIdentity(), plan.finalPreviewWays(),
                new ValidationReport(ValidationReport.Disposition.APPLICABLE, java.util.List.of()),
                plan.disconnectionSource(), plan.disconnectedOccurrenceReplacements(), plan.disconnectionGeometryMode());
        var review = PreviewReviewState.fromEditPlan("selected", plan);
        assertFalse(review.canApply(), "a complete ridge must not bypass explicit disconnection confirmation");
        assertTrue(review.confirm().canApply());
        assertFalse(review.confirm().matches(review.withCandidate("other")));
    }
}
