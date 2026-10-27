package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Tiny actual source-to-solver controls; not public-case numerical evidence. */
class V022BSharedFeasibilityComparisonTest {
    @Test void tinySourceUsesSharedRowsPhysicalTableAndActualSolver() {
        var ledgerResult = BSharedFeasibilityComparison.executeTiny(CancellationProbe.NONE);
        assertEquals("EXPLORATORY_QUALITY_ONLY_V1", ledgerResult.get("evidenceClass"));
        assertEquals(1, ledgerResult.get("solverInvocations"));
        assertEquals(true, ledgerResult.get("routeReturned"));
        assertEquals(false, ledgerResult.get("runtimeQualified"));
        assertEquals(0L, ledgerResult.get("trackedBytesAfterClose"));
        assertEquals(true, ledgerResult.get("sharedRowsUsed"));
    }

    @Test void exploratoryCarrierRejectsForeignAndClosedWork() {
        try(var a=BPhysicalWork.createExploratory(CancellationProbe.NONE);
            var b=BPhysicalWork.createExploratory(CancellationProbe.NONE);
            var source=BSharedFeasibilityInputs.tiny(a)) {
            assertThrows(IllegalArgumentException.class,()->source.require(b));
            source.close();
            assertThrows(IllegalStateException.class,()->source.require(a));
        }
    }

    @Test void ordinaryWorkCannotAcquireExploratoryCarrier() {
        try(var ordinary=BPhysicalWork.create(CancellationProbe.NONE)) {
            assertThrows(IllegalArgumentException.class,()->BSharedFeasibilityInputs.tiny(ordinary));
        }
    }

    @Test void cancelledExploratoryAttemptReturnsNoSuccessfulPrefix() {
        assertThrows(java.util.concurrent.CancellationException.class,
            ()->BSharedFeasibilityComparison.executeTiny(()->true));
    }

    @Test void originalCasesRequireExplicitCiOptInBeforeCapture() {
        try(var work=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            assertThrows(IllegalStateException.class,()->BSharedFeasibilityInputs.capture("strong-parallel-gap",work));
        }
    }

    @Test void commandLineRejectsUnknownCommandsBeforeAnyAcquisition(){
        assertThrows(IllegalArgumentException.class,()->BSharedFeasibilityTool.main(new String[]{"unknown"}));
        assertThrows(IllegalStateException.class,()->BSharedFeasibilityTool.main(new String[]{"export","build/not-created-by-local-export"}));
    }

    @Test void baselineCheckerHonorsExpiredOriginalClockBeforeReadingInputs()throws Exception{
        var command=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-cp",System.getProperty("wayheatmaptracer.sharedFeasibilityTestClasspath"),ExpiredCheckerProbe.class.getName());
        command.environment().put("CI","true");command.environment().put("V022_RUN_SHARED_FEASIBILITY","true");
        var child=command.redirectErrorStream(true).start();
        try{assertTrue(child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));
            String output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,child.exitValue(),output);assertTrue(output.contains("ORIGINAL_CLOCK_EXPIRED_BEFORE_INPUT"),output);
        }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}}
    }
    /** Actual checker entry with child-only opt-in; expired before any input read. */
    public static final class ExpiredCheckerProbe {
        public static void main(String[] args)throws Exception{
            try{BSharedFeasibilityTool.checkBaselineSince("strong-parallel-gap",java.nio.file.Path.of("build/nonexistent-input"),
                java.nio.file.Path.of("build/nonexistent-attempt"),System.nanoTime()-301_000_000_000L);
                throw new AssertionError("Expected missing synthetic input after extended-clock admission");
            }catch(java.nio.file.NoSuchFileException expected){System.out.println("EXTENDED_CLOCK_ADMITTED_BEFORE_INPUT");}
            try{BSharedFeasibilityTool.checkBaselineSince("strong-parallel-gap",java.nio.file.Path.of("build/nonexistent-input"),
                java.nio.file.Path.of("build/nonexistent-attempt"),System.nanoTime()-901_000_000_000L);
                throw new AssertionError("Checker ignored original deadline");
            }catch(java.util.concurrent.CancellationException expected){System.out.println("ORIGINAL_CLOCK_EXPIRED_BEFORE_INPUT");}
        }
    }

    @Test void settingsRefuseUnregisteredFamilyMembers() {
        assertThrows(IllegalArgumentException.class,()->new BSharedFeasibilityComparison.Settings(0,1));
        assertThrows(IllegalArgumentException.class,()->new BSharedFeasibilityComparison.Settings(1,.5));
        assertEquals(2,new BSharedFeasibilityComparison.Settings(4,1).etaIndex());
    }

    @Test void actualSolverInvocationCountExcludesRejectedPreflightAndSecondSolve(){
        try(var work=BPhysicalWork.createExploratory(CancellationProbe.NONE);
            var source=BSharedFeasibilityInputs.tiny(work);
            var table=BPhysicalPairTable.buildExploratory(work,source,EvidenceModelParameters.defaults(),1)){
            assertEquals(0,table.solverInvocations());
            assertThrows(IllegalArgumentException.class,()->table.solveStoredRaw1(new TraceBudgets(96,8000000,128000000,2,1),0));
            assertEquals(0,table.solverInvocations());
            assertTrue(table.solveStoredRaw1(new TraceBudgets(96,8000000,128000000,1,1),0).result().mapPath().isPresent());
            assertEquals(1,table.solverInvocations());
            assertThrows(IllegalStateException.class,()->table.solveStoredRaw1(new TraceBudgets(96,8000000,128000000,1,1),0));
            assertEquals(1,table.solverInvocations());
        }
    }

    @Test void exploratorySourceNeverSatisfiesQualifiedRuntimeAuthority(){
        try(var work=BPhysicalWork.createExploratory(CancellationProbe.NONE);
            var source=BSharedFeasibilityInputs.tiny(work);
            var bound=BSharedPositionEvidence.bindExploratory(source,work)){
            assertThrows(UnsupportedOperationException.class,bound::requireRuntimeAuthority);
        }
    }

    @Test void sourceGeneratedPosteriorDetectsSharedRowAndConnectionBypasses()throws Exception{
        //Original analytical graph is1/3/1, defined before execution. No
        //original-case/native lattice is pruned to obtain these three cells.
        var p=new EvidenceModelParameters("source-consumer-control",1,0,0,0,0,1,1,0);
        try(var work=BPhysicalWork.createExploratory(CancellationProbe.NONE);
            var source=BSharedFeasibilityInputs.tinyThree(work);
            var table=BPhysicalPairTable.buildExploratory(work,source,p,4)){
            @SuppressWarnings("unchecked") var graph=(List<InferenceProfile>)member(table,"graph");
            assertEquals(List.of(1,3,1),graph.stream().map(r->r.cells().size()).toList());
            double[] j=((double[])member(table,"values")).clone();
            assertEquals(6,j.length);
            var input=source.input();
            var baseline=new ProbabilisticObservationModel().evaluate(input.facts.get(1).baseline(),input.lattices.get(1),p,true,work.owner());
            double[] full=new double[3],noConnection=new double[3],oldRows=new double[3];
            for(int s=0;s<3;s++){
                assertEquals(BPhysicalPairTable.CERTIFIED,table.slotStatus(0,0,s));
                assertEquals(BPhysicalPairTable.CERTIFIED,table.slotStatus(1,s,0));
                assertTrue(Double.isFinite(j[s])&&Double.isFinite(j[3+s]));
                double width=graph.get(1).cells().get(s).quadratureWidthMeters();
                //Unit chainage, zero non-data weights, exact fixed anchors:
                //independent3-path enumeration needs no nativecost helper.
                full[s]=-graph.get(1).unaryCost(s)-j[s]-j[3+s]+StrictMath.log(width);
                noConnection[s]=-graph.get(1).unaryCost(s)+StrictMath.log(width);
                oldRows[s]=-baseline.unaryCost(s)-j[s]-j[3+s]+StrictMath.log(width);
            }
            var expected=normalize(full);
            assertTrue(difference(expected,normalize(noConnection))>1e-10,"Source control must discriminate removal of eta");
            assertTrue(difference(expected,normalize(oldRows))>1e-10,"Source control must discriminate baseline-only rows");
            var actual=table.solveStoredRaw1(new TraceBudgets(96,8000000,128000000,1,1),2).result();
            assertTrue(actual.mapPath().isPresent());
            assertArrayEquals(expected,actual.positionMarginals().get(1),1e-12);
            assertEquals(1,table.solverInvocations());
        }
    }
    private static Object member(Object object,String name)throws Exception{
        var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static double[] normalize(double[] log){
        double max=java.util.Arrays.stream(log).max().orElseThrow(),sum=0;double[] result=new double[log.length];
        for(int i=0;i<log.length;i++){result[i]=StrictMath.exp(log[i]-max);sum+=result[i];}
        for(int i=0;i<result.length;i++)result[i]/=sum;return result;
    }
    private static double difference(double[] a,double[] b){double d=0;for(int i=0;i<a.length;i++)d=Math.max(d,Math.abs(a[i]-b[i]));return d;}
}
