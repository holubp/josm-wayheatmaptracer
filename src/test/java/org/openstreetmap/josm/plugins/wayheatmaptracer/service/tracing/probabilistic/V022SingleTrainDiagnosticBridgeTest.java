package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022SingleTrainDiagnosticBridgeTest {
    static BIdentificationCatalog.CaseDefinition definition() {
        return BIdentificationCatalog.trainDiagnosticCatalog().definitions().stream()
                .filter(d->d.key().id().equals(BSingleTrainDiagnosticManifest.CASE)).findFirst().orElseThrow();
    }
    static BIdentificationCatalog.Scene toy() throws Exception {
        var method=BObservationIdentificationTool.class.getDeclaredMethod("fixtureScene",BIdentificationCatalog.CaseDefinition.class,CancellationProbe.class);
        method.setAccessible(true);
        return (BIdentificationCatalog.Scene)method.invoke(null,definition(),CancellationProbe.NONE);
    }
    @Test void fixedPrivateAssemblyAndCoordinateCarrierMatchActualAdapterOnToyRaster() throws Exception {
        var calls=new AtomicInteger();var controls=BObservationIdentificationTool.Controls.defaults();
        var loaded=BSingleTrainDiagnosticBridge.open(definition(),"a".repeat(64),"b".repeat(64),"c".repeat(64),()->{},
                d->{calls.incrementAndGet();return toy();},controls);
        try(loaded) {
            var rows=loaded.coordinates(CancellationProbe.NONE);
            for(var parameters:List.of(new BIdentifiedObservationKernel.Parameters(1,1,0),new BIdentifiedObservationKernel.Parameters(1,1,1)))
                for(int i=0;i<loaded.profiles.size();i++)try(var owner=loaded.owner.child("adapter-parity")) {
                    var actual=BObservationAdapter.evaluate(loaded.facts.get(i),loaded.admitted.lattices().get(i),
                            EvidenceModelParameters.defaults(),true,new BIdentifiedObservationKernel(parameters),CancellationProbe.NONE,owner);
                    assertEquals(actual.cells().size(),rows.get(i).size());
                    for(int s=0;s<actual.cells().size();s++) {
                        assertEquals(Double.doubleToRawLongBits(actual.point(s).xMeters()),Double.doubleToRawLongBits(rows.get(i).get(s).xMeters()));
                        assertEquals(Double.doubleToRawLongBits(actual.point(s).yMeters()),Double.doubleToRawLongBits(rows.get(i).get(s).yMeters()));
                    }
                }
            assertEquals(1,calls.get());assertTrue(loaded.ledger.currentBytes()>0);
        }
        assertEquals(0,loaded.ledger.currentBytes());
    }
    @Test void guardFailureIsPreservedAndStopsBeforeTheProvider() {
        var calls=new AtomicInteger();var sentinel=new IllegalStateException("fixed sentinel");
        var failure=assertThrows(IllegalStateException.class,()->BSingleTrainDiagnosticBridge.open(definition(),"a","b","c",
                ()->{throw sentinel;},d->{calls.incrementAndGet();return toy();},BObservationIdentificationTool.Controls.defaults()));
        assertSame(sentinel,failure);assertEquals(0,calls.get());
    }
    @Test void curvedNormalsSignedZeroAnchorsAndUnequalRowsKeepActualBaselineAndAdapterPointBits() throws Exception {
        var loaded=BSingleTrainDiagnosticBridge.open(definition(),"a".repeat(64),"b".repeat(64),"c".repeat(64),()->{},d->{
            var scene=toy();return new BIdentificationCatalog.Scene(scene.evidence(),List.of(
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(0,-0.0),
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(3,2),
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(6,+0.0)),scene.truth());
        },BObservationIdentificationTool.Controls.defaults());
        try(loaded) {
            var rows=loaded.coordinates(CancellationProbe.NONE);
            assertEquals(1,rows.get(0).size());assertEquals(1,rows.get(rows.size()-1).size());
            assertTrue(rows.stream().anyMatch(r->r.size()>1));assertTrue(loaded.profiles.stream().anyMatch(p->p.normalUnit().xMeters()!=0));
            for(int i=0;i<rows.size();i++)try(var owner=loaded.owner.child("curved-coordinate-parity")) {
                var baseline=new ProbabilisticObservationModel().evaluate(loaded.profiles.get(i),loaded.admitted.lattices().get(i),EvidenceModelParameters.defaults(),true,owner);
                var adapter=BObservationAdapter.evaluate(loaded.facts.get(i),loaded.admitted.lattices().get(i),EvidenceModelParameters.defaults(),true,
                        new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(1,1,0)),CancellationProbe.NONE,owner);
                for(int s=0;s<rows.get(i).size();s++)for(var actual:List.of(baseline,adapter)) {
                    assertEquals(Double.doubleToRawLongBits(actual.point(s).xMeters()),Double.doubleToRawLongBits(rows.get(i).get(s).xMeters()));
                    assertEquals(Double.doubleToRawLongBits(actual.point(s).yMeters()),Double.doubleToRawLongBits(rows.get(i).get(s).yMeters()));
                }
            }
        }
        assertEquals(0,loaded.ledger.currentBytes());
    }
}
