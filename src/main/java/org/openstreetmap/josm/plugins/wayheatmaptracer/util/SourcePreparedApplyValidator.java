package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.Objects;
import java.util.function.Consumer;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;

/** Combines a minimal prepared source lease with the existing original network witness. */
public record SourcePreparedApplyValidator(LiveNetworkSnapshotValidator network,PreparedCommandSource source,
        Consumer<String> reporter) implements LockedApplyValidator {
    public SourcePreparedApplyValidator{Objects.requireNonNull(network);Objects.requireNonNull(source);Objects.requireNonNull(reporter);}
    public String datasetIdentity(){return network.datasetIdentity();}
    public boolean returnsNormallyAfterCompletedTransaction(){return true;}
    public Runnable prepareExecution(DataSet data,boolean redo){return source.prepareExecution(redo);}
    public void executeWithPreparedSource(Runnable transaction){source.withSource(transaction);}
    public void validateLocked(DataSet data,AlignmentEditPlan plan,boolean requireSource){network.validateLocked(data,plan,true);}
    public void validateUndoLocked(DataSet data,AlignmentEditPlan plan){network.validateUndoLocked(data,plan);}
    public void reportRejectedRedo(RuntimeException failure){reporter.accept("The captured source or network changed; recompute alignment before applying.");}
    public void reportRejectedUndo(RuntimeException failure){reporter.accept("Alignment Undo was refused because the OSM context changed.");}
}
