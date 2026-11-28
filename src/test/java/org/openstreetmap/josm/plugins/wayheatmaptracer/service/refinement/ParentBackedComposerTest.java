package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class ParentBackedComposerTest {
    static ParentTraceReference parent(AlignmentMode mode) {
        var root=RefinementParentFixtures.authority(mode);
        return new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
    }
    static ParentBackedComposer.Choice choice(ParentTraceReference parent,OccurrenceRange range,double y) {
        var reference=parent.slice(range);var points=new ArrayList<MetricPoint>();var ids=new ArrayList<FinalRoutePointId>();
        var arcs=new ArrayList<ParentArc>();var way=(DetachedWay)parent.root().network().primitives().get(parent.root().request().selectedWayKey());
        for(int i=range.firstIndex();i<=range.lastIndex();i++) {
            var p=parent.occurrencePosition(i);points.add(i==range.firstIndex()||i==range.lastIndex()?p:new MetricPoint(p.xMeters(),y));
            ids.add(parent.root().request().geometryMode()==AlignmentMode.MOVE_EXISTING_NODES
                ?new FinalRoutePointId.ExistingWayNodeOccurrence(way.key(),way.nodeKeys().get(i),i)
                :new FinalRoutePointId.GeneratedCandidatePoint("native-child",i-range.firstIndex()));
            arcs.add(new ParentArc(i,Math.min(i-range.firstIndex(),range.size()-2),i==range.lastIndex()?1:0));
        }
        return new ParentBackedComposer.Choice(reference,points,ids,arcs,"native-child","blue");
    }
    @Test void failedIntervalsRetainSelectedAParentRatherThanOriginalOsm() {
        var parent=parent(AlignmentMode.PRECISE_SHAPE);var composer=new ParentBackedComposer();
        var unchanged=composer.compose("unchanged",parent,List.of());assertEquals(parent.points(),unchanged.points());
        var child=composer.compose("child",parent,List.of(choice(parent,new OccurrenceRange(1,3),55)));
        assertEquals(parent.points().get(0),child.points().get(0));assertEquals(parent.points().get(1),child.points().get(1));
        assertEquals(55,child.points().get(2).yMeters());assertEquals(parent.points().get(3),child.points().get(3));
        assertEquals(parent.points().get(4),child.points().get(4));assertEquals(5,child.correspondence().size());
        assertTrue(child.spans().stream().anyMatch(s->s.parentRetained()));
    }
    @Test void removedPreciseCutsSubdivideExactParentEdgesAndKeepLegacyAbsentAssignmentMapsUsable() {
        var source=parent(AlignmentMode.PRECISE_SHAPE);
        var points=List.of(source.points().get(0),source.points().get(2),source.points().get(4));
        var parent=new ParentTraceReference(source.root(),source.parentStage(),source.parentChoice(),source.nativeCandidateId(),source.parentPalette(),source.parentIdentity(),points,
            List.of(new ParentArc(0,0,0),new ParentArc(1,0,.5),new ParentArc(2,1,0),new ParentArc(3,1,.5),new ParentArc(4,1,1)));
        var result=new ParentBackedComposer().compose("removed",parent,List.of(choice(parent,new OccurrenceRange(1,3),55)));
        assertEquals(points.get(0),result.points().get(0));assertEquals(parent.occurrencePosition(1),result.points().get(1));
        assertEquals(parent.occurrencePosition(3),result.points().get(3));assertEquals(points.get(2),result.points().get(4));
        assertEquals(5,result.correspondence().size());
        assertTrue(result.pointIds().subList(1,4).stream().allMatch(FinalRoutePointId.GeneratedCandidatePoint.class::isInstance));
    }
    @Test void movePreservesEveryOriginalOrderedIdAndCandidateOwnedAssignment() {
        var parent=parent(AlignmentMode.MOVE_EXISTING_NODES);
        var result=new ParentBackedComposer().compose("move",parent,List.of(choice(parent,new OccurrenceRange(1,3),54)));
        assertEquals(List.of(0,1,2,3,4),result.pointIds().stream().map(i->((FinalRoutePointId.ExistingWayNodeOccurrence)i).originalOccurrenceIndex()).toList());
        for(int i=0;i<5;i++)assertEquals(result.points().get(i),result.assignments().get(result.pointIds().get(i)));
    }
    @Test void retainedPreciseFractionIsNotRecomputedWhenItsWholeEdgeSurvives() {
        var source=parent(AlignmentMode.PRECISE_SHAPE);
        var fractional=new ParentArc(1,1,0.12345678901234567);
        var parent=new ParentTraceReference(source.root(),source.parentStage(),source.parentChoice(),
                source.nativeCandidateId(),source.selectedSource(),source.parentIdentity(),source.points(),
                List.of(new ParentArc(0,0,0),fractional,new ParentArc(2,2,0),
                        new ParentArc(3,3,0),new ParentArc(4,3,1)));
        var result=new ParentBackedComposer().compose("exact-retained-cut",parent,
                List.of(choice(parent,new OccurrenceRange(2,4),55)));
        assertEquals(fractional,result.correspondence().stream().filter(arc->arc.originalOccurrence()==1)
                .findFirst().orElseThrow());
    }
    @Test void frozenPreciseSpanPreservesGeometryAndMandatoryIdentitiesWhenParentHasRemovableOccurrences() {
        var parent=RefinementIntervalPlannerTest.capturedTopologyParent(List.of(new OccurrenceRange(0,18)),Set.of(),Set.of(),false);
        var composer=new ParentBackedComposer();
        var result=composer.compose("retained-island",parent,List.of(choice(parent,new OccurrenceRange(0,4),51)));
        var original=(DetachedWay)parent.root().network().primitives().get(parent.root().request().selectedWayKey());
        var anchor=new FinalRoutePointId.ExistingWayNodeOccurrence(original.key(),original.nodeKeys().get(6),6);
        assertEquals(parent.occurrencePosition(6),result.assignments().get(anchor));
        var removable=new FinalRoutePointId.ExistingWayNodeOccurrence(original.key(),original.nodeKeys().get(5),5);
        assertFalse(result.assignments().containsKey(removable));
        assertDoesNotThrow(()->ParentBackedComposer.requireFrozenGeometryPreserved(parent,result));
        var ids=new ArrayList<>(result.pointIds());int index=ids.indexOf(anchor);
        var forged=new FinalRoutePointId.GeneratedCandidatePoint(result.id(),index);
        ids.set(index,forged);
        var assignments=new LinkedHashMap<>(result.assignments());assignments.put(forged,assignments.remove(anchor));
        var missingMandatory=new ParentBackedComposer.Composed(result.id(),result.points(),ids,assignments,result.correspondence(),result.spans());
        assertThrows(IllegalArgumentException.class,()->ParentBackedComposer.requireFrozenGeometryPreserved(parent,missingMandatory));
    }
    @Test void completeFinalAssessmentCountsRetainedLengthsOnceAndBlocksFinalSelfCrossing() {
        var parent=parent(AlignmentMode.PRECISE_SHAPE);var composer=new ParentBackedComposer();
        var assembled=composer.compose("physical",parent,List.of(choice(parent,new OccurrenceRange(1,3),54)));
        var assessed=composer.assess(parent,assembled,parent.root().evidence(),"native",CancellationProbe.NONE);
        double total=0;for(int i=1;i<assembled.points().size();i++)total+=assembled.points().get(i-1).distanceTo(assembled.points().get(i));
        assertEquals(total,assessed.quality().totalLengthMeters(),1e-7);
        var cross=List.of(parent.points().get(0),new MetricPoint(100,60),new MetricPoint(40,60),new MetricPoint(100,45),parent.points().get(4));
        var ids=new ArrayList<FinalRoutePointId>();for(int i=0;i<cross.size();i++)ids.add(new FinalRoutePointId.GeneratedCandidatePoint("cross",i));
        var bad=composer.compose("cross",parent,List.of(new ParentBackedComposer.Choice(parent,cross,ids,parent.correspondence(),"cross","blue")));
        assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,composer.assess(parent,bad,parent.root().evidence(),"native",CancellationProbe.NONE).quality().disposition());
    }
    @Test void overlappingIntervalsAndMovedParentCutsFailClosed() {
        var parent=parent(AlignmentMode.PRECISE_SHAPE);var c=choice(parent,new OccurrenceRange(1,3),54);
        assertThrows(IllegalArgumentException.class,()->new ParentBackedComposer().compose("overlap",parent,List.of(c,c)));
        var moved=new ArrayList<>(c.points());moved.set(0,new MetricPoint(50,54));
        var bad=new ParentBackedComposer.Choice(c.reference(),moved,c.pointIds(),c.correspondence(),c.nativeId(),c.palette());
        assertThrows(IllegalArgumentException.class,()->new ParentBackedComposer().compose("moved",parent,List.of(bad)));
    }
    @Test void actualNativeFinalizedGeometryReassemblesWithoutLosingOriginalFixedCoordinateAuthority() {
        var parent=parent(AlignmentMode.MOVE_EXISTING_NODES);
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,parent.root().evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
            var route=run.finalized().orElseThrow().routes().get(0);
            assertEquals(parent.points(),run.reference().points(),"native entry retained the selected parent curve");
            assertEquals(parent.points(),parent.slice(parent.originalRange()).points(),"whole-range slicing is exact");
            assertEquals(parent.occurrencePosition(0),route.hypothesis().points().get(0),"canonical parent and immutable original anchor must have one exact coordinate");
            var choice=new ParentBackedComposer.Choice(run.reference(),route.hypothesis().points(),route.pointIds(),run.finalCorrespondence().get(route.hypothesis().id()),route.hypothesis().id(),"blue");
            var composed=new ParentBackedComposer().compose("actual",parent,List.of(choice));
            assertEquals(parent.frozenAnchors().get(0),composed.points().get(0));
            assertEquals(parent.frozenAnchors().get(4),composed.points().get(composed.points().size()-1));
        }
    }
    @Test void actualPreciseCopyAssessmentUsesTheAuthorizedDestinationAndPreservesReadonlyOriginal() {
        var parent=copyParent();var root=parent.root();var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
        var originalKey=way.nodeKeys().get(4);var original=root.network().primitives().get(originalKey);var receiverKey=PrimitiveKey.existing(PrimitiveKey.Type.WAY,20);
        var expected=SharedNodeDisconnectionPlanner.forRequest(root.request(),root.network()).replacements().get(4);
        for(var engine:List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC))try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var run=new RefinementEngineAdapter(engine,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
            assertEquals(1,run.nativeCalls(),"actual qualified whole copy must reach "+engine+"; refusal="+run.unavailable());
            var route=run.finalized().orElseThrow().routes().get(0);
            var logical=new FinalRoutePointId.ExistingWayNodeOccurrence(way.key(),originalKey,4);
            assertTrue(route.pointIds().contains(logical),"native identity is logical source provenance, not permission to move original");
            var plan=new ModernSingleWayEditPlanAdapter().adaptRefined(run,root.evidence(),0);
            assertEquals(original,plan.after().primitives().get(originalKey));
            assertEquals(root.network().primitives().get(receiverKey),plan.after().primitives().get(receiverKey));
            assertFalse(((DetachedWay)plan.after().primitives().get(way.key())).nodeKeys().contains(originalKey));
            var choice=new ParentBackedComposer.Choice(run.reference(),route.hypothesis().points(),route.pointIds(),run.finalCorrespondence().get(route.hypothesis().id()),route.hypothesis().id(),"hot");
            var composer=new ParentBackedComposer();var composed=composer.compose("copy-"+engine,parent,List.of(choice));
            var assessed=composer.assess(parent,composed,root.evidence(),"native",CancellationProbe.NONE);var copy=assessed.copies().get(0);
            assertEquals(expected,copy.destinationKey(),"assessment must clone, never reconnect to the global protected node");
            assertEquals(original,copy.retainedOriginal().orElseThrow());assertNotEquals(originalKey,copy.destination().orElseThrow().key());
            assertEquals(plan.after().primitives().get(expected),copy.destination().orElseThrow());
            assertEquals(root.network().primitives().get(receiverKey),copy.contextWays().get(receiverKey));
            assertEquals(ModernSingleWayEditPlanAdapter.assessFinalTopology(plan,root.evidence(),root.request().permissions().ordinaryRadiusMeters()).findingCodes(),assessed.topologyFindings());
            assertNotEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Disposition.HARD_BLOCKED,assessed.quality().disposition());
            var ticket=RefinementPipelineTest.ticket(parent,engine);
            var result=new RefinementPipeline().run(ticket,parent,RefinementPipelineTest.plan(ticket,parent,false),
                new RefinementPipeline.Adapters(RefinementPipelineTest.sources(parent),RefinementPipeline.productionRunner(),owner),
                RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertFalse(result.candidates().isEmpty(),"actual copy pipeline must retain its fully assessed whole candidate");
            var pipelineCopy=result.candidates().get(0).assessed().copies().get(0);
            assertEquals(expected,pipelineCopy.destinationKey());assertEquals(original,pipelineCopy.retainedOriginal().orElseThrow());
        }
    }
    static ParentTraceReference copyParent() {
        var template=RefinementPipelineTest.straightScene(parent(AlignmentMode.PRECISE_SHAPE)).root();var e=template.evidence();var frame=e.coordinateFrame();var wayKey=template.request().selectedWayKey();
        var values=new LinkedHashMap<PrimitiveKey,DetachedPrimitive>();var keys=new ArrayList<PrimitiveKey>();var fixed=new HashSet<PrimitiveKey>();var removable=new HashSet<PrimitiveKey>();
        for(int i=0;i<9;i++) {
            var key=PrimitiveKey.existing(PrimitiveKey.Type.NODE,i+1);keys.add(key);values.put(key,new DetachedNode(key,frame.toGeographic(new MetricPoint(20+15*i,50)),Map.of(),false,false));
            if(Set.of(0,4,8).contains(i))fixed.add(key);else removable.add(key);
        }
        values.put(wayKey,new DetachedWay(wayKey,keys,Map.of("highway","path"),false,false));
        var outside=PrimitiveKey.existing(PrimitiveKey.Type.NODE,99);var receiver=PrimitiveKey.existing(PrimitiveKey.Type.WAY,20);fixed.add(outside);
        values.put(outside,new DetachedNode(outside,frame.toGeographic(new MetricPoint(80,40)),Map.of(),false,false));
        values.put(receiver,new DetachedWay(receiver,List.of(keys.get(4),outside),Map.of("boundary","administrative"),false,false));
        var editable=new HashSet<>(removable);editable.add(wayKey);var old=template.network().closure();var range=new OccurrenceRange(0,8);
        var closure=new ClosureDescriptor(old.scope(),"copy-assessment-test",values.keySet(),editable,Set.of(),fixed,removable,Map.of(wayKey,List.of(range)),List.of(),old.collisionEnvelope(),old.editRegion(),true,true,true,true);
        var network=new NetworkSnapshot("copy-original",SnapshotRole.CAPTURED_BEFORE,"copy-dataset",1,closure,values,V022SnapshotFixtures.closedWorldReferrerWatches(values));
        var intent=new SharedNodeDisconnectionIntent(wayKey,range,Map.of(4,keys.get(4)),network.canonicalHash());
        var permissions=new RecoveryPermissions(false,8,8,JunctionPolicy.FIXED,false,intent);var r=template.request();
        var source=keys.stream().map(k->frame.toMetric(((DetachedNode)values.get(k)).coordinate())).toList();
        r=new TraceRequest(wayKey,range,r.engine(),r.geometryMode(),permissions,r.budgets(),e.snapshotId(),e.canonicalHash(),network.snapshotId(),network.canonicalHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),3,
            new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory().profileChainage(source,3),e.resolution(),Optional.empty(),network);
        var specification=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture.Specification(network.snapshotId(),network.datasetIdentity(),network.sourceGeneration(),wayKey,range,frame,closure.collisionEnvelope(),closure.editRegion(),closure.editableWayOccurrences(),editable,Set.of(),removable,fixed,true,permissions).withDisconnectionSource(network);
        var partition=org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner.partition(network,specification);assertTrue(partition.fixedIslands().isEmpty());
        var root=new RefinementRootAuthority(network,r,e,template.effectiveConfig(),template.projectionCode(),template.sourceIdentity(),partition);
        var points=new ArrayList<MetricPoint>();var arcs=new ArrayList<ParentArc>();
        for(int i=0;i<9;i++){var p=source.get(i);points.add(i==4?new MetricPoint(p.xMeters(),51):fixed.contains(keys.get(i))?p:new MetricPoint(p.xMeters(),50.5));arcs.add(new ParentArc(i,Math.min(i,7),i==8?1:0));}
        return new ParentTraceReference(root,new StageId(UUID.randomUUID()),new CandidateId("copied-parent"),"copied-parent","hot","copied-parent",points,arcs);
    }
}
