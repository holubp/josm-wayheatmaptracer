package org.openstreetmap.josm.plugins.wayheatmaptracer;

import java.util.HashSet;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;

/** Maintainer-approved cross-version exceptions; native identities are never rewritten. */
public enum BenchmarkComparisonContract {
    STRICT("rc6-identical-v1"),
    RC10_APPROVED("rc10-approved-numerical-readonly-v1");

    // Exact reviewed live B parameter identities. A future policy needs a new approved contract.
    public static final String RC6_B_PARAMETERS = "df52ac49e7aae10c1b5953155ef4ba3cc9430c06b1b42ac8a9afe24f3235e825";
    public static final String RC10_B_PARAMETERS = "9814307c79fae2c8ec105c755c7373694d3c4e893be78a747062511361241b35";
    private final String identity;
    BenchmarkComparisonContract(String identity) { this.identity = identity; }
    public String identity() { return identity; }
    public static BenchmarkComparisonContract parse(String identity) {
        for (var value : values()) if (value.identity.equals(identity)) return value;
        throw new IllegalArgumentException("Unknown benchmark comparison contract");
    }

    public static Set<PrimitiveKey> frozenKeys(SelectedWayIntervalPartitioner.Partition partition) {
        if (partition == null) return Set.of();
        Set<PrimitiveKey> result = new HashSet<>();
        partition.fixedIslands().forEach(island -> result.addAll(island.occurrenceKeys()));
        return Set.copyOf(result);
    }

    public static void requireApprovedParameters(String baseline, String candidate) {
        if ((!RC6_B_PARAMETERS.equals(baseline) && !RC10_B_PARAMETERS.equals(baseline))
                || !RC10_B_PARAMETERS.equals(candidate)) {
            throw new IllegalStateException("Unapproved B numerical parameter identity");
        }
    }

    /** Compares factual shared payloads/watches and permits only read-only added context. */
    public static void requireReadOnlyExtension(NetworkSnapshot baseline, NetworkSnapshot candidate,
            Set<PrimitiveKey> fixedIslandKeys) {
        var a = baseline.closure(); var b = candidate.closure();
        if (!baseline.snapshotId().equals(candidate.snapshotId()) || baseline.role() != candidate.role()
                || baseline.sourceGeneration() != candidate.sourceGeneration()
                || a.scope() != b.scope() || !a.queryVersion().equals(b.queryVersion())
                || a.mayCreateNodes() != b.mayCreateNodes()
                || a.wayReferrersComplete() != b.wayReferrersComplete()
                || a.relationReferrersComplete() != b.relationReferrersComplete()
                || a.nearbyGeometryComplete() != b.nearbyGeometryComplete()
                || !a.collisionEnvelope().equals(b.collisionEnvelope())
                || !a.editRegion().equals(b.editRegion())
                || !a.editableWayOccurrences().equals(b.editableWayOccurrences())
                || !a.externalPorts().equals(b.externalPorts())
                || !b.primitiveKeys().containsAll(a.primitiveKeys())
                || !a.editableExistingKeys().containsAll(b.editableExistingKeys())
                || !a.movableExistingNodeKeys().containsAll(b.movableExistingNodeKeys())
                || !a.removableExistingNodeKeys().equals(b.removableExistingNodeKeys())
                || !b.protectedExistingNodeKeys().containsAll(a.protectedExistingNodeKeys())) {
            throw new IllegalStateException("Unapproved network authority or capture difference");
        }
        for (var key : a.primitiveKeys()) {
            if (!baseline.primitives().get(key).equals(candidate.primitives().get(key))
                    || !baseline.incomingReferrerWatches().get(key)
                            .equals(candidate.incomingReferrerWatches().get(key))) {
                throw new IllegalStateException("Shared original OSM payload or referrer watch differs");
            }
        }
        Set<PrimitiveKey> removedRights = new HashSet<>(a.editableExistingKeys());
        removedRights.removeAll(b.editableExistingKeys());
        Set<PrimitiveKey> removedMovement = new HashSet<>(a.movableExistingNodeKeys());
        removedMovement.removeAll(b.movableExistingNodeKeys()); removedRights.addAll(removedMovement);
        if (!fixedIslandKeys.containsAll(removedRights)
                || !b.protectedExistingNodeKeys().containsAll(removedRights)) {
            throw new IllegalStateException("Removed movement rights are not proved fixed islands");
        }
        Set<PrimitiveKey> addedProtection = new HashSet<>(b.protectedExistingNodeKeys());
        addedProtection.removeAll(a.protectedExistingNodeKeys());
        addedProtection.retainAll(a.primitiveKeys());
        if (!fixedIslandKeys.containsAll(addedProtection)) {
            throw new IllegalStateException("Unapproved protection change outside fixed islands");
        }
        for (var key : b.primitiveKeys()) if (!a.primitiveKeys().contains(key)) {
            if (key.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE
                    || b.editableExistingKeys().contains(key) || b.movableExistingNodeKeys().contains(key)
                    || b.removableExistingNodeKeys().contains(key)
                    || key.type() == PrimitiveKey.Type.NODE && !b.protectedExistingNodeKeys().contains(key)) {
                throw new IllegalStateException("Added context is not read-only");
            }
        }
    }

    public static void requireInputs(FrozenReplayInput baseline, FrozenReplayInput candidate,
            Set<PrimitiveKey> fixedIslandKeys) {
        requireReadOnlyExtension(baseline.network(), candidate.network(), fixedIslandKeys);
        BenchmarkDecisionComparatorMain.requireSameEvidence(baseline, candidate);
        requireRequests(baseline.request(), candidate.request());
    }

    public static void requireRequests(TraceRequest a, TraceRequest b) {
        requireApprovedParameters(a.parameterHash(), b.parameterHash());
        if (a.engine() != TrackerMode.PROBABILISTIC || b.engine() != TrackerMode.PROBABILISTIC
                || !a.selectedWayKey().equals(b.selectedWayKey()) || !a.selectedRange().equals(b.selectedRange())
                || a.geometryMode() != b.geometryMode() || !a.permissions().equals(b.permissions())
                || !a.budgets().equals(b.budgets()) || !a.settingsHash().equals(b.settingsHash())
                || !a.evidenceSnapshotId().equals(b.evidenceSnapshotId())
                || !a.evidenceContentHash().equals(b.evidenceContentHash())
                || !a.networkSnapshotId().equals(b.networkSnapshotId())
                || !a.samplerId().equals(b.samplerId())
                || Double.doubleToRawLongBits(a.configuredSampleStepMeters())
                    != Double.doubleToRawLongBits(b.configuredSampleStepMeters())
                || !a.profileChainage().equals(b.profileChainage())
                || !a.evidenceResolution().equals(b.evidenceResolution())
                || !a.corridorInput().equals(b.corridorInput())) {
            throw new IllegalStateException("Unapproved frozen request difference");
        }
    }

    /** Additional protection must survive in the actual immutable proposed output, bit for bit. */
    public static void requireFrozenPreserved(AlignmentEditPlan plan, Set<PrimitiveKey> frozen) {
        for (var key : frozen) if (!plan.before().primitives().get(key).equals(plan.after().primitives().get(key))) {
            throw new IllegalStateException("Frozen island primitive differs in proposed output");
        }
    }
}
