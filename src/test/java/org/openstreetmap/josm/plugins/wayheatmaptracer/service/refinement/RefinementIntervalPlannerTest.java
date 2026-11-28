package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionTopologyRules;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;

class RefinementIntervalPlannerTest {
    @Test void allTrimmedRangesUseOriginalOccurrencesAndNeverExpandSelection() {
        var ranges=JunctionTopologyRules.ranges(2,18,i->Set.of(6,12).contains(i),
            i->!Set.of(2,6,12,18).contains(i));
        assertEquals(List.of(new JunctionTopologyRules.Range(2,6,3,5),
            new JunctionTopologyRules.Range(6,12,7,11),new JunctionTopologyRules.Range(12,18,13,17)),ranges);
    }
    @Test void aSafeEndToEndContinuationIsRetainedButUnknownOrBranchingTopologyIsNot() {
        var selected=new JunctionTopologyRules.Referrer(true,true,true,true);
        var continuation=new JunctionTopologyRules.Referrer(false,true,true,true);
        assertTrue(JunctionTopologyRules.safeEndpoint(List.of(selected)));
        assertTrue(JunctionTopologyRules.safeEndpoint(List.of(selected,continuation)));
        assertFalse(JunctionTopologyRules.safeEndpoint(List.of(selected,new JunctionTopologyRules.Referrer(false,true,true,false))));
        assertFalse(JunctionTopologyRules.safeEndpoint(List.of(selected,new JunctionTopologyRules.Referrer(false,true,false,true))));
        assertFalse(JunctionTopologyRules.safeEndpoint(List.of(selected,continuation,continuation)));
    }
    @Test void completeParentProducesOneEligibleWholeOriginalRange() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);
        var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
        var intervals=new RefinementIntervalPlanner().plan(parent);
        assertEquals(1,intervals.size());assertEquals(new OccurrenceRange(0,4),intervals.get(0).range());
        assertTrue(intervals.get(0).eligible());assertEquals(parent.points(),intervals.get(0).reference().orElseThrow().points());
    }
    @Test void missingRemovedOccurrenceFreezesOnlyItsDependentBoundedRange() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);
        var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
        var partial=new ParentTraceReference(root,parent.parentStage(),parent.parentChoice(),parent.nativeCandidateId(),
            parent.parentPalette(),parent.parentIdentity(),parent.points(),parent.correspondence().stream().filter(a->a.originalOccurrence()!=2).toList());
        var intervals=new RefinementIntervalPlanner().plan(partial);
        assertEquals(1,intervals.size());assertFalse(intervals.get(0).eligible());
        assertEquals(RefinementIntervalPlanner.Reason.MAPPING_UNAVAILABLE,intervals.get(0).reason());
    }
    @Test void insertedPreviewVerticesDoNotMakeTwoOriginalOccurrencesEligible() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE,false,true,false);
        var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
        var intervals=new RefinementIntervalPlanner().plan(parent);
        assertEquals(1,intervals.size());assertFalse(intervals.get(0).eligible());
        assertEquals(RefinementIntervalPlanner.Reason.TOO_FEW_ORIGINAL_OCCURRENCES,intervals.get(0).reason());
    }
    @Test void detachedMultipleJunctionsKeepEveryEligibleOriginalIntervalAndExactParentCuts() {
        var parent=capturedTopologyParent(List.of(new OccurrenceRange(0,18)),Set.of(),Set.of());
        var intervals=new RefinementIntervalPlanner().plan(parent);
        assertEquals(List.of(new OccurrenceRange(0,4),new OccurrenceRange(8,10),new OccurrenceRange(14,18)),
            intervals.stream().filter(RefinementIntervalPlanner.Interval::eligible).map(RefinementIntervalPlanner.Interval::range).toList());
        for(var interval:intervals)if(interval.eligible()) {
            assertSame(parent.root(),interval.reference().orElseThrow().root());
            assertEquals(parent.occurrencePosition(interval.range().firstIndex()),interval.reference().orElseThrow().points().get(0));
            assertTrue(interval.range().size()>2);
        }
    }
    @Test void frozenVicinityIsSubtractedRatherThanShrunkToOneAnchorOrFreezingDisjointAuthorizedWork() {
        var parent=capturedTopologyParent(List.of(new OccurrenceRange(0,3),new OccurrenceRange(8,18)),Set.of(),Set.of());
        var intervals=new RefinementIntervalPlanner().plan(parent);
        assertEquals(List.of(new OccurrenceRange(0,3),new OccurrenceRange(8,10),new OccurrenceRange(14,18)),
            intervals.stream().filter(RefinementIntervalPlanner.Interval::eligible).map(RefinementIntervalPlanner.Interval::range).toList());
        for(var interval:intervals)if(interval.eligible())assertTrue(interval.range().lastIndex()<=3||interval.range().firstIndex()>=8);
    }
    @Test void capturedFixedIslandMembershipFreezesEveryEdgeInsideTheVicinity() {
        var parent=capturedTopologyParent(List.of(new OccurrenceRange(0,18)),Set.of(),Set.of());
        var intervals=new RefinementIntervalPlanner().plan(parent);
        for(var interval:intervals)if(interval.eligible())for(int i=interval.range().firstIndex()+1;i<interval.range().lastIndex();i++)
            assertFalse(Set.of(4,5,6,7,8,10,11,12,13,14).contains(i),"fixed-island occurrences cannot become movable interval interiors");
        assertTrue(intervals.stream().anyMatch(i->i.eligible()&&i.range().equals(new OccurrenceRange(8,10))));
        assertTrue(intervals.stream().filter(i->i.range().firstIndex()>=4&&i.range().lastIndex()<=8).noneMatch(RefinementIntervalPlanner.Interval::eligible));
    }
    @Test void taggedAndRelationAnchorsBoundOnlyOtherwiseAuthorizedInteriors() {
        var parent=capturedTopologyParent(List.of(new OccurrenceRange(0,18)),Set.of(1),Set.of(17));
        var intervals=new RefinementIntervalPlanner().plan(parent);
        assertTrue(intervals.stream().anyMatch(RefinementIntervalPlanner.Interval::eligible));
        for(var interval:intervals)if(interval.eligible())for(int i:List.of(1,17))
            assertFalse(interval.range().firstIndex()<i&&i<interval.range().lastIndex());
        for(int i:List.of(1,17)) {
            var way=(DetachedWay)parent.root().network().primitives().get(parent.root().request().selectedWayKey());
            var original=parent.root().evidence().coordinateFrame().toMetric(((DetachedNode)parent.root().network().primitives().get(way.nodeKeys().get(i))).coordinate());
            assertEquals(original,parent.occurrencePosition(i));
        }
    }
    @Test void oneMissingRemovedCutDoesNotFreezeOtherDetachedJunctionlessIntervals() {
        var parent=capturedTopologyParent(List.of(new OccurrenceRange(0,18)),Set.of(),Set.of());
        var missing=new ParentTraceReference(parent.root(),parent.parentStage(),parent.parentChoice(),parent.nativeCandidateId(),parent.parentPalette(),parent.parentIdentity(),parent.points(),
            parent.correspondence().stream().filter(a->a.originalOccurrence()!=9).toList());
        var intervals=new RefinementIntervalPlanner().plan(missing);
        assertEquals(List.of(new OccurrenceRange(0,4),new OccurrenceRange(14,18)),intervals.stream().filter(RefinementIntervalPlanner.Interval::eligible).map(RefinementIntervalPlanner.Interval::range).toList());
        assertTrue(intervals.stream().anyMatch(i->i.range().equals(new OccurrenceRange(8,10))&&i.reason()==RefinementIntervalPlanner.Reason.MAPPING_UNAVAILABLE));
    }
    /** Larger public scene: existing captured 30 m island policy is explicit input, never a new planner buffer. */
    static ParentTraceReference capturedTopologyParent(List<OccurrenceRange> editableRanges,Set<Integer> tagged,Set<Integer> related) {
        return capturedTopologyParent(editableRanges,tagged,related,true);
    }
    static ParentTraceReference capturedTopologyParent(List<OccurrenceRange> editableRanges,Set<Integer> tagged,Set<Integer> related,
            boolean protectEveryIslandOccurrence) {
        var template=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var e=template.evidence();var frame=e.coordinateFrame();
        int width=401,height=101;var scalar=new double[width*height];var valid=new boolean[scalar.length];Arrays.fill(valid,true);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)scalar[y*width+x]=.02+.98*Math.exp(-.5*Math.pow((y-50.)/1.5,2));
        var region=MetricRegion.rectangle(1,1,399,99);var edit=MetricRegion.rectangle(5,40,395,65);
        var evidence=new EvidenceSnapshot("captured-island-scene",frame,e.transform(),e.resolution(),region,MetricRegion.rectangle(-.5,-.5,400.5,100.5),
            Map.of("native",new ScalarEvidenceField(width,height,scalar,valid,e.fields().get("native").lineage())),e.sourceIdentity());
        var wayKey=template.request().selectedWayKey();var values=new LinkedHashMap<PrimitiveKey,DetachedPrimitive>();var keys=new ArrayList<PrimitiveKey>();
        var fixed=new HashSet<PrimitiveKey>();var removable=new HashSet<PrimitiveKey>();
        for(int i=0;i<19;i++) {
            var key=PrimitiveKey.existing(PrimitiveKey.Type.NODE,i+1);keys.add(key);
            values.put(key,new DetachedNode(key,frame.toGeographic(new MetricPoint(20+20*i,50)),tagged.contains(i)?Map.of("barrier","gate"):Map.of(),false,false));
            if(Set.of(0,6,12,18).contains(i)||tagged.contains(i)||related.contains(i))fixed.add(key);else removable.add(key);
        }
        values.put(wayKey,new DetachedWay(wayKey,keys,Map.of("highway","path"),false,false));
        for(int i:List.of(6,12)) {
            var middle=PrimitiveKey.existing(PrimitiveKey.Type.NODE,100+i);var outside=PrimitiveKey.existing(PrimitiveKey.Type.NODE,200+i);
            values.put(middle,new DetachedNode(middle,frame.toGeographic(new MetricPoint(20+20*i,90)),Map.of(),false,false));
            values.put(outside,new DetachedNode(outside,frame.toGeographic(new MetricPoint(20+20*i,130)),Map.of(),false,false));fixed.add(middle);fixed.add(outside);
            var branch=PrimitiveKey.existing(PrimitiveKey.Type.WAY,100+i);values.put(branch,new DetachedWay(branch,List.of(keys.get(i),middle,outside),Map.of("highway","path"),false,false));
        }
        for(int i:related){var key=PrimitiveKey.existing(PrimitiveKey.Type.RELATION,300+i);values.put(key,new DetachedRelation(key,List.of(new DetachedRelationMember(keys.get(i),"via")),Map.of("type","restriction"),false,false));}
        var editable=new HashSet<>(removable);editable.add(wayKey);
        var closure=new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,"captured-island-test",values.keySet(),editable,Set.of(),fixed,removable,Map.of(wayKey,List.of(new OccurrenceRange(0,18))),List.of(),region,edit,true,true,true,true);
        var initial=new NetworkSnapshot("initial-island-source",SnapshotRole.CAPTURED_BEFORE,"captured-island-dataset",1,closure,values,V022SnapshotFixtures.closedWorldReferrerWatches(values));
        var original=keys.stream().map(k->frame.toMetric(((DetachedNode)values.get(k)).coordinate())).toList();var r=template.request();
        var specification=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture.Specification(initial.snapshotId(),initial.datasetIdentity(),initial.sourceGeneration(),wayKey,new OccurrenceRange(0,18),frame,region,edit,closure.editableWayOccurrences(),editable,Set.of(),removable,fixed,true,r.permissions()).withFixedReceiverGeometry();
        var partition=org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner.partition(initial,specification);
        if(tagged.isEmpty()&&related.isEmpty())assertEquals(List.of(new OccurrenceRange(4,8),new OccurrenceRange(10,14)),partition.fixedIslands().stream().map(org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner.FixedIsland::range).toList());
        if(protectEveryIslandOccurrence)for(var island:partition.fixedIslands())fixed.addAll(island.occurrenceKeys());
        removable.removeAll(fixed);editable.removeAll(fixed);
        closure=new ClosureDescriptor(closure.scope(),closure.queryVersion(),values.keySet(),editable,Set.of(),fixed,removable,Map.of(wayKey,editableRanges),List.copyOf(partition.provedPorts()),region,edit,true,true,true,true);
        var network=new NetworkSnapshot("captured-frozen-island-source",SnapshotRole.CAPTURED_BEFORE,initial.datasetIdentity(),initial.sourceGeneration(),closure,values,initial.incomingReferrerWatches());
        assertTrue(org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner.verifyFrozenParity(partition,network));
        r=new TraceRequest(wayKey,new OccurrenceRange(0,18),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),evidence.snapshotId(),evidence.canonicalHash(),network.snapshotId(),network.canonicalHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),3,
            new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory().profileChainage(original,3),evidence.resolution());
        var root=new RefinementRootAuthority(network,r,evidence,template.effectiveConfig(),template.projectionCode(),template.sourceIdentity(),partition);
        var points=new ArrayList<MetricPoint>();var arcs=new ArrayList<ParentArc>();
        for(int i=0;i<19;i++){var p=original.get(i);points.add(fixed.contains(keys.get(i))?p:new MetricPoint(p.xMeters(),50.5));arcs.add(new ParentArc(i,Math.min(i,17),i==18?1:0));}
        return new ParentTraceReference(root,new StageId(UUID.randomUUID()),new CandidateId("selected-A"),"selected-A","blue","selected-A",points,arcs);
    }
    static ParentTraceReference topologyParent(List<OccurrenceRange> editableRanges,Set<Integer> protectedOccurrences) {
        return topologyParent(editableRanges,protectedOccurrences,Set.of(),Set.of());
    }
    static ParentTraceReference topologyParent(List<OccurrenceRange> editableRanges,Set<Integer> protectedOccurrences,Set<Integer> tagged,Set<Integer> related) {
        var template=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);
        var frame=template.evidence().coordinateFrame();var wayKey=template.request().selectedWayKey();
        var values=new LinkedHashMap<PrimitiveKey,DetachedPrimitive>();var keys=new ArrayList<PrimitiveKey>();
        var protectedKeys=new HashSet<PrimitiveKey>();var removable=new HashSet<PrimitiveKey>();var points=new ArrayList<MetricPoint>();var arcs=new ArrayList<ParentArc>();
        var fixedOccurrences=new HashSet<>(protectedOccurrences);fixedOccurrences.addAll(tagged);fixedOccurrences.addAll(related);
        for(int i=0;i<19;i++) {
            var key=PrimitiveKey.existing(PrimitiveKey.Type.NODE,i+1);keys.add(key);
            values.put(key,new DetachedNode(key,frame.toGeographic(new MetricPoint(20+6*i,50)),tagged.contains(i)?Map.of("barrier","gate"):Map.of(),false,false));
            if(fixedOccurrences.contains(i))protectedKeys.add(key);else removable.add(key);
            points.add(new MetricPoint(20+6*i,fixedOccurrences.contains(i)?50:53));
            arcs.add(new ParentArc(i,Math.min(i,17),i==18?1:0));
        }
        values.put(wayKey,new DetachedWay(wayKey,keys,Map.of("highway","path"),false,false));
        for(int index:List.of(6,12)) {
            var extra=PrimitiveKey.existing(PrimitiveKey.Type.NODE,100+index);
            values.put(extra,new DetachedNode(extra,frame.toGeographic(new MetricPoint(20+6*index,60)),Map.of(),false,false));
            var branch=PrimitiveKey.existing(PrimitiveKey.Type.WAY,100+index);
            values.put(branch,new DetachedWay(branch,List.of(keys.get(index),extra),Map.of(),false,false));
        }
        for(int index:related) {
            var relation=PrimitiveKey.existing(PrimitiveKey.Type.RELATION,200+index);
            values.put(relation,new DetachedRelation(relation,List.of(new DetachedRelationMember(keys.get(index),"via")),Map.of("type","restriction"),false,false));
        }
        var editKeys=new HashSet<>(removable);editKeys.add(wayKey);var old=template.network().closure();
        var closure=new ClosureDescriptor(old.scope(),old.queryVersion(),values.keySet(),editKeys,Set.of(),protectedKeys,removable,
            Map.of(wayKey,editableRanges),List.of(),old.collisionEnvelope(),old.editRegion(),true,true,true,true);
        var network=new NetworkSnapshot("junction-network",SnapshotRole.CAPTURED_BEFORE,"synthetic",1,closure,values,V022SnapshotFixtures.closedWorldReferrerWatches(values));
        var request=template.request();var original=keys.stream().map(k->frame.toMetric(((DetachedNode)values.get(k)).coordinate())).toList();
        request=new TraceRequest(wayKey,new OccurrenceRange(0,18),request.engine(),request.geometryMode(),request.permissions(),request.budgets(),
            request.evidenceSnapshotId(),request.evidenceContentHash(),network.snapshotId(),network.canonicalHash(),request.settingsHash(),request.parameterHash(),request.samplerId(),
            request.configuredSampleStepMeters(),new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory().profileChainage(original,3),request.evidenceResolution());
        var root=new RefinementRootAuthority(network,request,template.evidence(),template.effectiveConfig(),template.projectionCode(),template.sourceIdentity());
        return new ParentTraceReference(root,new StageId(UUID.randomUUID()),new CandidateId("selected-A"),"selected-A","blue","selected-A",points,arcs);
    }
}
