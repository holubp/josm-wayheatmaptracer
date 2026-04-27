package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
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
        drawCandidateAlternatives(g, mv);
        drawPolyline(g, mv, result.previewPolyline(), new Color(0, 90, 255, 230), null, 3.5f);
        drawLegend(g);
    }

    private void drawCandidateAlternatives(Graphics2D g, MapView mapView) {
        if (result.candidates().size() <= 1) {
            return;
        }
        int index = 1;
        for (CenterlineCandidate candidate : result.candidates()) {
            if (candidate.id().equals(chosenCandidate.id())) {
                continue;
            }
            Color color = candidateColor(index++);
            drawCandidateScreenPolyline(g, mapView, candidate, color, new float[] {3f, 5f}, 1.6f);
            if (index <= 9 || debugEnabled) {
                drawCandidateLabel(g, mapView, candidate, color);
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

    private void drawCandidateScreenPolyline(Graphics2D g, MapView mapView, CenterlineCandidate candidate, Color color, float[] dash, float width) {
        if (!candidate.eastNorthPoints().isEmpty()) {
            drawPolyline(g, mapView, candidate.eastNorthPoints(), color, dash, width);
            return;
        }
        if (candidate.screenPoints().size() < 2) {
            return;
        }
        double scale = org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.RASTER_SCALE;
        Path2D path = new Path2D.Double();
        Point2D.Double first = candidate.screenPoints().get(0);
        path.moveTo(first.x / scale, first.y / scale);
        for (int i = 1; i < candidate.screenPoints().size(); i++) {
            Point2D.Double point = candidate.screenPoints().get(i);
            path.lineTo(point.x / scale, point.y / scale);
        }
        g.setColor(color);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
        g.draw(path);
    }

    private void drawCandidateLabel(Graphics2D g, MapView mapView, CenterlineCandidate candidate, Color color) {
        if (candidate.screenPoints().isEmpty() && candidate.eastNorthPoints().isEmpty()) {
            return;
        }
        Point2D point;
        if (!candidate.eastNorthPoints().isEmpty()) {
            point = mapView.getPoint2D(candidate.eastNorthPoints().get(candidate.eastNorthPoints().size() / 2));
        } else {
            double scale = org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.RASTER_SCALE;
            Point2D.Double screen = candidate.screenPoints().get(candidate.screenPoints().size() / 2);
            point = new Point2D.Double(screen.x / scale, screen.y / scale);
        }
        String text = compactLabel(candidate);
        int x = (int) Math.round(point.getX()) + 6;
        int y = (int) Math.round(point.getY()) - 6;
        g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
        int width = g.getFontMetrics().stringWidth(text) + 8;
        int height = g.getFontMetrics().getHeight() + 4;
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRoundRect(x - 4, y - height + 4, width, height, 6, 6);
        g.setColor(color.darker());
        g.drawString(text, x, y);
    }

    private void drawLegend(Graphics2D g) {
        int x = 14;
        int y = 24;
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        drawLegendItem(g, x, y, new Color(0, 90, 255, 230), null, "selected preview");
        drawLegendItem(g, x, y + 18, new Color(255, 153, 0, 190), new float[] {6f, 6f}, "original segment");
        if (result.candidates().size() > 1) {
            drawLegendItem(g, x, y + 36, new Color(130, 80, 230, 150), new float[] {3f, 5f}, "other detected ridges");
        }
    }

    private void drawLegendItem(Graphics2D g, int x, int y, Color color, float[] dash, String text) {
        g.setColor(new Color(255, 255, 255, 210));
        g.fillRoundRect(x - 6, y - 13, 180, 17, 6, 6);
        g.setColor(color);
        g.setStroke(dash == null ? new BasicStroke(3f) : new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
        g.drawLine(x, y - 5, x + 28, y - 5);
        g.setColor(Color.DARK_GRAY);
        g.drawString(text, x + 36, y);
    }

    private Color candidateColor(int index) {
        Color[] colors = {
            new Color(130, 80, 230, 150),
            new Color(0, 160, 180, 150),
            new Color(230, 90, 70, 150),
            new Color(90, 140, 255, 150),
            new Color(180, 110, 0, 150)
        };
        return colors[Math.floorMod(index - 1, colors.length)];
    }

    private String compactLabel(CenterlineCandidate candidate) {
        String label = candidate.displayName();
        if (label.length() <= 34) {
            return label;
        }
        return label.substring(0, 31) + "...";
    }
}
