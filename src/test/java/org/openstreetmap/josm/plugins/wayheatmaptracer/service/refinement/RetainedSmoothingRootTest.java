package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class RetainedSmoothingRootTest {
    private static TraceRequest request(RefinementRootAuthority root,EvidenceSnapshot evidence) {
        var r=root.request();return new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),evidence.snapshotId(),evidence.canonicalHash(),
            r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),evidence.resolution());
    }
    @Test void sameLedgerGroupedEvidenceIsInventoriedOnceWithoutSkippingValidation() {
        var original=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var source=SmoothingParentBinderTest.source(original,"blue",owner,()->{});
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            var authority=new RefinementRootAuthority(original.network(),request(original,source.evidence()),source.evidence(),original.effectiveConfig(),original.projectionCode(),original.sourceIdentity(),null,source,work);
            assertEquals(source.backingBytes(),authority.charges().get(source.evidence()));
            assertFalse(authority.charges().containsKey(source.evidence().fields().get("native")),"private arrays are already inside the admitted group");
            long before=ledger.currentBytes();source.retainInto(owner);assertEquals(before,ledger.currentBytes());
            assertThrows(IllegalArgumentException.class,()->new RefinementRootAuthority(original.network(),original.request(),source.evidence(),original.effectiveConfig(),original.projectionCode(),original.sourceIdentity(),null,source,work),"group ownership cannot waive source-hash binding");
            var old=new RefinementRootAuthority(original.network(),request(original,source.evidence()),source.evidence(),original.effectiveConfig(),original.projectionCode(),original.sourceIdentity());
            assertTrue(old.charges().containsKey(source.evidence().fields().get("native")),"old constructor retains its existing inventory behavior");
        }assertEquals(0,ledger.currentBytes());
    }
    @Test void foreignLedgerAndUndercostGroupsAreRefused() {
        var original=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);
        try(var owner=AttemptMemoryLedger.production().rootOwner();var foreign=AttemptMemoryLedger.production().rootOwner()) {
            var source=SmoothingParentBinderTest.source(original,"blue",owner,()->{});
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,foreign);
            assertThrows(IllegalArgumentException.class,()->new RefinementRootAuthority(original.network(),request(original,source.evidence()),source.evidence(),original.effectiveConfig(),original.projectionCode(),original.sourceIdentity(),null,source,work));
        }
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var source=SmoothingParentBinderTest.source(original,"blue",owner,()->{});
            var lie=new RetainedSmoothingSource(source.source(),source.intensityMode(),source.fieldName(),source.evidence(),source.referenceTransform(),source.referenceWidth(),source.referenceHeight(),1,()->{});
            owner.reserve(lie.descriptorBytes()).adopt(lie);
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            assertThrows(IllegalArgumentException.class,()->new RefinementRootAuthority(original.network(),request(original,lie.evidence()),lie.evidence(),original.effectiveConfig(),original.projectionCode(),original.sourceIdentity(),null,lie,work));
        }
    }
}
