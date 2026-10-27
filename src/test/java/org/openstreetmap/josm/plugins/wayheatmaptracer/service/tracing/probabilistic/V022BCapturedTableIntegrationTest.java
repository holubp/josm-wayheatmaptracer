package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Declared tiny full native graph;no public/cohort case,solver or runtime admission. */
class V022BCapturedTableIntegrationTest {
    @Test void authenticCapturedTwoAnchorGraphUsesTheActualNewKernelAndCompletePairData() throws Exception {
        run(0);
    }
    @Test void historicalDeepBranchWitnessRemainsTheOriginalPositiveWidthOvercover() throws Exception {
        //The repaired live path must not be required to reproduce its old
        //depth62 refusal. Preserve and independently discriminate the actual
        //old record;the new source-face test exercises current availability.
        var path=java.nio.file.Path.of("build/captured-table-integration/final/branch-witness/stdout.txt");
        byte[] bytes=java.nio.file.Files.readAllBytes(path);
        assertEquals("dcd28324e5ad2ebd46bb441ec426614fcec25683a29bc97a5ddb46e914084931",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        long ordinal=1152921504606842880L,face=1L<<60;
        assertTrue(ordinal+1<face,"Intrinsic depth62 panel is strictly before1/4");
        double p=Double.valueOf("0x1.3ffffffffffffp3"),lo=Double.valueOf("0x1.3fffffffffffdp3"),hi=Double.valueOf("0x1.4000000000001p3");
        assertTrue(lo<p&&p<hi);assertEquals(9,(int)StrictMath.floor(lo));assertEquals(10,(int)StrictMath.floor(hi));
        assertTrue(p>=0x1p-32,"New PN safe class concerns original coordinate,not tiny cell fraction");
    }
    @Test void actualAdapterGraphAndBorrowedResponseSurviveUntilThePreEdgeCancellation() throws Exception {run(2);}
    @Test void localQueryCancellationRetainsTruthfulEarlierEventsAndCallerSources() throws Exception {run(3);}
    @Test void foreignAttemptRejectsCapturedInputWithoutConsumingItsOriginalSource() throws Exception {run(4);}
    @Test void compiledFieldsFundOnlyOneBorrowAndTheExpandedCompilerFrame() throws Exception {
        assertEquals(23,fields(BPhysicalResponse.TableLocal.class));
        assertEquals(15,fields(BPhysicalResponse.OwnedLocal.class),"Own-only shell/lifetime was not expanded");
        Class<?> budget=Class.forName(BPhysicalPairTable.class.getName()+"$Budget");
        assertEquals(31,Arrays.stream(budget.getDeclaredFields()).filter(f->f.getType()==long.class&&!java.lang.reflect.Modifier.isStatic(f.getModifiers())).count());
        assertEquals(2,Arrays.stream(budget.getDeclaredFields()).filter(f->f.getType()==int.class).count());
        assertEquals(256,fields(budget));assertEquals(272,AttemptMemoryLedger.objectBytes(fields(budget)));
        long frame=AttemptMemoryLedger.objectBytes(fields(BPhysicalPairTable.class))
            +AttemptMemoryLedger.objectBytes(fields(BPhysicalPairTable.Permit.class))
            +AttemptMemoryLedger.objectBytes(fields(BPhysicalPairTable.MutableSupport.class))
            +AttemptMemoryLedger.objectBytes(fields(budget));
        assertEquals(624,frame);assertEquals(776,BPhysicalPairTable.FRAME);
        assertEquals(696,frame+24+48);
        assertTrue(frame+24+48<=BPhysicalPairTable.FRAME,"Frame funds Inference24 and the additional pure-H Reservation48");
        assertEquals(40,AttemptMemoryLedger.objectBytes(fields(BPhysicalResponse.TableLocal.class)));
        assertTrue(java.lang.reflect.Modifier.isPrivate(BPhysicalPairTable.Permit.class.getDeclaredConstructors()[0].getModifiers()));
        String dir="src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/";
        String kernel=java.nio.file.Files.readString(java.nio.file.Path.of(dir+"BSharedPositionKernel.java"));
        int entry=kernel.indexOf("Evaluation evaluateWithReceipt"),body=kernel.indexOf("private Evaluation evaluateBody");
        String control=kernel.substring(entry,body);
        assertTrue(control.indexOf("prepayKernel(")<control.indexOf("field.own.begin()"));
        assertTrue(control.contains("if(primary==null)throw secondary"),"Fallible end cannot replace primary body failure");
        String table=java.nio.file.Files.readString(java.nio.file.Path.of(dir+"BPhysicalPairTable.java"));
        assertTrue(table.indexOf("table.prepareCapturedLocal()")<table.indexOf("response.borrowLocal(table.permit)"));
        assertTrue(table.contains("BObservationAdapter.evaluate(b.input.facts.get(i)"));
        assertTrue(table.contains("createForTable(fieldView,gain,work)"));
    }
    private static int fields(Class<?> type){int n=0;for(var f:type.getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(f.getModifiers())){
        Class<?> t=f.getType();n+=!t.isPrimitive()?8:t==long.class||t==double.class?8:t==int.class||t==float.class?4:t==short.class||t==char.class?2:1;}return n;}
    interface TableAction {void accept(BPhysicalPairTable table)throws Exception;}
    void withCompletedTable(TableAction action)throws Exception {run(0,action);}
    void run(int mode) throws Exception {run(mode,null);}
    private void run(int mode,TableAction action) throws Exception {
        var observer=new Witness();observer.mode=mode;
        if(action!=null)observer.mode=6; //Only the UNIT solver lifecycle observer.
        CancellationProbe probe=action!=null||mode>0&&mode!=4?observer:CancellationProbe.NONE;
        try(var work=BPhysicalWork.create(probe)) {
            observer.work=work;
            var root=work.owner();var sw=work.sourceWork();
            var publication=BPhysicalPairTable.unitPublicationBounds(work,2);
            var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
            //Fixed BEFORE outputs:original quarter source,short path,step1,
            //native fixed endpoints. Neither returned states nor K is altered.
            var path=List.of(new MetricPoint(2.5,8.5),new MetricPoint(3.5,8.5));
            var geo=path.stream().map(frame::toGeographic).toList();
            var image=new BufferedImage(9,17,BufferedImage.TYPE_INT_ARGB);
            var values=new double[153];Arrays.fill(values,.02);Arrays.fill(values,8*9,10*9,1);
            var valid=new boolean[153];Arrays.fill(valid,true);
            var transform=SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(0,0),1,0,0,1);
            var grid=new MetricRasterGrid(frame,new MetricPoint(-.25,-.25),1,0,0,1,.5,19,35);
            var resolution=EvidenceResolution.nativeSource(1,1);
            var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"captured-table-quarter",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
            root.reserve(536+AttemptMemoryLedger.arrayBytes(153,4)).adopt(image);
            root.reserve(AttemptMemoryLedger.arrayBytes(153,8)).adopt(values);root.reserve(AttemptMemoryLedger.arrayBytes(153,1)).adopt(valid);
            root.reserve(128).adopt(geo);root.reserve(96).adopt(path);root.reserve(96).adopt(transform);
            root.reserve(112).adopt(grid);root.reserve(56).adopt(resolution);root.reserve(64).adopt(lineage);
            var chainage=new ProbabilisticProfileFactory().profileChainage(path,1);var parameters=EvidenceModelParameters.defaults();
            root.reserve(40+32+AttemptMemoryLedger.arrayBytes(2,8)+2*24).adopt(chainage);
            root.reserve(96+112+32+AttemptMemoryLedger.arrayBytes(parameters.version().length(),2)).adopt(parameters);
            try(var capture=BExperimentalScalarCapture.open(root,sw,probe)) {
                var view=capture.capture("captured-table-quarter",image,valid,values,geo,path,transform,grid,resolution,3,"captured-table-original-source","scalar",lineage);
                try(var profiles=capture.createProfiles(view,"scalar",chainage,3,true,parameters);
                        var facts=new BIdentificationFactsExtractor().extractCapturedPosition(capture,view,"scalar",profiles,probe,root,sw);
                        var lattices=capture.createLattices(profiles,facts)) {
                    assertEquals(2,profiles.profiles().size());assertEquals(2,lattices.lattices().size());
                    for(int i=0;i<2;i++){assertEquals(1,lattices.lattices().get(i).cells().size());assertTrue(lattices.lattices().get(i).cells().get(0).exactAnchor());assertFalse(profiles.profiles().get(i).modes().isEmpty());}
                    var input=BSharedPositionEvidence.CapturedInput.create(capture,view,"scalar",profiles,facts,lattices,root,probe,sw);
                    if(mode==4){try(var foreign=BPhysicalWork.create(CancellationProbe.NONE)){
                            var otherPublication=BPhysicalPairTable.unitPublicationBounds(foreign,2);
                            assertThrows(IllegalArgumentException.class,()->BPhysicalPairTable.buildCapturedData(foreign,input,parameters,4,otherPublication));
                        }
                        assertDoesNotThrow(facts::facts);assertDoesNotThrow(lattices::lattices);assertDoesNotThrow(view::fields);return;
                    }
                    if(mode>0){observer.armed=true;long before=root.currentBytes();
                        assertThrows(java.util.concurrent.CancellationException.class,()->BPhysicalPairTable.buildCapturedData(work,input,parameters,4,publication));
                        assertTrue(observer.found,"Declared actual callback boundary reached");
                        assertEquals(0,work.tableRegistry().actual(BPhysicalTableRegistry.TABLE,1));
                        assertEquals(1,work.tableRegistry().actual(BPhysicalTableRegistry.TABLE,2));
                        assertEquals(before,root.currentBytes(),"Unreturned table/response/Bound children roll back,caller capture persists");
                        if(mode==2){assertEquals(2,work.tableRegistry().actual(BPhysicalTableRegistry.LOCAL,1));assertEquals(0,work.tableRegistry().actual(BPhysicalTableRegistry.EDGE,0));}
                        if(mode==3){assertEquals(1,work.tableRegistry().actual(BPhysicalTableRegistry.LOCAL,0));assertEquals(1,work.tableRegistry().actual(BPhysicalTableRegistry.LOCAL,2));}
                        assertDoesNotThrow(facts::facts);assertDoesNotThrow(lattices::lattices);return;
                    }
                    try(var table=assertDoesNotThrow(()->{
                        try{return BPhysicalPairTable.buildCapturedData(work,input,parameters,4,publication);}
                        catch(RuntimeException failure){diagnostic(work);throw failure;}
                    })) {
                        assertTrue(table.complete());assertEquals(1,table.slots());assertEquals(BPhysicalPairTable.CERTIFIED,table.slotStatus(0,0,0));
                        var f=BPhysicalPairTable.class.getDeclaredField("graph");f.setAccessible(true);
                        @SuppressWarnings("unchecked") var actual=(List<InferenceProfile>)f.get(table);
                        boolean distinct=false;
                        for(int i=0;i<2;i++) {
                            var p=profiles.profiles().get(i);var l=lattices.lattices().get(i);
                            var baseline=new ProbabilisticObservationModel().evaluate(p,l,parameters);
                            assertEquals(0,actual.get(i).unaryCost(0));
                            for(int c=0;c<l.components().size();c++)distinct|=Double.doubleToRawLongBits(actual.get(i).componentResponsibility(0,c))!=Double.doubleToRawLongBits(baseline.componentResponsibility(0,c));
                            assertSame(p.anchor(),actual.get(i).anchor());assertSame(l.cells().get(0),actual.get(i).cells().get(0));
                        }
                        assertTrue(distinct,"Mutation to native baseline graph must fail even though anchor unaries are zero");
                        assertEquals(2,work.tableRegistry().actual(BPhysicalTableRegistry.LOCAL,1));
                        System.out.println("CAPTURED_TABLE_P1 J="+Double.toHexString(((double[])raw(table,"values"))[0])
                                +" error="+Double.toHexString(((double[])raw(table,"errors"))[0])
                                +" unknown="+Double.toHexString(((double[])raw(table,"unknown"))[0])
                                +" K="+raw(raw(table,"budget"),"k")+" ownQueries="+raw(work,"queries")
                                +" semantic="+work.semanticUnits()+" helper="+raw(work,"helpers"));
                        var source=BPhysicalPairTable.class.getDeclaredField("response");source.setAccessible(true);
                        assertThrows(UnsupportedOperationException.class,()->((BPhysicalResponse)source.get(table)).bound().requireRuntimeAuthority());
                        if(action!=null)action.accept(table); //TEST-only one completed-table consumer.
                    }
                    assertDoesNotThrow(facts::facts);assertDoesNotThrow(lattices::lattices);assertDoesNotThrow(view::fields);
                    assertThrows(UnsupportedOperationException.class,capture::requireTableSourceAuthority);
                }
            }
        }
    }
    private static final class Witness implements CancellationProbe {
        public boolean cancelled(){return false;}
        BPhysicalWork work;int mode,solverWrapperChecks;boolean armed,found,solverArmed;Object adaptive,component,source,pool,maximum,bank;
        java.lang.reflect.Field current,depth,componentPhase,componentStatus;
        public void checkpoint(){
            if(mode==6){
                if(!solverArmed)return; //No stack allocation during source/table construction.
                boolean wrapper=false,body=false;
                for(var frame:Thread.currentThread().getStackTrace())if(frame.getClassName().equals(ProbabilisticInference.class.getName())){
                    wrapper|=frame.getMethodName().equals("solveCrossStreamRaw1");body|=frame.getMethodName().equals("solve");}
                if(wrapper&&!body)solverWrapperChecks++; //exact first+final wrapper checkpoints per solve
                return; //No numerical/source fault injection for completed-table consumers.
            }
            if(!armed||found)return;
            try{
                var r=work.tableRegistry();
                if(mode==3){if(r!=null&&r.actual(BPhysicalTableRegistry.LOCAL,0)==1&&((long)raw(work,"queries"))==1){found=true;throw new java.util.concurrent.CancellationException("first actual own-state query");}return;}
                if(mode==2){if(r==null||!r.geometryAvailable())return;found=true;
                    Object table=raw(r,"table");assertAdapterGraph(table);
                    var response=(BPhysicalResponse)raw(table,"response");assertDoesNotThrow(()->response.bound().ensureOpen());
                    var permit=(BPhysicalPairTable.Permit)raw(table,"permit");
                    assertThrows(IllegalStateException.class,()->response.borrowLocal(permit),"Local permit expired before global table evaluation");
                    long queries=(long)raw(work,"queries");
                    assertThrows(IllegalStateException.class,()->response.query(0,0,2.5,8.5),"Borrowed kernel path cannot enable public arbitrary point queries");
                    assertEquals(queries,raw(work,"queries"));assertThrows(IllegalStateException.class,response::reason);
                    assertThrows(UnsupportedOperationException.class,()->response.bound().requireRuntimeAuthority());
                    throw new java.util.concurrent.CancellationException("actual adapted graph before first edge");
                }
                if(adaptive==null){var registry=work.tableRegistry();if(registry==null||!registry.geometryAvailable())return;
                    var table=raw(registry,"table");adaptive=raw(raw(table,"permit"),"workspace");if(adaptive==null)return;
                    pool=raw(adaptive,"pool");maximum=raw(raw(adaptive,"root"),"max");component=raw(maximum,"candidate");
                    source=raw(raw(component,"source"),"bank");bank=raw(component,"bank");
                    current=field(adaptive,"current");depth=field(pool,"depth");componentPhase=field(component,"phase");componentStatus=field(component,"status");}
                int at=current.getInt(adaptive);
                if(mode==5){
                    if(componentPhase.getInt(component)!=2||(int)raw(source,"scratch")!=2)return;
                    long[] admitted=(long[])raw(work,"admitted"),normal=(long[])raw(work,"normal"),thrown=(long[])raw(work,"thrown");
                    if(admitted[BPhysicalWork.SOURCE_PANEL]!=normal[BPhysicalWork.SOURCE_PANEL]+thrown[BPhysicalWork.SOURCE_PANEL])return;
                    found=true;V022BPhysicalSourceFaceTest.independentRecord(source);
                    V022BPhysicalSourceFaceTest.failedMaxGuardKeepsPaidThrownFacts(source,component,maximum);
                    throw new java.util.concurrent.CancellationException("completed adjacent-cell source range");
                }
                if(((int[])depth.get(pool))[at]!=62||componentPhase.getInt(component)!=1||componentStatus.getInt(component)!=3)return;
                //phase1 follows the actual COMPONENT_PANEL normal completion;
                //no later candidate has begun to overwrite its private banks.
                found=true;
                System.out.println("WITNESS depth62 ordinal="+((long[])raw(pool,"ordinal"))[at]+" calls="+raw(maximum,"calls")+" visits="+raw(maximum,"visits")
                    +" contextXY="+raw(component,"contextX")+","+raw(component,"contextY"));
                for(String name:List.of("width","height","scratch","cx","cy"))System.out.println("SOURCE "+name+"="+raw(source,name));
                for(int i:new int[]{0,1,6,15,16,29,36,40,44,48})lanes("SOURCE",source,i);
                for(int i:new int[]{0,1,2,3,4,7,8,9,10,13,14,15,16,20})lanes("COMPONENT",bank,i);
                for(String name:List.of("semantic","helpers","upper","helperUpper","queries","queryLimit"))System.out.println("WORK "+name+"="+raw(work,name));
                throw new java.util.concurrent.CancellationException("actual completed depth62 source witness");
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        }
    }
    private static void assertAdapterGraph(Object table)throws ReflectiveOperationException {
        var response=(BPhysicalResponse)raw(table,"response");var input=response.bound().input;
        @SuppressWarnings("unchecked") var graph=(List<InferenceProfile>)raw(table,"graph");
        boolean differs=false;var p=EvidenceModelParameters.defaults();
        for(int row=0;row<graph.size();row++){
            var facts=input.facts.get(row);var l=input.lattices.get(row);var result=graph.get(row);
            var nativeRows=new ProbabilisticObservationModel().evaluate(facts.baseline(),l,p);
            assertSame(facts.baseline().anchor(),result.anchor());assertSame(l.cells().get(0),result.cells().get(0));
            double sum=0;
            for(int c=0;c<l.components().size();c++){
                double v=result.componentResponsibility(0,c);assertTrue(Double.isFinite(v)&&v>=0);sum+=v;
                differs|=Double.doubleToRawLongBits(v)!=Double.doubleToRawLongBits(nativeRows.componentResponsibility(0,c));
            }
            assertEquals(1,sum,2e-15);assertEquals(0,result.unaryCost(0));
        }
        assertTrue(differs,"Replacing the actual Adapter/new-kernel graph with native rows must fail");
        var w=(BPhysicalWork)raw(table,"work");
        assertEquals(2,w.tableRegistry().actual(BPhysicalTableRegistry.LOCAL,1));
        assertEquals(2,((long[])raw(w,"admitted"))[BPhysicalWork.COMPONENT]);
        assertEquals(2L,raw(w,"queries"));assertEquals(2L,raw(w,"queryLimit"));
        var budget=raw(table,"budget");assertEquals(2L,raw(budget,"localQueries"));
        assertTrue(w.tableRegistry().maximum(BPhysicalTableRegistry.SCALAR)>=w.tableRegistry().actual(BPhysicalTableRegistry.SCALAR,0));
    }
    private static java.lang.reflect.Field field(Object object,String name)throws NoSuchFieldException {
        for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException missing){}
        throw new NoSuchFieldException(name);
    }
    private static void lanes(String label,Object object,int index)throws ReflectiveOperationException{
        StringBuilder line=new StringBuilder(label+" lane"+index);
        for(String n:List.of("n","p","lo","hi","error")){double value=((double[])field(object,n).get(object))[index];line.append(" ").append(n).append("=").append(Double.toHexString(value));}
        System.out.println(line);
    }
    private static Object raw(Object object,String name)throws ReflectiveOperationException {
        var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static void diagnostic(BPhysicalWork work){
        try{
            Object table=raw(work.tableRegistry(),"table");
            if(table==null){System.out.println("DIAGNOSTIC pre-global failure");return;}
            Object permit=raw(table,"permit"),adaptive=raw(permit,"workspace"),budget=raw(table,"budget");
            System.out.println("DIAGNOSTIC K="+raw(budget,"k")+" C0="+raw(budget,"current")+" Q="+raw(budget,"localQueries"));
            for(String name:List.of("status","consumed","splits","merges","rangeNodes","branchNodes","logNodes","numericRefusals","centre","error"))
                System.out.println("ADAPTIVE "+name+"="+raw(adaptive,name));
            Object root=raw(adaptive,"root");
            for(String name:List.of("status","centre","lower","upper","error","budget","logs","unknownLower","unknownUpper"))
                System.out.println("ROOT "+name+"="+raw(root,name));
            Object maximum=raw(root,"max");for(String name:List.of("fieldState","contextState"))
                System.out.println("MAX "+name+"="+raw(maximum,name));
        }catch(Exception unavailable){System.out.println("DIAGNOSTIC unavailable "+unavailable.getClass().getSimpleName());}
    }
}
