package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyProfileNormalPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;

class V022RecordedBChildExecutionTest {
    static String hash(String... fields) {
        try {var h=MessageDigest.getInstance("SHA-256");for(var value:fields){byte[] b=value.getBytes(StandardCharsets.UTF_8);h.update(Integer.toString(b.length).getBytes(StandardCharsets.US_ASCII));h.update((byte)':');h.update(b);}return HexFormat.of().formatHex(h.digest());}
        catch(Exception e){throw new AssertionError(e);}
    }
    static String newParent(boolean managed) {
        String identity="legacy-then-b-v2/"+LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1.id()+"/"+EvidenceModelParameters.defaults().version()+"/"+ProbabilisticTraceEngine.ReliabilityPolicy.DIRECT_LONGITUDINAL_V2+"/"+ModernNumericalPolicy.SHARED_COST;
        String old=hash((managed?"managed-live-":"live-")+"legacy_then_b-v2",identity);
        return hash("legacy-b-reference-inventory-policy-v1",old,BPolicy.INVENTORY_PREP_VERSION);
    }
    static TraceRequest parent(TraceRequest r,String parameter) {
        return new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),parameter,r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
    }
    private void actualChild(boolean compact) {
        var fixture=LegacyBTestFixtures.alignedEndpointFixture();var original=parent(fixture.request(),newParent(compact));
        var remaining=compact?new TraceBudgets(96,8_000_000,1_000_000,32,8):original.budgets();
        var reference=new LegacyBReferenceFactory().prepare(LegacyBTestFixtures.section(fixture,0),original,fixture.evidence(),fixture.network(),remaining,CancellationProbe.NONE);
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var run=new ProbabilisticTraceEngine("synthetic").traceRefinedWithUsage(reference,fixture.network(),CancellationProbe.NONE,owner);
            var result=run.value().result();
            assertTrue(result.workAccounting().orElseThrow().hasPolicy(compact?BPolicy.INVENTORY_COMPACT_VERSION:BPolicy.INVENTORY_FULL_VERSION),"The actual bound child must execute the recorded new policy");
            assertFalse(result.hypotheses().isEmpty());assertTrue(result.evaluatedTransitions()>0&&result.evaluatedTransitions()<=remaining.maximumTransitions());
            assertTrue(result.hypotheses().stream().flatMap(h->h.points().stream()).allMatch(p->Math.abs(p.yMeters())<1.0),"Reference source, not displaced original midpoint, must drive B");
            assertEquals(remaining,reference.request().budgets());assertEquals(fixture.network().canonicalHash(),original.networkContentHash());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void newVisibleParentExecutesActualFullChildWithOriginalReferenceAuthority(){actualChild(false);}
    @Test void newManagedParentExecutesActualCompactChildWithRemainingAllocation(){actualChild(true);}
    @Test void forgedChildStillFailsGuardedAuthorityBeforeKnownParentPolicySelection() {
        var f=LegacyBTestFixtures.alignedEndpointFixture();var original=parent(f.request(),newParent(false));
        var ref=new LegacyBReferenceFactory().prepare(LegacyBTestFixtures.section(f,0),original,f.evidence(),f.network(),original.budgets(),CancellationProbe.NONE);
        var r=ref.request();var forgedChild=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),"f".repeat(64),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
        var forged=new LegacyBModels.Reference(ref.section(),forgedChild,ref.evidence(),ref.originalRequest(),ref.originalEvidence());var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var error=assertThrows(IllegalArgumentException.class,()->new ProbabilisticTraceEngine("synthetic").traceRefinedWithUsage(forged,f.network(),CancellationProbe.NONE,owner));
            assertTrue(error.getMessage().contains("original authority"));
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void unknownParentKeepsTheHistoricalExactBackend() {
        var f=LegacyBTestFixtures.alignedEndpointFixture();var original=parent(f.request(),"e".repeat(64));
        var ref=new LegacyBReferenceFactory().prepare(LegacyBTestFixtures.section(f,0),original,f.evidence(),f.network(),original.budgets(),CancellationProbe.NONE);
        var result=new ProbabilisticTraceEngine("synthetic").traceRefinedWithUsage(ref,f.network(),CancellationProbe.NONE).result();
        assertTrue(result.workAccounting().orElseThrow().hasPolicy(ProbabilisticExecutionPolicy.CURRENT_VERSION));
        assertFalse(BReferencePolicyBinding.isNewParentHash(original.parameterHash()));
        assertThrows(IllegalArgumentException.class,()->BReferencePolicyBinding.newParentParameterHash(original.parameterHash()));
        assertThrows(IllegalArgumentException.class,()->new ProbabilisticTraceEngine("synthetic").traceWithUsage(ref.request(),ref.evidence(),f.network(),CancellationProbe.NONE));
    }
}
