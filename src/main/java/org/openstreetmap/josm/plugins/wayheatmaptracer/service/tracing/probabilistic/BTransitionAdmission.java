package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;

/** Exact adjacent geometry admission, with independently selected storage backends. */
interface BTransitionAdmission {
    boolean allowed(int row, int first, int second, List<InferenceProfile> profiles, MetricRegion region);
}
