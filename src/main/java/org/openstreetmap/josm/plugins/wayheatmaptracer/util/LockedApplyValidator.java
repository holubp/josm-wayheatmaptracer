package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;

/** Factual Apply preflight executed under the command's dataset write lock. */
public interface LockedApplyValidator {
    /** Returns the dataset identity that owns the captured Apply boundary. */
    String datasetIdentity();

    /** Validates the exact plan before allocation, command snapshotting, or mutation. */
    void validateLocked(DataSet dataSet, AlignmentEditPlan plan, boolean firstExecution);
}
