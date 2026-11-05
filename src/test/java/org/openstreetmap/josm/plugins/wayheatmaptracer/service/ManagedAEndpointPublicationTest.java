package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class ManagedAEndpointPublicationTest {
    @TempDir Path cache;
    @ParameterizedTest @ValueSource(strings={"refine-close","refine-cancel","prefix-close","prefix-resource","prefix-last-close","prefix-last-cancel"})
    void finalSupplierCannotPublishOrMutateARevokedCensus(String kind)throws Exception{
        var helper=new ManagedAEndpointProducerTest();helper.cache=cache;
        helper.withCapture(false,true,.25,CancellationProbe.NONE,f->{
            var current=new AtomicReference<NativeAExecutionCensus>();var fired=new AtomicBoolean();
            var primary=new AtomicReference<RuntimeException>();var snapshot=new AtomicReference<List<Object>>();
            var published=new AtomicReference<CorridorAwareTracker.OptionalEmissionTracking>();
            try(var permit=ManagedAEndpointProducerTest.bind(f,()->{
                var c=current.get();
                if(c!=null&&!fired.get()&&finalSupplier()
                        &&!stack("NativeAEmissionSolver","solve")
                        &&(kind.contains("-last-")?!stack("CorridorAwareTracker","trackOptionalEmissionBound"):stack("CorridorAwareTracker","trackOptionalEmissionBound"))){
                    fired.set(true);c.close();snapshot.set(snapshot(c));
                    if(kind.endsWith("cancel")){var failure=new CancellationException("synthetic final supplier");primary.set(failure);throw failure;}
                    if(kind.endsWith("resource")){
                        try{f.owner().reserve(f.ledger().limitBytes()-f.ledger().currentBytes()+1);throw new AssertionError("Pressure must refuse");}
                        catch(AttemptMemoryLedger.ResourceLimitException failure){primary.set(failure);throw failure;}
                    }
                }
                return f.settings();
            });var input=ManagedAEndpointProducerTest.produce(f,permit)){
                long baseline=f.ledger().currentBytes();
                try(var census=NativeAExecutionCensus.open(input)){
                    current.set(census);
                    var choice=kind.startsWith("refine")?NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_REFINEMENT_V1
                        :NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_PREFIX_DOMINANCE_V1;
                    try{
                        var failure=assertThrows(RuntimeException.class,()->published.set(ManagedAEndpointExecutionTest.invoke(input,choice,census)));
                        assertTrue(fired.get(),"Actual outer final supplier must run");
                        if(primary.get()!=null){assertSame(primary.get(),failure);assertSame(failure,assertThrows(RuntimeException.class,input::requireOpenAndUnchanged));}
                        assertEquals(snapshot.get(),snapshot(census),"A revoked census is immutable to this failed owner");
                        assertNull(published.get());assertEquals(baseline,f.ledger().currentBytes(),"Every candidate/engine/lease must be released");
                    }finally{if(published.get()!=null)published.get().close();}
                }
            }
        });
    }
    static boolean finalSupplier(){return Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f->f.getClassName().contains("ManagedCaptureBinding$FinalPublicationCheck")&&f.getMethodName().equals("validate"));}
    static boolean stack(String type,String method){return Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f->f.getClassName().endsWith(type)&&f.getMethodName().equals(method));}
    static List<Object> snapshot(NativeAExecutionCensus c){
        try{var values=new ArrayList<Object>();for(var field:NativeAExecutionCensus.class.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())){
            field.setAccessible(true);values.add(field.get(c));}return Collections.unmodifiableList(values);
        }catch(IllegalAccessException e){throw new AssertionError(e);}
    }
}
