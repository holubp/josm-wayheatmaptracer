package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent topology representation and checks for synthetic v0.22 scenes. */
public final class TopologyOracle {
    private TopologyOracle() {
        // Utility class.
    }

    /** Immutable physical truth branch with a scalar evidence profile. */
    public record Branch(String id, AnalyticCurve curve, double amplitude, double sigmaMeters) {
        /** Samples exact physical truth at at most {@code 0.1 m} spacing. */
        public List<V022Point> sampledPoints() {
            return curve.sample(0.1);
        }
    }

    /** Immutable editable synthetic way, represented only by node occurrence IDs. */
    public record Way(String id, List<String> nodeIds) {
        /** Creates a defensive immutable way. */
        public Way {
            nodeIds = List.copyOf(nodeIds);
            if (nodeIds.size() < 2) {
                throw new IllegalArgumentException("A synthetic way requires two node occurrences");
            }
        }
    }

    /** Detached synthetic graph used to distinguish truth, initial OSM, and defective proposals. */
    public record Network(Map<String, V022Point> nodes, List<Branch> branches, List<Way> ways) {
        /** Creates a deeply immutable synthetic graph. */
        public Network {
            nodes = Map.copyOf(new LinkedHashMap<>(nodes));
            branches = List.copyOf(branches);
            ways = List.copyOf(ways);
        }
    }

    /** Returns whether one node has exactly {@code expectedIncidentEdges} independent edge incidences. */
    public static boolean hasSingleSharedJunction(Network network, String nodeId, int expectedIncidentEdges) {
        if (!network.nodes().containsKey(nodeId)) {
            return false;
        }
        int incidences = 0;
        for (Way way : network.ways()) {
            for (int index = 1; index < way.nodeIds().size(); index++) {
                if (nodeId.equals(way.nodeIds().get(index - 1)) || nodeId.equals(way.nodeIds().get(index))) {
                    incidences++;
                }
            }
        }
        return incidences == expectedIncidentEdges;
    }

    /** Detects an immediate A-B-A traversal, a minimal topology-only receiver backtrack. */
    public static boolean hasBacktrack(Network network) {
        for (Way way : network.ways()) {
            for (int index = 2; index < way.nodeIds().size(); index++) {
                if (way.nodeIds().get(index - 2).equals(way.nodeIds().get(index))) {
                    return true;
                }
            }
        }
        return false;
    }
}
