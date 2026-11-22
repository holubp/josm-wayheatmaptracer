package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyRefinementBootstrap;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Admitted original root. Its owner is transferred once to the retained session. */
public record PreparedRefinementRoot(LegacyRefinementBootstrap.Root root,RefinementSourcePlanner.Settings settings,
        AlignmentResult original,RefinementSourceContext source,AttemptMemoryLedger.Owner memory,long payloadLimit) implements AutoCloseable {
    public PreparedRefinementRoot {
        Objects.requireNonNull(root);Objects.requireNonNull(settings);Objects.requireNonNull(original);
        Objects.requireNonNull(source);Objects.requireNonNull(memory);
        if(payloadLimit<1)throw new IllegalArgumentException("Positive root payload allowance required");
    }
    @Override public void close(){memory.close();}
}
