package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Portable source-issued fixtures; no original-case execution or authority forgery. */
class V022BPhysicalSourceReuseTest {
    @Test void repeatedSourceWorkIsReusedWithExactCarrierBits() throws Exception {
        var cached=run(false,false,7.4375);var uncached=run(true,false,7.4375);
        assertArrayEquals(uncached.bits,cached.bits);
        assertTrue(cached.coordinates<uncached.coordinates,"repeated mode-independent region work must be reused: "+cached.coordinates+" vs "+uncached.coordinates);
        assertTrue(cached.scalars<uncached.scalars,"repeated source corner reads must be reused");
        System.out.println("SOURCE_REUSE scalar="+cached.scalars+"/"+uncached.scalars+" region="+cached.coordinates+"/"+uncached.coordinates);
    }
    @Test void invalidSupportAndChangedContextsPreserveCarrierBits() throws Exception {
        for(boolean masked:new boolean[]{false,true})for(double x:new double[]{7.4375,8.0625,22.0625})
            assertArrayEquals(run(true,masked,x).bits,run(false,masked,x).bits,"all modes/contexts and unavailable support");
    }
    @Test void adjacentCellTableRangesAndExactZeroKeepUncachedBits() throws Exception {
        assertArrayEquals(tableBits(true),tableBits(false));
    }
    private static long[] tableBits(boolean misses) throws Exception {
        BPhysicalWork[] active={null};byte[][] state={null};
        CancellationProbe probe=()->{
            if(!misses||active[0]==null)return false;
            try {
                if(state[0]==null&&active[0].tableRegistry()!=null) {
                    var table=raw(active[0].tableRegistry(),"table");
                    if(table!=null){var ad=raw(raw(table,"permit"),"workspace");
                        if(ad!=null){var bank=raw(raw(raw(raw(raw(ad,"root"),"max"),"candidate"),"source"),"bank");
                            state[0]=(byte[])raw(bank,"reuseState");}}
                }
                if(state[0]!=null)Arrays.fill(state[0],(byte)0);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            return false;
        };
        try(var w=BPhysicalWork.createExploratory(probe)) {
            active[0]=w;var f=V022BPhysicalPairTableTest.fixture(w,true,2,false,true,false,Math.scalb(1.0,-300));
            var ps=f.profiles();var ls=new ArrayList<ProbabilisticStateLattice>();
            for(int i=0;i<2;i++) {
                var nativeLattice=V022BPhysicalPairTableTest.nativeLattice(w,ps.get(i));
                try(var held=w.owner().reserve(8192)) {
                    var l=new ProbabilisticStateLattice(List.of(new LateralStateCell((25+2*i)/16.0,.125,false,false,"unlocalized")),nativeLattice.components(),1,false);
                    held.adopt(l);ls.add(l);
                }
            }
            try(var table=BPhysicalPairTable.build(w,f.snapshot(),"scalar",ps,ls,EvidenceModelParameters.defaults(),4,
                    BPhysicalPairTable.unitPublicationBounds(w,2))) {
                var out=table.supportCarrier();table.support(0,0,0,out);
                assertEquals(0,out.value(),0);assertEquals(0,out.error(),0);assertEquals(1,table.nodes());
                return new long[]{Double.doubleToRawLongBits(out.value()),Double.doubleToRawLongBits(out.error()),
                        Double.doubleToRawLongBits(out.unknownLength()),Double.doubleToRawLongBits(out.unknownError()),table.nodes(),table.merges()};
            } finally {active[0]=null;state[0]=null;}
        }
    }
    @Test void reusedPreparationStillRejectsCancellationAndClosedSourceOrOwner() throws Exception {
        for(int action=0;action<3;action++) {
            final int selected=action;BPhysicalResponse[] response={null};BPhysicalMaxPanel[] panel={null};Object[] bank={null};boolean[] fired={false};
            CancellationProbe probe=()->{
                if(bank[0]==null||fired[0])return false;
                try {
                    int slot=(int)raw(bank[0],"reuseSlot");var state=(byte[])raw(bank[0],"reuseState");
                    if(slot<0||(state[slot]&1)==0)return false;
                    boolean prepare=false;
                    for(var frame:Thread.currentThread().getStackTrace())if(frame.getClassName().endsWith("BPhysicalPanelCertificate$SourceBank")
                            &&frame.getMethodName().equals("prepareComponent"))prepare=true;
                    if(!prepare)return false;
                    fired[0]=true;
                    if(selected==1)response[0].close();else if(selected==2)panel[0].owner.close();
                    return selected==0;
                }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            };
            try(var w=BPhysicalWork.createExploratory(probe)) {
                var f=V022BPhysicalPairTableTest.fixture(w,true,3);var ps=f.profiles();
                var ls=ps.stream().map(p->V022BSharedPositionFieldTest.tinyLattice(w.owner(),p)).toList();
                long baseline=w.owner().currentBytes();
                try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",ps,ls);var p=BPhysicalMaxPanel.open(r)) {
                    response[0]=r;panel[0]=p;bank[0]=raw(raw(raw(p,"candidate"),"source"),"bank");
                    if(action==0)assertThrows(java.util.concurrent.CancellationException.class,()->p.certify(7.4375,8.625,7.5625,8.625,4));
                    else assertThrows(IllegalStateException.class,()->p.certify(7.4375,8.625,7.5625,8.625,4));
                    assertTrue(fired[0],"actual same-slot prepare reuse boundary reached");
                    assertThrows(IllegalStateException.class,p::fieldStatus);
                } finally {bank[0]=null;}
                assertEquals(baseline,w.owner().currentBytes(),"all workspace released after failed reuse");
            }
        }
    }
    @Test void adaptiveChildStartsWithEmptySourceCertificates() throws Exception {
        Object[] source={null};boolean[] populated={false},child={false};
        CancellationProbe probe=()->{
            if(source[0]==null||child[0])return false;
            try {
                var state=(byte[])raw(source[0],"reuseState");for(byte s:state)populated[0]|=s!=0;
                boolean prepare=false;
                for(var frame:Thread.currentThread().getStackTrace())if(frame.getClassName().endsWith("BPhysicalPanelCertificate$SourceBank")
                        &&frame.getMethodName().equals("prepareComponent"))prepare=true;
                if(!prepare)return false;
                var nodes=raw(raw(source[0],"parent"),"nodes");
                if(((int[])raw(nodes,"depth"))[0]==0)return false;
                assertTrue(populated[0],"the parent used actual source certificates before refinement");
                for(byte s:state)assertEquals(0,s,"a changed adaptive node starts with no reusable source output");
                child[0]=true;return true;
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        };
        try(var w=BPhysicalWork.createExploratory(probe)) {
            var f=V022BPhysicalPairTableTest.fixture(w,true,3);var ps=f.profiles();
            var ls=ps.stream().map(p->V022BSharedPositionFieldTest.tinyLattice(w.owner(),p)).toList();
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",ps,ls)) {
                long before=w.owner().currentBytes();
                try(var ad=BPhysicalAdaptiveIntegral.open(r)) {
                    source[0]=raw(raw(raw(raw(raw(ad,"root"),"max"),"candidate"),"source"),"bank");
                    assertThrows(java.util.concurrent.CancellationException.class,()->ad.certify(7.4375,6.625,7.5625,6.625,16));
                    assertTrue(child[0],"a genuine changed dyadic node was reached");
                } finally {source[0]=null;}
                assertEquals(before,w.owner().currentBytes(),"fixed cache and adaptive pool released after cancellation");
            }
        }
    }
    private record Result(long[] bits,long scalars,long coordinates) { }
    private static Result run(boolean misses,boolean masked,double x) throws Exception {
        byte[][] states={null};BPhysicalMaxPanel[] active={null};var carriers=new ArrayList<Long>();int[] previous={0};
        CancellationProbe probe=()->{
            if(misses&&states[0]!=null)Arrays.fill(states[0],(byte)0);
            if(active[0]!=null)try {
                var component=(BPhysicalComponentPanel)raw(active[0],"candidate");int calls=(int)raw(active[0],"calls");
                if(calls!=previous[0]&&(int)raw(component,"phase")==1) {
                    previous[0]=calls;carriers.add((long)component.status().ordinal());
                    var values=(BPhysicalResponseBank)raw(component,"bank");
                    for(double[] plane:List.of(values.n,values.p,values.lo,values.hi,values.error))carriers.add(Double.doubleToRawLongBits(plane[35]));
                    var poly=(BPhysicalComponentPanel.Polynomial)raw(component,"poly");
                    for(var plane:List.of(poly.c,poly.lo,poly.hi))for(double v:plane[13])carriers.add(Double.doubleToRawLongBits(v));
                }
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            return false;
        };
        try(var w=BPhysicalWork.createExploratory(probe)) {
            var f=V022BPhysicalPairTableTest.fixture(w,true,3,false,false,masked);
            var ps=f.profiles();var ls=ps.stream().map(p->V022BSharedPositionFieldTest.tinyLattice(w.owner(),p)).toList();
            try(var response=BPhysicalResponse.open(w,f.snapshot(),"scalar",ps,ls)) {
                long before=w.owner().currentBytes();
                var panel=BPhysicalMaxPanel.open(response);
                try {
                    active[0]=panel;
                    //Invalidating only published cache tags forces genuine recomputation.
                    //No numerical source, record, mode or authority is changed.
                    try {states[0]=(byte[])raw(raw(raw(raw(panel,"candidate"),"source"),"bank"),"reuseState");}
                    catch(NoSuchFieldException absentBeforeCorrection){/* watched RED uses uncached implementation */}
                    long scalars=w.observed(0),coordinates=w.observed(4);
                    panel.certify(x,8.625,x+.125,8.625,4);
                    active[0]=null;
                    if(masked&&x==7.4375)assertTrue(panel.fieldStatus()==BPhysicalMaxPanel.Status.SOURCE_UNKNOWN
                            ||panel.contextStatus()==BPhysicalMaxPanel.Status.SOURCE_UNKNOWN,"invalid retained support remains explicitly unknown");
                    var poly=(BPhysicalComponentPanel.Polynomial)raw(panel,"poly");
                    long[] bits=new long[2+33+3*22*81+carriers.size()];int i=0;
                    bits[i++]=panel.fieldStatus().ordinal();bits[i++]=panel.contextStatus().ordinal();
                    for(double v:poly.registers)bits[i++]=Double.doubleToRawLongBits(v);
                    for(var plane:List.of(poly.c,poly.lo,poly.hi))for(var row:plane)for(double v:row)bits[i++]=Double.doubleToRawLongBits(v);
                    for(long v:carriers)bits[i++]=v;
                    return new Result(Arrays.copyOf(bits,i),w.observed(0)-scalars,w.observed(4)-coordinates);
                } finally {active[0]=null;states[0]=null;panel.close();assertEquals(before,w.owner().currentBytes(),"fixed workspace and prefix released");}
            }
        }
    }
    private static Object raw(Object owner,String name) throws ReflectiveOperationException {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
}
