package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.*;

class LegacyCeilingPublicationTest {
    @TempDir Path cache;
    static final String CENSUS="EXACT_Q0_CEILING_CENSUS_V1";
    // R0: the second original-row charge follows checking row0. R1: later ticket prepayment
    // follows the admitted inventory; the complete callback-free final scan must still see row0.
    static boolean lateCheckCharge(String publication,AtomicInteger oldRows,AtomicInteger ticketCharges) {
        var stack=Arrays.asList(Thread.currentThread().getStackTrace());
        if(stack.stream().noneMatch(s->s.getMethodName().equals(publication)))return false;
        int charge=-1;for(int i=0;i+1<stack.size();i++)if(stack.get(i).getClassName().equals(EmissionWorkBudget.class.getName())&&stack.get(i).getMethodName().equals("charge")){charge=i;break;}
        if(charge<0)return false;var caller=stack.get(charge+1);
        if(caller.getMethodName().equals("prepareManagedFinalPublication"))return ticketCharges.incrementAndGet()==2;
        return caller.getClassName().equals(LegacyEmissionBinding.class.getName())&&caller.getMethodName().equals("requireNativeProfilesUnchanged")&&oldRows.incrementAndGet()==2;
    }

    @Test void laterValidationChargeCannotPublishAnEarlierMutatedOriginalRow() throws Exception {
        for(String site:List.of("ceilingWitness","ceilingObservation")) {
            var armed=new AtomicBoolean();var fired=new AtomicBoolean();var rows=new AtomicInteger();var tickets=new AtomicInteger();
            LegacyCeilingTicketFixture.Managed[] fixture={null};
            var budget=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
                if(armed.get()&&!fired.get()&&lateCheckCharge(site,rows,tickets)) {
                    fired.set(true);var normal=fixture[0].profiles().get(0).normalScreen();normal.x=Math.nextUp(normal.x);
                }return false;
            });
            LegacyCeilingTicketFixture.managed(cache,budget,f->{try(var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
                fixture[0]=f;long bytes=f.ledger().currentBytes();armed.set(true);
                assertThrows(IllegalStateException.class,()->LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0),List.of(),(a,b,c)->b,CENSUS,p));
                assertTrue(fired.get(),site);assertEquals(bytes,f.ledger().currentBytes());
                assertEquals(0,p.view().ceilingTotalCount(CeilingCounter.CANDIDATES));
                if(site.equals("ceilingWitness")){assertEquals(0,p.view().count(Counter.WITNESS_CERTIFIED));assertFalse(p.view().hasCeilingWitness());}
                else assertTrue(p.view().hasCeilingWitness());
            }});
        }
    }

    @Test void sealedOrClosedObserverCannotBeTaxedOrResealedDuringValidation() throws Exception {
        for(String boundary:List.of("charge","supplier","prepayment"))for(boolean seal:List.of(false,true))for(boolean terminal:List.of(false,true)) {
            var armed=new AtomicBoolean();var fired=new AtomicBoolean();var rows=new AtomicInteger();var tickets=new AtomicInteger();
            LegacyEmissionProgress[] progress={null};EmissionWorkBudget[] current={null};var frozen=new AtomicReference<Map<String,Object>>();
            var primary=new CancellationException("synthetic census validation cancellation");
            Runnable revoke=()->{
                if(!fired.compareAndSet(false,true))return;
                if(seal)progress[0].seal(primary);else progress[0].close();
                frozen.set(fields(progress[0]));if(terminal)current[0].failTerminally(primary);
            };
            current[0]=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
                if(!armed.get()||fired.get())return false;
                boolean matched=boundary.equals("charge")&&lateCheckCharge("ceilingWitness",rows,tickets);
                if(boundary.equals("prepayment")) {
                    var stack=Arrays.asList(Thread.currentThread().getStackTrace());
                    matched=stack.stream().anyMatch(s->s.getMethodName().equals("ceilingWitness"))&&stack.stream().anyMatch(s->s.getMethodName().equals("ceilingCharge"));
                }
                if(matched)revoke.run();return false;
            });
            LegacyCeilingTicketFixture.managed(cache,current[0],f->{try(var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
                progress[0]=p;
                if(boundary.equals("supplier"))f.settingsHook().set(()->{
                    var stack=Arrays.asList(Thread.currentThread().getStackTrace());
                    if(armed.get()&&stack.stream().anyMatch(s->s.getMethodName().equals("ceilingWitness"))
                            &&stack.stream().anyMatch(s->s.getClassName().endsWith("ManagedCaptureBinding$FinalPublicationCheck")&&s.getMethodName().equals("validate")))revoke.run();
                });
                long bytes=f.ledger().currentBytes();armed.set(true);
                var failure=assertThrows(RuntimeException.class,()->LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0),List.of(),(a,b,c)->b,CENSUS,p));
                assertTrue(fired.get());assertFields(frozen.get(),fields(p));
                if(terminal){assertSame(primary,failure,boundary+" seal="+seal);assertSame(primary,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));}
                if(seal)assertEquals(Termination.CANCELLED,p.view().termination());else assertThrows(IllegalStateException.class,p::view);
                assertEquals(bytes-(seal?0:STORAGE_BYTES),f.ledger().currentBytes());
            }});
        }
    }

    @Test void ticketResourceRefusalsRetainEveryAdmittedOperationAndOriginalPrimary()throws Exception {
        for(int kind=0;kind<3;kind++) {
            int fault=kind;var armed=new AtomicBoolean();var fired=new AtomicBoolean();var rows=new AtomicInteger();var tickets=new AtomicInteger();
            EmissionWorkBudget[] budget={null};LegacyCeilingTicketFixture.Managed[] fixture={null};LegacyEmissionProgress[] progress={null};
            var beforeOps=new AtomicReference<Map<EmissionWorkBudget.Operation,Long>>();var beforeTax=new AtomicReference<long[]>();
            budget[0]=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
                if(!armed.get()||fired.get())return false;
                var stack=Arrays.asList(Thread.currentThread().getStackTrace());
                if(stack.stream().noneMatch(s->s.getMethodName().equals("ceilingWitness"))||stack.stream().noneMatch(s->s.getMethodName().equals("prepareManagedFinalPublication")))return false;
                if(beforeOps.get()==null){beforeOps.set(budget[0].snapshot().operationUnits());beforeTax.set((long[])fields(progress[0]).get("tax"));}
                if(!lateCheckCharge("ceilingWitness",rows,tickets))return false;fired.set(true);
                if(fault==0)budget[0].charge(EmissionWorkBudget.Operation.PREPARATION,budget[0].workLimit()+1);
                if(fault==1)fixture[0].owner().reserve(Long.MAX_VALUE);
                return fault==2;
            });
            LegacyCeilingTicketFixture.managed(cache,budget[0],f->{try(var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
                fixture[0]=f;progress[0]=p;long bytes=f.ledger().currentBytes();armed.set(true);
                var failure=assertThrows(RuntimeException.class,()->LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0),List.of(),(a,b,c)->b,CENSUS,p));
                assertTrue(fired.get());assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));
                assertEquals(bytes,f.ledger().currentBytes());assertEquals(0,p.view().count(Counter.WITNESS_CERTIFIED));
                for(var op:EmissionWorkBudget.Operation.values())assertEquals(p.view().operationUnits(op)-beforeOps.get().get(op),
                    p.view().diagnosticUnits(op)-beforeTax.get()[op.ordinal()],op.name());
            }});
        }
    }

    static Map<String,Object> fields(LegacyEmissionProgress p) {
        var result=new LinkedHashMap<String,Object>();
        try {
            for(var field:LegacyEmissionProgress.class.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);var value=field.get(p);
                if(value instanceof long[] a)value=a.clone();else if(value instanceof int[] a)value=a.clone();result.put(field.getName(),value);
            }
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        return result;
    }
    static void assertFields(Map<String,Object> before,Map<String,Object> after) {
        assertEquals(before.keySet(),after.keySet());
        before.forEach((name,a)->{var b=after.get(name);if(a instanceof long[] x)assertArrayEquals(x,(long[])b,name);
            else if(a instanceof int[] x)assertArrayEquals(x,(int[])b,name);else assertEquals(a,b,name);});
    }
}
