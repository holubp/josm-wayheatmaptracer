package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class AlignmentServiceTest {
    @BeforeAll
    static void setPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void roughSketchSelectionUsesPreciseShapeAutomatically() {
        SelectionContext sketch = selection(4);
        ManagedHeatmapConfig config = config(AlignmentMode.MOVE_EXISTING_NODES);

        assertTrue(AlignmentService.isSketchLikeSelection(sketch));
        assertEquals(AlignmentMode.PRECISE_SHAPE, AlignmentService.effectiveAlignmentMode(sketch, config));
    }

    @Test
    void detailedSelectionKeepsConfiguredAlignmentMode() {
        SelectionContext detailed = selection(8);
        ManagedHeatmapConfig config = config(AlignmentMode.MOVE_EXISTING_NODES);

        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, AlignmentService.effectiveAlignmentMode(detailed, config));
    }

    @Test
    void shortSegmentOfLongerWayIsNotTreatedAsSketch() {
        SelectionContext segment = segmentSelection(8, 2, 5);
        ManagedHeatmapConfig config = config(AlignmentMode.MOVE_EXISTING_NODES);

        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, AlignmentService.effectiveAlignmentMode(segment, config));
    }

    @Test
    void multiColorConsensusProducesFusedCandidate() {
        AlignmentService service = new AlignmentService();
        List<CenterlineCandidate> candidates = List.of(
            candidate("blue/ridge-1", 20.0, "blue", List.of(-2.0, -3.0, -4.0, -5.0)),
            candidate("hot/ridge-1", 12.0, "hot", List.of(-3.0, -4.0, -5.0, -6.0)),
            candidate("gray/ridge-1", 10.0, "gray", List.of(-2.5, -3.5, -4.5, -5.5)),
            candidate("purple/ridge-1", 5.0, "purple", List.of(16.0, 16.0, 16.0))
        );

        List<CenterlineCandidate> scored = service.applyColorConsensus(candidates);

        CenterlineCandidate consensus = scored.stream()
            .filter(candidate -> "consensus".equals(candidate.evidence().detectorMode()))
            .findFirst()
            .orElseThrow();
        assertEquals(List.of("blue", "hot", "gray"), consensus.evidence().consensusModes());
        assertEquals("consensus-3/consensus/ridge-1", consensus.id());
        assertTrue(consensus.score() > 20.0, "Fused consensus should outrank a single high-support detector in its cluster");
        assertTrue(consensus.offsetsPx().stream().allMatch(offset -> offset < -1.0 && offset > -7.0));
    }

    @Test
    void multiColorConsensusDoesNotFuseDivergentOrRoughRidges() {
        AlignmentService service = new AlignmentService();
        List<CenterlineCandidate> candidates = List.of(
            candidate("blue/ridge-1", 20.0, "blue", List.of(-2.0, -8.0, -2.0, -8.0)),
            candidate("hot/ridge-1", 12.0, "hot", List.of(-12.0, -6.0, -12.0, -6.0)),
            candidate("gray/ridge-1", 10.0, "gray", List.of(-3.0, -7.0, -3.0, -7.0))
        );

        List<CenterlineCandidate> scored = service.applyColorConsensus(candidates);

        assertTrue(scored.stream().noneMatch(candidate -> "consensus".equals(candidate.evidence().detectorMode())),
            "Consensus should not synthesize geometry from ridges that diverge or oscillate");
    }

    @Test
    void fixedEndpointGuardRemovesNearJunctionKink() {
        AlignmentService service = new AlignmentService();
        SelectionContext selection = selection(3);
        List<EastNorth> source = List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(50.0, 0.0),
            new EastNorth(100.0, 0.0)
        );
        List<EastNorth> preview = List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(0.0, 10.0),
            new EastNorth(30.0, 2.0),
            new EastNorth(100.0, 0.0)
        );

        List<EastNorth> guarded = service.guardPreciseEndpointApproaches(selection, source, preview);

        assertEquals(new EastNorth(30.0, 2.0), guarded.get(1));
    }

    private SelectionContext selection(int nodeCount) {
        Way way = new Way();
        List<Node> nodes = java.util.stream.IntStream.range(0, nodeCount)
            .mapToObj(index -> new Node(new LatLon(0.0, index * 0.0001)))
            .toList();
        way.setNodes(nodes);
        return new SelectionContext(way, 0, nodeCount - 1, nodes, Set.of(nodes.get(0), nodes.get(nodeCount - 1)));
    }

    private SelectionContext segmentSelection(int nodeCount, int start, int end) {
        Way way = new Way();
        List<Node> nodes = java.util.stream.IntStream.range(0, nodeCount)
            .mapToObj(index -> new Node(new LatLon(0.0, index * 0.0001)))
            .toList();
        way.setNodes(nodes);
        List<Node> segment = nodes.subList(start, end + 1);
        return new SelectionContext(way, start, end, segment, Set.of(segment.get(0), segment.get(segment.size() - 1)));
    }

    private ManagedHeatmapConfig config(AlignmentMode mode) {
        return new ManagedHeatmapConfig(
            "", "", "", "",
            "all",
            "hot",
            "",
            ".*",
            mode,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            18,
            4,
            3.0,
            InferenceMode.STABLE_FIXED_SCALE,
            15,
            13,
            28.0,
            6.0,
            0L
        );
    }

    private CenterlineCandidate candidate(String id, double score, String mode, List<Double> offsets) {
        List<Point2D.Double> points = java.util.stream.IntStream.range(0, offsets.size())
            .mapToObj(index -> new Point2D.Double(index * 10.0, offsets.get(index)))
            .toList();
        List<EastNorth> eastNorth = java.util.stream.IntStream.range(0, offsets.size())
            .mapToObj(index -> new EastNorth(index * 10.0, offsets.get(index)))
            .toList();
        return new CenterlineCandidate(
            id,
            score,
            points,
            offsets,
            eastNorth,
            new CandidateEvidence(mode, offsets.size(), offsets.size(), 0, 0,
                offsets.size(), 1.0, 0.4, 0.0, List.of()),
            List.of()
        );
    }
}
