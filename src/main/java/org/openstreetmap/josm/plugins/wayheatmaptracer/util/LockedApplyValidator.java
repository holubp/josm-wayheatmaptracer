package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;

/** Factual Apply preflight executed under the command's dataset write lock. */
public interface LockedApplyValidator {
    /** Returns the dataset identity that owns the captured Apply boundary. */
    String datasetIdentity();

    /** Validates the exact plan before every Apply or Redo allocation, snapshot, or mutation. */
    void validateLocked(DataSet dataSet, AlignmentEditPlan plan, boolean requireSourceGeneration);

    /** Reports a rejected Redo to the source owner's UI; must not change command state. */
    default void reportRejectedRedo(RuntimeException failure) { }
}
