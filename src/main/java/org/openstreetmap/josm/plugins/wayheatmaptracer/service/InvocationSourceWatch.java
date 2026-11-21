package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.osm.event.DataSetListenerAdapter;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.LegacyReviewOwner;

/** Only deferred invocations need this gesture-time stamp; it acquires no imagery or native authority. */
public final class InvocationSourceWatch implements AutoCloseable {
    private final DataSet dataSet;
    private final SelectionContext requested;
    private final List<Node> wholeWay;
    private final List<EastNorth> geometry;
    private final AlignmentMode mode;
    private final String projection;
    private final IPreferences preferences=Config.getPref();
    private final AtomicBoolean changed=new AtomicBoolean();
    private final DataSetListenerAdapter changes=new DataSetListenerAdapter(event->changed.set(true));
    private final DataSourceListener sources=event->changed.set(true);
    private final PreferenceChangedListener settings=event->{if(LegacyReviewOwner.isMaterialSetting(event.getKey()))changed.set(true);};
    private boolean closed;
    private InvocationSourceWatch(DataSet dataSet,SelectionContext selection,AlignmentMode mode) {
        this.dataSet=dataSet;this.mode=mode;
        requested=new SelectionContext(selection.way(),selection.startIndex(),selection.endIndex(),List.copyOf(selection.segmentNodes()),Set.copyOf(selection.fixedNodes()));
        wholeWay=List.copyOf(selection.way().getNodes());geometry=selection.segmentNodes().stream().map(Node::getEastNorth).toList();
        projection=ProjectionRegistry.getProjection().toCode();SelectionIntegrity.requirePreviewSourceUnchanged(dataSet,requested,geometry);
        dataSet.addDataSetListener(changes);dataSet.addDataSourceListener(sources);preferences.addPreferenceChangeListener(settings);
    }
    public static InvocationSourceWatch capture(DataSet dataSet,SelectionContext selection,AlignmentMode mode) {
        requireEdt();
        // Same no-mutation delivery barrier as original capture. Host lock wait has no promised bound.
        return dataSet.update(()->new InvocationSourceWatch(dataSet,selection,mode));
    }
    public SelectionContext requested(){return requested;}
    public AlignmentMode requestedMode(){return mode;}
    public void requireCurrent(DataSet active,SelectionContext current,AlignmentMode currentMode) {
        requireEdt();
        if(closed||changed.get()||preferences!=Config.getPref())throw new IllegalStateException("The requested alignment source changed while waiting");
        if(active!=dataSet||!requested.equals(current)||mode!=currentMode||!wholeWay.equals(requested.way().getNodes())
            ||!projection.equals(ProjectionRegistry.getProjection().toCode()))
            throw new IllegalStateException("The requested way, range or mode changed while waiting");
        dataSet.update(()->{
            if(changed.get())throw new IllegalStateException("The requested dataset changed while waiting");
            SelectionIntegrity.requirePreviewSourceUnchanged(dataSet,requested,geometry);
        });
    }
    @Override public void close(){requireEdt();if(!closed){closed=true;dataSet.removeDataSetListener(changes);dataSet.removeDataSourceListener(sources);preferences.removePreferenceChangeListener(settings);}}
    private static void requireEdt(){if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Invocation ownership requires EDT");}
}
