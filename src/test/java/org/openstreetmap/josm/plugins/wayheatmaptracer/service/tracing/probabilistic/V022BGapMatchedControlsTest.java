package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
    private static final List<String> THIN_CASES=List.of("thin-three-strands","thin-three-strands-intermittent","thin-strands-merge-separate",
            "drifting-strong-with-faint-original","thin-strong-sharp-bend","thin-faint-coherent-sharp-bend","thin-local-overlap-noise");
    private static final List<String> SWITCHBACK_CASES=BThinSwitchbackFixtures.IDS;
    private static final List<String> SAME_TRAIL_CASES=BSameTrailRecordingFixtures.IDS;
    private static final List<String> SEPARATED_SAME_TRAIL_CASES=List.of(BSeparatedSameTrailFixtures.ID);
    private static final List<String> ALL_CASES=Stream.concat(CASES.stream(),THIN_CASES.stream()).toList();
    private static final List<MetricPoint> SOURCE=List.of(new MetricPoint(8,37),new MetricPoint(32,37));
    private static final List<MetricPoint> THIN_SOURCE=List.of(new MetricPoint(-8,37),new MetricPoint(52,37));
    private static final EvidenceModelParameters PARAMETERS=EvidenceModelParameters.defaults();
    private static final TraceBudgets SOLVE_BUDGETS=new TraceBudgets(96,8_000_000,128_000_000,1,1);
    private static final BObservationKernel B0=new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(1,1,0));
    private static final Map<String,CaseResult> RESULTS=new LinkedHashMap<>();
    private static final Map<String,Map<String,Object>> CONTROL_EVIDENCE=new LinkedHashMap<>();
    private static final Map<String,Map<String,Object>> ADMISSION_EVIDENCE=new LinkedHashMap<>();
    private static final Map<String,Map<Integer,ThinProof>> THIN_ORACLE_EVIDENCE=new LinkedHashMap<>();
    private static final Map<String,PipelineProgress> PIPELINE_EVIDENCE=new LinkedHashMap<>();
    private enum SourcePhase {RESERVE,CAPTURE,PROFILES,FACTS,PROPOSALS,ADMISSION,ADAPT,BASELINE_SOLVE,FIELD,TABLE,ENABLED_SOLVE,COMPLETE}
    /** Bounded coarse source diagnostics, never a replacement for returned solver work. */
    private static final class PipelineProgress {
        SourcePhase phase=SourcePhase.RESERVE,failedPhase;
        String cause="NONE",snapshot="unavailable";
        int profiles,modes,censored,solverCalls,solverReturns;
        long peak,current;
        List<Row> rows=List.of();
        Map<String,Object> capture=Map.of();
        Map<String,Object> receipt() {
            var result=new LinkedHashMap<String,Object>();result.put("phase",phase.name());result.put("failedPhase",failedPhase==null?null:failedPhase.name());
            result.put("safeCause",cause);result.put("solverInvocations",solverCalls);result.put("solverReturns",solverReturns);
            result.put("peakAttemptBytes",peak);result.put("remainingOwnedBytesAfterCase",current);result.put("capture",capture);return result;
        }
        void failed(RuntimeException failure){failedPhase=phase;cause=safeFailure(failure);}
    }
    private record ThinProof(Row row,Map<String,Object> receipt) { }
    private static final Path OUTPUT=Path.of("build/gap-task5-matrix");
    static Stream<String> cases(){return CASES.stream();}
    static Stream<String> thinCases(){return THIN_CASES.stream();}
    static Stream<String> allCases(){return ALL_CASES.stream();}
    static Stream<String> switchbackCases(){return SWITCHBACK_CASES.stream();}
    static Stream<String> sameTrailCases(){return SAME_TRAIL_CASES.stream();}
    @BeforeAll static void requireDistinctOptInSuites(){requiredCases("true".equals(System.getenv("V022_RUN_SWITCHBACK_CONTROLS")),"true".equals(System.getenv("V022_RUN_SAME_TRAIL_CONTROLS")),"true".equals(System.getenv("V022_RUN_SEPARATED_SAME_TRAIL_CONTROL")));}
    private static List<String> requiredCases(boolean switchbacks,boolean sameTrail,boolean separated) {
        if(separated&&(switchbacks||sameTrail))throw new IllegalArgumentException("Exactly one dedicated synthetic suite may be opted in");
        return separated?SEPARATED_SAME_TRAIL_CASES:requiredCases(switchbacks,sameTrail);
    }
    private static List<String> requiredCases(boolean switchbacks,boolean sameTrail) {
        if(switchbacks&&sameTrail)throw new IllegalArgumentException("Exactly one dedicated synthetic suite may be opted in");
        return sameTrail?SAME_TRAIL_CASES:switchbacks?SWITCHBACK_CASES:ALL_CASES;
    }
    static Stream<String> refusedAdmissionCases(){return Stream.of("connected-sharp-apex","faint-six-metre-bend","genuine-junction","valid-sparse-gap");}
    /** Restricted factual-source seam for the separate native-production comparison. */
    static List<MetricPoint> nativeComparisonSource(String id){BNativeProductionComparison.requireCase(id);return source(id);}
    /** Uses exactly the existing frozen scalar handoff and production resampler once. */
    static EvidenceSnapshot captureNativeComparisonSource(String id,Map<String,Object> binding,CancellationProbe probe){
        BNativeProductionComparison.requireCase(id);Objects.requireNonNull(binding);Objects.requireNonNull(probe).checkpoint();
        var input=isSwitchback(id)?switchbackInput(id):sameTrailInput(id);
        var scalar=input.scalar();var validity=input.validity();var raster=java.nio.ByteBuffer.allocate(scalar.length*8).order(java.nio.ByteOrder.BIG_ENDIAN);
        for(double value:scalar)raster.putDouble(value);byte[] mask=new byte[validity.length];for(int i=0;i<mask.length;i++)mask[i]=(byte)(validity[i]?1:0);
        binding.put("rasterSha256",BIdentificationReceipt.hash(raster.array()));binding.put("validitySha256",BIdentificationReceipt.hash(mask));
        binding.put("width",input.width);binding.put("height",input.height);binding.put("origin",List.of(input.origin.xMeters(),input.origin.yMeters()));binding.put("pitchMeters",input.pitchMeters);
        var evidence=captureScalarOnGrid(id,input,switchbackOutputGrid(input));probe.checkpoint();return evidence;
    }

    /** Fixed scalar-only seam. Neither truth descriptors nor G/identified admission enters native B. */
    static List<String> nativeDefaultCases(){return Stream.concat(ALL_CASES.stream(),BNativeProductionComparison.IDS.stream()).toList();}
    static List<MetricPoint> nativeDefaultSource(String id){BNativeProductionDefaultComparison.requireCase(id);return source(id);}
    private static ScalarInput originalScalarInput(String id,List<MetricPoint> source){
        int w=81,h=81;double[] scalar=new double[w*h];boolean[] valid=new boolean[w*h];Arrays.fill(valid,true);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double worldX=x-20;scalar[y*w+x]=Math.max(0,Math.min(1,intensity(id,worldX,y)));
            if((id.equals("invalid-unknown-gap")||id.equals("unknown-detour"))&&worldX>=18&&worldX<=22&&y>=34&&y<=40)valid[y*w+x]=false;
        }
        return new ScalarInput(w,h,p(-20,0),1,scalar,valid,source);
    }
    static EvidenceSnapshot captureNativeDefaultSource(String id,Map<String,Object> binding,CancellationProbe probe){
        BNativeProductionDefaultComparison.requireCase(id);Objects.requireNonNull(binding);probe.checkpoint();
        var source=source(id);var input=isCurvedControl(id)?(isSwitchback(id)?switchbackInput(id):sameTrailInput(id)):isThin(id)?thinInput(id):originalScalarInput(id,source);
        if(input.width!=81||input.height!=81||input.pitchMeters!=1||source.size()>12)throw new SecurityException("Fixed native dimensions changed");
        var bytes=java.nio.ByteBuffer.allocate(input.scalar.length*8).order(java.nio.ByteOrder.BIG_ENDIAN);
        for(int i=0;i<input.scalar.length;i++){if((i&1023)==0)probe.checkpoint();bytes.putDouble(input.scalar[i]);}
        byte[] mask=new byte[input.validity.length];for(int i=0;i<mask.length;i++){if((i&1023)==0)probe.checkpoint();mask[i]=(byte)(input.validity[i]?1:0);}
        binding.put("rasterSha256",BIdentificationReceipt.hash(bytes.array()));binding.put("validitySha256",BIdentificationReceipt.hash(mask));
        binding.put("width",input.width);binding.put("height",input.height);binding.put("origin",List.of(input.origin.xMeters(),input.origin.yMeters()));binding.put("pitchMeters",input.pitchMeters);
        var evidence=capture(id,source,32L*1024*1024,probe);probe.checkpoint();
        var field=evidence.fields().get("scalar");
        BNativeProductionDefaultComparison.checkCaptureMetadata(id,evidence,source);
        binding.put("snapshotHash",evidence.canonicalHash());binding.put("outputWidth",field.width());binding.put("outputHeight",field.height());
        binding.put("captureUpperBytes",BNativeProductionDefaultComparison.captureUpperBytes(Math.multiplyExact(field.width(),field.height()),source.size()));
        return evidence;
    }
    /** Differential capture witness only; no profiles, facts or inference. */
    static EvidenceSnapshot nativeDefaultLegacyCapture(String id){BNativeProductionDefaultComparison.requireCase(id);return capture(id,source(id));}
    static boolean nativePointTruth(String id,List<MetricPoint> path,List<MetricPoint> original,List<MetricPoint> nativeSource){
        if(!CASES.contains(id))throw new IllegalArgumentException("Not an original point oracle");return inTruth(id,path);
    }
    static boolean nativePointObserved(EvidenceSnapshot evidence,List<MetricPoint> path){return fullyObserved(evidence,path);}

    private static AttemptMemoryLedger newComparisonLedger(){return new AttemptMemoryLedger(BGapEvidence.BYTES);}

    /** This is the entire new acquisition handoff; truth descriptors are deliberately absent. */
    private record ScalarInput(int width,int height,MetricPoint origin,double pitchMeters,double[] scalar,boolean[] validity,List<MetricPoint> source) {
        ScalarInput {
            if(width<2||height<2||origin==null||!Double.isFinite(pitchMeters)||pitchMeters<=0||scalar==null||validity==null
                    ||scalar.length!=Math.multiplyExact(width,height)||validity.length!=scalar.length||source==null||source.size()<2)
                throw new IllegalArgumentException("Declared scalar acquisition is incomplete");
            scalar=scalar.clone();validity=validity.clone();source=List.copyOf(source);
        }
        @Override public double[] scalar(){return scalar.clone();}
        @Override public boolean[] validity(){return validity.clone();}
    }
    private static ScalarInput thinInput(String id) {
        var fixture=BThinStrandFixtures.byId(id);
        return new ScalarInput(fixture.width(),fixture.height(),fixture.origin(),fixture.pitchMeters(),fixture.intensities(),fixture.validity(),fixture.source());
    }

    private static ScalarInput switchbackInput(String id) {
        var fixture=BThinSwitchbackFixtures.byId(id);
        return new ScalarInput(81,81,BThinSwitchbackFixtures.origin(),1,fixture.scalar(),fixture.validity(),BThinSwitchbackFixtures.source());
    }
    private static ScalarInput sameTrailInput(String id) {
        var fixture=isSeparatedSameTrail(id)?BSeparatedSameTrailFixtures.fixture():BSameTrailRecordingFixtures.byId(id);
        return new ScalarInput(81,81,BThinSwitchbackFixtures.origin(),1,fixture.scalar(),fixture.validity(),BSameTrailRecordingFixtures.source());
    }
    @Test void sameTrailSourceBoundaryPreservesPriorInventoriesAndNeverPassesTraceLabels() {
        assertEquals(27,ALL_CASES.size());assertEquals(2,SWITCHBACK_CASES.size());assertEquals(2,SAME_TRAIL_CASES.size());
        assertTrue(Collections.disjoint(ALL_CASES,SAME_TRAIL_CASES));assertTrue(Collections.disjoint(SWITCHBACK_CASES,SAME_TRAIL_CASES));
        assertEquals(List.of("width","height","origin","pitchMeters","scalar","validity","source"),
                Arrays.stream(ScalarInput.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        for(String id:SAME_TRAIL_CASES){var input=sameTrailInput(id);assertEquals(input.source,source(id));assertEquals(57,expectedProfiles(id));
            assertEquals(6561,input.scalar.length);assertEquals(p(-25,-20),input.origin);}
        assertSame(ALL_CASES,requiredCases(false,false));assertSame(SWITCHBACK_CASES,requiredCases(true,false));assertSame(SAME_TRAIL_CASES,requiredCases(false,true));
        assertThrows(IllegalArgumentException.class,()->requiredCases(true,true));
    }
    @Test void separatedSameTrailMetadataBindsOneSourceWithoutChangingPriorSchedules() {
        String id=BSeparatedSameTrailFixtures.ID;assertTrue(isCurvedControl(id),"additive curved case must bind its real source");
        assertEquals(BSameTrailRecordingFixtures.source(),source(id));assertEquals(57,expectedProfiles(id));
        assertEquals(27,ALL_CASES.size());assertEquals(2,SAME_TRAIL_CASES.size());assertEquals(2,SWITCHBACK_CASES.size());
        assertSame(SEPARATED_SAME_TRAIL_CASES,requiredCases(false,false,true));assertEquals(List.of(id),SEPARATED_SAME_TRAIL_CASES);
        assertThrows(IllegalArgumentException.class,()->requiredCases(true,false,true));assertThrows(IllegalArgumentException.class,()->requiredCases(false,true,true));
        assertSame(ALL_CASES,requiredCases(false,false,false));assertSame(SAME_TRAIL_CASES,requiredCases(false,true,false));
        var handed=sameTrailInput(id);assertArrayEquals(BSeparatedSameTrailFixtures.fixture().scalar(),handed.scalar);
        assertEquals(List.of("width","height","origin","pitchMeters","scalar","validity","source"),
                Arrays.stream(ScalarInput.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        var f=BSeparatedSameTrailFixtures.fixture();var input=new ScalarInput(81,81,p(-25,-20),1,f.scalar(),f.validity(),BSameTrailRecordingFixtures.source());
        assertTrue(switchbackOutputGrid(input).footprint().containsRegion(MetricCorridorRegion.aroundPolyline(input.source,25)));
        assertEquals(128_000_000,SOLVE_BUDGETS.maximumTransitions());assertEquals(64L*1024*1024,newComparisonLedger().limitBytes());
    }
    @Test void sameTrailMetadataPreflightUsesUnchangedWindowAndCaps() {
        var input=sameTrailInput(BSameTrailRecordingFixtures.THREE);var grid=switchbackOutputGrid(input);
        assertTrue(grid.footprint().containsRegion(MetricCorridorRegion.aroundPolyline(input.source,25)));
        assertEquals(96,grid.width());assertEquals(95,grid.height());assertEquals(p(-35,-33),grid.firstPixelCenter());
        assertEquals(57,expectedProfiles(BSameTrailRecordingFixtures.FIVE));
        assertEquals(128_000_000,SOLVE_BUDGETS.maximumTransitions());assertEquals(8_000_000,SOLVE_BUDGETS.maximumPairVisits());assertEquals(96,SOLVE_BUDGETS.maximumStatesPerProfile());
        var ledger=newComparisonLedger();assertEquals(64L*1024*1024,ledger.limitBytes());
        System.out.println("SAME_TRAIL_METADATA profiles57 sourceLength="+BSameTrailRecordingFixtures.length()+" nativePairUpper497856 tableBytesUpper9959848; original sharedFB+K128M runtime refusal remains possible");
    }
    @Test void sameTrailReturnedPathsUseRepresentativeOracleAndFailureStatesRemainHonest() {
        String id=BSameTrailRecordingFixtures.THREE;var prior=THIN_ORACLE_EVIDENCE.remove(id);var ledger=newComparisonLedger();
        try(var owner=ledger.rootOwner();var metadata=owner.reserve(1024L*1024)) {
            var raw=controlledThinRow(0,BSameTrailRecordingFixtures.source());var checked=assessSwitchbackReturnedPath(id,raw,1,CancellationProbe.NONE,owner);
            assertTrue(checked.geometryPassed);assertTrue(checked.controlPassed);assertEquals(raw.work,checked.work);assertEquals(raw.energy,checked.energy);
            assertEquals(BThinSwitchbackAcceptance.ORACLE_ID,((Map<?,?>)receiptForCase(id,checked).get("truthOracle")).get("oracle"));
            var oneRecording=BSameTrailRecordingFixtures.byId(id).traces().get(0);
            var rejected=assessSwitchbackReturnedPath(id,controlledThinRow(1,oneRecording),1,CancellationProbe.NONE,owner);
            assertFalse(rejected.geometryPassed);assertFalse(rejected.controlPassed);assertFalse(stopsCase(rejected));
            assertEquals("FAIL",receiptForCase(id,rejected).get("wholeRouteGeometryVerdict"));
            for(var row:List.of(unavailable(2,"UNVISITED"),new Row(4,true,ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,List.of(),List.of(),Double.NaN,128000000,
                    "actual-resource-work","Optional.empty","RETURNED_RESOURCE_LIMIT",false,false,false))) {
                var unavailable=assessSwitchbackReturnedPath(id,row,1,()->{throw new AssertionError("No path must not query truth");},owner);
                assertSame(row,unavailable);assertNull(receiptForCase(id,unavailable).get("wholeRouteGeometryPassed"));assertFalse(unavailable.controlPassed);
            }
        }finally {if(prior==null)THIN_ORACLE_EVIDENCE.remove(id);else THIN_ORACLE_EVIDENCE.put(id,prior);}
        assertEquals(0,ledger.currentBytes());
    }

    @Test void switchbackHandoffHasOnlyScalarSourceInputsAndOldDefaultRemainsTwentySeven() {
        assertEquals(27,ALL_CASES.size());assertEquals(2,SWITCHBACK_CASES.size());
        assertTrue(Collections.disjoint(ALL_CASES,SWITCHBACK_CASES));
        assertEquals(List.of("width","height","origin","pitchMeters","scalar","validity","source"),
                Arrays.stream(ScalarInput.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        for(String id:SWITCHBACK_CASES){var input=switchbackInput(id);assertEquals(BThinSwitchbackFixtures.source(),input.source);
            assertTrue(input.source.size()>2);assertEquals(6561,input.scalar.length);assertEquals(BThinSwitchbackFixtures.origin(),input.origin);
            assertEquals(new ProbabilisticProfileFactory().sourceSamplingGeometry(input.source,1.5).points().size(),expectedProfiles(id));}
    }

    @Test void switchbackMetadataPreflightCoversOriginalWindowAndReportsConservativePayload() {
        var input=switchbackInput(BThinSwitchbackFixtures.STRONG);var grid=switchbackOutputGrid(input);
        assertTrue(grid.footprint().containsRegion(MetricCorridorRegion.aroundPolyline(input.source,25)));
        int n=expectedProfiles(BThinSwitchbackFixtures.STRONG);assertTrue(n<=81);
        assertEquals(p(-25,-20),input.origin);assertEquals(81,input.width);assertEquals(81,input.height);
        // Native full96-state interiors, not evidence that these unread actual scenes fit/succeed.
        long pairs=(n-3L)*96*96+2*96;
        long table=784+2*AttemptMemoryLedger.arrayBytes(pairs,8)+AttemptMemoryLedger.arrayBytes(pairs,4)
                +2*AttemptMemoryLedger.arrayBytes(n-1,8)+2*AttemptMemoryLedger.arrayBytes(n,4)+AttemptMemoryLedger.arrayBytes(n,8);
        assertTrue(table<64L*1024*1024);assertEquals(6561,input.scalar.length);
        System.out.println("SWITCHBACK_METADATA profiles="+n+" sourceLength="+BThinSwitchbackFixtures.length()+" nativePairUpper="+pairs+
                " tableBytesUpper="+table+" output="+grid.width()+"x"+grid.height()+" first="+grid.firstPixelCenter());
    }

    @Test void switchbackTruthIsBoundOnlyAfterReturnedPathAndUnavailableNeverPasses() {
        String id=BThinSwitchbackFixtures.STRONG;var prior=THIN_ORACLE_EVIDENCE.remove(id);var ledger=newComparisonLedger();
        try(var owner=ledger.rootOwner();var metadata=owner.reserve(1024L*1024)) {
            var raw=controlledThinRow(0,BThinSwitchbackFixtures.source());var checked=assessSwitchbackReturnedPath(id,raw,1,CancellationProbe.NONE,owner);
            assertTrue(checked.geometryPassed);assertTrue(checked.controlPassed);assertEquals(raw.points,checked.points);assertEquals(raw.work,checked.work);
            assertEquals(BThinSwitchbackAcceptance.ORACLE_ID,((Map<?,?>)receiptForCase(id,checked).get("truthOracle")).get("oracle"));
            var wrong=assessSwitchbackReturnedPath(id,controlledThinRow(1,List.of(p(0,0),p(23,29))),1,CancellationProbe.NONE,owner);
            assertFalse(wrong.geometryPassed);assertFalse(wrong.controlPassed);assertFalse(stopsCase(wrong));
            assertEquals("FAIL",receiptForCase(id,wrong).get("wholeRouteGeometryVerdict"));
            var notBound=receiptForCase(id,raw);assertNull(notBound.get("wholeRouteGeometryPassed"));assertEquals(false,notBound.get("requiredControlPassed"));
            for(var row:List.of(unavailable(2,"UNVISITED"),new Row(4,true,ProbabilisticInferenceResult.Status.NO_ROUTE,List.of(),List.of(),Double.NaN,9,
                    "actual-no-route","Optional.empty","NO_ROUTE",false,false,false))) {
                var noPath=assessSwitchbackReturnedPath(id,row,1,()->{throw new AssertionError("No path must never read truth");},owner);
                assertSame(row,noPath);assertNull(receiptForCase(id,noPath).get("wholeRouteGeometryPassed"));assertFalse(noPath.controlPassed);
            }
        }finally {if(prior==null)THIN_ORACLE_EVIDENCE.remove(id);else THIN_ORACLE_EVIDENCE.put(id,prior);}
        assertEquals(0,ledger.currentBytes());
    }

    @Test void additionalThinInventoryPreservesOriginalTwentyAndFrozenRecipeBytes() throws Exception {
        assertEquals(20,CASES.size());assertEquals(7,THIN_CASES.size());assertEquals(27,allCases().distinct().count());
        assertEquals(THIN_CASES,BThinStrandFixtures.all().stream().map(BThinStrandFixtures.Fixture::id).toList());
        for(var item:Map.of("matched-controls.json","543fb7add12d3afab901dc31930820c3a7012cd2ca44ee1047094b4ff369026e",
                "thin-strand-controls.json","072ce1b0aed7e3d61c7da1d40ac4bda70e3b682c2ba872f9c8496aaab5660d2c").entrySet())
            try(var resource=V022BGapMatchedControlsTest.class.getResourceAsStream("/b-observed-gap/"+item.getKey())) {
                assertNotNull(resource);assertEquals(item.getValue(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(resource.readAllBytes())));
            }
    }

    @ParameterizedTest(name="thin-metadata-{0}") @MethodSource("thinCases")
    void thinHandoffContainsOnlyNativeScalarValidityFrameAndSource(String id) {
        var input=thinInput(id);var fixture=BThinStrandFixtures.byId(id);
        assertEquals(81,input.width);assertEquals(81,input.height);assertEquals(p(-20,0),input.origin);assertEquals(1,input.pitchMeters);
        assertEquals(List.of(p(-8,37),p(52,37)),input.source);assertEquals(60,input.source.get(0).distanceTo(input.source.get(1)));
        assertEquals(input.source,source(id));
        assertEquals(41,expectedProfiles(id));
        assertEquals(-25,fixture.lateralMinMeters());assertEquals(25,fixture.lateralMaxMeters());
        assertEquals(List.of("width","height","origin","pitchMeters","scalar","validity","source"),
                Arrays.stream(ScalarInput.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        var values=input.scalar();var validity=input.validity();assertEquals(6561,values.length);assertEquals(6561,validity.length);
        for(int y=0;y<81;y++)for(int x=0;x<81;x++) {
            assertEquals(Double.doubleToRawLongBits(fixture.intensity(x,y)),Double.doubleToRawLongBits(values[y*81+x]));
            assertEquals(fixture.valid(x,y),validity[y*81+x]);assertTrue(validity[y*81+x],"intermittent contributions never become invalid source");
        }
    }

    @Test void scalarHandoffOwnsDefensiveValuesWithoutRetainingTruthContainers() {
        var values=new double[]{.02,.04,.06,.08};var valid=new boolean[]{true,true,true,true};var source=new ArrayList<>(List.of(p(-8,37),p(52,37)));
        var input=new ScalarInput(2,2,p(-20,0),1,values,valid,source);
        values[0]=1;valid[0]=false;source.set(0,p(0,0));
        assertEquals(.02,input.scalar()[0]);assertTrue(input.validity()[0]);assertEquals(p(-8,37),input.source().get(0));
        input.scalar()[0]=1;input.validity()[0]=false;assertEquals(.02,input.scalar()[0]);assertTrue(input.validity()[0]);
        assertThrows(UnsupportedOperationException.class,()->input.source().set(0,p(0,0)));
        assertThrows(IllegalArgumentException.class,()->thinInput("not-a-frozen-control"));
    }

    @Test void thinFortyOneRowPayloadCensusIsExplicitWithoutPromisingSolverSuccess() {
        // Worst native layout:39 interior96-state rows and two exact single-state anchors.
        int n=41;int[] counts=new int[n];Arrays.fill(counts,96);counts[0]=counts[n-1]=1;
        long pairs=0,fbMatrices=0;
        for(int i=1;i<n;i++) {
            pairs+=(long)counts[i-1]*counts[i];
            fbMatrices+=2*(AttemptMemoryLedger.referenceArrayBytes(counts[i-1])
                    +counts[i-1]*AttemptMemoryLedger.arrayBytes(counts[i],8));
        }
        long table=784+2*AttemptMemoryLedger.arrayBytes(pairs,8)+AttemptMemoryLedger.arrayBytes(pairs,4)
                +2*AttemptMemoryLedger.arrayBytes(n-1,8)+2*AttemptMemoryLedger.arrayBytes(n,4)+AttemptMemoryLedger.arrayBytes(n,8);
        assertEquals(350400,pairs);assertEquals(7010216,table);assertEquals(5787440,fbMatrices);
        long partialLive=33L*1024*1024+table+fbMatrices;assertEquals(47400664,partialLive);
        assertTrue(partialLive<BGapEvidence.BYTES);
        // NOT a whole-attempt fit proof: actual profiles/facts/Kbest/cache/result still coexist.
        // Existing reservations must refuse the whole setting if remaining ownership cannot fit.
    }

    @Test void oneThinNativeCaptureReachesTheUnchangedProfileInput() {
        String id="thin-three-strands";var ledger=newComparisonLedger();
        try(var owner=ledger.rootOwner();var render=owner.reserve(32L*1024*1024);var metadata=owner.reserve(1024L*1024)) {
            var evidence=capture(id,source(id));
            assertEquals(MetricCorridorRegion.aroundPolyline(THIN_SOURCE,25).polygons(),evidence.decisionRegion().polygons());
            assertEquals(1,evidence.resolution().nativePitchMeters().orElseThrow());
            assertEquals(1,evidence.resolution().outputRasterPitchMeters());
            assertEquals(81,evidence.resampling().inputWidth());assertEquals(81,evidence.resampling().inputHeight());
            assertEquals(111,evidence.resampling().outputWidth());assertEquals(81,evidence.resampling().outputHeight());
            assertEquals(p(-33,0),evidence.transform().pixelCenterToMetric(0,0));
            var input=thinInput(id);var referenceInput=new ScalarInput(81,81,input.origin,1,input.scalar(),input.validity(),SOURCE);
            var frame=evidence.coordinateFrame();
            var reference=captureScalarOnGrid(id,referenceInput,new MetricRasterGrid(frame,input.origin,1,0,0,1,1,81,81));
            assertEquals(reference.resampling().sourceTransformIdentity(),evidence.resampling().sourceTransformIdentity());
            var field=evidence.fields().get("scalar");var oldField=reference.fields().get("scalar");
            // Compare two actual production resamplings, including strict four-corner edge validity.
            var values=field.copiedValues();var oldValues=oldField.copiedValues();
            for(int y=0;y<81;y++)for(int x=0;x<81;x++) {
                assertEquals(oldField.sample(x,y).isPresent(),field.sample(x+13,y).isPresent());
                assertEquals(Double.doubleToRawLongBits(oldValues[y*81+x]),Double.doubleToRawLongBits(values[y*111+x+13]));
                if(x<80&&y<80)assertEquals(oldField.supportsInterpolationCell(x,y),field.supportsInterpolationCell(x+13,y));
            }
            for(int y=0;y<81;y++)for(int x=0;x<111;x++)if(x<13||x>93) {
                assertTrue(field.sample(x,y).isEmpty());assertTrue(Double.isNaN(values[y*111+x]));
                assertFalse(field.supportsInterpolationCell(x,y));
            }
            for(int y=0;y<81;y++)assertTrue(field.sample(93,y).isEmpty());
            for(int x=0;x<111;x++)assertTrue(field.sample(x,80).isEmpty());
            assertEquals(8991,values.length);assertTrue(values.length<=191401);assertTrue(110*80<=190404);
            long logicalPeak=6561L*16+8991L*36;
            assertTrue(logicalPeak+logicalPeak/8+16L*1024*1024+6561L*8<32L*1024*1024,
                    "existing reserved render payload covers the actual direct scalar producer working estimate");
        } finally {assertEquals(0,ledger.currentBytes());}
    }

    @Test void thinReturnedPathUsesReviewedOracleAndPreservesReturnedSolverFacts() {
        String id="thin-strong-sharp-bend";var prior=THIN_ORACLE_EVIDENCE.remove(id);var ledger=newComparisonLedger();
        try(var owner=ledger.rootOwner();var metadata=owner.reserve(1024L*1024)) {
            var path=List.of(p(-8,37),p(19,37),p(22,40),p(25,37),p(52,37));var raw=controlledThinRow(0,path);
            var checked=assessThinReturnedPath(id,raw,1,CancellationProbe.NONE,owner);
            assertTrue(checked.geometryPassed);assertTrue(checked.controlPassed);assertEquals(raw.status,checked.status);
            assertEquals(raw.points,checked.points);assertEquals(raw.states,checked.states);assertEquals(raw.work,checked.work);
            assertEquals(raw.completion,checked.completion);assertEquals(raw.transitions,checked.transitions);
            assertEquals(Double.doubleToRawLongBits(raw.energy),Double.doubleToRawLongBits(checked.energy));
            var flat=assessThinReturnedPath(id,controlledThinRow(1,List.of(p(-8,37),p(52,37))),1,CancellationProbe.NONE,owner);
            assertFalse(flat.geometryPassed);assertFalse(flat.controlPassed);assertFalse(stopsCase(flat));
            var receipt=receiptForCase(id,flat);assertEquals("FAIL",receipt.get("wholeRouteGeometryVerdict"));
            var oracle=(Map<?,?>)receipt.get("truthOracle");assertEquals(BThinStrandAcceptance.ORACLE_ID,oracle.get("oracle"));
            assertEquals("sharp-course",oracle.get("expectedRouteKind"));assertEquals(5,((List<?>)oracle.get("checks")).size());
            assertTrue(((Number)oracle.get("cancellationChecks")).longValue()>0);
        } finally {if(prior==null)THIN_ORACLE_EVIDENCE.remove(id);else THIN_ORACLE_EVIDENCE.put(id,prior);}
        assertEquals(0,ledger.currentBytes());
    }

    @Test void thinMissingReturnedPathCannotInvokeOracleOrInventGeometry() {
        String id="thin-three-strands";var prior=THIN_ORACLE_EVIDENCE.remove(id);var ledger=newComparisonLedger();
        try(var owner=ledger.rootOwner();var metadata=owner.reserve(1024L*1024)) {
            for(var status:List.of(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,ProbabilisticInferenceResult.Status.NO_ROUTE)) {
                var raw=new Row(0,true,status,List.of(),List.of(),Double.NaN,7,"actual-failure-work","actual-completion","ACTUAL_RETURNED_FAILURE",false,false,false);
                var checked=assessThinReturnedPath(id,raw,1,()->{throw new AssertionError("No path must not query truth");},owner);
                assertEquals(status,checked.status);assertEquals(raw.work,checked.work);assertFalse(checked.controlPassed);
                assertEquals("UNAVAILABLE_NO_RETURNED_PATH",receiptForCase(id,checked).get("wholeRouteGeometryVerdict"));
            }
            assessThinReturnedPath(id,unavailable(1,"UNVISITED"),1,()->{throw new AssertionError("Unvisited must not query truth");},owner);
            THIN_ORACLE_EVIDENCE.remove(id);
            var missing=receiptForCase(id,controlledThinRow(0,List.of(p(-8,37),p(52,37))));
            assertNull(missing.get("wholeRouteGeometryPassed"));assertEquals("UNAVAILABLE_THIN_ORACLE",missing.get("wholeRouteGeometryVerdict"));
            assertEquals(false,missing.get("requiredControlPassed"));
        } finally {if(prior==null)THIN_ORACLE_EVIDENCE.remove(id);else THIN_ORACLE_EVIDENCE.put(id,prior);}
    }

    @Test void thinOracleCancellationAndResourceRefusalPreserveActualSolveAndCloseOwners() throws Exception {
        String id="thin-strong-sharp-bend";var prior=THIN_ORACLE_EVIDENCE.remove(id);var ledger=newComparisonLedger();
        var raw=controlledThinRow(0,List.of(p(-8,37),p(52,37)));
        try(var owner=ledger.rootOwner();var metadata=owner.reserve(1024L*1024)) {
            var tree=BGapTestFixture.ownerTree(owner);long bytes=owner.currentBytes();
            var cancelled=assessThinReturnedPath(id,raw,1,()->true,owner);
            assertTrue(stopsCase(cancelled));assertTrue(cancelled.actualSolve);assertEquals(raw.status,cancelled.status);
            assertEquals(raw.work,cancelled.work);assertEquals(raw.points,cancelled.points);
            assertNull(receiptForCase(id,cancelled).get("wholeRouteGeometryPassed"));assertEquals(bytes,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));
            var unavailable=assessThinReturnedPath(id,raw,.5,CancellationProbe.NONE,owner);
            assertTrue(stopsCase(unavailable));assertEquals(raw.status,unavailable.status);
            assertNull(receiptForCase(id,unavailable).get("wholeRouteGeometryPassed"));
            assertEquals("UNAVAILABLE",((Map<?,?>)receiptForCase(id,unavailable).get("truthOracle")).get("failure"));
            try(var blocker=owner.reserve(ledger.limitBytes()-bytes-1000)) {
                var refused=assessThinReturnedPath(id,raw,1,CancellationProbe.NONE,owner);
                assertTrue(stopsCase(refused));assertEquals(raw.status,refused.status);assertEquals(raw.transitions,refused.transitions);
                var receipt=receiptForCase(id,refused);assertNull(receipt.get("wholeRouteGeometryPassed"));
                assertEquals("RESOURCE_LIMIT",((Map<?,?>)receipt.get("truthOracle")).get("failure"));
            }
            assertEquals(bytes,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));
        } finally {if(prior==null)THIN_ORACLE_EVIDENCE.remove(id);else THIN_ORACLE_EVIDENCE.put(id,prior);}
    }

    private static Row controlledThinRow(int eta,List<MetricPoint> path) {
        return new Row(eta,true,ProbabilisticInferenceResult.Status.REVIEW_REQUIRED,path,List.of(0,1),.5,100,
                "controlled-returned-work","controlled-completion","CONTROLLED_RETURNED_PATH",false,true,false);
    }
    private static Row assessThinReturnedPath(String id,Row row,double pitch,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        if(!isThin(id)||Arrays.binarySearch(ETAS,row.eta)<0)throw new IllegalArgumentException("Unknown thin source-control slot");
        Objects.requireNonNull(cancellation);Objects.requireNonNull(owner);
        // Counter/receipt payloads belong to the existing bounded1MiB harness metadata reserve.
        // The actual oracle independently reserves its result and complete path/track workspace.
        var receipt=new LinkedHashMap<String,Object>();receipt.put("oracle",BThinStrandAcceptance.ORACLE_ID);
        receipt.put("fixture",id);receipt.put("invoked",completed(row));receipt.put("checks",List.of());
        receipt.put("branchIdentity","UNAVAILABLE_ORACLE_REPORTS_CONTINUITY_NOT_STRAND_ID");
        receipt.put("workUnits","UNAVAILABLE_PRIVATE_ORACLE_COUNTER_NOT_EXPORTED");
        long[] calls={0};boolean passed=false,terminal=false;String failure="NONE";
        if(!completed(row))receipt.put("availability","UNAVAILABLE_NO_RETURNED_PATH");
        else {
            try(var oracleOwner=owner.child("matched-thin-truth-oracle")) {
                var result=BThinStrandAcceptance.evaluate(BThinStrandFixtures.byId(id),row.points,pitch,
                        ()->{calls[0]++;return cancellation.cancelled();},oracleOwner);
                receipt.put("expectedRouteKind",result.expectedRouteKind());
                receipt.put("checks",result.checks().stream().map(check->Map.of("reason",check.reason().name(),
                        "verdict",check.verdict().name(),"count",check.count())).toList());
                terminal=result.checks().stream().anyMatch(check->check.verdict()==BThinStrandAcceptance.Verdict.UNAVAILABLE);
                passed=result.passed();receipt.put("availability",terminal?"UNAVAILABLE":"AVAILABLE");
                receipt.put("verdict",terminal?"UNAVAILABLE":passed?"PASS":"FAIL");
                if(terminal)failure="UNAVAILABLE";
            } catch(RuntimeException stopped) {
                terminal=true;failure=stopped instanceof java.util.concurrent.CancellationException?"CANCELLED":
                        stopped instanceof AttemptMemoryLedger.ResourceLimitException?"RESOURCE_LIMIT":safeFailure(stopped);
                receipt.put("availability","UNAVAILABLE");receipt.put("verdict","UNAVAILABLE");
            }
        }
        receipt.put("failure",failure);receipt.put("terminal",terminal);receipt.put("cancellationChecks",calls[0]);
        Row checked=!completed(row)?row:new Row(row.eta,row.actualSolve,row.status,List.copyOf(row.points),List.copyOf(row.states),
                row.energy,row.transitions,row.work,row.completion,terminal?"THIN_ORACLE_TERMINAL_"+failure:row.explanation,
                passed,row.sourceObserved,passed&&row.sourceObserved);
        THIN_ORACLE_EVIDENCE.computeIfAbsent(id,key->new LinkedHashMap<>()).put(row.eta,new ThinProof(checked,Map.copyOf(receipt)));
        return checked;
    }

    /** Independent truth-side geometry, invoked only after the actual solver returns. */
    private static Row assessSwitchbackReturnedPath(String id,Row row,double pitch,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        if(!isCurvedControl(id)||Arrays.binarySearch(ETAS,row.eta)<0)throw new IllegalArgumentException("Unknown curved source-control slot");
        var receipt=new LinkedHashMap<String,Object>();receipt.put("oracle",BThinSwitchbackAcceptance.ORACLE_ID);
        receipt.put("invoked",completed(row));receipt.put("availability","UNAVAILABLE_NO_RETURNED_PATH");
        boolean passed=false,terminal=false;String failure="NONE";
        if(completed(row))try(var oracleOwner=owner.child("matched-switchback-truth-oracle")) {
            var result=BThinSwitchbackAcceptance.evaluate(BThinSwitchbackFixtures.source(),row.points,pitch,cancellation,oracleOwner);
            receipt.putAll(result.receipt());terminal=!result.available();passed=result.passed();
            receipt.put("availability",terminal?"UNAVAILABLE":"AVAILABLE");receipt.put("verdict",terminal?"UNAVAILABLE":passed?"PASS":"FAIL");
            if(terminal)failure="UNAVAILABLE";
        } catch(RuntimeException stopped) {
            terminal=true;failure=safeFailure(stopped);receipt.put("availability","UNAVAILABLE");receipt.put("verdict","UNAVAILABLE");
        }
        receipt.put("failure",failure);receipt.put("terminal",terminal);
        Row checked=!completed(row)?row:new Row(row.eta,row.actualSolve,row.status,row.points,row.states,row.energy,row.transitions,row.work,row.completion,
                terminal?"THIN_ORACLE_TERMINAL_"+failure:row.explanation,passed,row.sourceObserved,passed&&row.sourceObserved);
        THIN_ORACLE_EVIDENCE.computeIfAbsent(id,key->new LinkedHashMap<>()).put(row.eta,new ThinProof(checked,Map.copyOf(receipt)));
        return checked;
    }

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

    @Test void connectedStraightPipelineSmoke() throws Exception {var result=result("connected-straight");verify(result);requireSourceExecution(result);}

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

    @Test void requiredSourceExecutionRejectsMissingAndTerminalWorkButNotGeometryOutcomes() {
        var successful=new ArrayList<Row>();
        for(int eta:ETAS)successful.add(controlledThinRow(eta,SOURCE));
        var base=new CaseResult("controlled-execution",SOURCE,successful,2,0,0,0,0,0,false,false,"captured","facts","cold","hot");
        assertDoesNotThrow(()->requireSourceExecution(base)); // Geometry FAIL is a reported model outcome.
        for(var failure:List.of(unavailable(64,"UNVISITED_AFTER_WHOLE_CASE_FAILURE"),
                new Row(64,true,ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,List.of(),List.of(),Double.NaN,9,"actual-work","actual-completion","RETURNED_RESOURCE",false,false,false),
                new Row(64,true,ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,List.of(),List.of(),Double.NaN,9,"actual-work","actual-completion","RETURNED_NUMERIC",false,false,false),
                new Row(64,true,ProbabilisticInferenceResult.Status.NO_ROUTE,List.of(),List.of(),Double.NaN,0,"unavailable","Optional.empty","THROWN_SOLVER_FAILURE_ILLEGAL_ARGUMENT",false,false,false),
                new Row(64,true,ProbabilisticInferenceResult.Status.COMPLETE,SOURCE,List.of(0,0),0,9,"actual-work","actual-completion","THIN_ORACLE_TERMINAL_RESOURCE_LIMIT",false,false,false))) {
            var rows=new ArrayList<>(successful);rows.set(7,failure);
            assertThrows(AssertionError.class,()->requireSourceExecution(new CaseResult(base.id,base.source,rows,2,0,0,0,0,0,false,false,"captured","facts","cold","hot")));
        }
        var noRoute=new ArrayList<Row>();for(int eta:ETAS)noRoute.add(new Row(eta,true,ProbabilisticInferenceResult.Status.NO_ROUTE,List.of(),List.of(),Double.NaN,
                9,"actual-work","Optional.empty","RETURNED_NO_ROUTE",false,false,false));
        assertDoesNotThrow(()->requireSourceExecution(new CaseResult(base.id,base.source,noRoute,2,0,0,0,0,0,false,false,"captured","facts","cold","hot")));
    }

    @Test void failedSourceReceiptIsPublishedBeforeRequiredExecutionAssertion() throws Exception {
        String id="controlled-source-integration-failure";Path path=OUTPUT.resolve(id+".json");
        var prior=RESULTS.remove(id);byte[] priorBytes=Files.exists(path)?Files.readAllBytes(path):null;
        try {
            var rows=new ArrayList<Row>();for(int eta:ETAS)rows.add(unavailable(eta,eta==0?"PIPELINE_BEFORE_SOLVE_FAILURE_ILLEGAL_ARGUMENT":"UNVISITED_AFTER_WHOLE_CASE_FAILURE"));
            var failed=new CaseResult(id,SOURCE,rows,0,0,0,0,0,33L*1024*1024,false,false,"unavailable","unavailable","unavailable","unavailable");
            assertThrows(AssertionError.class,()->{publish(failed);requireSourceExecution(failed);});
            assertSame(failed,RESULTS.get(id));assertTrue(Files.exists(path));
            String json=Files.readString(path);assertTrue(json.contains("UNAVAILABLE_BEFORE_SOLVE"));assertTrue(json.contains("UNVISITED"));
            assertFalse(json.contains("\"wholeRouteGeometryVerdict\":\"PASS\""));
            var receipt=BIdentificationReceipt.parse(Files.readAllBytes(path));assertNull(receipt.get("profiles"));
            assertEquals(33L*1024*1024,((Number)receipt.get("peakAttemptBytes")).longValue());
            assertThrows(AssertionError.class,()->requireSourceExecution(RESULTS.get(id)));
        } finally {if(prior==null)RESULTS.remove(id);else RESULTS.put(id,prior);
            if(priorBytes==null)Files.deleteIfExists(path);else Files.write(path,priorBytes);}
    }

    @Test void coarseSourceFailureRetainsOnlyActuallyAvailableWorkAndSafeCause() throws Exception {
        String id="controlled-capture-failure";var prior=PIPELINE_EVIDENCE.remove(id);
        var ledger=newComparisonLedger();var progress=new PipelineProgress();
        try {
            try(var owner=ledger.rootOwner();var render=owner.reserve(32L*1024*1024);var metadata=owner.reserve(1024L*1024)) {
                progress.phase=SourcePhase.CAPTURE;progress.failed(new IllegalArgumentException("untrusted exception text never exported"));
            }
            progress.peak=ledger.peakBytes();progress.current=ledger.currentBytes();PIPELINE_EVIDENCE.put(id,progress);
            var failed=failedSourceResult(id,SOURCE,progress);
            assertEquals(33L*1024*1024,failed.peakBytes);assertEquals(0,failed.profileCount);assertEquals("unavailable",failed.sourceHash);
            var receipt=progress.receipt();assertEquals("CAPTURE",receipt.get("failedPhase"));assertEquals("ILLEGAL_ARGUMENT",receipt.get("safeCause"));
            assertEquals(0,receipt.get("solverInvocations"));assertEquals(0,receipt.get("solverReturns"));assertEquals(0L,receipt.get("remainingOwnedBytesAfterCase"));
            assertFalse(BIdentificationReceipt.canonical(receipt).contains("untrusted"));assertTrue(progress.capture.isEmpty());
            assertEquals("UNAVAILABLE_BEFORE_SOLVE",failed.rows.get(0).receipt().get("executionOutcome"));
            for(var row:failed.rows) {assertNull(row.receipt().get("status"));assertNull(row.receipt().get("wholeRouteGeometryPassed"));assertFalse(row.controlPassed);}
            assertThrows(AssertionError.class,()->requireSourceExecution(failed));
            var returned=controlledThinRow(0,SOURCE);progress.rows=List.of(returned);progress.phase=SourcePhase.FIELD;
            progress.failed(new IllegalStateException("not exported"));var later=failedSourceResult(id,SOURCE,progress);
            assertSame(returned,later.rows.get(0));assertEquals("PIPELINE_FAILURE_ILLEGAL_STATE",later.rows.get(1).explanation);
            for(int i=2;i<8;i++)assertEquals("UNVISITED",later.rows.get(i).receipt().get("executionOutcome"));
        } finally {if(prior==null)PIPELINE_EVIDENCE.remove(id);else PIPELINE_EVIDENCE.put(id,prior);}
    }

    private static void requireSourceExecution(CaseResult result) {
        assertEquals(ETAS.length,result.rows.size(),"required source schedule is incomplete: "+result.id);
        assertArrayEquals(ETAS,result.rows.stream().mapToInt(Row::eta).toArray());
        for(var row:result.rows) {
            assertTrue(row.actualSolve&&!row.work.equals("unavailable")
                    &&(row.status==ProbabilisticInferenceResult.Status.NO_ROUTE||!row.completion.equals("Optional.empty"))&&!stopsCase(row),
                    "required source execution unavailable: "+result.id+" eta="+row.eta+" cause="+row.explanation);
        }
        assertTrue(sourceCaseComplete(result),"terminal source pipeline failure: "+result.id);
        // NO_ROUTE and returned geometry FAIL remain honest model outcomes, never qualification.
    }

    @Test void terminalPipelineFailureAfterEightReturnsCannotCompleteOrQualifyTheCase() {
        String id="controlled-late-pipeline-failure";var prior=PIPELINE_EVIDENCE.remove(id);
        try {
            var progress=new PipelineProgress();var rows=new ArrayList<Row>();
            for(int eta:ETAS)rows.add(new Row(eta,true,ProbabilisticInferenceResult.Status.REVIEW_REQUIRED,SOURCE,List.of(0,0),.5,100,
                    "actual-returned-work","actual-completion","RETURNED_PATH",true,true,true));
            progress.rows=rows;progress.profiles=2;progress.snapshot="captured";progress.solverCalls=8;progress.solverReturns=8;
            progress.phase=SourcePhase.COMPLETE;progress.failed(new IllegalStateException("late cleanup failure"));
            PIPELINE_EVIDENCE.put(id,progress);var failed=failedSourceResult(id,SOURCE,progress);
            for(int i=0;i<8;i++){assertSame(rows.get(i),failed.rows.get(i));assertTrue(failed.rows.get(i).actualSolve);assertTrue(failed.rows.get(i).controlPassed);}
            assertEquals("COMPLETE",progress.receipt().get("failedPhase"));assertEquals(8,progress.receipt().get("solverReturns"));
            assertAll(()->assertThrows(AssertionError.class,()->requireSourceExecution(failed)),
                    ()->assertFalse(matrixComplete(Map.of(id,failed),1)),
                    ()->{for(int eta:ETAS)assertFalse(settingEligible(failed,eta),"late failed case cannot qualify eta="+eta);});
        } finally {if(prior==null)PIPELINE_EVIDENCE.remove(id);else PIPELINE_EVIDENCE.put(id,prior);}
    }

    @Test void oneThinActualPipelineSmoke() throws Exception {
        var result=result("thin-three-strands");verify(result);requireSourceExecution(result);
        var progress=PIPELINE_EVIDENCE.get(result.id);assertNotNull(progress);
        assertEquals(41,result.profileCount);assertEquals(8,progress.solverCalls);assertEquals(8,progress.solverReturns);
        assertEquals(SourcePhase.COMPLETE,progress.phase);assertEquals(0,progress.current);
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

    @ParameterizedTest(name="{0}") @MethodSource("allCases")
    void actualSourcePipelineProducesCompleteDeclaredEightSettingMatrix(String id) throws Exception {var result=result(id);verify(result);requireSourceExecution(result);}

    /** Expensive source integration is explicitly skipped outside the dedicated opt-in CI job. */
    @EnabledIfEnvironmentVariable(named="V022_RUN_SWITCHBACK_CONTROLS",matches="true")
    @ParameterizedTest(name="switchback-{0}") @MethodSource("switchbackCases")
    void switchbackSourcePipelineProducesCompleteEightSettingMatrix(String id) throws Exception {
        if(RESULTS.keySet().stream().noneMatch(SWITCHBACK_CASES::contains))BThinSwitchbackFixtures.exportNative(Path.of("build/gap-switchback-matrix"));
        var result=result(id);verify(result);requireSourceExecution(result);
        assertEquals(8,PIPELINE_EVIDENCE.get(id).solverCalls);assertEquals(8,PIPELINE_EVIDENCE.get(id).solverReturns);
        assertEquals(0,PIPELINE_EVIDENCE.get(id).current);
    }

    /** Separate additive opt-in; never reruns the previous two historical switchbacks. */
    @EnabledIfEnvironmentVariable(named="V022_RUN_SAME_TRAIL_CONTROLS",matches="true")
    @ParameterizedTest(name="same-trail-{0}") @MethodSource("sameTrailCases")
    void sameTrailSourcePipelineProducesCompleteEightSettingMatrix(String id) throws Exception {
        if(RESULTS.keySet().stream().noneMatch(SAME_TRAIL_CASES::contains))BSameTrailRecordingFixtures.exportNative(Path.of("build/gap-same-trail-matrix"));
        var result=result(id);verify(result);requireSourceExecution(result);
        assertEquals(8,PIPELINE_EVIDENCE.get(id).solverCalls);assertEquals(8,PIPELINE_EVIDENCE.get(id).solverReturns);assertEquals(0,PIPELINE_EVIDENCE.get(id).current);
    }

    /** One additive visualization case, opt-in only; prior suites remain untouched. */
    @EnabledIfEnvironmentVariable(named="V022_RUN_SEPARATED_SAME_TRAIL_CONTROL",matches="true")
    @Test void separatedSameTrailSourcePipelineProducesCompleteEightSettingMatrix() throws Exception {
        String id=BSeparatedSameTrailFixtures.ID;BSeparatedSameTrailFixtures.exportNative(Path.of("build/gap-separated-same-trail-matrix"));
        var result=result(id);verify(result);requireSourceExecution(result);
        assertEquals(8,PIPELINE_EVIDENCE.get(id).solverCalls);assertEquals(8,PIPELINE_EVIDENCE.get(id).solverReturns);assertEquals(0,PIPELINE_EVIDENCE.get(id).current);
    }

    private static void verify(CaseResult result) {
        assertEquals(8,result.rows.size(),"all fixed settings must be recorded");
        assertArrayEquals(ETAS,result.rows.stream().mapToInt(Row::eta).toArray());
        for(var row:result.rows) {
            assertTrue(row.transitions<=SOLVE_BUDGETS.maximumTransitions());
            if(!row.actualSolve) {
                assertTrue(row.points.isEmpty());assertTrue(row.states.isEmpty());assertEquals(0,row.transitions);
                assertEquals("unavailable",row.work);assertEquals("Optional.empty",row.completion);assertFalse(row.controlPassed);
            } else if(completed(row)) {
                assertEquals(result.source.get(0),row.points.get(0));assertEquals(result.source.get(result.source.size()-1),row.points.get(row.points.size()-1));
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
        return !row.actualSolve||row.status==ProbabilisticInferenceResult.Status.RESOURCE_LIMIT||row.status==ProbabilisticInferenceResult.Status.NUMERIC_FAILURE
                ||row.explanation.startsWith("THIN_ORACLE_TERMINAL_");
    }
    private static Map<String,Object> receiptForCase(String id,Row row) {
        var receipt=row.receipt();receipt.put("requiredPredicate",id.equals("censored-flank")?"CENSORED_UNAVAILABLE_ZERO_NO_NEW_BARRIER":
                id.contains("twenty-five-representation")?"ORIGINAL_FULL_DOMAIN_REPRESENTATION":
                id.equals("invalid-unknown-gap")||id.equals("unknown-detour")?"UNSUPPORTED_OR_UNKNOWN_ROUTE_NOT_ELIGIBLE":"WHOLE_ROUTE_GEOMETRY_AND_OBSERVED_SUPPORT");
        receipt.put("wholeRouteGeometryVerdict",!completed(row)?"UNAVAILABLE_NO_RETURNED_PATH":id.equals("censored-flank")?"UNAVAILABLE_CENSORED_POSITION":
                id.contains("twenty-five-representation")?"UNAVAILABLE_REPRESENTATION_ONLY":row.geometryPassed?"PASS":"FAIL");
        if(id.equals("censored-flank")||id.contains("twenty-five-representation"))receipt.put("wholeRouteGeometryPassed",null);
        if(isThin(id)||isCurvedControl(id)) {
            String oracleId=isCurvedControl(id)?BThinSwitchbackAcceptance.ORACLE_ID:BThinStrandAcceptance.ORACLE_ID;
            receipt.put("requiredPredicate",oracleId+"_AND_OBSERVED_SUPPORT");
            var proof=THIN_ORACLE_EVIDENCE.getOrDefault(id,Map.of()).get(row.eta);
            boolean bound=proof!=null&&proof.row==row;
            var oracle=bound?proof.receipt:Map.<String,Object>of("oracle",oracleId,"invoked",false,"availability",
                    completed(row)?"UNAVAILABLE_NOT_BOUND_TO_RETURNED_ROW":"UNAVAILABLE_NO_RETURNED_PATH");
            receipt.put("truthOracle",oracle);
            if(completed(row)&&(!bound||!"AVAILABLE".equals(oracle.get("availability")))) {
                receipt.put("wholeRouteGeometryPassed",null);receipt.put("wholeRouteGeometryVerdict","UNAVAILABLE_THIN_ORACLE");
                receipt.put("requiredControlPassed",false);
            }
        }
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
        return failure instanceof AttemptMemoryLedger.ResourceLimitException||failure instanceof RasterEvidenceCapture.ResourceLimitException?"RESOURCE_LIMIT":
                failure instanceof java.util.concurrent.CancellationException?"CANCELLED":
                failure instanceof IllegalArgumentException?"ILLEGAL_ARGUMENT":failure instanceof IllegalStateException?"ILLEGAL_STATE":
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
            var progress=PIPELINE_EVIDENCE.get(id);if(progress==null)throw failure;
            progress.failed(failure);result=failedSourceResult(id,source(id),progress);
        }
        publish(result);return result;
    }
    private static CaseResult failedSourceResult(String id,List<MetricPoint> source,PipelineProgress progress) {
        var rows=new ArrayList<>(progress.rows);boolean first=true;
        while(rows.size()<ETAS.length) {
            int eta=ETAS[rows.size()];rows.add(unavailable(eta,first?(eta==0?"PIPELINE_BEFORE_SOLVE_FAILURE_":"PIPELINE_FAILURE_")+progress.cause:"UNVISITED_AFTER_WHOLE_CASE_FAILURE"));first=false;
        }
        return new CaseResult(id,source,rows,progress.profiles,progress.modes,progress.censored,0,0,progress.peak,false,false,
                progress.snapshot,"unavailable","unavailable","unavailable");
    }
    private static void publish(CaseResult result) throws Exception {
        String id=result.id;RESULTS.put(id,result);
        var output=new LinkedHashMap<String,Object>();output.put("schema","observed-gap-matched-optional-overflow-result-v2");output.put("case",id);
        output.put("sourceCohort",isSeparatedSameTrail(id)?"ADDITIONAL_SEPARATED_SAME_TRAIL_ONE":isSameTrail(id)?"ADDITIONAL_SAME_TRAIL_RECORDINGS_TWO":isSwitchback(id)?"ADDITIONAL_FROZEN_SWITCHBACK_TWO":isThin(id)?"ADDITIONAL_FROZEN_THIN_SEVEN":"ORIGINAL_FROZEN_TWENTY");
        output.put("comparisonPolicy",ProbabilisticOptionalOverflowAdmission.POLICY_ID);
        var progress=PIPELINE_EVIDENCE.get(id);output.put("sourcePipeline",progress==null?Map.of("availability","UNAVAILABLE"):progress.receipt());
        output.put("sourceCaseExecutionComplete",sourceCaseComplete(result));
        if(isThin(id))output.put("declaredNativeInput",Map.of("status","DECLARED_FROZEN_RECIPE_NOT_CAPTURE_RESULT","width",81,"height",81,
                "firstPixelCenterMeters",List.of(-20,0),"nativePitchMeters",1,"sourceSpanMeters",60,"profileStepMeters",1.5,"decisionRadiusMeters",25));
        output.put("admissionEvidence",ADMISSION_EVIDENCE.getOrDefault(id,Map.of("availability","UNAVAILABLE")));
        output.put("source",result.source.stream().map(p->List.of(p.xMeters(),p.yMeters())).toList());output.put("snapshotHash",result.sourceHash);
        boolean factual=!result.factsHash.equals("unavailable");
        boolean profiled=result.profileCount>0;
        output.put("factsAndLatticeHash",factual?result.factsHash:null);output.put("profiles",profiled?result.profileCount:null);output.put("modes",profiled?result.modeCount:null);
        output.put("censoredModes",profiled?result.censoredModes:null);output.put("originalPairInventory",factual?result.pairs:null);output.put("tableBytes",factual?result.tableBytes:null);
        output.put("peakAttemptBytes",result.peakBytes>0?result.peakBytes:null);
        output.put("selectedAdmissionCoarsened",ADMISSION_EVIDENCE.getOrDefault(id,Map.of()).get("coarsened"));
        output.put("fullTwentyFiveMetreDomain",factual?result.fullDomain:null);output.put("coldBuildCounters",result.buildCounters);
        output.put("sourceControlEvidence",CONTROL_EVIDENCE.getOrDefault(id,Map.of("availability","UNAVAILABLE")));
        output.put("postSolveCounters",result.postSolveCounters);output.put("rows",result.rows.stream().map(r->receiptForCase(id,r)).toList());
        write(id+".json",output);System.out.println("MATCHED "+id+" controlByEta="+result.rows.stream().map(r->r.eta+":"+r.controlPassed).toList()
                +" modes="+result.modeCount+" censored="+result.censoredModes+" pairs="+result.pairs+" peak="+result.peakBytes);
    }

    private static CaseResult run(String id) throws Exception {
        var ledger=newComparisonLedger();var rows=new ArrayList<Row>();var source=source(id);
        var progress=new PipelineProgress();progress.rows=rows;PIPELINE_EVIDENCE.put(id,progress);
        try(var owner=ledger.rootOwner();var render=owner.reserve(32L*1024*1024);var metadata=owner.reserve(1024L*1024)) {
            progress.phase=SourcePhase.CAPTURE;
            var evidence=capture(id,source);var factory=new ProbabilisticProfileFactory();
            progress.snapshot=evidence.canonicalHash();var provenance=evidence.resampling();
            progress.capture=Map.of("inputWidth",provenance.inputWidth(),"inputHeight",provenance.inputHeight(),
                    "outputWidth",provenance.outputWidth(),"outputHeight",provenance.outputHeight(),
                    "outputFirstPixelCenterMeters",List.of(evidence.transform().pixelCenterToMetric(0,0).xMeters(),evidence.transform().pixelCenterToMetric(0,0).yMeters()),
                    "nativePitchMeters",evidence.resolution().nativePitchMeters().orElseThrow(),"outputPitchMeters",evidence.resolution().outputRasterPitchMeters());
            progress.phase=SourcePhase.PROFILES;
            var raw=factory.create(source,factory.profileChainage(source,1.5),25,true,evidence,evidence.fields().get("scalar"),
                    PARAMETERS,CancellationProbe.NONE,true,owner);
            progress.profiles=raw.size();progress.modes=raw.stream().mapToInt(p->p.modes().size()).sum();progress.censored=raw.stream().mapToInt(p->p.censoredModes().size()).sum();
            progress.phase=SourcePhase.FACTS;
            var facts=new BIdentificationFactsExtractor().extract(evidence,raw,CancellationProbe.NONE,owner);
            assertEquals(expectedProfiles(id),raw.size());for(int i=0;i<raw.size();i++)assertSame(raw.get(i),facts.get(i).baseline());
            int modes=raw.stream().mapToInt(p->p.modes().size()).sum(),censored=raw.stream().mapToInt(p->p.censoredModes().size()).sum();
            progress.phase=SourcePhase.PROPOSALS;
            var proposals=ProbabilisticContinuationProposals.build(raw,CancellationProbe.NONE,owner,PARAMETERS.localization());
            progress.phase=SourcePhase.ADMISSION;
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
            progress.phase=SourcePhase.ADAPT;
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
                progress.phase=SourcePhase.FIELD;
                field=BGapField.build(input,CancellationProbe.NONE,owner);
                if(isThin(id)){assertEquals(81,field.grid.width);assertEquals(81,field.grid.height);assertEquals(-20,field.grid.ox);assertEquals(0,field.grid.oy);}
                long cells=0,unavailable=0,nonzero=0;var statuses=new TreeMap<String,Long>();
                for(int y=0;y<field.grid.height-1;y++)for(int x=0;x<field.grid.width-1;x++) {
                    var status=field.status(x,y);cells++;if(status.ordinal()>=2)unavailable++;if(field.value(x,y)!=0)nonzero++;
                    statuses.merge(status.name(),1L,Long::sum);
                }
                var evidenceReceipt=new LinkedHashMap<String,Object>();evidenceReceipt.put("fieldCellStatuses",statuses);
                evidenceReceipt.put("fieldCellsInspected",cells);evidenceReceipt.put("nonzeroFieldCells",nonzero);
                evidenceReceipt.put("censoredModeCount",censored);CONTROL_EVIDENCE.put(id,evidenceReceipt);
                progress.phase=SourcePhase.TABLE;
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
                progress.failed(failure);
                if(field!=null){cold=field.counters().toString();hot=cold;}
                boolean first=true;
                while(rows.size()<ETAS.length) {int eta=ETAS[rows.size()];rows.add(unavailable(eta,first?"GAP_CONSTRUCTION_FAILURE_"+safeFailure(failure):"UNVISITED_AFTER_WHOLE_CASE_FAILURE"));first=false;}
            } finally {if(field!=null)field.close();}
            if(progress.failedPhase==null&&rows.stream().noneMatch(V022BGapMatchedControlsTest::stopsCase))progress.phase=SourcePhase.COMPLETE;
            return new CaseResult(id,source,List.copyOf(rows),raw.size(),modes,censored,pairs,tableBytes,owner.peakBytes(),admission.coarsened(),fullDomain,
                    evidence.canonicalHash(),factsHash,cold,hot);
            } finally {updateAdmissionWork(ADMISSION_EVIDENCE.get(id),admissionWork);}
            }
        } finally {progress.peak=ledger.peakBytes();progress.current=ledger.currentBytes();assertEquals(0,ledger.currentBytes());}
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
            var progress=PIPELINE_EVIDENCE.get(id);
            if(progress!=null){progress.phase=eta==0?SourcePhase.BASELINE_SOLVE:SourcePhase.ENABLED_SOLVE;progress.solverCalls++;}
            ProbabilisticInferenceResult result;
            try {result=potential==null?solver.solve(profiles,PARAMETERS,SOLVE_BUDGETS,evidence.decisionRegion(),CancellationProbe.NONE,resultOwner,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2):
                    solver.solve(profiles,PARAMETERS,SOLVE_BUDGETS,evidence.decisionRegion(),CancellationProbe.NONE,resultOwner,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2,potential);
            } catch(RuntimeException failure) {
                if(progress!=null)progress.failed(failure);
                return new Row(eta,true,ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,List.of(),List.of(),Double.NaN,0,
                        "unavailable","Optional.empty","THROWN_SOLVER_FAILURE_"+safeFailure(failure),false,false,false);
            }
            if(progress!=null) {
                progress.solverReturns++;
                if(result.status()==ProbabilisticInferenceResult.Status.RESOURCE_LIMIT||result.status()==ProbabilisticInferenceResult.Status.NUMERIC_FAILURE) {
                    progress.failedPhase=progress.phase;progress.cause="RETURNED_"+result.status().name();
                }
            }
            var path=result.mapPath();var points=path.map(ProbabilisticPath::points).orElse(List.of());
            var states=new ArrayList<Integer>();if(path.isPresent())for(int s:path.orElseThrow().stateIndices())states.add(s);
            boolean anchors=!points.isEmpty()&&points.get(0).equals(source.get(0))&&points.get(points.size()-1).equals(source.get(source.size()-1));
            if(isThin(id)||isCurvedControl(id)) {
                var returned=new Row(eta,true,result.status(),List.copyOf(points),List.copyOf(states),path.map(ProbabilisticPath::energy).orElse(Double.NaN),
                        result.evaluatedTransitions(),result.workAccounting().toString(),result.completion().toString(),result.explanation(),
                        false,anchors&&fullyObserved(evidence,points),false);
                var checked=isCurvedControl(id)?assessSwitchbackReturnedPath(id,returned,evidence.resolution().nativePitchMeters().orElse(Double.NaN),CancellationProbe.NONE,resultOwner):
                        assessThinReturnedPath(id,returned,evidence.resolution().nativePitchMeters().orElse(Double.NaN),CancellationProbe.NONE,resultOwner);
                if(progress!=null&&checked.explanation.startsWith("THIN_ORACLE_TERMINAL_")){progress.failedPhase=progress.phase;progress.cause="TRUTH_ORACLE_UNAVAILABLE";}
                return checked;
            }
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
    private static EvidenceSnapshot capture(String id,List<MetricPoint> source) {return capture(id,source,512L*1024*1024);}
    private static EvidenceSnapshot capture(String id,List<MetricPoint> source,long captureBytes) {return capture(id,source,captureBytes,CancellationProbe.NONE);}
    private static EvidenceSnapshot capture(String id,List<MetricPoint> source,long captureBytes,CancellationProbe probe) {
        if(isSameTrail(id)||isSeparatedSameTrail(id)) {
            var input=sameTrailInput(id);if(!input.source.equals(source))throw new IllegalArgumentException("Declared same-trail source changed");
            return captureScalarOnGrid(id,input,switchbackOutputGrid(input),probe);
        }
        if(isSwitchback(id)) {
            var input=switchbackInput(id);
            if(!input.source.equals(source))throw new IllegalArgumentException("Declared switchback source identity changed");
            return captureScalarOnGrid(id,input,switchbackOutputGrid(input),probe);
        }
        if(isThin(id)) {
            var input=thinInput(id);
            if(!input.source.equals(source))throw new IllegalArgumentException("Declared thin source identity changed");
            return captureScalar(id,input,probe);
        }
        var input=originalScalarInput(id,source);int w=input.width,h=input.height;double[] scalar=input.scalar;boolean[] valid=input.validity;
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var inverse=SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(20,0),1,0,0,1);
        boolean fine=id.equals("half-pitch-alias")||id.equals("quarter-phase-alias");double pitch=fine?.5:1,phase=id.equals("quarter-phase-alias")?.25:0;
        var grid=new MetricRasterGrid(frame,p(-20+phase,phase),1,0,0,1,pitch,fine?161:81,fine?161:81);
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                "matched-declared-scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        return new RasterEvidenceCapture(captureBytes).captureWithMetricScalarSource("matched-"+id,new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB),valid,scalar,
                source.stream().map(frame::toGeographic).toList(),source,inverse,grid,EvidenceResolution.nativeSource(1,1),25,"matched-public-declared-"+id,
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",lineage,probe);
    }
    /** No truth centerline, observed-strand mask or expected route kind can cross this typed boundary. */
    private static EvidenceSnapshot captureScalar(String id,ScalarInput input) {return captureScalar(id,input,CancellationProbe.NONE);}
    private static EvidenceSnapshot captureScalar(String id,ScalarInput input,CancellationProbe probe) {
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        if(input.width!=81||input.height!=81||input.pitchMeters!=1||!input.origin.equals(p(-20,0))||!input.source.equals(THIN_SOURCE))
            throw new IllegalArgumentException("Fixed thin native input changed");
        // Square endcaps extend the hard decision corridor to x[-33,77]. Only the output
        // enclosure grows; the acquired input, inverse transform and native analysis mesh do not.
        return captureScalarOnGrid(id,input,new MetricRasterGrid(frame,p(-33,0),1,0,0,1,1,111,81),probe);
    }
    /** Output enclosure only: original native grid/phase/values and unknown exterior stay intact. */
    private static MetricRasterGrid switchbackOutputGrid(ScalarInput input) {
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var region=MetricCorridorRegion.aroundPolyline(input.source,25);
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
        for(var polygon:region.polygons())for(var point:polygon){minX=Math.min(minX,point.xMeters());maxX=Math.max(maxX,point.xMeters());
            minY=Math.min(minY,point.yMeters());maxY=Math.max(maxY,point.yMeters());}
        // Every production-resampled anchor lies on the source polyline; this also
        // encloses its full +/-25m normal window, including bends between source vertices.
        for(var point:input.source){minX=Math.min(minX,point.xMeters()-25);maxX=Math.max(maxX,point.xMeters()+25);
            minY=Math.min(minY,point.yMeters()-25);maxY=Math.max(maxY,point.yMeters()+25);}
        double ox=input.origin.xMeters()+StrictMath.floor((minX-input.origin.xMeters())/input.pitchMeters)*input.pitchMeters;
        double oy=input.origin.yMeters()+StrictMath.floor((minY-input.origin.yMeters())/input.pitchMeters)*input.pitchMeters;
        int width=Math.toIntExact((long)StrictMath.ceil((maxX-ox)/input.pitchMeters)+1);
        int height=Math.toIntExact((long)StrictMath.ceil((maxY-oy)/input.pitchMeters)+1);
        if((long)width*height>191401||(long)(width-1)*(height-1)>190404)throw new IllegalArgumentException("Switchback output exceeds unchanged source proof");
        return new MetricRasterGrid(frame,p(ox,oy),1,0,0,1,input.pitchMeters,width,height);
    }

    private static EvidenceSnapshot captureScalarOnGrid(String id,ScalarInput input,MetricRasterGrid grid) {return captureScalarOnGrid(id,input,grid,CancellationProbe.NONE);}
    private static EvidenceSnapshot captureScalarOnGrid(String id,ScalarInput input,MetricRasterGrid grid,CancellationProbe probe) {
        var frame=grid.coordinateFrame();
        double pitch=input.pitchMeters;
        var inverse=SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(-input.origin.xMeters()/pitch,-input.origin.yMeters()/pitch),
                1/pitch,0,0,1/pitch);
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                "matched-declared-scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        return new RasterEvidenceCapture(32L*1024*1024).captureWithMetricScalarSource("matched-"+id,new BufferedImage(input.width,input.height,BufferedImage.TYPE_INT_ARGB),
                input.validity(),input.scalar(),input.source.stream().map(frame::toGeographic).toList(),input.source,inverse,grid,
                EvidenceResolution.nativeSource(pitch,pitch),25,"matched-public-declared-"+id,EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                "scalar",lineage,probe);
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
    private static boolean isThin(String id){return THIN_CASES.contains(id);}
    private static boolean isSwitchback(String id){return SWITCHBACK_CASES.contains(id);}
    private static boolean isSameTrail(String id){return SAME_TRAIL_CASES.contains(id);}
    private static boolean isSeparatedSameTrail(String id){return SEPARATED_SAME_TRAIL_CASES.contains(id);}
    private static boolean isCurvedControl(String id){return isSwitchback(id)||isSameTrail(id)||isSeparatedSameTrail(id);}
    private static int expectedProfiles(String id){return isCurvedControl(id)?new ProbabilisticProfileFactory().sourceSamplingGeometry(BThinSwitchbackFixtures.source(),1.5).points().size():isThin(id)?41:17;}
    // Source lookup must not initialize any raster/truth fixture before the attempt's render reservation.
    private static List<MetricPoint> source(String id){return isCurvedControl(id)?BThinSwitchbackFixtures.source():isThin(id)?THIN_SOURCE:id.equals("reverse-original-mesh")?List.of(SOURCE.get(1),SOURCE.get(0)):SOURCE;}
    private static double center(double x,double width,double height){return 37+height*Math.max(0,1-Math.abs(x-20)/(width/2));}
    private static double gauss(double distance,double sigma){return StrictMath.exp(-.5*(distance/sigma)*(distance/sigma));}
    private static MetricPoint p(double x,double y){return new MetricPoint(x,y);}
    private static void write(String name,Object value) throws Exception {
        if(!name.matches("[a-z0-9-]+\\.json"))throw new IllegalArgumentException("Fixed synthetic output name required");
        byte[] bytes=BIdentificationReceipt.canonical(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(bytes.length<=65536);Files.createDirectories(OUTPUT);Files.write(OUTPUT.resolve(name),bytes);
    }
    private static boolean matrixComplete(Map<String,CaseResult> results,int requiredCases) {
        return results.size()==requiredCases&&results.values().stream().allMatch(V022BGapMatchedControlsTest::sourceCaseComplete);
    }
    private static boolean sourceCaseComplete(CaseResult result) {
        var progress=PIPELINE_EVIDENCE.get(result.id);
        if(progress!=null&&progress.failedPhase!=null||result.rows.size()!=ETAS.length)return false;
        for(int i=0;i<ETAS.length;i++) {
            var row=result.rows.get(i);
            if(row.eta!=ETAS[i]||!row.actualSolve||row.work.equals("unavailable")||stopsCase(row)
                    ||row.status!=ProbabilisticInferenceResult.Status.NO_ROUTE&&row.completion.equals("Optional.empty"))return false;
        }
        return true;
    }
    private static boolean settingEligible(CaseResult result,int eta) {
        return result!=null&&sourceCaseComplete(result)&&result.rows.stream().anyMatch(r->r.eta==eta&&r.controlPassed);
    }
    @AfterAll static void report() throws Exception {
        var summary=new LinkedHashMap<String,Object>();summary.put("status","SOURCE_CONTROL_MATRIX_NOT_MODEL_QUALIFICATION");
        boolean switchbackRun="true".equals(System.getenv("V022_RUN_SWITCHBACK_CONTROLS"));
        boolean sameTrailRun="true".equals(System.getenv("V022_RUN_SAME_TRAIL_CONTROLS"));
        boolean separatedRun="true".equals(System.getenv("V022_RUN_SEPARATED_SAME_TRAIL_CONTROL"));
        var expected=requiredCases(switchbackRun,sameTrailRun,separatedRun);
        summary.put("expectedCases",expected);summary.put("originalTwenty",CASES);summary.put("additionalThinSeven",THIN_CASES);
        summary.put("additionalSwitchbacks",SWITCHBACK_CASES);summary.put("switchbackExecutionEnabled",switchbackRun);
        summary.put("additionalSameTrailRecordings",SAME_TRAIL_CASES);summary.put("sameTrailExecutionEnabled",sameTrailRun);
        summary.put("additionalSeparatedSameTrail",SEPARATED_SAME_TRAIL_CASES);summary.put("separatedSameTrailExecutionEnabled",separatedRun);
        summary.put("completedCases",new ArrayList<>(RESULTS.keySet()));summary.put("complete",matrixComplete(RESULTS,expected.size()));
        var settings=new ArrayList<Map<String,Object>>();
        for(int eta:ETAS) {
            var failed=new ArrayList<String>();for(var id:expected) {
                var result=RESULTS.get(id);
                if(!settingEligible(result,eta))failed.add(id);
            }
            settings.add(Map.of("eta",eta,"failedOrUnavailableRequiredControls",failed,"allControlsPassed",failed.isEmpty()));
        }
        summary.put("settings",settings);summary.put("progressionAuthority","HOLD_CONTROLLER_GATE_UNRESOLVED_NO_SELECTION");write(separatedRun?"separated-same-trail-summary.json":sameTrailRun?"same-trail-summary.json":switchbackRun?"switchback-summary.json":"summary.json",summary);
    }
}
