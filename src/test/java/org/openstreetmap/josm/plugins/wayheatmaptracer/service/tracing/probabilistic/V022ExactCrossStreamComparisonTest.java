package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.nio.file.*;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CrossStreamWorkAccounting;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Only tiny/controlled receipt tests run by default; fixed-source execution is separately opted in. */
class V022ExactCrossStreamComparisonTest {
    @TempDir Path temporary;

    @Test
    void initialPhasePreservesUnknownEntryAndReturnAsJsonNullWithoutAcquisition() throws Exception {
        var type=Class.forName(BExactCrossStreamComparison.class.getName()+"$Run");
        var constructor=type.getDeclaredConstructor(String.class,BExactCrossStreamComparison.Controls.class);constructor.setAccessible(true);
        var run=constructor.newInstance(BExactCrossStreamComparison.IDS.get(0),controls(true));
        var phase=type.getDeclaredMethod("phase");phase.setAccessible(true);
        @SuppressWarnings("unchecked") var value=(Map<String,Object>)assertDoesNotThrow(()->phase.invoke(run));
        var parsed=BIdentificationReceipt.parse(BIdentificationReceipt.canonical(value).getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.containsKey("actualSolverEntries"));assertNull(parsed.get("actualSolverEntries"));
        assertTrue(parsed.containsKey("normalReturns"));assertNull(parsed.get("normalReturns"));
        assertEquals(false,parsed.get("terminal"));assertEquals(false,parsed.get("invocationAttempted"));
        var terminal=type.getDeclaredField("terminal");terminal.setAccessible(true);terminal.setBoolean(run,true);
        var terminalPhase=(Map<?,?>)phase.invoke(run);
        assertEquals(0,terminalPhase.get("actualSolverEntries"));assertEquals(0,terminalPhase.get("normalReturns"));
    }

    @Test
    void initialPhaseSerializationFailureCannotClaimAnEmptyFinalFile() throws Exception {
        var phase=temporary.resolve("phase.json");
        assertThrows(BExactCrossStreamComparison.ExportOverflow.class,()->BExactCrossStreamComparison.writeInitialPhase(phase,
                Map.of("oversizedEscapedText","\n".repeat(12_000))));
        assertFalse(Files.exists(phase));assertFalse(Files.exists(temporary.resolve("phase.json.partial")));
        var value=new LinkedHashMap<String,Object>();value.put("actualSolverEntries",null);value.put("normalReturns",null);
        BExactCrossStreamComparison.writeInitialPhase(phase,value);
        assertTrue(Files.size(phase)>0);assertNull(BIdentificationReceipt.parse(Files.readAllBytes(phase)).get("actualSolverEntries"));
        byte[] original=Files.readAllBytes(phase);
        assertThrows(FileAlreadyExistsException.class,()->BExactCrossStreamComparison.writeInitialPhase(phase,Map.of("changed",true)));
        assertArrayEquals(original,Files.readAllBytes(phase));
    }

    @Test
    void firstCheckpointCancellationPublishesOnlyFailureBeforeAnySourceAcquisition() throws Exception {
        var harness=new BExactCrossStreamComparison(controls(true));var output=temporary.resolve("cancelled-before-source");
        var receipt=assertDoesNotThrow(()->harness.execute(BExactCrossStreamComparison.IDS.get(0),output,()->0,()->true));
        assertEquals("CANCELLED_OR_DEADLINE",receipt.get("safeCause"));assertEquals(false,receipt.get("pipelineComplete"));
        assertEquals(0,receipt.get("actualSolverEntries"));assertEquals(0,receipt.get("normalReturns"));
        assertFalse(receipt.containsKey("capture"));assertFalse(receipt.containsKey("profiles"));assertFalse(receipt.containsKey("solver"));
        assertEquals(0,harness.lastClosedBytes());
        assertTrue(Files.size(output.resolve("phase.json"))>0);assertTrue(Files.size(output.resolve("phase.json"))<=65_536);
        assertEquals(true,BIdentificationReceipt.parse(Files.readAllBytes(output.resolve("phase.json"))).get("terminal"));
    }

    @Test
    void allAlternativesAndRawBitsAreRetained() {
        var first = new ProbabilisticPath(new int[] {1, 0},
                List.of(new MetricPoint(-0.0, 0), new MetricPoint(2, 1)), "first", 3, -0.0, 0.25);
        var second = new ProbabilisticPath(new int[] {0, 1},
                List.of(new MetricPoint(0.0, 0), new MetricPoint(2, 2)), "second", 4, 1, 0.75);
        var actual = BExactCrossStreamComparison.routes(List.of(first, second));
        assertEquals(2, actual.size(), "all returned alternatives must be retained in native order");
        assertEquals(List.of(1, 0), actual.get(0).get("states"));
        assertEquals(List.of(0, 1), actual.get(1).get("states"));
        assertEquals("8000000000000000", ((List<?>) ((List<?>) actual.get(0).get("pointBits")).get(0)).get(0));
        assertEquals("8000000000000000", actual.get(0).get("logBaseMeasureBits"));
        assertEquals("0", ((List<?>) ((List<?>) actual.get(1).get("pointBits")).get(0)).get(0));
        assertEquals("first", actual.get(0).get("branchSignature"));
        assertEquals("second", actual.get(1).get("branchSignature"));
    }

    @Test
    void fixedInventoryAndPolicyCannotDrift() {
        assertEquals(List.of("switchback-thin-strong", "switchback-thin-faint", "same-trail-three-strong",
                "same-trail-five-strong", "same-trail-three-separated-strong"), BExactCrossStreamComparison.IDS);
        var b = BExactCrossStreamComparison.BUDGETS;
        assertEquals(List.of(96L, 8_000_000L, 128_000_000L, 1L, 1L), List.of((long)b.maximumStatesPerProfile(), b.maximumPairVisits(),
                b.maximumTransitions(), (long)b.maximumRawAlternatives(), (long)b.maximumDistinctAlternatives()));
        assertEquals(64L*1024*1024, BExactCrossStreamComparison.MEMORY);
        assertEquals(33L*1024*1024, BExactCrossStreamComparison.CAPTURE_BYTES + BExactCrossStreamComparison.METADATA_BYTES);
        assertEquals(EvidenceModelParameters.defaults(), BExactCrossStreamComparison.PARAMETERS);
        assertEquals(new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(1,1,0)).identityHash(), BExactCrossStreamComparison.B0.identityHash());
    }

    @Test
    void pendingWrongIdentityAndDuplicateClaimRefuseBeforeAcquisition() throws Exception {
        var pending = new BExactCrossStreamComparison(controls(false));
        assertThrows(SecurityException.class, () -> pending.execute(BExactCrossStreamComparison.IDS.get(0), temporary.resolve("pending"),
                () -> { throw new AssertionError("No pending source clock/acquisition"); }, CancellationProbe.NONE));
        assertFalse(Files.exists(temporary.resolve("pending")));
        assertThrows(SecurityException.class, () -> pending.execute("unknown-case", temporary, () -> 0, CancellationProbe.NONE));
        Files.writeString(temporary.resolve("phase.json"), "preserved-existing-attempt");
        var admitted = new BExactCrossStreamComparison(controls(true));
        assertThrows(FileAlreadyExistsException.class, () -> admitted.execute(BExactCrossStreamComparison.IDS.get(0), temporary,
                () -> { throw new AssertionError("Duplicate attempt reached acquisition"); }, CancellationProbe.NONE));
        assertEquals("preserved-existing-attempt", Files.readString(temporary.resolve("phase.json")));
        assertEquals(-1, admitted.lastClosedBytes(), "no ledger/attempt is claimed for an existing namespace");
    }

    @Test
    void sourceBoundaryCannotReceiveTruthAndFiveOraclesKeepOriginalReference() throws Exception {
        assertEquals(BExactCrossStreamComparison.Controls.class, BExactCrossStreamComparison.class.getDeclaredConstructors()[0].getParameterTypes()[0]);
        assertArrayEquals(new Class<?>[] {String.class, Path.class, java.util.function.LongSupplier.class, CancellationProbe.class},
                BExactCrossStreamComparison.class.getDeclaredMethod("execute", String.class, Path.class, java.util.function.LongSupplier.class,
                        CancellationProbe.class).getParameterTypes());
        assertArrayEquals(new Class<?>[] {String.class, Map.class, CancellationProbe.class},
                V022BGapMatchedControlsTest.class.getDeclaredMethod("captureNativeComparisonSource", String.class, Map.class,
                        CancellationProbe.class).getParameterTypes());
        var source = BThinSwitchbackFixtures.source();
        var passed = controlled(ProbabilisticInferenceResult.Status.COMPLETE, source, true);
        var escaped = new ArrayList<>(source); escaped.set(5, new MetricPoint(source.get(5).xMeters()+4,source.get(5).yMeters()+4));
        var failed = controlled(ProbabilisticInferenceResult.Status.COMPLETE, escaped, true);
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            for (String id : BExactCrossStreamComparison.IDS) {
                var reference = V022BGapMatchedControlsTest.nativeComparisonSource(id);
                assertEquals(source, reference);
                var accepted = BExactCrossStreamComparison.truth(id, reference, passed, 0, CancellationProbe.NONE, owner);
                assertEquals(BThinSwitchbackAcceptance.ORACLE_ID, accepted.get("oracle")); assertEquals(true, accepted.get("passed"));
                assertEquals("ORIGINAL_FROZEN_METRIC_SOURCE_PRESERVATION_REFERENCE", accepted.get("referenceIdentity"));
                assertEquals(false, BExactCrossStreamComparison.truth(id, reference, failed, 0, CancellationProbe.NONE, owner).get("passed"));
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void missingRefusedPartialAndCutoffNeverAcquireTruthOrBecomeAvailable() {
        assertFalse(BExactCrossStreamComparison.usable(null));
        for (var status : List.of(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,
                ProbabilisticInferenceResult.Status.NO_ROUTE)) {
            var partial = controlled(status, List.of(new MetricPoint(0,0),new MetricPoint(1,0)), true);
            assertFalse(BExactCrossStreamComparison.usable(partial));
            var ledger = new AttemptMemoryLedger(1_000_000);
            try (var owner = ledger.rootOwner()) {
                assertEquals(false, BExactCrossStreamComparison.truth(BExactCrossStreamComparison.IDS.get(0), List.of(), partial, 0,
                        () -> { throw new AssertionError("Unavailable data queried truth"); }, owner).get("available"));
            }
            assertEquals(0, ledger.currentBytes());
        }
        assertFalse(BExactCrossStreamComparison.usable(controlled(ProbabilisticInferenceResult.Status.COMPLETE,
                List.of(new MetricPoint(0,0),new MetricPoint(1,0)), false)), "missing completion cannot imply usable returned geometry");
    }

    @Test
    void boundedBinaryGraphHasIndependentCompleteFieldDecoderAndStableSequence() throws Exception {
        var graph = tinyProfiles();
        var manifest = BExactCrossStreamComparison.graph(BExactCrossStreamComparison.IDS.get(0), graph, temporary, CancellationProbe.NONE);
        assertEquals(8, manifest.get("cells")); assertEquals(BExactCrossStreamComparison.GRAPH_FORMAT, manifest.get("format"));
        var chunks = (List<?>) manifest.get("chunks"); assertEquals(1, chunks.size());
        var descriptor = (Map<?,?>) chunks.get(0); byte[] bytes = Files.readAllBytes(temporary.resolve((String) descriptor.get("file")));
        assertEquals(bytes.length, descriptor.get("bytes")); assertEquals(BIdentificationReceipt.hash(bytes), descriptor.get("sha256"));
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals(0x43534731, input.readInt()); assertEquals(8, input.readInt());
            for (int row = 0; row < graph.size(); row++) for (int state = 0; state < 2; state++) {
                int size = input.readInt(); byte[] raw = input.readNBytes(size); assertEquals(size, raw.length);
                try (var cell = new DataInputStream(new ByteArrayInputStream(raw))) {
                    var p = graph.get(row); var c = p.cells().get(state); var point = p.point(state);
                    assertEquals(row, cell.readInt()); assertEquals(state, cell.readInt());
                    for (double expected : new double[] {p.chainageMeters(), p.anchor().xMeters(), p.anchor().yMeters(), p.normalUnit().xMeters(),
                            p.normalUnit().yMeters(), c.offsetMeters(), c.quadratureWidthMeters(), point.xMeters(), point.yMeters(), p.unaryCost(state),
                            p.structuralGuideCost(state), p.orientationReliability(state)}) assertEquals(Double.doubleToRawLongBits(expected), cell.readLong());
                    assertEquals((c.exactAnchor()?1:0)|(c.mandatory()?2:0), cell.readUnsignedByte()); assertEquals(p.ownership().name(), cell.readUTF());
                    assertEquals(p.entirelyMissing(), cell.readBoolean()); assertEquals(c.branchLabel(), cell.readUTF());
                    var orientation = p.orientationSupport(state); assertEquals(orientation.status().name(), cell.readUTF());
                    assertEquals(Double.doubleToRawLongBits(orientation.certainty()), cell.readLong()); assertEquals(orientation.modes().size(), cell.readInt());
                    for (var mode : orientation.modes()) for (double expected : new double[] {mode.startRadians(),mode.endRadians(),mode.peakBearingRadians(),mode.response()})
                        assertEquals(Double.doubleToRawLongBits(expected), cell.readLong());
                    assertEquals(2, cell.readInt()); assertEquals(Double.doubleToRawLongBits(.25), cell.readLong());
                    assertEquals(Double.doubleToRawLongBits(.75), cell.readLong()); assertEquals(-1, cell.read());
                }
            }
            assertEquals(-1, input.read());
        }
    }

    @Test
    void exportOverflowAndLateCancellationLeaveNoPartialSuccessPrefix() throws Exception {
        var output = temporary.resolve("overflow");
        var batch = new LinkedHashMap<String,Object>(); batch.put("route-one.json", Map.of("UNIT_ONLY", true));
        batch.put("route-two.json", Map.of("oversized", "x".repeat(65_537)));
        assertThrows(IllegalArgumentException.class, () -> BExactCrossStreamComparison.publish(output, batch, CancellationProbe.NONE));
        try (var files = Files.list(output)) { assertEquals(0, files.count()); }
        var complete = Map.<String,Object>of("route-one.json", Map.of("UNIT_ONLY", true), "result.json", Map.of("UNIT_ONLY", true));
        boolean[] allStaged = {false};
        assertThrows(CancellationException.class, () -> BExactCrossStreamComparison.publish(output, complete, () -> {
            try (var children = Files.list(output)) {
                allStaged[0] = children.anyMatch(stage -> Files.isDirectory(stage) && Files.exists(stage.resolve("route-one.json")) && Files.exists(stage.resolve("result.json")));
                return allStaged[0];
            } catch (Exception failure) { throw new AssertionError(failure); }
        }));
        assertTrue(allStaged[0]); try (var files = Files.list(output)) { assertEquals(0, files.count()); }
        Files.writeString(output.resolve("result.json"), "sentinel");
        assertThrows(FileAlreadyExistsException.class, () -> BExactCrossStreamComparison.publish(output, complete, CancellationProbe.NONE));
        assertEquals("sentinel", Files.readString(output.resolve("result.json")));
        try (var files = Files.list(output)) { assertEquals(1, files.count()); }
    }

    @Test
    void preexistingSymlinkNamespacesAndEscapedOversizePayloadsArePreserved() throws Exception {
        var target=temporary.resolve("existing-target");Files.createDirectory(target);Files.writeString(target.resolve("sentinel"),"preserve");
        var link=temporary.resolve("unowned-link");Files.createSymbolicLink(link,target);
        var harness=new BExactCrossStreamComparison(controls(true));
        assertThrows(SecurityException.class,()->harness.execute(BExactCrossStreamComparison.IDS.get(0),link,
                ()->{throw new AssertionError("Unowned namespace acquired input");},CancellationProbe.NONE));
        assertThrows(SecurityException.class,()->BExactCrossStreamComparison.publish(link,Map.of("result.json",Map.of("unit",true)),CancellationProbe.NONE));
        assertEquals("preserve",Files.readString(target.resolve("sentinel")));try(var files=Files.list(target)){assertEquals(1,files.count());}
        var output=temporary.resolve("escaped");
        assertThrows(BExactCrossStreamComparison.ExportOverflow.class,()->BExactCrossStreamComparison.publish(output,
                Map.of("result.json",Map.of("escaped","\u0001".repeat(12_000))),CancellationProbe.NONE));
        try(var files=Files.list(output)){assertEquals(0,files.count());}
    }

    @Test
    void tinyActualFundedCallBindsTruthfulWorkAndLongMetadata() throws Exception {
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            var actual = new ProbabilisticInference().solveCrossStreamRaw1(tinyProfiles(), EvidenceModelParameters.defaults(),
                    BExactCrossStreamComparison.BUDGETS, null, CancellationProbe.NONE, owner, null);
            var receipt = BExactCrossStreamComparison.resultReceipt(actual);
            assertEquals(1, receipt.get("rawCount"));
            assertEquals(ProbabilisticExecutionPolicy.CROSS_STREAM_VERSION, ((Map<?,?>)receipt.get("work")).get("executionPolicy"));
            assertEquals(20, ((Map<?,?>)receipt.get("boundWork")).size());
            var parsed = BIdentificationReceipt.parse(BIdentificationReceipt.canonical(receipt).getBytes(StandardCharsets.UTF_8));
            assertEquals("RETAINED_SEPARATE_TYPED_BINARY_LEAVES; NOT_OMITTED_OR_HASH_ONLY", parsed.get("posteriorEvidence"));
            var leaves = BExactCrossStreamComparison.posteriorLeaves(BExactCrossStreamComparison.IDS.get(0),actual.result(),CancellationProbe.NONE);
            assertEquals(4,leaves.size());
            for (var entry:leaves.entrySet()) try (var input = new DataInputStream(new ByteArrayInputStream(entry.getValue()))) {
                assertTrue(entry.getValue().length<=65_536); assertEquals(0x43535031,input.readInt());
                String kind=input.readUTF();var matrix=switch(kind){case "position"->actual.result().positionMarginals();case "component"->actual.result().componentMarginals();
                    case "forward"->actual.result().forwardPositionMarginals();case "backward"->actual.result().backwardPositionMarginals();default->throw new AssertionError("Unknown matrix");};
                assertEquals(matrix.size(),input.readInt());
                for(var row:matrix){assertEquals(row.length,input.readInt());for(double value:row)assertEquals(Double.doubleToRawLongBits(value),input.readLong());}
                assertEquals(-1,input.read());
            }
            long big = 8 + 64L*50_000_000;
            var synthetic = new CrossStreamWorkAccounting(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,big);
            var longReceipt = BExactCrossStreamComparison.resultReceipt(new CrossStreamInferenceResult(actual.result(),synthetic));
            var decoded = BIdentificationReceipt.parse(BIdentificationReceipt.canonical(longReceipt).getBytes(StandardCharsets.UTF_8));
            assertEquals(big, ((Map<?,?>)decoded.get("boundWork")).get("boundUnits"));
            assertThrows(ArithmeticException.class, () -> new CrossStreamWorkAccounting(0,0,Long.MAX_VALUE,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,8));
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void actualProtocolBytesRequireApprovedFixedLimitsAndKeepDualSourceIdentity() throws Exception {
        var protocol=unitProtocol();Path path=temporary.resolve("unit-only-protocol.json");
        byte[] bytes=BIdentificationReceipt.canonical(protocol).getBytes(StandardCharsets.UTF_8);Files.write(path,bytes);
        var loaded=BExactCrossStreamComparison.loadProtocol(path,BIdentificationReceipt.hash(bytes),"5".repeat(40));
        assertTrue(loaded.approved());assertEquals("4".repeat(40),loaded.reviewedCodeCommit());assertEquals("5".repeat(40),loaded.actualCheckoutCommit());
        assertNotEquals(loaded.actualCheckoutCommit(),loaded.reviewedCodeCommit(),"later metadata publication is not falsely the reviewed source commit");
        assertEquals(57,loaded.baseline().get(BExactCrossStreamComparison.IDS.get(0)).profiles());
        assertThrows(SecurityException.class,()->BExactCrossStreamComparison.loadProtocol(path,"f".repeat(64),"5".repeat(40)));
        for(String field:List.of("approval","engineEligible")){
            protocol.put(field,false);bytes=BIdentificationReceipt.canonical(protocol).getBytes(StandardCharsets.UTF_8);Files.write(path,bytes);
            String sha=BIdentificationReceipt.hash(bytes);assertThrows(SecurityException.class,()->BExactCrossStreamComparison.loadProtocol(path,sha,"5".repeat(40)));protocol.put(field,true);
        }
        var limits=(Map<String,Object>)protocol.get("limits");limits.put("raw",true);
        bytes=BIdentificationReceipt.canonical(protocol).getBytes(StandardCharsets.UTF_8);Files.write(path,bytes);String sha=BIdentificationReceipt.hash(bytes);
        assertThrows(SecurityException.class,()->BExactCrossStreamComparison.loadProtocol(path,sha,"5".repeat(40)),"Boolean cannot masquerade as integer count");
    }

    @Test
    void variableCellPayloadFlushesSmallerCompleteChunksAndRejectsWholeOverflow() throws Exception {
        String large="branch-"+"x".repeat(32_600);var cells=new ArrayList<LateralStateCell>();
        for(int i=0;i<3;i++)cells.add(new LateralStateCell(i*.1,1,false,true,large));
        var p=new InferenceProfile(0,new MetricPoint(0,0),new MetricPoint(0,1),cells,new double[3],List.of(0.0),1,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership.DIRECT_TWO_SIDED,false);
        var dir=temporary.resolve("adaptive");var manifest=BExactCrossStreamComparison.graph(BExactCrossStreamComparison.IDS.get(0),List.of(p),dir,CancellationProbe.NONE);
        var chunks=(List<?>)manifest.get("chunks");assertTrue(chunks.size()>1);int total=0;
        for(Object item:chunks){var entry=(Map<?,?>)item;byte[] bytes=Files.readAllBytes(dir.resolve((String)entry.get("file")));assertTrue(bytes.length<=65_536);
            try(var input=new DataInputStream(new ByteArrayInputStream(bytes))){assertEquals(0x43534731,input.readInt());int n=input.readInt();total+=n;for(int i=0;i<n;i++){int size=input.readInt();assertEquals(size,input.readNBytes(size).length);}assertEquals(-1,input.read());}}
        assertEquals(3,total);assertEquals(3,manifest.get("cells"));
        var bad=new InferenceProfile(0,new MetricPoint(0,0),new MetricPoint(0,1),List.of(new LateralStateCell(0,1,false,true,"z".repeat(65_537))),
                new double[]{0},List.of(),0,org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership.DIRECT_TWO_SIDED,false);
        var refused=temporary.resolve("too-large");assertThrows(BExactCrossStreamComparison.ExportOverflow.class,()->BExactCrossStreamComparison.graph(
                BExactCrossStreamComparison.IDS.get(0),List.of(bad),refused,CancellationProbe.NONE));
        try(var files=Files.list(refused)){assertEquals(0,files.count(),"no incomplete graph prefix retained");}
    }

    private static LinkedHashMap<String,Object> unitProtocol(){
        var p=new LinkedHashMap<String,Object>();p.put("approval",true);p.put("engineEligible",true);p.put("cases",BExactCrossStreamComparison.IDS);
        p.put("comparisonIdentity",BExactCrossStreamComparison.IDENTITY);p.put("executionPolicy",ProbabilisticExecutionPolicy.CROSS_STREAM_VERSION);
        p.put("reviewedCodeCommit","4".repeat(40));p.put("sourceClosureSha256","6".repeat(64));p.put("nullPotential",true);p.put("eta",0);
        p.put("limits",new LinkedHashMap<>(Map.of("states",96,"pairs",8_000_000,"transitions",128_000_000,"raw",1,"distinct",1,"bytes",64L*1024*1024,
                "cooperativeSeconds",300,"externalSeconds",360)));
        var rows=new ArrayList<Map<String,Object>>();for(var id:BExactCrossStreamComparison.IDS){var row=new LinkedHashMap<String,Object>();
            row.put("case",id);row.put("snapshotHash","0".repeat(64));row.put("factsAndLatticeHash","1".repeat(64));row.put("profiles",57);row.put("modes",1);
            row.put("originalPairInventory",10);row.put("observedForwardBackwardScores",20);row.put("archiveSha256","2".repeat(64));row.put("memberSha256","3".repeat(64));
            row.put("selectedAdmissionCoarsened",false);row.put("fullTwentyFiveMetreDomain",true);rows.add(row);}p.put("baselineInputs",rows);return p;
    }

    @Test
    @Tag("dedicated-experiment")
    void executesOneFrozenCase() throws Exception {
        assertEquals("true", System.getenv("V022_RUN_EXACT_CROSS_STREAM"), "Explicit fixed-source acquisition opt-in required");
        String id = System.getenv("V022_EXACT_CROSS_STREAM_CASE"); BExactCrossStreamComparison.requireCase(id);
        var controls = BExactCrossStreamComparison.loadProtocol(Path.of("src/test/resources/b-exact-cross-stream/protocol.json"),
                System.getenv("V022_EXACT_CROSS_STREAM_PROTOCOL_SHA256"), System.getenv("GITHUB_SHA"));
        var harness = new BExactCrossStreamComparison(controls);
        var receipt = harness.execute(id, Path.of("build/exact-cross-stream").resolve(id), System::nanoTime, CancellationProbe.NONE);
        assertEquals(id, harness.lastClosedCase()); assertEquals(0, harness.lastClosedBytes());
        assertEquals(1, receipt.get("actualSolverEntries")); assertEquals(1, receipt.get("normalReturns"));
        assertEquals(true, receipt.get("pipelineComplete")); assertEquals(false, receipt.get("modelQualified"));
        var solver = (Map<?,?>)receipt.get("solver"); assertEquals(1, solver.get("rawCount"), "Resource/numeric/no-route is failed feasibility, not qualification");
    }

    private static BExactCrossStreamComparison.Controls controls(boolean approved) {
        // UNIT-ONLY external metadata. No actual source could match these zero pins.
        var pins = new LinkedHashMap<String,BExactCrossStreamComparison.FrozenInput>();
        for (String id : BExactCrossStreamComparison.IDS) pins.put(id, new BExactCrossStreamComparison.FrozenInput("0".repeat(64),"1".repeat(64),57,1,10,20,"2".repeat(64),"3".repeat(64)));
        return new BExactCrossStreamComparison.Controls(approved,"4".repeat(40),"5".repeat(40),"6".repeat(64),"7".repeat(64),pins);
    }
    private static List<InferenceProfile> tinyProfiles() {
        var rows = new ArrayList<InferenceProfile>();
        for (int i=0;i<4;i++) rows.add(V022ProbabilisticInferenceTest.profile(i,new double[]{-0.1,.1},new double[]{.5,1.5},new double[]{0,.2},new String[]{"one","two"})
                .withObservationRows(new double[]{0,.2},new double[][]{{.25,.75},{.25,.75}},null));
        return List.copyOf(rows);
    }
    private static ProbabilisticInferenceResult controlled(ProbabilisticInferenceResult.Status status,List<MetricPoint> points,boolean completion) {
        var path=new ProbabilisticPath(new int[points.size()],points,"UNIT_ONLY_SYNTHETIC_RETURNED_CARRIER",0,0,1);
        return new ProbabilisticInferenceResult(status,Optional.of(path),List.of(path),List.of(path),0,List.of(),List.of(),List.of(),List.of(),List.of(),
                true,false,completion?Optional.of(new ProbabilisticInferenceResult.Completion(1,1,1,false,false,true)):Optional.empty(),0,0,
                new GapSummary(0,0,0,0,0,0),"UNIT_ONLY_NOT_NATIVE_EXECUTION_OR_AUTHORITY");
    }
}
