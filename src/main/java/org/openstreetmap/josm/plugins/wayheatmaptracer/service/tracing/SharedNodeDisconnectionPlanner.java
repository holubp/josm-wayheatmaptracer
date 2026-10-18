package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SharedNodeDisconnectionEligibility;

/** Source-bound occurrence replacement authority, separate from factual node movement authority. */
public final class SharedNodeDisconnectionPlanner {
    private SharedNodeDisconnectionPlanner() { }

    /** A stale final route or failed attempt cannot supply clone-only authority. */
    static boolean retainedProductionOwner(ModernTracePipeline.Result result, ModernTracePipeline.Route route) {
        return result != null && route != null
                && (result.inference().status() == TraceHypothesisSet.Status.COMPLETE
                        || result.inference().status() == TraceHypothesisSet.Status.AMBIGUOUS)
                && result.routes().contains(route)
                && result.inference().hypotheses().contains(route.rawHypothesis())
                && route.quality().disposition() != FinalGeometryEvaluator.Disposition.HARD_BLOCKED
                && Double.isFinite(route.quality().directlySupportedLengthMeters())
                && route.quality().directlySupportedLengthMeters() > 0.0;
    }

    /** Proof of unchanged geographic geometry at one exact authorized external-port edge. */
    public static final class BoundaryIdentityProof {
        private final PrimitiveKey wayKey;
        private final int segmentIndex;
        private BoundaryIdentityProof(PrimitiveKey wayKey, int segmentIndex) {
            this.wayKey = wayKey;
            this.segmentIndex = segmentIndex;
        }
        public boolean matches(PrimitiveKey way, int segment) {
            return wayKey.equals(way) && segmentIndex == segment;
        }
    }

    /**
     * Recognizes unchanged source geometry using an already validated complete port mapping.
     * Returns null without exact original adjacency, protected coordinates and destination incidence.
     */
    public static BoundaryIdentityProof exactBoundaryIdentityChange(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, PrimitiveKey selectedKey, int segmentIndex,
            Map<ExternalPort, PrimitiveKey> validatedPorts) {
        if (validatedPorts.isEmpty() || before.role() != SnapshotRole.CAPTURED_BEFORE
                || !(before.primitives().get(selectedKey) instanceof DetachedWay source)
                || !(after.get(selectedKey) instanceof DetachedWay selected)) return null;
        for (var entry : validatedPorts.entrySet()) {
            ExternalPort port = entry.getKey();
            if (!port.wayKey().equals(selectedKey) || !before.closure().externalPorts().contains(port)) continue;
            int originalBoundary = port.boundaryOccurrenceIndex();
            int direction = port.side() == ExternalPort.Side.BEFORE ? -1 : 1;
            int originalOutside = originalBoundary + direction;
            int boundary = selected.nodeKeys().indexOf(entry.getValue());
            int outside = boundary + direction;
            if (entry.getValue().identityKind() != PrimitiveKey.IdentityKind.PLAN_LOCAL
                    || !before.closure().protectedExistingNodeKeys().contains(port.boundaryNodeKey())
                    || boundary < 0 || outside < 0 || outside >= selected.nodeKeys().size()
                    || Math.min(boundary, outside) != segmentIndex
                    || originalBoundary >= source.nodeKeys().size() || originalOutside < 0
                    || originalOutside >= source.nodeKeys().size()
                    || !source.nodeKeys().get(originalBoundary).equals(port.boundaryNodeKey())
                    || !source.nodeKeys().get(originalOutside).equals(port.outsideNeighborKey())
                    || !selected.nodeKeys().get(outside).equals(port.outsideNeighborKey())
                    || !java.util.Objects.equals(before.primitives().get(port.boundaryNodeKey()),
                            after.get(port.boundaryNodeKey()))
                    || !java.util.Objects.equals(before.primitives().get(port.outsideNeighborKey()),
                            after.get(port.outsideNeighborKey()))
                    || !(before.primitives().get(port.boundaryNodeKey()) instanceof DetachedNode original)
                    || !(after.get(entry.getValue()) instanceof DetachedNode replacement)
                    || !(after.get(port.outsideNeighborKey()) instanceof DetachedNode outsideNode)
                    || !replacement.coordinate().equals(original.coordinate())
                    || !outsideNode.coordinate().equals(port.outsideNeighborCoordinate())) continue;
            return new BoundaryIdentityProof(selectedKey, segmentIndex);
        }
        return null;
    }

    /** Proves every explicit choice; there is no partial acceptance or automatic choice. */
    public static Map<Integer, PrimitiveKey> replacements(NetworkSnapshot source,
            SharedNodeDisconnectionIntent intent) {
        if (intent == null) throw new IllegalArgumentException("Disconnection intent is required");
        if (intent.isEmpty()) return Map.of();
        var eligibility = SharedNodeDisconnectionEligibility.evaluate(source, intent);
        Map<Integer, PrimitiveKey> result = new LinkedHashMap<>();
        for (var decision : eligibility.decisions().values()) {
            if (!decision.eligible()) {
                throw new IllegalArgumentException("Disconnection is unavailable: " + decision.reason());
            }
            if (!source.closure().protectedExistingNodeKeys().contains(decision.originalNodeKey())) {
                throw new IllegalArgumentException("Original shared node must remain protected");
            }
            result.put(decision.occurrenceIndex(), PrimitiveKey.planned(PrimitiveKey.Type.NODE,
                    Long.MIN_VALUE + decision.occurrenceIndex()));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Verifies the final capture contains the same facts and only reduced original authority. */
    public static void requireSourceParity(NetworkSnapshot source, NetworkSnapshot effective,
            SharedNodeDisconnectionIntent intent) {
        replacements(source, intent);
        requireCaptureFacts(source, effective);
    }

    private static void requireCaptureFacts(NetworkSnapshot source, NetworkSnapshot effective) {
        if (source == null || effective == null || source.role() != SnapshotRole.CAPTURED_BEFORE
                || effective.role() != SnapshotRole.CAPTURED_BEFORE
                || !source.datasetIdentity().equals(effective.datasetIdentity())
                || source.sourceGeneration() != effective.sourceGeneration()
                || !source.primitives().equals(effective.primitives())
                || !source.incomingReferrerWatches().equals(effective.incomingReferrerWatches())
                || source.closure().scope() != effective.closure().scope()
                || !source.closure().queryVersion().equals(effective.closure().queryVersion())
                || source.closure().wayReferrersComplete() != effective.closure().wayReferrersComplete()
                || source.closure().relationReferrersComplete() != effective.closure().relationReferrersComplete()
                || source.closure().nearbyGeometryComplete() != effective.closure().nearbyGeometryComplete()
                || !source.closure().externalPorts().equals(effective.closure().externalPorts())
                || !java.util.Objects.equals(source.semanticWitness() == null ? null : source.semanticWitness().relations(),
                        effective.semanticWitness() == null ? null : effective.semanticWitness().relations())
                || !source.closure().editableExistingKeys().containsAll(effective.closure().editableExistingKeys())
                || !source.closure().movableExistingNodeKeys().containsAll(effective.closure().movableExistingNodeKeys())
                || !source.closure().removableExistingNodeKeys().containsAll(effective.closure().removableExistingNodeKeys())
                || !effective.closure().protectedExistingNodeKeys().containsAll(source.closure().protectedExistingNodeKeys())
                || effective.closure().editableWayOccurrences().entrySet().stream().anyMatch(entry ->
                        entry.getValue().stream().anyMatch(range -> source.closure().editableWayOccurrences()
                                .getOrDefault(entry.getKey(), java.util.List.of()).stream().noneMatch(original ->
                                        original.firstIndex() <= range.firstIndex()
                                                && original.lastIndex() >= range.lastIndex())))
                || !source.closure().editRegion().equals(effective.closure().editRegion())
                || !source.closure().collisionEnvelope().equals(effective.closure().collisionEnvelope())
                || source.closure().mayCreateNodes() != effective.closure().mayCreateNodes()) {
            throw new IllegalArgumentException("Disconnection source and effective capture facts differ");
        }
    }

    /** Validates once at a request boundary; loop lookups never rehash the captured network. */
    public static Authority forRequest(TraceRequest request, NetworkSnapshot effective) {
        if (request == null) throw new IllegalArgumentException("Disconnection request is required");
        var intent = request.permissions().sharedNodeDisconnectionIntent();
        if (intent.isEmpty()) return Authority.NONE;
        Map<Integer, PrimitiveKey> replacements = replacements(request.disconnectionSource(), intent);
        requireCaptureFacts(request.disconnectionSource(), effective);
        if (!intent.selectedWayKey().equals(request.selectedWayKey())
                || request.selectedRange().firstIndex() < intent.selectedRange().firstIndex()
                || request.selectedRange().lastIndex() > intent.selectedRange().lastIndex()) {
            throw new IllegalArgumentException("Disconnection request occurrence authority differs");
        }
        Map<ExternalPort, PrimitiveKey> ports = new LinkedHashMap<>();
        for (var port : effective.closure().externalPorts()) {
            if (port.wayKey().equals(intent.selectedWayKey())
                    && replacements.containsKey(port.boundaryOccurrenceIndex())) {
                ports.put(port, replacements.get(port.boundaryOccurrenceIndex()));
            }
        }
        return new Authority(intent, replacements, Map.copyOf(ports));
    }

    /** Immutable validated decisions; construction is restricted to the source-checking factory. */
    public static final class Authority {
        private static final Authority NONE = new Authority(SharedNodeDisconnectionIntent.none(), Map.of(), Map.of());
        private final SharedNodeDisconnectionIntent intent;
        private final Map<Integer, PrimitiveKey> replacements;
        private final Map<ExternalPort, PrimitiveKey> proposedPortReplacements;

        private Authority(SharedNodeDisconnectionIntent intent, Map<Integer, PrimitiveKey> replacements,
                Map<ExternalPort, PrimitiveKey> proposedPortReplacements) {
            this.intent = intent;
            this.replacements = replacements;
            this.proposedPortReplacements = proposedPortReplacements;
        }

        public Map<Integer, PrimitiveKey> replacements() { return replacements; }

        /** Exact proposed identity at a factual fixed external port; never changes capture authority. */
        public Map<ExternalPort, PrimitiveKey> proposedPortReplacements() { return proposedPortReplacements; }

        /** Replacement position is movable only inside the original full selected endpoints. */
        public boolean mayPosition(int occurrence) {
            return replacements.containsKey(occurrence)
                    && occurrence > intent.selectedRange().firstIndex()
                    && occurrence < intent.selectedRange().lastIndex();
        }

        /** Resolves original occurrence provenance without granting any new image evidence. */
        public PrimitiveKey destinationKey(FinalRoutePointId.ExistingWayNodeOccurrence occurrence) {
            PrimitiveKey replacement = replacements.get(occurrence.originalOccurrenceIndex());
            if (replacement == null) return occurrence.nodeKey();
            if (!occurrence.wayKey().equals(intent.selectedWayKey())
                    || !intent.originalNodeKeys().get(occurrence.originalOccurrenceIndex()).equals(occurrence.nodeKey())) {
                throw new IllegalArgumentException("Disconnection source provenance differs");
            }
            return replacement;
        }
    }
}
