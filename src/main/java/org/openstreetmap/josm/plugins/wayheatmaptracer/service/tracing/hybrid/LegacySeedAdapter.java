package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RidgeTracker;

/** Reuses the production Legacy numerical tracker without involving live OSM primitives. */
public final class LegacySeedAdapter {
    private final RidgeTracker tracker = new RidgeTracker();

    /** Returns native Legacy alternatives in their original order and raster coordinate frame. */
    public List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
            double sourcePixelSizeRasterPx) {
        return tracker.track(profiles, sourcePixelSizeRasterPx);
    }
}
