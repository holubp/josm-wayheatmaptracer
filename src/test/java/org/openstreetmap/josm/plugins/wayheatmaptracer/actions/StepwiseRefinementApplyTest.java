package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Actual unchanged Legacy command seam; descendant original-authority transaction gates remain separate. */
class StepwiseRefinementApplyTest {
    @BeforeAll static void setup() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }
    @Test void signalAndExactReviewConfirmationGateCommandPreparation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DataSet data = new DataSet(); data.addDataSource(new DataSource(new Bounds(-1, -1, 1, 1), "public synthetic"));
            List<Node> nodes = List.of(new Node(new LatLon(0, 0)), new Node(new LatLon(0, .0001)), new Node(new LatLon(0, .0002)));
            nodes.forEach(data::addPrimitive); Way way = new Way(); way.setNodes(nodes); data.addPrimitive(way);
            var selection = new SelectionContext(way, 0, 2, nodes, Set.of(nodes.get(0), nodes.get(2)));
            var config = PluginPreferences.load().withTrackerMode(TrackerMode.LEGACY_V02).withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES);
            var source = nodes.stream().map(Node::getEastNorth).toList();
            var blocked = new CenterlineCandidate("hot/ridge-0", 1, List.of(), List.of()).withFinalPreviewPoints(source);
            var moves = List.of(new NodeMove(nodes.get(1), source.get(1)));
            var result = new AlignmentResult(selection, null, List.of(blocked), source, source, moves, null, null);
            var review = blocked.withEvidence(supportedEvidence().withCorridorCoverage(new CorridorCoverage(
                    true, false, 4, 4, 1, 0, 3, 0, 0, 0, 0, 0, false, "incomplete-longitudinal-corridor")));
            var confirmation = CandidateReviewConfirmation.capture(review, source);
            var otherGeometry = List.of(source.get(0), source.get(1).add(0, 1), source.get(2));
            var staleConfirmation = CandidateReviewConfirmation.capture(review, otherGeometry);
            AlignmentService service = new AlignmentService();
            assertAll("Full Apply eligibility and exact confirmation",
                () -> assertThrows(IllegalStateException.class, () -> AlignWayAction.createPreviewCommand(service,
                    data, selection, result, blocked, config)),
                () -> assertThrows(IllegalStateException.class, () -> AlignWayAction.createPreviewCommand(service,
                    data, selection, result, review, config, null)),
                () -> assertNotNull(AlignWayAction.createPreviewCommand(service, data, selection,
                    result, review, config, confirmation)),
                () -> assertThrows(IllegalStateException.class, () -> AlignWayAction.createPreviewCommand(service,
                    data, selection, result, review, config, staleConfirmation)));
        });
    }
    @Test void rejectedHostCommandCannotBeReportedAsApplied() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var data=new DataSet();var history=UndoRedoHandler.getInstance();int before=history.getUndoCommands().size();
            var rejected=new org.openstreetmap.josm.command.Command(data){
                @Override public boolean executeCommand(){return false;}
                @Override public String getDescriptionText(){return "Synthetic refused transaction";}
                @Override public void fillModifiedData(Collection<OsmPrimitive> modified,Collection<OsmPrimitive> deleted,Collection<OsmPrimitive> added){}
            };
            assertThrows(IllegalStateException.class,()->AlignWayAction.addReviewedCommand(history,rejected));
            assertEquals(before,history.getUndoCommands().size());assertNotSame(rejected,history.getLastCommand());assertFalse(data.isModified());
        });
    }
    @Test void falseAndThrowPreserveEntireUndoRedoPrefixesAndUnrelatedHistory() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var history=UndoRedoHandler.getInstance();var unrelated=new DataSet();
            var first=recorded(unrelated,new AtomicInteger(),true,false);var second=recorded(unrelated,new AtomicInteger(),true,false);
            AlignWayAction.addReviewedCommand(history,first);AlignWayAction.addReviewedCommand(history,second);history.undo();
            var undoPrefix=List.copyOf(history.getUndoCommands());var redoPrefix=List.copyOf(history.getRedoCommands());
            try {
                for(boolean throwing:List.of(false,true)) {
                    var executions=new AtomicInteger();var data=new DataSet();var command=recorded(data,executions,false,throwing);
                    assertThrows(IllegalStateException.class,()->AlignWayAction.addReviewedCommand(history,command));assertEquals(1,executions.get());
                    assertEquals(undoPrefix,history.getUndoCommands());assertEquals(redoPrefix,history.getRedoCommands());assertFalse(data.isModified());
                }
            } finally{history.clean(unrelated);}
        });
    }
    @Test void synchronousReentrantAddedCallbackKeepsExactCommandsAndExecutesEachOnlyOnce() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var history=UndoRedoHandler.getInstance();var data=new DataSet();var outerCount=new AtomicInteger();var nestedCount=new AtomicInteger();var events=new AtomicInteger();
            var outer=recorded(data,outerCount,true,false);var nested=recorded(data,nestedCount,true,false);var prefix=List.copyOf(history.getUndoCommands());
            var listener=new UndoRedoHandler.CommandQueuePreciseListener(){
                public void commandAdded(UndoRedoHandler.CommandAddedEvent event){events.incrementAndGet();assertTrue(Thread.holdsLock(history));if(event.getCommand()==outer){assertEquals(1,outerCount.get());AlignWayAction.addReviewedCommand(history,nested);}}
                public void cleaned(UndoRedoHandler.CommandQueueCleanedEvent event){}
                public void commandUndone(UndoRedoHandler.CommandUndoneEvent event){}
                public void commandRedone(UndoRedoHandler.CommandRedoneEvent event){}
            };
            history.addCommandQueuePreciseListener(listener);
            try {
                AlignWayAction.addReviewedCommand(history,outer);assertEquals(1,outerCount.get());assertEquals(1,nestedCount.get());assertEquals(2,events.get());
                var expected=new ArrayList<>(prefix);expected.add(outer);expected.add(nested);assertEquals(expected,history.getUndoCommands());assertSame(nested,history.getLastCommand());assertFalse(data.isModified());
            } finally{history.removeCommandQueuePreciseListener(listener);history.clean(data);}
        });
    }
    @Test void acceptedNotificationThrowStillExecutesTheExactCommandOnlyOnce() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var history=UndoRedoHandler.getInstance();var data=new DataSet();var count=new AtomicInteger();var command=recorded(data,count,true,false);var prefix=List.copyOf(history.getUndoCommands());
            var listener=new UndoRedoHandler.CommandQueuePreciseListener(){
                public void commandAdded(UndoRedoHandler.CommandAddedEvent event){assertSame(command,event.getCommand());assertEquals(1,count.get());assertTrue(Thread.holdsLock(history));throw new IllegalStateException("Synthetic host notification failure");}
                public void cleaned(UndoRedoHandler.CommandQueueCleanedEvent event){}
                public void commandUndone(UndoRedoHandler.CommandUndoneEvent event){}
                public void commandRedone(UndoRedoHandler.CommandRedoneEvent event){}
            };
            history.addCommandQueuePreciseListener(listener);
            try {
                assertEquals(AlignWayAction.CommandPublication.COMMITTED_NOTIFICATION_FAILED,AlignWayAction.addReviewedCommand(history,command));
                assertEquals(1,count.get());var expected=new ArrayList<>(prefix);expected.add(command);assertEquals(expected,history.getUndoCommands());assertSame(command,history.getLastCommand());
            }finally{history.removeCommandQueuePreciseListener(listener);history.clean(data);}
        });
    }
    private static org.openstreetmap.josm.command.Command recorded(DataSet data,AtomicInteger count,boolean accepted,boolean throwing){return new org.openstreetmap.josm.command.Command(data){
        public boolean executeCommand(){count.incrementAndGet();if(throwing)throw new IllegalStateException("Synthetic refused transaction");return accepted;}
        public void undoCommand(){}
        public String getDescriptionText(){return "Synthetic host history receipt";}
        public void fillModifiedData(Collection<OsmPrimitive> modified,Collection<OsmPrimitive> deleted,Collection<OsmPrimitive> added){}
    };}
    private static CandidateEvidence supportedEvidence() {
        return new CandidateEvidence("hot", 4, 4, 0, 0, 3.2, .8, .2, .9, .5, .1, List.of());
    }
    @ParameterizedTest @EnumSource(AlignmentMode.class)
    void legacyReviewApplyAndDatasetUndoRedoUseStoredGeometryWithoutAdditionalSourceWork(AlignmentMode mode) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DataSet data = new DataSet();
            data.addDataSource(new DataSource(new Bounds(-1, -1, 1, 1), "public synthetic"));
            List<Node> nodes = List.of(new Node(new LatLon(0, 0)), new Node(new LatLon(0, .0001)),
                    new Node(new LatLon(0, .0002)));
            nodes.forEach(data::addPrimitive); Way way = new Way(); way.setNodes(nodes); data.addPrimitive(way);
            nodes.forEach(node -> node.setModified(false)); way.setModified(false);
            SelectionContext selection = new SelectionContext(way, 0, 2, nodes, Set.of(nodes.get(0), nodes.get(2)));
            var config = AlignmentConfig.withoutCleanup(PluginPreferences.load().withTrackerMode(TrackerMode.LEGACY_V02)
                    .withAlignmentMode(mode));
            List<EastNorth> original = nodes.stream().map(Node::getEastNorth).toList();
            EastNorth target = original.get(1).add(0, 1);
            var finalGeometry = List.of(original.get(0), target, original.get(2));
            var candidate = new CenterlineCandidate("hot/ridge-0", 1, List.of(), List.of())
                    .withFinalPreviewPoints(finalGeometry).withEvidence(supportedEvidence());
            AtomicInteger reads = new AtomicInteger(), legacyRuns = new AtomicInteger();
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB) {
                @Override public int[] getRGB(int x, int y, int w, int h, int[] rgb, int offset, int stride) {
                    reads.incrementAndGet(); return super.getRGB(x, y, w, h, rgb, offset, stride);
                }
            };
            var result = new AlignmentResult(selection, image, List.of(candidate), original, finalGeometry,
                    List.of(new NodeMove(nodes.get(1), target)), null, null);
            var undo = UndoRedoHandler.getInstance(); int before = undo.getUndoCommands().size();
            AlignmentService service = new AlignmentService();
            String acquisitionBefore = ManagedTileRuntime.diagnosticsJsonIfInitialized();
            ModernDiagnosticCounters.begin();
            try (var owner = AlignWayAction.captureLegacyReview(data, selection, config, capture -> {
                legacyRuns.incrementAndGet(); return result;
            })) {
                owner.select(candidate);
                var command = AlignWayAction.createLegacyReviewCommand(owner,service, data, selection,
                        owner.originalResult(), owner.selectedCandidate(), config.heatmap(),null);
                assertNotNull(command); assertEquals(original, nodes.stream().map(Node::getEastNorth).toList());
                assertFalse(data.isModified()); assertEquals(before, undo.getUndoCommands().size());
                AlignWayAction.addReviewedCommand(undo,command);
                assertTrue(owner.invalidated());owner.close();
                assertEquals(before + 1, undo.getUndoCommands().size());
                assertEquals(finalGeometry, way.getNodes().stream().map(Node::getEastNorth).toList());
                if (mode == AlignmentMode.MOVE_EXISTING_NODES) assertEquals(nodes, way.getNodes());
                undo.undo(); assertEquals(nodes, way.getNodes());
                assertEquals(original, way.getNodes().stream().map(Node::getEastNorth).toList());
                assertFalse(data.isModified());
                undo.redo(); assertEquals(finalGeometry, way.getNodes().stream().map(Node::getEastNorth).toList());
                assertEquals(1, legacyRuns.get()); assertEquals(0, reads.get());
                assertEquals(acquisitionBefore, ManagedTileRuntime.diagnosticsJsonIfInitialized());
                assertTrue(ModernDiagnosticCounters.snapshot().isEmpty(), "No modern inference/scalar diagnostic work on Legacy Apply/Undo/Redo");
                undo.undo(); undo.clean(data);
            } finally { ModernDiagnosticCounters.end(); }
        });
    }
}
