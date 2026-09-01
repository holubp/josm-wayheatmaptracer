package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentDiagnostics;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class LastSlideDebugBundleTest {
    @BeforeAll
    static void setEnvironment() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void exportsAllCorridorDiagnosticsWithoutSensitiveAccessValues(@TempDir Path temporaryDirectory) throws Exception {
        Node first = node(new EastNorth(0, 0));
        Node last = node(new EastNorth(10, 0));
        Way way = new Way();
        way.setNodes(List.of(first, last));
        SelectionContext selection = new SelectionContext(way, 0, 1, List.of(first, last), Set.of(first, last));
        CenterlineCandidate candidate = new CenterlineCandidate("hot/strand-1", 1.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(10, 0)), List.of(0.0, 0.0))
            .withEastNorthPoints(List.of(new EastNorth(0, 0), new EastNorth(10, 0)))
            .withFinalPreviewGeometry(List.of(new EastNorth(0, 0), new EastNorth(10, 0)),
                Map.of(first.getUniqueId(), new EastNorth(0, 0), last.getUniqueId(), new EastNorth(10, 0)));
        AlignmentDiagnostics diagnostics = new AlignmentDiagnostics(
            "Strava", 1, 0, 1, 2, 3,
            "{\"trackerMode\":\"CORRIDOR_AWARE\"}", "{}", "{}", "[\"hot\"]", "[]", "[]",
            "candidate\n", "peaks\n", "palette\n", "intensity\n", "bands\n", "tracks\n", "costs\n",
            "{\"enabled\":true,\"ways\":[],\"assignments\":[]}"
        );
        AlignmentResult result = new AlignmentResult(selection,
            new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), List.of(candidate),
            List.of(new EastNorth(0, 0), new EastNorth(10, 0)),
            List.of(new EastNorth(0, 0), new EastNorth(10, 0)), List.of(), diagnostics, null);
        Path bundlePath = temporaryDirectory.resolve("last-slide.zip");

        LastSlideDebugBundle.fromResult(result, candidate, "preview-open", "redacted log")
            .writeTo(bundlePath.toFile());

        try (ZipFile zip = new ZipFile(bundlePath.toFile())) {
            assertNotNull(zip.getEntry("profile-intensity.csv"));
            assertNotNull(zip.getEntry("corridor-bands.csv"));
            assertNotNull(zip.getEntry("corridor-tracks.csv"));
            assertNotNull(zip.getEntry("corridor-bundles.csv"));
            assertNotNull(zip.getEntry("bundle-points.csv"));
            assertNotNull(zip.getEntry("optimizer-costs.csv"));
            assertNotNull(zip.getEntry("corridor-tube.csv"));
            assertNotNull(zip.getEntry("association-decisions.csv"));
            assertNotNull(zip.getEntry("endpoint-approaches.csv"));
            assertNotNull(zip.getEntry("detector-performance.csv"));
            assertNotNull(zip.getEntry("parallel-context.json"));
            assertNotNull(zip.getEntry("candidate-previews.osm"));
            assertNotNull(zip.getEntry("applied-segment.osm"));
            assertNotNull(zip.getEntry("junction-safety.csv"));
            assertNotNull(zip.getEntry("proposed-node-positions.csv"));
            assertNotNull(zip.getEntry("junction-context.osm"));
            assertEquals("intensity\n", text(zip, "profile-intensity.csv"));
            assertTrue(text(zip, "diagnostics.json").contains("CORRIDOR_AWARE"));
            assertTrue(text(zip, "diagnostics.json").contains("pluginVersion"));
            assertTrue(text(zip, "diagnostics.json").contains("buildIdentity"));
            assertTrue(text(zip, "manifest.json").contains("containsSecrets\":false"));
            assertTrue(text(zip, "manifest.json").contains("formatVersion\":12"));
            assertNotNull(zip.getEntry("tile-acquisition.json"));
            assertTrue(text(zip, "proposed-node-positions.csv").contains("hot/strand-1"));
            assertTrue(text(zip, "diagnostics.json").contains("dedicated-csv-artifacts"));
            assertTrue(text(zip, "diagnostics.json").contains("profile-intensity.csv"));
            assertTrue(text(zip, "diagnostics.json").contains("corridor-bundles.csv"));
            assertTrue(text(zip, "verbose-log.txt").contains("Plugin-Build:"));
        }
    }

    @Test
    void exportsChecksummedCleanupArtifactsWithEscapingAndRedaction(@TempDir Path temporaryDirectory)
        throws Exception {
        Node first = node(new EastNorth(0, 0));
        Node last = node(new EastNorth(10, 0));
        Way way = new Way();
        way.setNodes(List.of(first, last));
        SelectionContext selection = new SelectionContext(way, 0, 1, List.of(first, last), Set.of(first, last));
        CenterlineCandidate raw = candidate("hot/strand#raw", first, last).withGeometryCleanup(
            new CandidateGeometryCleanup("", CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE,
                "cleaned-sibling", List.of("raw-kept"), 12, 12, 12, 2, 1, 8, 4, 0,
                0.88, 0.88, 0.0, OptionalDouble.empty(), OptionalDouble.empty()));
        CenterlineCandidate cleaned = candidate("hot/strand#cleaned,\"quoted\"", first, last)
            .withCleanupEvidence(CandidateCleanupEvidence.skipped(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingFrame.empty(),
                List.of(new CandidateCleanupProfile(0, -0.5, 0.5, -1.0, 1.0, 0.0, 0.5,
                    CleanupEvidenceProvenance.DIRECT, 0.6, 0.2, false, 0.8, 0.1, 0.15)),
                CleanupEvidenceStatus.INCOMPLETE_LONGITUDINAL_EVIDENCE))
            .withGeometryCleanup(
            new CandidateGeometryCleanup(raw.id(), CandidateGeometryCleanup.Outcome.CLEANED,
                "accepted", List.of("fit-retained", "comma,quoted \"reason\""), 12, 12, 5, 2, 1, 8, 4, 0,
                0.88, 0.91, 1.25, OptionalDouble.of(0.42), OptionalDouble.of(0.93)));
        AlignmentDiagnostics diagnostics = new AlignmentDiagnostics(
            "Strava", 2, 0, 1, 2, 3,
            "{\"CloudFront-Signature\":\"test-secret\"}", "{}", "{}", "[\"hot\"]", "[]", "[]",
            "candidate\n", "peaks\n", "palette\n", "intensity\n", "bands\n", "tracks\n", "costs\n",
            "{\"enabled\":false}");
        AlignmentResult result = new AlignmentResult(selection,
            new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), List.of(raw, cleaned),
            List.of(new EastNorth(0, 0), new EastNorth(10, 0)),
            List.of(new EastNorth(0, 0), new EastNorth(10, 0)), List.of(), diagnostics, null, List.of(),
            List.of(raw, cleaned));
        Path bundlePath = temporaryDirectory.resolve("cleanup.zip");

        LastSlideDebugBundle.fromResult(result, raw, cleaned, "preview-open",
            "CloudFront-Signature=log-secret; _strava_idcf=another-secret\nCookie: private-cookie", Map.of())
            .writeTo(bundlePath.toFile());

        try (ZipFile zip = new ZipFile(bundlePath.toFile())) {
            String cleanup = text(zip, "geometry-cleanup.csv");
            String anchors = text(zip, "geometry-cleanup-anchors.csv");
            String localShape = text(zip, "geometry-cleanup-local-shape.csv");
            String diagnosticsJson = text(zip, "diagnostics.json");
            String statusJson = text(zip, "status.json");
            String allText = cleanup + anchors + localShape + diagnosticsJson + text(zip, "verbose-log.txt");
            assertNotNull(zip.getEntry("geometry-cleanup.csv"));
            assertNotNull(zip.getEntry("geometry-cleanup-anchors.csv"));
            assertNotNull(zip.getEntry("geometry-cleanup-local-shape.csv"));
            assertTrue(cleanup.contains("CLEANED_ALTERNATIVE_AVAILABLE"));
            assertTrue(cleanup.contains("CLEANED"));
            assertTrue(cleanup.contains("\"hot/strand#cleaned,\"\"quoted\"\"\""));
            assertTrue(cleanup.contains("JOSM-projection-units"));
            assertTrue(anchors.contains("candidate-owned-proposed-node"));
            assertTrue(anchors.contains(",\"available\","));
            assertTrue(anchors.contains(Long.toString(first.getUniqueId())));
            assertTrue(anchors.contains(",true,"));
            assertTrue(diagnosticsJson.contains("\"geometryCleanup\""));
            assertTrue(diagnosticsJson.contains("\"sha256\":\"" + sha256(cleanup) + "\""));
            assertTrue(diagnosticsJson.contains("\"sha256\":\"" + sha256(anchors) + "\""));
            assertTrue(diagnosticsJson.contains("\"sha256\":\"" + sha256(localShape) + "\""));
            assertTrue(localShape.startsWith("candidate_id,parent_candidate_id,cleanup_evidence_status,profile_index,"));
            assertTrue(localShape.contains("DIRECT"));
            assertTrue(localShape.contains(",0.8,0.1,0.15"));
            assertFalse(localShape.contains("east"));
            assertTrue(statusJson.contains("\"selectedCandidate\":\"hot/strand#raw\""));
            assertTrue(statusJson.contains("\"highestRankedApplicableBase\":\"hot/strand#raw\""));
            assertTrue(statusJson.contains("\"initialPreviewCandidate\":\"hot/strand#cleaned,\\\"quoted\\\"\""));
            assertTrue(diagnosticsJson.contains("\"highestRankedApplicableBase\":\"hot/strand#raw\""));
            assertTrue(diagnosticsJson.contains("\"initialPreviewCandidate\":\"hot/strand#cleaned,\\\"quoted\\\"\""));
            assertFalse(allText.contains("test-secret"));
            assertFalse(allText.contains("log-secret"));
            assertFalse(allText.contains("another-secret"));
            assertFalse(allText.contains("private-cookie"));
        }
    }

    @Test
    void appliedBundleKeepsImmutableSourceAndCapturesActualCoordinates(@TempDir Path temporaryDirectory)
        throws Exception {
        Node first = node(new EastNorth(0, 0));
        Node last = node(new EastNorth(10, 0));
        Way way = new Way();
        way.setNodes(List.of(first, last));
        SelectionContext selection = new SelectionContext(way, 0, 1, List.of(first, last), Set.of(first, last));
        AlignmentResult result = new AlignmentResult(selection, null, List.of(),
            List.of(new EastNorth(0, 0), new EastNorth(10, 0)),
            List.of(new EastNorth(0, 0), new EastNorth(10, 0)), List.of(),
            new AlignmentDiagnostics("Strava", 0, 0, 0, 0, 0, "{}", "{}", "{}", "[]", "[]", "[]"),
            null);
        first.setCoor(ProjectionRegistry.getProjection().eastNorth2latlon(new EastNorth(100, 50)));
        Path bundlePath = temporaryDirectory.resolve("applied.zip");

        LastSlideDebugBundle.fromResult(result, null, "applied", "log").writeTo(bundlePath.toFile());

        try (ZipFile zip = new ZipFile(bundlePath.toFile())) {
            String original = text(zip, "original-segment.osm");
            String applied = text(zip, "applied-segment.osm");
            assertTrue(original.contains("lon=\"0.0\""));
            assertTrue(applied.contains("node"));
            assertTrue(!original.equals(applied));
        }
    }

    private static Node node(EastNorth point) {
        return new Node(ProjectionRegistry.getProjection().eastNorth2latlon(point));
    }

    private static CenterlineCandidate candidate(String id, Node first, Node last) {
        return new CenterlineCandidate(id, 1.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(10, 0)), List.of(0.0, 0.0))
            .withEastNorthPoints(List.of(new EastNorth(0, 0), new EastNorth(10, 0)))
            .withFinalPreviewGeometry(List.of(new EastNorth(0, 0), new EastNorth(10, 0)),
                Map.of(first.getUniqueId(), new EastNorth(0, 0), last.getUniqueId(), new EastNorth(10, 0)));
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(ZipFile zip, String name) throws Exception {
        return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
    }
}
