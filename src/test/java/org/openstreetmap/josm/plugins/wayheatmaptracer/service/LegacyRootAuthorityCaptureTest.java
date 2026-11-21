package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class LegacyRootAuthorityCaptureTest {
    @BeforeAll static void setup() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }
    @Test void explicitRootCaptureRetainsOptedInInteriorSharedMovementAndUnfrozenPartition() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = fixture(true);
            try (var memory = AttemptMemoryLedger.production().rootOwner()) {
                var seed = new LiveBPreviewService().captureLegacyRootSeed(f.data(), f.selection(), f.config(),
                        10.114, 1.56, "original-legacy-test", memory).value();
                var shared = PrimitiveKey.existing(PrimitiveKey.Type.NODE, f.selection().segmentNodes().get(2).getUniqueId());
                assertTrue(seed.network().closure().movableExistingNodeKeys().contains(shared), "Original opt-in rights must precede refinement restrictions");
                assertFalse(seed.network().closure().protectedExistingNodeKeys().contains(shared));
                assertNotNull(seed.partition());
                assertTrue(seed.partition().fixedIslands().stream().anyMatch(i -> i.occurrenceKeys().contains(shared)));
                assertFalse(f.data().isModified());
            }
        });
    }
    @Test void optedOutSharedOccurrenceCannotGainMovementAtBootstrap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = fixture(false);
            try (var memory = AttemptMemoryLedger.production().rootOwner()) {
                var seed = new LiveBPreviewService().captureLegacyRootSeed(f.data(), f.selection(), f.config(),
                        10.114, 1.56, "original-legacy-test", memory).value();
                var shared = PrimitiveKey.existing(PrimitiveKey.Type.NODE, f.selection().segmentNodes().get(2).getUniqueId());
                assertFalse(seed.network().closure().movableExistingNodeKeys().contains(shared));
                assertTrue(seed.network().closure().protectedExistingNodeKeys().contains(shared));
            }
        });
    }
    @Test void rootSnapshotAdmissionRefusesBeforeCaptureWithAnInsufficientLedger() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = fixture(true);
            try (var memory = new AttemptMemoryLedger(1024).rootOwner()) {
                assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> new LiveBPreviewService().captureLegacyRootSeed(f.data(), f.selection(), f.config(),
                        10.114, 1.56, "original-legacy-test", memory));
                assertFalse(f.data().isModified());
            }
        });
    }
    private record Fixture(DataSet data, SelectionContext selection, AlignmentConfig config) { }
    private static Fixture fixture(boolean adjust) {
        DataSet data = new DataSet(); var nodes = new ArrayList<Node>();
        for (int i=0;i<5;i++) {var n=new Node(new LatLon(50,14+(i-2)*.0001)); data.addPrimitive(n); nodes.add(n);}
        Way way=new Way();way.setNodes(nodes);way.put("highway","path");data.addPrimitive(way);
        Node north=new Node(new LatLon(50.0002,14));data.addPrimitive(north);
        Way branch=new Way();branch.setNodes(List.of(nodes.get(2),north));branch.put("highway","path");data.addPrimitive(branch);
        data.getNodes().forEach(n->n.setModified(false));data.getWays().forEach(w->w.setModified(false));data.setSelected(way);
        var config = new ManagedHeatmapConfig("k","p","s","t","all","hot","",".*",AlignmentMode.PRECISE_SHAPE,
            TrackerMode.LEGACY_V02,false,false,false,false,false,false,false,false,adjust,false,7,4,3,
            InferenceMode.RAW_HIGH_RESOLUTION,15,15,10,1.56,IntensitySamplingMode.COLOR_MAPPING,7);
        return new Fixture(data,SelectionResolver.resolve(data,adjust),AlignmentConfig.withoutCleanup(config));
    }
}
