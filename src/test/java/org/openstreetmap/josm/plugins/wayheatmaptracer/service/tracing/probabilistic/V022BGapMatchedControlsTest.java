package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Source controls only: a green harness is never model or production qualification. */
class V022BGapMatchedControlsTest {
    private static final int[] ETAS={0,1,2,4,8,16,32,64};
    private static final List<String> CASES=List.of("strong-parallel-gap","connected-straight","connected-short-bend","connected-sharp-apex",
            "faint-six-metre-bend","faint-twelve-metre-bend","genuine-junction","positive-twenty-five-representation","negative-twenty-five-representation",
            "flecks-versus-faint-coherent","valid-sparse-gap","invalid-unknown-gap","censored-flank","strong-modulated-versus-quiet-faint",
            "unknown-detour","uniform-null","alternating-null","half-pitch-alias","quarter-phase-alias","reverse-original-mesh");
    private static final List<MetricPoint> SOURCE=List.of(new MetricPoint(8,37),new MetricPoint(32,37));
    private static final EvidenceModelParameters PARAMETERS=EvidenceModelParameters.defaults();
    private static final TraceBudgets SOLVE_BUDGETS=new TraceBudgets(96,8_000_000,128_000_000,1,1);
    private static final BObservationKernel B0=new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(1,1,0));
    private static final Map<String,CaseResult> RESULTS=new LinkedHashMap<>();
    private static final Map<String,Map<String,Object>> CONTROL_EVIDENCE=new LinkedHashMap<>();
    private static final Map<String,Map<String,Object>> ADMISSION_EVIDENCE=new LinkedHashMap<>();
    private static final Path OUTPUT=Path.of("build/gap-task5-matrix");
    static Stream<String> cases(){return CASES.stream();}
    static Stream<String> refusedAdmissionCases(){return Stream.of("connected-sharp-apex","faint-six-metre-bend","genuine-junction","valid-sparse-gap");}
    private static AttemptMemoryLedger newComparisonLedger(){return new AttemptMemoryLedger(BGapEvidence.BYTES);}

    @Test void newComparisonIncrementallyRefusesBeyond64MiBBeforePublication() throws Exception {
        boolean[] published={false};var ledger=newComparisonLedger();
        try(var owner=ledger.rootOwner()) {
            var tree=BGapTestFixture.ownerTree(owner);
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->{
                try(var reservation=owner.reserve(64L*1024*1024+1)){published[0]=true;}
            });
            assertFalse(published[0]);assertEquals(0,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));
        }
        var original=AttemptMemoryLedger.production();
        try(var owner=original.rootOwner();var reservation=owner.reserve(64L*1024*1024+1)) {
            assertEquals(64L*1024*1024+1,owner.currentBytes(),"ordinary global production limit remains unchanged");
        }
    }

    @Test void admissionRefusalIsPublishedAndCachedBeforeOriginalAssertionPropagates() throws Exception {
        String id="controlled-admission-refusal";Path path=OUTPUT.resolve(id+".json");
        var priorResult=RESULTS.remove(id);var priorEvidence=CONTROL_EVIDENCE.remove(id);
        byte[] priorBytes=Files.exists(path)?Files.readAllBytes(path):null;
        var rows=new ArrayList<Row>();for(int eta:ETAS)rows.add(unavailable(eta,eta==0?"ADMISSION_REFUSED":"UNVISITED_AFTER_ADMISSION_REFUSAL"));
        var refusal=new CaseResult(id,SOURCE,rows,17,19,0,0,0,35_043_632,false,false,"1".repeat(64),"unavailable","unavailable","unavailable");
        var details=Map.<String,Object>of("availability","ADMISSION_REFUSED","admitted",false,"maximumStates",96,
                "nativeForwardBackwardBound",22_491_347L,"pairBound",127_112L,"completeScoreBound",-1,
                "builderReason","continuation cannot retain every baseline state","inferenceCalls",0);
        try {
            var failure=assertThrows(AssertionError.class,()->{
                publishAdmissionRefusal(refusal,details);
                assertTrue(false,"unchanged original graph refused "+id);
            });
            assertTrue(failure.getMessage().contains("unchanged original graph refused "+id));
            assertSame(refusal,RESULTS.get(id),"refusal must be cached before the original assertion");
            assertTrue(Files.exists(path),"refusal must be published before the original assertion");
            var receipt=BIdentificationReceipt.parse(Files.readAllBytes(path));
            assertEquals("1".repeat(64),receipt.get("snapshotHash"));assertEquals(17,((Number)receipt.get("profiles")).intValue());
            assertEquals(19,((Number)receipt.get("modes")).intValue());assertNull(receipt.get("factsAndLatticeHash"));
            assertNull(receipt.get("originalPairInventory"));assertNull(receipt.get("fullTwentyFiveMetreDomain"));
            assertEquals(details,CONTROL_EVIDENCE.get(id));
            var slots=(List<?>)receipt.get("rows");assertEquals(8,slots.size());
            for(int i=0;i<slots.size();i++) {
                var slot=(Map<?,?>)slots.get(i);assertEquals(false,slot.get("actualSolve"));assertNull(slot.get("status"));
                assertNull(slot.get("wholeRouteGeometryPassed"));assertNull(slot.get("sourceFullyObserved"));assertEquals(false,slot.get("requiredControlPassed"));
                assertEquals(i==0?"UNAVAILABLE_BEFORE_SOLVE":"UNVISITED",slot.get("executionOutcome"));
            }
            assertFalse(matrixComplete(Map.of(id,refusal),1));
            assertThrows(AssertionError.class,()->result(id),"cached refusal must not become a green repeated case");
        } finally {
            if(priorResult==null)RESULTS.remove(id);else RESULTS.put(id,priorResult);
            if(priorEvidence==null)CONTROL_EVIDENCE.remove(id);else CONTROL_EVIDENCE.put(id,priorEvidence);
            if(priorBytes==null)Files.deleteIfExists(path);else Files.write(path,priorBytes);
        }
    }

    /** Diagnosis only: stops at unchanged original admission; no adapter, table or inference. */
    @ParameterizedTest(name="admission-only-{0}") @MethodSource("refusedAdmissionCases")
    void retainedCiAdmissionRefusalsHaveExactOriginalInventory(String id) throws Exception {
        var ledger=AttemptMemoryLedger.production();var output=new LinkedHashMap<String,Object>();
        try(var owner=ledger.rootOwner();var render=owner.reserve(32L*1024*1024);var metadata=owner.reserve(1024L*1024)) {
            var evidence=capture(id,source(id));var factory=new ProbabilisticProfileFactory();
            var raw=factory.create(source(id),factory.profileChainage(source(id),1.5),25,true,evidence,evidence.fields().get("scalar"),
                    PARAMETERS,CancellationProbe.NONE,true,owner);
            var facts=new BIdentificationFactsExtractor().extract(evidence,raw,CancellationProbe.NONE,owner);
            var proposals=ProbabilisticContinuationProposals.build(raw,CancellationProbe.NONE,owner,PARAMETERS.localization());
            var plan=ProbabilisticLatticeAdmission.select(raw,proposals,TraceBudgets.defaults(),CancellationProbe.NONE,owner);
            var rows=new ArrayList<Map<String,Object>>();long[] counts=new long[raw.size()];
            for(int i=0;i<raw.size();i++)try(var scratch=owner.child("diagnostic-admission-row")) {
                double proposal=proposals[i];
                var baseline=new ProbabilisticStateBuilder().build(raw.get(i),96,scratch);
                var proposed=new ProbabilisticStateBuilder().buildWithProposal(raw.get(i),96,proposals[i],scratch);
                var row=new LinkedHashMap<String,Object>();row.put("row",i);row.put("modes",raw.get(i).modes().size());
                row.put("censoredModes",raw.get(i).censoredModes().size());row.put("proposal",Double.isFinite(proposals[i])?proposals[i]:null);
                row.put("baselineStatus",baseline.status().name());row.put("baselineExplanation",baseline.explanation());
                counts[i]=baseline.lattice().map(l->(long)l.cells().size()).orElse(-1L);row.put("baselineCount",counts[i]);
                row.put("mandatoryCount",baseline.retainedMandatoryOffsets().size());row.put("proposedStatus",proposed.status().name());
                row.put("proposedExplanation",proposed.explanation());row.put("proposedCount",proposed.lattice().map(l->l.cells().size()).orElse(-1));
                row.put("nearestNativeProposalDistance",Double.isFinite(proposals[i])&&baseline.lattice().isPresent()?
                        baseline.lattice().orElseThrow().cells().stream().mapToDouble(c->Math.abs(c.offsetMeters()-proposal)).min().orElseThrow():null);
                rows.add(row);
            }
            long pairs=0,triples=0;for(int i=1;i<counts.length;i++)pairs+=counts[i-1]*counts[i];
            for(int i=2;i<counts.length;i++)triples+=counts[i-2]*counts[i-1]*counts[i];
            long fb=counts[0]*counts[1]+2*triples;
            output.put("case",id);output.put("snapshotHash",evidence.canonicalHash());output.put("profiles",raw.size());output.put("facts",facts.size());
            output.put("admitted",plan.admitted());output.put("coarsened",plan.coarsened());output.put("maximumStates",plan.maximumStates());
            output.put("nativeForwardBackwardBound",plan.nativeForwardBackwardBound());output.put("completeScoreBound",plan.completeScoreBound());
            output.put("pairBound",plan.pairBound());output.put("independentNativeForwardBackwardBound",fb);output.put("independentNativePairBound",pairs);
            output.put("rows",rows);output.put("currentOwnedBytes",owner.currentBytes());output.put("peakOwnedBytes",owner.peakBytes());
            output.put("adapterEvaluations",0);output.put("tableBuilds",0);output.put("inferenceCalls",0);
            byte[] bytes=BIdentificationReceipt.canonical(output).getBytes(java.nio.charset.StandardCharsets.UTF_8);assertTrue(bytes.length<=65536);
            Path path=Path.of("build/gap-task5-admission-diagnosis",id+".json");Files.createDirectories(path.getParent());Files.write(path,bytes);
            assertFalse(plan.admitted(),"retained CI refusal must reproduce unchanged");
        } finally {assertEquals(0,ledger.currentBytes());}
    }

    @Test void declaredManifestAndIndependentWholeRouteOracleAreNonvacuous() throws Exception {
        Map<String,Object> manifest;
        try(var stream=V022BGapMatchedControlsTest.class.getResourceAsStream("/b-observed-gap/matched-controls.json")) {
            assertNotNull(stream);manifest=BIdentificationReceipt.parse(stream.readAllBytes());
        }
        assertEquals(CASES,manifest.get("recipes"));
        assertEquals(Arrays.stream(ETAS).boxed().toList(),((List<?>)manifest.get("etas")).stream().map(v->((Number)v).intValue()).toList());
        assertTrue(manifest.get("status").toString().startsWith("PREREGISTERED_BEFORE_RESULTS"));
        assertTrue(inTruth("strong-parallel-gap",List.of(p(8,37),p(32,37))));
        for(double x:new double[]{9,15,20,29,31})
            assertFalse(inTruth("strong-parallel-gap",List.of(p(8,37),p(x,37),p(x+.25,43),p(32,37))),
                    "a jump cannot hide at any longitudinal position");
        assertFalse(inTruth("connected-sharp-apex",List.of(p(8,37),p(32,37))),"a shortcut must not pass the supported sharp apex");
        assertTrue(inTruth("connected-sharp-apex",List.of(p(8,37),p(17,37),p(20,40),p(23,37),p(32,37))));
    }

    @Test void connectedStraightPipelineSmoke() throws Exception {verify(result("connected-straight"));}

    @Test void optionalAdmissionReceiptRetainsDeclineIdentityAndActualPrivateWork() {
        var proposals=V022OptionalOverflowAdmissionTest.proposals(5);proposals[2]=-0.0;
        var ledger=newComparisonLedger();
        try(var owner=ledger.rootOwner();var selection=ProbabilisticOptionalOverflowAdmission.select(
                V022OptionalOverflowAdmissionTest.profiles(5,0),proposals,TraceBudgets.defaults(),CancellationProbe.NONE,owner)) {
            var receipt=admissionReceipt(selection,5);
            assertEquals(ProbabilisticOptionalOverflowAdmission.POLICY_ID,receipt.get("policy"));assertEquals(1,receipt.get("declinedOptionalCount"));
            var decisions=(List<?>)receipt.get("rows");var declined=(Map<?,?>)decisions.get(2);
            assertEquals("DECLINED_NATIVE_FULL",declined.get("decision"));assertEquals("8000000000000000",declined.get("originalProposalRawBits"));
            assertEquals("7ff8000000000000",declined.get("effectiveProposalRawBits"));
            assertEquals(0L,receipt.get("materializationBuilderInvocations"));
            selection.buildProfile(2,owner,CancellationProbe.NONE);updateAdmissionWork(receipt,selection.work());
            assertEquals(1L,receipt.get("materializationBuilderInvocations"));
            assertTrue(((Number)receipt.get("preflightAndMaterializationUnits")).longValue()<=((Number)receipt.get("privateWorkUpper")).longValue());
        }
        assertEquals(0,ledger.currentBytes());
    }

    /** Exact four previously refused synthetic recipes; one shared newly admitted graph per case, eight solves. */
    @ParameterizedTest(name="optional-policy-{0}") @MethodSource("refusedAdmissionCases")
    void previouslyRefusedCasesUseExplicitOptionalPolicy(String id) throws Exception {
        var result=result(id);verify(result);
        var policy=ADMISSION_EVIDENCE.get(id);assertNotNull(policy);
        assertEquals(ProbabilisticOptionalOverflowAdmission.POLICY_ID,policy.get("policy"));
        assertTrue(((Number)policy.get("declinedOptionalCount")).intValue()>0);
        assertEquals(false,policy.get("coarsened"));assertEquals(8,result.rows.size());
        assertTrue(result.rows.stream().allMatch(r->r.actualSolve),"all eight fixed settings actually execute");
        assertTrue(result.rows.stream().allMatch(V022BGapMatchedControlsTest::completed),"each returned a completed path");
    }

    @Test void unavailableSlotsAreReportedWithoutPretendingTheyWereSolved() {
        var rows=new ArrayList<Row>();
        for(int eta:ETAS)rows.add(new Row(eta,false,ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,List.of(),List.of(),
                Double.NaN,0,"unavailable","Optional.empty","FIXED_RESOURCE_REFUSAL",false,false,false));
        var unavailable=new CaseResult("connected-straight",SOURCE,rows,17,0,0,0,0,0,false,false,"unavailable","unavailable","unavailable","unavailable");
        assertDoesNotThrow(()->verify(unavailable));
        for(var row:rows){assertEquals(false,row.receipt().get("actualSolve"));assertEquals(false,row.receipt().get("requiredControlPassed"));
            assertNull(row.receipt().get("status"),"an unexecuted slot has no returned solver status");}
    }

    @Test void failedSolverStatusCannotClaimPassingGeometryAsAControlSuccess() {
        var failed=new Row(1,true,ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,SOURCE,List.of(0,0),0,0,
                "unavailable","Optional.empty","FIXED_NUMERIC_FAILURE",true,true,true);
        assertEquals(false,failed.receipt().get("requiredControlPassed"));
    }

    @Test void enabledFailureClosesCaseAndLeavesLaterSettingsUnvisited() {
        for(boolean throwsFailure:new boolean[]{false,true}) {
            var rows=new ArrayList<Row>();int[] calls={0},closed={0};
            appendEnabled(rows,eta->{calls[0]++;if(throwsFailure)throw new IllegalArgumentException("controlled refusal");
                return new Row(eta,true,ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,List.of(),List.of(),Double.NaN,3,
                        "actual-failure-work","Optional.empty","CONTROLLED_RETURNED_FAILURE",false,false,false);},()->closed[0]++);
            assertEquals(1,calls[0]);assertEquals(1,closed[0]);assertEquals(7,rows.size());
            for(int i=1;i<rows.size();i++) {
                assertFalse(rows.get(i).actualSolve);assertEquals("UNVISITED",rows.get(i).receipt().get("executionOutcome"));
                assertEquals(false,rows.get(i).receipt().get("requiredControlPassed"));
            }
        }
    }

    @Test void censoredReceiptCannotImpersonateAWholeRouteGeometryPass() {
        var row=new Row(1,true,ProbabilisticInferenceResult.Status.COMPLETE,SOURCE,List.of(0,0),0,1,
                "actual-work","Optional[present]","CONTROLLED_CAPABILITY_ONLY",true,true,true);
        assertNull(receiptForCase("censored-flank",row).get("wholeRouteGeometryPassed"));
        assertEquals("UNAVAILABLE_CENSORED_POSITION",receiptForCase("censored-flank",row).get("wholeRouteGeometryVerdict"));
        assertEquals("NOT_GRANTED_SOURCE_CONTROL_ONLY",receiptForCase("censored-flank",row).get("modelQualification"));
    }

    @Test void uncompletedReceiptsHaveUnavailableGeometryAndSupport() {
        var rows=List.of(unavailable(1,"UNVISITED_AFTER_WHOLE_CASE_FAILURE"),
                new Row(1,true,ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,List.of(),List.of(),Double.NaN,0,
                        "unavailable","Optional.empty","THROWN_SOLVER_FAILURE_ILLEGAL_STATE",false,false,false),
                new Row(1,true,ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,List.of(),List.of(),Double.NaN,3,
                        "actual-resource-work","Optional.empty","CONTROLLED_RETURNED_RESOURCE",false,false,false),
                new Row(1,true,ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,List.of(),List.of(),Double.NaN,4,
                        "actual-numeric-work","Optional.empty","CONTROLLED_RETURNED_NUMERIC",false,false,false),
                new Row(1,true,ProbabilisticInferenceResult.Status.NO_ROUTE,List.of(),List.of(),Double.NaN,5,
                        "actual-no-route-work","Optional.empty","CONTROLLED_RETURNED_NO_ROUTE",false,false,false));
        for(var row:rows) {
            var factual=row.receipt();assertNull(factual.get("wholeRouteGeometryPassed"));assertNull(factual.get("sourceFullyObserved"));
            for(String id:List.of("connected-straight","censored-flank","positive-twenty-five-representation")) {
                var receipt=receiptForCase(id,row);
                assertNull(receipt.get("wholeRouteGeometryPassed"));assertNull(receipt.get("sourceFullyObserved"));
                assertEquals("UNAVAILABLE_NO_RETURNED_PATH",receipt.get("wholeRouteGeometryVerdict"));
                assertEquals(factual.get("actualSolve"),receipt.get("actualSolve"));
                assertEquals(factual.get("executionOutcome"),receipt.get("executionOutcome"));
                assertEquals(factual.get("status"),receipt.get("status"));assertEquals(false,receipt.get("requiredControlPassed"));
            }
        }
        var escapingPoints=List.of(p(8,37),p(20,50),p(32,37));assertFalse(inTruth("connected-straight",escapingPoints));
        var escape=new Row(1,true,ProbabilisticInferenceResult.Status.COMPLETE,escapingPoints,List.of(0,1,0),
                1,7,"actual-work","Optional[present]","CONTROLLED_COMPLETED_ESCAPE",inTruth("connected-straight",escapingPoints),true,false);
        var receipt=receiptForCase("connected-straight",escape);
        assertEquals("FAIL",receipt.get("wholeRouteGeometryVerdict"));assertEquals(false,receipt.get("wholeRouteGeometryPassed"));
        assertEquals(true,receipt.get("sourceFullyObserved"));assertEquals("COMPLETE",receipt.get("status"));
        assertEquals("UNAVAILABLE_CENSORED_POSITION",receiptForCase("censored-flank",escape).get("wholeRouteGeometryVerdict"));
        assertEquals("UNAVAILABLE_REPRESENTATION_ONLY",receiptForCase("positive-twenty-five-representation",escape).get("wholeRouteGeometryVerdict"));
    }

    @Test void censoredWitnessRequiresReceiptUnavailabilityRatherThanUnsupportedSource() {
        assertTrue(censoredAvailable(2,4,4,0,Map.of("UNAVAILABLE_RECEIPT",4L),9,9,0));
        assertFalse(censoredAvailable(2,4,4,0,Map.of("UNAVAILABLE_SOURCE_GRID",4L),9,9,0));
        assertFalse(censoredAvailable(0,4,4,0,Map.of("UNAVAILABLE_RECEIPT",4L),9,9,0));
        assertFalse(censoredAvailable(2,4,3,0,Map.of("UNAVAILABLE_RECEIPT",3L,"OBSERVED_NO_GAP",1L),9,9,0));
        assertFalse(censoredAvailable(2,4,4,1,Map.of("UNAVAILABLE_RECEIPT",4L),9,9,0));
        assertFalse(censoredAvailable(2,4,4,0,Map.of("UNAVAILABLE_RECEIPT",4L),9,8,0));
        assertFalse(censoredAvailable(2,4,4,0,Map.of("UNAVAILABLE_RECEIPT",4L),9,9,1));
    }

    @Test void ordinaryNoRouteStillExecutesEveryFixedSetting() {
        var rows=new ArrayList<Row>();int[] calls={0},closed={0};
        appendEnabled(rows,eta->{calls[0]++;return new Row(eta,true,ProbabilisticInferenceResult.Status.NO_ROUTE,List.of(),List.of(),Double.NaN,
                1,"actual-no-route-work","Optional.empty","CONTROLLED_NO_ROUTE",false,false,false);},()->closed[0]++);
        assertEquals(7,calls[0]);assertEquals(0,closed[0]);assertEquals(7,rows.size());
        for(var row:rows){assertTrue(row.actualSolve);assertFalse(row.controlPassed);assertEquals("NO_ROUTE",row.receipt().get("status"));}
    }

    @Test void lastSettingFailureCannotMakeMatrixComplete() {
        var rows=new ArrayList<Row>();
        for(int eta:ETAS)rows.add(new Row(eta,true,eta==64?ProbabilisticInferenceResult.Status.RESOURCE_LIMIT:ProbabilisticInferenceResult.Status.COMPLETE,
                eta==64?List.of():SOURCE,List.of(),eta==64?Double.NaN:0,1,"actual-work",eta==64?"Optional.empty":"Optional[present]",
                "CONTROLLED_LAST_FAILURE",false,false,false));
        var result=new CaseResult("connected-straight",SOURCE,rows,17,0,0,0,0,0,false,false,"controlled","controlled","controlled","controlled");
        assertFalse(matrixComplete(Map.of("connected-straight",result),1));
    }

    @ParameterizedTest(name="independent-{0}") @MethodSource("cases")
    void independentDeclaredRasterGeometryAndAvailabilityOracles(String id) {
        var evidence=capture(id,source(id));
        assertFalse(fullyObserved(evidence,List.of(p(8,37))),"one point is not a supported route");
        var straight=List.of(p(8,37),p(32,37));
        boolean invalid=id.equals("invalid-unknown-gap")||id.equals("unknown-detour");
        assertEquals(!invalid,fullyObserved(evidence,straight));
        assertFalse(inTruth(id,List.of(p(8,37),p(20,50),p(32,37))),"an unrelated excursion is not accepted");
        if(id.contains("bend")||id.equals("connected-sharp-apex")) {
            double width=id.equals("connected-sharp-apex")||id.equals("faint-six-metre-bend")?6:12;
            double height=id.equals("connected-short-bend")?2:3;
            var bend=List.of(p(8,37),p(20-width/2,37),p(20,37+height),p(20+width/2,37),p(32,37));
            assertTrue(inTruth(id,bend));assertFalse(inTruth(id,straight));
            assertTrue(intensity(id,20,37+height)>intensity(id,20,37));
        } else assertTrue(inTruth(id,straight));
        if(id.equals("strong-parallel-gap")||id.equals("strong-modulated-versus-quiet-faint")||id.equals("unknown-detour")) {
            for(double x:new double[]{9,19,21,31})assertTrue(intensity(id,x,37)>intensity(id,x,40));
            assertFalse(inTruth(id,List.of(p(8,37),p(9,37),p(10,43),p(30,43),p(32,37))));
        }
        if(id.equals("valid-sparse-gap"))assertEquals(.02,intensity(id,20,37),0);
        if(id.equals("flecks-versus-faint-coherent")) {
            assertTrue(intensity(id,11,43)>intensity(id,11,37));
            assertTrue(intensity(id,14,37)>intensity(id,14,43));
        }
        if(id.equals("strong-modulated-versus-quiet-faint"))for(double x:new double[]{11,17,23,29})
            assertTrue(intensity(id,x,37)>intensity(id,x,43),"modulation never authorizes buying the faint strand");
        if(id.contains("twenty-five-representation")) {
            double target=id.startsWith("positive")?61:13;
            assertTrue(intensity(id,20,target)>intensity(id,20,37));assertEquals(24,Math.abs(target-37),0);
        }
        if(id.equals("censored-flank"))assertTrue(intensity(id,20,62)>intensity(id,20,61),"peak lies beyond the last interior sample");
        if(id.equals("genuine-junction"))assertTrue(inTruth(id,List.of(p(8,37),p(20,37),p(20,45),p(20,37),p(32,37))));
        if(id.equals("uniform-null"))assertEquals(intensity(id,8,13),intensity(id,20,61),0);
        if(id.equals("alternating-null"))assertEquals(intensity(id,8,37),intensity(id,20,37),0);
        if(id.contains("alias")){assertEquals(.5,evidence.resolution().outputRasterPitchMeters(),0);assertEquals(1,evidence.resolution().nativePitchMeters().orElseThrow(),0);}
        if(id.equals("reverse-original-mesh"))assertEquals(SOURCE.get(1),source(id).get(0));
    }

    @ParameterizedTest(name="{0}") @MethodSource("cases")
    void actualSourcePipelineProducesCompleteDeclaredEightSettingMatrix(String id) throws Exception {verify(result(id));}

    private static void verify(CaseResult result) {
        assertEquals(8,result.rows.size(),"all fixed settings must be recorded");
        assertArrayEquals(ETAS,result.rows.stream().mapToInt(Row::eta).toArray());
        for(var row:result.rows) {
            assertTrue(row.transitions<=SOLVE_BUDGETS.maximumTransitions());
            if(!row.actualSolve) {
                assertTrue(row.points.isEmpty());assertTrue(row.states.isEmpty());assertEquals(0,row.transitions);
                assertEquals("unavailable",row.work);assertEquals("Optional.empty",row.completion);assertFalse(row.controlPassed);
            } else if(completed(row)) {
                assertEquals(result.source.get(0),row.points.get(0));assertEquals(result.source.get(1),row.points.get(row.points.size()-1));
                assertEquals(result.profileCount,row.states.size());assertFalse(row.work.equals("unavailable"));
                assertFalse(row.completion.equals("Optional.empty"));assertTrue(Double.isFinite(row.energy));
            } else assertFalse(row.controlPassed,"failed actual work is not a passing control");
        }
        if(result.id.equals("uniform-null")||result.id.equals("alternating-null")) {
            assertEquals(0,result.modeCount,"declared null toy must actually have no extracted modes");
            var baseline=result.rows.get(0);
            for(var row:result.rows) {
                assertEquals(baseline.points,row.points);assertEquals(baseline.states,row.states);
                assertEquals(Double.doubleToRawLongBits(baseline.energy),Double.doubleToRawLongBits(row.energy));
            }
        }
    }

    private record Row(int eta,boolean actualSolve,ProbabilisticInferenceResult.Status status,List<MetricPoint> points,List<Integer> states,
            double energy,long transitions,String work,String completion,String explanation,boolean geometryPassed,boolean sourceObserved,boolean controlPassed) {
        Row { if(!actualSolve||status==ProbabilisticInferenceResult.Status.RESOURCE_LIMIT||status==ProbabilisticInferenceResult.Status.NUMERIC_FAILURE
                ||status==ProbabilisticInferenceResult.Status.NO_ROUTE||points.isEmpty())controlPassed=false; }
        Map<String,Object> receipt() {
            var map=new LinkedHashMap<String,Object>();map.put("eta",eta);map.put("actualSolve",actualSolve);
            map.put("status",actualSolve&&!work.equals("unavailable")?status.name():null);
            map.put("executionOutcome",actualSolve?(completed(this)?"RETURNED_PATH":"ATTEMPTED_FAILURE"):
                    explanation.startsWith("UNVISITED")?"UNVISITED":"UNAVAILABLE_BEFORE_SOLVE");
            map.put("points",points.stream().map(p->List.of(p.xMeters(),p.yMeters())).toList());map.put("states",states);
            map.put("energy",Double.isFinite(energy)?energy:null);map.put("energyBits",Long.toHexString(Double.doubleToRawLongBits(energy)));
            map.put("transitions",actualSolve&&work.equals("unavailable")?null:transitions);map.put("work",work);map.put("completion",completion);map.put("explanation",explanation);
            map.put("wholeRouteGeometryPassed",completed(this)?geometryPassed:null);map.put("sourceFullyObserved",completed(this)?sourceObserved:null);
            map.put("requiredControlPassed",controlPassed);
            map.put("modelQualification","NOT_GRANTED_SOURCE_CONTROL_ONLY");return map;
        }
    }
    private static boolean completed(Row row) {
        return row.actualSolve&&!row.points.isEmpty()&&row.status!=ProbabilisticInferenceResult.Status.RESOURCE_LIMIT
                &&row.status!=ProbabilisticInferenceResult.Status.NUMERIC_FAILURE&&row.status!=ProbabilisticInferenceResult.Status.NO_ROUTE;
    }
    private static boolean stopsCase(Row row) {
        return !row.actualSolve||row.status==ProbabilisticInferenceResult.Status.RESOURCE_LIMIT||row.status==ProbabilisticInferenceResult.Status.NUMERIC_FAILURE;
    }
    private static Map<String,Object> receiptForCase(String id,Row row) {
        var receipt=row.receipt();receipt.put("requiredPredicate",id.equals("censored-flank")?"CENSORED_UNAVAILABLE_ZERO_NO_NEW_BARRIER":
                id.contains("twenty-five-representation")?"ORIGINAL_FULL_DOMAIN_REPRESENTATION":
                id.equals("invalid-unknown-gap")||id.equals("unknown-detour")?"UNSUPPORTED_OR_UNKNOWN_ROUTE_NOT_ELIGIBLE":"WHOLE_ROUTE_GEOMETRY_AND_OBSERVED_SUPPORT");
        receipt.put("wholeRouteGeometryVerdict",!completed(row)?"UNAVAILABLE_NO_RETURNED_PATH":id.equals("censored-flank")?"UNAVAILABLE_CENSORED_POSITION":
                id.contains("twenty-five-representation")?"UNAVAILABLE_REPRESENTATION_ONLY":row.geometryPassed?"PASS":"FAIL");
        if(id.equals("censored-flank")||id.contains("twenty-five-representation"))receipt.put("wholeRouteGeometryPassed",null);
        return receipt;
    }
    private static Row unavailable(int eta,String reason) {
        return new Row(eta,false,ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,List.of(),List.of(),Double.NaN,0,
                "unavailable","Optional.empty",reason,false,false,false);
    }
    private static void appendEnabled(List<Row> rows,java.util.function.IntFunction<Row> run,Runnable discard) {
        boolean stopped=false;
        for(int eta:ETAS)if(eta!=0) {
            if(stopped){rows.add(unavailable(eta,"UNVISITED_AFTER_WHOLE_CASE_FAILURE"));continue;}
            Row row;
            try {row=run.apply(eta);}catch(RuntimeException failure){row=unavailable(eta,"ENABLED_CALLBACK_FAILURE_"+safeFailure(failure));}
            rows.add(row);if(stopsCase(row)){discard.run();stopped=true;}
        }
    }
    private static String safeFailure(RuntimeException failure) {
        return failure instanceof IllegalArgumentException?"ILLEGAL_ARGUMENT":failure instanceof IllegalStateException?"ILLEGAL_STATE":
                failure instanceof SecurityException?"SECURITY":"RUNTIME";
    }
    private static boolean censoredAvailable(int censored,long cells,long unavailable,long nonzero,Map<String,Long> statuses,
            long pairs,long pairReads,long nonzeroPairs) {
        return censored>0&&cells>0&&unavailable==cells&&nonzero==0&&pairs>0&&pairReads==pairs&&nonzeroPairs==0
                &&statuses.equals(Map.of("UNAVAILABLE_RECEIPT",cells));
    }
    private record CaseResult(String id,List<MetricPoint> source,List<Row> rows,int profileCount,int modeCount,int censoredModes,
            long pairs,long tableBytes,long peakBytes,boolean coarsened,boolean fullDomain,String sourceHash,String factsHash,
            String buildCounters,String postSolveCounters) { }
    private static void publishAdmissionRefusal(CaseResult refusal,Map<String,Object> details) throws Exception {
        CONTROL_EVIDENCE.put(refusal.id,details);publish(refusal);
    }
    private static CaseResult result(String id) throws Exception {
        if(RESULTS.containsKey(id)) {
            assertTrue(!"ADMISSION_REFUSED".equals(CONTROL_EVIDENCE.getOrDefault(id,Map.of()).get("availability")),
                    "unchanged original graph refused "+id);
            return RESULTS.get(id);
        }
        CaseResult result;
        try {result=run(id);}catch(RuntimeException failure) {
            var rows=new ArrayList<Row>();for(int eta:ETAS)rows.add(unavailable(eta,eta==0?"PIPELINE_BEFORE_SOLVE_FAILURE_"+safeFailure(failure):"UNVISITED_AFTER_WHOLE_CASE_FAILURE"));
            result=new CaseResult(id,source(id),rows,0,0,0,0,0,0,false,false,"unavailable","unavailable","unavailable","unavailable");
        }
        publish(result);return result;
    }
    private static void publish(CaseResult result) throws Exception {
        String id=result.id;RESULTS.put(id,result);
        var output=new LinkedHashMap<String,Object>();output.put("schema","observed-gap-matched-optional-overflow-result-v2");output.put("case",id);
        output.put("comparisonPolicy",ProbabilisticOptionalOverflowAdmission.POLICY_ID);
        output.put("admissionEvidence",ADMISSION_EVIDENCE.getOrDefault(id,Map.of("availability","UNAVAILABLE")));
        output.put("source",result.source.stream().map(p->List.of(p.xMeters(),p.yMeters())).toList());output.put("snapshotHash",result.sourceHash);
        boolean factual=!result.factsHash.equals("unavailable");
        boolean captured=!result.sourceHash.equals("unavailable");
        output.put("factsAndLatticeHash",factual?result.factsHash:null);output.put("profiles",captured?result.profileCount:null);output.put("modes",captured?result.modeCount:null);
        output.put("censoredModes",captured?result.censoredModes:null);output.put("originalPairInventory",factual?result.pairs:null);output.put("tableBytes",factual?result.tableBytes:null);
        output.put("peakAttemptBytes",captured?result.peakBytes:null);output.put("selectedAdmissionCoarsened",captured?result.coarsened:null);
        output.put("fullTwentyFiveMetreDomain",factual?result.fullDomain:null);output.put("coldBuildCounters",result.buildCounters);
        output.put("sourceControlEvidence",CONTROL_EVIDENCE.getOrDefault(id,Map.of("availability","UNAVAILABLE")));
        output.put("postSolveCounters",result.postSolveCounters);output.put("rows",result.rows.stream().map(r->receiptForCase(id,r)).toList());
        write(id+".json",output);System.out.println("MATCHED "+id+" controlByEta="+result.rows.stream().map(r->r.eta+":"+r.controlPassed).toList()
                +" modes="+result.modeCount+" censored="+result.censoredModes+" pairs="+result.pairs+" peak="+result.peakBytes);
    }

    private static CaseResult run(String id) throws Exception {
        var ledger=newComparisonLedger();var rows=new ArrayList<Row>();var source=source(id);
        try(var owner=ledger.rootOwner();var render=owner.reserve(32L*1024*1024);var metadata=owner.reserve(1024L*1024)) {
            var evidence=capture(id,source);var factory=new ProbabilisticProfileFactory();
            var raw=factory.create(source,factory.profileChainage(source,1.5),25,true,evidence,evidence.fields().get("scalar"),
                    PARAMETERS,CancellationProbe.NONE,true,owner);
            var facts=new BIdentificationFactsExtractor().extract(evidence,raw,CancellationProbe.NONE,owner);
            assertEquals(17,raw.size());for(int i=0;i<raw.size();i++)assertSame(raw.get(i),facts.get(i).baseline());
            int modes=raw.stream().mapToInt(p->p.modes().size()).sum(),censored=raw.stream().mapToInt(p->p.censoredModes().size()).sum();
            var proposals=ProbabilisticContinuationProposals.build(raw,CancellationProbe.NONE,owner,PARAMETERS.localization());
            try(var admission=ProbabilisticOptionalOverflowAdmission.select(raw,proposals,TraceBudgets.defaults(),CancellationProbe.NONE,owner)) {
            ADMISSION_EVIDENCE.put(id,admissionReceipt(admission,raw.size()));
            var admissionWork=admission.work();try {
            if(!admission.admitted()) {
                var details=new LinkedHashMap<String,Object>();details.put("availability","ADMISSION_REFUSED");details.put("admitted",false);
                details.put("coarsened",admission.coarsened());details.put("policy",ProbabilisticOptionalOverflowAdmission.POLICY_ID);
                details.put("nativeForwardBackwardBound",admission.nativeForwardBackwardBound());details.put("pairBound",admission.pairBound());
                details.put("completeScoreBound",admission.completeScoreBound());
                details.put("boundSemantics","NEGATIVE_IS_UNAVAILABLE; THESE_ARE_INVENTORY_BOUNDS_NOT_EXECUTED_SOLVER_WORK");
                details.put("inferenceCalls",0);details.put("adapterEvaluations",0);details.put("tableBuilds",0);
                details.put("builderReason",admission.refusalReason());
                var unvisited=new ArrayList<Row>();for(int eta:ETAS)unvisited.add(unavailable(eta,eta==0?"ADMISSION_REFUSED":"UNVISITED_AFTER_ADMISSION_REFUSAL"));
                publishAdmissionRefusal(new CaseResult(id,source,List.copyOf(unvisited),raw.size(),modes,censored,0,0,owner.peakBytes(),admission.coarsened(),false,
                        evidence.canonicalHash(),"unavailable","unavailable","unavailable"),details);
            }
            assertTrue(admission.admitted(),"explicit optional-overflow graph refused "+id);
            var profiles=new ArrayList<InferenceProfile>();var lattices=new ArrayList<ProbabilisticStateLattice>();
            long pairs=0;boolean fullDomain=true;
            for(int i=0;i<raw.size();i++) {
                var built=admission.buildProfile(i,owner,CancellationProbe.NONE);
                var lattice=built.lattice().orElseThrow();lattices.add(lattice);
                if(i>0)pairs+=((long)lattices.get(i-1).cells().size())*lattice.cells().size();
                if(i>0&&i+1<raw.size())fullDomain&=lattice.cells().stream().mapToDouble(LateralStateCell::offsetMeters).min().orElseThrow()==-25
                        &&lattice.cells().stream().mapToDouble(LateralStateCell::offsetMeters).max().orElseThrow()==25;
                profiles.add(BObservationAdapter.evaluate(facts.get(i),lattice,PARAMETERS,true,B0,CancellationProbe.NONE,owner));
            }
            ADMISSION_EVIDENCE.put(id,admissionReceipt(admission,raw.size()));
            var factsHash=BIdentificationReceipt.factsHash(facts,lattices,CancellationProbe.NONE);
            // Eta0 executes first, without constructing a source copy, field, prefix, table or views.
            rows.add(solve(id,0,source,profiles,evidence,null,owner,fullDomain,censored));
            if(stopsCase(rows.get(0))) {
                for(int eta:ETAS)if(eta!=0)rows.add(unavailable(eta,"UNVISITED_AFTER_BASELINE_FAILURE"));
                return new CaseResult(id,source,List.copyOf(rows),raw.size(),modes,censored,pairs,0,owner.peakBytes(),admission.coarsened(),fullDomain,
                        evidence.canonicalHash(),factsHash,"unavailable","unavailable");
            }
            var input=new BGapEvidence.Input(evidence,"scalar",facts,profiles,BGapEvidence.approvedContract());
            BGapField field=null;String cold="unavailable",hot="unavailable";long tableBytes=0;
            try {
                field=BGapField.build(input,CancellationProbe.NONE,owner);
                long cells=0,unavailable=0,nonzero=0;var statuses=new TreeMap<String,Long>();
                for(int y=0;y<field.grid.height-1;y++)for(int x=0;x<field.grid.width-1;x++) {
                    var status=field.status(x,y);cells++;if(status.ordinal()>=2)unavailable++;if(field.value(x,y)!=0)nonzero++;
                    statuses.merge(status.name(),1L,Long::sum);
                }
                var evidenceReceipt=new LinkedHashMap<String,Object>();evidenceReceipt.put("fieldCellStatuses",statuses);
                evidenceReceipt.put("fieldCellsInspected",cells);evidenceReceipt.put("nonzeroFieldCells",nonzero);
                evidenceReceipt.put("censoredModeCount",censored);CONTROL_EVIDENCE.put(id,evidenceReceipt);
                try(var table=BGapPairTable.build(field,profiles,CancellationProbe.NONE,owner)) {
                long censoredPairReads=0,nonzeroCensoredPairs=0;
                if(id.equals("censored-flank"))for(int stage=0;stage<profiles.size()-1;stage++)
                    for(int left=0;left<profiles.get(stage).cells().size();left++)for(int right=0;right<profiles.get(stage+1).cells().size();right++) {
                        censoredPairReads++;if(table.entry(stage,left,right).value()!=0)nonzeroCensoredPairs++;
                    }
                evidenceReceipt.put("censoredWitnessPairReads",censoredPairReads);evidenceReceipt.put("nonzeroCensoredWitnessPairs",nonzeroCensoredPairs);
                field.close();tableBytes=BGapPairTable.tableBytes(profiles);var before=table.counters();cold=before.toString();
                assertEquals(pairs,before.pairWrites());assertEquals(tableBytes,before.currentOwnedBytes());
                boolean originalFullDomain=fullDomain;
                appendEnabled(rows,eta->{
                    var view=(BGapPairTable.View)table.forEta(eta);view.requireProfiles(profiles);
                    return solve(id,eta,source,profiles,evidence,view,owner,originalFullDomain,censored);
                },table::close);
                var after=table.counters();hot=after.toString();
                assertEquals(before.sourceElements(),after.sourceElements());assertEquals(before.maskReads(),after.maskReads());
                assertEquals(before.cacheReads(),after.cacheReads());assertEquals(before.prefixOperations(),after.prefixOperations());
                if(rows.stream().skip(1).anyMatch(V022BGapMatchedControlsTest::completed))assertTrue(after.solverLookups()>0);
                assertTrue(owner.peakBytes()<=BGapEvidence.BYTES);
                if(id.equals("censored-flank")) {
                    var baseline=rows.get(0);boolean availability=censoredAvailable(censored,cells,unavailable,nonzero,statuses,pairs,censoredPairReads,nonzeroCensoredPairs);
                    evidenceReceipt.put("typedCensoredCapabilityAvailable",availability);
                    for(int i=0;i<rows.size();i++) {
                        var row=rows.get(i);boolean same=completed(row)&&row.points.equals(baseline.points)&&row.states.equals(baseline.states)
                                &&Double.doubleToRawLongBits(row.energy)==Double.doubleToRawLongBits(baseline.energy)&&row.work.equals(baseline.work);
                        rows.set(i,new Row(row.eta,row.actualSolve,row.status,row.points,row.states,row.energy,row.transitions,row.work,row.completion,
                                row.explanation,false,row.sourceObserved,availability&&same));
                    }
                }
                }
            } catch(RuntimeException failure) {
                if(field!=null){cold=field.counters().toString();hot=cold;}
                boolean first=true;
                while(rows.size()<ETAS.length) {int eta=ETAS[rows.size()];rows.add(unavailable(eta,first?"GAP_CONSTRUCTION_FAILURE_"+safeFailure(failure):"UNVISITED_AFTER_WHOLE_CASE_FAILURE"));first=false;}
            } finally {if(field!=null)field.close();}
            return new CaseResult(id,source,List.copyOf(rows),raw.size(),modes,censored,pairs,tableBytes,owner.peakBytes(),admission.coarsened(),fullDomain,
                    evidence.canonicalHash(),factsHash,cold,hot);
            } finally {updateAdmissionWork(ADMISSION_EVIDENCE.get(id),admissionWork);}
            }
        } finally {assertEquals(0,ledger.currentBytes());}
    }

    private static Map<String,Object> admissionReceipt(ProbabilisticOptionalOverflowAdmission.Selection selection,int rows) {
        var output=new LinkedHashMap<String,Object>();output.put("policy",ProbabilisticOptionalOverflowAdmission.POLICY_ID);
        output.put("strictBaselineIdentity","UNCHANGED_STRICT_API; FORMER_REFUSALS_HAVE_NO_OLD_SUCCESSFUL_OUTPUT");
        output.put("admitted",selection.admitted());output.put("coarsened",selection.coarsened());output.put("refusalReason",selection.refusalReason());
        output.put("declinedOptionalCount",selection.declinedCount());
        var decisions=new ArrayList<Map<String,Object>>();
        for(int i=0;i<rows;i++)decisions.add(Map.of("row",i,"declinedOptional",selection.declined(i),
                "decision",selection.declined(i)?"DECLINED_NATIVE_FULL":"UNCHANGED_STRICT_PROPOSAL",
                "originalProposalRawBits",Long.toHexString(Double.doubleToRawLongBits(selection.originalProposal(i))),
                "effectiveProposalRawBits",Long.toHexString(Double.doubleToRawLongBits(selection.effectiveProposal(i)))));
        output.put("rows",decisions);output.put("retainedCapabilityBytes",ProbabilisticOptionalOverflowAdmission.retainedBytes(rows));
        output.put("nativeForwardBackwardBound",selection.nativeForwardBackwardBound());output.put("selectedForwardBackwardUpperBeforePossibleStrictCoarsening",selection.selectedForwardBackwardBound());
        output.put("pairBound",selection.pairBound());output.put("completeScoreBound",selection.completeScoreBound());
        output.put("boundSemantics","INVENTORY_UPPER_BOUNDS_NOT_EXECUTED_SOLVER_WORK");
        updateAdmissionWork(output,selection.work());return output;
    }
    private static void updateAdmissionWork(Map<String,Object> output,ProbabilisticOptionalOverflowAdmission.Work work) {
        output.put("preflightAndMaterializationUnits",work.units);output.put("privateWorkUpper",work.upper);
        output.put("nativeBuilderInvocations",work.nativeBuilds);output.put("cellComparisons",work.cellComparisons);
        output.put("checkpoints",work.checkpoints);output.put("strictSelectionInvocations",work.strictSelections);
        output.put("materializationBuilderInvocations",work.materializations);output.put("copiedElements",work.copiedElements);
        output.put("workSemantics","DEFINED_COPY_ROW_CELL_AND_CHECKPOINT_UNITS; BUILDER_AND_STRICT_INTERNAL_WORK_NOT_MEASURED_HERE");
    }
    private static Row solve(String id,int eta,List<MetricPoint> source,List<InferenceProfile> profiles,EvidenceSnapshot evidence,
            AdjacentStatePotential potential,AttemptMemoryLedger.Owner owner,boolean fullDomain,int censored) {
        try(var resultOwner=owner.child("matched-solver-"+eta)) {
            var solver=new ProbabilisticInference();
            ProbabilisticInferenceResult result;
            try {result=potential==null?solver.solve(profiles,PARAMETERS,SOLVE_BUDGETS,evidence.decisionRegion(),CancellationProbe.NONE,resultOwner,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2):
                    solver.solve(profiles,PARAMETERS,SOLVE_BUDGETS,evidence.decisionRegion(),CancellationProbe.NONE,resultOwner,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2,potential);
            } catch(RuntimeException failure) {
                return new Row(eta,true,ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,List.of(),List.of(),Double.NaN,0,
                        "unavailable","Optional.empty","THROWN_SOLVER_FAILURE_"+safeFailure(failure),false,false,false);
            }
            var path=result.mapPath();var points=path.map(ProbabilisticPath::points).orElse(List.of());
            var states=new ArrayList<Integer>();if(path.isPresent())for(int s:path.orElseThrow().stateIndices())states.add(s);
            boolean anchors=!points.isEmpty()&&points.get(0).equals(source.get(0))&&points.get(points.size()-1).equals(source.get(1));
            boolean geometry=anchors&&!id.equals("censored-flank")&&inTruth(id,points),observed=anchors&&fullyObserved(evidence,points);
            boolean passed=geometry&&observed;
            if(id.contains("twenty-five-representation"))passed=anchors&&fullDomain;
            // Censored position is unavailable. Only the actual zero/unavailable
            // field + complete zero table + unchanged baseline comparison below
            // can attest its separate no-new-barrier capability predicate.
            if(id.equals("censored-flank"))passed=false;
            if(id.equals("invalid-unknown-gap")||id.equals("unknown-detour"))passed=anchors&&!geometry||anchors&&!observed;
            return new Row(eta,true,result.status(),List.copyOf(points),List.copyOf(states),path.map(ProbabilisticPath::energy).orElse(Double.NaN),
                    result.evaluatedTransitions(),result.workAccounting().toString(),result.completion().toString(),result.explanation(),geometry,observed,passed);
        }
    }

    /** Exact whole-segment oracle for these declared piecewise-linear vertical tubes. */
    private static boolean inTruth(String id,List<MetricPoint> points) {
        if(points.size()<2)return false;var polygons=new ArrayList<List<MetricPoint>>();
        double width=id.equals("connected-sharp-apex")||id.equals("faint-six-metre-bend")?6:12;
        double height=id.equals("connected-short-bend")?2:3;
        boolean bend=id.contains("bend")||id.equals("connected-sharp-apex");
        double[] xs=bend?new double[]{8,20-width/2,20,20+width/2,32}:new double[]{8,32};
        for(int i=1;i<xs.length;i++) {
            double a=xs[i-1],b=xs[i],ya=bend?center(a,width,height):37,yb=bend?center(b,width,height):37;
            polygons.add(List.of(p(a,ya-1),p(b,yb-1),p(b,yb+1),p(a,ya+1)));
        }
        if(id.equals("genuine-junction"))polygons.add(List.of(p(19,12),p(21,12),p(21,62),p(19,62)));
        var truth=new MetricRegion(polygons);
        for(int i=1;i<points.size();i++)if(!truth.containsSegment(points.get(i-1),points.get(i)))return false;
        return true;
    }
    /** Independent actual-raster support traversal; no truth enters the scorer. */
    private static boolean fullyObserved(EvidenceSnapshot evidence,List<MetricPoint> points) {
        if(points.size()<2)return false;
        var field=evidence.fields().get("scalar");
        for(int i=1;i<points.size();i++) {
            var a=evidence.transform().metricToPixelCenter(points.get(i-1));var b=evidence.transform().metricToPixelCenter(points.get(i));
            double dx=b.x()-a.x(),dy=b.y()-a.y();var cuts=new TreeSet<Double>();cuts.add(0.0);cuts.add(1.0);
            if(dx!=0)for(int x=0;x<field.width();x++){double t=(x-a.x())/dx;if(t>0&&t<1)cuts.add(t);}
            if(dy!=0)for(int y=0;y<field.height();y++){double t=(y-a.y())/dy;if(t>0&&t<1)cuts.add(t);}
            double previous=0;
            for(double t:cuts) {
                if(t==0)continue;double middle=(previous+t)/2;
                int x=(int)Math.floor(a.x()+dx*middle),y=(int)Math.floor(a.y()+dy*middle);
                if(!field.supportsInterpolationCell(x,y))return false;
                previous=t;
            }
        }
        return true;
    }
    private static EvidenceSnapshot capture(String id,List<MetricPoint> source) {
        int w=81,h=81;double[] scalar=new double[w*h];boolean[] valid=new boolean[w*h];Arrays.fill(valid,true);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double worldX=x-20;scalar[y*w+x]=Math.max(0,Math.min(1,intensity(id,worldX,y)));
            if((id.equals("invalid-unknown-gap")||id.equals("unknown-detour"))&&worldX>=18&&worldX<=22&&y>=34&&y<=40)valid[y*w+x]=false;
        }
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var inverse=SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(20,0),1,0,0,1);
        boolean fine=id.equals("half-pitch-alias")||id.equals("quarter-phase-alias");double pitch=fine?.5:1,phase=id.equals("quarter-phase-alias")?.25:0;
        var grid=new MetricRasterGrid(frame,p(-20+phase,phase),1,0,0,1,pitch,fine?161:81,fine?161:81);
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                "matched-declared-scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        return new RasterEvidenceCapture().captureWithMetricScalarSource("matched-"+id,new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB),valid,scalar,
                source.stream().map(frame::toGeographic).toList(),source,inverse,grid,EvidenceResolution.nativeSource(1,1),25,"matched-public-declared-"+id,
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",lineage,CancellationProbe.NONE);
    }
    private static double intensity(String id,double x,double y) {
        if(id.equals("uniform-null"))return .25;
        if(id.equals("alternating-null"))return ((int)y&1)==0?.02:.03;
        if(id.equals("strong-parallel-gap"))return .02+(x<20?.8:.55)*gauss(y-37,1)+(x<20?.55:.8)*gauss(y-43,1);
        if(id.equals("strong-modulated-versus-quiet-faint"))return .02+(.7+.25*StrictMath.sin(StrictMath.PI*(x-8)/6))*gauss(y-37,1)+.15*gauss(y-43,1);
        if(id.equals("genuine-junction"))return .02+.8*Math.max(gauss(y-37,1),gauss(x-20,1));
        if(id.equals("valid-sparse-gap"))return x>=18&&x<=22?.02:.02+.8*gauss(y-37,1);
        if(id.equals("flecks-versus-faint-coherent")) {
            double value=.02+.08*gauss(y-37,1);
            for(double at:new double[]{11,17,23,29})value+=.6*gauss(x-at,.45)*gauss(y-43,.45);
            return value;
        }
        if(id.equals("unknown-detour"))return .02+.8*gauss(y-37,1)+.15*gauss(y-43,1);
        double center=37,amplitude=.8;
        if(id.equals("positive-twenty-five-representation"))center=61;
        if(id.equals("negative-twenty-five-representation"))center=13;
        if(id.equals("censored-flank"))center=61.75;
        if(id.equals("connected-short-bend"))center=center(x,12,2);
        if(id.equals("connected-sharp-apex"))center=center(x,6,3);
        if(id.equals("faint-six-metre-bend")){center=center(x,6,3);amplitude=.08;}
        if(id.equals("faint-twelve-metre-bend")){center=center(x,12,3);amplitude=.08;}
        return .02+amplitude*gauss(y-center,1);
    }
    private static List<MetricPoint> source(String id){return id.equals("reverse-original-mesh")?List.of(SOURCE.get(1),SOURCE.get(0)):SOURCE;}
    private static double center(double x,double width,double height){return 37+height*Math.max(0,1-Math.abs(x-20)/(width/2));}
    private static double gauss(double distance,double sigma){return StrictMath.exp(-.5*(distance/sigma)*(distance/sigma));}
    private static MetricPoint p(double x,double y){return new MetricPoint(x,y);}
    private static void write(String name,Object value) throws Exception {
        if(!name.matches("[a-z0-9-]+\\.json"))throw new IllegalArgumentException("Fixed synthetic output name required");
        byte[] bytes=BIdentificationReceipt.canonical(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(bytes.length<=65536);Files.createDirectories(OUTPUT);Files.write(OUTPUT.resolve(name),bytes);
    }
    private static boolean matrixComplete(Map<String,CaseResult> results,int requiredCases) {
        return results.size()==requiredCases&&results.values().stream().allMatch(r->r.rows.size()==8&&r.rows.stream().noneMatch(V022BGapMatchedControlsTest::stopsCase));
    }
    @AfterAll static void report() throws Exception {
        var summary=new LinkedHashMap<String,Object>();summary.put("status","SOURCE_CONTROL_MATRIX_NOT_MODEL_QUALIFICATION");
        summary.put("expectedCases",CASES);summary.put("completedCases",new ArrayList<>(RESULTS.keySet()));
        summary.put("complete",matrixComplete(RESULTS,CASES.size()));
        var settings=new ArrayList<Map<String,Object>>();
        for(int eta:ETAS) {
            var failed=new ArrayList<String>();for(var id:CASES) {
                var result=RESULTS.get(id);
                if(result==null||result.rows.stream().noneMatch(r->r.eta==eta&&r.controlPassed))failed.add(id);
            }
            settings.add(Map.of("eta",eta,"failedOrUnavailableRequiredControls",failed,"allControlsPassed",failed.isEmpty()));
        }
        summary.put("settings",settings);summary.put("progressionAuthority","HOLD_CONTROLLER_GATE_UNRESOLVED_NO_SELECTION");write("summary.json",summary);
    }
}
