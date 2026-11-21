package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Binder fixtures are immutable values only. Actual acquisition/publication is tested separately. */
class SmoothingParentBinderTest {
    private static OperationTicket ticket(Candidate candidate,AlignmentMode mode) {
        var stage=new StageId(UUID.randomUUID());
        var input=new SmoothingInput(stage,candidate.id(),stage,candidate.id(),"Medium");
        return new OperationTicket(new SessionId(UUID.randomUUID()),1,stage,candidate.id(),TrackerMode.LEGACY_V02,mode,StageKind.SMOOTH,Optional.of(input));
    }
    private static GeometryWorkContext work(AttemptMemoryLedger.Owner owner) {
        return new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
    }
    static RetainedSmoothingSource source(RefinementRootAuthority root,String palette,AttemptMemoryLedger.Owner owner,Runnable currentness) {
        var original=root.evidence();var field=original.fields().get("native");
        var transform=SupportedInputRasterTransform.localMetricAffine(original.coordinateFrame(),new RasterPoint(0,0),1,0,0,1);
        var mapped=new ScalarEvidenceField(field.width(),field.height(),field.copiedValues(),field.copiedValidity(),field.copiedInterpolationValidity(),
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING,palette,EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var evidence=new EvidenceSnapshot("selected-"+palette,original.coordinateFrame(),original.transform(),original.resolution(),original.decisionRegion(),original.evidenceRegion(),Map.of("native",mapped),
            RasterResamplingProvenance.exactInverseBilinear(transform.kindId(),transform.parameterIdentity(),field.width(),field.height(),field.width(),field.height()),original.sourceIdentity());
        long bytes=1_048_576L+16L*field.width()*field.height();owner.reserve(bytes).adopt(evidence);
        var input=new java.awt.image.BufferedImage(field.width(),field.height(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var hashWork=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,System.nanoTime()+60_000_000_000L,CancellationProbe.NONE,owner);
        var hash=org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.hashOriginalArgb(input,hashWork);
        var retained=new RetainedSmoothingSource(new RefinementSourcePlanner.SourceRequest(palette,palette,false,""),IntensitySamplingMode.COLOR_MAPPING,"native",evidence,transform,field.width(),field.height(),bytes,currentness,
            "synthetic-original-input/"+UUID.randomUUID(),hash,OptionalLong.empty(),Optional.empty(),Optional.empty(),org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL);
        owner.reserve(retained.descriptorBytes()).adopt(retained);return retained;
    }
    @Test void selectedLowerRankedLegacyPaletteOwnsFrameAndPoints() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var candidate=RefinementParentFixtures.parent(root,"blue");
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var source=source(root,"blue",owner,()->{});var ticket=ticket(candidate,root.request().geometryMode());
            var parent=new SmoothingParentBinder().bind(root,ticket,candidate,source,work(owner));
            assertEquals("blue",parent.frame().palette());assertEquals("blue",parent.frame().mappingIdentity());
            assertEquals("blue-native",parent.reference().nativeCandidateId());assertEquals(53,parent.reference().points().get(2).yMeters(),1e-7);
            assertSame(source.evidence(),parent.evidence());assertNotSame(root.evidence(),parent.evidence());
            assertEquals(source.referenceTransform(),parent.frame().originalInputTransform());
            assertEquals(source.inputByteHash(),parent.frame().inputHash());assertEquals(64,parent.frame().inputHash().length());
        }
    }
    @Test void legacyMissingNativeMapIsNotMissingSmoothingAssignments() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var candidate=RefinementParentFixtures.parent(root,"blue");
        assertTrue(candidate.payload().nativeGeometry().get(1).proposedNodePositions().isEmpty());
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var parent=new SmoothingParentBinder().bind(root,ticket(candidate,root.request().geometryMode()),candidate,source(root,"blue",owner,()->{}),work(owner));
            assertTrue(parent.nativeBasis().isEmpty());assertEquals(5,parent.smoothingBasis().size());
            assertEquals(List.of(0,1,2,3,4),parent.smoothingBasis().independentIndices());
            assertEquals(Set.of(0,4),parent.smoothingBasis().protectedIndices());
            assertInstanceOf(FinalRoutePointId.GeneratedCandidatePoint.class,parent.pointIds().get(2),"Legacy absent assignments do not prove a removable existing-node identity");
            assertEquals(parent.reference().points(),parent.smoothingBasis().lift(parent.reference().points()));
        }
    }
    @Test void localAffineReceiptCannotRelabelTheOutputResampleAsOriginalInput() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var source=source(root,"blue",owner,()->{});
            assertThrows(IllegalArgumentException.class,()->new SmoothingModels.Frame("",source.imageOwnershipIdentity(),"blue","blue",IntensitySamplingMode.COLOR_MAPPING.name(),root.evidence().coordinateFrame(),
                source.referenceTransform(),161,101,source.evidence().resampling().inputCoordinateConvention(),Optional.of(new SmoothingModels.VerifiedOriginalAffine(
                    RasterMetricTransform.metricGrid(new MetricPoint(0,0),2,0,0,2),source.referenceTransform().parameterIdentity()))));
        }
    }
    @Test void moveUsesExistingSequence() {
        var root=RefinementParentFixtures.authority(AlignmentMode.MOVE_EXISTING_NODES);var candidate=RefinementParentFixtures.parent(root,"blue");
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var parent=new SmoothingParentBinder().bind(root,ticket(candidate,root.request().geometryMode()),candidate,source(root,"blue",owner,()->{}),work(owner));
            assertEquals(List.of(0,1,2,3,4),parent.pointIds().stream().map(id->((FinalRoutePointId.ExistingWayNodeOccurrence)id).originalOccurrenceIndex()).toList());
        }
    }
    @Test void originalJunctionVicinityFreezesEveryFinalSlotOfBothIslands() {
        var reference=RefinementIntervalPlannerTest.capturedTopologyParent(List.of(new OccurrenceRange(0,18)),Set.of(),Set.of());
        var root=reference.root();var projection=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode(root.projectionCode());
        var points=reference.points().stream().map(p->{var geographic=root.evidence().coordinateFrame().toGeographic(p);return projection.latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(geographic.latitudeDegrees(),geographic.longitudeDegrees()));}).toList();
        var nativeCandidate=new CenterlineCandidate("frozen-parent",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
        var result=new AlignmentResult(RefinementParentFixtures.selection(root),null,List.of(nativeCandidate),points,points,List.of(),null,null,
            List.of(new DetectorAttempt("blue","blue",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(nativeCandidate.id()),"","")),List.of(nativeCandidate));
        var candidate=new Candidate(new CandidateId(nativeCandidate.id()),result,reference.correspondence(),"frozen-parent-identity");
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var parent=new SmoothingParentBinder().bind(root,ticket(candidate,root.request().geometryMode()),candidate,source(root,"blue",owner,()->{}),work(owner));
            assertEquals(Set.of(0,4,5,6,7,8,10,11,12,13,14,18),parent.smoothingBasis().protectedIndices());
            for(int index:parent.smoothingBasis().protectedIndices())assertInstanceOf(FinalRoutePointId.ExistingWayNodeOccurrence.class,parent.pointIds().get(index));
            var changed=new ArrayList<>(parent.reference().points());var old=changed.get(5);changed.set(5,new MetricPoint(old.xMeters(),old.yMeters()+.1));
            assertThrows(IllegalArgumentException.class,()->parent.smoothingBasis().lift(changed));
            assertInstanceOf(FinalRoutePointId.GeneratedCandidatePoint.class,parent.pointIds().get(2));
        }
    }
    @Test void nativeFindingsAndParentDispositionDoNotBecomeSmoothingApproval() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var original=RefinementParentFixtures.parent(root,"blue");var old=original.result();var selected=old.candidates().get(1);
        var blocked=new CenterlineCandidate(selected.id(),selected.score(),List.of(),List.of(),selected.eastNorthPoints(),selected.finalPreviewPoints(),Map.of(),List.of(),0,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of("structural-hard-block"));
        var result=new AlignmentResult(old.selection(),null,List.of(blocked),old.sourcePolyline(),blocked.finalPreviewPoints(),List.of(),null,null,
            List.of(new DetectorAttempt("blue","blue",TrackerMode.LEGACY_V02,DetectorAttemptStatus.INSUFFICIENT_SIGNAL,List.of(blocked.id()),"no-signal","No signal")),List.of());
        var candidate=new Candidate(original.id(),result,original.correspondence(),"blocked-parent");
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var parent=new SmoothingParentBinder().bind(root,ticket(candidate,root.request().geometryMode()),candidate,source(root,"blue",owner,()->{}),work(owner));
            assertFalse(parent.nativeFacts().parentApplicable());assertEquals(List.of("structural-hard-block"),parent.nativeFacts().safetyWarnings());
            assertEquals(DetectorAttemptStatus.INSUFFICIENT_SIGNAL,parent.nativeFacts().detectorAttempts().get(0).status());
            assertTrue(parent.nativeFacts().quality().isEmpty());assertTrue(parent.nativeFacts().nativeDisposition().isEmpty());
            assertFalse(parent.nativeFacts().evidence().corridorCoverage().measured());
        }
    }
    @Test void missingEpochOrAffineRetainsUsableParent() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var candidate=RefinementParentFixtures.parent(root,"blue");
        var points=candidate.payload().finalPreviewGeometry();
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var source=source(root,"blue",owner,()->{throw new IllegalStateException("stale epoch");});
            var refused=assertThrows(SmoothingParentBinder.Unavailable.class,()->new SmoothingParentBinder().bind(root,ticket(candidate,root.request().geometryMode()),candidate,source,work(owner)));
            assertEquals(SmoothingParentBinder.Reason.STALE_SOURCE,refused.reason());assertEquals(points,candidate.payload().finalPreviewGeometry());
        }
    }
    @Test void historicalMissingOrUnknownProtocolHashCannotMaterializeAParentImage() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var candidate=RefinementParentFixtures.parent(root,"blue");
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var actual=source(root,"blue",owner,()->{});
            for(String hash:List.of("","input-label",actual.inputByteHash())) {
                var historical=new RetainedSmoothingSource(actual.source(),actual.intensityMode(),actual.fieldName(),actual.evidence(),actual.referenceTransform(),actual.referenceWidth(),actual.referenceHeight(),actual.backingBytes(),()->{},actual.imageOwnershipIdentity(),hash,OptionalLong.empty(),Optional.empty(),Optional.empty(),hash.equals(actual.inputByteHash())?"unknown-protocol":actual.inputHashProtocol());
                owner.reserve(historical.descriptorBytes()).adopt(historical);long before=owner.currentBytes();
                var refused=assertThrows(SmoothingParentBinder.Unavailable.class,()->new SmoothingParentBinder().bind(root,ticket(candidate,root.request().geometryMode()),candidate,historical,work(owner)));
                assertEquals(SmoothingParentBinder.Reason.ORIGINAL_INPUT_HASH_UNAVAILABLE,refused.reason());assertEquals(before,owner.currentBytes());
            }
        }
    }
}
