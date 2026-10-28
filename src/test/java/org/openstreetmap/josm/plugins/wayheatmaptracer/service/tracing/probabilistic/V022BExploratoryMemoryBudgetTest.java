package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Explicit partial accounting only; authentic tiny sources, no runtime authority. */
class V022BExploratoryMemoryBudgetTest {
    @Test void deferredEnvelopeOverflowDiffersFromQualifiedAdmission() throws Exception {
        //Pure numeric predicate, not an accepted source or a forged permit.
        var type=Class.forName(BPhysicalPairTable.class.getName()+"$Budget");
        var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);var budget=constructor.newInstance();
        set(budget,"semanticBase",Long.MAX_VALUE);set(budget,"helperBase",1);set(budget,"memoryBase",1L<<20);set(budget,"pairs",1);
        var failure=type.getDeclaredMethod("fitFailure",int.class,BPhysicalWork.class);failure.setAccessible(true);
        try(var qualified=BPhysicalWork.create(CancellationProbe.NONE);var exploratory=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            qualified.claimTableRegistry();exploratory.claimTableRegistry();
            assertEquals(BPhysicalPairTable.JointBudgetReason.ARITHMETIC_ENVELOPE_OVERFLOW,failure.invoke(budget,1,qualified));
            assertNull(failure.invoke(budget,1,exploratory),"actual memory fits despite a deferred prospective sum overflow");
            set(budget,"memoryBase",(64L<<20)-BPhysicalAdaptiveIntegral.poolBytes(1)+1);
            assertEquals(BPhysicalPairTable.JointBudgetReason.MEMORY_ENVELOPE_LIMIT,failure.invoke(budget,1,exploratory));
        }
    }
    @Test void actualPoolCapacityIsMaximalUnderTheUnchangedMemoryEnvelope() throws Exception {
        var w=BPhysicalWork.createExploratory(CancellationProbe.NONE);var owner=w.owner();
        try(w;var source=BSharedFeasibilityInputs.tiny(w);
            var table=BPhysicalPairTable.buildExploratory(w,source,EvidenceModelParameters.defaults(),1)) {
            var budget=raw(table,"budget");long base=(long)raw(budget,"memoryBase");int k=table.capacity();
            assertTrue((long)raw(budget,"pairs")>0);
            assertTrue(base+BPhysicalAdaptiveIntegral.poolBytes(k)<=64L<<20);
            assertTrue(base+BPhysicalAdaptiveIntegral.poolBytes(k+1)>64L<<20,"K is selected from memory, not a fixed proof-derived number");
            assertTrue(owner.currentBytes()<=64L<<20);assertTrue(table.nodes()<k);
            System.out.println("EXPLORATORY_MEMORY K="+k+" base="+base+" pool="+BPhysicalAdaptiveIntegral.poolBytes(k));
        }
        assertEquals(0,owner.currentBytes());
    }
    @Test void actualOneByteShortMemoryStillRefusesAndClosesEverything() throws Exception {
        long future;
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE);var source=BSharedFeasibilityInputs.tiny(w);
            var table=BPhysicalPairTable.buildExploratory(w,source,EvidenceModelParameters.defaults(),1)) {
            var budget=raw(table,"budget");future=(long)raw(budget,"memoryBase")-(long)raw(budget,"current")+BPhysicalAdaptiveIntegral.poolBytes(1);
        }
        BPhysicalWork[] active={null};boolean[] armed={false},pressured={false};
        CancellationProbe probe=()->{
            if(!armed[0]||pressured[0])return false;
            for(var frame:Thread.currentThread().getStackTrace())if(frame.getClassName().endsWith("BPhysicalPairTable$Budget")
                    &&frame.getMethodName().equals("bindExploratoryMemory")) {
                var w=active[0];long bytes=(64L<<20)-w.owner().currentBytes()-future+1;
                assertTrue(bytes>0);w.owner().reserve(bytes);pressured[0]=true;break;
            }
            return false;
        };
        var w=BPhysicalWork.createExploratory(probe);var owner=w.owner();
        try(w;var source=BSharedFeasibilityInputs.tiny(w)) {
            active[0]=w;armed[0]=true;
            var failure=assertThrows(BPhysicalPairTable.JointBudgetFailure.class,
                    ()->BPhysicalPairTable.buildExploratory(w,source,EvidenceModelParameters.defaults(),1));
            assertTrue(pressured[0]);var facts=failure.diagnostics();
            assertEquals("MEMORY_ENVELOPE_LIMIT",facts.get("reason"));
            assertEquals((64L<<20)+1,(long)facts.get("memoryBaseBytes")+(long)facts.get("poolBytes"));
            assertEquals(0,w.tableRegistry().actual(BPhysicalTableRegistry.EDGE,0));
        }
        assertEquals(0,owner.currentBytes());
    }
    @Test void exploratoryRegistryStillOwnsCompletionAndQualifiedGuardRemains() throws Exception {
        for(boolean exploratory:new boolean[]{false,true})try(var w=exploratory?BPhysicalWork.createExploratory(CancellationProbe.NONE):BPhysicalWork.create(CancellationProbe.NONE)) {
            var registry=w.claimTableRegistry();set(registry,"upper",(long)raw(registry,"used"));
            assertThrows(IllegalStateException.class,()->registry.end(BPhysicalTableRegistry.BIND,true));
            if(exploratory){
                registry.begin(BPhysicalTableRegistry.BIND);registry.end(BPhysicalTableRegistry.BIND,true);
                assertEquals(1,registry.actual(BPhysicalTableRegistry.BIND,0));assertEquals(1,registry.actual(BPhysicalTableRegistry.BIND,1));
                assertThrows(IllegalStateException.class,()->registry.end(BPhysicalTableRegistry.BIND,false));
                assertThrows(IllegalStateException.class,()->registry.begin(BPhysicalTableRegistry.BIND));
            } else assertThrows(IllegalStateException.class,()->registry.begin(BPhysicalTableRegistry.BIND));
        }
    }
    @Test void qualifiedSourceCurrencyStillRefusesWhileExploratoryCountersStayActual() {
        for(boolean exploratory:new boolean[]{false,true})try(var w=exploratory?BPhysicalWork.createExploratory(CancellationProbe.NONE):BPhysicalWork.create(CancellationProbe.NONE)) {
            //Counter-only control; no source or runtime authority is constructed.
            var source=w.sourceWork();source.reserveUpper(100);
            if(exploratory){source.numeric(101);assertEquals(101,source.snapshot().buildUnits());}
            else {assertThrows(IllegalArgumentException.class,()->source.numeric(101));assertEquals(100,source.snapshot().reservedUpperUnits());}
        }
    }
    @Test void exploratoryTableCannotPublishQualifiedProspectiveBounds() throws Exception {
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE);
            var source=BSharedFeasibilityInputs.tiny(w);
            var table=BPhysicalPairTable.buildExploratory(w,source,EvidenceModelParameters.defaults(),1)) {
            assertThrows(UnsupportedOperationException.class,w::registeredUpper,"a qualified future bound is unavailable in the exploratory table");
            assertThrows(UnsupportedOperationException.class,()->table.roleMaximum(BPhysicalTableRegistry.NODE));
            assertThrows(UnsupportedOperationException.class,()->w.tableRegistry().maximum(BPhysicalTableRegistry.NODE));
            assertThrows(UnsupportedOperationException.class,()->w.tableRegistry().freeze(false));
            assertThrows(UnsupportedOperationException.class,()->w.tableImage(false,21));
        }
    }
    @Test void futureSourceAllowanceCannotOverflowAnExploratoryAttempt() throws Exception {
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE);
            var source=BSharedFeasibilityInputs.tiny(w);
            var table=BPhysicalPairTable.buildExploratory(w,source,EvidenceModelParameters.defaults(),1)) {
            var response=(BPhysicalResponse)raw(table,"response");
            assertDoesNotThrow(()->w.sourceWork().extendCapturedAuthority(response.bound(),Long.MAX_VALUE),
                    "an explicitly deferred future source allowance is not numerical model work");
            var counters=w.sourceWork().snapshot();
            assertThrows(UnsupportedOperationException.class,counters::reservedUpperUnits);
            assertTrue(counters.sourceReads()>0,"actual source counters remain available");
        }
    }
    static Object raw(Object owner,String name) throws ReflectiveOperationException {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static void set(Object owner,String name,long value) throws ReflectiveOperationException {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.setLong(owner,value);
    }
}
