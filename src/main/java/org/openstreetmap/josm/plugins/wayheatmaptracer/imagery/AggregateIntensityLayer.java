package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.ImageIcon;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.ProjectionBounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;

/**
 * Non-editable white-on-transparent map layer that visualizes the fused all-color managed heatmap intensity field.
 */
public final class AggregateIntensityLayer extends Layer {
    public static final String LAYER_NAME = "WayHeatmapTracer aggregate intensity";
    private static final Icon ICON = createIcon();

    private final TileHeatmapSampler.AggregateVisualization visualization;

    private AggregateIntensityLayer(TileHeatmapSampler.AggregateVisualization visualization) {
        super(LAYER_NAME + " z" + visualization.zoom());
        this.visualization = visualization;
        setOpacity(0.85);
    }

    /**
     * Replaces any previous aggregate visualization layer with the supplied visualization.
     *
     * @param visualization aggregate intensity image to show
     */
    public static void show(TileHeatmapSampler.AggregateVisualization visualization) {
        if (visualization == null || MainApplication.getLayerManager() == null) {
            return;
        }
        removeExisting();
        MainApplication.getLayerManager().addLayer(new AggregateIntensityLayer(visualization));
    }

    /**
     * Removes the aggregate visualization layer if one is present.
     */
    public static void removeExisting() {
        if (MainApplication.getLayerManager() == null) {
            return;
        }
        MainApplication.getLayerManager().getLayers().stream()
            .filter(AggregateIntensityLayer.class::isInstance)
            .toList()
            .forEach(layer -> MainApplication.getLayerManager().removeLayer(layer));
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        if (visualization.image() == null || !isVisible()) {
            return;
        }
        Point2D topLeft = mv.getPoint2D(visualization.topLeft());
        Point2D topRight = mv.getPoint2D(visualization.topRight());
        Point2D bottomLeft = mv.getPoint2D(visualization.bottomLeft());
        Point2D bottomRight = mv.getPoint2D(visualization.bottomRight());
        Polygon screenBounds = new Polygon(
            new int[] {
                (int) Math.round(topLeft.getX()),
                (int) Math.round(topRight.getX()),
                (int) Math.round(bottomRight.getX()),
                (int) Math.round(bottomLeft.getX())
            },
            new int[] {
                (int) Math.round(topLeft.getY()),
                (int) Math.round(topRight.getY()),
                (int) Math.round(bottomRight.getY()),
                (int) Math.round(bottomLeft.getY())
            },
            4
        );
        Rectangle clip = g.getClipBounds();
        if (clip != null && !screenBounds.getBounds().intersects(clip)) {
            return;
        }

        AffineTransform transform = new AffineTransform(
            (topRight.getX() - topLeft.getX()) / visualization.image().getWidth(),
            (topRight.getY() - topLeft.getY()) / visualization.image().getWidth(),
            (bottomLeft.getX() - topLeft.getX()) / visualization.image().getHeight(),
            (bottomLeft.getY() - topLeft.getY()) / visualization.image().getHeight(),
            topLeft.getX(),
            topLeft.getY()
        );
        Graphics2D copy = (Graphics2D) g.create();
        try {
            copy.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) getOpacity()));
            copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            copy.drawImage(visualization.image(), transform, null);
        } finally {
            copy.dispose();
        }
    }

    @Override
    public Icon getIcon() {
        return ICON;
    }

    @Override
    public String getToolTipText() {
        return "WayHeatmapTracer all-color aggregate intensity visualization";
    }

    @Override
    public void mergeFrom(Layer from) {
        throw new UnsupportedOperationException("Aggregate intensity layers cannot be merged.");
    }

    @Override
    public boolean isMergable(Layer other) {
        return false;
    }

    @Override
    public void visitBoundingBox(BoundingXYVisitor v) {
        v.visit(bounds());
    }

    @Override
    public Object getInfoComponent() {
        return "<html><b>" + getName() + "</b><br>"
            + "Colors: " + String.join(", ", visualization.colors()) + "<br>"
            + "Visualization only; not used as editable OSM data.</html>";
    }

    @Override
    public Action[] getMenuEntries() {
        return new Action[0];
    }

    @Override
    public boolean isSavable() {
        return false;
    }

    @Override
    public ProjectionBounds getViewProjectionBounds() {
        return bounds();
    }

    private ProjectionBounds bounds() {
        double minEast = Math.min(Math.min(visualization.topLeft().east(), visualization.topRight().east()),
            Math.min(visualization.bottomLeft().east(), visualization.bottomRight().east()));
        double maxEast = Math.max(Math.max(visualization.topLeft().east(), visualization.topRight().east()),
            Math.max(visualization.bottomLeft().east(), visualization.bottomRight().east()));
        double minNorth = Math.min(Math.min(visualization.topLeft().north(), visualization.topRight().north()),
            Math.min(visualization.bottomLeft().north(), visualization.bottomRight().north()));
        double maxNorth = Math.max(Math.max(visualization.topLeft().north(), visualization.topRight().north()),
            Math.max(visualization.bottomLeft().north(), visualization.bottomRight().north()));
        return new ProjectionBounds(new EastNorth(minEast, minNorth), new EastNorth(maxEast, maxNorth));
    }

    private static Icon createIcon() {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            for (int y = 0; y < 16; y++) {
                float t = y / 15f;
                g.setColor(new Color(t, Math.max(0f, 1f - Math.abs(t - 0.5f) * 2f), 1f - t, 0.95f));
                g.drawLine(0, y, 15, y);
            }
            g.setColor(new Color(255, 255, 255, 180));
            g.drawRect(1, 1, 13, 13);
        } finally {
            g.dispose();
        }
        return new ImageIcon(image);
    }
}
