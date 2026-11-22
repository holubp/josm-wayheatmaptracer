package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.function.LongSupplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage;

/** Actual retained source capability, separate from original OSM edit authority. */
public interface RefinementSourceContext {
    RefinementAttemptDiagnostics.SourceKind kind();
    void requireCurrent();
    RefinementPipeline.Sources open(RefinementSourcePlanner.Plan plan,Stage parent,GeometryWorkContext work,
        AttemptMemoryLedger.Owner owner,CancellationProbe cancellation);
    LongSupplier detachedCommandGeneration();
    /** Coordinate-free work witness when the actual provider produces one. */
    default java.util.Optional<RefinementAttemptDiagnostics.SourceFacts> sourceFacts(){return java.util.Optional.empty();}
}
