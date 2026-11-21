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
                    var first=new CenterlineCandidate("first",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var chosen=new CenterlineCandidate("chosen",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var result=new AlignmentResult(selection,image,List.of(first,chosen),points,points,List.of(),null,null,List.of(new DetectorAttempt("hot","hot",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(first.id(),chosen.id()),"","")),List.of(first,chosen));
                    var metadata=new LegacyVisibleSource(-100,-100,100,100,6,6,OptionalDouble.empty(),"EPSG:3857",epoch.captureStable(),OptionalDouble.of(7.01),OptionalDouble.of(1.56));
                    owner[0].captured(result,LegacyVisibleRefinementBootstrapTest.factualFrame(metadata,200,200,0,0));owner[0].select(chosen);
                    workflow[0]=new LegacyRefinementWorkflow(owner[0],coordinator,owner[0]::select,d->{},s->{},work::add,publications::add);
                    assertTrue(((JButton)find(workflow[0].controls(),engine==TrackerMode.CORRIDOR_AWARE?"refineA":"refineB")).isEnabled(),"Factual visible Color Mapping enables refinement");
                    workflow[0].refine(engine);
                });
                drain(work,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    assertTrue(workflow[0].nativeSelected(),workflow[0].computationEvidence());
                    assertTrue(workflow[0].actualNativeCalls()>0);assertFalse(workflow[0].currentStage().candidates().isEmpty());
                    var nativeReview=workflow[0].currentNative().orElseThrow();assertFalse(nativeReview.retainedSources().isEmpty());nativeReview.retainedSources().values().forEach(RetainedSmoothingSource::requireCurrent);
                    int calls=workflow[0].actualNativeCalls();((JButton)find(workflow[0].controls(),"undo")).doClick();assertEquals("legacy-choice-1",workflow[0].selectedChoiceIdentity());((JButton)find(workflow[0].controls(),"redo")).doClick();assertEquals(calls,workflow[0].actualNativeCalls());
                });
            } finally {SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();});if(workflow[0]!=null)drain(work,publications,workflow[0]);SwingUtilities.invokeAndWait(()->{if(owner[0]!=null)owner[0].close();});}
        }
    }
    static void drain(LinkedBlockingQueue<Runnable> work,LinkedBlockingQueue<Runnable> publications,LegacyRefinementWorkflow workflow) throws Exception {
        for(int i=0;i<20;i++){var task=work.poll();if(task!=null)task.run();var publication=publications.poll();if(publication!=null)SwingUtilities.invokeAndWait(publication);if(work.isEmpty()&&publications.isEmpty()&&!workflow.busy())return;}
        fail("Controlled workflow did not settle");
    }
    static java.awt.Component find(java.awt.Container root,String name){for(var child:root.getComponents()){if(name.equals(child.getName()))return child;if(child instanceof java.awt.Container nested){var found=find(nested,name);if(found!=null)return found;}}return null;}
}
