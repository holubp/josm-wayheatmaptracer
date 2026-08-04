package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/** Replays exported scalar profiles through the current tracker when the private calibration bundle is present. */
class SparseCorridorDebugReplayTest {
    private static final Path ARCHIVE = Path.of("last-slide-debug-1785794101840.zip");
    private static final String DETECTOR = "hot-corridor";

    @Test
    void currentTrackerBuildsAStableCompleteCandidateFromTheKnownSparseCorridor() throws Exception {
        Assumptions.assumeTrue(Files.exists(ARCHIVE), "Private sparse-corridor calibration bundle is optional");

        try (ZipFile zip = new ZipFile(ARCHIVE.toFile())) {
            String diagnostics = new String(zip.getInputStream(zip.getEntry("diagnostics.json")).readAllBytes(),
                StandardCharsets.UTF_8);
            double sourcePixelRasterPx = jsonNumber(diagnostics, "trackerNormalizationRasterPx");
            double groundMetersPerRasterPixel = jsonNumber(diagnostics, "groundMetersPerRasterPixel");
            Map<Integer, Double> distanceMeters = readDistances(zip);
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles = readProfiles(
                zip, distanceMeters, groundMetersPerRasterPixel);

            JunctionContext fixedEndpoints = new JunctionContext(List.of(
                new EndpointConstraint(0, 1L, true, false, 0.0, 0.0, 6),
                new EndpointConstraint(profiles.size() - 1, 2L, true, false, 0.0, 0.0, 6)
            ));
            CorridorAwareTracker.TrackingResult result = new CorridorAwareTracker().trackDetailed(
                profiles, sourcePixelRasterPx, fixedEndpoints);

            assertFalse(result.candidates().isEmpty(), "Known sparse corridor should produce a candidate");
            CenterlineCandidate candidate = result.sparseBundles().stream()
                .max(java.util.Comparator.comparingInt(SparseCorridorBundle::directUnionProfileCount))
                .flatMap(bundle -> result.candidates().stream()
                    .filter(value -> value.id().equals(bundle.id())).findFirst())
                .orElseGet(() -> result.candidates().get(0));
            assertTrue(candidate.evidence().supportRatio() >= 0.70,
                "Sparse candidate support=" + candidate.evidence().supportRatio());
            assertTrue(candidate.evidence().corridorCoverage().complete(),
                "Sparse parent coverage=" + candidate.evidence().corridorCoverage().reason());
            assertTrue(candidate.evidence().corridorQuality().nonSustainedHighFrequencyP95SourcePx() <= 0.40,
                "Sparse parent ripple="
                    + candidate.evidence().corridorQuality().nonSustainedHighFrequencyP95SourcePx()
                    + ", candidate=" + candidate.id()
                    + ", bundles=" + result.sparseBundles().stream()
                        .map(bundle -> bundle.id() + ':' + bundle.classification() + ':'
                            + bundle.directUnionProfileCount() + ':' + bundle.childTrackIds()).toList()
                    + ", tubeResidualP95=" + candidate.evidence().corridorQuality().tubeResidualP95SourcePx()
                    + ", localization=" + candidate.evidence().localizationConfidence()
                    + ", prominence=" + result.tracks().stream()
                        .filter(track -> track.id().equals(candidate.id())).findFirst().orElseThrow()
                        .points().values().stream().mapToDouble(point -> point.band().peakIntensity()
                            - point.band().noiseFloor()).average().orElse(0.0)
                    + ", motion=" + result.tubes().get(candidate.id()).slices().stream()
                        .mapToDouble(CorridorTubeSlice::motionSupport).average().orElse(0.0));
        }
    }

    private Map<Integer, Double> readDistances(ZipFile zip) throws Exception {
        Map<Integer, Double> result = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            zip.getInputStream(zip.getEntry("corridor-tube.csv")), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            Map<String, Integer> columns = columns(header);
            for (String line; (line = reader.readLine()) != null;) {
                String[] values = line.split(",", -1);
                if (DETECTOR.equals(unquote(values[columns.get("detector")]))) {
                    int profile = Integer.parseInt(values[columns.get("profile_index")]);
                    result.putIfAbsent(profile, Double.parseDouble(values[columns.get("distance_m")]));
                }
            }
        }
        return result;
    }

    private List<RenderedHeatmapSampler.CrossSectionProfile> readProfiles(
        ZipFile zip,
        Map<Integer, Double> distanceMeters,
        double groundMetersPerRasterPixel
    ) throws Exception {
        Map<Integer, List<IntensitySample>> samples = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            zip.getInputStream(zip.getEntry("profile-intensity.csv")), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            Map<String, Integer> columns = columns(header);
            for (String line; (line = reader.readLine()) != null;) {
                String[] values = line.split(",", -1);
                if (!DETECTOR.equals(unquote(values[columns.get("detector")]))) {
                    continue;
                }
                int profile = Integer.parseInt(values[columns.get("profile_index")]);
                samples.computeIfAbsent(profile, ignored -> new ArrayList<>()).add(new IntensitySample(
                    Double.parseDouble(values[columns.get("offset_px")]),
                    Double.parseDouble(values[columns.get("native_intensity")]),
                    Double.parseDouble(values[columns.get("b3_intensity")]),
                    Double.parseDouble(values[columns.get("b5_intensity")]),
                    Boolean.parseBoolean(values[columns.get("inside_raster")])));
            }
        }
        List<RenderedHeatmapSampler.CrossSectionProfile> result = new ArrayList<>();
        for (Map.Entry<Integer, List<IntensitySample>> entry : samples.entrySet()) {
            double distance = distanceMeters.getOrDefault(entry.getKey(),
                entry.getKey() * groundMetersPerRasterPixel);
            double rasterX = distance / groundMetersPerRasterPixel;
            result.add(new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(distance, 0.0), rasterX, 0.0, distance),
                new Point2D.Double(0.0, 1.0), List.of(), true, entry.getValue()));
        }
        return result;
    }

    private Map<String, Integer> columns(String header) {
        String[] names = header.split(",", -1);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < names.length; index++) {
            result.put(names[index], index);
        }
        return result;
    }

    private double jsonNumber(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\":([0-9.]+)").matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing numeric diagnostics field " + name);
        }
        return Double.parseDouble(matcher.group(1));
    }

    private String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
            ? value.substring(1, value.length() - 1) : value;
    }
}
