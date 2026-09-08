package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Map;

/** Common immutable state of a captured OSM primitive. */
public sealed interface DetachedPrimitive permits DetachedNode, DetachedWay, DetachedRelation {
    /** Returns the type-safe primitive identity. */
    PrimitiveKey key();

    /** Returns immutable tags. */
    Map<String, String> tags();

    /** Returns the captured deleted flag. */
    boolean deleted();

    /** Returns the captured modified flag. */
    boolean modified();
}
