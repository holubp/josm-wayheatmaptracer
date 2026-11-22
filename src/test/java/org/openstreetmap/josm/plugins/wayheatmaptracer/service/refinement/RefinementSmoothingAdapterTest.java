package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class RefinementSmoothingAdapterTest {
    static SmoothingModels.Parent parent(AlignmentMode mode,AttemptMemoryLedger.Owner owner,GeometryWorkContext work,Runnable currentness) {
        var root=RefinementParentFixtures.authority(mode);var candidate=RefinementParentFixtures.parent(root,"blue");
        var stage=new StageId(UUID.randomUUID());
        var input=new SmoothingInput(stage,candidate.id(),stage,candidate.id(),"Medium");
        var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,stage,candidate.id(),TrackerMode.LEGACY_V02,mode,StageKind.SMOOTH,Optional.of(input));
        return new SmoothingParentBinder().bind(root,ticket,candidate,SmoothingParentBinderTest.source(root,"blue",owner,currentness),work);
    }
    static GeometryWorkContext work(AttemptMemoryLedger.Owner owner) {return new GeometryWorkContext(20_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);}
    @Test void bothStrengthsChangeTheSelectedNonTopLegacyParentAndBuildActualPlansInBothModes() {
        for(var mode:List.of(AlignmentMode.PRECISE_SHAPE,AlignmentMode.MOVE_EXISTING_NODES))try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner);var parent=parent(mode,owner,work,()->{});var before=parent.reference().root().network();
            assertTrue(parent.nativeBasis().isEmpty());assertEquals("blue-native",parent.reference().nativeCandidateId());
            for(var strength:SmoothingModels.Preset.values()) {
                long started=work.visits();
                try(var result=new RefinementSmoothingAdapter().smooth(parent,strength,work,CancellationProbe.NONE)) {
                assertEquals(RefinementSmoothingAdapter.Outcome.CHANGED,result.receipt().outcome(),mode+" "+strength);
                assertNotEquals(parent.reference().points(),result.points());
                var plan=result.plan().orElseThrow();assertSame(before,plan.before());assertEquals(mode,parent.ticket().mode());
                assertEquals(parent.frame().inputHash(),result.receipt().inputHash());
                assertEquals(parent.evidence().transform(),result.receipt().reference().transform());
                assertEquals("postprocessing-final-slots-v1",result.receipt().representation());
                assertTrue(result.receipt().workVisits()>0);assertEquals(before,parent.reference().root().network());
                assertEquals(work.visits()-started,result.receipt().workVisits());assertSame(parent.nativeFacts(),result.inheritedFacts());
                var ref=parent.baselineReference();
                var policy=new CaptureScaleFairingPolicy(parent.frame().inputHash(),parent.frame().imageIdentity(),ref.width(),ref.height(),ref.transform(),strength==SmoothingModels.Preset.MEDIUM_1PX?1:2,1);
                try(var expected=LongScaleNormalFairing.requestCaptureScale(new LongScaleNormalFairing.CaptureRequest(parent.smoothingBasis(),policy,Long.MAX_VALUE,AttemptMemoryLedger.MAX_BYTES),CancellationProbe.NONE,work)) {
                    assertEquals(expected.result().requestedPoints().orElseThrow(),result.points(),"Each strength must use the same unsmoothed selected parent and unchanged accepted helper");
                }
                for(int fixed:parent.smoothingBasis().protectedIndices())assertEquals(parent.reference().points().get(fixed),result.points().get(fixed));
                if(mode==AlignmentMode.MOVE_EXISTING_NODES)assertEquals(((DetachedWay)before.primitives().get(plan.selectedWayKey())).nodeKeys(),((DetachedWay)plan.after().primitives().get(plan.selectedWayKey())).nodeKeys());
                }
            }
        }
    }
    @Test void cancellationResourceAndStaleSourceRetainParentWithoutPlan() {
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var parent=parent(AlignmentMode.MOVE_EXISTING_NODES,owner,work(owner),()->{});
            try(var result=new RefinementSmoothingAdapter().smooth(parent,SmoothingModels.Preset.MEDIUM_1PX,work(owner),()->true)) {
                assertEquals(RefinementSmoothingAdapter.Outcome.RETAINED_CANCELLED,result.receipt().outcome());assertEquals(parent.reference().points(),result.points());assertTrue(result.plan().isEmpty());
            }
            var tiny=new GeometryWorkContext(0,0,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            try(var result=new RefinementSmoothingAdapter().smooth(parent,SmoothingModels.Preset.STRONG_2PX,tiny,CancellationProbe.NONE)) {
                assertEquals(RefinementSmoothingAdapter.Outcome.RETAINED_RESOURCE,result.receipt().outcome());assertEquals(parent.reference().points(),result.points());assertTrue(result.plan().isEmpty());
            }
            var stale=new boolean[]{false};var selected=parent(AlignmentMode.PRECISE_SHAPE,owner,work(owner),()->{if(stale[0])throw new IllegalStateException("stale");});stale[0]=true;
            try(var result=new RefinementSmoothingAdapter().smooth(selected,SmoothingModels.Preset.STRONG_2PX,work(owner),CancellationProbe.NONE)) {
                assertEquals(RefinementSmoothingAdapter.Outcome.RETAINED_STALE,result.receipt().outcome());assertEquals(selected.reference().points(),result.points());assertTrue(result.plan().isEmpty());
            }
        }
    }
    @Test void sourceCoverageIsAWholeOutputHardGateEvenWhenTheParentItselfIsCovered() {
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var candidate=RefinementParentFixtures.parent(root,"blue");
            var source=SmoothingParentBinderTest.source(root,"blue",owner,()->{});var e=source.evidence();
            var points=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),candidate).points();
            var polygon=new ArrayList<MetricPoint>();for(var p:points)polygon.add(new MetricPoint(p.xMeters(),p.yMeters()+.0001));
            for(int i=points.size()-1;i>=0;i--){var p=points.get(i);polygon.add(new MetricPoint(p.xMeters(),p.yMeters()-.0001));}
            var strip=new MetricRegion(List.of(polygon));
            var narrowed=new EvidenceSnapshot("thin-selected-coverage",e.coordinateFrame(),e.transform(),e.resolution(),strip,strip,e.fields(),e.resampling(),e.sourceIdentity());
            owner.reserve(source.backingBytes()).adopt(narrowed);
            var selected=new RetainedSmoothingSource(source.source(),source.intensityMode(),source.fieldName(),narrowed,source.referenceTransform(),source.referenceWidth(),source.referenceHeight(),source.backingBytes(),()->{},source.imageOwnershipIdentity(),source.inputByteHash(),source.generation(),source.visibleReceipt(),source.legacyDisposition(),source.inputHashProtocol());
            owner.reserve(selected.descriptorBytes()).adopt(selected);
            var stage=new StageId(UUID.randomUUID());var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,stage,candidate.id(),TrackerMode.LEGACY_V02,AlignmentMode.PRECISE_SHAPE,StageKind.SMOOTH,Optional.of(new SmoothingInput(stage,candidate.id(),stage,candidate.id(),"Strong")));
            var work=work(owner);var parent=new SmoothingParentBinder().bind(root,ticket,candidate,selected,work);
            try(var result=new RefinementSmoothingAdapter().smooth(parent,SmoothingModels.Preset.STRONG_2PX,work,CancellationProbe.NONE)) {
                assertEquals(RefinementSmoothingAdapter.Outcome.RETAINED_UNSAFE,result.receipt().outcome());assertEquals(parent.reference().points(),result.points());assertTrue(result.plan().isEmpty());
            }
        }
    }
    @Test void lateStaleAndLateResourceReleaseOnlyTheirOwnLeasesAndNeverCompoundStrengths() {
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            owner.reserve(4096).adopt(new Object());var work=work(owner);var checks=new java.util.concurrent.atomic.AtomicInteger();var stale=new boolean[]{false};
            var parent=parent(AlignmentMode.MOVE_EXISTING_NODES,owner,work,()->{if(stale[0]&&checks.incrementAndGet()==3)throw new IllegalStateException("late stale");});
            long before=owner.currentBytes();long actual;
            try(var first=new RefinementSmoothingAdapter().smooth(parent,SmoothingModels.Preset.STRONG_2PX,work,CancellationProbe.NONE)) {
                assertEquals(RefinementSmoothingAdapter.Outcome.CHANGED,first.receipt().outcome());actual=first.receipt().workVisits();
            }
            assertEquals(before,owner.currentBytes());
            var limited=new GeometryWorkContext(actual-1,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            try(var result=new RefinementSmoothingAdapter().smooth(parent,SmoothingModels.Preset.STRONG_2PX,limited,CancellationProbe.NONE)) {
                assertEquals(RefinementSmoothingAdapter.Outcome.RETAINED_RESOURCE,result.receipt().outcome());assertTrue(result.receipt().proposalWorkVisits()>0);assertEquals(parent.reference().points(),result.points());
            }
            assertEquals(before,owner.currentBytes());stale[0]=true;
            try(var result=new RefinementSmoothingAdapter().smooth(parent,SmoothingModels.Preset.STRONG_2PX,work,CancellationProbe.NONE)) {
                assertEquals(RefinementSmoothingAdapter.Outcome.RETAINED_STALE,result.receipt().outcome());assertTrue(result.receipt().proposalWorkVisits()>0);assertEquals(parent.reference().points(),result.points());
            }
            assertEquals(before,owner.currentBytes());assertNull(PostprocessingValidationWork.current());
        }
    }
    @Test void wholeFrozenIslandsAndTaggedRelatedAnchorsStayExactWhileDisjointGeometryChanges() {
        var reference=RefinementIntervalPlannerTest.capturedTopologyParent(List.of(new OccurrenceRange(0,18)),Set.of(2),Set.of(16));
        var root=reference.root();
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner);var source=SmoothingParentBinderTest.source(root,"blue",owner,()->{});
            var candidate=candidate(reference,TrackerMode.LEGACY_V02,Optional.empty());var parent=bind(root,candidate,source,TrackerMode.LEGACY_V02,work);
            assertTrue(parent.smoothingBasis().protectedIndices().containsAll(Set.of(2,16)));
            try(var result=new RefinementSmoothingAdapter().smooth(parent,SmoothingModels.Preset.STRONG_2PX,work,CancellationProbe.NONE)) {
                assertEquals(RefinementSmoothingAdapter.Outcome.CHANGED,result.receipt().outcome());assertTrue(result.plan().isPresent());
                for(int index:parent.smoothingBasis().protectedIndices())assertEquals(parent.reference().points().get(index),result.points().get(index));
                for(var frozen:root.frozenGeometryProof().orElseThrow().ranges())
                    for(int i=frozen.firstIndex();i<=frozen.lastIndex();i++)assertEquals(parent.reference().points().get(i),result.points().get(i));
                assertEquals(root.network().primitives().get(PrimitiveKey.existing(PrimitiveKey.Type.NODE,3)),result.plan().orElseThrow().after().primitives().get(PrimitiveKey.existing(PrimitiveKey.Type.NODE,3)));
            }
        }
    }
    @Test void actualAAndBDescendantsInBothModesUseTheirOwnSelectedFinalGeometryAndProvenance() {
        for(var engine:List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC))for(var mode:List.of(AlignmentMode.PRECISE_SHAPE,AlignmentMode.MOVE_EXISTING_NODES)) {
            var root=curvedAuthority(mode);var legacy=RefinementParentFixtures.parent(root,"blue");var stage=new StageId(UUID.randomUUID());
            var reference=new ParentReferenceBinder().bind(root,stage,legacy);
            try(var owner=AttemptMemoryLedger.production().rootOwner()) {
                var source=SmoothingParentBinderTest.source(root,"blue",owner,()->{});var work=work(owner);
                // Actual native preparation is confined to fixture setup, before smoothing.
                var run=new RefinementEngineAdapter(engine,"native").run(reference,source.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
                assertEquals(1,run.nativeCalls());var route=run.finalized().orElseThrow().routes().get(0);
                var plan=new ModernSingleWayEditPlanAdapter().adaptRefined(run,source.evidence(),0);
                assertNotEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,route.quality().disposition(),engine+" "+mode+" native setup "+route.quality().findings());
                assertNotEquals(ValidationReport.Disposition.HARD_BLOCKED,plan.validation().disposition(),engine+" "+mode+" native setup "+plan.validation().findingCodes());
                String inheritedReason="existing-parent-review-reason";
                plan=new AlignmentEditPlan(plan.selectedWayKey(),plan.selectedRange(),plan.before(),plan.after(),plan.metricFrame(),plan.permissions(),plan.settingsHash(),plan.evidenceHash(),plan.parameterHash(),plan.routeIdentity(),plan.finalPreviewWays(),new ValidationReport(ValidationReport.Disposition.REVIEW_REQUIRED,List.of(inheritedReason)),plan.disconnectionSource(),plan.disconnectedOccurrenceReplacements(),plan.disconnectionGeometryMode());
                var points=route.hypothesis().points();var id=route.hypothesis().id();
                var nativeReference=new ParentTraceReference(root,new StageId(UUID.randomUUID()),new CandidateId(id),id,"blue","actual-"+engine,points,run.finalCorrespondence().get(id));
                var span=new ParentBackedComposer.Span(0,points.size()-1,false,id,"blue");
                var review=new RefinementPayload.NativeReview(plan,route.quality(),Optional.empty(),List.of(source.source()),OptionalInt.of(0),Map.of(source.source(),source),
                    List.of(new RefinementPipeline.SourceSpan(source.source(),span,Optional.ofNullable(run.finalProvenance().get(id)))),Optional.of(route.pointIds()));
                var candidate=candidate(nativeReference,engine,Optional.of(review));
                var parent=bind(root,candidate,source,engine,work);
                assertEquals(candidate.id(),parent.ticket().smoothingInput().orElseThrow().unsmoothedChoice());assertFalse(parent.nativeFacts().sourceSpans().isEmpty());
                for(var strength:SmoothingModels.Preset.values())try(var result=new RefinementSmoothingAdapter().smooth(parent,strength,work,CancellationProbe.NONE)) {
                    assertEquals(RefinementSmoothingAdapter.Outcome.CHANGED,result.receipt().outcome(),engine+" "+mode+" "+strength+" "+result.receipt().reason());
                    assertSame(root.network(),result.plan().orElseThrow().before());assertEquals(parent.representationIdentity(),result.receipt().parentIdentity());
                    assertEquals(parent.nativeFacts().sourceSpans(),result.inheritedFacts().sourceSpans());
                    assertEquals(parent.pointIds(),result.assessment().orElseThrow().candidate().pointIds());
                    assertEquals(result.points().size(),result.assessment().orElseThrow().candidate().assignments().size());
                    assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED,result.plan().orElseThrow().validation().disposition());
                    assertTrue(result.plan().orElseThrow().validation().findingCodes().contains(inheritedReason),"Native plan-only reasons must survive smoothing exactly");
                    assertEquals(List.of(inheritedReason),result.inheritedFacts().nativeValidation().orElseThrow().findingCodes());
                    assertEquals(result.inheritedFacts().nativeValidation(),result.assessment().orElseThrow().inheritedValidation());
                }
            }
        }
    }
    static SmoothingModels.Parent bind(RefinementRootAuthority root,Candidate candidate,RetainedSmoothingSource source,TrackerMode engine,GeometryWorkContext work) {
        var stage=new StageId(UUID.randomUUID());var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,stage,candidate.id(),engine,root.request().geometryMode(),StageKind.SMOOTH,
            Optional.of(new SmoothingInput(stage,candidate.id(),stage,candidate.id(),"Strong")));
        return new SmoothingParentBinder().bind(root,ticket,candidate,source,work);
    }
    static Candidate candidate(ParentTraceReference reference,TrackerMode engine,Optional<RefinementPayload.NativeReview> review) {
        var root=reference.root();var projection=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode(root.projectionCode());
        var points=reference.points().stream().map(p->{var geo=root.evidence().coordinateFrame().toGeographic(p);return projection.latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(geo.latitudeDegrees(),geo.longitudeDegrees()));}).toList();
        var assignments=new LinkedHashMap<Long,org.openstreetmap.josm.data.coor.EastNorth>();
        review.flatMap(RefinementPayload.NativeReview::finalPointIds).ifPresent(ids->{for(int i=0;i<ids.size();i++)if(ids.get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing)assignments.put(existing.nodeKey().id(),points.get(i));});
        var nativeCandidate=new CenterlineCandidate(reference.nativeCandidateId(),1,List.of(),List.of(),points,points,assignments,List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
        var result=new AlignmentResult(RefinementParentFixtures.selection(root),null,List.of(nativeCandidate),points,points,List.of(),null,null,
            List.of(new DetectorAttempt("blue","blue",engine,DetectorAttemptStatus.APPLICABLE,List.of(nativeCandidate.id()),"","")),List.of(nativeCandidate));
        if(review.isEmpty())return new Candidate(new CandidateId(nativeCandidate.id()),result,reference.correspondence(),reference.parentIdentity());
        var original=RefinementParentFixtures.parent(root,"blue").payload();
        var payload=RefinementPayload.captureNative(result,original,review.orElseThrow(),AttemptMemoryLedger.MAX_BYTES);
        return new Candidate(new CandidateId(nativeCandidate.id()),payload,reference.correspondence(),reference.parentIdentity(),nativeCandidate.id(),"blue");
    }
    static RefinementRootAuthority curvedAuthority(AlignmentMode mode) {
        var old=RefinementParentFixtures.authority(mode);var e=old.evidence();var field=e.fields().get("native");var values=new double[field.width()*field.height()];
        for(int y=0;y<field.height();y++)for(int x=0;x<field.width();x++) {
            double center=x<20||x>140?50:x<50?50+(x-20)/10.:x<=110?53:53-(x-110)/10.;
            values[y*field.width()+x]=.02+.98*Math.exp(-.5*Math.pow((y-center)/1.5,2));
        }
        var scalar=new ScalarEvidenceField(field.width(),field.height(),values,field.copiedValidity(),field.copiedInterpolationValidity(),field.lineage());
        var curved=new EvidenceSnapshot("supported-curved-native-parent",e.coordinateFrame(),e.transform(),e.resolution(),e.decisionRegion(),e.evidenceRegion(),Map.of("native",scalar),e.resampling(),e.sourceIdentity());
        var r=old.request();var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),curved.snapshotId(),curved.canonicalHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution());
        return new RefinementRootAuthority(old.network(),request,curved,old.effectiveConfig(),old.projectionCode(),old.sourceIdentity());
    }
    @Test void aNewReadonlyContextContactCannotBePromotedToAReviewOnlySmoothPlan() {
        var old=RefinementParentFixtures.authority(AlignmentMode.MOVE_EXISTING_NODES);var frame=old.evidence().coordinateFrame();var original=old.network();
        var values=new LinkedHashMap<>(original.primitives());var first=PrimitiveKey.existing(PrimitiveKey.Type.NODE,99);var last=PrimitiveKey.existing(PrimitiveKey.Type.NODE,100);
        values.put(first,new DetachedNode(first,frame.toGeographic(new MetricPoint(50,52.5)),Map.of(),false,false));
        values.put(last,new DetachedNode(last,frame.toGeographic(new MetricPoint(50,52.99)),Map.of(),false,false));
        var context=PrimitiveKey.existing(PrimitiveKey.Type.WAY,20);values.put(context,new DetachedWay(context,List.of(first,last),Map.of("highway","path"),false,false));
        var c=original.closure();var fixed=new HashSet<>(c.protectedExistingNodeKeys());fixed.add(first);fixed.add(last);
        var closure=new ClosureDescriptor(c.scope(),c.queryVersion(),values.keySet(),c.editableExistingKeys(),c.movableExistingNodeKeys(),fixed,c.removableExistingNodeKeys(),c.editableWayOccurrences(),c.externalPorts(),c.collisionEnvelope(),c.editRegion(),c.mayCreateNodes(),true,true,true);
        var network=new NetworkSnapshot("readonly-contact-source",SnapshotRole.CAPTURED_BEFORE,original.datasetIdentity(),original.sourceGeneration(),closure,values,V022SnapshotFixtures.closedWorldReferrerWatches(values));
        var r=old.request();var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),network.snapshotId(),network.canonicalHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution());
        var root=new RefinementRootAuthority(network,request,old.evidence(),old.effectiveConfig(),old.projectionCode(),old.sourceIdentity());
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner);var selected=RefinementParentFixtures.parent(root,"blue");var source=SmoothingParentBinderTest.source(root,"blue",owner,()->{});var parent=bind(root,selected,source,TrackerMode.LEGACY_V02,work);
            var composer=new ParentBackedComposer();var unchanged=composer.compose("valid-selected-parent",parent.reference(),List.of());
            assertTrue(composer.assess(parent.reference(),unchanged,parent.evidence(),"native",CancellationProbe.NONE).topologyFindings().isEmpty(),"The selected parent must really be contact-free");
            try(var result=new RefinementSmoothingAdapter().smooth(parent,SmoothingModels.Preset.STRONG_2PX,work,CancellationProbe.NONE)) {
                assertEquals(RefinementSmoothingAdapter.Outcome.RETAINED_UNSAFE,result.receipt().outcome());assertTrue(result.plan().isEmpty());assertEquals(parent.reference().points(),result.points());
            }
        }
    }
    @Test void exactSelectedCellsRequireSafeBacktrackingOrParentRetentionForBothStrengths() {
        for(double height:new double[]{53.02,53.25})for(var strength:SmoothingModels.Preset.values())try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var original=RefinementParentFixtures.parent(root,"blue");
            var reference=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),original);
            var points=new ArrayList<>(reference.points());for(int i=1;i<4;i++)points.set(i,new MetricPoint(points.get(i).xMeters(),height));
            var selectedReference=new ParentTraceReference(root,reference.parentStage(),reference.parentChoice(),"cell-selected-parent","blue","cell-selected-parent",points,reference.correspondence());
            var candidate=candidate(selectedReference,TrackerMode.LEGACY_V02,Optional.empty());
            var source=SmoothingParentBinderTest.source(root,"blue",owner,()->{});var e=source.evidence();var field=e.fields().get("native");
            var cells=field.copiedInterpolationValidity();cells[52*(field.width()-1)+50]=false;
            var scalar=new ScalarEvidenceField(field.width(),field.height(),field.copiedValues(),field.copiedValidity(),cells,field.lineage());
            var masked=new EvidenceSnapshot("selected-cell-gap-"+height,e.coordinateFrame(),e.transform(),e.resolution(),e.decisionRegion(),e.evidenceRegion(),Map.of("native",scalar),e.resampling(),e.sourceIdentity());
            owner.reserve(source.backingBytes()).adopt(masked);
            var retained=new RetainedSmoothingSource(source.source(),source.intensityMode(),source.fieldName(),masked,source.referenceTransform(),source.referenceWidth(),source.referenceHeight(),source.backingBytes(),()->{},source.imageOwnershipIdentity(),source.inputByteHash(),source.generation(),source.visibleReceipt(),source.legacyDisposition(),source.inputHashProtocol());
            owner.reserve(retained.descriptorBytes()).adopt(retained);var work=work(owner);var parent=bind(root,candidate,retained,TrackerMode.LEGACY_V02,work);
            assertTrue(exactCells(parent.image(),parent.reference().points()),"The selected parent itself must have complete cells");
            try(var result=new RefinementSmoothingAdapter().smooth(parent,strength,work,CancellationProbe.NONE)) {
                if(height<53.1) {
                    assertEquals(RefinementSmoothingAdapter.Outcome.RETAINED_UNSAFE,result.receipt().outcome());assertTrue(result.plan().isEmpty());assertEquals(parent.reference().points(),result.points());
                } else {
                    assertEquals(RefinementSmoothingAdapter.Outcome.CHANGED,result.receipt().outcome());assertTrue(result.receipt().acceptedScale()<1,"The actual proposal touches the invalid cell and must backtrack");
                    assertTrue(exactCells(parent.image(),result.points()));
                }
            }
        }
    }
    static boolean exactCells(ImageCostField image,List<MetricPoint> points) {
        var budget=new ImageCostField.SegmentSupportBudget(Long.MAX_VALUE);
        for(int i=1;i<points.size();i++)if(image.certifySegmentSupport(points.get(i-1),points.get(i),budget,CancellationProbe.NONE)!=ImageCostField.SegmentSupport.COMPLETE)return false;
        return true;
    }
}
