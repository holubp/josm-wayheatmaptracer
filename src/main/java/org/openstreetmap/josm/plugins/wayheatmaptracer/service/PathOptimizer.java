package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

public final class PathOptimizer {
    public List<EastNorth> optimize(List<EastNorth> source, CenterlineCandidate candidate, MapView mapView) {
        List<EastNorth> target = projectCandidate(candidate, mapView);
        List<EastNorth> current = PolylineMath.resampleByCount(source, target.size());
        for (int iteration = 0; iteration < 20; iteration++) {
            List<EastNorth> next = new ArrayList<>(current);
            for (int i = 1; i < current.size() - 1; i++) {
                EastNorth prev = current.get(i - 1);
                EastNorth after = current.get(i + 1);
                EastNorth targetPoint = target.get(i);
                EastNorth sourcePoint = source.get(Math.min(i, source.size() - 1));

                double east = (prev.east() + after.east()) * 0.30 + targetPoint.east() * 0.55 + sourcePoint.east() * 0.15;
                double north = (prev.north() + after.north()) * 0.30 + targetPoint.north() * 0.55 + sourcePoint.north() * 0.15;
                next.set(i, new EastNorth(east, north));
            }
            current = next;
        }
        return current;
    }

    public List<EastNorth> projectCandidate(CenterlineCandidate candidate, MapView mapView) {
        if (!candidate.eastNorthPoints().isEmpty()) {
            PluginLog.debug("Candidate %s already carries %d projected EastNorth points.", candidate.id(), candidate.eastNorthPoints().size());
            return candidate.eastNorthPoints();
        }
        List<EastNorth> target = new ArrayList<>(candidate.screenPoints().size());
        AffineTransform inverse = inverseTransform(mapView);
        double[] matrix = new double[6];
        inverse.getMatrix(matrix);
        PluginLog.debug("Inverse view transform for candidate projection: [%.6f %.6f %.6f %.6f %.6f %.6f]",
            matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]);
        for (int i = 0; i < candidate.screenPoints().size(); i++) {
            Point2D.Double point = candidate.screenPoints().get(i);
            double viewX = point.x / RenderedHeatmapSampler.RASTER_SCALE;
            double viewY = point.y / RenderedHeatmapSampler.RASTER_SCALE;
            EastNorth fromState = mapView.getState().getForView(viewX, viewY).getEastNorth();
            Point2D transformed = inverse.transform(new Point2D.Double(viewX, viewY), null);
            EastNorth fromAffine = new EastNorth(transformed.getX(), transformed.getY());
            target.add(fromState);
            if (i < 12) {
                PluginLog.debug(
                    "CandidatePoint[%d] raster=(%.2f,%.2f) view=(%.2f,%.2f) -> state=(%.3f,%.3f) affine=(%.3f,%.3f) delta=(%.6f,%.6f)",
                    i,
                    point.x, point.y,
                    viewX, viewY,
                    fromState.east(), fromState.north(),
                    fromAffine.east(), fromAffine.north(),
                    fromState.east() - fromAffine.east(),
                    fromState.north() - fromAffine.north()
                );
            }
        }
        return target;
    }

    private AffineTransform inverseTransform(MapView mapView) {
        try {
            return mapView.getAffineTransform().createInverse();
        } catch (NoninvertibleTransformException e) {
            throw new IllegalStateException("Map view transform is not invertible.", e);
        }
    }
}
