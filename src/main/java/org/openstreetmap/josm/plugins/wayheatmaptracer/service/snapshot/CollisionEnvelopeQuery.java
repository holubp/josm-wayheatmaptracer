package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DistortionCertificate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;

/**
 * Repeatable bounded query for live ways intersecting a detached collision envelope.
 *
 * <p>The query scans {@link DataSet#allPrimitives()} directly so every visited primitive and
 * segment is charged to an explicit budget. Snapshot producers and live validators must use the
 * same {@link #DEFINITION} and store its version in the closure descriptor. Geographic segments
 * are clipped to the certified frame domain before the affine local-metric conversion, including
 * across the antimeridian. A remote segment therefore cannot fail merely because its endpoints
 * lie outside that domain.</p>
 */
public final class CollisionEnvelopeQuery {
    /** The one query contract understood by the v0.22 live validator. */
    public static final Definition DEFINITION = new Definition(
        "live-collision-envelope-v2", 250_000, 1_000_000);

    private static final double DEGREES_EPSILON = 1e-12;
    private static final double[] LONGITUDE_SHIFTS = {-360.0, 0.0, 360.0};

    private CollisionEnvelopeQuery() {
    }

    /** Versioned deterministic scan limits that form part of snapshot query identity. */
    public record Definition(String version, int maximumExaminedPrimitives,
                             int maximumExaminedSegments) {
        /** Validates the explicit query identity and positive hard limits. */
        public Definition {
            if (version == null || version.isBlank() || maximumExaminedPrimitives <= 0
                || maximumExaminedSegments <= 0) {
                throw new IllegalArgumentException("Collision query definition is incomplete");
            }
        }
    }

    /** Immutable query outcome with the exact charged work. */
    public record Result(Set<PrimitiveKey> intersectingWayKeys, int examinedPrimitives,
                         int examinedSegments, Definition definition) {
        /** Copies the identity set and requires a complete outcome. */
        public Result {
            intersectingWayKeys = Set.copyOf(new TreeSet<>(intersectingWayKeys));
            Objects.requireNonNull(definition, "definition");
            if (examinedPrimitives < 0 || examinedSegments < 0) {
                throw new IllegalArgumentException("Collision query counters cannot be negative");
            }
        }
    }

    /**
     * Executes the current production query.
     *
     * @param dataSet live dataset protected by its owner's read or write boundary
     * @param frame certified geographic-to-metric transform from the snapshot
     * @param collisionEnvelope exact metric region to test, including its boundary
     * @return relevant live way identities and charged work
     * @throws IllegalStateException when a hard budget is exhausted or relevant geometry is incomplete
     */
    public static Result execute(DataSet dataSet, LocalMetricFrame frame,
        MetricRegion collisionEnvelope) {
        return execute(dataSet, frame, collisionEnvelope, DEFINITION);
    }

    static Result execute(DataSet dataSet, LocalMetricFrame frame, MetricRegion collisionEnvelope,
        Definition definition) {
        Objects.requireNonNull(dataSet, "dataSet");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(collisionEnvelope, "collisionEnvelope");
        Objects.requireNonNull(definition, "definition");
        CertifiedDomain domain = CertifiedDomain.from(frame);
        if (!domain.metricRegion().containsRegion(collisionEnvelope)) {
            throw new IllegalStateException("Collision envelope exceeds the certified metric-frame domain");
        }

        int primitiveCount = 0;
        int segmentCount = 0;
        Set<PrimitiveKey> intersectingWays = new LinkedHashSet<>();
        for (OsmPrimitive primitive : dataSet.allPrimitives()) {
            primitiveCount = chargedIncrement(primitiveCount, definition.maximumExaminedPrimitives(),
                "primitive");
            if (!(primitive instanceof Way way) || way.isDeleted()) {
                continue;
            }
            int waySegmentCount = Math.max(0, way.getNodesCount() - 1);
            if (waySegmentCount > definition.maximumExaminedSegments() - segmentCount) {
                throw new IllegalStateException("Collision query segment budget exceeded: "
                    + definition.maximumExaminedSegments());
            }
            segmentCount += waySegmentCount;
            List<Node> nodes = way.getNodes();
            // The envelope is segment-based. A zero-segment placeholder has no spatial
            // extent that can intersect it.
            if (nodes.size() < 2) {
                continue;
            }
            boolean intersects = false;
            boolean incompleteGeometry = way.isIncomplete() || way.hasIncompleteNodes();
            StringBuilder incompleteDetail = new StringBuilder();
            if (way.isIncomplete()) {
                incompleteDetail.append("way-incomplete");
            }
            if (nodes.size() < 2) {
                appendIncompleteDetail(incompleteDetail, "fewer-than-two-nodes");
            }
            for (Node node : nodes) {
                if (!node.isLatLonKnown()) {
                    incompleteGeometry = true;
                    appendIncompleteDetail(incompleteDetail, "unknown-node-" + node.getUniqueId());
                }
            }
            for (int index = 1; index < nodes.size(); index++) {
                Node start = nodes.get(index - 1);
                Node end = nodes.get(index);
                if (!start.isLatLonKnown() || !end.isLatLonKnown()) {
                    incompleteGeometry = true;
                    continue;
                }
                if (isAntipodalLongitudeArc(start.lon(), end.lon())) {
                    throw new IllegalStateException("Collision query indeterminate: ambiguous antipodal "
                        + "longitude arc in live way: " + way.getUniqueId());
                }
                Optional<MetricSegment> segment = domain.clipAndConvert(
                    start.lat(), start.lon(), end.lat(), end.lon());
                if (segment.isPresent()) {
                    MetricSegment value = segment.orElseThrow();
                    if (collisionEnvelope.intersectsSegment(value.start(), value.end())) {
                        intersects = true;
                    }
                }
            }
            if (incompleteGeometry) {
                throw new IllegalStateException("Collision query indeterminate: incomplete geometry "
                    + "prevents proving exclusion for live way: " + way.getUniqueId()
                    + " (" + (incompleteDetail.isEmpty() ? "has-incomplete-nodes" : incompleteDetail) + ")");
            }
            if (intersects) {
                intersectingWays.add(PrimitiveKey.existing(PrimitiveKey.Type.WAY, way.getUniqueId()));
            }
        }
        return new Result(intersectingWays, primitiveCount, segmentCount, definition);
    }

    private static void appendIncompleteDetail(StringBuilder detail, String value) {
        if (!detail.isEmpty()) {
            detail.append(",");
        }
        detail.append(value);
    }

    private static int chargedIncrement(int current, int maximum, String unit) {
        if (current >= maximum) {
            throw new IllegalStateException("Collision query " + unit + " budget exceeded: " + maximum);
        }
        return current + 1;
    }

    record MetricSegment(MetricPoint start, MetricPoint end) {
    }

    private record DegreeSegment(double startX, double startY, double endX, double endY) {
    }

    record CertifiedDomain(double westLongitude, double longitudeSpan, double southLatitude,
                                   double northLatitude, double originX, LocalMetricFrame frame,
                                   MetricRegion metricRegion) {
        static CertifiedDomain from(LocalMetricFrame frame) {
            DistortionCertificate certificate = frame.distortionCertificate();
            double west = certificate.southWest().longitudeDegrees();
            double span = positiveLongitudeSpan(west, certificate.northEast().longitudeDegrees());
            double south = certificate.southWest().latitudeDegrees();
            double north = certificate.northEast().latitudeDegrees();
            if (!(span > 0.0) || !(north > south)) {
                throw new IllegalStateException("Collision query requires a two-dimensional certified domain");
            }
            double originX = positiveLongitudeSpan(west, frame.origin().longitudeDegrees());
            MetricPoint southWest = toMetric(0.0, south, originX, frame);
            MetricPoint northEast = toMetric(span, north, originX, frame);
            MetricRegion metricDomain = MetricRegion.rectangle(southWest.xMeters(), southWest.yMeters(),
                northEast.xMeters(), northEast.yMeters());
            return new CertifiedDomain(west, span, south, north, originX, frame, metricDomain);
        }

        Optional<MetricPoint> metricPointInside(double latitude, double longitude) {
            double x = normalizeDelta(longitude - westLongitude);
            for (double shift : LONGITUDE_SHIFTS) {
                double shifted = x + shift;
                if (shifted >= -DEGREES_EPSILON && shifted <= longitudeSpan + DEGREES_EPSILON
                    && latitude >= southLatitude - DEGREES_EPSILON
                    && latitude <= northLatitude + DEGREES_EPSILON) {
                    return Optional.of(toMetric(clamp(shifted, 0.0, longitudeSpan),
                        clamp(latitude, southLatitude, northLatitude), originX, frame));
                }
            }
            return Optional.empty();
        }

        Optional<MetricSegment> clipAndConvert(double startLatitude, double startLongitude,
            double endLatitude, double endLongitude) {
            double startX = normalizeDelta(startLongitude - westLongitude);
            double deltaX = normalizeDelta(endLongitude - startLongitude);
            for (double shift : LONGITUDE_SHIFTS) {
                Optional<DegreeSegment> clipped = clipRectangle(startX + shift, startLatitude,
                    startX + shift + deltaX, endLatitude, 0.0, longitudeSpan,
                    southLatitude, northLatitude);
                if (clipped.isPresent()) {
                    DegreeSegment value = clipped.orElseThrow();
                    return Optional.of(new MetricSegment(
                        toMetric(value.startX(), value.startY(), originX, frame),
                        toMetric(value.endX(), value.endY(), originX, frame)));
                }
            }
            return Optional.empty();
        }
    }

    private static Optional<DegreeSegment> clipRectangle(double startX, double startY,
        double endX, double endY, double minimumX, double maximumX,
        double minimumY, double maximumY) {
        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double[] interval = {0.0, 1.0};
        if (!clipAxis(startX, deltaX, minimumX, maximumX, interval)
            || !clipAxis(startY, deltaY, minimumY, maximumY, interval)) {
            return Optional.empty();
        }
        return Optional.of(new DegreeSegment(
            clamp(startX + interval[0] * deltaX, minimumX, maximumX),
            clamp(startY + interval[0] * deltaY, minimumY, maximumY),
            clamp(startX + interval[1] * deltaX, minimumX, maximumX),
            clamp(startY + interval[1] * deltaY, minimumY, maximumY)));
    }

    private static boolean clipAxis(double start, double delta, double minimum, double maximum,
        double[] interval) {
        if (Math.abs(delta) <= DEGREES_EPSILON) {
            return start >= minimum - DEGREES_EPSILON && start <= maximum + DEGREES_EPSILON;
        }
        double first = (minimum - start) / delta;
        double second = (maximum - start) / delta;
        double enter = Math.min(first, second);
        double exit = Math.max(first, second);
        interval[0] = Math.max(interval[0], enter);
        interval[1] = Math.min(interval[1], exit);
        return interval[0] <= interval[1] + DEGREES_EPSILON;
    }

    private static MetricPoint toMetric(double xDegreesFromWest, double latitude,
        double originX, LocalMetricFrame frame) {
        DistortionCertificate certificate = frame.distortionCertificate();
        return new MetricPoint(
            Math.toRadians(xDegreesFromWest - originX) * certificate.eastMetersPerRadian(),
            Math.toRadians(latitude - frame.origin().latitudeDegrees())
                * certificate.northMetersPerRadian());
    }

    private static double positiveLongitudeSpan(double west, double east) {
        double result = (east - west) % 360.0;
        return result < 0.0 ? result + 360.0 : result;
    }

    private static double normalizeDelta(double value) {
        double result = value % 360.0;
        if (result > 180.0) {
            result -= 360.0;
        } else if (result <= -180.0) {
            result += 360.0;
        }
        return result;
    }

    private static boolean isAntipodalLongitudeArc(double startLongitude, double endLongitude) {
        double wrappedAbsoluteDelta = Math.abs((endLongitude - startLongitude) % 360.0);
        return Math.abs(wrappedAbsoluteDelta - 180.0) <= DEGREES_EPSILON;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
