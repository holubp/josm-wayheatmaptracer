package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

class LongScaleSeedProvenanceTest {
    @Test void derivedNonMidpointOccurrenceReceivesDisplacementWithoutBecomingAnObservation() {
        var p = List.of(new MetricPoint(0,0),new MetricPoint(3,7),new MetricPoint(5,1),
                new MetricPoint(8,9),new MetricPoint(10,0));
        var basis = ModernTracePipeline.controlProvenance(p,List.of(0.,.3,.5,.8,1.),
                Map.of(0,0,1,2,2,4),3,Set.of(0,4)).basis().orElseThrow();
        var controls = new java.util.ArrayList<>(p); controls.set(2,new MetricPoint(5,11));
        var lifted = basis.lift(controls);
        assertEquals(13.,lifted.get(1).yMeters(),1e-12);
        assertEquals(13.,lifted.get(3).yMeters(),1e-12);
        assertFalse(basis.protectedIndices().contains(2));
        assertTrue(basis.rows().get(2).independent());
    }
    @Test void nativeAliasRemainsIndependentAndInsertedOccurrenceUsesRecordedNonMidpointWeight() {
        var p = List.of(new MetricPoint(0,0), new MetricPoint(3,7), new MetricPoint(10,0));
        var provenance = ModernTracePipeline.controlProvenance(p, List.of(0., .3, 1.),
                Map.of(0,0, 1,2), 2, Set.of(0,2));
        var basis = provenance.basis().orElseThrow();
        assertEquals(List.of(0,2), basis.independentIndices());
        assertEquals(.3, basis.rows().get(1).weight());
        assertEquals(p, basis.lift(p));
        assertEquals(Map.of(0,0, 1,2), provenance.nativeAliases());
    }

    @Test void retainedMovableIsNotFixedButProtectedInsertedAnchorSplitsAncestry() {
        var p = List.of(new MetricPoint(0,0), new MetricPoint(2,0), new MetricPoint(5,8),
                new MetricPoint(8,0), new MetricPoint(10,0));
        var result = ModernTracePipeline.controlProvenance(p, List.of(0.,.2,.5,.8,1.),
                Map.of(0,0,1,4), 2, Set.of(0,2,4));
        var basis = result.basis().orElseThrow();
        assertEquals(List.of(0,2,4), basis.independentIndices());
        assertEquals(2, basis.rows().get(1).rightIndependent());
        assertEquals(2, basis.rows().get(3).leftIndependent());
        assertEquals(p, basis.lift(p));
    }

    @Test void duplicateFractionsAndMissingNativeMappingRefuseWithoutInventingAncestry() {
        var p = List.of(new MetricPoint(0,0),new MetricPoint(1,1),new MetricPoint(2,0));
        var duplicate = ModernTracePipeline.controlProvenance(p,List.of(0.,0.,1.),
                Map.of(0,0,1,2),2,Set.of(0,2));
        assertTrue(duplicate.basis().isEmpty());
        assertEquals(ModernTracePipeline.ProvenanceFailure.AMBIGUOUS_FRACTION, duplicate.failure());
        var missing = ModernTracePipeline.controlProvenance(p,List.of(0.,.5,1.),
                Map.of(0,0),2,Set.of(0,2));
        assertTrue(missing.basis().isEmpty());
        assertEquals(ModernTracePipeline.ProvenanceFailure.MISSING_NATIVE_ALIAS, missing.failure());
        var reordered = ModernTracePipeline.controlProvenance(p,List.of(0.,.5,1.),
                Map.of(0,2,1,0),2,Set.of(0,2));
        assertTrue(reordered.basis().isEmpty());
    }
}
