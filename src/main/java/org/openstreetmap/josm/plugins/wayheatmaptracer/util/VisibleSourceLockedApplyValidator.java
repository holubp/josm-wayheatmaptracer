package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.Consumer;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;

/** Combines locked network closure validation with factual visible-render freshness. */
public final class VisibleSourceLockedApplyValidator implements LockedApplyValidator {
    private final LiveNetworkSnapshotValidator network;
    private final LiveBPreviewService previewService;
    private final LiveBPreviewService.Captured captured;
    private final Supplier<LiveBPreviewService.VisibleRaster> repeatCapture;
    private final VisibleSourceEpoch sourceEpoch;
    private final VisibleSourceEpoch.Receipt capturedSourceReceipt;
    private final Runnable requireSourceOwnerCurrent;
    private final Consumer<String> redoFailureReporter;

    /** Binds one immutable plan, its network receipt, and a same-frame visible repeat capture. */
    public VisibleSourceLockedApplyValidator(LiveNetworkSnapshotValidator network,
            LiveBPreviewService previewService, LiveBPreviewService.Captured captured,
            Supplier<LiveBPreviewService.VisibleRaster> repeatCapture,
            VisibleSourceEpoch sourceEpoch,
            Runnable requireSourceOwnerCurrent, Consumer<String> redoFailureReporter) {
        this.network = Objects.requireNonNull(network, "network");
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.captured = Objects.requireNonNull(captured, "captured");
        this.repeatCapture = Objects.requireNonNull(repeatCapture, "repeatCapture");
        this.sourceEpoch = sourceEpoch;
        this.requireSourceOwnerCurrent = Objects.requireNonNull(requireSourceOwnerCurrent,
                "requireSourceOwnerCurrent");
        this.redoFailureReporter = Objects.requireNonNull(redoFailureReporter,
                "redoFailureReporter");
        if (captured.managedRaster() != null) {
            throw new IllegalArgumentException("Visible source validator requires a visible capture");
        }
        capturedSourceReceipt = captured.raster().sourceReceipt();
        if (sourceEpoch != null && (capturedSourceReceipt == null
                || capturedSourceReceipt.owner() != sourceEpoch)) {
            throw new IllegalArgumentException("Visible capture lacks its source revision receipt");
        }
    }

    @Override
    public String datasetIdentity() {
        return network.datasetIdentity();
    }

    @Override
    public Runnable prepareExecution(DataSet dataSet, boolean redo) {
        if (sourceEpoch == null && redo) {
            throw new VisibleSourceRevisionUnavailableException();
        }
        if (sourceEpoch != null) sourceEpoch.requireCurrent(capturedSourceReceipt);
        requireSourceOwnerCurrent.run();
        VisibleSourceEpoch.Receipt before = sourceEpoch == null ? null : sourceEpoch.captureStable();
        try {
            previewService.requireCurrent(dataSet, captured,
                Objects.requireNonNull(repeatCapture.get(), "repeat visible raster"));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("The captured visible source changed; run a new alignment.");
        }
        if (sourceEpoch == null) {
            return () -> { };
        }
        sourceEpoch.requireCurrent(before);
        sourceEpoch.requireCurrent(capturedSourceReceipt);
        VisibleSourceEpoch.Receipt after = sourceEpoch.captureStable();
        return () -> {
            sourceEpoch.requireCurrent(capturedSourceReceipt);
            sourceEpoch.requireCurrent(after);
        };
    }

    @Override
    public void executeWithPreparedSource(Runnable transaction) {
        if (sourceEpoch == null) {
            transaction.run();
        } else {
            synchronized (sourceEpoch) {
                transaction.run();
            }
        }
    }

    @Override
    public void validateLocked(DataSet dataSet, AlignmentEditPlan plan, boolean firstExecution) {
        network.validateLocked(dataSet, plan, false);
        requireSourceOwnerCurrent.run();
    }

    @Override public void reportRejectedRedo(RuntimeException failure) {
        redoFailureReporter.accept(failure instanceof VisibleSourceRevisionUnavailableException
            ? "This visible source cannot verify unchanged tiles for Redo; run a new alignment."
            : "The captured source or network changed; recompute alignment before applying.");
    }
}
