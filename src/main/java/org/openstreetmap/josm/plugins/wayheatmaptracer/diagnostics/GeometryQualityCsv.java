package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PhysicalPathMeasurements;

/** Stage-tagged, physically measured path geometry for diagnostic bundles. */
final class GeometryQualityCsv {
    private static final String HEADER = "candidate_id,stage,availability,reason,conversion_identity,length_meters,"
        + "defined_turn_count,max_turn_degrees,zero_length_segments\n";

    private GeometryQualityCsv() { }

    static String create(List<CenterlineCandidate> candidates, String selectedCandidateId,
        List<EastNorth> selectedPreview, String projectionCode, boolean applied, List<GeographicPoint> appliedPath) {
        StringBuilder csv = new StringBuilder(HEADER);
        Projection projection;
        try {
            projection = projectionCode == null || projectionCode.isBlank()
                ? null : Projections.getProjectionByCode(projectionCode);
        } catch (RuntimeException ex) {
            projection = null;
        }
        for (CenterlineCandidate candidate : candidates) {
            append(csv, candidate.id(), "raw", measure(project(candidate.eastNorthPoints(), projection), projectionCode,
                projection == null ? missingProjectionReason(projectionCode) : "invalid-or-uncertified-geographic-path"));
            List<EastNorth> finalGeometry = candidate.finalPreviewPoints();
            if (!finalGeometry.isEmpty()) {
                if (Objects.equals(candidate.id(), selectedCandidateId) && selectedPreview != null
                    && selectedPreview.equals(finalGeometry)) {
                    finalGeometry = selectedPreview;
                }
                append(csv, candidate.id(), "final-preview", measure(project(finalGeometry, projection), projectionCode,
                    projection == null ? missingProjectionReason(projectionCode)
                        : "invalid-or-uncertified-geographic-path"));
            } else {
                append(csv, candidate.id(), "final-preview", Measurement.unavailable("no-stored-final-preview"));
            }
            if (applied && Objects.equals(candidate.id(), selectedCandidateId)) {
                append(csv, candidate.id(), "applied", measure(appliedPath, "WGS84-LatLon",
                    appliedPath == null || appliedPath.isEmpty() ? "applied-geometry-unavailable"
                        : "invalid-or-uncertified-geographic-path"));
            }
        }
        return csv.toString();
    }

    private static String missingProjectionReason(String code) {
        return code == null || code.isBlank() ? "missing-projection-code" : "unsupported-projection-code";
    }

    private static List<GeographicPoint> project(List<EastNorth> points, Projection projection) {
        if (projection == null || points == null || points.size() < 2) {
            return List.of();
        }
        List<GeographicPoint> result = new ArrayList<>(points.size());
        try {
            for (EastNorth point : points) {
                if (point == null || !Double.isFinite(point.east()) || !Double.isFinite(point.north())) {
                    return List.of();
                }
                var coordinate = projection.eastNorth2latlon(point);
                result.add(new GeographicPoint(coordinate.lat(), coordinate.lon()));
            }
        } catch (RuntimeException ex) {
            return List.of();
        }
        return List.copyOf(result);
    }

    private static Measurement measure(List<GeographicPoint> geographic, String conversion, String unavailableReason) {
        if (geographic == null || geographic.size() < 2) {
            return Measurement.unavailable(unavailableReason);
        }
        try {
            double minLat = geographic.stream().mapToDouble(GeographicPoint::latitudeDegrees).min().orElseThrow();
            double maxLat = geographic.stream().mapToDouble(GeographicPoint::latitudeDegrees).max().orElseThrow();
            double minLon = geographic.stream().mapToDouble(GeographicPoint::longitudeDegrees).min().orElseThrow();
            double maxLon = geographic.stream().mapToDouble(GeographicPoint::longitudeDegrees).max().orElseThrow();
            GeographicPoint origin = new GeographicPoint((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0);
            LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(minLat, minLon), new GeographicPoint(maxLat, maxLon));
            List<MetricPoint> metric = geographic.stream().map(frame::toMetric).toList();
            PhysicalPathMeasurements.Result values = PhysicalPathMeasurements.measure(metric);
            double maxTurn = values.vertexTurnsDegrees().stream().mapToDouble(Double::doubleValue).max()
                .orElse(Double.NaN);
            return new Measurement("available", "", conversion + "+" + frame.projectionId(), values.lengthMeters(),
                values.vertexTurnsDegrees().size(), maxTurn, values.zeroLengthSegmentCount());
        } catch (RuntimeException ex) {
            return Measurement.unavailable(unavailableReason);
        }
    }

    private static void append(StringBuilder csv, String candidateId, String stage, Measurement m) {
        csv.append(cell(candidateId)).append(',').append(stage).append(',').append(m.availability()).append(',')
            .append(cell(m.reason())).append(',').append(cell(m.conversion())).append(',')
            .append(number(m.lengthMeters())).append(',').append(m.turnCount() < 0 ? "" : m.turnCount()).append(',')
            .append(number(m.maxTurnDegrees())).append(',')
            .append(m.zeroLengthSegments() < 0 ? "" : m.zeroLengthSegments()).append('\n');
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String cell(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private record Measurement(String availability, String reason, String conversion, double lengthMeters,
        int turnCount, double maxTurnDegrees, int zeroLengthSegments) {
        static Measurement unavailable(String reason) {
            return new Measurement("unavailable", reason, "", Double.NaN, -1, Double.NaN, -1);
        }
    }
}
