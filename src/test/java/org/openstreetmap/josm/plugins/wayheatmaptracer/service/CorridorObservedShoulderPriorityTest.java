package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Constructed direct-observation model control; production scalar behavior has separate adapter coverage. */
class CorridorObservedShoulderPriorityTest {
    @Test void admittedCorrespondenceIgnoresOtherwiseEligibleUnrelatedObservedPrefix() throws Exception {
        var f=CorridorPolicyFixture.admittedParabolaWithStraightPrefix();
        var type=Class.forName(CorridorEngineAdapter.class.getName()+"$OwnedInvocation");
        var ctor=type.getDeclaredConstructor(CorridorEngineAdapter.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot.class,org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.class); ctor.setAccessible(true);
        var invocation=ctor.newInstance(new CorridorEngineAdapter("native"),f.request(),f.evidence(),f.network(),CancellationProbe.NONE);
        var execute=type.getDeclaredMethod("execute"); execute.setAccessible(true); execute.invoke(invocation);
        var trackedField=type.getDeclaredField("tracked"); trackedField.setAccessible(true);
        var sampled=(CorridorAwareTracker.TrackingResult)trackedField.get(invocation); assertNotNull(sampled);
        var trackerField=type.getDeclaredField("tracker"); trackerField.setAccessible(true);
        var nativeTracker=(CorridorAwareTracker)trackerField.get(invocation);
        var certificate=CorridorSourceProtection.inspect(f.request(),f.evidence(),f.network(),"native",CancellationProbe.NONE);
        assertTrue(certificate.available()); var context=certificate.bind(CancellationProbe.NONE);
        var jm=CorridorEngineAdapter.class.getDeclaredMethod("junctionContext",TraceRequest.class,NetworkSnapshot.class,int.class); jm.setAccessible(true);
        var junctions=(JunctionContext)jm.invoke(null,f.request(),f.network(),sampled.profiles().size());
        var profiles=new ArrayList<>(sampled.profiles()); int unrelated=14; var p=profiles.get(unrelated);
        var admittedTrack=sampled.tracks().stream().filter(t->nativeTracker.correspondencePreparations().get(t.id()).admitted()).findFirst().orElseThrow();
        var originalPoint=admittedTrack.points().get(unrelated); assertNotNull(originalPoint);
        var b=originalPoint.band(); assertTrue(p.bands().contains(b));
        // Deliberately constructed complete, direct finite-width observation on the separated prefix.
        // The native scalar sampling and all admitted-fold profiles remain unchanged.
        double center=b.centerOffsetPx()+sampled.sourcePixelSizePx()/8;
        var narrow=new CorridorBand(b.id(),center,center-.025,center+.025,center-.0125,center+.0125,List.of(center),
            b.peakIntensity(),b.noiseFloor(),b.valleyRatio(),b.gradientStrength(),b.gradientBalance(),b.scaleAgreement(),
            b.signalExistenceConfidence(),b.localizationConfidence(),b.uncertaintyPx(),false,List.of());
        var bands=p.bands().stream().map(value->value.equals(b)?narrow:value).toList();
        profiles.set(unrelated,new CorridorProfile(unrelated,p.source(),bands,p.maxIntensity(),p.noiseFloor(),p.prominence(),true));
        var originals=new LinkedHashMap<String,CorridorCenterlineOptimizer.OriginalDomain>();
        CorridorAwareTracker.PreparedCorrespondencePolicy policy=d->{originals.put(d.track().id(),d);return CorridorWorkingSchedule.prepare(d,context,1,CancellationProbe.NONE);};
        var r4=CorridorAwareTracker.withTargetOwnedGapDomain(guarded(f),context,policy,25);
        var old=invoke(r4,profiles,junctions); var r5=CorridorAwareTracker.withTargetOwnedObservedShoulderDomain(guarded(f),context,policy,25);
        var current=invoke(r5,profiles,junctions);
        assertEquals(old.optimizations(),current.optimizations());
        assertEquals(old.candidates().stream().map(CenterlineCandidate::screenPoints).toList(),current.candidates().stream().map(CenterlineCandidate::screenPoints).toList());
        var admitted=r5.correspondencePreparations().entrySet().stream().filter(e->e.getValue().admitted()).toList();
        assertFalse(admitted.isEmpty(),"Control must positively admit real correspondence");
        long eligible=0;
        for(var entry:admitted) {
            var id=entry.getKey(); var admission=entry.getValue(); var domain=originals.get(id);
            assertFalse(admission.schedule().consumedRows().contains(unrelated));
            assertNotNull(domain.track().points().get(unrelated),"Constructed prefix measurement must survive native association");
            assertEquals(CorridorPointSupport.DIRECT_UNION,domain.track().points().get(unrelated).support());
            var diagnostic=CorridorObservedShoulderDomain.restrict(domain,Map.of(id,domain.track()),Optional.empty(),CancellationProbe.NONE,w->{});
            var row=diagnostic.work().rows().stream().filter(v->v.originalRow()==unrelated).findFirst().orElseThrow();
            System.out.println("PUBLIC_ADMITTED_UNRELATED_OBS track="+id+" row="+row+" admission="+admission.status()+" mapping="+admission.schedule().originalRows());
            if(row.disposition()==CorridorObservedShoulderDomain.Disposition.RESTRICTED)eligible++;
            assertEquals(r4.preparedOptimization(id).orElseThrow().geometry(),r5.preparedOptimization(id).orElseThrow().geometry());
            assertEquals(r4.preparedOptimization(id).orElseThrow().schedule().originalRows(),r5.preparedOptimization(id).orElseThrow().schedule().originalRows());
            var work=r5.correspondenceWork().get(id).preparation().observedWork().orElseThrow();
            assertEquals(CorridorObservedShoulderDomain.Disposition.SKIPPED_OWNED_CORRESPONDENCE_PRIORITY,work.outcome());
            assertEquals(0,work.admissionUnits()); assertEquals(0,work.retainedBytes());
        }
        assertTrue(eligible>0,"At least one ADMITTED track must have a genuinely eligible unrelated prefix observation");
    }
    private static CorridorCenterlineOptimizer guarded(CorridorPolicyFixture.Fixture f) {
        return new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(f.evidence().transform(),CancellationProbe.NONE);
    }
    private static CorridorAwareTracker.TrackingResult invoke(CorridorAwareTracker tracker,List<CorridorProfile> profiles,JunctionContext junctions) throws Exception {
        var method=Arrays.stream(CorridorAwareTracker.class.getDeclaredMethods()).filter(m->m.getName().equals("trackExtracted")).findFirst().orElseThrow();
        method.setAccessible(true);
        try{return (CorridorAwareTracker.TrackingResult)method.invoke(tracker,profiles,1.0,junctions,"native",GeometryCleanupConfig.disabled(),1.0,
            Map.of(),List.of(),0L,0L,TraceBudgets.defaults());}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
    }
}
