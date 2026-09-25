package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;

/**
 * Applies one immutable v0.22 alignment edit plan as an atomic JOSM command.
 *
 * <p>The command owns every plan-local node for its complete lifetime. Apply and redo therefore
 * reuse the same JOSM objects and IDs. Existing primitives are compared with the detached before
 * snapshot immediately before every execution. Any normal runtime failure after JOSM's snapshot
 * hook restores the complete before-state before the exception escapes.</p>
 */
public final class ApplyAlignmentEditPlanCommand extends Command {
    private final AlignmentEditPlan plan;
    private final String liveDatasetIdentity;
    private final LongSupplier liveSourceGeneration;
    private final String description;
    private final MutationProbe mutationProbe;
    private final LockedApplyValidator lockedValidator;
    private final String canonicalPlanHash;
    private Map<PrimitiveKey, Node> createdNodes = Map.of();
    private final List<PrimitiveKey> writeKeys;
    private final List<PrimitiveKey> createdKeys;
    private final List<PrimitiveKey> removedKeys;
    private Map<PrimitiveKey, List<Node>> preparedWayNodes = Map.of();
    private Map<PrimitiveKey, List<RelationMember>> preparedRelationMembers = Map.of();
    private final Map<PrimitiveKey, OsmPrimitive> existingPrimitives = new LinkedHashMap<>();
    private final Map<OsmPrimitive, PrimitiveKey> liveKeys = new IdentityHashMap<>();

    private boolean applied;
    private boolean appliedSuccessfullyBefore;

    /**
     * Creates a side-effect-free command for a detached or unit-test edit plan.
     *
     * <p>This compatibility boundary checks only the materialized plan payload and the supplied
     * generation. Live Apply callers must use the full-closure constructor so spatial entrants are
     * recaptured under the dataset write lock.</p>
     *
     * @param dataSet live dataset that supplied the detached snapshot
     * @param plan complete immutable before/after edit plan
     * @param liveDatasetIdentity identity of {@code dataSet} in the snapshot owner
     * @param liveSourceGeneration current managed-source generation supplier
     * @param description Undo/Redo menu description
     */
    public ApplyAlignmentEditPlanCommand(DataSet dataSet, AlignmentEditPlan plan,
        String liveDatasetIdentity, LongSupplier liveSourceGeneration, String description) {
        this(dataSet, plan, liveDatasetIdentity, liveSourceGeneration, description,
            point -> { }, null);
    }

    /**
     * Creates a command whose factual full closure is repeated at the locked Apply boundary.
     */
    public ApplyAlignmentEditPlanCommand(DataSet dataSet, AlignmentEditPlan plan,
            LiveNetworkSnapshotValidator validator, String description) {
        this(dataSet, plan, Objects.requireNonNull(validator, "validator").datasetIdentity(),
            validator::currentSourceGeneration, description, point -> { }, validator);
    }

    /**
     * Creates a command whose complete factual Apply preflight runs under the dataset write lock.
     */
    public ApplyAlignmentEditPlanCommand(DataSet dataSet, AlignmentEditPlan plan,
            LockedApplyValidator validator, String description) {
        this(dataSet, plan, Objects.requireNonNull(validator, "validator").datasetIdentity(),
            () -> plan.before().sourceGeneration(), description, point -> { }, validator);
    }

    ApplyAlignmentEditPlanCommand(DataSet dataSet, AlignmentEditPlan plan,
        String liveDatasetIdentity, LongSupplier liveSourceGeneration, String description,
        MutationProbe mutationProbe) {
        this(dataSet, plan, liveDatasetIdentity, liveSourceGeneration, description,
            mutationProbe, null);
    }

    ApplyAlignmentEditPlanCommand(DataSet dataSet, AlignmentEditPlan plan,
            LiveNetworkSnapshotValidator validator, String description,
            MutationProbe mutationProbe) {
        this(dataSet, plan, Objects.requireNonNull(validator, "validator").datasetIdentity(),
            validator::currentSourceGeneration, description, mutationProbe, validator);
    }

    private ApplyAlignmentEditPlanCommand(DataSet dataSet, AlignmentEditPlan plan,
        String liveDatasetIdentity, LongSupplier liveSourceGeneration, String description,
        MutationProbe mutationProbe, LockedApplyValidator lockedValidator) {
        super(Objects.requireNonNull(dataSet, "dataSet"));
        this.plan = Objects.requireNonNull(plan, "plan");
        this.liveDatasetIdentity = requireText(liveDatasetIdentity, "liveDatasetIdentity");
        this.liveSourceGeneration = Objects.requireNonNull(liveSourceGeneration, "liveSourceGeneration");
        this.description = requireText(description, "description");
        this.mutationProbe = Objects.requireNonNull(mutationProbe, "mutationProbe");
        this.lockedValidator = lockedValidator;
        if (plan.validation().disposition() == ValidationReport.Disposition.HARD_BLOCKED) {
            throw new IllegalArgumentException("A structurally blocked alignment plan cannot be applied");
        }
        if (plan.writePrimitiveKeys().stream().anyMatch(key -> {
            DetachedPrimitive after = plan.after().primitives().get(key);
            return after != null && !after.modified();
        })) {
            throw new IllegalArgumentException("Every retained or created changed primitive must be modified");
        }
        canonicalPlanHash = plan.canonicalHash();
        writeKeys = sorted(plan.writePrimitiveKeys());
        createdKeys = sorted(plan.createdPrimitives().keySet());
        removedKeys = sorted(plan.removedPrimitives().keySet());
    }

    /**
     * Revalidates and applies the complete edit on the EDT in one dataset update.
     *
     * @return {@code true} after the exact proposed after-state was established
     * @throws IllegalStateException if called off the EDT, state is stale, or mutation fails
     */
    @Override
    public boolean executeCommand() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Alignment Apply must execute on the EDT");
        }
        if (applied) {
            throw new IllegalStateException("Alignment edit plan is already applied");
        }
        Runnable sourceReceipt = () -> { };
        if (lockedValidator != null) {
            try {
                sourceReceipt = Objects.requireNonNull(lockedValidator.prepareExecution(
                    getAffectedDataSet(), appliedSuccessfullyBefore),
                    "prepared source receipt");
            } catch (RuntimeException failure) {
                reportRejectedRedo(failure);
                throw failure;
            }
        }
        Runnable preparedSourceReceipt = sourceReceipt;
        Runnable transaction = () -> {
            getAffectedDataSet().update(() -> {
                if (lockedValidator != null) {
                    try {
                        preparedSourceReceipt.run();
                        lockedValidator.validateLocked(getAffectedDataSet(), plan, true);
                        preparedSourceReceipt.run();
                    } catch (RuntimeException failure) {
                        reportRejectedRedo(failure);
                        throw failure;
                    }
                } else if (liveSourceGeneration.getAsLong() != plan.before().sourceGeneration()) {
                    throw new IllegalStateException("Alignment source generation changed before Apply or Redo");
                }
                if (createdNodes.isEmpty() && !createdKeys.isEmpty()) {
                    createdNodes = allocatePlanLocalNodes(plan);
                }
                prepareAndValidateBeforeState();
                ApplyAlignmentEditPlanCommand.super.executeCommand();
                try {
                    applyMutationPhases();
                    validateAfterState();
                    preparedSourceReceipt.run();
                } catch (RuntimeException failure) {
                    reportRejectedRedo(failure);
                    rollbackFailedExecution(failure);
                }
            });
            applied = true;
            appliedSuccessfullyBefore = true;
        };
        if (lockedValidator == null) {
            transaction.run();
        } else {
            lockedValidator.executeWithPreparedSource(transaction);
        }
        return true;
    }

    private void reportRejectedRedo(RuntimeException failure) {
        if (appliedSuccessfullyBefore && lockedValidator != null) {
            try {
                lockedValidator.reportRejectedRedo(failure);
            } catch (RuntimeException reportingFailure) {
                failure.addSuppressed(reportingFailure);
            }
        }
    }

    /** Restores exact pre-Apply primitive state and removes command-owned plan-local nodes. */
    @Override
    public void undoCommand() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Alignment Undo must execute on the EDT");
        }
        if (!applied) {
            throw new IllegalStateException("Alignment edit plan is not currently applied");
        }
        getAffectedDataSet().update(this::restoreBeforeState);
        applied = false;
    }

    /** Registers every changed existing object and every command-owned created/removed node. */
    @Override
    public void fillModifiedData(Collection<OsmPrimitive> modified,
        Collection<OsmPrimitive> deleted, Collection<OsmPrimitive> added) {
        for (PrimitiveKey key : writeKeys) {
            boolean existedBefore = plan.before().primitives().containsKey(key);
            boolean existsAfter = plan.after().primitives().containsKey(key);
            if (!existedBefore) {
                added.add(requireCreatedNode(key));
            } else if (!existsAfter) {
                deleted.add(requireExistingPrimitive(key));
            } else {
                modified.add(requireExistingPrimitive(key));
            }
        }
    }

    /** Returns the complete captured closure plus command-owned new nodes. */
    @Override
    public Collection<? extends OsmPrimitive> getParticipatingPrimitives() {
        List<OsmPrimitive> result = new ArrayList<>(existingPrimitives.values());
        result.addAll(createdNodes.values());
        return List.copyOf(result);
    }

    /** Returns the text displayed in JOSM's Undo/Redo history. */
    @Override
    public String getDescriptionText() {
        return description;
    }

    private void prepareAndValidateBeforeState() {
        if (!canonicalPlanHash.equals(plan.canonicalHash())) {
            throw new IllegalStateException("Alignment edit plan identity changed");
        }
        if (!liveDatasetIdentity.equals(plan.before().datasetIdentity())) {
            throw new IllegalStateException("Alignment edit plan belongs to another dataset");
        }
        existingPrimitives.clear();
        liveKeys.clear();
        for (PrimitiveKey key : sorted(plan.before().primitives().keySet())) {
            OsmPrimitive primitive = getAffectedDataSet().getPrimitiveById(key.id(), osmType(key));
            if (primitive == null || primitive.getDataSet() != getAffectedDataSet()) {
                throw new IllegalStateException("Captured primitive is missing from the target dataset: " + key);
            }
            existingPrimitives.put(key, primitive);
            liveKeys.put(primitive, key);
        }
        createdNodes.forEach((key, value) -> liveKeys.put(value, key));
        prepareAfterStructures();
        validateLiveSnapshot(plan.before().primitives(), true);
    }

    private void prepareAfterStructures() {
        Map<PrimitiveKey, List<Node>> ways = new LinkedHashMap<>();
        Map<PrimitiveKey, List<RelationMember>> relations = new LinkedHashMap<>();
        for (PrimitiveKey key : writeKeys) {
            DetachedPrimitive oldValue = plan.before().primitives().get(key);
            DetachedPrimitive newValue = plan.after().primitives().get(key);
            if (oldValue instanceof DetachedWay oldWay && newValue instanceof DetachedWay newWay
                && !oldWay.nodeKeys().equals(newWay.nodeKeys())) {
                ways.put(key, newWay.nodeKeys().stream().map(this::requireLiveNode).toList());
            } else if (oldValue instanceof DetachedRelation oldRelation
                && newValue instanceof DetachedRelation newRelation
                && !oldRelation.members().equals(newRelation.members())) {
                relations.put(key, newRelation.members().stream().map(this::toLiveMember).toList());
            }
        }
        preparedWayNodes = Map.copyOf(ways);
        preparedRelationMembers = Map.copyOf(relations);
    }

    private void applyMutationPhases() {
        checkpoint(MutationPoint.BEFORE_CREATE_NODES);
        for (PrimitiveKey key : createdKeys) {
            Node node = createdNodes.get(key);
            if (node.getDataSet() != null) {
                throw new IllegalStateException("Plan-local node is unexpectedly attached before Apply");
            }
            getAffectedDataSet().addPrimitive(node);
        }
        checkpoint(MutationPoint.AFTER_CREATE_NODES);

        checkpoint(MutationPoint.BEFORE_MOVE_NODES);
        for (PrimitiveKey key : writeKeys) {
            DetachedPrimitive oldValue = plan.before().primitives().get(key);
            DetachedPrimitive newValue = plan.after().primitives().get(key);
            if (oldValue instanceof DetachedNode oldNode && newValue instanceof DetachedNode newNode
                && !oldNode.coordinate().equals(newNode.coordinate())) {
                ((Node) requireExistingPrimitive(key)).setCoor(toLatLon(newNode.coordinate()));
            }
        }
        checkpoint(MutationPoint.AFTER_MOVE_NODES);

        checkpoint(MutationPoint.BEFORE_REPLACE_WAYS);
        preparedWayNodes.forEach((key, nodes) -> ((Way) requireExistingPrimitive(key)).setNodes(nodes));
        checkpoint(MutationPoint.AFTER_REPLACE_WAYS);

        checkpoint(MutationPoint.BEFORE_CHANGE_RELATIONS);
        preparedRelationMembers.forEach((key, members) ->
            ((Relation) requireExistingPrimitive(key)).setMembers(members));
        checkpoint(MutationPoint.AFTER_CHANGE_RELATIONS);

        checkpoint(MutationPoint.BEFORE_DELETE_NODES);
        for (PrimitiveKey key : removedKeys) {
            OsmPrimitive primitive = requireExistingPrimitive(key);
            if (!(primitive instanceof Node node) || !node.getReferrers().isEmpty()) {
                throw new IllegalStateException("Planned node deletion still has live referrers: " + key);
            }
            if (node.isNew()) {
                getAffectedDataSet().removePrimitive(node);
            } else {
                node.setModified(true);
                node.setDeleted(true);
            }
        }
        applyFinalModifiedFlags();
        checkpoint(MutationPoint.AFTER_DELETE_NODES);
    }

    private void applyFinalModifiedFlags() {
        for (PrimitiveKey key : writeKeys) {
            DetachedPrimitive detached = plan.after().primitives().get(key);
            if (detached != null) {
                requireLivePrimitive(key).setModified(detached.modified());
            }
        }
    }

    private void rollbackFailedExecution(RuntimeException originalFailure) {
        try {
            restoreBeforeState();
        } catch (RuntimeException rollbackFailure) {
            IllegalStateException failure = new IllegalStateException(
                "Alignment edit failed and rollback was incomplete", originalFailure);
            failure.addSuppressed(rollbackFailure);
            throw failure;
        }
        throw new IllegalStateException("Alignment edit failed; all mutations were rolled back", originalFailure);
    }

    private void restoreBeforeState() {
        for (PrimitiveKey key : removedKeys) {
            OsmPrimitive primitive = requireExistingPrimitive(key);
            if (primitive.getDataSet() == null) {
                getAffectedDataSet().addPrimitive(primitive);
            }
        }
        ApplyAlignmentEditPlanCommand.super.undoCommand();
        for (PrimitiveKey key : createdKeys) {
            Node node = createdNodes.get(key);
            if (node.getDataSet() != null) {
                if (!node.getReferrers().isEmpty()) {
                    throw new IllegalStateException("Rollback left a plan-local node referenced: " + key);
                }
                getAffectedDataSet().removePrimitive(node);
            }
        }
        validateLiveSnapshot(plan.before().primitives(), true);
    }

    private void validateAfterState() {
        for (PrimitiveKey key : plan.removedPrimitives().keySet()) {
            OsmPrimitive primitive = requireExistingPrimitive(key);
            OsmPrimitive indexed = getAffectedDataSet().getPrimitiveById(key.id(), osmType(key));
            if (primitive.isNew()) {
                if (primitive.getDataSet() != null || indexed != null) {
                    throw new IllegalStateException("Transient removed primitive remains in the dataset: " + key);
                }
            } else if (primitive.getDataSet() != getAffectedDataSet() || indexed != primitive
                || !primitive.isDeleted() || !primitive.isModified()
                || !primitive.getReferrers().isEmpty()) {
                throw new IllegalStateException(
                    "Uploaded removed primitive is not a deletion tombstone: " + key);
            }
        }
        validateLiveSnapshot(plan.after().primitives(), false);
    }

    private void validateLiveSnapshot(Map<PrimitiveKey, DetachedPrimitive> expected, boolean beforeState) {
        for (Map.Entry<PrimitiveKey, DetachedPrimitive> entry : expected.entrySet()) {
            OsmPrimitive live = requireLivePrimitive(entry.getKey());
            if (live.getDataSet() != getAffectedDataSet() || !matches(live, entry.getValue())) {
                throw new IllegalStateException((beforeState ? "Stale before-state: " : "Wrong after-state: ")
                    + entry.getKey());
            }
        }
        Map<PrimitiveKey, Set<PrimitiveKey>> expectedReferrers = beforeState
            ? plan.before().incomingReferrerWatches() : plan.after().incomingReferrerWatches();
        for (PrimitiveKey key : expected.keySet()) {
            Set<PrimitiveKey> actual = new LinkedHashSet<>();
            requireLivePrimitive(key).getReferrers().forEach(referrer -> actual.add(keyForLive(referrer)));
            if (!actual.equals(expectedReferrers.getOrDefault(key, Set.of()))) {
                throw new IllegalStateException((beforeState ? "Stale referrer closure: "
                    : "Wrong after-state referrers: ") + key);
            }
        }
    }

    private boolean matches(OsmPrimitive live, DetachedPrimitive expected) {
        if (live.isDeleted() != expected.deleted() || live.isModified() != expected.modified()
            || !Map.copyOf(live.getKeys()).equals(expected.tags())) {
            return false;
        }
        if (live instanceof Node node && expected instanceof DetachedNode detached) {
            return sameCoordinate(node, detached.coordinate());
        }
        if (live instanceof Way way && expected instanceof DetachedWay detached) {
            return way.getNodes().stream().map(this::keyForLive).toList().equals(detached.nodeKeys());
        }
        if (live instanceof Relation relation && expected instanceof DetachedRelation detached) {
            List<DetachedRelationMember> members = relation.getMembers().stream()
                .map(member -> new DetachedRelationMember(keyForLive(member.getMember()), member.getRole())).toList();
            return members.equals(detached.members());
        }
        return false;
    }

    private boolean sameCoordinate(Node node, GeographicPoint point) {
        return Double.doubleToLongBits(node.lat()) == Double.doubleToLongBits(point.latitudeDegrees())
            && Double.doubleToLongBits(node.lon()) == Double.doubleToLongBits(point.longitudeDegrees());
    }

    private RelationMember toLiveMember(DetachedRelationMember member) {
        return new RelationMember(member.role(), requireLivePrimitive(member.memberKey()));
    }

    private Node requireLiveNode(PrimitiveKey key) {
        OsmPrimitive primitive = requireLivePrimitive(key);
        if (!(primitive instanceof Node node)) {
            throw new IllegalStateException("Expected live node: " + key);
        }
        return node;
    }

    private OsmPrimitive requireLivePrimitive(PrimitiveKey key) {
        OsmPrimitive primitive = key.identityKind() == PrimitiveKey.IdentityKind.PLAN_LOCAL
            ? createdNodes.get(key) : existingPrimitives.get(key);
        if (primitive == null) {
            throw new IllegalStateException("Plan primitive has no live object: " + key);
        }
        return primitive;
    }

    private OsmPrimitive requireExistingPrimitive(PrimitiveKey key) {
        OsmPrimitive primitive = existingPrimitives.get(key);
        if (primitive == null) {
            throw new IllegalStateException("Existing plan primitive is not prepared: " + key);
        }
        return primitive;
    }

    private Node requireCreatedNode(PrimitiveKey key) {
        Node node = createdNodes.get(key);
        if (node == null) {
            throw new IllegalStateException("Created plan node is not allocated: " + key);
        }
        return node;
    }

    private PrimitiveKey keyForLive(OsmPrimitive primitive) {
        PrimitiveKey known = liveKeys.get(primitive);
        if (known != null) {
            return known;
        }
        return PrimitiveKey.existing(typeOf(primitive), primitive.getUniqueId());
    }

    private void checkpoint(MutationPoint point) {
        mutationProbe.reached(point);
    }

    private static Map<PrimitiveKey, Node> allocatePlanLocalNodes(AlignmentEditPlan plan) {
        Map<PrimitiveKey, Node> result = new LinkedHashMap<>();
        plan.createdPrimitives().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!(entry.getValue() instanceof DetachedNode detached)) {
                throw new IllegalArgumentException("Only plan-local nodes may be allocated");
            }
            Node node = new Node(toLatLon(detached.coordinate()));
            node.setKeys(detached.tags());
            node.setModified(detached.modified());
            result.put(entry.getKey(), node);
        });
        return Map.copyOf(result);
    }

    private static LatLon toLatLon(GeographicPoint point) {
        return new LatLon(point.latitudeDegrees(), point.longitudeDegrees());
    }

    private static OsmPrimitiveType osmType(PrimitiveKey key) {
        return switch (key.type()) {
            case NODE -> OsmPrimitiveType.NODE;
            case WAY -> OsmPrimitiveType.WAY;
            case RELATION -> OsmPrimitiveType.RELATION;
        };
    }

    private static PrimitiveKey.Type typeOf(OsmPrimitive primitive) {
        if (primitive instanceof Node) {
            return PrimitiveKey.Type.NODE;
        }
        if (primitive instanceof Way) {
            return PrimitiveKey.Type.WAY;
        }
        if (primitive instanceof Relation) {
            return PrimitiveKey.Type.RELATION;
        }
        throw new IllegalStateException("Unsupported JOSM primitive type: " + primitive.getClass().getName());
    }

    private static List<PrimitiveKey> sorted(Collection<PrimitiveKey> keys) {
        return keys.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    enum MutationPoint {
        BEFORE_CREATE_NODES,
        AFTER_CREATE_NODES,
        BEFORE_MOVE_NODES,
        AFTER_MOVE_NODES,
        BEFORE_REPLACE_WAYS,
        AFTER_REPLACE_WAYS,
        BEFORE_CHANGE_RELATIONS,
        AFTER_CHANGE_RELATIONS,
        BEFORE_DELETE_NODES,
        AFTER_DELETE_NODES
    }

    @FunctionalInterface
    interface MutationProbe {
        void reached(MutationPoint point);
    }
}
