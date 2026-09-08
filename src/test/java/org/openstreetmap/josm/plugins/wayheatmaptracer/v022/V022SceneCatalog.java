package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public neutral analytic scene catalog for v0.22 fixture, oracle, and replay tests. */
public final class V022SceneCatalog {
    private static final Set<String> IDS = Set.of(
            "S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10", "S11", "S12",
            "S13", "S14", "S15", "S16", "S17", "S18", "S19", "S20", "S21", "S22", "S23", "S24",
            "S25", "S26", "S27", "S28");
    private static final List<Integer> DEVELOPMENT_SEEDS = List.of(11, 29, 47, 83);
    private static final List<Integer> WITHHELD_SEEDS = List.of(101, 131, 173);
    private static final SyntheticHeatmapScene.EvidenceMask ALL_OBSERVED = (x, y) -> true;

    private V022SceneCatalog() {
        // Static catalog.
    }

    /** Returns all public scene IDs. */
    public static Set<String> ids() {
        return IDS;
    }

    /** Returns the development seeds declared by the fixture contract. */
    public static List<Integer> developmentSeeds() {
        return DEVELOPMENT_SEEDS;
    }

    /** Returns withheld generator-validation seeds declared by the fixture contract. */
    public static List<Integer> withheldSeeds() {
        return WITHHELD_SEEDS;
    }

    /** Builds a new deterministic scene instance for a public scene ID and portable seed. */
    public static SyntheticHeatmapScene scene(String id, int seed) {
        if (!IDS.contains(id)) {
            throw new IllegalArgumentException("Unknown v0.22 analytic scene: " + id);
        }
        return switch (id) {
            case "S01" -> straightDisplaced(seed);
            case "S02" -> realSine(seed);
            case "S03" -> falseDogleg(seed, false);
            case "S04" -> falseDogleg(seed, true);
            case "S05" -> genuineApex(seed);
            case "S06" -> weakCrossing(seed);
            case "S07" -> symmetricBranches(seed);
            case "S08" -> brightWrongParallel(seed);
            case "S09" -> intermittentUnion(seed);
            case "S10" -> persistentParallel(seed);
            case "S11" -> fragmentedTail(seed, false);
            case "S12" -> fragmentedTail(seed, true);
            case "S13" -> missedUTurn(seed);
            case "S14" -> clippedCore(seed);
            case "S15" -> broadPlateau(seed);
            case "S16" -> northwestDrift(seed);
            case "S17" -> narrowReattachment(seed);
            case "S18" -> preserveReceiverBend(seed);
            case "S19" -> splitReceiver(seed);
            case "S20" -> interiorRelocation(seed);
            case "S21" -> multiArmRelocation(seed);
            case "S22" -> coupledJunctions(seed);
            case "S23" -> fixedAnchorLoop(seed);
            case "S24" -> gradeSeparatedCrossing(seed);
            case "S25" -> locationBoundFeature(seed);
            case "S26" -> partialCleanup(seed);
            case "S27" -> lateRefitDefect(seed);
            case "S28" -> adversarialEmpty(seed);
            default -> throw new IllegalStateException(id);
        };
    }

    private static SyntheticHeatmapScene straightDisplaced(int seed) {
        TopologyOracle.Branch truth = branch("truth", line(0, 0, 240, 0), 0.7, 1.5);
        TopologyOracle.Network initial = network(List.of(branch("initial", line(0, 3, 240, 3), 0.0, 1.5)));
        return scene("S01", seed, List.of(truth), initial, ALL_OBSERVED, null, null,
                Map.of("initialDisplacementsMeters", "3,7,15", "pinnedEndpoints", "true"));
    }

    private static SyntheticHeatmapScene realSine(int seed) {
        TopologyOracle.Branch truth = branch("truth", new SineCurve(0, 360, 8, 120), 0.7, 1.5);
        TopologyOracle.Network initial = network(List.of(branch("initial", new SineCurve(0, 360, 4, 120), 0.0, 1.5)));
        return scene("S02", seed, List.of(truth), initial, ALL_OBSERVED, null, null,
                Map.of("sourceSamples", "5", "verticalDisplacementMeters", "4"));
    }

    private static SyntheticHeatmapScene falseDogleg(int seed, boolean severe) {
        TopologyOracle.Branch truth = branch("truth", line(0, 0, 120, 0), 0.7, 1.5);
        List<V022Point> candidate = severe
                ? List.of(point(0, 0), point(50, 0), point(52, .6), point(54, 4.8), point(56, 4.0), point(58, .5), point(60, 0), point(120, 0))
                : List.of(point(0, 0), point(50, 0), point(52, .3), point(54, 1.5), point(56, 1.2), point(58, .2), point(60, 0), point(120, 0));
        return scene(severe ? "S04" : "S03", seed, List.of(truth), network(List.of(truth)), ALL_OBSERVED,
                new SyntheticHeatmapScene.DefectiveCandidate(candidate), null,
                Map.of("defect", severe ? "severe-false-apex" : "moderate-false-dogleg"));
    }

    private static SyntheticHeatmapScene genuineApex(int seed) {
        PolylineCurve apex = polyline(point(0, 0), point(50, 0), point(54, 4.8), point(56, 4.0), point(60, 0), point(120, 0));
        TopologyOracle.Branch truth = branch("truth", apex, .7, 1.5);
        return scene("S05", seed, List.of(truth), network(List.of(truth)), ALL_OBSERVED,
                new SyntheticHeatmapScene.DefectiveCandidate(apex.vertices()), null,
                Map.of("control", "supported-apex"));
    }

    private static SyntheticHeatmapScene weakCrossing(int seed) {
        TopologyOracle.Branch target = branch("target", line(0, 0, 240, 0), .2, 1.5);
        TopologyOracle.Branch crossing = branch("crossing", line(120, -60, 120, 60), .9, 1.5);
        return scene("S06", seed, List.of(target, crossing), network(List.of(target)), ALL_OBSERVED, null, null,
                Map.of("obscuredIntersectionHalfWidthMeters", "4", "port", "120,0"));
    }

    private static SyntheticHeatmapScene symmetricBranches(int seed) {
        TopologyOracle.Branch upper = branch("upper", polyline(point(0, 0), point(40, 15), point(80, 15), point(120, 0)), .7, 1.5);
        TopologyOracle.Branch lower = branch("lower", polyline(point(0, 0), point(40, -15), point(80, -15), point(120, 0)), .7, 1.5);
        return scene("S07", seed, List.of(upper, lower), network(List.of(upper)), ALL_OBSERVED, null, null,
                Map.of("expectedDisposition", "review-required", "symmetry", "reflected"));
    }

    private static SyntheticHeatmapScene brightWrongParallel(int seed) {
        TopologyOracle.Branch truth = branch("truth", line(0, 0, 120, 0), .5, 1.5);
        TopologyOracle.Branch wrong = branch("brighter-parallel", line(0, 8, 120, 8), .9, 1.5);
        return scene("S08", seed, List.of(truth, wrong), network(List.of(truth)), ALL_OBSERVED, null, null,
                Map.of("informedPort", "60,0", "uninformedDisposition", "review-required"));
    }

    private static SyntheticHeatmapScene intermittentUnion(int seed) {
        TopologyOracle.Branch low = branch("low", line(0, -1, 300, -1), .15, 1.5);
        TopologyOracle.Branch center = branch("center", line(0, 0, 300, 0), .15, 1.5);
        TopologyOracle.Branch high = branch("high", line(0, 1, 300, 1), .15, 1.5);
        SyntheticHeatmapScene.EvidenceMask holes = (x, y) -> Math.floorMod((int) Math.floor(x), 35) > 2;
        return scene("S09", seed, List.of(low, center, high), network(List.of(center)), holes, null, null,
                Map.of("activeSpanMeters", "12", "staggerMeters", "4", "internalHoles", "true"));
    }

    private static SyntheticHeatmapScene persistentParallel(int seed) {
        TopologyOracle.Branch south = branch("south", line(0, -5, 300, -5), .7, 1.5);
        TopologyOracle.Branch north = branch("north", line(0, 5, 300, 5), .7, 1.5);
        return scene("S10", seed, List.of(south, north), network(List.of(south)), ALL_OBSERVED, null, null,
                Map.of("deepValley", "true", "independentBranchX", "180"));
    }

    private static SyntheticHeatmapScene fragmentedTail(int seed, boolean missingTail) {
        TopologyOracle.Branch truth = branch("truth", new SineCurve(0, 600, 3, 240), .7, 1.5);
        SyntheticHeatmapScene.EvidenceMask mask = missingTail ? (x, y) -> x < 200.0
                : (x, y) -> x < 200.0 || Math.floorMod((int) Math.floor(x), 35) > 2;
        return scene(missingTail ? "S12" : "S11", seed, List.of(truth), network(List.of(truth)), mask, null, null,
                Map.of("proposalStopsAtMeters", "200", "expectedTail", missingTail ? "missing" : "observed-with-holes"));
    }

    private static SyntheticHeatmapScene missedUTurn(int seed) {
        AnalyticCurve truthCurve = new CompositeCurve(List.of(
                line(0, 0, 100, 0), new ArcCurve(point(100, -12), 12, Math.PI / 2.0, 3.0 * Math.PI / 2.0),
                line(100, -24, 20, -24)));
        TopologyOracle.Branch truth = branch("truth", truthCurve, .7, 1.5);
        return scene("S13", seed, List.of(truth), network(List.of(branch("rough", line(0, 0, 20, -24), 0, 1.5))), ALL_OBSERVED,
                null, null, Map.of("requiredEngine", "directional-image", "expected", "u-turn"));
    }

    private static SyntheticHeatmapScene clippedCore(int seed) {
        TopologyOracle.Branch truth = branch("truth", line(-30, -30, 30, 30), .7, 1.5);
        SyntheticHeatmapScene.EvidenceMask clipped = (x, y) -> Math.abs(y - x) > 1.5 || Math.abs(x) > 7.01;
        return scene("S14", seed, List.of(truth), network(List.of(truth)), clipped, null, null,
                Map.of("decisionHalfWidthMeters", "7.01", "clip", "one-sided-core"));
    }

    private static SyntheticHeatmapScene broadPlateau(int seed) {
        TopologyOracle.Branch center = branch("truth", new SineCurve(0, 240, 1.5, 240), .9, 3.0);
        return scene("S15", seed, List.of(center), network(List.of(center)), ALL_OBSERVED, null, null,
                Map.of("highCoreHalfWidthMeters", "3", "asymmetricShoulderEndMeters", "12"));
    }

    private static SyntheticHeatmapScene northwestDrift(int seed) {
        TopologyOracle.Branch truth = branch("truth", polyline(point(-80, -20), point(-40, 20), point(-20, 60), point(-60, 80)), .7, 1.5);
        TopologyOracle.Branch initial = branch("initial", polyline(point(-80, -12), point(-40, 28), point(-20, 68), point(-60, 88)), 0, 1.5);
        return scene("S16", seed, List.of(truth), network(List.of(initial)), ALL_OBSERVED, null, null,
                Map.of("sourceOmission", "final-turn", "displacementMeters", "8"));
    }

    private static SyntheticHeatmapScene narrowReattachment(int seed) {
        TopologyOracle.Network truth = junctionNetwork(false, false, false);
        return new SyntheticHeatmapScene("S17", seed, truth, truth, ALL_OBSERVED, null, junctionNetwork(true, false, false),
                Map.of("receiverOrder", "L,M,X,R", "reattach", "narrow-t"));
    }

    private static SyntheticHeatmapScene preserveReceiverBend(int seed) {
        TopologyOracle.Network truth = junctionNetwork(false, true, false);
        return new SyntheticHeatmapScene("S18", seed, truth, truth, ALL_OBSERVED, null, junctionNetwork(true, true, false),
                Map.of("receiverReconstruction", "disabled", "oldBend", "preserve"));
    }

    private static SyntheticHeatmapScene splitReceiver(int seed) {
        TopologyOracle.Network truth = junctionNetwork(false, false, true);
        return new SyntheticHeatmapScene("S19", seed, truth, truth, ALL_OBSERVED, null, junctionNetwork(true, false, true),
                Map.of("receiverWays", "2", "relations", "route-and-restriction"));
    }

    private static SyntheticHeatmapScene interiorRelocation(int seed) {
        TopologyOracle.Network truth = fourArmNetwork(false, false);
        return new SyntheticHeatmapScene("S20", seed, truth, truth, ALL_OBSERVED, null, fourArmNetwork(true, false),
                Map.of("selectedContinuation", "west-east", "oldJunction", "7,6"));
    }

    private static SyntheticHeatmapScene multiArmRelocation(int seed) {
        TopologyOracle.Network truth = fourArmNetwork(false, true);
        return new SyntheticHeatmapScene("S21", seed, truth, truth, ALL_OBSERVED, null, fourArmNetwork(true, true),
                Map.of("nearbyUnconnectedPaths", "2", "required", "all-original-arms"));
    }

    private static SyntheticHeatmapScene coupledJunctions(int seed) {
        TopologyOracle.Branch receiver = branch("receiver", line(-60, 0, 60, 0), .7, 1.5);
        TopologyOracle.Branch first = branch("first", line(-6, 40, -6, 0), .7, 1.5);
        TopologyOracle.Branch second = branch("second", line(6, -40, 6, 0), .7, 1.5);
        return scene("S22", seed, List.of(receiver, first, second), network(List.of(receiver, first, second)), ALL_OBSERVED, null, null,
                Map.of("junctionSeparationMeters", "12", "reject", "semantic-order-inversion"));
    }

    private static SyntheticHeatmapScene fixedAnchorLoop(int seed) {
        TopologyOracle.Branch truth = branch("truth", polyline(point(-40, 0), point(0, 30), point(40, 0)), .7, 1.5);
        return scene("S23", seed, List.of(truth), network(List.of(truth)), ALL_OBSERVED, null, null,
                Map.of("fixedMode", "block-loop", "reattachMode", "reconstruct-or-reject"));
    }

    private static SyntheticHeatmapScene gradeSeparatedCrossing(int seed) {
        TopologyOracle.Branch eastWest = branch("bridge", line(-60, 0, 60, 0), .7, 1.5);
        TopologyOracle.Branch northSouth = branch("ground", line(0, -60, 0, 60), .7, 1.5);
        return scene("S24", seed, List.of(eastWest, northSouth), network(List.of(eastWest, northSouth)), ALL_OBSERVED, null, null,
                Map.of("sharedNode", "false", "layerContext", "different"));
    }

    private static SyntheticHeatmapScene locationBoundFeature(int seed) {
        TopologyOracle.Network truth = junctionNetwork(false, false, false);
        return new SyntheticHeatmapScene("S25", seed, truth, truth, ALL_OBSERVED, null, null,
                Map.of("locationBoundFeature", "barrier-or-signal", "unsupportedRelation", "block"));
    }

    private static SyntheticHeatmapScene partialCleanup(int seed) {
        TopologyOracle.Branch truth = branch("truth", new SineCurve(0, 240, 4, 120), .7, 1.5);
        List<V022Point> wrinkles = List.of(point(0, 0), point(35, 4), point(38, 6), point(42, 3), point(90, -4),
                point(120, 0), point(155, 4), point(158, 6), point(162, 3), point(240, 0));
        SyntheticHeatmapScene.EvidenceMask island = (x, y) -> x < 95 || x > 145;
        return scene("S26", seed, List.of(truth), network(List.of(truth)), island,
                new SyntheticHeatmapScene.DefectiveCandidate(wrinkles), null,
                Map.of("fixedAnchorMeters", "120", "expectedCleanup", "partial"));
    }

    private static SyntheticHeatmapScene lateRefitDefect(int seed) {
        TopologyOracle.Branch truth = branch("truth", line(0, 0, 120, 0), .7, 1.5);
        List<V022Point> crossing = List.of(point(0, 0), point(55, 0), point(60, 15), point(65, 0), point(120, 0));
        return scene("S27", seed, List.of(truth), network(List.of(truth)), ALL_OBSERVED,
                new SyntheticHeatmapScene.DefectiveCandidate(crossing), null,
                Map.of("expected", "line-search-backtrack", "defect", "late-crossing"));
    }

    private static SyntheticHeatmapScene adversarialEmpty(int seed) {
        TopologyOracle.Network truth = new TopologyOracle.Network(Map.of(), List.of(), List.of());
        TopologyOracle.Network initial = network(List.of(branch("initial", line(0, 0, 120, 0), 0, 1.5)));
        return new SyntheticHeatmapScene("S28", seed, truth, initial, (x, y) -> false, null, null,
                Map.of("raster", "all-invalid", "plateau", "unlocalized", "expected", "typed-no-signal-or-review"));
    }

    private static TopologyOracle.Network junctionNetwork(boolean defective, boolean oldBend, boolean splitReceiver) {
        Map<String, V022Point> nodes = new LinkedHashMap<>();
        nodes.put("L", point(-60, 0));
        nodes.put("M", point(10, 0));
        nodes.put("X", point(20, 0));
        nodes.put("R", point(60, 0));
        nodes.put("A", point(20, 60));
        nodes.put("J", oldBend ? point(0, 3) : point(0, 0));
        List<TopologyOracle.Way> ways = splitReceiver
                ? List.of(new TopologyOracle.Way("receiver-west", List.of("L", "M", "X")),
                        new TopologyOracle.Way("receiver-east", List.of("X", "R")))
                : List.of(new TopologyOracle.Way("receiver", List.of("L", "M", "X", "R")));
        ways = new java.util.ArrayList<>(ways);
        ((java.util.ArrayList<TopologyOracle.Way>) ways).add(defective
                ? new TopologyOracle.Way("selected", List.of("A", "J", "M", "J"))
                : new TopologyOracle.Way("selected", List.of("A", "X")));
        List<TopologyOracle.Branch> branches = List.of(
                branch("receiver", line(-60, 0, 60, 0), .7, 1.5), branch("selected", line(20, 60, 20, 0), .7, 1.5));
        return new TopologyOracle.Network(nodes, branches, ways);
    }

    private static TopologyOracle.Network fourArmNetwork(boolean defective, boolean diagonal) {
        Map<String, V022Point> nodes = new LinkedHashMap<>();
        nodes.put("W", point(-60, 0));
        nodes.put("E", point(60, 0));
        nodes.put("S", point(0, -60));
        nodes.put("N", point(0, 60));
        nodes.put("X", point(0, 0));
        nodes.put("J", point(7, 6));
        if (diagonal) {
            nodes.put("D", point(50, 50));
        }
        String junction = defective ? "J" : "X";
        List<TopologyOracle.Way> ways = new java.util.ArrayList<>(List.of(
                new TopologyOracle.Way("selected", List.of("W", junction, "E")),
                new TopologyOracle.Way("north-south", List.of("S", junction, "N"))));
        if (diagonal) {
            ways.add(new TopologyOracle.Way("diagonal", List.of(junction, "D")));
        }
        List<TopologyOracle.Branch> branches = List.of(branch("east-west", line(-60, 0, 60, 0), .7, 1.5),
                branch("north-south", line(0, -60, 0, 60), .7, 1.5));
        return new TopologyOracle.Network(nodes, branches, ways);
    }

    private static SyntheticHeatmapScene scene(String id, int seed, List<TopologyOracle.Branch> truth,
            TopologyOracle.Network initial, SyntheticHeatmapScene.EvidenceMask mask,
            SyntheticHeatmapScene.DefectiveCandidate candidate, TopologyOracle.Network defective,
            Map<String, String> attributes) {
        TopologyOracle.Network physical = network(truth);
        return new SyntheticHeatmapScene(id, seed, physical, initial, mask, candidate, defective, attributes);
    }

    private static TopologyOracle.Network network(List<TopologyOracle.Branch> branches) {
        Map<String, V022Point> nodes = new LinkedHashMap<>();
        List<TopologyOracle.Way> ways = new java.util.ArrayList<>();
        for (TopologyOracle.Branch branch : branches) {
            String start = branch.id() + "-start";
            String end = branch.id() + "-end";
            nodes.put(start, branch.curve().pointAtFraction(0));
            nodes.put(end, branch.curve().pointAtFraction(1));
            ways.add(new TopologyOracle.Way(branch.id(), List.of(start, end)));
        }
        return new TopologyOracle.Network(nodes, branches, ways);
    }

    private static TopologyOracle.Branch branch(String id, AnalyticCurve curve, double amplitude, double sigma) {
        return new TopologyOracle.Branch(id, curve, amplitude, sigma);
    }

    private static LineCurve line(double x1, double y1, double x2, double y2) {
        return new LineCurve(point(x1, y1), point(x2, y2));
    }

    private static PolylineCurve polyline(V022Point... points) {
        return new PolylineCurve(List.of(points));
    }

    private static V022Point point(double x, double y) {
        return new V022Point(x, y);
    }
}
