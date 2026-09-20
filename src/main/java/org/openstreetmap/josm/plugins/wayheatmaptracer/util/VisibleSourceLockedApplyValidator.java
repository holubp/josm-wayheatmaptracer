package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.Objects;
import java.util.function.Supplier;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;

/** Combines locked network closure validation with factual visible-render freshness. */
public final class VisibleSourceLockedApplyValidator implements LockedApplyValidator {
    private final LiveNetworkSnapshotValidator network;
    private final LiveBPreviewService previewService;
    private final LiveBPreviewService.Captured captured;
    private final Supplier<LiveBPreviewService.VisibleRaster> repeatCapture;

    /** Binds one immutable plan, its network receipt, and a same-frame visible repeat capture. */
    public VisibleSourceLockedApplyValidator(LiveNetworkSnapshotValidator network,
            LiveBPreviewService previewService, LiveBPreviewService.Captured captured,
            Supplier<LiveBPreviewService.VisibleRaster> repeatCapture) {
        this.network = Objects.requireNonNull(network, "network");
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.captured = Objects.requireNonNull(captured, "captured");
        this.repeatCapture = Objects.requireNonNull(repeatCapture, "repeatCapture");
        if (captured.managedRaster() != null) {
            throw new IllegalArgumentException("Visible source validator requires a visible capture");
        }
    }

    @Override
    public String datasetIdentity() {
        return network.datasetIdentity();
    }

    @Override
    public void validateLocked(DataSet dataSet, AlignmentEditPlan plan, boolean firstExecution) {
        network.validateLocked(dataSet, plan, false);
        if (firstExecution) {
            previewService.requireCurrent(dataSet, captured,
                Objects.requireNonNull(repeatCapture.get(), "repeat visible raster"));
        }
    }
}
