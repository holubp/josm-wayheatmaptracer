package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import java.util.Objects;
import java.util.function.LongSupplier;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.LockedApplyValidator;

/**
 * Repeats one factual bounded network capture at the final command boundary.
 *
 * <p>The caller must invoke {@link #validateLocked(DataSet, AlignmentEditPlan, boolean)} while
 * holding the dataset write lock and before command snapshotting, allocation, or mutation.</p>
 */
public final class LiveNetworkSnapshotValidator implements LockedApplyValidator {
    private final NetworkSnapshotCapture.CapturedSnapshot captured;
    private final AlignmentEditPlan expectedPlan;
    private final LongSupplier liveSourceGeneration;

    /**
     * Binds an actual capture receipt, the exact reviewed plan, and a factual generation owner.
     */
    public LiveNetworkSnapshotValidator(NetworkSnapshotCapture.CapturedSnapshot captured,
            AlignmentEditPlan expectedPlan, LongSupplier liveSourceGeneration) {
        this.captured = Objects.requireNonNull(captured, "captured");
        this.expectedPlan = Objects.requireNonNull(expectedPlan, "expectedPlan");
        this.liveSourceGeneration = Objects.requireNonNull(
            liveSourceGeneration, "liveSourceGeneration");
        requireExactAssociation(captured, expectedPlan);
    }

    /** Returns the exact captured dataset identity. */
    @Override
    public String datasetIdentity() {
        return captured.snapshot().datasetIdentity();
    }

    @Override public boolean returnsNormallyAfterCompletedTransaction() { return true; }

    /** Returns the current factual source generation. */
    public long currentSourceGeneration() {
        return liveSourceGeneration.getAsLong();
    }

    /**
     * Recaptures and compares the complete bounded closure.
     *
     * @throws IllegalStateException when called off the EDT or any factual closure state is stale
     */
    @Override
    public void validateLocked(DataSet dataSet, AlignmentEditPlan plan,
            boolean requireSourceGeneration) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Live closure validation must execute on the EDT");
        }
        NetworkSnapshot expectedBefore = captured.snapshot();
        if (!captured.belongsTo(dataSet) || !expectedPlan.equals(plan)
                || requireSourceGeneration
                    && liveSourceGeneration.getAsLong() != expectedBefore.sourceGeneration()) {
            throw new IllegalStateException(
                "Alignment plan, dataset, or source generation is stale");
        }
        NetworkSnapshot current = NetworkSnapshotCapture.capture(
            dataSet, captured.specification());
        if (!current.canonicalHash().equals(expectedBefore.canonicalHash())
                || !Objects.equals(current.semanticWitness(), expectedBefore.semanticWitness())) {
            throw new IllegalStateException("Live network closure changed before Apply");
        }
    }

    /** Rechecks only immutable OSM semantics; the command checks its exact applied primitives. */
    @Override public void validateUndoLocked(DataSet dataSet, AlignmentEditPlan plan) {
        if (!SwingUtilities.isEventDispatchThread() || !captured.belongsTo(dataSet) || !expectedPlan.equals(plan)) {
            throw new IllegalStateException("Alignment Undo plan or dataset is stale");
        }
        if (plan.before().semanticWitness() != null) {
            NetworkSnapshotCapture.requireSemanticWitnessCurrentForUndo(dataSet, captured.snapshot());
        }
    }

    private static void requireExactAssociation(
            NetworkSnapshotCapture.CapturedSnapshot captured, AlignmentEditPlan plan) {
        NetworkSnapshotCapture.Specification specification = captured.specification();
        NetworkSnapshot before = captured.snapshot();
        ClosureDescriptor closure = plan.before().closure();
        if (!before.equals(plan.before())
                || !specification.snapshotId().equals(before.snapshotId())
                || !specification.datasetIdentity().equals(before.datasetIdentity())
                || specification.sourceGeneration() != before.sourceGeneration()
                || !specification.selectedWayKey().equals(plan.selectedWayKey())
                || !specification.selectedRange().equals(plan.selectedRange())
                || !specification.metricFrame().equals(plan.metricFrame())
                || !specification.permissions().equals(plan.permissions())
                || !specification.collisionEnvelope().equals(closure.collisionEnvelope())
                || !specification.editRegion().equals(closure.editRegion())
                || !specification.editableWayOccurrences().equals(
                    closure.editableWayOccurrences())
                || !specification.editableExistingKeys().equals(
                    closure.editableExistingKeys())
                || !specification.movableExistingNodeKeys().equals(
                    closure.movableExistingNodeKeys())
                || !specification.removableExistingNodeKeys().equals(
                    closure.removableExistingNodeKeys())
                || !closure.protectedExistingNodeKeys().containsAll(
                    specification.explicitlyProtectedNodeKeys())
                || specification.mayCreateNodes() != closure.mayCreateNodes()) {
            throw new IllegalArgumentException(
                "Live closure capture does not match the reviewed alignment plan");
        }
    }
}
