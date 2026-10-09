package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
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

    @BeforeAll
    static void produceActualManagedFixedPlan() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        original = new DataSet();
        Node west = node(1, -8, 0), east = node(2, 8, 0);
        Node south = node(33, 0.17345, -10), north = node(34, 0.17345, 10);
        Way selected = way(10, "highway", "path", west, east);
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
        original.setSelected(selected);
        var fixture = BenchmarkHostMain.fixtureSource(37);
        PluginPreferences.save(fixture.config()); fixture.saveCacheGenerationPreference();
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
        computed = new LiveBPreviewService().compute(captured, CancellationProbe.NONE);
        assertFalse(computed.pipeline().routes().isEmpty(), computed.pipeline().inference().status()
                + ": " + computed.pipeline().inference().explanation());
        plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertTrue(plan.validation().reviewRequired());
        assertTrue(plan.validation().findingCodes().contains("inherited-nontransport-contact-review-required"));
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
        if (mutation.equals("after-context-node")) {
            DetachedNode context = (DetachedNode) plan.after().primitives().entrySet().stream()
                    .filter(e -> e.getKey().id() == 33).findFirst().orElseThrow().getValue();
            byte[] bytes = FrozenReplayCodec.encodeEditPlan(plan);
            byte[] point = java.nio.ByteBuffer.allocate(16).putDouble(context.coordinate().latitudeDegrees())
                    .putDouble(context.coordinate().longitudeDegrees()).array();
            int last = -1, matches = 0;
            for (int index = 0; index <= bytes.length - point.length; index++) {
                boolean match = true;
                for (int offset = 0; offset < point.length; offset++) match &= bytes[index + offset] == point[offset];
                if (match) { last = index; matches++; }
            }
            assertEquals(2, matches, "one immutable context coordinate in before and one in after");
            java.nio.ByteBuffer.wrap(bytes).putDouble(last, context.coordinate().latitudeDegrees() + Math.toDegrees(1 / 6378137.0));
            artifacts.put("frozen-edit-plan.bin", Format15Artifact.binary("frozen-edit-plan.bin", bytes));
            assertTrue(Arrays.equals(produced.artifact("final-route.json").bytes(), artifacts.get("final-route.json").bytes()),
                    "context tamper must preserve selected published geometry and complete native companion");
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

    private record Pair(Path baseline, Path candidate, Path osm) { }
}
