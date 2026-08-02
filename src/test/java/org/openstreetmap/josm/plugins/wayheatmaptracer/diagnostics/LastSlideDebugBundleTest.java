package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
            .withEastNorthPoints(List.of(new EastNorth(0, 0), new EastNorth(10, 0)));
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
            assertNotNull(zip.getEntry("optimizer-costs.csv"));
            assertNotNull(zip.getEntry("corridor-tube.csv"));
            assertNotNull(zip.getEntry("association-decisions.csv"));
            assertNotNull(zip.getEntry("endpoint-approaches.csv"));
            assertNotNull(zip.getEntry("parallel-context.json"));
            assertEquals("intensity\n", text(zip, "profile-intensity.csv"));
            assertTrue(text(zip, "diagnostics.json").contains("CORRIDOR_AWARE"));
            assertTrue(text(zip, "manifest.json").contains("containsSecrets\":false"));
        }
    }

    private static Node node(EastNorth point) {
        return new Node(ProjectionRegistry.getProjection().eastNorth2latlon(point));
    }

    private static String text(ZipFile zip, String name) throws Exception {
        return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
    }
}
