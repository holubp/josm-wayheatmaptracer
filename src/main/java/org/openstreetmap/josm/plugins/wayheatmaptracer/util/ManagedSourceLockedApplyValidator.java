package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.Objects;
import java.util.function.Consumer;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;

/** Combines locked network validation with the immutable managed-source capture identity. */
public final class ManagedSourceLockedApplyValidator implements LockedApplyValidator {
    private final LiveNetworkSnapshotValidator network;
    private final LiveBPreviewService previewService;
    private final LiveBPreviewService.Captured captured;
    private final Runnable requireSourceOwnerCurrent;
    private final Consumer<String> redoFailureReporter;

    /** Binds an exact managed capture to one immutable edit plan. */
    public ManagedSourceLockedApplyValidator(LiveNetworkSnapshotValidator network,
            LiveBPreviewService previewService, LiveBPreviewService.Captured captured,
            Runnable requireSourceOwnerCurrent, Consumer<String> redoFailureReporter) {
        this.network = Objects.requireNonNull(network, "network");
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.captured = Objects.requireNonNull(captured, "captured");
        this.requireSourceOwnerCurrent = Objects.requireNonNull(requireSourceOwnerCurrent,
                "requireSourceOwnerCurrent");
        this.redoFailureReporter = Objects.requireNonNull(redoFailureReporter,
                "redoFailureReporter");
        if (captured.managedRaster() == null) {
            throw new IllegalArgumentException("Managed source validator requires a managed capture");
        }
    }

    @Override public String datasetIdentity() { return network.datasetIdentity(); }

    @Override public boolean returnsNormallyAfterCompletedTransaction() { return true; }

    @Override public Runnable prepareExecution(DataSet dataSet, boolean redo) {
        return requireSourceOwnerCurrent;
    }

    @Override public void validateLocked(DataSet dataSet, AlignmentEditPlan plan, boolean firstExecution) {
        network.validateLocked(dataSet, plan, true);
        requireSourceOwnerCurrent.run();
        previewService.requireCurrent(dataSet, captured);
    }

    @Override public void reportRejectedRedo(RuntimeException failure) {
        redoFailureReporter.accept("The captured source or network changed; recompute alignment before applying.");
    }
}
