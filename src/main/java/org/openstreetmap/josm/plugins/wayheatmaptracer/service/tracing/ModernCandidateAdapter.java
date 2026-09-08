package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorQuality;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;

/** Converts detached modern hypotheses into the existing modeless-preview candidate contract. */
public final class ModernCandidateAdapter {
    /**
     * Projects all retained routes and derives truthful, source-independent evidence summaries.
     * Common final geometry validation still runs after precise/move-only reconstruction.
     */
    public List<CenterlineCandidate> adapt(TraceHypothesisSet hypotheses, EvidenceSnapshot evidence,
            String fieldName, List<MetricPoint> sourcePolyline,
            Function<GeographicPoint, EastNorth> geographicProjector) {
        if (hypotheses == null || evidence == null || fieldName == null || fieldName.isBlank()
                || sourcePolyline == null || sourcePolyline.size() < 2 || geographicProjector == null) {
            throw new IllegalArgumentException("Modern candidate adaptation is incomplete");
        }
        ScalarEvidenceField field = evidence.fields().get(fieldName);
        if (field == null) {
            throw new IllegalArgumentException("Selected scalar evidence field is unavailable");
        }
        List<CenterlineCandidate> result = new ArrayList<>();
        for (TraceHypothesis hypothesis : hypotheses.hypotheses()) {
            List<Point2D.Double> raster = hypothesis.points().stream().map(point -> {
                RasterPoint value = evidence.transform().metricToPixelCenter(point);
                return new Point2D.Double(value.x(), value.y());
            }).toList();
            List<EastNorth> projected = hypothesis.points().stream()
                    .map(evidence.coordinateFrame()::toGeographic).map(geographicProjector).toList();
            List<Double> offsets = hypothesis.points().stream()
                    .map(point -> signedDistance(point, sourcePolyline)
                            / evidence.resolution().renderedPitchMeters()).toList();
            CandidateEvidence candidateEvidence = summarize(hypothesis, evidence, field, fieldName);
            result.add(new CenterlineCandidate(hypothesis.id(), -hypothesis.objective(), raster, offsets,
                    projected, candidateEvidence, List.of()));
        }
        return List.copyOf(result);
    }

    private static CandidateEvidence summarize(TraceHypothesis hypothesis, EvidenceSnapshot evidence,
            ScalarEvidenceField field, String fieldName) {
        int supported = 0;
        int empty = 0;
        int maximumEmpty = 0;
        int currentEmpty = 0;
        int first = -1;
        int last = -1;
        double total = 0.0;
        for (int index = 0; index < hypothesis.points().size(); index++) {
            ObservationOwnership ownership = hypothesis.support().get(index);
            boolean observed = ownership == ObservationOwnership.DIRECT_TWO_SIDED
                    || ownership == ObservationOwnership.DIRECT_AMBIGUOUS
                    || ownership == ObservationOwnership.SHOULDER_CENSORED;
            OptionalDouble sample = sample(field, evidence, hypothesis.points().get(index));
            if (observed && sample.isPresent()) {
                supported++;
                currentEmpty = 0;
                first = first < 0 ? index : first;
                last = index;
                total += sample.getAsDouble();
            } else {
                empty++;
                currentEmpty++;
                maximumEmpty = Math.max(maximumEmpty, currentEmpty);
            }
        }
        int count = hypothesis.points().size();
        double mean = supported == 0 ? 0.0 : total / supported;
        boolean complete = supported > 0 && empty == 0;
        CorridorCoverage coverage = new CorridorCoverage(true, complete, supported, supported,
                count == 0 ? 0.0 : (double) supported / count, first, last,
                first <= 0 ? 0.0 : chainage(hypothesis.points(), 0, first),
                last < 0 || last == count - 1 ? 0.0 : chainage(hypothesis.points(), last, count - 1),
                maximumEmpty, maximumGap(hypothesis), 0, false,
                complete ? "modern-complete-direct-evidence" : "modern-incomplete-evidence");
        double ambiguity = 1.0 - hypothesis.posteriorProbability();
        double persistence = Math.max(0.0, Math.min(1.0,
                hypothesis.diagnostics().getOrDefault("longitudinalPersistence",
                        count == 0 ? 0.0 : (double) supported / count)));
        return new CandidateEvidence(fieldName, count, supported, empty, maximumEmpty, total, mean,
                hypothesis.diagnostics().getOrDefault("meanGradientStrength", 0.0), persistence,
                supported == 0 ? 0.0 : mean / Math.max(0.02, 1.0 - mean), ambiguity,
                supported == 0 ? 0.0 : Math.min(1.0, mean + persistence * 0.25),
                supported == 0 ? 0.0 : Math.min(1.0, hypothesis.posteriorProbability() + 0.25),
                hypothesis.objective(), supported == 0 ? 0.0 : (double) supported / count,
                0.0, 0.0, CorridorQuality.empty(), coverage, List.of(fieldName));
    }

    private static OptionalDouble sample(ScalarEvidenceField field, EvidenceSnapshot evidence,
            MetricPoint point) {
        RasterPoint raster = evidence.transform().metricToPixelCenter(point);
        int x = (int) Math.round(raster.x());
        int y = (int) Math.round(raster.y());
        return x < 0 || y < 0 || x >= field.width() || y >= field.height()
                ? OptionalDouble.empty() : field.sample(x, y);
    }

    private static double maximumGap(TraceHypothesis hypothesis) {
        double maximum = 0.0;
        int start = -1;
        for (int index = 0; index < hypothesis.support().size(); index++) {
            ObservationOwnership ownership = hypothesis.support().get(index);
            boolean missing = ownership == ObservationOwnership.NO_RASTER
                    || ownership == ObservationOwnership.NO_SIGNAL_VALID_RASTER
                    || ownership == ObservationOwnership.INFERRED_GAP;
            if (missing && start < 0) {
                start = index;
            } else if (!missing && start >= 0) {
                maximum = Math.max(maximum, chainage(hypothesis.points(), start, index));
                start = -1;
            }
        }
        return start < 0 ? maximum : Math.max(maximum,
                chainage(hypothesis.points(), start, hypothesis.points().size() - 1));
    }

    private static double chainage(List<MetricPoint> points, int first, int last) {
        double result = 0.0;
        for (int index = first + 1; index <= last; index++) {
            result += points.get(index - 1).distanceTo(points.get(index));
        }
        return result;
    }

    private static double signedDistance(MetricPoint point, List<MetricPoint> source) {
        double bestSquared = Double.POSITIVE_INFINITY;
        double signed = 0.0;
        for (int index = 1; index < source.size(); index++) {
            MetricPoint start = source.get(index - 1);
            MetricPoint end = source.get(index);
            double dx = end.xMeters() - start.xMeters();
            double dy = end.yMeters() - start.yMeters();
            double lengthSquared = dx * dx + dy * dy;
            if (lengthSquared <= 1.0e-12) {
                continue;
            }
            double fraction = ((point.xMeters() - start.xMeters()) * dx
                    + (point.yMeters() - start.yMeters()) * dy) / lengthSquared;
            fraction = Math.max(0.0, Math.min(1.0, fraction));
            double nearestX = start.xMeters() + fraction * dx;
            double nearestY = start.yMeters() + fraction * dy;
            double offsetX = point.xMeters() - nearestX;
            double offsetY = point.yMeters() - nearestY;
            double squared = offsetX * offsetX + offsetY * offsetY;
            if (squared < bestSquared) {
                bestSquared = squared;
                double cross = dx * offsetY - dy * offsetX;
                signed = Math.copySign(Math.sqrt(squared), cross == 0.0 ? 1.0 : cross);
            }
        }
        return signed;
    }
}
