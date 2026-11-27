package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
class RefinementSourcePlannerTest {
 static RefinementSourcePlanner.Settings settings(boolean managed,IntensitySamplingMode mode,boolean aggregate){
  return new RefinementSourcePlanner.Settings(managed,"all","blue",15,15,new ManagedTileGeneration(9),mode,List.of("hot-corridor"),aggregate);
 }
 static ParentTraceReference parent(){return parent(false);}
 static ParentTraceReference parent(boolean aggregate){return parent(aggregate,IntensitySamplingMode.COLOR_MAPPING);}
 static ParentTraceReference parent(boolean aggregate,IntensitySamplingMode mode){
  var original=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var c=original.effectiveConfig().heatmap();
  var source=new ManagedHeatmapConfig("","","","","all","blue",c.manualLayerName(),c.layerRegex(),c.alignmentMode(),c.trackerMode(),c.verbose(),c.debug(),true,aggregate,c.showAggregateIntensityLayer(),c.candidateRatingEnabled(),c.parallelWayAwareness(),c.allowUndownloadedAlignment(),c.adjustJunctionNodes(),c.simplifyEnabled(),c.crossSectionHalfWidthPx(),c.crossSectionStepPx(),c.simplifyTolerancePx(),c.inferenceMode(),15,15,8,3,mode,9);
  var root=new RefinementRootAuthority(original.network(),original.request(),original.evidence(),new AlignmentConfig(source,original.effectiveConfig().cleanup()),original.projectionCode(),original.sourceIdentity());
  return new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"purple"));
 }
 static OperationTicket ticket(ParentTraceReference p){return new OperationTicket(new SessionId(UUID.randomUUID()),1,p.parentStage(),p.parentChoice(),TrackerMode.PROBABILISTIC,AlignmentMode.PRECISE_SHAPE);}
 @Test void enabledAlternativeAndAggregateDoNotExpandTheSelectedDetector() {
  var parent=parent(true);
  var plan=RefinementSourcePlanner.plan(ticket(parent),settings(true,IntensitySamplingMode.COLOR_MAPPING,true),parent);
  assertEquals(List.of("purple"),plan.acquiredPalettes());
  assertEquals(List.of(new RefinementSourcePlanner.SourceRequest("purple","purple",false,"")),plan.requests());
  assertFalse(plan.aggregateRequested());
  assertThrows(UnsupportedOperationException.class,()->plan.acquiredPalettes().clear());
 }
 @Test void visibleRefusesTheSelectedDetectorIfItsRasterIsUnavailable() {
  var parent=parent();
  var plan=RefinementSourcePlanner.plan(ticket(parent),settings(false,IntensitySamplingMode.COLOR_MAPPING,true),parent);
  assertTrue(plan.acquiredPalettes().isEmpty());
  assertEquals(List.of(new RefinementSourcePlanner.SourceRequest("purple","purple",false,"visible-palette-unavailable")),plan.requests());
 }
 @Test void managedDirectRunsOnlyTheSelectedRasterWithItsFrozenMode() {
  var parent=parent();
  for(var mode:List.of(IntensitySamplingMode.DIRECT_ALPHA,IntensitySamplingMode.DIRECT_LUMINANCE,IntensitySamplingMode.DIRECT_VALUE)) {
   var plan=RefinementSourcePlanner.plan(ticket(parent),settings(true,mode,true),parent);
   assertEquals(List.of("purple"),plan.acquiredPalettes());
   assertEquals(1,plan.requests().size());
   assertTrue(plan.requests().get(0).unavailableReason().isEmpty());
   assertFalse(plan.aggregateRequested());
   assertEquals(mode,plan.settings().intensity());
  }
 }
 @Test void aggregateRemainsOneDetectorUsingAllFiveInputs() {
  var parent=parent(true);
  var source=new RefinementSourcePlanner.SourceRequest("purple","all-colors-combined",true,"");
  var selected=new ParentTraceReference(parent.root(),parent.parentStage(),parent.parentChoice(),
          parent.nativeCandidateId(),source,parent.parentIdentity(),parent.points(),parent.correspondence());
  var plan=RefinementSourcePlanner.plan(ticket(selected),settings(true,IntensitySamplingMode.COLOR_MAPPING,true),selected);
  assertEquals(List.of(source),plan.requests());
  assertTrue(plan.aggregateRequested());
  assertEquals("purple",plan.acquiredPalettes().get(0));
  assertEquals(Set.copyOf(RefinementSourcePlanner.BASE_PALETTES),Set.copyOf(plan.acquiredPalettes()));
  assertEquals(source,selected.slice(new OccurrenceRange(1,3)).selectedSource());
 }
 @Test void rootSourcePolicyCannotChangeGenerationIntensityOrPalette(){var p=parent();assertDoesNotThrow(()->settings(true,IntensitySamplingMode.COLOR_MAPPING,false).requireRootPolicy(p.root()));assertThrows(IllegalArgumentException.class,()->settings(true,IntensitySamplingMode.DIRECT_ALPHA,false).requireRootPolicy(p.root()));}
 @Test void smoothingUsesExistingEvidenceWithoutNewFivePaletteAcquisition(){var p=parent();var t=ticket(p);var s=new SmoothingInput(t.parent(),t.choice(),t.parent(),t.choice(),"Medium");var smooth=new OperationTicket(t.session(),t.sequence(),t.parent(),t.choice(),TrackerMode.LEGACY_V02,t.mode(),StageKind.SMOOTH,Optional.of(s));var plan=RefinementSourcePlanner.plan(smooth,settings(true,IntensitySamplingMode.COLOR_MAPPING,true),p);assertTrue(plan.acquiredPalettes().isEmpty());assertTrue(plan.requests().stream().allMatch(r->r.unavailableReason().equals("existing-parent-evidence-required")));}
}
