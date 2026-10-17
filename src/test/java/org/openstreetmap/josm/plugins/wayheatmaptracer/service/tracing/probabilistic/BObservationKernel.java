package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Pure observation replacement; no truth, fitting or selected-path inputs. */
interface BObservationKernel {
    BObservationMixture evaluate(BIdentificationFacts.ProfileFacts facts,
            ProbabilisticStateLattice lattice, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner);
    String identityHash();
}
