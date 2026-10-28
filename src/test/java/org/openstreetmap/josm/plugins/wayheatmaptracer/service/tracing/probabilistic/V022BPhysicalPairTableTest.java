package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Tiny authentic factory inputs only; no fixed-case/cohort or solver execution. */
class V022BPhysicalPairTableTest {
    @Test void r1LifecycleControlsArePrepaidWithoutNewStorageOrSupportBody() throws Exception {
        long controls=2+5+7+5+9+1+2+16; //Source operation partition,not a node multiplier.
        assertEquals(47,controls);assertEquals(controls,BPhysicalWork.RESPONSE_LIFECYCLE_UNITS);
        assertEquals(1832+3+4+6,BPhysicalTableRegistry.COPY); //last gate,table gate,TABLE-normal gate.
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            assertEquals(12977+256+5+2+controls,w.semanticUnits());
            assertEquals((1L<<20)-512-3560,w.sourceWork().remainingMetadataBytes());
        }
        var dir=java.nio.file.Path.of("src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic");
        String source=java.nio.file.Files.readString(dir.resolve("BPhysicalResponse.java"));
        String open=source.substring(source.indexOf("    static BPhysicalResponse open("),source.indexOf("    private static BPhysicalResponse openCapturedSource("));
        assertEquals(5,java.util.regex.Pattern.compile("catch\\(RuntimeException\\|Error ").matcher(open).results().count());
        assertFalse(open.contains("addSuppressed"));assertEquals(1,java.util.regex.Pattern.compile("result\\.close\\(\\)").matcher(open).results().count());
        assertTrue(open.lastIndexOf("return result;")>open.lastIndexOf("registry.end("));
        //The new captured constructor branch is separate from the unchanged
        //five-handler publication body. Its once512 entry precedes either
        //source acquisition or constructor/failure work;it is not a node fee.
        String captured=source.substring(source.indexOf("    private static BPhysicalResponse openCapturedSource("),source.indexOf("    private static BPhysicalResponse openSource("));
        assertEquals(3,java.util.regex.Pattern.compile("catch\\(RuntimeException\\|Error ").matcher(captured).results().count());
        String entry=source.substring(source.indexOf("    static BPhysicalResponse openCapturedTable("),source.indexOf("    private static BPhysicalResponse open("));
        assertTrue(entry.indexOf("work.prepayCapturedTableEntry()")<entry.indexOf("return open("));
        //claim1,owner2,probe1,sourceWork1,bind1,view/new1,child1,
        //reserve1,response/new1,install1,close2 =13 wrapper sites. Add
        //three entry registration APIs(live/add/helper),all <=32 paid slots.
        var calls=java.util.regex.Pattern.compile("(?:work\\.(?:claim|owner|probe|sourceWork|install)|BSharedPositionEvidence\\.bindCaptured|child\\.(?:child|reserve|close)|bound\\.close)\\(");
        //The owner.child invocation is a separate receiver call.
        long api=calls.matcher(captured).results().count()+java.util.regex.Pattern.compile("\\.child\\(").matcher(captured).results().count()
                +java.util.regex.Pattern.compile("new (?:BorrowedSourceView|BPhysicalResponse)\\(").matcher(captured).results().count();
        assertEquals(13,api);assertTrue(api+3<=32);assertEquals(32*8+256,BPhysicalWork.CAPTURED_OWN_SETUP);
        assertFalse(captured.contains("addSuppressed"));
        String table=java.nio.file.Files.readString(dir.resolve("BPhysicalPairTable.java"));
        String support=table.substring(table.indexOf("    void support("),table.indexOf("    /** Upper-only UNIT"));
        assertTrue(support.contains("prepayTableBody(575)"));assertTrue(support.indexOf("out.reason=CERTIFIED")>support.indexOf("registry.end("));
        assertFalse(table.substring(table.indexOf("    static BPhysicalPairTable build("),table.indexOf("    private void prepareGraph(")).contains("freeze(false)"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void failedBindEndClosesUnreturnedSourceAndPreservesBodyFailure(boolean primary) throws Exception {
            BPhysicalWork[] active={null};BSharedPositionEvidence.Bound[] issued={null};boolean[] armed={false},fired={false};
            var cancellation=new java.util.concurrent.CancellationException("owned VIEW-body failure");
            CancellationProbe probe=()->{
                var w=active[0];if(!armed[0]||fired[0]||w==null||w.tableRegistry()==null)return false;
                var r=w.tableRegistry();if(r.actual(BPhysicalTableRegistry.VIEW,0)!=1||r.actual(BPhysicalTableRegistry.VIEW,1)!=0)return false;
                try {
                    issued[0]=((BPhysicalResponse.SourceView)raw(w,"view")).bound;fired[0]=true;
                    //Controlled TEST fault at the actual bookkeeping boundary.
                    //No source,geometry,weight,integral or authority is forged.
                    var ceiling=r.getClass().getDeclaredField("upper");ceiling.setAccessible(true);
                    ceiling.setLong(r,(long)raw(r,"used")+(primary?0:BPhysicalTableRegistry.END));
                    if(primary)throw cancellation;
                    return false;
                } catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
                catch(java.util.concurrent.CancellationException expected){throw expected;}
                catch(Exception failure){throw new AssertionError(failure);}
            };
            try(var w=BPhysicalWork.create(probe)) {
                active[0]=w;var f=fixture(w,false,2);var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
                var registry=w.claimTableRegistry();long before=w.owner().currentBytes();armed[0]=true;
                if(primary)assertSame(cancellation,assertThrows(java.util.concurrent.CancellationException.class,
                        ()->BPhysicalResponse.open(w,f.snapshot,"scalar",f.profiles,ls)));
                else assertEquals("Global bookkeeping ceiling",assertThrows(IllegalStateException.class,
                        ()->BPhysicalResponse.open(w,f.snapshot,"scalar",f.profiles,ls)).getMessage());
                armed[0]=false;assertTrue(fired[0]);assertNotNull(issued[0]);
                assertThrows(IllegalStateException.class,issued[0]::ensureOpen);
                assertThrows(IllegalStateException.class,issued[0].input.authority::ensureOpen);
                assertEquals(before,w.owner().currentBytes(),"all unreturned source/authority/response children released");
                assertFalse(w.owner().isClosed());assertDoesNotThrow(w.sourceWork()::remainingMetadataBytes);
                assertEquals(1,registry.actual(BPhysicalTableRegistry.BIND,0));assertEquals(0,registry.actual(BPhysicalTableRegistry.BIND,1));
                assertEquals(primary?0:1,registry.actual(BPhysicalTableRegistry.VIEW,1));
            }
    }
    @Test void failedSupportEndCannotPublishAfterPriorSuccessfulLookup() throws Exception {
        BPhysicalWork[] active={null};boolean[] armed={false},fired={false};
        CancellationProbe probe=()->{
            var w=active[0];if(!armed[0]||fired[0]||w==null)return false;
            var r=w.tableRegistry();if(r==null||r.actual(BPhysicalTableRegistry.SUPPORT,0)!=2)return false;
            try {var ceiling=r.getClass().getDeclaredField("upper");ceiling.setAccessible(true);ceiling.setLong(r,(long)raw(r,"used"));fired[0]=true;return false;}
            catch(Exception failure){throw new AssertionError(failure);}
        };
        try(var w=BPhysicalWork.create(probe)) {
            active[0]=w;var f=fixture(w,false,3);var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
            try(var table=BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,EvidenceModelParameters.defaults(),4,
                    BPhysicalPairTable.unitPublicationBounds(w,2))) {
                var out=table.supportCarrier();table.support(0,0,0,out);assertEquals(0,out.value(),0);armed[0]=true;
                assertEquals("Global bookkeeping ceiling",assertThrows(IllegalStateException.class,()->table.support(0,0,0,out)).getMessage());
                armed[0]=false;assertTrue(fired[0]);
                assertThrows(IllegalStateException.class,out::value);assertThrows(IllegalStateException.class,out::error);
                assertThrows(IllegalStateException.class,out::unknownLength);assertThrows(IllegalStateException.class,out::unknownError);
                assertEquals(2,w.tableRegistry().actual(BPhysicalTableRegistry.SUPPORT,0));
                assertEquals(1,w.tableRegistry().actual(BPhysicalTableRegistry.SUPPORT,1));
                assertEquals(0,w.tableRegistry().actual(BPhysicalTableRegistry.SUPPORT,2));
            }
        }
    }
    @Test void numericalBuildDoesNotConsumeTheOwningPrecommitCut() {
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            var f=fixture(w,false,2);var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
            try(var table=BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,EvidenceModelParameters.defaults(),4,
                    BPhysicalPairTable.unitPublicationBounds(w,2))) {
                var r=w.tableRegistry();assertEquals(1,r.actual(BPhysicalTableRegistry.TABLE,1));
                assertThrows(IllegalStateException.class,()->r.original(false));assertThrows(IllegalStateException.class,()->r.original(true));
                assertThrows(IllegalStateException.class,()->w.tableImage(false,0));
                long before=w.sourceWork().remainingMetadataBytes();r.freeze(false);
                assertEquals(208,before-w.sourceWork().remainingMetadataBytes());assertNotNull(r.original(false));
                assertThrows(IllegalStateException.class,()->r.freeze(false));
                table.close();before=w.sourceWork().remainingMetadataBytes();r.freeze(true);
                assertEquals(208,before-w.sourceWork().remainingMetadataBytes());assertNotNull(r.original(true));
                assertThrows(IllegalStateException.class,()->r.freeze(true));
            }
        }
    }
    @Test void exactUnitWeightCopiesOwnedPolynomialAndKeepsCallbackGuards() {
        //The superseded negative-node observer is retained in the immutable
        //apex-operands-diagnostic packet. This live observer never writes a
        //numerical field or changes the naturally reached node/weight.
        for(int action=0;action<3;action++) {
            var observer=new UnitWeightObserver(action);
            try(var w=BPhysicalWork.create(observer::checkpoint)) {
                observer.work=w;var f=fixture(w,true,2,false,true,false);
                var ls=List.of(unitSingleton(w,f.profiles.get(0),27.0/16),unitSingleton(w,f.profiles.get(1),30.0/16));
                if(action==1)assertThrows(IllegalStateException.class,()->BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,
                        EvidenceModelParameters.defaults(),4,BPhysicalPairTable.unitPublicationBounds(w,2)));
                else assertThrows(java.util.concurrent.CancellationException.class,()->BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,
                        EvidenceModelParameters.defaults(),4,BPhysicalPairTable.unitPublicationBounds(w,2)));
                assertTrue(observer.before,"an actual polynomial self weight was reached");
                assertEquals(action==0,observer.after,"callback closure/cancel prevents the weighted row publication");
                if(action==0)assertTrue(observer.nonOne,"a real adjacent disk weight used the ordinary producer");
                assertFalse(w.owner().isClosed());
            }
        }
    }
    private static final class UnitWeightObserver {
        final int action;BPhysicalWork work;Object table,ad,max,poly,weights;
        boolean before,after,nonOne,pending,unit,finished;int index;
        final long[][] input=new long[3][5];
        UnitWeightObserver(int action){this.action=action;}
        boolean checkpoint() {
            if(work==null||finished)return false;
            try {
                if(ad==null) {
                    var registry=work.tableRegistry();if(registry==null)return false;
                    table=raw(registry,"table");if(table==null)return false;
                    ad=raw(raw(table,"permit"),"workspace");if(ad==null)return false;
                    max=raw(raw(ad,"root"),"max");poly=raw(max,"poly");weights=raw(max,"weights");
                }
                if(!pending&&onStack("weight")&&(int)raw(max,"currentState")==1) {
                    index=(int)raw(max,"contextIndex");
                    double w=((double[])raw(weights,"nominal"))[index];
                    double l=((double[])raw(weights,"lower"))[index],h=((double[])raw(weights,"upper"))[index];
                    double e=((double[])raw(weights,"error"))[index];
                    //Both cases have actual nonzero support; geometric zero
                    //and RANGE never enter the coefficient copy loop.
                    double[] registers=(double[])raw(poly,"registers");
                    if(registers[6]==0&&registers[8]==0&&registers[9]==0)return false;
                    unit=w==1&&l==1&&h==1&&e==0;
                    if(unit&&before||!unit&&(nonOne||w!=.75))return false;
                    pending=true;readRow(16,input);
                    if(unit) {
                        before=true;
                        if(action!=0) {
                            finished=true;
                            if(action==1){((BPhysicalResponse)raw(table,"response")).close();return false;}
                            return true;
                        }
                    }
                } else if(pending&&onStack("update")&&!onStack("weight")) {
                    long[][] actual=new long[3][5];readRow(17,actual);
                    if(unit) {
                        for(int lane=0;lane<3;lane++)assertArrayEquals(input[lane],actual[lane],"exact-one coefficient bits");
                        after=true;
                    } else {
                        double w=((double[])raw(weights,"nominal"))[index],l=((double[])raw(weights,"lower"))[index],h=((double[])raw(weights,"upper"))[index];
                        for(int k=0;k<5;k++) {
                            double c=Double.longBitsToDouble(input[0][k]),a=Double.longBitsToDouble(input[1][k]),b=Double.longBitsToDouble(input[2][k]);
                            assertEquals(Double.doubleToRawLongBits(c*w),actual[0][k]);
                            assertEquals(Double.doubleToRawLongBits(Math.nextDown(Math.min(Math.min(a*l,a*h),Math.min(b*l,b*h)))),actual[1][k]);
                            assertEquals(Double.doubleToRawLongBits(Math.nextUp(Math.max(Math.max(a*l,a*h),Math.max(b*l,b*h)))),actual[2][k]);
                        }
                        nonOne=true;
                    }
                    pending=false;
                    if(after&&nonOne){finished=true;return true;}
                }
            } catch(Exception failure){throw new AssertionError(failure);}
            return false;
        }
        private void readRow(int row,long[][] out) throws Exception {
            int lane=0;
            for(String name:List.of("c","lo","hi")) {
                var values=((double[][])raw(poly,name))[row];
                for(int k=0;k<5;k++)out[lane][k]=Double.doubleToRawLongBits(values[k]);
                lane++;
            }
        }
        private boolean onStack(String method) {
            for(var frame:Thread.currentThread().getStackTrace())if(frame.getClassName().equals(BPhysicalMaxPanel.class.getName())&&frame.getMethodName().equals(method))return true;
            return false;
        }
    }
    @Test void authenticTableNondyadicApexPublishesKnownRangeWithoutStalePolynomial() {
        //Declared complete UNIT singleton graph over two actual factory rows.
        //u(t)=27/16+3t/16 crosses the actual7/4 centre at t=1/3.
        //All translated tap traces stay within fixed retained half-metre cells.
        var active=new BPhysicalWork[1];boolean[] seen={false};
        CancellationProbe probe=()->{
            var w=active[0];if(w==null||seen[0]||w.tableRegistry()==null)return false;
            try {
                var table=raw(w.tableRegistry(),"table");if(table==null)return false;
                var ad=raw(raw(table,"permit"),"workspace");if(ad==null)return false;
                var max=raw(raw(ad,"root"),"max");
                var component=(BPhysicalComponentPanel)raw(max,"candidate");
                if((int)raw(component,"phase")!=1||(int)raw(component,"contextX")!=Integer.MIN_VALUE)return false;
                var bank=(BPhysicalResponseBank)raw(component,"bank");
                if(!(bank.lo[13]<1.75&&bank.hi[13]>1.75))return false;
                seen[0]=true;
                assertEquals("KNOWN_RANGE",component.status().name(),"a true non-dyadic apex has a completed scalar range");
                assertTrue(Double.isNaN(component.coefficient(0)),"range never exports the stale coefficient row");
                //Independent whole-panel lower:central B3 mass1/4,
                //lateral tent>=5/6 and longitudinal>=7/8. Actual retained
                //values at every central source corner are>=.75.
                var response=(BPhysicalResponse)raw(table,"response");var b=response.bound();
                assertEquals(1.75,b.d[4][0],0);assertEquals(.75,b.d[7][0],0);
                double margin=.75-b.d[5][0]-b.d[6][0];assertTrue(margin>0);
                assertTrue(bank.hi[35]>=35*margin/192);
                assertTrue(bank.lo[35]>=0&&bank.hi[35]<=1);
                System.out.println("APEX_RANGE t=1/3 bounds="+bank.lo[35]+"/"+bank.hi[35]+" analyticLower="+(35*margin/192));
                return true; //Stop this UNIT observer before any full table run.
            } catch(Exception failure){throw new AssertionError(failure);}
        };
        try(var w=BPhysicalWork.create(probe)) {
            active[0]=w;var f=fixture(w,true,2,false,true,false);
            var ls=List.of(unitSingleton(w,f.profiles.get(0),27.0/16),unitSingleton(w,f.profiles.get(1),30.0/16));
            assertThrows(java.util.concurrent.CancellationException.class,()->BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,
                    EvidenceModelParameters.defaults(),4,BPhysicalPairTable.unitPublicationBounds(w,2)));
            assertTrue(seen[0],"actual source-bound table candidate reached the nondyadic apex");
        }
    }
    private static ProbabilisticStateLattice unitSingleton(BPhysicalWork w,ProbabilisticProfile profile,double offset) {
        var original=nativeLattice(w,profile);
        try(var held=w.owner().reserve(8192)) {
            var l=new ProbabilisticStateLattice(List.of(new LateralStateCell(offset,.125,false,false,"unlocalized")),
                    original.components(),1,false);
            held.adopt(l);return l;
        }
    }
    @Test void genuineNondyadicApexTableIntegralEnclosesIndependentZero() throws Exception {
        //The SAME complete two-singleton UNIT graph as the range observer.
        //At every t,base max has longitudinal tent>=7/8. All b!=0
        //translated lateral tents vanish (rho1 shifts2m);all a!=0 context
        //weights<=3/4,so they cannot beat base. Thus C=F and D=J=0.
        assertTrue(.75<.875);
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=fixture(w,true,2,false,true,false);
            var ls=List.of(unitSingleton(w,f.profiles.get(0),27.0/16),unitSingleton(w,f.profiles.get(1),30.0/16));
            try(var table=BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,EvidenceModelParameters.defaults(),4,
                    BPhysicalPairTable.unitPublicationBounds(w,2))) {
                var out=table.supportCarrier();table.support(0,0,0,out);
                assertTrue(out.value()>=0&&out.error()<=Math.scalb(1,-32));
                assertTrue(out.value()-out.error()<=0&&out.value()+out.error()>=0);
                assertEquals(0,out.unknownLength(),0);assertEquals(1,table.nodes());
                System.out.println("TABLE_APEX J="+Double.toHexString(out.value())+" error="+Double.toHexString(out.error())
                        +" K="+table.capacity()+" nodes="+table.nodes()+" merges="+table.merges()+" independentJ=0");
            } catch(RuntimeException failure){postmortem(w);throw failure;}
        }
    }
    @Test void validTinyPositiveRasterTailsDoNotPermanentlyRefuseCellFaces() {
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)){
            var f=fixture(w,true,2,false,true,false,Math.scalb(1.0,-300));
            assertTrue(java.util.Arrays.stream(f.snapshot.fields().get("scalar").copiedValues()).anyMatch(v->v>0&&v<0x1p-256));
            // The translated lower tap crosses the exact6.25m raster face;
            // both adjacent cells contain genuine tiny positive source data.
            var ls=List.of(unitSingleton(w,f.profiles.get(0),25.0/16),unitSingleton(w,f.profiles.get(1),27.0/16));
            try(var table=assertDoesNotThrow(()->BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,
                    EvidenceModelParameters.defaults(),4,BPhysicalPairTable.unitPublicationBounds(w,2)))){
                var out=table.supportCarrier();table.support(0,0,0,out);
                assertTrue(out.value()-out.error()<=0&&out.value()+out.error()>=0,"independent self-dominance proves J=0");
                assertTrue(out.error()<=Math.scalb(1.0,-32));assertEquals(0,out.unknownLength(),0);
                assertEquals(1,table.nodes(),"known zero-context dominance proves the whole RANGE chord without subdivision");
                assertEquals(0,out.value(),0);assertEquals(0,out.error(),0);
            }
        }
    }
    @Test void unavailableEdgeRetainsBoundedFailureEvidenceAfterCleanup() {
        RuntimeException failure;
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)){
            var f=fixture(w,true,2,false,true,true);
            var ls=List.of(unitSingleton(w,f.profiles.get(0),0),unitSingleton(w,f.profiles.get(1),0));
            failure=assertThrows(RuntimeException.class,()->BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,
                EvidenceModelParameters.defaults(),4,BPhysicalPairTable.unitPublicationBounds(w,2)));
        }
        assertEquals("EdgeCertificateFailure",failure.getClass().getSimpleName());
        var diagnostic=((BPhysicalPairTable.EdgeCertificateFailure)failure).diagnostics();
        assertEquals("SOURCE_UNKNOWN",diagnostic.get("status"));
        assertEquals(java.util.Set.of("status","nodes","splits","merges","capacity","maximumDepth",
            "rangeNodes","branchNodes","logNodes","numericRefusals"),diagnostic.keySet());
        assertTrue((int)diagnostic.get("nodes")>=1);
        assertThrows(UnsupportedOperationException.class,()->diagnostic.put("status","CERTIFIED"));
    }
    @Test void genuineOneEdgeNonzeroIntegralExceedsIndependentWholeChordLowerBound() throws Exception {
        //Declared BEFORE outcomes:fixed endpoint native singleton rows,one
        //1/8m chord;real factory samples a ridge2m above the original path.
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            var f=fixture(w,true,2,false,true,false);
            assertTrue(f.profiles.stream().allMatch(p->p.modes().size()==1));
            //Source/profile prerequisites BEFORE builder evaluation. The
            //Original sample maximum is at2m;the extractor's nested receipt
            //centre1.75 is not inferred from that sampled maximum.
            for(var p:f.profiles) {
                double peak=p.samples().stream().filter(ProbabilisticProfile.Sample::valid).mapToDouble(ProbabilisticProfile.Sample::intensity).max().orElseThrow();
                var plateau=p.samples().stream().filter(s->s.valid()&&s.intensity()==peak).map(ProbabilisticProfile.Sample::offsetMeters).toList();
                assertEquals(List.of(2.0),plateau);assertEquals(1,p.sourcePitchMeters(),0);
            }
            var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
            assertEquals(List.of(1,1),ls.stream().map(l->l.cells().size()).toList());
            try(var t=BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,EvidenceModelParameters.defaults(),4,
                    BPhysicalPairTable.unitPublicationBounds(w,2))) {
                var field=BPhysicalPairTable.class.getDeclaredField("response");field.setAccessible(true);
                var response=(BPhysicalResponse)field.get(t);var b=response.bound();
                assertEquals(2,response.descriptorCount());assertEquals(1,b.grid.rho,0);
                for(int i=0;i<2;i++){assertEquals(1.75,b.d[4][i],0);assertEquals(.75,b.d[7][i],0);assertTrue(b.d[8][i]>=1);
                    assertTrue(b.d[9][i]<2&&b.d[10][i]>2);}
                var a=f.snapshot.transform().metricToPixelCenter(new MetricPoint(7.0625,8.625));
                var z=f.snapshot.transform().metricToPixelCenter(new MetricPoint(9.1875,8.625));
                assertEquals(14.625,a.x(),0);assertEquals(17.75,a.y(),0);assertEquals(18.875,z.x(),0);
                double minimum=1;var values=f.snapshot.fields().get("scalar").copiedValues();
                for(int y=17;y<=18;y++)for(int x=14;x<=19;x++)minimum=Math.min(minimum,values[y*35+x]);
                var margin=new java.math.BigDecimal(minimum).subtract(new java.math.BigDecimal(b.d[5][0])).subtract(new java.math.BigDecimal(b.d[6][0]));
                assertTrue(margin.signum()>0,"factual retained corners dominate own excess floor");
                //F=0 throughout;context b2 weight1/2. The central B3 tap
                //weight1/4,own lateral tent2/3 and longitudinal tent>=7/8
                //give4C>=7margin/24. log(1+x)>=x/(1+x),length1/8.
                var zz=margin.multiply(java.math.BigDecimal.valueOf(7)).divide(java.math.BigDecimal.valueOf(24),new java.math.MathContext(80,java.math.RoundingMode.DOWN));
                var oracle=zz.divide(java.math.BigDecimal.ONE.add(zz),new java.math.MathContext(80,java.math.RoundingMode.DOWN))
                        .divide(java.math.BigDecimal.valueOf(8));
                var out=t.supportCarrier();t.support(0,0,0,out);
                assertTrue(out.value()>0&&out.error()<=Math.scalb(1.0,-32));assertEquals(0,out.unknownLength(),0);
                assertTrue(new java.math.BigDecimal(out.value()).add(new java.math.BigDecimal(out.error())).compareTo(oracle)>=0);
                for(int eta=1;eta<=3;eta++)assertEquals((eta==1?.25:eta==2?1:4)*out.value(),t.potential(eta).energy(0,0,0),0);
                System.out.println("TABLE_POSITIVE P1 K="+t.capacity()+" nodes="+t.nodes()+" merges="+t.merges()+" J="+Double.toHexString(out.value())
                        +" error="+Double.toHexString(out.error())+" independentLower="+oracle);
            } catch(RuntimeException failure){postmortem(w);throw failure;}
        }
    }
    private static Object raw(Object o,String name) throws Exception {
        var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private static void postmortem(BPhysicalWork w) throws Exception {
        //TEST-ONLY postmortem of genuine retained primitive state. No reopened
        //capability,source query,changed input or actual-attempt reflection.
        var registry=w.tableRegistry();var table=raw(registry,"table");if(table==null)return;var permit=raw(table,"permit");
        var a=raw(permit,"workspace");var pool=raw(a,"pool");var root=raw(a,"root");var max=raw(root,"max");
        System.out.println("POST_TABLE edge="+registry.actual(BPhysicalTableRegistry.EDGE,0)+"/"+registry.actual(BPhysicalTableRegistry.EDGE,1)
                +"/"+registry.actual(BPhysicalTableRegistry.EDGE,2)+" node="+registry.actual(BPhysicalTableRegistry.NODE,0)+"/"
                +registry.actual(BPhysicalTableRegistry.NODE,1)+"/"+registry.actual(BPhysicalTableRegistry.NODE,2));
        for(String n:List.of("status","current","rangeNodes","branchNodes","logNodes","numericRefusals","splits","merges"))System.out.println("POST_ADAPTIVE "+n+"="+raw(a,n));
        int current=(int)raw(a,"current");System.out.println("POST_POOL K="+raw(pool,"capacity")+" cursor="+raw(pool,"cursor")+" depth="+((int[])raw(pool,"depth"))[current]
                +" ordinal="+((long[])raw(pool,"ordinal"))[current]);
        for(String n:List.of("status","centre","error","budget","unknownLower","unknownUpper"))System.out.println("POST_ROOT "+n+"="+raw(root,n));
        for(String n:List.of("fieldState","contextState","currentState","contextIndex","visits","calls"))System.out.println("POST_MAX "+n+"="+raw(max,n));
        var response=raw(table,"response");var view=raw(response,"view");var b=(BSharedPositionEvidence.Bound)raw(view,"bound");
        for(int i=0;i<b.indices[2].length;i++){System.out.print("POST_RECEIPT status="+b.indices[2][i]);
            for(int j=4;j<=10;j++)System.out.print(" d"+j+"="+Double.toHexString(b.d[j][i]));System.out.println();}
    }
    @Test void actualOriginalAdmissionRejectsSlotsWithoutAnyImageEvaluation() {
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            var f=fixture(w,false,3,true,false,false);var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
            int admitted=0,rejected=0;
            try(var t=BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,EvidenceModelParameters.defaults(),4,
                    BPhysicalPairTable.unitPublicationBounds(w,2))) {
                for(int i=0;i<2;i++)for(int a=0;a<ls.get(i).cells().size();a++)for(int b=0;b<ls.get(i+1).cells().size();b++) {
                    //Independent unchanged convenience predicate,not a table
                    //status oracle;all original cells retained in both paths.
                    var p=f.profiles.get(i);var q=f.profiles.get(i+1);double x=ls.get(i).cells().get(a).offsetMeters(),y=ls.get(i+1).cells().get(b).offsetMeters();
                    boolean expected=f.snapshot.decisionRegion().containsSegment(new MetricPoint(p.anchor().xMeters()+p.normalUnit().xMeters()*x,p.anchor().yMeters()+p.normalUnit().yMeters()*x),
                            new MetricPoint(q.anchor().xMeters()+q.normalUnit().xMeters()*y,q.anchor().yMeters()+q.normalUnit().yMeters()*y));
                    assertEquals(expected?BPhysicalPairTable.CERTIFIED:BPhysicalPairTable.NOT_ADMITTED,t.slotStatus(i,a,b));
                    if(expected)admitted++;else{rejected++;final int ii=i,aa=a,bb=b;assertThrows(IllegalArgumentException.class,()->t.potential(1).energy(ii,aa,bb));}
                }
                assertTrue(admitted>0&&rejected>0);assertEquals(admitted,t.nodes());
                assertEquals(admitted,w.tableRegistry().actual(BPhysicalTableRegistry.NODE,0));assertEquals(admitted+rejected,t.slots());
                assertEquals(0,w.tableRegistry().actual(BPhysicalTableRegistry.SCALAR,0));
            }
        }
    }
    @Test void aggregateStatesAbove96KeepPerProfileGuardAndFullInputInventory() {
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            var f=fixture(w,false,3);var ls=List.of(nativeLattice(w,f.profiles.get(0)),unit96(w,f.profiles.get(1)),nativeLattice(w,f.profiles.get(2)));
            assertEquals(98,ls.stream().mapToInt(l->l.cells().size()).sum());
            try(var t=BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,EvidenceModelParameters.defaults(),4,
                    BPhysicalPairTable.unitPublicationBounds(w,2))) {
                assertEquals(192,t.slots());assertEquals(192,t.nodes());assertEquals(192,w.tableRegistry().actual(BPhysicalTableRegistry.EDGE,1));
            }
        }
    }
    private static ProbabilisticStateLattice unit96(BPhysicalWork w,ProbabilisticProfile p) {
        //Complete declared UNIT graph,not a production lattice-admission claim;
        //native singleton endpoint rows and all96declared interior cells stay.
        try(var held=w.owner().reserve(8192)) {
            var cells=new java.util.ArrayList<LateralStateCell>(96);
            for(int i=0;i<96;i++)cells.add(new LateralStateCell(i==47?0:-3+6.0*i/95,.0625,false,false,"unlocalized"));
            double pitch=0;for(int i=1;i<cells.size();i++)pitch=Math.max(pitch,cells.get(i).offsetMeters()-cells.get(i-1).offsetMeters());
            var l=new ProbabilisticStateLattice(cells,List.of(new ObservationComponent("missing","missing:"+p.profileIndex(),ObservationComponent.Kind.MISSING,1,false)),pitch,false);
            held.adopt(l);return l;
        }
    }
    @Test void sourcePublicationAloneCannotAuthorizeAnUnsealedGlobalNumericalWorker() {
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            var f=fixture(w,false,2);w.claimTableRegistry();
            var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
            try(var r=BPhysicalResponse.open(w,f.snapshot,"scalar",f.profiles,ls)) {
                assertThrows(IllegalStateException.class,()->BPhysicalPanelCertificate.open(r),
                        "source publication is not complete global K/cap installation");
            }
        }
    }
    @Test void completeNativeThreeProfileGraphReusesOnePoolAndPreservesEverySlot() {
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            var f=fixture(w,false,3);var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
            assertEquals(1,ls.get(0).cells().size());assertEquals(1,ls.get(2).cells().size());
            assertTrue(ls.get(1).cells().size()>1,"actual native interior full graph is not a singleton");
            int p=2*ls.get(1).cells().size();
            try(var table=BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,EvidenceModelParameters.defaults(),4,
                    BPhysicalPairTable.unitPublicationBounds(w,2))) {
                assertEquals(p,table.slots());var registry=w.tableRegistry();
                assertEquals(p,registry.actual(BPhysicalTableRegistry.EDGE,0));assertEquals(p,registry.actual(BPhysicalTableRegistry.EDGE,1));
                assertEquals(p,registry.actual(BPhysicalTableRegistry.NODE,0));assertEquals(p,registry.actual(BPhysicalTableRegistry.NODE,1));
                assertEquals(0,registry.actual(BPhysicalTableRegistry.MERGE,0));
                assertEquals(0,registry.actual(BPhysicalTableRegistry.SCALAR,0));
                assertEquals(5L*35*51,registry.actual(BPhysicalTableRegistry.MASK,0),"prefix constructed once,not once per edge");
                assertEquals(0,registry.actual(BPhysicalTableRegistry.CODEC,0));assertEquals(1,registry.maximum(BPhysicalTableRegistry.CODEC));
                assertNull(table.potential(0));var out=table.supportCarrier();assertThrows(IllegalStateException.class,out::value);
                for(int stage=0;stage<2;stage++)for(int a=0;a<ls.get(stage).cells().size();a++)for(int b=0;b<ls.get(stage+1).cells().size();b++) {
                    for(int eta=1;eta<=3;eta++)assertEquals(0,table.potential(eta).energy(stage,a,b),0);
                    table.support(stage,a,b,out);assertEquals(0,out.value(),0);assertEquals(0,out.unknownLength(),0);
                    assertTrue(out.error()<=Math.scalb(1.0,-32)&&out.unknownError()>0);
                }
                assertEquals(p,registry.actual(BPhysicalTableRegistry.SUPPORT,1));
                assertThrows(IllegalStateException.class,()->registry.original(false));
                registry.freeze(false); //UNIT cut mechanics only;no staging claim.
                var prefix=registry.original(false);assertEquals(1,prefix.sourceExtractionStartedCalls());
                assertEquals(prefix.sourceReads(),w.tableImage(false,3));
                assertEquals(0,w.tableImage(false,124));assertThrows(IllegalStateException.class,()->w.tableImage(false,98));
                table.close();assertThrows(IllegalStateException.class,out::value);
                assertThrows(IllegalStateException.class,()->table.potential(1));
                registry.freeze(true);
                assertEquals(208,registry.original(true).retainedMetadataSliceBytes()-prefix.retainedMetadataSliceBytes());
                assertEquals(1,registry.actual(BPhysicalTableRegistry.TABLE,1));
                assertEquals(0,w.tableImage(true,130),"live metadata root is not a predicted root-zero result");
                assertThrows(IllegalStateException.class,()->registry.freeze(true));
            }
        }
    }
    @Test void prospectivePublicationUpperIsFundedAndNeverOriginalSourceAuthority() {
        try(var w=BPhysicalWork.create(CancellationProbe.NONE);var foreign=BPhysicalWork.create(CancellationProbe.NONE)) {
            long before=w.sourceWork().remainingMetadataBytes(),units=w.semanticUnits();
            var upper=BPhysicalPairTable.unitPublicationBounds(w,2);
            assertEquals(32,before-w.sourceWork().remainingMetadataBytes());assertEquals(160,w.semanticUnits()-units);
            assertThrows(IllegalArgumentException.class,()->BPhysicalPairTable.unitPublicationBounds(w,1));
            var f=fixture(foreign,false,2);var ls=f.profiles.stream().map(p->nativeLattice(foreign,p)).toList();
            assertThrows(IllegalArgumentException.class,()->BPhysicalPairTable.build(foreign,f.snapshot,"scalar",f.profiles,ls,
                    EvidenceModelParameters.defaults(),4,upper));
            assertNull(foreign.tableRegistry(),"foreign upper cannot start source issuance");
        }
    }
    @Test void cancellationAtSourceEdgeNodeAndFinalPublicationPreservesActualFailureCuts() {
        for(int cut=0;cut<4;cut++) {
            final int boundary=cut;var active=new BPhysicalWork[1];boolean[] armed={false},hit={false};
            CancellationProbe probe=()->{
                var w=active[0];if(!armed[0]||w==null)return false;
                var r=w.tableRegistry();if(r==null)return false;
                boolean reached=boundary==0?r.actual(BPhysicalTableRegistry.BIND,0)==1:
                        boundary==1?r.actual(BPhysicalTableRegistry.EDGE,0)==1:
                        boundary==2?r.actual(BPhysicalTableRegistry.NODE,0)==1:
                        r.actual(BPhysicalTableRegistry.EDGE,1)==1;
                if(reached)hit[0]=true;return reached;
            };
            try(var w=BPhysicalWork.create(probe)) {
                active[0]=w;var f=fixture(w,false,2);var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
                var publication=BPhysicalPairTable.unitPublicationBounds(w,2);armed[0]=true;
                assertThrows(java.util.concurrent.CancellationException.class,()->BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,
                        EvidenceModelParameters.defaults(),4,publication));
                armed[0]=false;assertTrue(hit[0]);var r=w.tableRegistry();
                assertEquals(0,r.actual(BPhysicalTableRegistry.TABLE,1));
                if(boundary==0){assertEquals(1,r.actual(BPhysicalTableRegistry.BIND,2));assertEquals(0,r.actual(BPhysicalTableRegistry.TABLE,0));}
                else {assertEquals(1,r.actual(BPhysicalTableRegistry.TABLE,2));assertEquals(1,r.actual(BPhysicalTableRegistry.EDGE,0));
                    assertEquals(boundary==3?1:0,r.actual(BPhysicalTableRegistry.EDGE,1));
                    assertEquals(boundary==3?0:1,r.actual(BPhysicalTableRegistry.EDGE,2));}
                if(boundary>=2){assertEquals(1,r.actual(BPhysicalTableRegistry.NODE,0));assertEquals(boundary==3?1:0,r.actual(BPhysicalTableRegistry.NODE,1));}
                //Root-funded cuts survive all numerical/source-child rollback;
                //they are actual partial counts,not a fabricated table/result.
                long before=w.sourceWork().remainingMetadataBytes();
                assertThrows(IllegalStateException.class,()->r.freeze(false));
                assertEquals(before,w.sourceWork().remainingMetadataBytes());
                r.freeze(true); //Explicit failure-HOST boundary,not PRECOMMIT.
                assertEquals(208,before-w.sourceWork().remainingMetadataBytes());assertNotNull(r.original(true));
                assertEquals(0,w.tableImage(true,130));
                assertThrows(IllegalStateException.class,()->r.original(false));
            }
        }
    }
    @Test void singleProfileZeroEdgeStillChecksActualRootAndHolderCapacity() {
        var active=new BPhysicalWork[1];boolean[] armed={false},pressured={false};
        CancellationProbe probe=()->{
            var w=active[0];if(!armed[0]||w==null||pressured[0])return false;
            var r=w.tableRegistry();if(r!=null&&r.actual(BPhysicalTableRegistry.BASELINE,1)==1) {
                //Factual TEST pressure before the actual C0 read. Zero pairs
                //does not waive remaining table/H/publication reservations.
                long bytes=(64L<<20)-w.owner().currentBytes()-128;
                assertTrue(bytes>0);w.owner().reserve(bytes);pressured[0]=true;
            }
            return false;
        };
        try(var w=BPhysicalWork.create(probe)) {
            active[0]=w;var original=fixture(w,false,3);
            var f=new Fixture(original.snapshot,List.of(original.profiles.get(1)));
            var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
            var publication=BPhysicalPairTable.unitPublicationBounds(w,2);armed[0]=true;
            var failure=assertThrows(IllegalStateException.class,()->BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,
                    EvidenceModelParameters.defaults(),4,publication));
            assertTrue(pressured[0]);assertEquals("No complete jointly funded table root",failure.getMessage());
            assertEquals(0,w.tableRegistry().actual(BPhysicalTableRegistry.EDGE,0));
            assertEquals(0,w.tableRegistry().actual(BPhysicalTableRegistry.NODE,0));
            assertEquals(1,w.tableRegistry().actual(BPhysicalTableRegistry.TABLE,2));
        }
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            var original=fixture(w,false,3);
            var f=new Fixture(original.snapshot,List.of(original.profiles.get(1)));
            var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
            var publication=BPhysicalPairTable.unitPublicationBounds(w,2);
            w.sourceWork().metadata(w.sourceWork().remainingMetadataBytes()-2687);
            var failure=assertThrows(IllegalArgumentException.class,()->BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,
                    EvidenceModelParameters.defaults(),4,publication));
            assertEquals("Shared metadata holder exhausted",failure.getMessage());
            assertNull(w.tableRegistry());
            //The failed registry claim is consumed before source effects.
            assertThrows(IllegalStateException.class,w::claimTableRegistry);
        }
    }
    @Test void fundedRegistryRecordsOnlyReachedSourceBoundariesAndConsumesItsClaim() {
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            var f=fixture(w,false,2);var registry=w.claimTableRegistry();
            assertEquals(1,registry.actual(BPhysicalTableRegistry.SETUP,0));
            assertEquals(1,registry.actual(BPhysicalTableRegistry.SETUP,1));
            assertEquals(0,registry.actual(BPhysicalTableRegistry.BIND,0));
            assertFalse(registry.sourcePublished());
            assertThrows(IllegalStateException.class,w::claimTableRegistry);
            var ls=f.profiles.stream().map(p->V022BSharedPositionFieldTest.tinyLattice(w.owner(),p)).toList();
            try(var response=BPhysicalResponse.open(w,f.snapshot,"scalar",f.profiles,ls)) {
                assertTrue(registry.sourcePublished());
                assertEquals(1,registry.actual(BPhysicalTableRegistry.BIND,0));
                assertEquals(1,registry.actual(BPhysicalTableRegistry.BIND,1));
                assertEquals(1,registry.actual(BPhysicalTableRegistry.VIEW,1));
                assertEquals(0,registry.actual(BPhysicalTableRegistry.SCALAR,0));
                assertThrows(IllegalStateException.class,()->response.query(0,0,7,8.5));
                assertEquals(0,registry.actual(BPhysicalTableRegistry.SCALAR,0));
            }
            //Real completion facts remain readable after Bound-child cleanup.
            assertEquals(1,registry.actual(BPhysicalTableRegistry.BIND,1));
        }
    }
    @Test void completeTwoProfileAllZeroFieldHasEveryOriginalAdjacentSlot() {
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            var f=fixture(w,false,2);
            assertEquals(2,f.profiles.size());
            assertTrue(f.profiles.stream().allMatch(p->p.modes().isEmpty()),"declared no-signal prerequisite");
            var ls=f.profiles.stream().map(p->nativeLattice(w,p)).toList();
            try(var table=assertDoesNotThrow(()->BPhysicalPairTable.build(w,f.snapshot,"scalar",f.profiles,ls,
                    EvidenceModelParameters.defaults(),4,BPhysicalPairTable.unitPublicationBounds(w,2)),"complete original adjacent table must be implemented")) {
                assertTrue(table.complete());assertEquals(ls.get(0).cells().size()*ls.get(1).cells().size(),table.slots());
            }
        }
    }
    @Test void completeSingleProfileTableRetainsNoInventedAdjacentEdge() {
        try(var w=BPhysicalWork.create(CancellationProbe.NONE)) {
            var f=fixture(w);var ps=List.of(f.profiles.get(1));
            var ls=List.of(nativeLattice(w,ps.get(0)));
            try(var table=assertDoesNotThrow(()->BPhysicalPairTable.build(w,f.snapshot,"scalar",ps,ls,
                    EvidenceModelParameters.defaults(),4,BPhysicalPairTable.unitPublicationBounds(w,2)),"complete table stage must be implemented")) {
                assertTrue(table.complete());assertEquals(0,table.slots());
            }
        }
    }
    record Fixture(EvidenceSnapshot snapshot,List<ProbabilisticProfile> profiles) { }
    static ProbabilisticStateLattice nativeLattice(BPhysicalWork w,ProbabilisticProfile p) {
        var built=new ProbabilisticStateBuilder().build(p,96,w.owner());
        assertEquals(StateSpaceBuildResult.Status.COMPLETE,built.status());return built.lattice().orElseThrow();
    }
    static Fixture fixture(BPhysicalWork w) {return fixture(w,true,3);}
    static Fixture fixture(BPhysicalWork w,boolean signal,int count) {
        return fixture(w,signal,count,false,false,false);
    }
    static Fixture fixture(BPhysicalWork w,boolean signal,int count,boolean narrow,boolean positive,boolean masked) {
        return fixture(w,signal,count,narrow,positive,masked,.02);
    }
    static Fixture fixture(BPhysicalWork w,boolean signal,int count,boolean narrow,boolean positive,boolean masked,double background) {
        if(positive)assertTrue(signal&&count==2);
        try(var held=w.owner().reserve(32L<<20)) {
            double[] values=new double[17*25];boolean[] valid=new boolean[values.length];
            Arrays.fill(values,signal?background:0);Arrays.fill(valid,true);if(signal)Arrays.fill(values,8*17,10*17,1);
            if(masked)valid[8*17+6]=false; //off-profile applicable translated tap
            var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                    EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
            var raw=new ScalarEvidenceField(17,25,values,valid,lineage);
            var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
            var path=positive?List.of(new MetricPoint(8.0625,6.625),new MetricPoint(8.1875,6.625)):
                    List.of(new MetricPoint(7,8.5),new MetricPoint(count==2?8:9,8.5));
            double search=positive?6:3;
            var inverse=SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(0,0),1,0,0,1);
            var grid=new MetricRasterGrid(frame,new MetricPoint(-.25,-.25),1,0,0,1,.5,35,51);
            var s=new RasterEvidenceCapture(32L<<20).captureWithMetricScalarSource("unit-table-quarter",
                    new BufferedImage(17,25,BufferedImage.TYPE_INT_ARGB),raw.copiedValidity(),raw.copiedValues(),
                    path.stream().map(frame::toGeographic).toList(),path,inverse,grid,EvidenceResolution.nativeSource(1,1),search,
                    "synthetic",EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",raw.lineage(),w.probe());
            held.adopt(s);
            if(narrow)try(var extra=w.owner().reserve(8192)) {
                s=new EvidenceSnapshot("unit-narrow-decision",s.coordinateFrame(),s.transform(),s.resolution(),
                        new MetricRegion(List.of(List.of(new MetricPoint(6.5,5),new MetricPoint(9.5,5),new MetricPoint(9.5,12),
                                new MetricPoint(7.6,12),new MetricPoint(7.6,8.6),new MetricPoint(7.4,8.6),
                                new MetricPoint(7.4,12),new MetricPoint(6.5,12)))),s.evidenceRegion(),s.fields(),s.resampling(),s.sourceIdentity());
                extra.adopt(s);
            }
            var ps=new ProbabilisticProfileFactory().create(path,new ProfileChainage(positive?List.of(0.0,.125):count==2?List.of(0.0,1.0):List.of(0.0,1.0,2.0),1),search,true,
                    s,s.fields().get("scalar"),EvidenceModelParameters.defaults(),w.probe(),true,w.owner());
            assertEquals(count,ps.size());return new Fixture(s,ps);
        }
    }
}
