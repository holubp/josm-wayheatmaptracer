package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.imagery.ColorfulImageProcessor;
import org.openstreetmap.josm.gui.layer.imagery.GammaImageProcessor;
import org.openstreetmap.josm.gui.layer.imagery.SharpenImageProcessor;
import org.openstreetmap.josm.tools.ImageProcessor;

/** Exact supported normal-JOSM render state. No pixels, viewport or arbitrary processor serialization. */
public final class VisibleRenderPolicy {
    private record Processor(ImageProcessor identity,long value) { }
    private final WeakReference<ManagedHeatmapLayer> source;
    private final VisibleSourceEpoch.Receipt publication;
    private final List<Processor> processors;
    private final String projection;
    private final long dx,dy,opacity;
    private final boolean visible,autoLoad,autoZoom,showErrors;
    private final int manualZoom;
    private VisibleRenderPolicy(ManagedHeatmapLayer layer) {
        publication=layer.sourceEpoch().captureStable();source=new WeakReference<>(layer);
        var active=ProjectionRegistry.getProjection();
        if(active==null||!"EPSG:3857".equals(active.toCode()))throw new UnsupportedOperationException("Visible render projection is unsupported");
        projection=active.toCode();var display=layer.getDisplaySettings();
        dx=bits(display.getDx());dy=bits(display.getDy());opacity=bits(layer.getOpacity());visible=layer.isVisible();
        autoLoad=display.isAutoLoad();autoZoom=display.isAutoZoom();showErrors=display.isShowErrors();manualZoom=autoZoom?-1:layer.getZoomLevel();
        var values=new ArrayList<Processor>();
        // Normal host filtering has three processors. Bound authority metadata independently
        // of caller-controlled lists; this does not change any solver or operation limit.
        if(layer.getImageProcessors().size()>64)throw new UnsupportedOperationException("Visible render processor inventory is unsupported");
        for(var processor:layer.getImageProcessors())values.add(new Processor(processor,parameter(processor)));
        processors=List.copyOf(values);publication.owner().requireCurrent(publication);
    }
    public static VisibleRenderPolicy capture(ManagedHeatmapLayer source) {
        requireEdt();return new VisibleRenderPolicy(java.util.Objects.requireNonNull(source));
    }
    public boolean belongsTo(ImageryLayer layer){return source.get()==layer;}
    public VisibleSourceEpoch.Receipt publication(){return publication;}
    public long metadataBytes(){return 512L+128L*processors.size();}
    /** Workers observe only the thread-safe publication barrier; EDT boundaries inspect host policy. */
    public void requireEpochCurrent(){publication.owner().requireCurrent(publication);}
    public ManagedHeatmapLayer currentSource(){requireCurrent();var layer=source.get();if(layer==null)throw changed();return layer;}
    public void requireCurrent() {
        requireEdt();requireEpochCurrent();var layer=source.get();if(layer==null)throw changed();
        try{
            var actual=new VisibleRenderPolicy(layer);
            if(!projection.equals(actual.projection)||dx!=actual.dx||dy!=actual.dy||opacity!=actual.opacity||visible!=actual.visible
                    ||autoLoad!=actual.autoLoad||autoZoom!=actual.autoZoom||showErrors!=actual.showErrors||manualZoom!=actual.manualZoom
                    ||processors.size()!=actual.processors.size())throw changed();
            for(int i=0;i<processors.size();i++)if(processors.get(i).identity()!=actual.processors.get(i).identity()||processors.get(i).value()!=actual.processors.get(i).value())throw changed();
            requireEpochCurrent();
        }catch(UnsupportedOperationException unsupported){throw changed();}
    }
    private static long parameter(ImageProcessor processor) {
        if(processor!=null&&processor.getClass()==GammaImageProcessor.class)return bits(((GammaImageProcessor)processor).getGamma());
        if(processor!=null&&processor.getClass()==ColorfulImageProcessor.class)return bits(((ColorfulImageProcessor)processor).getColorfulness());
        if(processor!=null&&processor.getClass()==SharpenImageProcessor.class){float value=((SharpenImageProcessor)processor).getSharpenLevel();if(!Float.isFinite(value))throw new UnsupportedOperationException("Visible render parameter is unsupported");return Float.floatToIntBits(value);}
        throw new UnsupportedOperationException("Visible render processor is unsupported");
    }
    private static long bits(double value){if(!Double.isFinite(value))throw new UnsupportedOperationException("Visible render parameter is unsupported");return Double.doubleToLongBits(value);}
    private static IllegalStateException changed(){return new IllegalStateException("Visible rendering policy changed after capture");}
    private static void requireEdt(){if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Visible render policy requires EDT");}
}
