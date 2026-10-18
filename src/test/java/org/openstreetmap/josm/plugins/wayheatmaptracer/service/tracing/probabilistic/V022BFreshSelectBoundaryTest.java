package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Metadata-only refusal tests: no raster or label from fresh SELECT is opened. */
class V022BFreshSelectBoundaryTest {
    @Test void extractCapabilityRefusesFreshSelectBeforeReadCountersAdvance() {
        var catalog=BIdentificationCatalog.publicCatalog();
        var key=catalog.definitions().stream()
                .filter(d->d.key().split()==BIdentificationCatalog.Split.SELECT).findFirst().orElseThrow().key();
        var access=BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.EXTRACT);
        assertThrows(SecurityException.class,()->catalog.keyForMaterialization(key.id(),access));
        assertThrows(SecurityException.class,()->access.beforeMaterialization(key));
        assertEquals(0,access.rasterReads());
        assertEquals(0,access.labelReads());
    }
    @Test void defaultExtractInventoryContainsOnlyTrainAndRegression() throws Exception {
        var definitions=definitions(BIdentificationCatalog.Phase.EXTRACT,null);
        assertEquals(132,definitions.size());
        assertTrue(definitions.stream().allMatch(d->d.key().split()==BIdentificationCatalog.Split.TRAIN
                ||d.key().split()==BIdentificationCatalog.Split.REGRESSION));
    }
    @Test void everySubsetRouteRefusesFreshSelectWithoutMaterialization() {
        String id=BIdentificationCatalog.publicCatalog().definitions().stream()
                .filter(d->d.key().split()==BIdentificationCatalog.Split.SELECT).findFirst().orElseThrow().key().id();
        for(var phase:List.of(BIdentificationCatalog.Phase.CATALOG,BIdentificationCatalog.Phase.EXTRACT,
                BIdentificationCatalog.Phase.FIT_SELECT)) {
            var failure=assertThrows(InvocationTargetException.class,()->definitions(phase,List.of(id)));
            assertInstanceOf(SecurityException.class,failure.getCause());
        }
    }
    @Test void fullFitRetainsAllOriginalTrainAndFreshSelectMetadata() throws Exception {
        var definitions=definitions(BIdentificationCatalog.Phase.FIT_SELECT,null);
        assertEquals(224,definitions.size());
        assertEquals(112,definitions.stream().filter(d->d.key().split()==BIdentificationCatalog.Split.TRAIN).count());
        assertEquals(112,definitions.stream().filter(d->d.key().split()==BIdentificationCatalog.Split.SELECT).count());
    }
    @SuppressWarnings("unchecked")
    private static List<BIdentificationCatalog.CaseDefinition> definitions(BIdentificationCatalog.Phase phase,
            List<String> subset) throws Exception {
        var method=BObservationIdentificationTool.class.getDeclaredMethod("definitions",BIdentificationCatalog.class,
                BIdentificationCatalog.Phase.class,List.class);
        method.setAccessible(true);
        return (List<BIdentificationCatalog.CaseDefinition>)method.invoke(null,BIdentificationCatalog.publicCatalog(),phase,subset);
    }
}
