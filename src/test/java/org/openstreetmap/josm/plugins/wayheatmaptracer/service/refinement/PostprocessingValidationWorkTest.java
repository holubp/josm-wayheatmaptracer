package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class PostprocessingValidationWorkTest {
    static GeometryWorkContext work(AttemptMemoryLedger.Owner owner,long visits,CancellationProbe cancellation) {
        return new GeometryWorkContext(visits,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,cancellation,owner);
    }
    @Test void zeroBudgetRejectsRealSnapshotCopyAndScopeAlwaysRestores() {
        var original=RefinementParentFixtures.authority(AlignmentMode.MOVE_EXISTING_NODES).network();
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner,0,CancellationProbe.NONE);
            assertThrows(GeometryWorkContext.LimitReached.class,()-> {
                try(var scope=PostprocessingValidationWork.open(work)) { copy(original); }
            });
            assertNull(PostprocessingValidationWork.current());
            assertEquals(original,copy(original));
            assertEquals(0,work.visits());
        }
    }
    @Test void sameContextNestsForeignContextFailsAndFailureRestores() {
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var first=work(owner,100,CancellationProbe.NONE);var foreign=work(owner,100,CancellationProbe.NONE);
            try(var outer=PostprocessingValidationWork.open(first)) {
                assertSame(first,PostprocessingValidationWork.current());
                try(var nested=PostprocessingValidationWork.open(first)) { PostprocessingValidationWork.visit(); }
                assertThrows(IllegalArgumentException.class,()->PostprocessingValidationWork.open(foreign));
                assertSame(first,PostprocessingValidationWork.current());
            }
            assertNull(PostprocessingValidationWork.current());
            long charged=first.visits();PostprocessingValidationWork.visit();assertEquals(charged,first.visits());
        }
    }
    static NetworkSnapshot copy(NetworkSnapshot snapshot) {
        return new NetworkSnapshot(snapshot.snapshotId(),snapshot.role(),snapshot.datasetIdentity(),snapshot.sourceGeneration(),snapshot.closure(),snapshot.primitives(),snapshot.incomingReferrerWatches(),snapshot.semanticWitness(),snapshot.proposedPortReplacements());
    }
    @Test void snapshotHashAndRealPlanHaveScopedParityAndLateRefusalRestoresUnrelatedWork() {
        var parent=ParentBackedComposerTest.parent(AlignmentMode.MOVE_EXISTING_NODES);var e=parent.root().evidence();
        var composer=new ParentBackedComposer();var geometry=composer.compose("parity",parent,List.of(ParentBackedComposerTest.choice(parent,parent.originalRange(),54)));
        var old=composer.assess(parent,geometry,e,"native",CancellationProbe.NONE);
        var oldPlan=new ModernSingleWayEditPlanAdapter().adaptRefinement(parent,old,e);
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner,20_000_000,CancellationProbe.NONE);var image=ImageCostField.fromEvidence(e,"native",work);
            var scoped=composer.assessPostprocessed(parent,geometry,e,image,work,CancellationProbe.NONE);
            assertEquals(old.quality(),scoped.quality());assertEquals(old.topologyFindings(),scoped.topologyFindings());
            var plan=new ModernSingleWayEditPlanAdapter().adaptPostprocessed(parent,scoped,e,work);assertEquals(oldPlan,plan);
            assertEquals(parent.root().network().canonicalHash(),parent.root().network().canonicalHash(work));
            var baseline=work(owner,20_000_000,CancellationProbe.NONE);
            try(var scope=PostprocessingValidationWork.open(baseline)){assertEquals(plan,copyPlan(plan));}
            assertTrue(baseline.visits()>20);
            var late=work(owner,baseline.visits()-1,CancellationProbe.NONE);
            assertThrows(GeometryWorkContext.LimitReached.class,()->{try(var scope=PostprocessingValidationWork.open(late)){copyPlan(plan);}});
            assertNull(PostprocessingValidationWork.current());assertEquals(plan,copyPlan(plan));
            var hashTiny=work(owner,2,CancellationProbe.NONE);
            assertThrows(GeometryWorkContext.LimitReached.class,()->parent.root().network().canonicalHash(hashTiny));
            assertNull(PostprocessingValidationWork.current());
        }
    }
    @Test void lateRecursiveCancellationKeepsItsTypeAndScopeDoesNotPropagateToThreads() throws Exception {
        var network=RefinementParentFixtures.authority(AlignmentMode.MOVE_EXISTING_NODES).network();
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            var work=work(owner,100000,()->checks.incrementAndGet()>80);
            assertThrows(java.util.concurrent.CancellationException.class,()->{try(var scope=PostprocessingValidationWork.open(work)){copy(network);}});
            assertNull(PostprocessingValidationWork.current());
            var current=work(owner,100000,CancellationProbe.NONE);
            var observed=new java.util.concurrent.atomic.AtomicReference<GeometryWorkContext>();
            try(var scope=PostprocessingValidationWork.open(current)) {
                var thread=new Thread(()->observed.set(PostprocessingValidationWork.current()));thread.start();thread.join();assertNull(observed.get());
            }
            assertNull(PostprocessingValidationWork.current());
        }
    }
    static AlignmentEditPlan copyPlan(AlignmentEditPlan p) {
        return new AlignmentEditPlan(p.selectedWayKey(),p.selectedRange(),p.before(),p.after(),p.metricFrame(),p.permissions(),p.settingsHash(),p.evidenceHash(),p.parameterHash(),p.routeIdentity(),p.finalPreviewWays(),p.validation(),p.disconnectionSource(),p.disconnectedOccurrenceReplacements(),p.disconnectionGeometryMode());
    }
    @Test void inheritedProofHonorsTheSameTighterNonzeroCapAndReleasesItsScratchOnCancellation() {
        var root=RefinementParentFixtures.authority(AlignmentMode.MOVE_EXISTING_NODES);var plain=root.network();
        var witness=new NonTransportSemanticWitness(plain.canonicalHash(),plain.datasetIdentity(),plain.sourceGeneration(),java.util.Map.of());
        var network=new NetworkSnapshot(plain.snapshotId(),plain.role(),plain.datasetIdentity(),plain.sourceGeneration(),plain.closure(),plain.primitives(),plain.incomingReferrerWatches(),witness);
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            owner.reserve(512).adopt(new Object());long before=owner.currentBytes();
            var tighter=new GeometryWorkContext(1_000_000,1024,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->{try(var scope=PostprocessingValidationWork.open(tighter)) {
                InheritedNonTransportContacts.prove(network,network.primitives(),root.request().selectedWayKey(),root.request().selectedRange(),root.evidence(),8,CancellationProbe.NONE,PostprocessingValidationWork::charge,owner);
            }});
            assertEquals(before,owner.currentBytes());assertNull(PostprocessingValidationWork.current());
            var cancelled=work(owner,1_000_000,()->owner.currentBytes()>before+4000);
            assertThrows(java.util.concurrent.CancellationException.class,()->{try(var scope=PostprocessingValidationWork.open(cancelled)) {
                InheritedNonTransportContacts.prove(network,network.primitives(),root.request().selectedWayKey(),root.request().selectedRange(),root.evidence(),8,CancellationProbe.NONE,PostprocessingValidationWork::charge,owner);
            }});
            assertEquals(before,owner.currentBytes());assertNull(PostprocessingValidationWork.current());
        }
    }
}
