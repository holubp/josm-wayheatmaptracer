package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;

/** Calls the actual common planner against public metadata only; no provider/renderer is invoked. */
class V022BProspectiveExtractionPlanTest {
    @Test void completeExtractPlansExactlyOriginalTrainAndRegressionWithZeroObservationReads() throws Exception {
        var catalog=publicCatalog();var expected=catalog.definitions().stream()
                .filter(d->d.key().split()==Split.TRAIN||d.key().split()==Split.REGRESSION).toList();
        var plan=plan(catalog,Phase.EXTRACT,null);
        assertEquals(132,plan.size());assertEquals(112,plan.stream().filter(d->d.key().split()==Split.TRAIN).count());
        assertEquals(20,plan.stream().filter(d->d.key().split()==Split.REGRESSION).count());
        assertEquals(plan.size(),plan.stream().map(d->d.key().id()).distinct().count());
        assertEquals(new HashSet<>(expected),new HashSet<>(plan));
        var access=EvaluationAccess.preselection(Phase.EXTRACT);
        for(var definition:plan)assertDoesNotThrow(()->access.requireKey(definition.key()));
        assertEquals(0,access.rasterReads());assertEquals(0,access.labelReads());
    }
    @Test void requestedHoldoutRetiredAndFreshSelectIdentitiesRefuseAtPlanningBeforeAcquisition() throws Exception {
        var catalog=publicCatalog();String train=catalog.definitions().stream()
                .filter(d->d.key().split()==Split.TRAIN).findFirst().orElseThrow().key().id();
        var inventory=catalog.inventory(EvaluationAccess.catalogView());var access=EvaluationAccess.preselection(Phase.EXTRACT);
        for(var split:List.of(Split.HOLDOUT,Split.INELIGIBLE_DIAGNOSTIC,Split.SELECT)) {
            String forbidden=inventory.stream().filter(item->item.split()==split).findFirst().orElseThrow().id();
            assertThrows(SecurityException.class,()->plan(catalog,Phase.EXTRACT,List.of(forbidden)));
            assertThrows(SecurityException.class,()->plan(catalog,Phase.EXTRACT,List.of(train,forbidden)));
            assertThrows(SecurityException.class,()->catalog.keyForMaterialization(forbidden,access));
        }
        assertEquals(List.of(train),plan(catalog,Phase.EXTRACT,List.of(train)).stream().map(d->d.key().id()).toList());
        assertEquals(0,access.rasterReads());assertEquals(0,access.labelReads());
    }
    @Test void catalogAndFitPlansAndAccessGuardsRetainTheirExistingCohorts() throws Exception {
        var catalog=publicCatalog();assertEquals(468,catalog.caseCount());assertEquals(468,catalog.inventory(EvaluationAccess.catalogView()).size());
        assertEquals(244,plan(catalog,Phase.CATALOG,null).size());
        assertEquals(new HashSet<>(catalog.definitions()),new HashSet<>(plan(catalog,Phase.CATALOG,null)));
        var expected=catalog.definitions().stream().filter(d->d.key().split()==Split.TRAIN||d.key().split()==Split.SELECT).toList();
        var fit=plan(catalog,Phase.FIT_SELECT,null);assertEquals(224,fit.size());assertEquals(new HashSet<>(expected),new HashSet<>(fit));
        var access=EvaluationAccess.preselection(Phase.EXTRACT);
        for(var definition:catalog.definitions())if(definition.key().split()!=Split.TRAIN&&definition.key().split()!=Split.REGRESSION)
            assertThrows(SecurityException.class,()->access.requireKey(definition.key()));
        assertEquals(0,access.rasterReads());assertEquals(0,access.labelReads());
    }
    @SuppressWarnings("unchecked")
    private static List<CaseDefinition> plan(BIdentificationCatalog catalog,Phase phase,List<String> ids) throws Exception {
        var method=BObservationIdentificationTool.class.getDeclaredMethod("definitions",BIdentificationCatalog.class,Phase.class,List.class);
        method.setAccessible(true);
        try{return (List<CaseDefinition>)method.invoke(null,catalog,phase,ids);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception cause)throw cause;throw failure;}
    }
}
