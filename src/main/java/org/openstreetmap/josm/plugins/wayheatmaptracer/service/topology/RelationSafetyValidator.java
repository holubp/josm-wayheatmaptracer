package org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.Finding;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.FindingCode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Completeness;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Id;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.PrimitiveType;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Relation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.RelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Way;

/** Explicit relation handlers for topology changes made by the junction planner. */
final class RelationSafetyValidator {
    private RelationSafetyValidator() {
        // Utility class.
    }

    static List<Finding> validate(TopologyNetwork before, TopologyNetwork after,
        Set<Id> changedPrimitiveIds, Map<Id, Id> effectiveNodeRemaps,
        Set<Id> approvedRelationNodeRemaps) {
        List<Finding> findings = new ArrayList<>();
        if (!before.relations().keySet().equals(after.relations().keySet())) {
            findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                "Junction planning cannot add or remove relation identities"));
            return findings;
        }
        for (Relation oldRelation : before.relations().values()) {
            Relation newRelation = after.relation(oldRelation.id());
            if (!affected(oldRelation, newRelation, changedPrimitiveIds)) {
                continue;
            }
            if (oldRelation.completeness() != Completeness.COMPLETE
                || newRelation.completeness() != Completeness.COMPLETE) {
                findings.add(finding(FindingCode.INCOMPLETE_RELATION,
                    "Affected relation is not completely materialized: " + oldRelation.id()));
                continue;
            }
            String type = oldRelation.tags().getOrDefault("type", "");
            switch (type) {
                case "restriction" -> validateRestriction(before, after, oldRelation, newRelation,
                    effectiveNodeRemaps, approvedRelationNodeRemaps, findings);
                case "route" -> validateRoute(before, after, oldRelation, newRelation,
                    changedPrimitiveIds, findings);
                case "multipolygon", "boundary" -> findings.add(finding(
                    FindingCode.UNSUPPORTED_RELATION,
                    "Area or boundary relation needs a dedicated topology handler: " + oldRelation.id()));
                default -> findings.add(finding(FindingCode.UNSUPPORTED_RELATION,
                    "No tested handler exists for affected relation type '" + type + "': "
                        + oldRelation.id()));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean affected(Relation before, Relation after, Set<Id> changedPrimitiveIds) {
        return !before.equals(after) || before.members().stream()
            .map(RelationMember::memberId).anyMatch(changedPrimitiveIds::contains);
    }

    private static void validateRestriction(TopologyNetwork before, TopologyNetwork after,
        Relation oldRelation, Relation newRelation, Map<Id, Id> effectiveNodeRemaps,
        Set<Id> approvedRelationNodeRemaps, List<Finding> findings) {
        int findingCountBefore = findings.size();
        if (!oldRelation.tags().equals(newRelation.tags())) {
            findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                "Restriction tags, including except/conditional tags, changed: " + oldRelation.id()));
            return;
        }
        List<RelationMember> expectedMembers = oldRelation.members().stream().map(member -> {
            Id replacement = effectiveNodeRemaps.get(member.memberId());
            if (replacement != null && !replacement.equals(member.memberId())
                && approvedRelationNodeRemaps.contains(member.memberId()) && "via".equals(member.role())) {
                return new RelationMember(replacement, member.role());
            }
            return member;
        }).toList();
        if (!expectedMembers.equals(newRelation.members())) {
            findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                "Restriction member order, roles or identities changed without approval: " + oldRelation.id()));
            return;
        }

        List<RelationMember> from = membersWithRole(newRelation, "from");
        List<RelationMember> via = membersWithRole(newRelation, "via");
        List<RelationMember> to = membersWithRole(newRelation, "to");
        if (from.size() != 1 || via.isEmpty() || to.size() != 1
            || from.get(0).memberId().type() != PrimitiveType.WAY
            || to.get(0).memberId().type() != PrimitiveType.WAY) {
            findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                "Restriction requires one from, one to and at least one ordered via member: "
                    + oldRelation.id()));
            return;
        }
        boolean viaNodes = via.stream().allMatch(member -> member.memberId().type() == PrimitiveType.NODE);
        boolean viaWays = via.stream().allMatch(member -> member.memberId().type() == PrimitiveType.WAY);
        if (viaNodes && via.size() == 1) {
            Id viaNode = via.get(0).memberId();
            if (!viaNodeTraversalConnected(after, from.get(0).memberId(), viaNode,
                to.get(0).memberId())) {
                findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                    "Via-node restriction lost directed from/via/to traversal: " + oldRelation.id()));
            }
        } else if (viaWays) {
            List<Id> traversal = new ArrayList<>();
            traversal.add(from.get(0).memberId());
            via.forEach(member -> traversal.add(member.memberId()));
            traversal.add(to.get(0).memberId());
            if (!orderedWayTraversalConnected(after, traversal)) {
                findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                    "Via-way restriction lost ordered entry/exit connectivity: " + oldRelation.id()));
            }
        } else {
            findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                "Mixed, repeated-node or missing via restriction shape is unsupported: " + oldRelation.id()));
        }

        if (findings.size() == findingCountBefore && !restrictionWasValidBefore(before, oldRelation)) {
            findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                "Captured restriction was already structurally incomplete: " + oldRelation.id()));
        }
    }

    private static boolean restrictionWasValidBefore(TopologyNetwork before, Relation relation) {
        List<RelationMember> from = membersWithRole(relation, "from");
        List<RelationMember> via = membersWithRole(relation, "via");
        List<RelationMember> to = membersWithRole(relation, "to");
        if (from.size() != 1 || via.isEmpty() || to.size() != 1) {
            return false;
        }
        if (via.stream().allMatch(member -> member.memberId().type() == PrimitiveType.NODE)
            && via.size() == 1) {
            Id viaNode = via.get(0).memberId();
            return viaNodeTraversalConnected(before, from.get(0).memberId(), viaNode,
                to.get(0).memberId());
        }
        if (via.stream().allMatch(member -> member.memberId().type() == PrimitiveType.WAY)) {
            List<Id> traversal = new ArrayList<>();
            traversal.add(from.get(0).memberId());
            via.forEach(member -> traversal.add(member.memberId()));
            traversal.add(to.get(0).memberId());
            return orderedWayTraversalConnected(before, traversal);
        }
        return false;
    }

    private static void validateRoute(TopologyNetwork before, TopologyNetwork after,
        Relation oldRelation, Relation newRelation, Set<Id> changedPrimitiveIds,
        List<Finding> findings) {
        if (!oldRelation.equals(newRelation)) {
            findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                "Route relation identity, tags, member order or roles changed: " + oldRelation.id()));
            return;
        }
        if (oldRelation.members().stream().anyMatch(member -> member.memberId().type()
            != PrimitiveType.WAY && changedPrimitiveIds.contains(member.memberId()))) {
            findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                "Route non-way member changed without a dedicated semantic handler: "
                    + oldRelation.id()));
            return;
        }
        List<RelationMember> wayMembers = oldRelation.members().stream()
            .filter(member -> member.memberId().type() == PrimitiveType.WAY).toList();
        if (wayMembers.isEmpty() || wayMembers.stream().anyMatch(member ->
            !member.role().isEmpty() && !"forward".equals(member.role())
                && !"backward".equals(member.role()))
            || !orderedRouteTraversalConnected(before, wayMembers)
            || !orderedRouteTraversalConnected(after, wayMembers)) {
            findings.add(finding(FindingCode.RELATION_SEMANTICS_INVALID,
                "Route lacks an unambiguous directed traversal through ordered members: "
                    + oldRelation.id()));
        }
    }

    private static boolean orderedRouteTraversalConnected(TopologyNetwork network,
        List<RelationMember> members) {
        List<Id> wayIds = members.stream().map(RelationMember::memberId).toList();
        if (hasAmbiguousTransferPort(network, wayIds)) {
            return false;
        }
        RelationMember firstMember = members.get(0);
        Way first = network.way(firstMember.memberId());
        Set<Id> possibleExits = new HashSet<>();
        for (int end = 0; end < first.nodeIds().size(); end++) {
            for (int start = 0; start < first.nodeIds().size(); start++) {
                if (canTraverseRoute(first, firstMember.role(), start, end)) {
                    possibleExits.add(first.nodeIds().get(end));
                    break;
                }
            }
        }
        for (int memberIndex = 1; memberIndex < members.size(); memberIndex++) {
            RelationMember member = members.get(memberIndex);
            Way current = network.way(member.memberId());
            Set<Id> nextExits = new HashSet<>();
            for (int entry = 0; entry < current.nodeIds().size(); entry++) {
                if (!possibleExits.contains(current.nodeIds().get(entry))) {
                    continue;
                }
                for (int exit = 0; exit < current.nodeIds().size(); exit++) {
                    if (canTraverseRoute(current, member.role(), entry, exit)) {
                        nextExits.add(current.nodeIds().get(exit));
                    }
                }
            }
            if (nextExits.isEmpty()) {
                return false;
            }
            possibleExits = nextExits;
        }
        return !possibleExits.isEmpty();
    }

    private static boolean canTraverseRoute(Way way, String role, int start, int end) {
        return canTraverse(way, start, end) && switch (role) {
            case "forward" -> start < end;
            case "backward" -> start > end;
            case "" -> true;
            default -> false;
        };
    }

    private static boolean orderedWayTraversalConnected(TopologyNetwork network, List<Id> wayIds) {
        if (hasAmbiguousTransferPort(network, wayIds)) {
            return false;
        }
        Way previous = network.way(wayIds.get(0));
        Set<Id> possibleExits = new HashSet<>();
        for (int index = 0; index < previous.nodeIds().size(); index++) {
            if (hasApproach(previous, index)) {
                possibleExits.add(previous.nodeIds().get(index));
            }
        }
        for (int index = 1; index < wayIds.size(); index++) {
            Way current = network.way(wayIds.get(index));
            Set<Id> nextExits = new HashSet<>();
            for (int entry = 0; entry < current.nodeIds().size(); entry++) {
                if (!possibleExits.contains(current.nodeIds().get(entry))) {
                    continue;
                }
                if (index == wayIds.size() - 1) {
                    if (hasDeparture(current, entry)) {
                        return true;
                    }
                } else {
                    for (int exit = 0; exit < current.nodeIds().size(); exit++) {
                        if (canTraverse(current, entry, exit)) {
                            nextExits.add(current.nodeIds().get(exit));
                        }
                    }
                }
            }
            if (nextExits.isEmpty()) {
                return false;
            }
            possibleExits = nextExits;
        }
        return false;
    }

    private static boolean hasAmbiguousTransferPort(TopologyNetwork network, List<Id> wayIds) {
        for (int index = 1; index < wayIds.size(); index++) {
            Way previous = network.way(wayIds.get(index - 1));
            Way current = network.way(wayIds.get(index));
            Set<Id> sharedPorts = new HashSet<>(previous.nodeIds());
            sharedPorts.retainAll(current.nodeIds());
            for (Id port : sharedPorts) {
                if (occurrenceCount(previous, port) != 1 || occurrenceCount(current, port) != 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean viaNodeTraversalConnected(TopologyNetwork network, Id fromWayId,
        Id viaNode, Id toWayId) {
        Way from = network.way(fromWayId);
        Way to = network.way(toWayId);
        if (occurrenceCount(from, viaNode) != 1 || occurrenceCount(to, viaNode) != 1) {
            return false;
        }
        for (int index = 0; index < from.nodeIds().size(); index++) {
            if (from.nodeIds().get(index).equals(viaNode) && hasApproach(from, index)) {
                for (int departure = 0; departure < to.nodeIds().size(); departure++) {
                    if (to.nodeIds().get(departure).equals(viaNode)
                        && hasDeparture(to, departure)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static long occurrenceCount(Way way, Id nodeId) {
        return way.nodeIds().stream().filter(nodeId::equals).count();
    }

    private static boolean hasApproach(Way way, int at) {
        for (int start = 0; start < way.nodeIds().size(); start++) {
            if (canTraverse(way, start, at)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDeparture(Way way, int at) {
        for (int end = 0; end < way.nodeIds().size(); end++) {
            if (canTraverse(way, at, end)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canTraverse(Way way, int start, int end) {
        if (start == end) {
            return false;
        }
        String oneway = way.tags().getOrDefault("oneway", "no");
        return switch (oneway) {
            case "yes", "1", "true" -> start < end;
            case "-1" -> start > end;
            case "no", "0", "false" -> true;
            default -> false;
        };
    }

    private static List<RelationMember> membersWithRole(Relation relation, String role) {
        return relation.members().stream().filter(member -> Objects.equals(role, member.role())).toList();
    }

    private static Finding finding(FindingCode code, String detail) {
        return new Finding(code, detail);
    }
}
