package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Declares whether a detached network snapshot is captured state or a proposed state. */
public enum SnapshotRole {
    /** Exact immutable materialization of the repeatable closure query. */
    CAPTURED_BEFORE,
    /** Proposed result that may omit authorized removals and add plan-local nodes. */
    PROPOSED_AFTER
}
