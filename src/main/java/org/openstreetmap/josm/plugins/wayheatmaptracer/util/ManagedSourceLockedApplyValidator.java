package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.Objects;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;

/** Combines locked network validation with the immutable managed-source capture identity. */
public final class ManagedSourceLockedApplyValidator implements LockedApplyValidator {
    private final LiveNetworkSnapshotValidator network;
    private final LiveBPreviewService previewService;
    private final LiveBPreviewService.Captured captured;

    /** Binds an exact managed capture to one immutable edit plan. */
    public ManagedSourceLockedApplyValidator(LiveNetworkSnapshotValidator network,
            LiveBPreviewService previewService, LiveBPreviewService.Captured captured) {
        this.network = Objects.requireNonNull(network, "network");
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.captured = Objects.requireNonNull(captured, "captured");
        if (captured.managedRaster() == null) {
            throw new IllegalArgumentException("Managed source validator requires a managed capture");
        }
    }

    @Override public String datasetIdentity() { return network.datasetIdentity(); }

    @Override public void validateLocked(DataSet dataSet, AlignmentEditPlan plan, boolean firstExecution) {
        network.validateLocked(dataSet, plan, false);
        if (firstExecution) {
            previewService.requireCurrent(dataSet, captured);
        }
    }
}
