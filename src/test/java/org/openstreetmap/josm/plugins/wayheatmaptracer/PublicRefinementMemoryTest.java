package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.spi.preferences.*;

/** Exact public GUI inputs through existing detached components; no graphical-window claim. */
class PublicRefinementMemoryTest {
    @TempDir java.nio.file.Path temporary;
    private record Trial(LegacyReviewOwner review,LegacyRefinementBootstrap.Seed seed) { }
    private record Operation(RefinementSession session,OperationTicket ticket,ParentTraceReference parent,RefinementSourcePlanner.Plan plan) { }
    @Test void exactPublicCaptureReleasesValidationCapacityBeforeRealNativeEntry() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var fixture=PublicGuiSmokeFixture.create(temporary.resolve("inputs"));var decoder=new TileDecoderClassifier();
        var cache=new ManagedTileCache(temporary.resolve("cache"),decoder);fixture.seedRefinementTiles(cache);
        try(var coordinator=new TileFetchCoordinator((r,c)->{throw new AssertionError("the unchanged public fixture must remain offline");},cache,decoder,TileReliabilityPolicy.defaults())) {
            var config=AlignmentConfig.withoutCleanup(PublicGuiSmokeMain.stepwiseConfig(AlignmentMode.PRECISE_SHAPE));
            Long heldEntryBytes=null,heldBaselineBytes=null;String heldInputHash=null;List<Object> heldRequestBinding=null;
            for(boolean legacyLifetime:List.of(true,false)) {
                var ledger=AttemptMemoryLedger.production();var original=new AtomicReference<Trial>();var operation=new AtomicReference<Operation>();
                try(var owner=ledger.rootOwner()) {
                    SwingUtilities.invokeAndWait(()->{
                        var way=fixture.selectedWay();var nodes=List.copyOf(way.getNodes());
                        var selection=new SelectionContext(way,0,nodes.size()-1,nodes,Set.of(nodes.get(0),nodes.get(nodes.size()-1)));
                        var review=LegacyReviewOwner.begin(fixture.dataSet(),selection,config);
                        var receipt=new TileHeatmapSampler.AcquisitionReceipt[1];
                        var result=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selection,null,null,config,
                            new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt value){receipt[0]=value;}});
                        assertTrue(result.candidates().size()>1);review.captured(result,null,receipt[0]);review.select(result.candidates().get(1));
                        original.set(new Trial(review,new LegacyRefinementBootstrap().capture(review,coordinator,owner.child("original"))));
                    });
                    var completed=new LegacyRefinementBootstrap().complete(original.get().seed(),coordinator,CancellationProbe.NONE);
                    SwingUtilities.invokeAndWait(()->{
                        var session=new RefinementSession(completed.stage(),config,completed.authority().sourceIdentity(),completed.authority(),RefinementLimits.defaults());
                        long available=session.availablePayloadBytes();owner.reserve(available).adopt(new Object());
                        var selected=session.current().candidates().stream().filter(c->c.id().equals(session.current().selected())).findFirst().orElseThrow();
                        var parent=new ParentReferenceBinder().bind(session.authority(),session.current().id(),selected);var ticket=session.begin(TrackerMode.CORRIDOR_AWARE);
                        session.reserve(ticket,32,available);operation.set(new Operation(session,ticket,parent,RefinementSourcePlanner.plan(ticket,original.get().seed().settings(),parent)));
                    });
                    var actual=operation.get();var acquired=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(
                        actual.plan(),CredentialSnapshot.fromConfig(config.heatmap()),CancellationProbe.NONE,owner);
                    long released=ledger.peakBytes()-ledger.currentBytes();
                    assertTrue(released>=16L*1024*1024,"Acquisition must release its full temporary raster workspace");
                    long baselineAfterAcquisitionBytes=ledger.currentBytes();
                    System.out.println("public-memory baseline legacyLifetime="+legacyLifetime+" currentBytes="+baselineAfterAcquisitionBytes);
                    if(legacyLifetime)owner.reserve(released).adopt(new Object());
                    var entries=new AtomicInteger();var firstEntryBytes=new AtomicLong(-1);
                    var budget=RefinementPipeline.OperationBudget.production(completed.authority().request().budgets());
                    var sourceWork=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(
                        64_000_000L,AttemptMemoryLedger.MAX_BYTES,budget.finalDeadlineNanos(),CancellationProbe.NONE,owner);
                    var sources=RefinementPipeline.managedSources(acquired.value(),coordinator,sourceWork);
                    try(var observed=PipelineAdmissionObserver.openForTesting(value->{})) {
                        var result=new RefinementPipeline().run(actual.ticket(),actual.parent(),actual.plan(),new RefinementPipeline.Adapters(
                            sources,RefinementPipeline.productionRunner(t->{if(entries.getAndIncrement()==0)firstEntryBytes.set(owner.currentBytes());}),owner),
                            budget,CancellationProbe.NONE);
                        System.out.println("public-memory legacyLifetime="+legacyLifetime+" outcome="+result.outcome()+" entries="+entries.get()+" assessed="+result.candidates().size()+" peakBytes="+ledger.peakBytes());
                        assertEquals(1,result.sourceOutcomes().size());assertTrue(ledger.peakBytes()<=AttemptMemoryLedger.MAX_BYTES);
                        if(legacyLifetime) {
                            assertEquals(Outcome.RESOURCE_LIMIT,result.outcome());assertTrue(result.candidates().isEmpty());
                            assertEquals(1,result.attempts().size(),"Only the selected detector has a native attempt receipt");
                            if(entries.get()==0) {
                                var refusal=observed.latest(actual.ticket()).orElseThrow();
                                assertTrue(refusal.requestedBytes().orElseThrow()>refusal.limitBytes().orElseThrow()-refusal.currentBytes().orElseThrow());
                                assertEquals(TraceWorkUsage.none(),result.actualWork().orElseThrow());
                            } else {
                                assertTrue(result.actualWork().isPresent());
                                assertTrue(result.attempts().stream().anyMatch(a->a.nativeCallAvailability().orElse(0)>0&&a.run().orElseThrow().actualWorkAvailability().isPresent()));
                                boolean nativeRefusal=result.attempts().stream().flatMap(a->a.run().stream()).flatMap(r->r.nativeRun().stream()).anyMatch(r->r.result().status()==TraceHypothesisSet.Status.RESOURCE_LIMIT);
                                if(nativeRefusal) {
                                    var refusal=observed.latestNative(actual.ticket()).orElseThrow();
                                    assertTrue(refusal.family()!=PipelineAdmissionObserver.NativeFamily.UNAVAILABLE);assertTrue(refusal.phase().isPresent());
                                } else {
                                    // COMPLETE native work may fail its retained-graph admission
                                    // before assessment, or the later assessment admission itself.
                                    var refusal=observed.latest(actual.ticket()).orElseThrow();
                                    assertTrue(refusal.gate()==PipelineAdmissionObserver.Gate.NATIVE_RETENTION||refusal.gate()==PipelineAdmissionObserver.Gate.ASSESSMENT_SCRATCH);
                                    assertTrue(refusal.requestedBytes().orElseThrow()>refusal.limitBytes().orElseThrow()-refusal.currentBytes().orElseThrow());
                                    System.out.println("public-memory actualBoundary="+refusal.gate().name()+" requested="+refusal.requestedBytes().orElseThrow()+" current="+refusal.currentBytes().orElseThrow()+" limit="+refusal.limitBytes().orElseThrow());
                                }
                            }
                        } else {
                            assertTrue(entries.get()>0,"completed validation workspace must be released before actual engine entry");
                            if(result.candidates().isEmpty())assertTrue(result.attempts().stream().flatMap(a->a.run().stream()).flatMap(r->r.nativeRun().stream())
                                .anyMatch(r->r.result().status()==TraceHypothesisSet.Status.RESOURCE_LIMIT),"missing geometry requires an actual subsequent native resource receipt");
                        }
                        if(entries.get()>0) {
                            var first=result.attempts().stream().filter(a->a.nativeCallAvailability().orElse(0)>0).findFirst().orElseThrow();
                            var run=first.run().orElseThrow();var request=run.request();var nativeReceipt=run.nativeRun().orElseThrow().corridorReceipt().orElseThrow();
                            assertEquals(request.evidenceContentHash(),nativeReceipt.evidenceHash());assertEquals(request.networkContentHash(),nativeReceipt.networkHash());
                            assertEquals(actual.parent().root().request().networkContentHash(),request.networkContentHash());
                            assertEquals(actual.parent().root().network().snapshotId(),request.networkSnapshotId());
                            var evidence=sources.evidence(first.source(),CancellationProbe.NONE);
                            var source=sources.retentionDescriptor(first.source(),evidence).orElseThrow();assertTrue(source.hasFactualInputHash());
                            assertFalse(first.source().aggregate());
                            assertEquals(RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL,source.inputHashProtocol());
                            assertTrue(sourceWork.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.HASH)>0);
                            var binding=List.<Object>of(request.selectedWayKey(),request.selectedRange(),request.engine(),request.geometryMode(),request.permissions(),request.budgets(),request.configuredSampleStepMeters(),request.profileChainage().cumulativeGroundMeters(),request.evidenceResolution());
                            if(legacyLifetime){heldEntryBytes=firstEntryBytes.get();heldBaselineBytes=baselineAfterAcquisitionBytes;heldInputHash=source.inputByteHash();heldRequestBinding=binding;}
                            else if(heldEntryBytes!=null){
                                assertEquals(heldInputHash,source.inputByteHash());assertEquals(heldRequestBinding,binding);
                                long baselineDelta=heldBaselineBytes-baselineAfterAcquisitionBytes;
                                long preparationDelta=(heldEntryBytes-heldBaselineBytes-41943040L)-(firstEntryBytes.get()-baselineAfterAcquisitionBytes);
                                System.out.println("public-memory paired baselineDelta="+baselineDelta+" preparationDelta="+preparationDelta+" entryDelta="+(heldEntryBytes-firstEntryBytes.get()));
                                assertEquals(41943040L,(heldEntryBytes-heldBaselineBytes)-(firstEntryBytes.get()-baselineAfterAcquisitionBytes));
                            }
                        }
                    }
                }finally{SwingUtilities.invokeAndWait(()->{if(operation.get()!=null)operation.get().session().close();if(original.get()!=null)original.get().review().close();});}
                assertEquals(0,ledger.currentBytes());
            }
        }
    }
}
