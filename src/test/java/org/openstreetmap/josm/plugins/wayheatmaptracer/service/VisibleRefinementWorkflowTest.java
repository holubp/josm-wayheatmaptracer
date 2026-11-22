package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

/** Four predeclared public small stripe cells exercise actual scalar/native/assessment/publication. */
class VisibleRefinementWorkflowTest {
    @TempDir Path temp;
    @BeforeAll static void setup(){Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));}
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> cells(){return LegacyRefinementWorkflowTest.modes();}
    @ParameterizedTest @MethodSource("cells")
    void visibleABPreciseAndMove(AlignmentMode mode,TrackerMode engine) throws Exception {
        var decoder=new TileDecoderClassifier();var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();
        var workflow=new LegacyRefinementWorkflow[1];var owner=new LegacyReviewOwner[1];var epoch=new VisibleSourceEpoch();
        var lifecycleEvents=new ArrayList<String>();
        try(var coordinator=new TileFetchCoordinator((r,c)->{fail("Visible refinement must not fetch managed tiles");return null;},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
            try {
                SwingUtilities.invokeAndWait(()->{
                    var data=new DataSet();var nodes=new ArrayList<Node>();
                    var projection=Projections.getProjectionByCode("EPSG:3857");
                    for(int i=0;i<5;i++){var node=new Node(projection.eastNorth2latlon(new EastNorth((i-2)*1.56,0)));data.addPrimitive(node);nodes.add(node);}
                    var way=new Way();way.setNodes(nodes);way.put("highway","path");data.addPrimitive(way);data.allPrimitives().forEach(p->p.setModified(false));
                    var selection=new SelectionContext(way,0,4,nodes,Set.of(nodes.get(0),nodes.get(4)));
                    var config=AlignmentConfig.withoutCleanup(new ManagedHeatmapConfig("","","","","all","hot","",".*",mode,TrackerMode.LEGACY_V02,false,false,false,false,false,false,false,true,false,false,7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,IntensitySamplingMode.COLOR_MAPPING,7));
                    owner[0]=LegacyReviewOwner.begin(data,selection,config);var points=nodes.stream().map(Node::getEastNorth).toList();
                    var image=new BufferedImage(200,200,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<200;y++)for(int x=0;x<200;x++)image.setRGB(x,y,y==99||y==100?0xffffffff:0xff000000);
                    var unselected=new ArrayList<EastNorth>(points);for(int i=1;i<unselected.size()-1;i++)unselected.set(i,new EastNorth(points.get(i).east(),points.get(i).north()+.25));
                    var first=new CenterlineCandidate("first",1,List.of(),List.of(),unselected,unselected,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var chosen=new CenterlineCandidate("chosen",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var result=new AlignmentResult(selection,image,List.of(first,chosen),points,points,List.of(),null,null,List.of(new DetectorAttempt("hot","hot",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(first.id(),chosen.id()),"","")),List.of(first,chosen));
                    var metadata=new LegacyVisibleSource(-100,-100,100,100,6,6,OptionalDouble.empty(),"EPSG:3857",epoch.captureStable(),OptionalDouble.of(7.01),OptionalDouble.of(1.56));
                    owner[0].captured(result,LegacyVisibleRefinementBootstrapTest.factualFrame(metadata,200,200,0,0));owner[0].select(chosen);
                    workflow[0]=new LegacyRefinementWorkflow(owner[0],coordinator,owner[0]::select,d->{},s->{},work::add,publication->{
                        publications.add(publication);
                        if(mode==AlignmentMode.MOVE_EXISTING_NODES&&engine==TrackerMode.PROBABILISTIC)
                            throw new IllegalStateException("Dispatcher accepted before reporting failure");
                    });
                    workflow[0].enableRuntimeDiagnostics(()->null);
                    workflow[0].attachLifecycle(new org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementReviewLifecycle(()->lifecycleEvents.add("hide"),()->lifecycleEvents.add("show"),()->lifecycleEvents.add("progress"),()->lifecycleEvents.add("settled-view"),message->lifecycleEvents.add("failure")));
                    assertTrue(((JButton)find(workflow[0].controls(),engine==TrackerMode.CORRIDOR_AWARE?"refineA":"refineB")).isEnabled(),"Factual visible Color Mapping enables refinement");
                    workflow[0].refine(engine);
                    assertEquals(List.of("hide","progress"),lifecycleEvents,"Hide review at the first click, before bootstrap completion");
                });
                drain(work,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    assertTrue(workflow[0].nativeSelected(),workflow[0].computationEvidence());
                    assertEquals(1,java.util.Collections.frequency(lifecycleEvents,"show"),"Bootstrap and refinement reopen only the final result");
                    assertTrue(workflow[0].actualNativeCalls()>0);assertFalse(workflow[0].currentStage().candidates().isEmpty());
                    var nativeReview=workflow[0].currentNative().orElseThrow();
                    assertApplicableAfterRequiredConfirmation(workflow[0]);
                    assertTrue(workflow[0].selectedGeometry().size()>=2);
                    assertEquals("legacy-choice-1",workflow[0].currentStage().parentChoice().orElseThrow().value());
                    assertFalse(nativeReview.retainedSources().isEmpty());nativeReview.retainedSources().values().forEach(source->{source.requireCurrent();assertEquals("hot",source.source().palette());assertEquals(200,source.referenceWidth());assertEquals(200,source.referenceHeight());});
                    var receipt=nativeReview.operation().orElseThrow();assertTrue(receipt.actualWork().isPresent());
                    assertEquals(4,receipt.attempts().stream().filter(attempt->attempt.kind()==RefinementPipeline.AttemptKind.UNAVAILABLE).count());
                    assertEquals(1,receipt.attempts().stream().filter(attempt->attempt.nativeCalls().orElse(0)>0).count());
                    assertVisibleExport(workflow[0],temp.resolve("current-visible.zip"));
                    int calls=workflow[0].actualNativeCalls();((JButton)find(workflow[0].controls(),"undo")).doClick();assertEquals("legacy-choice-1",workflow[0].selectedChoiceIdentity());((JButton)find(workflow[0].controls(),"redo")).doClick();assertEquals(calls,workflow[0].actualNativeCalls());
                });
                // The selected native descendant, not root geometry, is the exact second-engine parent.
                var firstStage=stage(workflow[0]);var second=engine==TrackerMode.CORRIDOR_AWARE?TrackerMode.PROBABILISTIC:TrackerMode.CORRIDOR_AWARE;
                int beforeSmoothing=workflow[0].actualNativeCalls();
                SwingUtilities.invokeAndWait(()->workflow[0].smooth(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Strength.MEDIUM));
                drain(work,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    assertEquals(beforeSmoothing,workflow[0].actualNativeCalls(),"Smoothen must not trace or acquire another source");
                    var smoothed=workflow[0].currentStage();
                    if(!smoothed.id().equals(firstStage.id())) {
                        assertEquals(RefinementModels.StageKind.SMOOTH,smoothed.kind());
                        assertTrue(workflow[0].currentNative().orElseThrow().smoothingReceipt().isPresent());
                        assertEquals(firstStage.id(),smoothed.parent().orElseThrow());
                        ((JButton)find(workflow[0].controls(),"undo")).doClick();assertEquals(firstStage.id(),workflow[0].currentStage().id());
                        ((JButton)find(workflow[0].controls(),"redo")).doClick();assertEquals(smoothed.id(),workflow[0].currentStage().id());
                        assertEquals(beforeSmoothing,workflow[0].actualNativeCalls());
                        ((JButton)find(workflow[0].controls(),"undo")).doClick();
                    }
                });
                SwingUtilities.invokeAndWait(()->workflow[0].refine(second));drain(work,publications,workflow[0]);
                var secondStage=stage(workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    assertNotEquals(firstStage.id(),secondStage.id(),workflow[0].computationEvidence());
                    assertEquals(firstStage.id(),secondStage.parent().orElseThrow());assertEquals(firstStage.selected(),secondStage.parentChoice().orElseThrow());
                    assertApplicableAfterRequiredConfirmation(workflow[0]);
                    ((JButton)find(workflow[0].controls(),"undo")).doClick();assertEquals(firstStage.id(),workflow[0].currentStage().id());
                    int before=workflow[0].actualNativeCalls();workflow[0].refine(second);((JButton)find(workflow[0].controls(),"cancel")).doClick();assertEquals(before,workflow[0].actualNativeCalls());
                });
                drain(work,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    assertEquals(firstStage.id(),workflow[0].currentStage().id());
                    assertTrue(((JButton)find(workflow[0].controls(),"redo")).isEnabled(),"Cancelled branch keeps forward history");
                    ((JButton)find(workflow[0].controls(),"redo")).doClick();assertEquals(secondStage.id(),workflow[0].currentStage().id());
                    ((JButton)find(workflow[0].controls(),"undo")).doClick();workflow[0].refine(TrackerMode.LEGACY_V02);
                    assertEquals(firstStage.id(),workflow[0].currentStage().id());assertTrue(((JButton)find(workflow[0].controls(),"redo")).isEnabled(),"Refused operation keeps forward history");
                    workflow[0].refine(second);
                });
                drain(work,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    assertNotEquals(secondStage.id(),workflow[0].currentStage().id(),"Successful branch replaces forward stage");assertFalse(((JButton)find(workflow[0].controls(),"redo")).isEnabled());
                    assertApplicableAfterRequiredConfirmation(workflow[0]);var nodes=owner[0].originalResult().selection().way().getNodes();var coordinates=nodes.stream().map(Node::getCoor).toList();
                    int calls=workflow[0].actualNativeCalls();var command=workflow[0].command();assertStoredGuardHasNoOriginalRaster(command);workflow[0].close();
                    assertTrue(command.executeCommand());command.undoCommand();assertEquals(coordinates,nodes.stream().map(Node::getCoor).toList());assertFalse(owner[0].dataSet().isModified());assertTrue(command.executeCommand());command.undoCommand();assertEquals(calls,workflow[0].actualNativeCalls());assertFalse(owner[0].dataSet().isModified());
                });
            } finally {SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();});if(workflow[0]!=null)drain(work,publications,workflow[0]);SwingUtilities.invokeAndWait(()->{if(owner[0]!=null)owner[0].close();});}
        }
    }
    static void assertStoredGuardHasNoOriginalRaster(org.openstreetmap.josm.command.Command command){
        try {
            var validatorField=command.getClass().getDeclaredField("lockedValidator");validatorField.setAccessible(true);var validator=validatorField.get(command);
            var guardField=validator.getClass().getDeclaredField("liveSourceGeneration");guardField.setAccessible(true);var guard=guardField.get(validator);
            for(var field:guard.getClass().getDeclaredFields()){
                field.setAccessible(true);var captured=field.get(guard);
                assertFalse(captured instanceof LegacyRefinementBootstrap.OriginalSource||captured instanceof AlignmentResult||captured instanceof BufferedImage,"Stored source guard must retain only source receipt facts, not the released original raster graph");
            }
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    static void assertVisibleExport(LegacyRefinementWorkflow workflow,Path file){
        try {
            var actual=workflow.latestAttemptDiagnostics().orElseThrow();workflow.prepareExport();
            org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry.writeLatest(file.toFile());
            var archive=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader.read(file);
            var bundle=new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Bundle(archive.buildIdentity(),archive.sourceIdentityHash(),archive.parameterHash(),archive.artifacts());
            var stage=workflow.currentStage();var decoded=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.readBound(bundle,stage.id(),stage.selected()).orElseThrow();
            assertEquals(actual,decoded.latestPublication().orElseThrow().diagnostic());
            String json=new String(bundle.artifact("refinement-lineage.json").bytes(),java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s+","");
            assertTrue(json.contains("\"sourceKind\":\"VISIBLE\""),"Actual current-owner export records factual visible source kind");
            assertTrue(json.contains("\"coverage\":\"COVERED\""),"Covered reuse has a factual typed coverage record");
            assertTrue(json.contains("\"frameWidth\":200"));assertTrue(json.contains("\"frameHeight\":200"));
            assertFalse(json.contains("managedRequests"),"Absent managed work must remain unavailable");
            var preparation=actual.sourcePreparation().orElseThrow();var facts=preparation.sourceFacts().orElseThrow();
            assertEquals(actual.binding().ticket(),facts.ticket());assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.InputFrameRole.ORIGINAL_INPUT,facts.frameRole());
            assertTrue(facts.captureAuxiliaryVisits().isEmpty(),"Reuse must not fabricate a capture work measurement");assertNotEquals(facts.frameWidth(),facts.outputGridWidth(),"Input raster and scalar grid roles remain distinct");
            var olderPreparation=new RefinementAttemptDiagnostics.SourcePreparationWork(preparation.cause(),preparation.visits(),preparation.counters(),preparation.failedSource(),preparation.nativeWork());
            var older=new RefinementAttemptDiagnostics(actual.binding(),actual.pipeline(),actual.publication(),actual.phase(),actual.cause(),actual.materializationRefusals(),actual.materializationComplete(),Optional.of(olderPreparation));
            var oldPublication=new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.LatestPublication(older,decoded.latestPublication().orElseThrow().resultingSelection());
            var oldDocument=new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.Document(decoded.binding(),decoded.originalOsm(),decoded.stages(),decoded.latestAttempt(),Optional.of(oldPublication));
            var oldDecoded=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.read(org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.artifact(oldDocument));
            assertTrue(oldDecoded.latestPublication().orElseThrow().diagnostic().sourcePreparation().orElseThrow().sourceFacts().isEmpty(),"Older missing fields stay unavailable");
            var unknown=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Artifact.text("refinement-lineage.json",json.replace("\"sourceKind\":\"VISIBLE\"","\"sourceKind\":\"FUTURE\""));
            assertTrue(org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.read(unknown).latestPublication().orElseThrow().diagnostic().sourcePreparation().orElseThrow().sourceFacts().isEmpty());
            var mismatch=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Artifact.text("refinement-lineage.json",json.replace("\"sourceIdentityHash\":\""+facts.sourceIdentityHash()+"\"","\"sourceIdentityHash\":\""+"f".repeat(64)+"\""));
            assertThrows(IllegalArgumentException.class,()->org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.read(mismatch));
        } catch(Exception failure){throw new AssertionError(failure);}
    }
    static org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage stage(LegacyRefinementWorkflow workflow) throws Exception {
        var value=new java.util.concurrent.atomic.AtomicReference<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage>();SwingUtilities.invokeAndWait(()->value.set(workflow.currentStage()));return value.get();
    }
    static void assertApplicableAfterRequiredConfirmation(LegacyRefinementWorkflow workflow){
        var review=workflow.currentNative().orElseThrow();var disposition=review.plan().validation().disposition();
        assertTrue(disposition==ValidationReport.Disposition.APPLICABLE||disposition==ValidationReport.Disposition.REVIEW_REQUIRED,review.quality().findings().toString());
        if(disposition==ValidationReport.Disposition.REVIEW_REQUIRED){assertFalse(workflow.canApplyNative());workflow.confirm();}
        assertTrue(workflow.canApplyNative(),"Actual safe stored plan can be applied after its exact required review");
    }
    static void drain(LinkedBlockingQueue<Runnable> work,LinkedBlockingQueue<Runnable> publications,LegacyRefinementWorkflow workflow) throws Exception {
        for(int i=0;i<20;i++){var task=work.poll();if(task!=null)task.run();var publication=publications.poll();if(publication!=null)SwingUtilities.invokeAndWait(publication);if(work.isEmpty()&&publications.isEmpty()&&!workflow.busy())return;}
        fail("Controlled workflow did not settle");
    }
    static java.awt.Component find(java.awt.Container root,String name){for(var child:root.getComponents()){if(name.equals(child.getName()))return child;if(child instanceof java.awt.Container nested){var found=find(nested,name);if(found!=null)return found;}}return null;}
}
