package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image;

import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;

/** Immutable pure-computation input for one bounded direction-aware image search attempt. */
public record DirectionalImageSearchProblem(EvidenceSnapshot evidence, String fieldName,
    List<MetricPoint> orderedAnchors, TraceBudgets budgets, RecoveryPermissions permissions,
    Scope scope, boolean zeroHeuristic) {
    /** Attempt lineage; callers must construct a separate snapshot for widened acquisition. */
    public enum Scope { ORDINARY, WIDER_DISCOVERY }

    public DirectionalImageSearchProblem {
        if (evidence == null || fieldName == null || fieldName.isBlank() || orderedAnchors == null
            || orderedAnchors.size() < 2 || orderedAnchors.stream().anyMatch(point -> point == null)
            || budgets == null || permissions == null || scope == null || !evidence.fields().containsKey(fieldName)
            || scope == Scope.WIDER_DISCOVERY && !permissions.widerDiscovery()) {
            throw new IllegalArgumentException("Directional image search input is incomplete or unauthorized");
        }
        orderedAnchors = List.copyOf(orderedAnchors);
    }

    /** Returns the factual pitch used for the orientation-lifted grid. */
    public double gridPitchMeters() {
        return evidence.resolution().effectivePitchMeters() / 2.0;
    }
}
