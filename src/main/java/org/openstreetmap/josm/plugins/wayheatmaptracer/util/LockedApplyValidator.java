package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;

/** Source preflight outside the dataset lock and factual network validation inside it. */
public interface LockedApplyValidator {
    /** Returns the dataset identity that owns the captured Apply boundary. */
    String datasetIdentity();

    /** Performs bounded source work before the dataset update and returns its locked receipt check. */
    default Runnable prepareExecution(DataSet dataSet) { return () -> { }; }

    /** Prepares one execution, with Redo indicated only after an earlier successful Apply. */
    default Runnable prepareExecution(DataSet dataSet, boolean redo) {
        return prepareExecution(dataSet);
    }

    /** Holds any source publication barrier around the complete dataset update and rollback. */
    default void executeWithPreparedSource(Runnable transaction) {
        transaction.run();
    }

    /** Explicitly certifies that the wrapper never rejects after a normally returned transaction. */
    default boolean returnsNormallyAfterCompletedTransaction() { return false; }

    /** Validates the exact plan before every Apply or Redo allocation, snapshot, or mutation. */
    void validateLocked(DataSet dataSet, AlignmentEditPlan plan, boolean requireSourceGeneration);

    /** Reports a rejected Redo to the source owner's UI; must not change command state. */
    default void reportRejectedRedo(RuntimeException failure) { }
}
