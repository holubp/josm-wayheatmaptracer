package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.concurrent.TimeUnit;

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage;
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
            .withEvidence(new CandidateEvidence("hot", 2, 2, 0, 0, 2.0, 1.0, 0.2, 1.0, 0.4, 0.0, List.of())
                .withCorridorCoverage(new CorridorCoverage(true, false, 1, 2, 0.5, 0, 1, 0.0, 0.0,
                    0, 0.0, 0, true, "unresolved-search-edge-censoring")))
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
            assertTrue(text(zip, "manifest.json").contains("formatVersion\":14"));
            assertNotNull(zip.getEntry("tile-acquisition.json"));
            assertTrue(text(zip, "proposed-node-positions.csv").contains("hot/strand-1"));
            assertTrue(text(zip, "diagnostics.json").contains("dedicated-csv-artifacts"));
            assertTrue(text(zip, "diagnostics.json").contains("profile-intensity.csv"));
            assertTrue(text(zip, "diagnostics.json").contains("corridor-bundles.csv"));
            assertTrue(text(zip, "verbose-log.txt").contains("Plugin-Build:"));
            assertTrue(text(zip, "status.json").contains("selectedDisposition\":\"REVIEW_REQUIRED"));
            assertTrue(text(zip, "status.json").contains("reviewConfirmed\":false"));
        }

        Path reviewedPath = temporaryDirectory.resolve("last-slide-reviewed.zip");
        LastSlideDebugBundle.fromResult(
            result, candidate, candidate, "applied-after-review", "redacted log", Map.of())
            .writeTo(reviewedPath.toFile());
        try (ZipFile zip = new ZipFile(reviewedPath.toFile())) {
            String status = text(zip, "status.json");
            assertTrue(status.contains("selectedDisposition\":\"REVIEW_REQUIRED"));
            assertTrue(status.contains("reviewConfirmed\":true"));
            assertTrue(status.matches(".*\"reviewedPreviewGeometryHash\":\"[0-9a-f]{64}\".*"));
            assertTrue(status.matches(".*\"appliedGeometryHash\":\"[0-9a-f]{64}\".*"));
            assertTrue(text(zip, "applied-segment.osm").contains("<way"));
        }

        Path failedReviewPath = temporaryDirectory.resolve("last-slide-review-failed.zip");
        LastSlideDebugBundle.fromResult(
            result, candidate, candidate, "review-confirmation-failed", "redacted log", Map.of())
            .writeTo(failedReviewPath.toFile());
        try (ZipFile zip = new ZipFile(failedReviewPath.toFile())) {
            String status = text(zip, "status.json");
            assertTrue(status.contains("reviewConfirmationRequested\":true"));
            assertTrue(status.contains("reviewConfirmed\":false"));
            assertTrue(status.contains("reviewRevalidationResult\":\"failed"));
            assertEquals("", text(zip, "applied-segment.osm"));
        }

        Path failedApplyPath = temporaryDirectory.resolve("last-slide-review-apply-failed.zip");
        LastSlideDebugBundle.fromResult(
            result, candidate, candidate, "review-apply-failed", "redacted log", Map.of())
            .writeTo(failedApplyPath.toFile());
        try (ZipFile zip = new ZipFile(failedApplyPath.toFile())) {
            String status = text(zip, "status.json");
            assertTrue(status.contains("reviewConfirmationRequested\":true"));
            assertTrue(status.contains("reviewConfirmed\":true"));
            assertTrue(status.contains("reviewRevalidationResult\":\"failed"));
            assertTrue(status.matches(".*\"reviewedPreviewGeometryHash\":\"[0-9a-f]{64}\".*"));
            assertTrue(status.contains("appliedGeometryHash\":\"\""));
            assertEquals("", text(zip, "applied-segment.osm"));
        }
    }

    @Test
    void geometryQualityKeepsRawFinalAndAppliedStagesOnFrozenProjection(@TempDir Path temporaryDirectory)
        throws Exception {
        Node first = node(new EastNorth(0, 0));
        Node last = node(new EastNorth(100, 0));
        Way way = new Way();
        way.setNodes(List.of(first, last));
        SelectionContext selection = new SelectionContext(way, 0, 1, List.of(first, last), Set.of(first, last));
        List<EastNorth> raw = List.of(new EastNorth(0, 0), new EastNorth(100, 0));
        List<EastNorth> finalPath = List.of(new EastNorth(0, 0), new EastNorth(100, 0), new EastNorth(100, 100));
        CenterlineCandidate candidate = new CenterlineCandidate("hot/test", 1.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(100, 0)), List.of(0.0, 0.0))
            .withEastNorthPoints(raw).withFinalPreviewGeometry(finalPath, Map.of());
        AlignmentDiagnostics diagnostics = new AlignmentDiagnostics("Strava", 0, 0, 0, 0, 0,
            "{}", "{}", "{\"projectionCode\":\"EPSG:3857\"}", "[]", "[]", "[]");
        AlignmentResult result = new AlignmentResult(selection, null, List.of(candidate), raw, finalPath,
            List.of(), diagnostics, null);
        LastSlideDebugBundle bundle = LastSlideDebugBundle.fromResult(result, candidate, "applied", "log");
        Path path = temporaryDirectory.resolve("geometry-quality.zip");
        var priorProjection = ProjectionRegistry.getProjection();
        try {
            ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:4326"));
            bundle.writeTo(path.toFile());
            try (ZipFile zip = new ZipFile(path.toFile())) {
                String csv = text(zip, "geometry-quality.csv");
                assertTrue(csv.contains("\"hot/test\",raw,available,"));
                assertTrue(csv.contains("\"hot/test\",final-preview,available,"));
                assertTrue(csv.contains("\"hot/test\",applied,available,"));
                String artifactSummary = text(zip, "diagnostics.json");
                assertTrue(artifactSummary.contains("geometryQuality"));
                assertTrue(artifactSummary.contains("\"sha256\":\"" + sha256(csv) + "\""));
                assertTrue(text(zip, "manifest.json").contains("geometryQualitySchemaVersion\":1"));
                assertTrue(text(zip, "manifest.json").contains("formatVersion\":14"));
            }
        } finally {
            ProjectionRegistry.setProjection(priorProjection);
        }
    }

    @Test
    void geometryQualityStagesMeasureRawFinalAndAdversarialAppliedGeometryIndependently(
        @TempDir Path temporaryDirectory) throws Exception {
        QualityFixture fixture = qualityFixtureWithAdversarialAppliedReversal();
        Path path = temporaryDirectory.resolve("geometry-quality-stages.zip");
        LastSlideDebugBundle.fromResult(fixture.result(), fixture.candidate(), "applied", "test")
            .writeTo(path.toFile());

        try (ZipFile zip = new ZipFile(path.toFile())) {
            String csv = text(zip, "geometry-quality.csv");
            double north100 = 99.33056;
            assertQualityRow(csv, "hot/quality", "raw", 2.0 * Math.hypot(100.0, north100),
                1, Math.toDegrees(Math.atan2(200.0 * north100, 10_000.0 - north100 * north100)));
            assertQualityRow(csv, "hot/quality", "final-preview", 199.33056, 1, 90.0);
            assertQualityRow(csv, "hot/quality", "applied", 200.0, 1, 180.0);
            String original = text(zip, "original-segment.osm");
            double originalLastLongitude = ProjectionRegistry.getProjection()
                .eastNorth2latlon(new EastNorth(200, 0)).lon();
            assertTrue(original.contains("lon=\"" + originalLastLongitude + "\""));
            assertTrue(text(zip, "applied-segment.osm").contains("node"));
        }
    }

    @Test
    void actualBundleGeometryQualityRoundTripsThroughAnalyzerWithVerifiedValues(
        @TempDir Path temporaryDirectory) throws Exception {
        QualityFixture fixture = qualityFixtureWithAdversarialAppliedReversal();
        Path bundlePath = temporaryDirectory.resolve("quality-roundtrip.zip");
        Path csvPath = temporaryDirectory.resolve("quality-roundtrip.csv");
        LastSlideDebugBundle.fromResult(fixture.result(), fixture.candidate(), "preview-open", "test")
            .writeTo(bundlePath.toFile());
        Path analyzer = Path.of("scripts", "analyze-debug-bundles.py").toAbsolutePath();
        Process process = new ProcessBuilder("python3", analyzer.toString(), bundlePath.toString(),
            "--raw-csv", csvPath.toString()).redirectErrorStream(true).start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("debug analyzer timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        String[] lines = Files.readString(csvPath, StandardCharsets.UTF_8).lines().toArray(String[]::new);
        List<String> columns = parseCsvLine(lines[0]);
        int candidateIdColumn = columns.indexOf("candidate_id");
        String row = java.util.Arrays.stream(lines).skip(1)
            .filter(line -> parseCsvLine(line).size() > candidateIdColumn
                && "hot/quality".equals(parseCsvLine(line).get(candidateIdColumn)))
            .findFirst().orElseThrow(() -> new AssertionError(String.join("\n", lines)));
        List<String> values = parseCsvLine(row);
        Map<String, String> analyzed = new java.util.HashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            analyzed.put(columns.get(index), values.get(index));
        }
        assertEquals("available", analyzed.get("geometry_quality_state"));
        assertEquals(199.33056, Double.parseDouble(analyzed.get("geometry_quality_length_meters")), 0.02);
        assertEquals(90.0, Double.parseDouble(analyzed.get("geometry_quality_max_turn_degrees")), 1e-7);
        try (ZipFile zip = new ZipFile(bundlePath.toFile())) {
            String artifact = text(zip, "geometry-quality.csv");
            assertTrue(text(zip, "diagnostics.json").contains("\"sha256\":\"" + sha256(artifact) + "\""));
        }
    }

    private static QualityFixture qualityFixtureWithAdversarialAppliedReversal() {
        Node first = node(new EastNorth(0, 0));
        Node middle = node(new EastNorth(100, 0));
        Node last = node(new EastNorth(200, 0));
        Way way = new Way();
        way.setNodes(List.of(first, middle, last));
        SelectionContext selection = new SelectionContext(way, 0, 2, List.of(first, middle, last),
            Set.of(first, middle, last));
        List<EastNorth> source = List.of(new EastNorth(0, 0), new EastNorth(100, 0), new EastNorth(200, 0));
        List<EastNorth> raw = List.of(new EastNorth(0, 0), new EastNorth(100, 100), new EastNorth(200, 0));
        List<EastNorth> finalPreview = List.of(new EastNorth(0, 0), new EastNorth(100, 0), new EastNorth(100, 100));
        CenterlineCandidate candidate = new CenterlineCandidate("hot/quality", 1.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(100, 100), new Point2D.Double(200, 0)),
            List.of(0.0, 0.0, 0.0)).withEastNorthPoints(raw).withFinalPreviewGeometry(finalPreview, Map.of());
        AlignmentDiagnostics diagnostics = new AlignmentDiagnostics("Strava", 0, 0, 0, 0, 0,
            "{}", "{}", "{\"projectionCode\":\"EPSG:3857\"}", "[]", "[]", "[]",
            "candidate_id,detector,calibrated_score,raw_score,support_ratio,mean_intensity,"
                + "mean_gradient_strength,signal_to_noise\n"
                + "hot/quality,hot,1,1,1,1,1,1\n",
            "", "", "", "", "", "", "{}");
        AlignmentResult result = new AlignmentResult(selection, null, List.of(candidate), source, finalPreview,
            List.of(), diagnostics, null);
        // Adversarial export fixture: mutable applied nodes deliberately form a reversal, unlike the stored preview.
        last.setCoor(ProjectionRegistry.getProjection().eastNorth2latlon(new EastNorth(0, 0)));
        return new QualityFixture(result, candidate);
    }

    private static void assertQualityRow(String csv, String candidateId, String stage,
        double expectedLength, int expectedTurns, double expectedMaximumTurn) {
        String row = csv.lines().filter(line -> line.startsWith("\"" + candidateId + "\"," + stage + ","))
            .findFirst().orElseThrow();
        String[] fields = row.split(",", -1);
        assertEquals("available", fields[2]);
        assertEquals(expectedLength, Double.parseDouble(fields[5]), 0.02);
        assertEquals(expectedTurns, Integer.parseInt(fields[6]));
        assertEquals(expectedMaximumTurn, Double.parseDouble(fields[7]), 0.02);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new java.util.ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private record QualityFixture(AlignmentResult result, CenterlineCandidate candidate) { }

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
            assertNotNull(zip.getEntry("geometry-quality.csv"));
            assertTrue(cleanup.contains("CLEANED_ALTERNATIVE_AVAILABLE"));
            assertTrue(cleanup.contains("CLEANED"));
            assertTrue(cleanup.contains("eligible_interval_count,changed_interval_count,frozen_interval_count"));
            assertTrue(cleanup.lines().allMatch(line -> line.split(",", -1).length >= 22));
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
