package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.List;
import java.util.ArrayList;
import java.util.TreeMap;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RidgeTracker;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

/** Reuses the production Legacy numerical tracker without involving live OSM primitives. */
public final class LegacySeedAdapter {
    private final RidgeTracker tracker = new RidgeTracker();

    /** Returns native Legacy alternatives in their original order and raster coordinate frame. */
    public List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
            double sourcePixelSizeRasterPx) {
        return tracker.track(profiles, sourcePixelSizeRasterPx);
    }

    /** Same native arithmetic with cooperative cancellation during numerical work. */
    public List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
            double sourcePixelSizeRasterPx, CancellationProbe cancellation) {
        return tracker.track(profiles, sourcePixelSizeRasterPx, cancellation);
    }

    /**
     * Restores Legacy's exact fixed anchors before converting through the slide-time projection.
     * Neither the active projection nor live primitives are consulted. The supplied metric source
     * is the immutable original authority, retained bit for bit for restored occurrences.
     */
    public LegacyBModels.Seed reconstructSeed(String id, List<ProjectedPoint> originalProjected,
            List<MetricPoint> originalMetric, List<ProjectedPoint> trace, List<Integer> fixedIndices,
            OccurrenceRange selectedRange, String capturedProjectionCode, LocalMetricFrame frame) {
        if (originalProjected == null || originalMetric == null || selectedRange == null
                || originalProjected.size() != originalMetric.size()
                || originalMetric.size() != selectedRange.lastIndex() - selectedRange.firstIndex() + 1
                || frame == null || capturedProjectionCode == null) {
            throw new IllegalArgumentException("Complete captured source and projection authority are required");
        }
        var projection = Projections.getProjectionByCode(capturedProjectionCode);
        if (projection == null) {
            throw new IllegalArgumentException("Captured projection is unavailable");
        }
        var reconstructed = LegacyPreciseGeometry.reconstruct(originalProjected, trace, fixedIndices);
        var points = new ArrayList<MetricPoint>(reconstructed.points().size());
        var retainedOriginal = new TreeMap<Integer, Integer>();
        reconstructed.originalToVertex().forEach((original, vertex) -> {
            if (reconstructed.points().get(vertex).equals(originalProjected.get(original))) {
                retainedOriginal.put(vertex, original);
            }
        });
        for (int vertex = 0; vertex < reconstructed.points().size(); vertex++) {
            Integer original = retainedOriginal.get(vertex);
            if (original != null) {
                points.add(originalMetric.get(original));
            } else {
                var projected = reconstructed.points().get(vertex);
                var coordinate = projection.eastNorth2latlon(new EastNorth(projected.east(), projected.north()));
                points.add(frame.toMetric(new GeographicPoint(coordinate.lat(), coordinate.lon())));
            }
        }
        var mapping = new TreeMap<Integer, Integer>();
        reconstructed.originalToVertex().forEach((original, vertex) ->
                mapping.put(selectedRange.firstIndex() + original, vertex));
        return new LegacyBModels.Seed(id, points, mapping);
    }
}
