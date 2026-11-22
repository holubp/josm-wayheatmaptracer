package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ModernReviewRoot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.PreparedRefinementRoot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;

/** Completes a factual native first pass without another capture or inference. */
public final class ModernRefinementBootstrap {
    public PreparedRefinementRoot complete(ModernReviewRoot review,GeometryWorkContext work){return review.prepare(work);}
    public PreparedRefinementRoot complete(ModernReviewRoot review,GeometryWorkContext work,VisibleRefinementCapture.Port visibleCapture){return review.prepare(work,visibleCapture);}
}
