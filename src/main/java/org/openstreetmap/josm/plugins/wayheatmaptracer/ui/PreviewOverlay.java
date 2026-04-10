package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;

public final class PreviewOverlay implements MapViewPaintable {
    private static final PreviewOverlay INSTANCE = new PreviewOverlay();

    private SelectionContext selection;
    private AlignmentResult result;
    private CenterlineCandidate chosenCandidate;
    private boolean debugEnabled;
    private boolean attached;

    public static PreviewOverlay getInstance() {
        return INSTANCE;
    }

    public void show(SelectionContext selection, AlignmentResult result, CenterlineCandidate chosenCandidate, boolean debugEnabled) {
        this.selection = selection;
        this.result = result;
        this.chosenCandidate = chosenCandidate;
        this.debugEnabled = debugEnabled;
        MapView mapView = MainApplication.getMap().mapView;
        if (!attached) {
            mapView.addTemporaryLayer(this);
            attached = true;
        }
        mapView.repaint();
    }

    public void hide() {
        if (attached && MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MainApplication.getMap().mapView.removeTemporaryLayer(this);
        }
        attached = false;
        selection = null;
        result = null;
        chosenCandidate = null;
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        if (selection == null || result == null || chosenCandidate == null) {
            return;
        }

        drawPolyline(g, mv, result.sourcePolyline(), new Color(255, 153, 0, 190), new float[] {6f, 6f}, 2f);
        drawPolyline(g, mv, result.previewPolyline(), new Color(0, 220, 140, 220), null, 3f);

        if (debugEnabled) {
            for (CenterlineCandidate candidate : result.candidates()) {
                List<EastNorth> candidatePolyline = new java.util.ArrayList<>();
                for (Point2D.Double point : candidate.screenPoints()) {
                    candidatePolyline.add(mv.getEastNorth(
                        (int) Math.round(point.x / org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.RASTER_SCALE),
                        (int) Math.round(point.y / org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.RASTER_SCALE)
                    ));
                }
                drawPolyline(g, mv, candidatePolyline, new Color(90, 140, 255, 120), new float[] {2f, 5f}, 1.5f);
            }
        }
    }

    private void drawPolyline(Graphics2D g, MapView mapView, List<EastNorth> polyline, Color color, float[] dash, float width) {
        if (polyline == null || polyline.size() < 2) {
            return;
        }
        Path2D path = new Path2D.Double();
        Point2D first = mapView.getPoint2D(polyline.get(0));
        path.moveTo(first.getX(), first.getY());
        for (int i = 1; i < polyline.size(); i++) {
            Point2D point = mapView.getPoint2D(polyline.get(i));
            path.lineTo(point.getX(), point.getY());
        }
        g.setColor(color);
        g.setStroke(dash == null ? new BasicStroke(width) : new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
        g.draw(path);
    }
}
