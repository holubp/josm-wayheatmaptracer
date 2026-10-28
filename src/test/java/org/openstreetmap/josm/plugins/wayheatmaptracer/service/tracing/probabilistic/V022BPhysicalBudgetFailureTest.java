package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022BPhysicalBudgetFailureTest {
    @Test void checkedArithmeticAndMemoryPredicatesRemainDistinctAndFailClosed() throws Exception {
        //Pure numeric envelope control; never a source, publication or K permit.
        var type=Class.forName(BPhysicalPairTable.class.getName()+"$Budget");
        var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);var budget=constructor.newInstance();
        var failure=type.getDeclaredMethod("fitFailure",int.class);failure.setAccessible(true);
        var fits=type.getDeclaredMethod("fits",int.class);fits.setAccessible(true);
        set(budget,"semanticBase",Long.MAX_VALUE);set(budget,"helperBase",1);
        assertEquals(BPhysicalPairTable.JointBudgetReason.ARITHMETIC_ENVELOPE_OVERFLOW,failure.invoke(budget,1));
        assertEquals(false,fits.invoke(budget,1));
        set(budget,"semanticBase",0);set(budget,"helperBase",0);set(budget,"memoryBase",(64L<<20)+1);
        assertEquals(BPhysicalPairTable.JointBudgetReason.MEMORY_ENVELOPE_LIMIT,failure.invoke(budget,1));
        assertEquals(false,fits.invoke(budget,1));
        set(budget,"memoryBase",64L<<20);assertNull(failure.invoke(budget,1));assertEquals(true,fits.invoke(budget,1));
    }
    private static void set(Object owner,String name,long value) throws Exception {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.setLong(owner,value);
    }
    @Test void actualMemoryRefusalRetainsSafeTypedFactsAfterCleanup() throws Exception {
        BPhysicalWork[] active={null};boolean[] armed={false},pressured={false};
        CancellationProbe probe=()->{
            var w=active[0];if(!armed[0]||w==null||pressured[0])return false;
            var r=w.tableRegistry();if(r!=null&&r.actual(BPhysicalTableRegistry.BASELINE,1)==1) {
                w.owner().reserve((64L<<20)-w.owner().currentBytes()-128);pressured[0]=true;
            }
            return false;
        };
        RuntimeException failure;
        try(var w=BPhysicalWork.createExploratory(probe)) {
            active[0]=w;var f=V022BPhysicalPairTableTest.fixture(w,false,2);
            var ps=java.util.List.of(f.profiles().get(0));
            var ls=ps.stream().map(p->V022BPhysicalPairTableTest.nativeLattice(w,p)).toList();
            var publication=BPhysicalPairTable.unitPublicationBounds(w,2);armed[0]=true;
            failure=assertThrows(IllegalStateException.class,()->BPhysicalPairTable.build(w,f.snapshot(),"scalar",ps,ls,
                    EvidenceModelParameters.defaults(),4,publication));
            assertTrue(pressured[0]);assertEquals(0,w.tableRegistry().actual(BPhysicalTableRegistry.EDGE,0));
        }
        assertEquals("JointBudgetFailure",failure.getClass().getSimpleName(),"memory envelope failure must be distinguishable after rollback");
        var method=failure.getClass().getDeclaredMethod("diagnostics");method.setAccessible(true);
        var facts=(java.util.Map<?,?>)method.invoke(failure);
        assertEquals("MEMORY_ENVELOPE_LIMIT",facts.get("reason"));assertEquals(1,facts.get("requestedCapacity"));
        assertEquals(64L<<20,facts.get("memoryLimitBytes"));
        for(var value:facts.values())assertTrue(value instanceof String||value instanceof Number,"no source references or arbitrary messages");
        assertThrows(UnsupportedOperationException.class,facts::clear);
    }
}
