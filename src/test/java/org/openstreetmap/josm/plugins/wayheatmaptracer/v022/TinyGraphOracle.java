package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exhaustive finite-graph oracle deliberately independent of every production inference engine. */
public final class TinyGraphOracle {
    private TinyGraphOracle() {
        // Utility class.
    }

    /** State observation cost at one ordered graph layer. */
    public record State(String id, double observationCost) {
    }

    /** Complete layered graph with explicit transition costs. */
    public record Graph(List<List<State>> layers, Map<String, Double> transitionCosts) {
        /** Creates defensive graph storage. */
        public Graph {
            layers = layers.stream().map(List::copyOf).toList();
            transitionCosts = Map.copyOf(transitionCosts);
            if (layers.isEmpty() || layers.stream().anyMatch(List::isEmpty)) {
                throw new IllegalArgumentException("Every tiny oracle layer must have at least one state");
            }
        }
    }

    /** Exhaustive result, including partition and per-layer posterior marginals. */
    public record Result(List<String> mapPath, double mapCost, double partition,
            List<Map<String, Double>> marginals) {
    }

    /** Constructs a graph from immutable layers and transition costs. */
    public static Graph graph(List<List<State>> layers, Map<String, Double> transitionCosts) {
        return new Graph(layers, transitionCosts);
    }

    /** Exhaustively enumerates all paths with deterministic lexical tie selection. */
    public static Result solve(Graph graph) {
        List<Path> paths = new ArrayList<>();
        enumerate(graph, 0, new ArrayList<>(), 0.0, paths);
        paths.sort(Comparator.comparingDouble(Path::cost).thenComparing(path -> String.join("\u0000", path.ids())));
        double partition = 0.0;
        List<Map<String, Double>> marginals = new ArrayList<>();
        for (List<State> layer : graph.layers()) {
            Map<String, Double> empty = new LinkedHashMap<>();
            for (State state : layer) {
                empty.put(state.id(), 0.0);
            }
            marginals.add(empty);
        }
        for (Path path : paths) {
            double mass = Math.exp(-path.cost());
            partition += mass;
            for (int index = 0; index < path.ids().size(); index++) {
                String id = path.ids().get(index);
                marginals.get(index).merge(id, mass, Double::sum);
            }
        }
        for (Map<String, Double> marginal : marginals) {
            for (Map.Entry<String, Double> entry : marginal.entrySet()) {
                entry.setValue(entry.getValue() / partition);
            }
        }
        Path map = paths.get(0);
        return new Result(List.copyOf(map.ids()), map.cost(), partition,
                marginals.stream().map(Map::copyOf).toList());
    }

    private static void enumerate(Graph graph, int index, List<State> path, double cost, List<Path> output) {
        if (index == graph.layers().size()) {
            output.add(new Path(path.stream().map(State::id).toList(), cost));
            return;
        }
        for (State state : graph.layers().get(index)) {
            double transition = path.isEmpty() ? 0.0
                    : graph.transitionCosts().getOrDefault(path.get(path.size() - 1).id() + ':' + state.id(), 0.0);
            path.add(state);
            enumerate(graph, index + 1, path, cost + state.observationCost() + transition, output);
            path.remove(path.size() - 1);
        }
    }

    private record Path(List<String> ids, double cost) {
    }
}
