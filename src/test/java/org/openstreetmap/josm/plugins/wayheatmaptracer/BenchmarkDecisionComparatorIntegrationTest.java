package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Artifact;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Bundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15BundleWriter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoverySettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Exercises the actual private comparator entry point, original OSM pin and capture wiring. */
class BenchmarkDecisionComparatorIntegrationTest {
    private static LiveBPreviewService.Computed computed;
    private static AlignmentEditPlan plan;
    private static DataSet original;
    private static NativeFixture partial;
    private static NativeFixture moveFull;
    private static NativeFixture movePartial;

    @BeforeAll
    static void produceActualManagedFixedPlan() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        NativeFixture full = produceFixture(false);
        original = full.original(); computed = full.computed(); plan = full.plan();
        partial = produceFixture(true);
        moveFull = produceFixture(false, true);
        movePartial = produceFixture(true, true);
    }

    private static NativeFixture produceFixture(boolean partial) throws Exception {
        return produceFixture(partial, false);
    }

    private static NativeFixture produceFixture(boolean partial, boolean move) throws Exception {
        DataSet original = new DataSet();
        Node west = node(1, -8, 0), east = node(2, 8, 0);
        Node south = node(33, 0.17345, -10), north = node(34, 0.17345, 10);
        Way selected = way(10, "highway", "path", west, east);
        Node middle = null;
        if (move) { middle = node(7, 0, 0.30000000000000004); original.addPrimitive(middle); }
        if (partial) {
            Node prefix = node(3, -10, 0), first = node(4, 10, 0), second = node(5, 12, 0), third = node(6, 14, 0);
            for (Node node : List.of(prefix, first, second, third)) original.addPrimitive(node);
            selected.setNodes(move ? List.of(prefix, west, middle, east, first, second, third)
                    : List.of(prefix, west, east, first, second, third)); selected.setModified(false);
        } else if (move) {
            selected.setNodes(List.of(west, middle, east)); selected.setModified(false);
        }
        Way cliff = way(35, "natural", "cliff", south, north);
        for (Node node : List.of(west, east, south, north)) original.addPrimitive(node);
        original.addPrimitive(selected); original.addPrimitive(cliff);
        Node a = node(60, 4, 4), b = node(61, 5, 4), c = node(62, 5, 5), d = node(63, 4, 5);
        for (Node node : List.of(a, b, c, d)) original.addPrimitive(node);
        Way outer = way(65, "name", "synthetic forest", a, b, c, d, a);
        original.addPrimitive(outer);
        Relation forest = new Relation(); forest.setOsmId(70, 1);
        forest.put("type", "multipolygon"); forest.put("landuse", "forest");
        forest.setMembers(List.of(new RelationMember("outer", outer))); forest.setModified(false);
        original.addPrimitive(forest);
        if (partial) original.setSelected(List.of(selected, west, east));
        else original.setSelected(selected);
        var fixture = BenchmarkHostMain.fixtureSource(37);
        PluginPreferences.save(move ? fixture.config().withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES)
                : fixture.config()); fixture.saveCacheGenerationPreference();
        PluginPreferences.saveTracingSettings(new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false, AlignmentSourceMode.MANAGED_TILES));
        PluginPreferences.saveGeometryCleanup(GeometryCleanupConfig.disabled());
        var seed = BenchmarkHostMain.onEventThread(() -> new LiveBPreviewService().captureManagedSeed(original,
                SelectionResolver.resolve(original, false),
                new AlignmentConfig(PluginPreferences.load(), GeometryCleanupConfig.disabled()), fixture.sourceIdentity()));
        BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 600; y++) {
            // A narrow, centered ridge has measured below-threshold brackets inside the
            // unchanged 7.01m decision window at this fixture's factual native z15 pitch.
            int gray = (int) Math.round(255 * (0.01 + 0.96 * Math.exp(
                    -0.5 * (y - 299.5) * (y - 299.5) / (0.35 * 0.35))));
            for (int x = 0; x < 600; x++) image.setRGB(x, y, 0xff000000 | gray << 16 | gray << 8 | gray);
        }
        boolean[] valid = new boolean[600 * 600]; Arrays.fill(valid, true);
        double equator = Math.scalb(256.0, 15) / 2;
        var captured = new LiveBPreviewService().attachManagedRaster(seed,
                new ManagedModernPreviewSource.Raster(image, valid,
                        SupportedInputRasterTransform.webMercator(15, equator - 150, equator - 150, 2),
                        "hot", 15, fixture.sourceIdentity(), new ManagedTileGeneration(37)));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(captured, CancellationProbe.NONE);
        assertFalse(computed.pipeline().routes().isEmpty(), computed.pipeline().inference().status()
                + ": " + computed.pipeline().inference().explanation());
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertTrue(plan.validation().reviewRequired());
        assertTrue(plan.validation().findingCodes().contains("inherited-nontransport-contact-review-required"));
        return new NativeFixture(original, computed, plan);
    }

    @Test
    void actualPartialSupplierObservesAllSelectedPointsWithoutFourUntouchedOutsideOccurrences() throws Exception {
        var owner = partial.computed(); var planned = partial.plan();
        var route = owner.pipeline().routes().get(0);
        var expected = route.hypothesis().points().stream().map(owner.evidence().coordinateFrame()::toGeographic).toList();
        var full = planned.finalPreviewWays().get(planned.selectedWayKey());
        assertEquals(1, planned.selectedRange().firstIndex());
        assertEquals(3, ((org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay)
                planned.before().primitives().get(planned.selectedWayKey())).nodeKeys().size()
                - planned.selectedRange().lastIndex() - 1);
        assertEquals(expected.size() + 4, full.size(), "exactly four original outside occurrences");
        Class<?> entry = Class.forName("org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction$BenchmarkPreviewRoute");
        Constructor<?> constructor = entry.getDeclaredConstructor(
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Route.class,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame.class);
        constructor.setAccessible(true);
        Method supplier = org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction.class
                .getDeclaredMethod("benchmarkDisplayedGeometry", List.class, int.class, int.class, List.class);
        supplier.setAccessible(true);
        Object observed = supplier.invoke(null, List.of(constructor.newInstance(route,
                owner.evidence().coordinateFrame())), 0, 0,
                org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction
                        .benchmarkSelectedPlanGeometry(planned, owner, 0));
        assertEquals(expected, observed, "single-preview receipt must hash the same selected interval in both plan modes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"precise-full", "precise-partial", "move-full", "move-partial"})
    void actualNativePlanSelectedGeometryIsExactInBothModes(String kind) {
        NativeFixture fixture = switch (kind) {
            case "precise-full" -> new NativeFixture(original, computed, plan);
            case "precise-partial" -> partial;
            case "move-full" -> moveFull;
            case "move-partial" -> movePartial;
            default -> throw new AssertionError(kind);
        };
        var owner = fixture.computed();
        var route = owner.pipeline().routes().get(0);
        var selected = org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction
                .benchmarkSelectedPlanGeometry(fixture.plan(), owner, 0);
        var whole = fixture.plan().finalPreviewWays().get(fixture.plan().selectedWayKey());
        int prefix = kind.endsWith("partial") ? 1 : 0, suffix = kind.endsWith("partial") ? 3 : 0;
        assertEquals(whole.subList(prefix, whole.size() - suffix), selected,
                "actual selected plan coordinates must remain exact");
        assertEquals(route.pointIds().size(), selected.size());
        if (kind.startsWith("move")) {
            assertEquals(3, selected.size(), "Move observes actual sparse assignments, not dense raw inference");
            assertTrue(route.rawHypothesis().points().size() > selected.size());
            var first = (FinalRoutePointId.ExistingWayNodeOccurrence) route.pointIds().get(0);
            var original = ((DetachedNode) fixture.plan().before().primitives().get(first.nodeKey())).coordinate();
            assertEquals(original, selected.get(0), "fixed original geographic bits survive frame conversion");
            assertFalse(original.equals(owner.evidence().coordinateFrame().toGeographic(
                    route.assignments().get(first))), "fixture must expose a real forward roundtrip difference");
        }
    }

    @Test
    void selectedObservationRejectsAbsentStaleAndSameCoreMissingWitnessOwners() throws Exception {
        assertThrows(IllegalStateException.class, () -> org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction
                .benchmarkSelectedPlanGeometry(null, partial.computed(), 0));
        assertThrows(IllegalStateException.class, () -> org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction
                .benchmarkSelectedPlanGeometry(partial.plan(), computed, 0));
        assertThrows(IllegalStateException.class, () -> org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction
                .benchmarkSelectedPlanGeometry(partial.plan(), partial.computed(), -1));
        var owner = partial.computed(); var network = owner.captured().network();
        var old = new NetworkSnapshot(network.snapshotId(), network.role(), network.datasetIdentity(),
                network.sourceGeneration(), network.closure(), network.primitives(), network.incomingReferrerWatches());
        assertEquals(network.canonicalHash(), old.canonicalHash(), "witness is additive to historical core identity");
        var captured = recordWith(owner.captured(), "network", old);
        var stale = recordWith(owner, "captured", captured);
        var rejected = assertThrows(IllegalStateException.class, () ->
                org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction
                        .benchmarkSelectedPlanGeometry(partial.plan(), stale, 0));
        assertEquals("Benchmark selected plan differs from its native owner", rejected.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static <T> T recordWith(T original, String component, Object changed) throws Exception {
        var fields = original.getClass().getRecordComponents();
        Class<?>[] types = new Class<?>[fields.length]; Object[] values = new Object[fields.length];
        for (int index = 0; index < fields.length; index++) {
            types[index] = fields[index].getType();
            values[index] = fields[index].getName().equals(component) ? changed : fields[index].getAccessor().invoke(original);
        }
        return (T) original.getClass().getDeclaredConstructor(types).newInstance(values);
    }

    @ParameterizedTest
    @ValueSource(strings = {"networkSnapshotId", "networkContentHash", "evidenceSnapshotId", "evidenceContentHash"})
    void selectedObservationRejectsRequestWhoseNativeOwnerBindingDiffers(String component) throws Exception {
        var owner = partial.computed();
        var stale = recordWith(owner, "request", recordWith(owner.request(), component, "different-frozen-binding"));
        var rejected = assertThrows(IllegalStateException.class, () ->
                org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction
                        .benchmarkSelectedPlanGeometry(partial.plan(), stale, 0));
        assertEquals("Benchmark selected plan differs from its native owner", rejected.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"boundary", "generated-boundary", "order", "repeat", "missing-assignment", "route-id"})
    void selectedObservationRejectsMalformedNativeOwnership(String mutation) throws Exception {
        var owner = partial.computed(); var plan = partial.plan(); var route = owner.pipeline().routes().get(0);
        if (mutation.equals("route-id")) {
            AlignmentEditPlan changed = recordWith(plan, "routeIdentity", "different-native-route");
            var rejected = assertThrows(IllegalStateException.class, () ->
                    org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction
                            .benchmarkSelectedPlanGeometry(changed, owner, 0));
            assertEquals("Benchmark selected plan differs from its native owner", rejected.getMessage());
            return;
        }
        List<FinalRoutePointId> ids = new ArrayList<>(route.pointIds());
        if (mutation.equals("boundary")) {
            var first = (FinalRoutePointId.ExistingWayNodeOccurrence) ids.get(0);
            ids.set(0, new FinalRoutePointId.ExistingWayNodeOccurrence(first.wayKey(), first.nodeKey(),
                    first.originalOccurrenceIndex() + 1));
        } else if (mutation.equals("generated-boundary")) {
            ids.set(0, new FinalRoutePointId.GeneratedCandidatePoint(route.hypothesis().id(), 999));
        } else if (mutation.equals("order")) {
            java.util.Collections.swap(ids, 1, 2);
        } else if (mutation.equals("repeat")) {
            ids.set(1, ids.get(0));
        }
        var assignments = new LinkedHashMap<FinalRoutePointId, org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint>();
        var ownership = new LinkedHashMap<FinalRoutePointId, org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership>();
        for (int index = 0; index < ids.size(); index++) {
            assignments.put(ids.get(index), route.hypothesis().points().get(index));
            ownership.put(ids.get(index), route.sourceOwnership().get(route.pointIds().get(index)));
        }
        if (mutation.equals("missing-assignment")) assignments.remove(ids.get(1));
        if (mutation.equals("repeat") || mutation.equals("missing-assignment")) {
            var rejected = assertThrows(IllegalArgumentException.class, () ->
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Route(
                            route.rawHypothesis(), route.hypothesis(), ids, assignments, ownership,
                            route.quality(), route.cleanupStatus(), route.geometryChanged()));
            assertEquals("Modern final route provenance is incomplete or reordered", rejected.getMessage());
            return;
        }
        var changedRoute = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Route(
                route.rawHypothesis(), route.hypothesis(), ids, assignments, ownership,
                route.quality(), route.cleanupStatus(), route.geometryChanged());
        var changed = recordWith(owner, "pipeline",
                new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Result(
                        owner.pipeline().inference(), List.of(changedRoute)));
        var rejected = assertThrows(IllegalStateException.class, () ->
                org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction
                        .benchmarkSelectedPlanGeometry(plan, changed, 0));
        assertEquals(mutation.equals("boundary") ? "Benchmark selected native occurrence identity differs"
                : "Benchmark selected plan geometry or assignments differ", rejected.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "after-outside-node", "after-outside-order", "after-boundary-order",
            "after-context-node", "missing-companion", "quality"})
    void actualMainPartialPlanRetainsCompleteNativeQualityAndOutsideContextProof(String mutation,
            @TempDir Path directory) throws Exception {
        Pair pair = archives(directory, mutation, partial.computed(), partial.plan());
        String xml = osm("none", partial.original()); Files.writeString(pair.osm(), xml);
        String pin = sha256(pair.osm());
        byte[] baseline = Files.readAllBytes(pair.baseline()), candidate = Files.readAllBytes(pair.candidate());
        if (mutation.equals("none")) {
            BenchmarkDecisionComparatorMain.main(new String[] {pair.baseline().toString(), pair.candidate().toString(),
                    pair.osm().toString(), pin});
            assertTrue(Format15ArchiveReader.read(pair.baseline()).artifact("frozen-edit-plan.bin").isEmpty());
        } else {
            Throwable rejected = assertThrows(Exception.class, () -> BenchmarkDecisionComparatorMain.main(
                    new String[] {pair.baseline().toString(), pair.candidate().toString(), pair.osm().toString(), pin}));
            while (rejected.getCause() != null) rejected = rejected.getCause();
            assertEquals(switch (mutation) {
                case "after-outside-node", "after-outside-order", "after-boundary-order"
                        -> "External port is inconsistent with its captured adjacency";
                case "after-context-node" -> "Moved existing node lacks explicit movement authority";
                case "missing-companion" -> "Complete native output companion is required";
                case "quality" -> "Complete native final-output components differ";
                default -> throw new AssertionError(mutation);
            }, rejected.getMessage());
        }
        assertTrue(Arrays.equals(baseline, Files.readAllBytes(pair.baseline())));
        assertTrue(Arrays.equals(candidate, Files.readAllBytes(pair.candidate())));
        assertEquals(xml, Files.readString(pair.osm()));
    }

    @Test
    void actualMainAdmitsNativeCandidateWithoutInventingHistoricalPlan(@TempDir Path directory) throws Exception {
        Pair pair = archives(directory, "none");
        String xml = osm("none");
        Files.writeString(pair.osm(), xml);
        BenchmarkDecisionComparatorMain.main(new String[] {pair.baseline().toString(), pair.candidate().toString(),
                pair.osm().toString(), sha256(pair.osm())});
        var baseline = Format15ArchiveReader.read(pair.baseline());
        assertTrue(baseline.artifact("frozen-edit-plan.bin").isEmpty());
        assertFalse(baseline.capability().supports(ReplayLevel.FULL_EDIT_PLAN));
        assertEquals(xml, Files.readString(pair.osm()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-sha", "absent-osm", "context-node", "context-way", "context-relation", "core", "config",
            "missing-companion", "forged-plan", "missing-plan", "after-context-node"})
    void actualMainRefusesBrokenPinsContextAndNativePlanWithoutChangingInputs(String failure,
            @TempDir Path directory) throws Exception {
        Pair pair = archives(directory, failure);
        String xml = osm(failure);
        if (!failure.equals("absent-osm")) Files.writeString(pair.osm(), xml);
        String hash = failure.equals("absent-osm") ? "0".repeat(64) : sha256(pair.osm());
        if (failure.equals("wrong-sha")) hash = "0".repeat(64);
        byte[] baseline = Files.readAllBytes(pair.baseline()), candidate = Files.readAllBytes(pair.candidate());
        String pinned = hash;
        Throwable rejected = assertThrows(Exception.class, () -> BenchmarkDecisionComparatorMain.main(new String[] {
                pair.baseline().toString(), pair.candidate().toString(), pair.osm().toString(), pinned}));
        while (rejected.getCause() != null) rejected = rejected.getCause();
        String expected = switch (failure) {
            case "wrong-sha" -> "Original OSM SHA256 differs before parsing";
            case "absent-osm" -> "Pinned original OSM is required";
            case "context-node", "context-way", "core" -> "Decision-bearing network closure differs";
            case "context-relation" -> "Semantic witness differs from original live OSM input";
            case "config" -> "Frozen request parameters differ";
            case "missing-companion" -> "Complete native output companion is required";
            case "forged-plan" -> "Native route does not prove the complete inherited review plan";
            case "missing-plan" -> "Candidate production edit plan is missing";
            case "after-context-node" -> "Moved existing node lacks explicit movement authority";
            default -> throw new AssertionError("Unknown negative oracle");
        };
        assertEquals(expected, rejected.getMessage());
        assertTrue(Arrays.equals(baseline, Files.readAllBytes(pair.baseline())));
        assertTrue(Arrays.equals(candidate, Files.readAllBytes(pair.candidate())));
        if (!failure.equals("absent-osm")) assertEquals(xml, Files.readString(pair.osm()));
    }

    private static Pair archives(Path directory, String mutation) throws Exception {
        return archives(directory, mutation, computed, plan);
    }

    private static Pair archives(Path directory, String mutation, LiveBPreviewService.Computed computed,
            AlignmentEditPlan plan) throws Exception {
        FrozenReplayInput candidate = new FrozenReplayInput(computed.request(), computed.evidence(),
                computed.captured().network(), computed.options());
        NetworkSnapshot n = candidate.network();
        NetworkSnapshot old = new NetworkSnapshot(n.snapshotId(), n.role(), n.datasetIdentity(),
                n.sourceGeneration(), n.closure(), n.primitives(), n.incomingReferrerWatches());
        FrozenReplayInput historical = new FrozenReplayInput(candidate.request(), candidate.evidence(), old, candidate.options());
        Format15Bundle baseline = Format15ProductionBundleFactory.createLive("synthetic-rc6", historical,
                computed.pipeline(), "review-required", computed.captured().managedRaster().sourceIdentity(), 0, null, false, false);
        Format15Bundle produced = Format15ProductionBundleFactory.createLive("synthetic-candidate", candidate,
                computed.pipeline(), "review-required", computed.captured().managedRaster().sourceIdentity(), 0, plan, false, false);
        if (mutation.equals("quality")) {
            var route = computed.pipeline().routes().get(0);
            var changedQuality = recordWith(route.quality(), "bendPreservingRoughness", route.quality().bendPreservingRoughness() + 1);
            var changed = recordWith(route, "quality", changedQuality);
            produced = Format15ProductionBundleFactory.createLive("synthetic-candidate", candidate,
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Result(
                            computed.pipeline().inference(), replaceFirst(computed.pipeline().routes(), changed)), "review-required",
                    computed.captured().managedRaster().sourceIdentity(), 0, plan, false, false);
        }
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(produced.artifacts());
        if (mutation.equals("missing-companion")) artifacts.remove("private/final-output-components.bin");
        if (mutation.equals("missing-plan")) artifacts.remove("frozen-edit-plan.bin");
        if (mutation.equals("forged-plan")) {
            AlignmentEditPlan forged = new AlignmentEditPlan(plan.selectedWayKey(), plan.selectedRange(), plan.before(),
                    plan.after(), plan.metricFrame(), plan.permissions(), plan.settingsHash(), plan.evidenceHash(),
                    plan.parameterHash(), plan.routeIdentity(), plan.finalPreviewWays(), new ValidationReport(
                            ValidationReport.Disposition.REVIEW_REQUIRED,
                            List.of("inherited-nontransport-contact-review-required", "unrelated-review")));
            artifacts.put("frozen-edit-plan.bin", Format15Artifact.binary("frozen-edit-plan.bin", FrozenReplayCodec.encodeEditPlan(forged)));
        }
        if (mutation.equals("after-context-node") || mutation.equals("after-outside-node")) {
            DetachedNode context = (DetachedNode) plan.after().primitives().entrySet().stream()
                    .filter(e -> e.getKey().identityKind()
                            == org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.IdentityKind.OSM_UNIQUE
                            && e.getKey().id() == (mutation.equals("after-outside-node") ? 3 : 33))
                    .findFirst().orElseThrow().getValue();
            byte[] bytes = FrozenReplayCodec.encodeEditPlan(plan);
            ByteArrayOutputStream marker = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(marker)) {
                writeKey(out, context.key()); out.writeByte(0);
                out.writeDouble(context.coordinate().latitudeDegrees()); out.writeDouble(context.coordinate().longitudeDegrees());
            }
            byte[] point = marker.toByteArray();
            List<Integer> positions = new ArrayList<>();
            for (int index = 0; index <= bytes.length - point.length; index++) {
                boolean match = true;
                for (int offset = 0; offset < point.length; offset++) match &= bytes[index + offset] == point[offset];
                if (match) positions.add(index);
            }
            assertEquals(2, positions.size(), "typed original node primitive occurs once in before and once in after");
            java.nio.ByteBuffer.wrap(bytes).putDouble(positions.get(1) + point.length - 16,
                    context.coordinate().latitudeDegrees() + Math.toDegrees(1 / 6378137.0));
            artifacts.put("frozen-edit-plan.bin", Format15Artifact.binary("frozen-edit-plan.bin", bytes));
            assertTrue(Arrays.equals(produced.artifact("final-route.json").bytes(), artifacts.get("final-route.json").bytes()),
                    "context tamper must preserve selected published geometry and complete native companion");
        }
        if (mutation.equals("after-outside-order") || mutation.equals("after-boundary-order")) {
            var selected = (org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay)
                    plan.after().primitives().get(plan.selectedWayKey());
            ByteArrayOutputStream marker = new ByteArrayOutputStream(); int header;
            try (DataOutputStream out = new DataOutputStream(marker)) {
                writeKey(out, selected.key()); out.writeByte(1); out.writeInt(selected.nodeKeys().size());
                header = marker.size();
                for (var key : selected.nodeKeys()) writeKey(out, key);
            }
            byte[] bytes = FrozenReplayCodec.encodeEditPlan(plan), sequence = marker.toByteArray();
            int position = -1, matches = 0;
            for (int index = 0; index <= bytes.length - sequence.length; index++) {
                boolean match = true;
                for (int offset = 0; offset < sequence.length; offset++) match &= bytes[index + offset] == sequence[offset];
                if (match) { position = index; matches++; }
            }
            assertEquals(1, matches, "the exact typed full after-way occurrence list has one native record");
            int first = mutation.equals("after-outside-order") ? 0 : plan.selectedRange().firstIndex();
            int last = mutation.equals("after-outside-order") ? selected.nodeKeys().size() - 1
                    : selected.nodeKeys().size() - 4;
            ByteArrayOutputStream keyBytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(keyBytes)) { writeKey(out, selected.nodeKeys().get(first)); }
            int width = keyBytes.size();
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            buffer.putLong(position + header + first * width + width - 8, selected.nodeKeys().get(last).id());
            buffer.putLong(position + header + last * width + width - 8, selected.nodeKeys().get(first).id());
            artifacts.put("frozen-edit-plan.bin", Format15Artifact.binary("frozen-edit-plan.bin", bytes));
        }
        if (mutation.equals("core") || mutation.equals("config")) {
            Map<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive> values = new LinkedHashMap<>(n.primitives());
            if (mutation.equals("core")) {
                var entry = values.entrySet().stream().filter(e -> e.getValue() instanceof DetachedNode).findFirst().orElseThrow();
                DetachedNode node = (DetachedNode) entry.getValue();
                values.put(entry.getKey(), new DetachedNode(node.key(), node.coordinate(), Map.of("changed", "yes"), node.deleted(), node.modified()));
            }
            NetworkSnapshot changed = new NetworkSnapshot(n.snapshotId(), n.role(), n.datasetIdentity(), n.sourceGeneration(),
                    n.closure(), values, n.incomingReferrerWatches());
            TraceRequest r = candidate.request();
            TraceRequest request = new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(), r.geometryMode(),
                    r.permissions(), r.budgets(), r.evidenceSnapshotId(), r.evidenceContentHash(), r.networkSnapshotId(),
                    changed.canonicalHash(), mutation.equals("config") ? "different-effective-settings" : r.settingsHash(),
                    r.parameterHash(), r.samplerId(), r.configuredSampleStepMeters(), r.profileChainage(), r.evidenceResolution(), r.corridorInput());
            FrozenReplayInput altered = new FrozenReplayInput(request, candidate.evidence(), changed, candidate.options());
            artifacts.put("frozen-input.bin", Format15Artifact.binary("frozen-input.bin", FrozenReplayCodec.encode(altered)));
        }
        Path first = directory.resolve("baseline.zip"), second = directory.resolve("candidate.zip");
        Format15BundleWriter.write(baseline, first);
        Format15BundleWriter.write(new Format15Bundle(produced.buildIdentity(), produced.sourceIdentityHash(),
                produced.parameterHash(), artifacts), second);
        return new Pair(first, second, directory.resolve("original.osm"));
    }

    private static String osm(String mutation) {
        return osm(mutation, original);
    }

    private static String osm(String mutation, DataSet original) {
        StringBuilder xml = new StringBuilder("<osm version='0.6'>");
        for (Node node : original.getNodes().stream().sorted(java.util.Comparator.comparingLong(Node::getUniqueId)).toList()) {
            xml.append("<node id='").append(node.getUniqueId()).append("' version='1' lat='")
                    .append(mutation.equals("context-node") && node.getUniqueId() == 33 ? node.lat() - 0.00001 : node.lat())
                    .append("' lon='").append(node.lon()).append("'/>");
        }
        for (Way way : original.getWays().stream().sorted(java.util.Comparator.comparingLong(Way::getUniqueId)).toList()) {
            xml.append("<way id='").append(way.getUniqueId()).append("' version='1'>");
            for (Node node : way.getNodes()) xml.append("<nd ref='").append(node.getUniqueId()).append("'/>");
            way.getKeys().forEach((key, value) -> xml.append("<tag k='").append(key).append("' v='")
                    .append(mutation.equals("context-way") && way.getUniqueId() == 35 ? "unknown" : value).append("'/>"));
            xml.append("</way>");
        }
        for (Relation relation : original.getRelations()) {
            xml.append("<relation id='").append(relation.getUniqueId()).append("' version='1'>");
            for (RelationMember member : relation.getMembers()) xml.append("<member type='way' ref='")
                    .append(member.getMember().getUniqueId()).append("' role='").append(member.getRole()).append("'/>");
            relation.getKeys().forEach((key, value) -> xml.append("<tag k='").append(key).append("' v='")
                    .append(mutation.equals("context-relation") && key.equals("landuse") ? "residential" : value).append("'/>"));
            xml.append("</relation>");
        }
        return xml.append("</osm>").toString();
    }

    private static Node node(long id, double east, double north) {
        Node node = new Node(new LatLon(Math.toDegrees(north / 6378137), Math.toDegrees(east / 6378137)));
        node.setOsmId(id, 1); node.setModified(false); return node;
    }

    private static Way way(long id, String tag, String value, Node... nodes) {
        Way way = new Way(); way.setNodes(List.of(nodes)); way.put(tag, value);
        way.setOsmId(id, 1); way.setModified(false); return way;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static <T> List<T> replaceFirst(List<T> original, T first) {
        List<T> changed = new ArrayList<>(original); changed.set(0, first); return changed;
    }

    private static void writeKey(DataOutputStream out,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey key) throws Exception {
        for (String text : List.of(key.type().name(), key.identityKind().name())) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
        }
        out.writeLong(key.id());
    }

    private record Pair(Path baseline, Path candidate, Path osm) { }
    private record NativeFixture(DataSet original, LiveBPreviewService.Computed computed, AlignmentEditPlan plan) { }
}
