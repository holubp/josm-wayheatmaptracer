package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentTileSourcePlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;

class RefinementPipelineTest {
    @Test void scalarPreparationDoesNotReserveUncreatedAssembledCandidates() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);
        var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var ledger=new AttemptMemoryLedger(4L*1024*1024);
        var entered=new java.util.concurrent.atomic.AtomicBoolean();
        try(var owner=ledger.rootOwner()) {
            var source=new RefinementPipeline.Sources(){
                public void requireCurrent(){}
                public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest request,CancellationProbe probe){
                    entered.set(true);
                    assertEquals(1_048_576L,ledger.currentBytes(),"Only pipeline metadata is live during scalar preparation");
                    throw new RefinementPipeline.SourceUnavailable();
                }
            };
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),
                    new RefinementPipeline.Adapters(source,(t,p,e,b,c,o)->{throw new AssertionError("No source");},owner),
                    RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertTrue(entered.get());
            assertEquals(Outcome.NO_USABLE_RESULT,result.outcome());
            assertTrue(result.candidates().isEmpty());
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void completedNativeRetentionAdmissionHasItsOwnTypedBoundaryAndActualWork() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var ledger=new AttemptMemoryLedger(32L*1024*1024);
        try(var owner=ledger.rootOwner();var observed=PipelineAdmissionObserver.openForTesting(value->{})) {
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),new RefinementPipeline.Adapters(sources(parent),RefinementPipeline.productionRunner(),owner),
                RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());assertTrue(result.candidates().isEmpty());
            assertTrue(result.attempts().stream().flatMap(a->a.run().stream()).anyMatch(run->run.outcome()==Outcome.COMPLETE&&run.actualWorkAvailability().isPresent()));
            assertTrue(result.actualWork().orElseThrow().transitions()>0);
            assertTrue(result.attempts().stream().anyMatch(a->a.kind()==RefinementPipeline.AttemptKind.UNATTEMPTED&&a.nativeCallAvailability().orElse(-1)==0));
            var refusal=observed.latest(ticket).orElseThrow(()->new AssertionError("Actual completed-native retention refusal needs its own admission receipt"));
            assertEquals("NATIVE_RETENTION",refusal.gate().name());assertEquals(64L*1024*1024,refusal.requestedBytes().orElseThrow());
            assertTrue(refusal.requestedBytes().orElseThrow()>refusal.limitBytes().orElseThrow()-refusal.currentBytes().orElseThrow());
            assertEquals(32L*1024*1024,refusal.limitBytes().orElseThrow());assertTrue(refusal.currentEvidence().isPresent());
            assertTrue(owner.currentBytes()>0,"Old native/result owners remain charged on admission refusal");
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void cancellationFromSourceCheckpointRemainsCancellationRatherThanStale() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var source=new RefinementPipeline.Sources(){
            public void requireCurrent(){throw new java.util.concurrent.CancellationException();}
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest request,CancellationProbe probe){throw new AssertionError("Cancelled source must not acquire evidence");}
        };
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,false),new RefinementPipeline.Adapters(source,
                (t,p,e,b,c,o)->{throw new AssertionError("Cancelled source must not enter native inference");},owner),
                RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertEquals(Outcome.CANCELLED,result.outcome());assertTrue(result.candidates().isEmpty());
            assertTrue(result.attempts().stream().allMatch(a->a.kind()==RefinementPipeline.AttemptKind.UNATTEMPTED&&a.nativeCallAvailability().orElse(-1)==0));
            assertEquals(0,owner.currentBytes());
        }
    }
    @Test void assessmentWorkspaceStartsAfterNativeAndRefusesBeforeComposition() throws Exception {
        var root=RefinementRepairContractTest.largeRoot();
        var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
        var ticket=ticket(parent,TrackerMode.CORRIDOR_AWARE);var sourcePlan=plan(ticket,parent,true);
        var vertexMethod=RefinementPipeline.class.getDeclaredMethod("maximumAssembledVertices",ParentTraceReference.class,int.class);vertexMethod.setAccessible(true);
        long vertices=(long)vertexMethod.invoke(null,parent,new RefinementIntervalPlanner().plan(parent).size());
        var scratchMethod=RefinementPipeline.class.getDeclaredMethod("assessmentScratchBytes",ParentTraceReference.class,EvidenceSnapshot.class,long.class);scratchMethod.setAccessible(true);
        long scratch=(long)scratchMethod.invoke(null,parent,root.evidence(),vertices);
        long retention=1_048_576L;
        long candidateRetention=vertices*2048L;
        var afterNative=new AtomicLong(retention);var assessments=new java.util.concurrent.atomic.AtomicInteger();
        var production=RefinementPipeline.productionRunner();
        RefinementPipeline.Result baseline;
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            RefinementPipeline.NativeRunner observed=(t,p,e,b,c,o)->{
                assertEquals(afterNative.get(),owner.currentBytes(),"Assessment workspace must not overlap native entry");
                var run=production.run(t,p,e,b,c,o);afterNative.set(owner.currentBytes());return run;
            };
            CancellationProbe probe=()->{
                if(StackWalker.getInstance().walk(frames->frames.anyMatch(f->f.getClassName().equals(RefinementPipeline.class.getName())&&f.getMethodName().equals("assess")))) {
                    assertEquals(afterNative.get()+candidateRetention+scratch,owner.currentBytes(),"One candidate and its assessment workspace are admitted before composition");assessments.incrementAndGet();
                }
                return false;
            };
            baseline=new RefinementPipeline().run(ticket,parent,sourcePlan,new RefinementPipeline.Adapters(sources(parent),observed,owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),probe);
            assertNotEquals(Outcome.RESOURCE_LIMIT,baseline.outcome());assertTrue(assessments.get()>0);assertTrue(baseline.actualWork().orElseThrow().transitions()>0);
        }
        long separatedCap=afterNative.get()+candidateRetention+scratch;
        for(boolean oneByteShort:new boolean[]{false,true}) {
            var ledger=new AttemptMemoryLedger(separatedCap-(oneByteShort?1:0));var enteredAssessment=new java.util.concurrent.atomic.AtomicBoolean();
            try(var owner=ledger.rootOwner();var admissions=PipelineAdmissionObserver.openForTesting(value->{})) {
                CancellationProbe probe=()->{
                    if(StackWalker.getInstance().walk(frames->frames.anyMatch(f->f.getClassName().equals(RefinementPipeline.class.getName())&&f.getMethodName().equals("assess"))))enteredAssessment.set(true);
                    return false;
                };
                // Both baseline and exact-fit control retain the same real native working
                // owner: this test isolates assessment admission, not post-frame settlement.
                var result=new RefinementPipeline().run(ticket,parent,sourcePlan,new RefinementPipeline.Adapters(sources(parent),production::run,owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),probe);
                assertEquals(baseline.actualWork(),result.actualWork(),"Completed native receipt survives assessment admission refusal");
                if(oneByteShort) {
                    assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());assertFalse(enteredAssessment.get());
                    var refusal=admissions.latest(ticket).orElseThrow();assertEquals(PipelineAdmissionObserver.Gate.ASSESSMENT_SCRATCH,refusal.gate());
                    assertEquals(scratch,refusal.requestedBytes().orElseThrow());assertEquals(afterNative.get()+candidateRetention,refusal.currentBytes().orElseThrow());
                } else {assertEquals(baseline.outcome(),result.outcome());assertTrue(enteredAssessment.get());assertEquals(baseline.candidates().size(),result.candidates().size());}
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void finalCompositionRetainsFrozenInteriorWhileWholeParentInferenceRemainsBlocked() {
        var parent=RefinementIntervalPlannerTest.capturedTopologyParent(List.of(new OccurrenceRange(0,18)),Set.of(),Set.of());
        assertThrows(ParentTraceReference.UnavailableException.class,parent::requireRefinementGeometryAuthority);
        assertThrows(ParentTraceReference.UnavailableException.class,()->parent.request(TrackerMode.PROBABILISTIC,parent.root().evidence(),parent.root().request().budgets(),1));
        var composer=new ParentBackedComposer();
        var composed=composer.compose("frozen-safe-composition",parent,List.of(ParentBackedComposerTest.choice(parent,new OccurrenceRange(0,4),50.2)));
        var assessed=composer.assess(parent,composed,parent.root().evidence(),"native",CancellationProbe.NONE);
        assertNotEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,assessed.quality().disposition(),assessed.quality().findings().toString());
        var adapter=new ModernSingleWayEditPlanAdapter();
        var plan=assertDoesNotThrow(()->adapter.adaptRefinement(parent,assessed,parent.root().evidence()));
        var way=(DetachedWay)plan.before().primitives().get(plan.selectedWayKey());
        for(var range:parent.root().frozenGeometryProof().orElseThrow().ranges())for(int i=range.firstIndex();i<=range.lastIndex();i++) {
            var key=way.nodeKeys().get(i);assertEquals(plan.before().primitives().get(key),plan.after().primitives().get(key));
        }
        // Keep frozen endpoints exact and change only its interior. Even a forged passing
        // quality record must not let that curve or its occurrence assignments reach a plan.
        var points=new ArrayList<>(composed.points());int changed=5;
        var old=points.get(changed);points.set(changed,new MetricPoint(old.xMeters(),old.yMeters()+1));
        var assignments=new LinkedHashMap<>(composed.assignments());assignments.put(composed.pointIds().get(changed),points.get(changed));
        var corrupt=new ParentBackedComposer.Composed(composed.id(),points,composed.pointIds(),assignments,composed.correspondence(),composed.spans());
        var forged=new ParentBackedComposer.Assessed(corrupt,assessed.quality(),List.of());
        assertThrows(IllegalArgumentException.class,()->adapter.adaptRefinement(parent,forged,parent.root().evidence()));
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void sourcePreparationDeadlineKeepsResourceOutcomeAndPriorNativeCandidates(int failingSource) {
        var parent=ParentBackedComposerTest.copyParent();var ticket=ticket(parent,TrackerMode.PROBABILISTIC);var plan=plan(ticket,parent,true);
        var clock=new AtomicLong();var budget=new RefinementPipeline.OperationBudget(parent.root().request().budgets(),clock::get,1000,200);
        var ordinal=new java.util.concurrent.atomic.AtomicInteger();var entries=new java.util.concurrent.atomic.AtomicInteger();
        var sources=new RefinementPipeline.Sources(){
            public void requireCurrent(){}
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest source,CancellationProbe cancellation){
                if(ordinal.getAndIncrement()==failingSource){clock.set(1001);cancellation.checkpoint();fail("Expired final probe must stop evidence preparation");}
                return parent.root().evidence();
            }
        };
        try(var scope=PipelineAdmissionObserver.openForTesting(value->{});var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,plan,new RefinementPipeline.Adapters(sources,RefinementPipeline.productionRunner(t->entries.incrementAndGet()),owner),budget,CancellationProbe.NONE);
            System.out.println("source-deadline ordinal="+failingSource+" outcome="+result.outcome()+" candidates="+result.candidates().size()+" native="+entries.get()+" work="+result.actualWork()+" attempts="+result.attempts().stream().map(a->a.kind()+":"+a.reason()).toList());
            assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());assertEquals(failingSource,entries.get());
            var failed=result.attempts().stream().filter(a->a.source().equals(plan.requests().get(failingSource))).findFirst().orElseThrow();
            assertEquals(RefinementPipeline.AttemptKind.UNAVAILABLE,failed.kind());assertEquals("source-preparation-resource-limit",failed.reason());assertEquals(0,failed.nativeCallAvailability().orElseThrow());
            assertEquals(PipelineAdmissionObserver.Gate.FINAL_DEADLINE,scope.latest(ticket).orElseThrow().gate());
            if(failingSource==0){assertTrue(result.candidates().isEmpty());assertEquals(TraceWorkUsage.none(),result.actualWork().orElseThrow());}
            else {assertFalse(result.candidates().isEmpty(),"Deadline during a later source must retain the earlier actual assessed native candidates");assertTrue(result.actualWork().orElseThrow().transitions()>0);assertTrue(result.attempts().stream().anyMatch(a->a.kind()==RefinementPipeline.AttemptKind.WHOLE&&a.nativeCallAvailability().orElseThrow()==1));}
            var receipt=RefinementOperationReceipt.capture(ticket,parent,result);assertEquals(result.actualWork(),receipt.actualWork());assertEquals(Outcome.RESOURCE_LIMIT,receipt.outcome());
        }
    }
    static OperationTicket ticket(ParentTraceReference parent,TrackerMode engine) {
        return new OperationTicket(new SessionId(UUID.randomUUID()),1,parent.parentStage(),parent.parentChoice(),engine,parent.root().request().geometryMode());
    }
    static RefinementSourcePlanner.Plan plan(OperationTicket ticket,ParentTraceReference parent,boolean managed) {
        var c=parent.root().effectiveConfig().heatmap();var ordinary=AlignmentTileSourcePlan.from(c);
        var settings=new RefinementSourcePlanner.Settings(managed,c.activity(),ordinary.selectedColor(),
            ordinary.requiredZooms().stream().mapToInt(Integer::intValue).max().orElseThrow(),ordinary.requiredZooms().stream().mapToInt(Integer::intValue).min().orElseThrow(),
            new ManagedTileGeneration(Math.max(0,c.cacheBuster())),c.intensitySamplingMode()==null?IntensitySamplingMode.COLOR_MAPPING:c.intensitySamplingMode(),List.of(),c.aggregateAllColorSchemes());
        return RefinementSourcePlanner.plan(ticket,settings,parent);
    }
    static RefinementPipeline.Sources sources(ParentTraceReference parent) {
        return new RefinementPipeline.Sources() {
            public void requireCurrent() { }
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest request,CancellationProbe cancellation) {cancellation.checkpoint();return parent.root().evidence();}
        };
    }
    static RefinementEngineAdapter.Run refusal(ParentTraceReference parent,Outcome outcome,int nativeCalls) {
        return new RefinementEngineAdapter.Run(parent,null,Optional.empty(),Optional.empty(),Optional.empty(),Map.of(),Map.of(),nativeCalls,
            Optional.empty(),outcome);
    }
    @Test void actualAAndBInBothModesKeepNativeRefusalsInspectableAndNeverRetryAnIdenticalWholeRange() {
        for(var engine:List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC))for(var mode:AlignmentMode.values()) {
            var parent=ParentBackedComposerTest.parent(mode);var ticket=ticket(parent,engine);
            try(var owner=AttemptMemoryLedger.production().rootOwner()) {
                var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,false),
                    new RefinementPipeline.Adapters(sources(parent),RefinementPipeline.productionRunner(),owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
                assertNotEquals(Outcome.FAILED,result.outcome(),engine+"/"+mode+" must preserve inspectable assessed native results");
                assertTrue(result.actualWork().orElseThrow().transitions()>0);
                assertEquals(1,result.attempts().stream().filter(a->a.run().isPresent()).count());
                assertTrue(result.attempts().stream().filter(a->a.run().isPresent()).allMatch(a->a.kind()==RefinementPipeline.AttemptKind.WHOLE));
                var nativeRun=result.attempts().stream().flatMap(a->a.run().stream()).findFirst().orElseThrow();
                assertSame(parent.root().network(),nativeRun.reference().root().network());assertEquals(mode,nativeRun.request().geometryMode());
                var route=nativeRun.finalized().orElseThrow().routes().get(0);
                if(route.quality().disposition()==FinalGeometryEvaluator.Disposition.HARD_BLOCKED) {
                    assertTrue(result.candidates().isEmpty());assertFalse(result.rejected().isEmpty());
                    for(var finding:route.quality().findings())assertTrue(result.rejected().get(0).quality().has(finding.code()),"native refusal reason lost: "+finding.code());
                } else {
                    assertFalse(result.candidates().isEmpty());
                    assertEquals(parent.frozenAnchors().get(0),result.candidates().get(0).assessed().candidate().points().get(0));
                }
            }
        }
    }
    @Test void localFailureRetainsSelectedParentAndDoesNotDiscardSuccessfulNativeIntervals() throws Exception {
        var parent=RefinementIntervalPlannerTest.capturedTopologyParent(List.of(new OccurrenceRange(0,18)),Set.of(),Set.of());
        var ticket=ticket(parent,TrackerMode.PROBABILISTIC);var ranges=new ArrayList<OccurrenceRange>();
        var vertexMethod=RefinementPipeline.class.getDeclaredMethod("maximumAssembledVertices",ParentTraceReference.class,int.class);vertexMethod.setAccessible(true);
        long vertices=(long)vertexMethod.invoke(null,parent,new RefinementIntervalPlanner().plan(parent).size());
        var nativeBytes=new AtomicLong(1_048_576L+32L*vertices*2048L);
        var real=RefinementPipeline.productionRunner();
        RefinementPipeline.NativeRunner runner=(t,p,e,b,c,o)-> {
            assertEquals(nativeBytes.get(),o.currentBytes(),"WHOLE and INTERVAL native entries never hold assessment scratch");
            ranges.add(p.originalRange());
            if(p.originalRange().firstIndex()==8)return refusal(p,Outcome.NO_USABLE_RESULT,0);
            var run=real.run(t,p,e,b,c,o);nativeBytes.set(o.currentBytes());return run;
        };
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,false),new RefinementPipeline.Adapters(sources(parent),runner,owner),
                RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertFalse(result.candidates().isEmpty(),"outcome="+result.outcome()+" rejections="+result.rejected().stream().map(a->a.quality().findings()).toList());var candidate=result.candidates().get(0).assessed().candidate();
            assertTrue(candidate.points().contains(parent.occurrencePosition(9)),"failed middle interval must retain selected A geometry");
            assertTrue(candidate.spans().stream().anyMatch(s->!s.parentRetained()));
            assertEquals(parent.originalRange(),ranges.get(0));assertTrue(ranges.contains(new OccurrenceRange(0,4)));
            assertTrue(ranges.contains(new OccurrenceRange(8,10)),"the local failure must really be attempted");
            assertTrue(ranges.contains(new OccurrenceRange(14,18)),"later native success must survive the failed interval");
            var whole=result.attempts().stream().filter(a->a.kind()==RefinementPipeline.AttemptKind.WHOLE).findFirst().orElseThrow().run().orElseThrow();
            assertEquals(0,whole.nativeCalls());assertTrue(whole.nativeRun().isEmpty());
        }
    }
    @Test void nativeResourceRefusalStopsAllRemainingSourcesAndIntervals() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.CORRIDOR_AWARE);
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),new RefinementPipeline.Adapters(sources(parent),RefinementPipeline.productionRunner(),owner),
                RefinementPipeline.OperationBudget.production(new TraceBudgets(96,1,1,1,1)),CancellationProbe.NONE);
            assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());assertTrue(result.candidates().isEmpty());
            assertEquals(1,result.attempts().stream().filter(a->a.run().isPresent()).count());
            assertTrue(result.attempts().stream().anyMatch(a->a.kind()==RefinementPipeline.AttemptKind.UNATTEMPTED));
            assertTrue(result.actualWork().isPresent());
        }
    }
    @Test void unknownNativeWorkAndControlledCancellationAbortAllSuccess() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        for(boolean unknown:List.of(false,true))try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),new RefinementPipeline.Adapters(sources(parent),
                (t,p,e,b,c,o)->{if(!unknown)throw new CancellationException();return refusal(p,Outcome.FAILED,1);},owner),
                RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertTrue(result.candidates().isEmpty());assertEquals(unknown?Outcome.FAILED:Outcome.CANCELLED,result.outcome());
            if(unknown)assertTrue(result.actualWork().isEmpty());
        }
    }
    @Test void elapsedDeadlineAndInsufficientReservedMemoryRefuseBeforeAnyInference() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var clock=new AtomicLong();var budget=new RefinementPipeline.OperationBudget(TraceBudgets.defaults(),clock::get,100,20);clock.set(81);
        for(boolean deadline:List.of(true,false))try(var owner=new AttemptMemoryLedger(deadline?AttemptMemoryLedger.MAX_BYTES:1024).rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,false),new RefinementPipeline.Adapters(sources(parent),
                (t,p,e,b,c,o)->{fail("native entry was not reserved");return null;},owner),deadline?budget:RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());assertTrue(result.candidates().isEmpty());
            assertEquals(TraceWorkUsage.none(),result.actualWork().orElseThrow());
        }
    }
    @Test void admissionObserverAttributesActualAssembledReservationWithoutInventingCurrentEvidence() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        try(var scope=PipelineAdmissionObserver.openForTesting(value->{});var owner=new AttemptMemoryLedger(1024).rootOwner()) {
            var result=admissionRefusal(parent,ticket,owner,sources(parent),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()));
            assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());
            var observed=scope.latest(ticket).orElseThrow();
            assertEquals(PipelineAdmissionObserver.Gate.ASSEMBLED_RETENTION,observed.gate());
            assertEquals(0,observed.currentBytes().orElseThrow());assertEquals(1024,observed.limitBytes().orElseThrow());
            assertTrue(observed.requestedBytes().orElseThrow()>1024);
            assertEquals(parent.points().size(),observed.parentPoints());assertEquals(parent.originalRange().size(),observed.selectedOccurrences());
            assertEquals(parent.root().request().configuredSampleStepMeters(),observed.rootConfiguredStepMeters());
            assertEquals(parent.root().request().profileChainage().cumulativeGroundMeters().size(),observed.rootProfileRows());
            assertEquals(parent.root().evidence().resampling().outputWidth(),observed.rootRasterWidth());
            assertTrue(observed.rowBound().orElseThrow()>0);assertTrue(observed.assembledVertexBound().orElseThrow()>observed.rowBound().orElseThrow());
            assertTrue(observed.currentEvidence().isEmpty());assertTrue(observed.elapsedNanos().isEmpty());
            assertTrue(result.attempts().stream().allMatch(a->a.kind()==RefinementPipeline.AttemptKind.UNATTEMPTED));
        }
    }
    @Test void admissionObserverDistinguishesScalarPreparationFromAssessmentScratch() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var events=new java.util.concurrent.atomic.AtomicInteger();
        for(boolean scalar:List.of(true,false))try(var scope=PipelineAdmissionObserver.openForTesting(value->events.incrementAndGet());
                var owner=new AttemptMemoryLedger(32L*1024*1024).rootOwner()) {
            var source=new RefinementPipeline.Sources(){
                public void requireCurrent() { }
                public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest source,CancellationProbe cancel){
                    if(scalar)owner.reserve(AttemptMemoryLedger.MAX_BYTES);return parent.root().evidence();
                }
            };
            events.set(0);var budget=RefinementPipeline.OperationBudget.production(TraceBudgets.defaults());
            var result=scalar?admissionRefusal(parent,ticket,owner,source,budget):new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),
                new RefinementPipeline.Adapters(source,RefinementPipeline.productionRunner()::run,owner),budget,CancellationProbe.NONE);
            assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());var observed=scope.latest(ticket).orElseThrow();assertEquals(1,events.get(),"One original admission refusal emits exactly one event");
            assertEquals(scalar?PipelineAdmissionObserver.Gate.SCALAR_PREPARATION:PipelineAdmissionObserver.Gate.ASSESSMENT_SCRATCH,observed.gate());
            assertTrue(observed.currentBytes().orElseThrow()>0);assertEquals(32L*1024*1024,observed.limitBytes().orElseThrow());
            assertTrue(observed.requestedBytes().orElseThrow()>observed.limitBytes().orElseThrow()-observed.currentBytes().orElseThrow());
            assertEquals(!scalar,observed.currentEvidence().isPresent());
            if(!scalar)assertEquals(parent.root().evidence().resampling().outputWidth(),observed.currentEvidence().orElseThrow().outputWidth());
            if(scalar)assertTrue(result.attempts().stream().allMatch(a->a.nativeCallAvailability().orElse(-1)==0));
            else {
                assertTrue(result.attempts().stream().anyMatch(a->a.nativeCallAvailability().orElse(-1)==1));
                assertTrue(result.actualWork().orElseThrow().transitions()>0,"Assessment admission failure retains completed actual native work");
            }
        }
    }
    @Test void sourcePreparationObserverPreservesOriginalNumericLedgerRefusalThroughWorkflowStyleWrapper() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var raw=new java.util.concurrent.atomic.AtomicReference<AttemptMemoryLedger.ResourceLimitException>();var events=new java.util.concurrent.atomic.AtomicInteger();
        try(var scope=PipelineAdmissionObserver.openForTesting(value->events.incrementAndGet());var owner=new AttemptMemoryLedger(32L*1024*1024).rootOwner()) {
            var source=new RefinementPipeline.Sources(){
                public void requireCurrent(){}
                public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest request,CancellationProbe cancel){
                    try{owner.reserve(AttemptMemoryLedger.MAX_BYTES);throw new AssertionError("Ledger must refuse this allocation");}
                    catch(AttemptMemoryLedger.ResourceLimitException limited){raw.set(limited);throw new RefinementPipeline.SourcePreparationFailure(Outcome.RESOURCE_LIMIT,limited);}
                }
            };
            var result=admissionRefusal(parent,ticket,owner,source,RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()));
            assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());assertNotNull(raw.get());var observed=scope.latest(ticket).orElseThrow();assertEquals(1,events.get());
            assertEquals(PipelineAdmissionObserver.Gate.SCALAR_PREPARATION,observed.gate());assertEquals(raw.get().requestedBytes(),observed.requestedBytes().orElseThrow());assertEquals(raw.get().currentBytes(),observed.currentBytes().orElseThrow());assertEquals(raw.get().limitBytes(),observed.limitBytes().orElseThrow());
            assertTrue(observed.currentEvidence().isEmpty());assertEquals("source-preparation-resource-limit",result.attempts().get(0).reason());assertTrue(result.attempts().stream().allMatch(a->a.nativeCallAvailability().orElse(-1)==0));
        }
    }
    @Test void admissionObserverUsesActualInjectedDeadlineElapsedAndPreservesUnknownMemory() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        for(long elapsed:List.of(81L,101L))try(var scope=PipelineAdmissionObserver.openForTesting(value->{});var owner=AttemptMemoryLedger.production().rootOwner()) {
            var clock=new AtomicLong();var budget=new RefinementPipeline.OperationBudget(TraceBudgets.defaults(),clock::get,100,20);clock.set(elapsed);
            var result=admissionRefusal(parent,ticket,owner,sources(parent),budget);assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());
            var observed=scope.latest(ticket).orElseThrow();
            assertEquals(elapsed==81?PipelineAdmissionObserver.Gate.INFERENCE_DEADLINE:PipelineAdmissionObserver.Gate.FINAL_DEADLINE,observed.gate());
            assertEquals(elapsed,observed.elapsedNanos().orElseThrow());assertEquals(elapsed==81?80:100,observed.deadlineNanos().orElseThrow());
            assertTrue(observed.requestedBytes().isEmpty());assertTrue(observed.currentBytes().isEmpty());assertTrue(observed.limitBytes().isEmpty());
        }
    }
    @Test void admissionObserverKeepsFirstDeadlineWhenFinalProbeAlsoExpires() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var reads=new java.util.concurrent.atomic.AtomicInteger();long[] times={0,0,81,101};
        var budget=new RefinementPipeline.OperationBudget(TraceBudgets.defaults(),()->times[reads.getAndIncrement()],100,20);
        try(var scope=PipelineAdmissionObserver.openForTesting(value->{});var owner=AttemptMemoryLedger.production().rootOwner()) {
            assertEquals(Outcome.RESOURCE_LIMIT,admissionRefusal(parent,ticket,owner,sources(parent),budget).outcome());
            var observed=scope.latest(ticket).orElseThrow();
            assertEquals(PipelineAdmissionObserver.Gate.INFERENCE_DEADLINE,observed.gate());
            assertEquals(81,observed.elapsedNanos().orElseThrow());assertEquals(80,observed.deadlineNanos().orElseThrow());
            assertEquals(4,reads.get(),"diagnostics must not read the clock again");
        }
    }
    @Test void admissionObserverScopeCloseCannotEraseNewerRegistrationOrExposeForeignTicket() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var old=PipelineAdmissionObserver.openForTesting(value->{fail("obsolete observer invoked");});
        try(var current=PipelineAdmissionObserver.openForTesting(value->{});var owner=new AttemptMemoryLedger(1024).rootOwner()) {
            old.close();admissionRefusal(parent,ticket,owner,sources(parent),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()));
            assertTrue(current.latest(ticket).isPresent());assertTrue(old.latest(ticket).isEmpty());
            assertTrue(current.latest(ticket(parent,TrackerMode.PROBABILISTIC)).isEmpty());
        }finally{old.close();}
        try(var owner=new AttemptMemoryLedger(1024).rootOwner()) {
            assertEquals(Outcome.RESOURCE_LIMIT,admissionRefusal(parent,ticket,owner,sources(parent),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults())).outcome());
        }
    }
    @Test void admissionObserverFailureCannotChangeResourceOutcomeOrInventNativeWork() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        try(var scope=PipelineAdmissionObserver.openForTesting(value->{throw new AssertionError("observer failure");});var owner=new AttemptMemoryLedger(1024).rootOwner()) {
            var result=admissionRefusal(parent,ticket,owner,sources(parent),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()));
            assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());assertTrue(scope.latest(ticket).isPresent());
            assertEquals(TraceWorkUsage.none(),result.actualWork().orElseThrow());assertTrue(result.candidates().isEmpty());
        }
    }
    private static RefinementPipeline.Result admissionRefusal(ParentTraceReference parent,OperationTicket ticket,
            AttemptMemoryLedger.Owner owner,RefinementPipeline.Sources sources,RefinementPipeline.OperationBudget budget) {
        return new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),new RefinementPipeline.Adapters(sources,
            (t,p,e,b,c,o)->{fail("no native entry is admitted");return null;},owner),budget,CancellationProbe.NONE);
    }
    @Test void inspectableReviewPreservesItsActualReasonAndNeverStartsFallback() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var production=RefinementPipeline.productionRunner();
        RefinementPipeline.NativeRunner reviewed=(t,p,e,b,c,o)-> {
            var run=production.run(t,p,e,b,c,o);var finalized=run.finalized().orElseThrow();var routes=new ArrayList<ModernTracePipeline.Route>();
            for(var route:finalized.routes()) {
                var q=route.quality();var finding=new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.SEARCH_TRUNCATED,FinalGeometryEvaluator.Severity.REVIEW,0,route.pointIds().size()-1,0);
                q=new FinalGeometryEvaluator.Result(q.id(),FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED,List.of(finding),q.totalLengthMeters(),q.directlySupportedLengthMeters(),q.worstUnsupportedSpanMeters(),q.meanImageCenterCost(),q.bendPreservingRoughness());
                routes.add(new ModernTracePipeline.Route(route.rawHypothesis(),route.hypothesis(),route.pointIds(),route.assignments(),route.sourceOwnership(),q,route.cleanupStatus(),route.geometryChanged(),route.lineage()));
            }
            return new RefinementEngineAdapter.Run(run.reference(),run.request(),run.execution(),run.nativeRun(),Optional.of(new ModernTracePipeline.Result(finalized.inference(),routes)),run.nativePointReferenceChainages(),run.finalCorrespondence(),run.nativeCalls(),run.unavailable(),run.outcome());
        };
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,false),new RefinementPipeline.Adapters(sources(parent),reviewed,owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertFalse(result.candidates().isEmpty());var quality=result.candidates().get(0).assessed().quality();
            assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED,quality.disposition());assertTrue(quality.has(FinalGeometryEvaluator.FindingCode.SEARCH_TRUNCATED));
            assertEquals(1,result.attempts().stream().filter(a->a.run().isPresent()).count());
        }
    }
    @Test void currentSourceInvalidationAfterACompletedNativeCallAbortsEveryCandidateWithoutDiscardingKnownWork() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);var current=new boolean[]{true};
        var sources=new RefinementPipeline.Sources() {
            public void requireCurrent(){if(!current[0])throw new IllegalStateException("synthetic stale generation");}
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest s,CancellationProbe c){return parent.root().evidence();}
        };
        var real=RefinementPipeline.productionRunner();
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),new RefinementPipeline.Adapters(sources,(t,p,e,b,c,o)->{var run=real.run(t,p,e,b,c,o);current[0]=false;return run;},owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertEquals(Outcome.STALE,result.outcome());assertTrue(result.candidates().isEmpty());assertTrue(result.actualWork().orElseThrow().transitions()>0);
            assertEquals(1,result.attempts().stream().filter(a->a.run().isPresent()).count());
        }
    }
    @Test void completedPalettesConsumeOneActualBudgetAndDedupKeepsEveryPaletteProvenance() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var issued=new ArrayList<TraceBudgets>();var real=RefinementPipeline.productionRunner();
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),new RefinementPipeline.Adapters(sources(parent),(t,p,e,b,c,o)->{issued.add(b);return real.run(t,p,e,new TraceBudgets(b.maximumStatesPerProfile(),b.maximumPairVisits(),b.maximumTransitions(),1,1),c,o);},owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            var runs=result.attempts().stream().flatMap(a->a.run().stream()).toList();assertTrue(runs.size()>=2,"public B scene should complete more than one palette");
            var sum=TraceWorkUsage.none();for(int i=0;i<runs.size();i++) {
                assertEquals(TraceBudgets.defaults().maximumTransitions()-sum.transitions(),issued.get(i).maximumTransitions());
                assertEquals(TraceBudgets.defaults().maximumPairVisits()-sum.pairVisits(),issued.get(i).maximumPairVisits());
                sum=sum.plus(runs.get(i).actualWork());
            }
            assertEquals(sum,result.actualWork().orElseThrow());assertFalse(result.candidates().isEmpty());
            assertTrue(result.candidates().get(0).sources().size()>=2,"dedup must retain alternate palette attribution");assertTrue(result.candidates().size()<=32);
            assertTrue(result.sourceOutcomes().stream().filter(s->!s.assessments().isEmpty()).count()>=2,"every completed palette keeps its own full assessment before dedup");
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void identicalGeometryWithConflictingNativeReviewKeepsSourceQualifiedChoicesInBothOrders(boolean reviewedFirst) {
        var parent=straightScene(ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE));var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var sourcePlan=plan(ticket,parent,true);var reviewedSource=sourcePlan.requests().get(reviewedFirst?0:1);var real=RefinementPipeline.productionRunner();
        int[] calls={0};
        RefinementPipeline.NativeRunner controlled=(t,p,e,b,c,o)-> {
            var run=real.run(t,p,e,new TraceBudgets(b.maximumStatesPerProfile(),b.maximumPairVisits(),b.maximumTransitions(),1,1),c,o);
            boolean review=calls[0]++==(reviewedFirst?0:1);var finalized=run.finalized().orElseThrow();var routes=new ArrayList<ModernTracePipeline.Route>();
            for(var route:finalized.routes()) {
                var q=route.quality();var findings=review?List.of(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.SEARCH_TRUNCATED,
                    FinalGeometryEvaluator.Severity.REVIEW,0,route.pointIds().size()-1,0)):List.<FinalGeometryEvaluator.Finding>of();
                q=new FinalGeometryEvaluator.Result(q.id(),review?FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED:FinalGeometryEvaluator.Disposition.APPLICABLE,
                    findings,q.totalLengthMeters(),q.directlySupportedLengthMeters(),q.worstUnsupportedSpanMeters(),q.meanImageCenterCost(),q.bendPreservingRoughness());
                routes.add(new ModernTracePipeline.Route(route.rawHypothesis(),route.hypothesis(),route.pointIds(),route.assignments(),route.sourceOwnership(),q,route.cleanupStatus(),route.geometryChanged(),route.lineage()));
            }
            return new RefinementEngineAdapter.Run(run.reference(),run.request(),run.execution(),run.nativeRun(),Optional.of(new ModernTracePipeline.Result(finalized.inference(),routes)),run.nativePointReferenceChainages(),run.finalCorrespondence(),run.nativeCalls(),run.unavailable(),run.outcome());
        };
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,sourcePlan,new RefinementPipeline.Adapters(sources(parent),controlled,owner),
                RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            var reviewed=result.candidates().stream().filter(r->r.sources().contains(reviewedSource)).findFirst().orElseThrow();
            assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED,reviewed.assessed().quality().disposition(),"another palette must not launder this source's native review");
            assertTrue(reviewed.assessed().quality().has(FinalGeometryEvaluator.FindingCode.SEARCH_TRUNCATED));assertEquals(List.of(reviewedSource),reviewed.sources());
            assertEquals(2,result.candidates().size(),"compatible applicable palettes still deduplicate; incompatible review remains separately selectable");
            var applicable=result.candidates().get(0);assertEquals(FinalGeometryEvaluator.Disposition.APPLICABLE,applicable.assessed().quality().disposition());
            assertFalse(applicable.assessed().quality().has(FinalGeometryEvaluator.FindingCode.SEARCH_TRUNCATED));
            assertEquals(sourcePlan.requests().size()-1,applicable.sources().size());assertFalse(applicable.sources().contains(reviewedSource));
            assertEquals(applicable.assessed().candidate().points(),reviewed.assessed().candidate().points());
            for(var retained:result.candidates()) {
                var own=result.sourceOutcomes().stream().filter(s->s.source().equals(retained.sources().get(0))).findFirst().orElseThrow().assessments().get(0);
                assertEquals(own.quality(),retained.assessed().quality(),"ranked metrics/findings belong to one factual source, never a cross-source measurement");
            }
            var sum=TraceWorkUsage.none();for(var attempt:result.attempts())if(attempt.run().isPresent()) {
                assertEquals(RefinementPipeline.AttemptKind.WHOLE,attempt.kind());sum=sum.plus(attempt.run().orElseThrow().actualWork());
            }
            assertEquals(sourcePlan.requests().size(),calls[0]);assertEquals(sum,result.actualWork().orElseThrow());
            assertTrue(sum.rawAlternatives()<=32);assertTrue(sum.distinctAlternatives()<=8);assertTrue(result.candidates().size()<=32);
        }
    }
    @Test void sourceCaptureFromAnotherOperationIsRefusedBeforeAnyInference() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);var actualPlan=plan(ticket,parent,false);
        var other=plan(ticket(parent,TrackerMode.PROBABILISTIC),parent,false);
        var wrong=new RefinementPipeline.Sources() {
            public void requireCurrent(){ }
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest s,CancellationProbe c){return parent.root().evidence();}
            public Optional<RefinementSourcePlanner.Plan> binding(){return Optional.of(other);}
        };
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            assertThrows(IllegalArgumentException.class,()->new RefinementPipeline().run(ticket,parent,actualPlan,new RefinementPipeline.Adapters(wrong,
                (t,p,e,b,c,o)->{fail("wrong source operation reached inference");return null;},owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE));
        }
    }
    @Test void representableStraightMoveUsesActualAAndBWithoutFallback() {
        for(var engine:List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC)) {
            var parent=straightScene(ParentBackedComposerTest.parent(AlignmentMode.MOVE_EXISTING_NODES));var ticket=ticket(parent,engine);
            try(var owner=AttemptMemoryLedger.production().rootOwner()) {
                var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,false),new RefinementPipeline.Adapters(sources(parent),RefinementPipeline.productionRunner(),owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
                assertFalse(result.candidates().isEmpty(),"representable Move "+engine+" outcome="+result.outcome());
                assertEquals(1,result.attempts().stream().filter(a->a.run().isPresent()).count());
                var finalGeometry=result.candidates().get(0).assessed().candidate();assertEquals(5,finalGeometry.points().size());
                assertTrue(finalGeometry.pointIds().stream().allMatch(FinalRoutePointId.ExistingWayNodeOccurrence.class::isInstance));
                assertTrue(result.actualWork().orElseThrow().transitions()>0);
            }
        }
    }
    @Test void defaultProductionSharesPaletteSlotsAndPreservesTheSmallSceneNativeBestGeometry() {
        var parent=straightScene(ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE));var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        try(var baselineOwner=AttemptMemoryLedger.production().rootOwner();var owner=AttemptMemoryLedger.production().rootOwner()) {
            var baseline=new RefinementEngineAdapter(TrackerMode.PROBABILISTIC,"native").run(ticket,parent,parent.root().evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,baselineOwner).value();
            var reference=baseline.finalized().orElseThrow().routes().get(0);
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),new RefinementPipeline.Adapters(sources(parent),RefinementPipeline.productionRunner(),owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertTrue(result.attempts().stream().filter(a->a.run().isPresent()).count()>=2,"default production must share alternative slots across affordable base palettes");
            for(var attempt:result.attempts())if(attempt.run().isPresent()) {
                assertEquals(RefinementPipeline.AllocationPolicy.BASE_COVERAGE_ORDERED_EXTRAS_V2,attempt.allocationPolicy());
                assertEquals(attempt.issuedBudgets().orElseThrow(),attempt.run().get().request().budgets());
            }
            var first=result.sourceOutcomes().stream().filter(s->!s.assessments().isEmpty()).findFirst().orElseThrow().assessments().get(0);
            assertEquals(reference.hypothesis().points(),first.candidate().points(),"allocation must preserve the native best geometry in this public analytic scene");
            assertEquals(reference.quality().disposition(),first.quality().disposition(),"surface any changed native review status before accepting allocation");
            assertTrue(result.candidates().get(0).sources().size()>=2);
        }
    }
    @Test void commonWholeGeometryQualityRanksAffordablePaletteResultsBeforeCanonicalOrder() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
        var straight=straightScene(parent).root().evidence();
        var sources=new RefinementPipeline.Sources() {
            public void requireCurrent(){ }
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest s,CancellationProbe c){return s.palette().equals("hot")?straight:parent.root().evidence();}
        };
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),new RefinementPipeline.Adapters(sources,RefinementPipeline.productionRunner(),owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertTrue(result.candidates().size()>=2);
            assertEquals("hot",result.candidates().get(0).sources().get(0).palette(),"a completely supported straight whole preview wins over the earlier palette's unsupported endpoint approaches");
        }
    }
    @Test void actualAThenBPartialFailureKeepsTheSelectedAFinalGeometryAndOriginalAuthority() {
        var legacy=RefinementIntervalPlannerTest.capturedTopologyParent(List.of(new OccurrenceRange(0,18)),Set.of(),Set.of());
        var aTicket=ticket(legacy,TrackerMode.CORRIDOR_AWARE);var hash=legacy.root().network().canonicalHash();
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var a=new RefinementPipeline().run(aTicket,legacy,plan(aTicket,legacy,false),new RefinementPipeline.Adapters(sources(legacy),RefinementPipeline.productionRunner(),owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertFalse(a.candidates().isEmpty(),"actual A stage must produce an assessed parent");var selected=a.candidates().get(0);
            var geometry=selected.assessed().candidate();var parent=new ParentTraceReference(legacy.root(),new StageId(UUID.randomUUID()),new CandidateId("actual-selected-A"),geometry.id(),selected.sources().get(0).palette(),geometry.id(),geometry.points(),geometry.correspondence());
            var bTicket=new OperationTicket(aTicket.session(),2,parent.parentStage(),parent.parentChoice(),TrackerMode.PROBABILISTIC,AlignmentMode.PRECISE_SHAPE);
            var actual=RefinementPipeline.productionRunner();var called=new ArrayList<OccurrenceRange>();
            var b=new RefinementPipeline().run(bTicket,parent,plan(bTicket,parent,false),new RefinementPipeline.Adapters(sources(parent),(t,p,e,budget,c,o)-> {
                called.add(p.originalRange());if(p.originalRange().firstIndex()==8)return refusal(p,Outcome.NO_USABLE_RESULT,0);return actual.run(t,p,e,budget,c,o);
            },owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertTrue(called.contains(new OccurrenceRange(8,10)));assertTrue(called.contains(new OccurrenceRange(14,18)));assertFalse(b.candidates().isEmpty());
            var result=b.candidates().get(0).assessed().candidate();double first=parent.occurrenceChainage(8),last=parent.occurrenceChainage(10);
            var retained=new ArrayList<MetricPoint>();double chainage=0;
            for(int i=0;i<parent.points().size();i++) {
                if(i>0)chainage+=parent.points().get(i-1).distanceTo(parent.points().get(i));
                if(chainage>first&&chainage<last)retained.add(parent.points().get(i));
            }
            assertFalse(retained.isEmpty());for(var point:retained)assertTrue(result.points().contains(point),"failed B interval must retain actual A vertices exactly");
            assertTrue(retained.stream().anyMatch(p->Math.abs(p.yMeters()-50.5)>1e-3),"A result must differ from the Legacy parent used by this fixture");
            assertSame(legacy.root().network(),parent.root().network());assertEquals(hash,parent.root().network().canonicalHash());
        }
    }
    @Test void defaultSlotPolicyKeepsStrongCurvedAndCompetingRidgeBestGeometryAndPreferredBreadth() {
        for(boolean competing:List.of(false,true)) {
            var parent=curvedScene(ParentBackedComposerTest.parent(AlignmentMode.PRECISE_SHAPE),competing);var ticket=ticket(parent,TrackerMode.PROBABILISTIC);
            try(var baselineOwner=AttemptMemoryLedger.production().rootOwner();var owner=AttemptMemoryLedger.production().rootOwner()) {
                var baseline=new RefinementEngineAdapter(TrackerMode.PROBABILISTIC,"native").run(ticket,parent,parent.root().evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,baselineOwner).value();
                var reference=baseline.finalized().orElseThrow().routes().get(0);
                var result=new RefinementPipeline().run(ticket,parent,plan(ticket,parent,true),new RefinementPipeline.Adapters(sources(parent),RefinementPipeline.productionRunner(),owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
                var attempts=result.attempts().stream().filter(a->a.run().isPresent()&&a.run().get().nativeCalls()>0).toList();assertTrue(attempts.size()>=2);
                assertTrue(attempts.get(0).issuedBudgets().orElseThrow().maximumDistinctAlternatives()>=attempts.get(attempts.size()-1).issuedBudgets().orElseThrow().maximumDistinctAlternatives(),"preferred palette must not receive less breadth than the last palette");
                var assessed=result.sourceOutcomes().stream().filter(s->!s.assessments().isEmpty()).findFirst().orElseThrow().assessments().get(0);
                assertEquals(reference.hypothesis().points(),assessed.candidate().points(),"unsplit best geometry changed; competing="+competing);
                assertEquals(reference.quality().disposition(),assessed.quality().disposition(),"unsplit review status changed; competing="+competing);
                for(var attempt:attempts) {
                    var run=attempt.run().orElseThrow();assertEquals(attempt.issuedBudgets().orElseThrow(),run.request().budgets());
                    if(run.nativeRun().orElseThrow().result().alternativesTruncated())assertTrue(result.truncated(),"actual native truncation must remain visible at operation level");
                }
            }
        }
    }
    static ParentTraceReference curvedScene(ParentTraceReference parent,boolean competing) {
        var old=parent.root();var original=old.evidence();var field=original.fields().get("native");int width=field.width(),height=field.height();
        var scalar=new double[width*height];var valid=new boolean[scalar.length];Arrays.fill(valid,true);
        for(int x=0;x<width;x++) {
            double center=50+3*Math.sin(Math.PI*Math.max(0,Math.min(1,(x-20.)/120.)));
            for(int y=0;y<height;y++) {
                double primary=Math.exp(-.5*Math.pow((y-center)/1.4,2));double alternative=competing?.85*Math.exp(-.5*Math.pow((y-center-4)/1.2,2)):0;
                scalar[y*width+x]=.02+.98*Math.max(primary,alternative);
            }
        }
        var e=new EvidenceSnapshot("curved-slot-scene-"+competing,original.coordinateFrame(),original.transform(),original.resolution(),original.decisionRegion(),original.evidenceRegion(),Map.of("native",new ScalarEvidenceField(width,height,scalar,valid,field.lineage())),original.sourceIdentity());
        var r=old.request();r=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),e.snapshotId(),e.canonicalHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),e.resolution(),r.corridorInput(),r.disconnectionSource());
        var root=new RefinementRootAuthority(old.network(),r,e,old.effectiveConfig(),old.projectionCode(),old.sourceIdentity());
        return new ParentTraceReference(root,parent.parentStage(),parent.parentChoice(),parent.nativeCandidateId(),parent.parentPalette(),parent.parentIdentity(),parent.points(),parent.correspondence());
    }
    static ParentTraceReference straightScene(ParentTraceReference parent) {
        var old=parent.root();var original=old.evidence();var oldField=original.fields().get("native");int width=oldField.width(),height=oldField.height();
        var scalars=new double[width*height];var valid=new boolean[scalars.length];Arrays.fill(valid,true);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)scalars[y*width+x]=.02+.98*Math.exp(-.5*Math.pow((y-50.)/1.5,2));
        var evidence=new EvidenceSnapshot("straight-scene",original.coordinateFrame(),original.transform(),original.resolution(),original.decisionRegion(),original.evidenceRegion(),
            Map.of("native",new ScalarEvidenceField(width,height,scalars,valid,oldField.lineage())),original.sourceIdentity());
        var r=old.request();r=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),evidence.snapshotId(),evidence.canonicalHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),evidence.resolution(),r.corridorInput(),r.disconnectionSource());
        var root=new RefinementRootAuthority(old.network(),r,evidence,old.effectiveConfig(),old.projectionCode(),old.sourceIdentity());
        return new ParentTraceReference(root,parent.parentStage(),parent.parentChoice(),parent.nativeCandidateId(),parent.parentPalette(),parent.parentIdentity(),parent.points(),parent.correspondence());
    }
}
