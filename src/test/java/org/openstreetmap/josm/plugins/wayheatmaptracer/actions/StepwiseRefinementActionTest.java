package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Action capture-owner seam only; actual native pipeline/GUI/Apply integration has separate gates. */
class StepwiseRefinementActionTest {
    @BeforeAll static void setup() {
        var preferences=new org.openstreetmap.josm.data.Preferences();preferences.enableSaveOnPut(false);Config.setPreferencesInstance(preferences);
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }
    @Test void ordinaryLegacyOwnerRunsOriginalActionOnceWithoutReadingRetainedRaster() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DataSet data = new DataSet();
            Node start = new Node(new LatLon(0, 0)), end = new Node(new LatLon(0, .0001));
            data.addPrimitive(start); data.addPrimitive(end);
            Way way = new Way(); way.setNodes(List.of(start, end)); data.addPrimitive(way);
            SelectionContext selection = new SelectionContext(way, 0, 1, way.getNodes(), Set.of(start, end));
            var config = AlignmentConfig.withoutCleanup(PluginPreferences.load().withTrackerMode(TrackerMode.LEGACY_V02));
            var geometry = way.getNodes().stream().map(Node::getEastNorth).toList();
            AtomicInteger reads = new AtomicInteger(), legacyRuns = new AtomicInteger();
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB) {
                @Override public int[] getRGB(int x, int y, int w, int h, int[] rgb, int offset, int stride) {
                    reads.incrementAndGet(); return super.getRGB(x, y, w, h, rgb, offset, stride);
                }
            };
            CenterlineCandidate candidate = new CenterlineCandidate("legacy-blue", 1, List.of(), List.of()).withFinalPreviewPoints(geometry);
            CenterlineCandidate alternative = new CenterlineCandidate("legacy-blue", .8, List.of(), List.of()).withFinalPreviewPoints(geometry);
            AlignmentResult result = new AlignmentResult(selection, image, List.of(candidate, alternative), geometry,
                    geometry, List.of(), null, null);
            try (var owner = AlignWayAction.captureLegacyReview(data, selection, config, capture -> {
                legacyRuns.incrementAndGet();
                return result;
            })) {
                assertSame(result, owner.originalResult()); owner.select(candidate);
                owner.requireOriginalAttemptCurrent();
                var controls = AlignWayAction.createLegacyRefinementControls(owner, owner::select, () -> "Original Legacy findings");
                JComboBox<?> choices = (JComboBox<?>) find(controls, "candidate");
                assertEquals(2, choices.getItemCount()); assertTrue(choices.isEnabled());
                assertNotEquals(((RefinementControls.Choice) choices.getItemAt(0)).id(),
                        ((RefinementControls.Choice) choices.getItemAt(1)).id(), "Native IDs are not stage choice IDs");
                choices.setSelectedIndex(1);
                assertSame(alternative, owner.selectedCandidate());
                assertEquals(choices.getItemAt(1), choices.getSelectedItem());
                assertEquals("Original Legacy findings", ((JTextArea) find(controls, "quality")).getText());
                for (String name : List.of("undo", "redo", "refineA", "refineB", "smooth")) {
                    JComponent button = (JComponent) find(controls, name);
                    assertFalse(button.isEnabled(), name); assertNotNull(button.getToolTipText(), name);
                }
                assertTrue(find(controls, "stage") instanceof JLabel);
                assertEquals(1, legacyRuns.get()); assertEquals(0, reads.get());
            }
        });
    }
    @Test void liveConfirmationRefreshKeepsConfirmedLegacyApplyEnabled() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var fixture=reviewFixture();
            try(var owner=AlignWayAction.captureLegacyReview(fixture.data,fixture.selection,fixture.config,capture->fixture.result)) {
                owner.select(fixture.candidate);var token=new java.util.concurrent.atomic.AtomicReference<CandidateReviewConfirmation>();
                var apply=new JButton("Apply");var confirm=new JButton("Confirm reviewed candidate");
                Runnable refresh=AlignWayAction.createReviewButtonRefresh(owner,apply,confirm,()->new AlignWayAction.ReviewButtonContext(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService.assessCandidate(fixture.candidate).disposition(),
                    token.get()!=null&&token.get().matches(fixture.candidate,fixture.result.previewPolyline()),false,false,false,true));
                confirm.addActionListener(event->{token.set(AlignWayAction.confirmLegacyReview(owner,new org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService(),
                    fixture.data,fixture.selection,fixture.result,fixture.candidate,fixture.config.heatmap()));apply.setEnabled(true);confirm.setEnabled(false);refresh.run();});
                refresh.run();assertFalse(apply.isEnabled());assertTrue(confirm.isEnabled());confirm.doClick();
                assertTrue(apply.isEnabled(),"Actual confirmation followed by workflow refresh must remain applicable");assertFalse(confirm.isEnabled());
                token.set(null);refresh.run();assertFalse(apply.isEnabled());assertTrue(confirm.isEnabled(),"Cleared exact confirmation becomes confirmable again");
            }
        });
    }
    @Test void liveBusyThenCancelledReviewParentRestoresConfirmationControl() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var fixture=reviewFixture();try(var owner=AlignWayAction.captureLegacyReview(fixture.data,fixture.selection,fixture.config,capture->fixture.result)) {
                owner.select(fixture.candidate);var busy=new java.util.concurrent.atomic.AtomicBoolean();var apply=new JButton("Apply");var confirm=new JButton("Confirm");
                Runnable refresh=AlignWayAction.createReviewButtonRefresh(owner,apply,confirm,()->new AlignWayAction.ReviewButtonContext(CandidateAssessment.Disposition.REVIEW_REQUIRED,false,busy.get(),true,false,true));
                refresh.run();assertTrue(confirm.isEnabled());busy.set(true);refresh.run();assertFalse(apply.isEnabled());assertFalse(confirm.isEnabled());
                busy.set(false);refresh.run();assertFalse(apply.isEnabled());assertTrue(confirm.isEnabled(),"Cancelled/refused operation must restore retained-parent confirmation");
            }
        });
    }
    @Test void liveLegacyBoundariesRejectRestoredMaterialSettings() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var fixture=reviewFixture();try(var owner=AlignWayAction.captureLegacyReview(fixture.data,fixture.selection,fixture.config,capture->fixture.result)) {
                owner.select(fixture.candidate);var service=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService();
                var confirmation=AlignWayAction.confirmLegacyReview(owner,service,fixture.data,fixture.selection,fixture.result,fixture.candidate,fixture.config.heatmap());
                String activity=Config.getPref().get("wayheatmaptracer.activity","all");Config.getPref().put("wayheatmaptracer.activity","ride");Config.getPref().put("wayheatmaptracer.activity",activity);
                assertTrue(owner.invalidated());
                assertAll("Actual Legacy confirmation and command boundaries",
                    ()->assertThrows(IllegalStateException.class,()->AlignWayAction.confirmLegacyReview(owner,service,fixture.data,fixture.selection,fixture.result,fixture.candidate,fixture.config.heatmap())),
                    ()->assertThrows(IllegalStateException.class,()->AlignWayAction.createLegacyReviewCommand(owner,service,fixture.data,fixture.selection,fixture.result,fixture.candidate,fixture.config.heatmap(),confirmation)));
            }
        });
    }
    @Test void liveLegacyRefreshDisablesStaleAndSupersededAuthority() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var fixture=reviewFixture();try(var owner=AlignWayAction.captureLegacyReview(fixture.data,fixture.selection,fixture.config,capture->fixture.result)) {
                owner.select(fixture.candidate);var apply=new JButton("Apply");var confirm=new JButton("Confirm");
                Runnable refresh=AlignWayAction.createReviewButtonRefresh(owner,apply,confirm,()->new AlignWayAction.ReviewButtonContext(CandidateAssessment.Disposition.APPLICABLE,false,false,false,false,true));
                refresh.run();assertTrue(apply.isEnabled());String activity=Config.getPref().get("wayheatmaptracer.activity","all");
                Config.getPref().put("wayheatmaptracer.activity","ride");Config.getPref().put("wayheatmaptracer.activity",activity);
                refresh.run();assertFalse(apply.isEnabled(),"Known original settings invalidation must disable live Legacy Apply");assertFalse(confirm.isEnabled());
            }
        });
    }
    private record ReviewFixture(org.openstreetmap.josm.data.osm.DataSet data,SelectionContext selection,AlignmentConfig config,AlignmentResult result,CenterlineCandidate candidate){ }
    private static ReviewFixture reviewFixture() {
        var data=new DataSet();data.addDataSource(new org.openstreetmap.josm.data.DataSource(new org.openstreetmap.josm.data.Bounds(-1,-1,1,1),"public synthetic"));
        var nodes=List.of(new Node(new LatLon(0,0)),new Node(new LatLon(0,.0001)),new Node(new LatLon(0,.0002)));nodes.forEach(data::addPrimitive);
        var way=new Way();way.setNodes(nodes);data.addPrimitive(way);data.allPrimitives().forEach(p->p.setModified(false));
        var selection=new SelectionContext(way,0,2,nodes,Set.of(nodes.get(0),nodes.get(2)));
        var config=AlignmentConfig.withoutCleanup(PluginPreferences.load().withTrackerMode(TrackerMode.LEGACY_V02).withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES));
        var source=nodes.stream().map(Node::getEastNorth).toList();var target=source.get(1).add(0,1);var preview=List.of(source.get(0),target,source.get(2));
        var evidence=new CandidateEvidence("hot",4,4,0,0,3.2,.8,.2,.9,.5,.1,List.of()).withCorridorCoverage(new CorridorCoverage(true,false,4,4,1,0,3,0,0,0,0,0,false,"incomplete-longitudinal-corridor"));
        var candidate=new CenterlineCandidate("hot/review-parent",1,List.of(),List.of()).withFinalPreviewPoints(preview).withEvidence(evidence);
        var result=new AlignmentResult(selection,null,List.of(candidate),source,preview,List.of(new NodeMove(nodes.get(1),target)),null,null);
        return new ReviewFixture(data,selection,config,result,candidate);
    }
    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                Component found = find(nested, name); if (found != null) return found;
            }
        }
        return null;
    }
}
