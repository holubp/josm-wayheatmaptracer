package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class V022SelectedSegmentNodeAuthorityTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void classifiesOnlyUnsharedUntaggedOrdinaryInteriorsAsMovable() {
        DataSet dataSet = new DataSet();
        Node start = node(1, 0); Node ordinary1 = node(2, 1); Node tagged = node(3, 2);
        Node shared = node(4, 3); Node explicitlyFixed = node(5, 4); Node ordinary2 = node(6, 5);
        Node end = node(7, 6); Node branchA = node(8, 3); Node branchB = node(9, 4);
        tagged.put("barrier", "gate");
        List<Node> selectedNodes = List.of(start, ordinary1, tagged, shared, explicitlyFixed, ordinary2, end);
        Way selected = way(10, selectedNodes);
        Way branch = way(11, List.of(branchA, shared, branchB));
        selectedNodes.forEach(dataSet::addPrimitive);
        dataSet.addPrimitive(branchA); dataSet.addPrimitive(branchB);
        dataSet.addPrimitive(selected); dataSet.addPrimitive(branch);
        SelectionContext selection = new SelectionContext(selected, 0, 6, selectedNodes,
                Set.of(start, explicitlyFixed, end));

        SelectedSegmentNodeAuthority.Result result = SelectedSegmentNodeAuthority.classify(selection);

        assertEquals(Set.of(key(ordinary1), key(ordinary2)), result.movableNodeKeys());
        assertEquals(Set.of(key(start), key(tagged), key(shared), key(explicitlyFixed), key(end)),
                result.protectedNodeKeys());
        assertTrue(result.removableNodeKeys().isEmpty());
        assertTrue(result.editableExistingKeys().contains(wayKey(selected)));
        assertTrue(result.editableExistingKeys().containsAll(result.movableNodeKeys()));
        assertEquals(SelectedSegmentNodeAuthority.NodeAuthorityReason.ORDINARY_INTERIOR,
                result.reasons().get(key(ordinary1)));
        assertEquals(SelectedSegmentNodeAuthority.NodeAuthorityReason.TAGGED,
                result.reasons().get(key(tagged)));
        assertEquals(SelectedSegmentNodeAuthority.NodeAuthorityReason.EXTERNAL_REFERRER,
                result.reasons().get(key(shared)));
        assertEquals(SelectedSegmentNodeAuthority.NodeAuthorityReason.EXPLICIT_FIXED,
                result.reasons().get(key(explicitlyFixed)));
        assertEquals(SelectedSegmentNodeAuthority.NodeAuthorityReason.SEGMENT_BOUNDARY,
                result.reasons().get(key(start)));
        assertTrue(java.util.Collections.disjoint(result.movableNodeKeys(), result.protectedNodeKeys()));
        assertTrue(java.util.Collections.disjoint(result.movableNodeKeys(), result.removableNodeKeys()));
        assertTrue(java.util.Collections.disjoint(result.protectedNodeKeys(), result.removableNodeKeys()));
    }

    @Test
    void taggedAndRelationReferencedEndpointsHaveStrongerReasonsThanBoundary() {
        DataSet taggedData = new DataSet();
        Node taggedStart = node(21, 0); Node taggedEnd = node(22, 1);
        taggedStart.put("barrier", "gate");
        Way taggedWay = way(20, List.of(taggedStart, taggedEnd));
        taggedData.addPrimitive(taggedStart); taggedData.addPrimitive(taggedEnd);
        taggedData.addPrimitive(taggedWay);
        SelectedSegmentNodeAuthority.Result tagged = SelectedSegmentNodeAuthority.classify(
                new SelectionContext(taggedWay, 0, 1, List.of(taggedStart, taggedEnd),
                        Set.of(taggedStart, taggedEnd)));
        assertEquals(SelectedSegmentNodeAuthority.NodeAuthorityReason.TAGGED,
                tagged.reasons().get(key(taggedStart)));
        assertTrue(tagged.protectedNodeKeys().contains(key(taggedStart)));

        DataSet relationData = new DataSet();
        Node relatedStart = node(31, 0); Node relatedEnd = node(32, 1);
        Way relatedWay = way(30, List.of(relatedStart, relatedEnd));
        Relation relation = new Relation();
        relation.setMembers(List.of(new RelationMember("via", relatedStart)));
        relationData.addPrimitive(relatedStart); relationData.addPrimitive(relatedEnd);
        relationData.addPrimitive(relatedWay); relationData.addPrimitive(relation);
        SelectedSegmentNodeAuthority.Result related = SelectedSegmentNodeAuthority.classify(
                new SelectionContext(relatedWay, 0, 1, List.of(relatedStart, relatedEnd),
                        Set.of(relatedStart, relatedEnd)));
        assertEquals(SelectedSegmentNodeAuthority.NodeAuthorityReason.EXTERNAL_REFERRER,
                related.reasons().get(key(relatedStart)));
        assertTrue(related.protectedNodeKeys().contains(key(relatedStart)));
    }

    private static Node node(long id, double longitude) {
        Node node = new Node(new LatLon(0, longitude));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static Way way(long id, List<Node> nodes) {
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(id, 1);
        way.setModified(false);
        return way;
    }

    private static PrimitiveKey key(Node node) {
        return PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId());
    }

    private static PrimitiveKey wayKey(Way way) {
        return PrimitiveKey.existing(PrimitiveKey.Type.WAY, way.getUniqueId());
    }
}
