package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.SharedNodeWorkflowFixture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;

class V022SharedNodeDisconnectionReplayTest {
    @TempDir Path temporary;
    @Test void emptyIntentIntervalContactsAreRequiredAndRecomputedByActualComposition() throws Exception {
        var f = SharedNodeWorkflowFixture.create(AlignmentMode.PRECISE_SHAPE, true);
        SharedNodeWorkflowFixture.edt(() -> {
            for (var way : List.copyOf(f.data().getWays()))
                if (way != f.way() && way.containsNode(f.nodes().get(10))) f.data().removePrimitive(way);
            addStream(f);
            return null;
        });
        var computed = f.compute(Map.of());
        assertTrue(computed.partitioned());
        var batch = computed.intervalBatch();
        var assessment = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer()
                .compose(batch, Map.of());
        assertTrue(assessment.applyAvailable(), assessment.intervals().toString());
        var plan = assessment.plan().orElseThrow();
        var input = new FrozenReplayInput(batch.fullRequest(), batch.evidence(), batch.network(), batch.options());
        var bundle = Format15ProductionBundleFactory.withCurrentNumericalPolicy(
                Format15ProductionBundleFactory.createLiveIntervals("synthetic-empty-interval", batch, assessment, Map.of(),
                        new Format15ProductionBundleFactory.VisibleLayerSourceReceipt(0L, 15, "a".repeat(64)),
                        Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW, null, null), input,
                batch.fullRequest().engine(), batch.runs().stream().map(run -> run.result().inference()).toList());
        var receipt = Format15ArchiveReader.parseObject(bundle.artifact("contact-review.json").bytes(), "interval-contact");
        var review = (Map<?, ?>) receipt.get("review");
        assertEquals(plan.canonicalHash(), review.get("planIdentity"));
        assertFalse(((List<?>) review.get("contacts")).isEmpty());
        assertEquals(true, review.get("confirmationRequired"));
        var archive = archive(bundle, "empty-interval.zip");
        assertEquals(plan.canonicalHash(), Format15ReplayRunner.replayIntervals(archive,
                archive.sourceIdentityHash(), archive.parameterHash()).assessment().plan().orElseThrow().canonicalHash());
        for (boolean missing : List.of(true, false)) {
            var changed = new java.util.LinkedHashMap<>(bundle.artifacts());
            if (missing) changed.remove("contact-review.json");
            else changed.put("contact-review.json", Format15Artifact.text("contact-review.json", "{\"schema\":1}\n"));
            var bad = archive(new Format15Bundle(bundle.buildIdentity(), bundle.sourceIdentityHash(),
                    bundle.parameterHash(), changed), "empty-interval-invalid-" + missing + ".zip");
            assertThrows(RuntimeException.class, () -> Format15ReplayRunner.replayIntervals(bad,
                    bad.sourceIdentityHash(), bad.parameterHash()));
        }
    }

    @Test void nonFixedContactReceiptExplicitlyRefusesUnsupportedReplayAndCannotFallBack() throws Exception {
        var f = SharedNodeWorkflowFixture.create();
        SharedNodeWorkflowFixture.edt(() -> {
            for (var way : List.copyOf(f.data().getWays())) if (way != f.way()) f.data().removePrimitive(way);
            addStream(f);
            return null;
        });
        var permissions = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions(false, 7.01, 7.01,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy.LEGACY_BOUNDED_MOVE, false);
        var service = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService();
        var capture = SharedNodeWorkflowFixture.edt(() -> service.capture(f.data(), f.selection(), f.raster(),
                f.config(), false, permissions));
        var computed = service.compute(capture, org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE);
        var plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        var input = new FrozenReplayInput(computed.request(), computed.evidence(), capture.network(), computed.options(),
                capture.searchRadiusMeters());
        var bundle = Format15ProductionBundleFactory.withCurrentNumericalPolicy(
                Format15ProductionBundleFactory.createLive("synthetic-non-fixed", input, computed.pipeline(),
                        "preview", "visible-layer", 0, plan, false, false), input, computed.request().engine(),
                List.of(computed.pipeline().inference()));
        assertNotNull(bundle.artifact("contact-review.json"));
        var status = Format15ArchiveReader.parseObject(bundle.artifact("attempt-status.json").bytes(), "status");
        assertEquals("UNAVAILABLE_NON_FIXED", status.get("contactEditPlanReplay"));
        for (int mutation = 0; mutation < 3; mutation++) {
            var changed = new java.util.LinkedHashMap<>(bundle.artifacts());
            if (mutation == 1) changed.remove("contact-review.json");
            if (mutation == 2) changed.put("contact-review.json", Format15Artifact.text("contact-review.json", "{\"schema\":1}\n"));
            var archive = archive(new Format15Bundle(bundle.buildIdentity(), bundle.sourceIdentityHash(),
                    bundle.parameterHash(), changed), "non-fixed-" + mutation + ".zip");
            var refused = assertThrows(RuntimeException.class, () -> Format15ReplayRunner.replay(archive,
                    ReplayLevel.FINAL_GEOMETRY, archive.sourceIdentityHash(), archive.parameterHash()));
            assertTrue(refused.getMessage().contains(mutation == 1 ? "contact-production-review-unavailable"
                    : "contact-production-replay-unavailable"), refused.getMessage());
        }
    }

    private static void addStream(SharedNodeWorkflowFixture f) {
        var a = new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(-0.0001, 0));
        var b = new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(0.0001, 0));
        a.setOsmId(1001, 1); b.setOsmId(1002, 1); a.setModified(false); b.setModified(false);
        f.data().addPrimitive(a); f.data().addPrimitive(b);
        var stream = new org.openstreetmap.josm.data.osm.Way(); stream.setNodes(List.of(a, b));
        stream.setOsmId(1003, 1); stream.put("waterway", "stream"); stream.setModified(false); f.data().addPrimitive(stream);
    }

    @ParameterizedTest
    @EnumSource(value = AlignmentMode.class, names = {"PRECISE_SHAPE", "MOVE_EXISTING_NODES"})
    void partialSelectionRetainsExactProposedPortReplacementsThroughWireAndProductionReplay(AlignmentMode mode) throws Exception {
        var f = SharedNodeWorkflowFixture.create(mode, false, true);
        var computed = f.compute(Map.of(1, SharedNodeWorkflowFixture.key(f.nodes().get(1)),
                2, SharedNodeWorkflowFixture.key(f.nodes().get(2))));
        var plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertEquals(2, plan.after().proposedPortReplacements().size());
        var copied = FrozenReplayCodec.decodeEditPlan(FrozenReplayCodec.encodeEditPlan(plan));
        assertEquals(plan.after().proposedPortReplacements(), copied.after().proposedPortReplacements());
        assertEquals(plan.canonicalHash(), copied.canonicalHash());
        var input = new FrozenReplayInput(computed.request(), computed.evidence(), computed.captured().network(),
                computed.options(), computed.captured().searchRadiusMeters());
        var restored = FrozenReplayCodec.decode(FrozenReplayCodec.encode(input));
        var actual = Format15ReplayRunner.replay(restored, ReplayLevel.FINAL_GEOMETRY, restored.request().engine());
        var receipt = Format15ArchiveReader.parseObject(actual.disconnectionPlanReviews().get(0)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "partial-review");
        assertEquals(plan.canonicalHash(), receipt.get("planIdentity"));
        assertEquals(2, ((List<?>) receipt.get("proposedPorts")).size());
        assertEquals(plan.disconnectionGeometryMode(), restored.request().geometryMode());
    }
    @Test void emptyIntentStreamContactRequiresAndReplaysActualReviewEvidence() throws Exception {
        var f = SharedNodeWorkflowFixture.create();
        SharedNodeWorkflowFixture.edt(() -> {
            for (var way : List.copyOf(f.data().getWays())) if (way != f.way()) f.data().removePrimitive(way);
            var a = new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(-0.0001, 0));
            var b = new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(0.0001, 0));
            a.setOsmId(1001, 1); b.setOsmId(1002, 1); a.setModified(false); b.setModified(false);
            f.data().addPrimitive(a); f.data().addPrimitive(b);
            var stream = new org.openstreetmap.josm.data.osm.Way(); stream.setNodes(List.of(a, b));
            stream.setOsmId(1003, 1); stream.put("waterway", "stream"); stream.setModified(false); f.data().addPrimitive(stream);
            return null;
        });
        var computed = f.compute(Map.of());
        assertFalse(computed.partitioned());
        var plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertTrue(plan.validation().reviewRequired());
        assertTrue(org.openstreetmap.josm.plugins.wayheatmaptracer.service.ModernPlanReview.summary(
                plan, computed.evidence(), computed.captured().searchRadiusMeters()).contains(
                        "The proposed path crosses a stream. Check the crossing before applying."));
        var input = new FrozenReplayInput(computed.request(), computed.evidence(), computed.captured().network(),
                computed.options(), computed.captured().searchRadiusMeters());
        var bundle = Format15ProductionBundleFactory.withCurrentNumericalPolicy(
                Format15ProductionBundleFactory.createLive("synthetic-stream", input, computed.pipeline(),
                        "confirmed", "visible-layer", 0, plan, true, false), input,
                computed.request().engine(), List.of(computed.pipeline().inference()));
        var receipt = Format15ArchiveReader.parseObject(bundle.artifact("contact-review.json").bytes(), "contact-review");
        assertEquals(plan.canonicalHash(), receipt.get("planIdentity"));
        assertFalse(((List<?>) receipt.get("contacts")).isEmpty());
        assertTrue(((List<?>) receipt.get("occurrences")).isEmpty());
        assertEquals(true, receipt.get("confirmationRequired"));
        var archive = archive(bundle, "stream.zip");
        assertDoesNotThrow(() -> Format15ReplayRunner.replay(archive, ReplayLevel.FINAL_GEOMETRY,
                archive.sourceIdentityHash(), archive.parameterHash()));
        for (boolean missing : List.of(true, false)) {
            var changed = new java.util.LinkedHashMap<>(bundle.artifacts());
            if (missing) changed.remove("contact-review.json");
            else changed.put("contact-review.json", Format15Artifact.text("contact-review.json", "{\"schema\":1}\n"));
            var invalid = archive(new Format15Bundle(bundle.buildIdentity(), bundle.sourceIdentityHash(),
                    bundle.parameterHash(), changed), "stream-invalid-" + missing + ".zip");
            assertThrows(RuntimeException.class, () -> Format15ReplayRunner.replay(invalid,
                    ReplayLevel.FINAL_GEOMETRY, invalid.sourceIdentityHash(), invalid.parameterHash()));
        }
    }
    @ParameterizedTest
    @EnumSource(value = AlignmentMode.class, names = {"PRECISE_SHAPE", "MOVE_EXISTING_NODES"})
    void actualProductionReplayReplansReplacementsContactsAndExactConfirmation(AlignmentMode mode) throws Exception {
        var f = SharedNodeWorkflowFixture.create(mode, false);
        var computed = f.compute(Map.of(1, SharedNodeWorkflowFixture.key(f.nodes().get(1)),
                2, SharedNodeWorkflowFixture.key(f.nodes().get(2))));
        var input = new FrozenReplayInput(computed.request(), computed.evidence(), computed.captured().network(),
                computed.options(), computed.captured().searchRadiusMeters());
        var plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertTrue(plan.validation().reviewRequired());
        var bundle = Format15ProductionBundleFactory.withCurrentNumericalPolicy(
                Format15ProductionBundleFactory.createLive("synthetic-task5", input, computed.pipeline(),
                        "confirmed", "visible-layer", 0, plan, true, false), input,
                computed.request().engine(), List.of(computed.pipeline().inference()));
        var archive = archive(bundle, "single-" + mode + ".zip");
        var actual = Format15ReplayRunner.replay(archive, ReplayLevel.FINAL_GEOMETRY,
                archive.sourceIdentityHash(), archive.parameterHash());
        assertFalse(actual.disconnectionPlanReviews().isEmpty());
        var receipt = Format15ArchiveReader.parseObject(actual.disconnectionPlanReviews().get(0)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "actual-review");
        assertEquals(plan.canonicalHash(), receipt.get("planIdentity"));
        assertEquals(true, receipt.get("confirmationRequired"));
        assertEquals(2, ((List<?>) receipt.get("occurrences")).size());
        String candidate = input.options().sourceTier() + ":" + input.request().evidenceContentHash()
                + ":0:" + computed.pipeline().routes().get(0).hypothesis().id();
        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState
                .fromEditPlan(candidate, plan).reviewHash(), receipt.get("confirmationIdentity"));
        var expectation = FinalReplayExpectation.read(archive).orElseThrow();
        assertTrue(expectation.matches(actual));
        assertEquals(actual.disconnectionPlanReviews(), FinalOutputComponentsCodec.decode(
                archive.artifact(FinalOutputComponentsCodec.ARTIFACT).orElseThrow().bytes(),
                expectation.componentsBinding()).output().disconnectionPlanReviews());
        var bypassed = new Format15ReplayRunner.Result(actual.level(), actual.capturedEngine(), actual.engine(),
                actual.inference(), actual.routes(), actual.inputHash());
        assertFalse(expectation.matches(bypassed), "identical geometry cannot conceal bypassed disconnection planning");
        assertThrows(IllegalArgumentException.class, () -> FinalReplayExpectation.capture("synthetic-task5", input, bypassed));
        var changed = new java.util.LinkedHashMap<>(bundle.artifacts());
        changed.put(DisconnectionReplaySemantics.ARTIFACT, Format15Artifact.text(
                DisconnectionReplaySemantics.ARTIFACT, "{\"schema\":1,\"routeReviews\":[]}\n"));
        var tampered = archive(new Format15Bundle(bundle.buildIdentity(), bundle.sourceIdentityHash(),
                bundle.parameterHash(), changed), "tampered-" + mode + ".zip");
        assertThrows(RuntimeException.class, () -> Format15ReplayRunner.replay(tampered,
                ReplayLevel.FINAL_GEOMETRY, tampered.sourceIdentityHash(), tampered.parameterHash()));
    }

    @Test void intervalReplayRetainsOriginalSourceAndEveryChosenOccurrenceOwner() throws Exception {
        var f = SharedNodeWorkflowFixture.create(AlignmentMode.PRECISE_SHAPE, true);
        var computed = f.compute(Map.of(10, SharedNodeWorkflowFixture.key(f.nodes().get(10))));
        assertTrue(computed.partitioned(), "the unchosen shared node must remain a frozen island");
        var batch = computed.intervalBatch();
        var assessment = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer()
                .compose(batch, Map.of());
        assertTrue(assessment.applyAvailable(), assessment.intervals() + "; unowned=" + assessment.unownedDisconnectionOccurrences());
        var plan = assessment.plan().orElseThrow();
        assertEquals(java.util.Set.of(10), plan.disconnectedOccurrenceReplacements().keySet());
        var input = new FrozenReplayInput(batch.fullRequest(), batch.evidence(), batch.network(), batch.options());
        var bundle = Format15ProductionBundleFactory.withCurrentNumericalPolicy(
                Format15ProductionBundleFactory.createLiveIntervals("synthetic-interval", batch,
                        assessment, Map.of(), new Format15ProductionBundleFactory.VisibleLayerSourceReceipt(0L, 15, "a".repeat(64)),
                        Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW, null, null), input,
                batch.fullRequest().engine(), batch.runs().stream().map(run -> run.result().inference()).toList());
        var archive = archive(bundle, "interval.zip");
        var provenance = Format15ArchiveReader.parseObject(archive.artifact("private/interval-point-provenance.json")
                .orElseThrow().bytes(), "interval-provenance");
        assertEquals(2L, provenance.get("schema"));
        var disconnected = ((List<?>) provenance.get("points")).stream().map(row -> (Map<?, ?>) row)
                .map(row -> (Map<?, ?>) row.get("pointId"))
                .filter(id -> "DISCONNECTED_WAY_NODE_OCCURRENCE".equals(id.get("kind"))).toList();
        assertEquals(1, disconnected.size());
        assertEquals(SharedNodeWorkflowFixture.key(f.nodes().get(10)).toString(), disconnected.get(0).get("originalNodeKey"));
        assertEquals(plan.disconnectedOccurrenceReplacements().get(10).toString(), disconnected.get(0).get("replacementNodeKey"));
        var state = new org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction.IntervalPreviewState(computed);
        assertFalse(state.applyAvailable());
        state.confirmReview();
        assertTrue(state.applyAvailable());
        var receipt = Format15ArchiveReader.parseObject(archive.artifact(DisconnectionReplaySemantics.ARTIFACT)
                .orElseThrow().bytes(), "interval-review");
        assertEquals(state.review().reviewHash(), ((Map<?, ?>) receipt.get("review")).get("confirmationIdentity"));
        state.choose(0, 0);
        assertFalse(state.applyAvailable(), "choosing a route must invalidate confirmation");
        var result = Format15ReplayRunner.replayIntervals(archive, archive.sourceIdentityHash(), archive.parameterHash());
        assertEquals(plan.canonicalHash(), result.assessment().plan().orElseThrow().canonicalHash());
        assertTrue(result.assessment().unownedDisconnectionOccurrences().isEmpty());
        assertEquals(batch.fullRequest().disconnectionSource(), result.batch().fullRequest().disconnectionSource());
    }

    @Test void noSignalIntervalReplayExportsEveryUnownedChoiceWithoutInventingAPlan() throws Exception {
        var f = SharedNodeWorkflowFixture.create(AlignmentMode.PRECISE_SHAPE, true);
        int[] dark = f.raster().argb(); java.util.Arrays.fill(dark, 0xff000000);
        var raster = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster(
                f.raster().width(), f.raster().height(), dark, -100, -100, 100, 100,
                1, 1, java.util.OptionalDouble.of(1), "synthetic-no-signal", "EPSG:3857");
        var fixture = new SharedNodeWorkflowFixture(f.data(), f.way(), f.nodes(), f.selection(), f.config(), raster, f.initial());
        var computed = fixture.compute(Map.of(10, SharedNodeWorkflowFixture.key(f.nodes().get(10))));
        var batch = computed.intervalBatch();
        var assessment = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer()
                .compose(batch, Map.of());
        assertTrue(assessment.plan().isEmpty());
        assertEquals(List.of(10), assessment.unownedDisconnectionOccurrences());
        var input = new FrozenReplayInput(batch.fullRequest(), batch.evidence(), batch.network(), batch.options());
        var bundle = Format15ProductionBundleFactory.withCurrentNumericalPolicy(
                Format15ProductionBundleFactory.createLiveIntervals("synthetic-no-signal", batch, assessment, Map.of(),
                        new Format15ProductionBundleFactory.VisibleLayerSourceReceipt(0L, 15, "a".repeat(64)),
                        Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW, null, null), input,
                batch.fullRequest().engine(), batch.runs().stream().map(run -> run.result().inference()).toList());
        var receipt = Format15ArchiveReader.parseObject(bundle.artifact(DisconnectionReplaySemantics.ARTIFACT).bytes(), "unowned");
        assertEquals(List.of(10L), receipt.get("unownedDisconnectionOccurrences"));
        assertNull(receipt.get("review"));
        var archive = archive(bundle, "no-signal-interval.zip");
        var result = Format15ReplayRunner.replayIntervals(archive, archive.sourceIdentityHash(), archive.parameterHash());
        assertTrue(result.assessment().plan().isEmpty());
        assertEquals(List.of(10), result.assessment().unownedDisconnectionOccurrences());
    }

    private Format15Archive archive(Format15Bundle bundle, String name) throws Exception {
        Path file = temporary.resolve(name); Format15BundleWriter.write(bundle, file); return Format15ArchiveReader.read(file);
    }
    @Test void sourceOccurrenceReplacementsAndConfirmationSurviveProductionWire() throws Exception {
        var fixture = SharedNodeWorkflowFixture.create();
        var computed = fixture.compute(Map.of(1, SharedNodeWorkflowFixture.key(fixture.nodes().get(1)),
                2, SharedNodeWorkflowFixture.key(fixture.nodes().get(2))));
        var input = new FrozenReplayInput(computed.request(), computed.evidence(), computed.captured().network(), computed.options());
        byte[] encoded = FrozenReplayCodec.encode(input);
        var decoded = assertDoesNotThrow(() -> FrozenReplayCodec.decode(encoded));
        byte[] falseLegacy = encoded.clone(); falseLegacy[7] = 4;
        var invalid = assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.decode(falseLegacy));
        assertEquals("Legacy frozen schema cannot carry disconnection authority", invalid.getCause().getMessage());
        assertEquals(input.canonicalHash(), decoded.canonicalHash());
        assertEquals(computed.request().disconnectionSource(), decoded.request().disconnectionSource());
        var plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        var copied = FrozenReplayCodec.decodeEditPlan(FrozenReplayCodec.encodeEditPlan(plan));
        assertEquals(plan.canonicalHash(), copied.canonicalHash());
        assertEquals(plan.disconnectedOccurrenceReplacements(), copied.disconnectedOccurrenceReplacements());
    }
}
