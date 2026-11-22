package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class RefinementRootAuthorityTest {
    @Test void registeredHashLookupChargesAndCancelsBeforeInspectingAliases() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var candidate=RefinementParentFixtures.parent(root,"blue");
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var source=SmoothingParentBinderTest.source(root,"blue",owner,()->{});
            var exhausted=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(0,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            assertThrows(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.LimitReached.class,()->root.registeredOriginalEvidenceHash(candidate,source,exhausted));
            var cancelled=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,()->{throw new java.util.concurrent.CancellationException();},owner);
            assertThrows(java.util.concurrent.CancellationException.class,()->root.registeredOriginalEvidenceHash(candidate,source,cancelled));
        }
    }
    @Test void acquisitionFrameExtensionCertifiesCoverageWithoutRenewingOriginalAuthority() {
        var origin=new GeographicPoint(0,0);var southWest=new GeographicPoint(-.001,-.001);var northEast=new GeographicPoint(.001,.001);
        var original=LocalMetricFrame.certifiedEquirectangular(origin,southWest,northEast);
        var required=MetricRegion.rectangle(-150,-150,150,150);
        var extended=original.certifiedExtension(required);
        assertNotEquals(original,extended);assertTrue(extended.isCertifiedExtensionOf(original));assertFalse(original.isCertifiedExtensionOf(extended));
        assertEquals(original.origin(),extended.origin());
        assertEquals(Double.doubleToLongBits(original.distortionCertificate().eastMetersPerRadian()),Double.doubleToLongBits(extended.distortionCertificate().eastMetersPerRadian()));
        assertEquals(Double.doubleToLongBits(original.distortionCertificate().northMetersPerRadian()),Double.doubleToLongBits(extended.distortionCertificate().northMetersPerRadian()));
        for(var polygon:required.polygons())for(var point:polygon)assertDoesNotThrow(()->extended.toGeographic(point));
        var outside=new MetricPoint(150,150);assertThrows(IllegalArgumentException.class,()->original.toGeographic(outside));
        assertEquals(original.toGeographic(new MetricPoint(10,10)),extended.toGeographic(new MetricPoint(10,10)));
        var shifted=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,.00001),southWest,northEast);
        assertFalse(extended.isCertifiedExtensionOf(shifted));
        var historical=LocalMetricFrame.legacyCertifiedEquirectangular(origin,southWest,northEast);
        assertFalse(extended.isCertifiedExtensionOf(historical));assertThrows(IllegalArgumentException.class,()->historical.certifiedExtension(required));
        assertThrows(IllegalArgumentException.class,()->original.certifiedExtension(MetricRegion.rectangle(-30_000_000,-150,30_000_000,150)));
    }
    @Test void factualNativeRootBindsExactEngineAndMode() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            for(var engine:List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC))for(var mode:AlignmentMode.values()) {
                var old=RefinementParentFixtures.authority(mode);var r=old.request();
                var config=new AlignmentConfig(old.effectiveConfig().heatmap().withTrackerMode(engine),old.effectiveConfig().cleanup());
                var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),engine,r.geometryMode(),r.permissions(),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
                var authority=new RefinementRootAuthority(old.network(),request,old.evidence(),config,old.projectionCode(),old.sourceIdentity());
                assertEquals(engine,authority.request().engine());
                var candidate=RefinementParentFixtures.parent(authority,"blue");
                var stage=new Stage(new StageId(UUID.randomUUID()),Optional.empty(),Optional.empty(),StageKind.valueOf("ROOT_NATIVE"),engine,List.of(candidate),candidate.id(),Optional.empty());
                try(var session=new RefinementSession(stage,config,authority.sourceIdentity(),authority,RefinementLimits.defaults())) {
                    var ticket=session.begin(engine==TrackerMode.CORRIDOR_AWARE?TrackerMode.PROBABILISTIC:TrackerMode.CORRIDOR_AWARE);
                    assertEquals(mode,ticket.mode());session.cancel(ticket);session.workerSettled(ticket);
                }
                assertThrows(IllegalArgumentException.class,()->new RefinementRootAuthority(old.network(),request,old.evidence(),old.effectiveConfig(),old.projectionCode(),old.sourceIdentity()));
            }
        });
    }
    record Captured(RefinementRootAuthority root,SelectedWayIntervalPartitioner.Partition partition) { }
    @Test void ordinaryTwoOriginalNodeWholeHasFactualNoIslandProofAndStillReachesActualPreciseInference() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE,false,true,false);
        assertEquals(RefinementRootAuthority.FrozenGeometryProofKind.DERIVED_CLOSED_TOPOLOGY,root.frozenGeometryProof().orElseThrow().kind());
        assertTrue(root.frozenGeometryProof().orElseThrow().ranges().isEmpty());
        var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
            assertEquals(1,run.nativeCalls());assertTrue(run.unavailable().isEmpty());assertTrue(run.actualWork().transitions()>0);
            assertTrue(run.finalized().orElseThrow().routes().get(0).hypothesis().points().size()>2);
        }
    }
    @Test void actualWholeTwoNodeFixedIslandCannotGainAnInsertedPreciseCurve() {
        var captured=wholeTwoNodeIsland();var root=captured.root();
        var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
            assertEquals(0,run.nativeCalls(),"frozen span must refuse before native insertion even though only endpoints are fixed");
            assertEquals(ParentTraceReference.Unavailable.FROZEN_GEOMETRY_SPAN,run.unavailable().orElseThrow());assertEquals(TraceWorkUsage.none(),run.actualWork());
            assertEquals(RefinementRootAuthority.FrozenGeometryProofKind.CAPTURED_PARTITION,root.frozenGeometryProof().orElseThrow().kind());
            assertEquals(List.of(new OccurrenceRange(0,1)),root.frozenGeometryProof().orElseThrow().ranges());
        }
    }
    @Test void complexRootWithoutCapturedSpanProofHasTypedZeroNativeRefusalAndKeepsLegacyApply() throws Exception {
        var parent=RefinementIntervalPlannerTest.topologyParent(List.of(new OccurrenceRange(0,18)),Set.of(0,6,12,18));var root=parent.root();
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var run=new RefinementEngineAdapter(TrackerMode.PROBABILISTIC,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
            assertEquals(0,run.nativeCalls());assertEquals(ParentTraceReference.Unavailable.FROZEN_GEOMETRY_PROOF_UNAVAILABLE,run.unavailable().orElseThrow());
            assertTrue(run.nativeRun().isEmpty());
        }
        SwingUtilities.invokeAndWait(()-> {
            try(var session=new RefinementSession(RefinementSessionTest.root(),root.effectiveConfig(),root.sourceIdentity(),root,RefinementLimits.defaults())) {
                assertTrue(session.canApply());assertEquals(TrackerMode.LEGACY_V02,session.current().engine());
            }
        });
    }
    @Test void partitionSelectionAndOriginalGeometryParityAreAuthorityRatherThanAClaimedHash() {
        var captured=wholeTwoNodeIsland();var original=captured.root();var partition=captured.partition();
        var other=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);
        assertThrows(IllegalArgumentException.class,()->new RefinementRootAuthority(other.network(),other.request(),other.evidence(),other.effectiveConfig(),other.projectionCode(),other.sourceIdentity(),partition));
        var values=new LinkedHashMap<>(partition.parityPrimitives());var key=values.keySet().stream().filter(k->k.type()==PrimitiveKey.Type.NODE).findFirst().orElseThrow();
        var node=(DetachedNode)values.get(key);values.put(key,new DetachedNode(key,new GeographicPoint(node.coordinate().latitudeDegrees()+.000001,node.coordinate().longitudeDegrees()),node.tags(),node.deleted(),node.modified()));
        var altered=new SelectedWayIntervalPartitioner.Partition(partition.selectedWayKey(),partition.selectedRange(),partition.fixedIslands(),partition.slideIntervals(),partition.junctionDispositions(),partition.provedPorts(),values,partition.parityIncomingReferrers(),partition.datasetIdentity(),partition.sourceGeneration(),partition.parityReceiverRelations());
        assertThrows(IllegalArgumentException.class,()->new RefinementRootAuthority(original.network(),original.request(),original.evidence(),original.effectiveConfig(),original.projectionCode(),original.sourceIdentity(),altered));
    }
    @Test void cancelledMissingProofRefusalHasZeroWorkAndKeepsCancellationOutcome() {
        var parent=RefinementIntervalPlannerTest.topologyParent(List.of(new OccurrenceRange(0,18)),Set.of(0,6,12,18));var ticket=RefinementPipelineTest.ticket(parent,TrackerMode.PROBABILISTIC);
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var result=new RefinementPipeline().run(ticket,parent,RefinementPipelineTest.plan(ticket,parent,false),new RefinementPipeline.Adapters(RefinementPipelineTest.sources(parent),
                (t,p,e,b,c,o)->{fail("unproved source reached inference");return null;},owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),()->true);
            assertEquals(Outcome.CANCELLED,result.outcome());assertTrue(result.candidates().isEmpty());assertEquals(TraceWorkUsage.none(),result.actualWork().orElseThrow());
        }
    }
    @Test void emptyCopyPartitionCannotBeReusedWithoutItsActualDisconnectionAuthority() {
        var root=ParentBackedComposerTest.copyParent().root();var r=root.request();
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),RecoveryPermissions.disabled(8),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution());
        var partition=root.frozenGeometryProof().orElseThrow().capturedPartition().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->new RefinementRootAuthority(root.network(),request,root.evidence(),root.effectiveConfig(),root.projectionCode(),root.sourceIdentity(),partition));
    }
    static Captured wholeTwoNodeIsland() {
        var old=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE,false,true,false);var r=old.request();var c=old.network().closure();
        var values=new LinkedHashMap<>(old.network().primitives());var way=(DetachedWay)values.get(r.selectedWayKey());var outside=PrimitiveKey.existing(PrimitiveKey.Type.NODE,99);var other=PrimitiveKey.existing(PrimitiveKey.Type.WAY,20);
        values.put(outside,new DetachedNode(outside,old.evidence().coordinateFrame().toGeographic(new MetricPoint(10,45)),Map.of(),false,false));
        values.put(other,new DetachedWay(other,List.of(outside,way.nodeKeys().get(0)),Map.of("highway","path"),false,false));
        var fixed=new HashSet<>(c.protectedExistingNodeKeys());fixed.add(outside);
        var closure=new ClosureDescriptor(c.scope(),c.queryVersion(),values.keySet(),c.editableExistingKeys(),c.movableExistingNodeKeys(),fixed,c.removableExistingNodeKeys(),c.editableWayOccurrences(),c.externalPorts(),c.collisionEnvelope(),c.editRegion(),c.mayCreateNodes(),true,true,true);
        var network=new NetworkSnapshot("two-node-island",SnapshotRole.CAPTURED_BEFORE,old.network().datasetIdentity(),old.network().sourceGeneration(),closure,values,V022SnapshotFixtures.closedWorldReferrerWatches(values));
        var specification=new NetworkSnapshotCapture.Specification(network.snapshotId(),network.datasetIdentity(),network.sourceGeneration(),r.selectedWayKey(),r.selectedRange(),old.evidence().coordinateFrame(),closure.collisionEnvelope(),closure.editRegion(),closure.editableWayOccurrences(),closure.editableExistingKeys(),closure.movableExistingNodeKeys(),closure.removableExistingNodeKeys(),closure.protectedExistingNodeKeys(),closure.mayCreateNodes(),r.permissions());
        var partition=SelectedWayIntervalPartitioner.partition(network,specification);
        assertEquals(List.of(new OccurrenceRange(0,1)),partition.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList(),"fixture uses an actual captured partition, not a fabricated range");
        assertTrue(SelectedWayIntervalPartitioner.verifyFrozenParity(partition,network));
        r=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),network.snapshotId(),network.canonicalHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
        return new Captured(new RefinementRootAuthority(network,r,old.evidence(),old.effectiveConfig(),old.projectionCode(),old.sourceIdentity(),partition),partition);
    }
}
