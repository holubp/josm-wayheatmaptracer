package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy;

class V022AdaptivePolicyReplayTest {
    @Test void newExactMergePolicyKeepsTheCapturedV1IdentityExecutableAndHasItsOwnFrozenIdentity() {
        assertEquals("bb10bde277fea50f810591c4710aec0fab5ad54419ac37e3476fc5569309081d",BPolicy.adaptiveParameterHash());
        assertNotEquals(BPolicy.adaptiveParameterHash(),BPolicy.adaptiveParameterHash(BPolicy.ADAPTIVE_MULTIMODAL_V2));
        var fixture=V022FrozenFrameTest.input(V022FrozenFrameTest.frame());
        var exact=parameter(fixture.request(),BPolicy.exactParameterHash());
        var v1=BPolicy.adaptiveRequest(exact);
        var v2=BPolicy.adaptiveRequest(exact,BPolicy.ADAPTIVE_MULTIMODAL_V2);
        assertEquals(BPolicy.ADAPTIVE_MULTIMODAL_V1,BPolicy.recorded(v1));
        var input=new FrozenReplayInput(v2,fixture.evidence(),fixture.network(),fixture.options());
        assertEquals(BPolicy.ADAPTIVE_MULTIMODAL_V2,BPolicy.recorded(FrozenReplayCodec.decode(FrozenReplayCodec.encode(input)).request()));
        assertNotEquals(v1.parameterHash(),v2.parameterHash());
    }
    @Test void adaptiveExecutionReceiptRoundTripsOnlyUnderItsKnownParameterIdentity() throws Exception {
        for(var revision:java.util.List.of(BPolicy.ADAPTIVE_MULTIMODAL_V1,BPolicy.ADAPTIVE_MULTIMODAL_V2)) {
        var stage=new InferenceWorkAccounting(revision.version(),100,50,60,0);
        var receipt=java.util.Optional.of(new ExecutionWorkReceipt(java.util.List.of(stage),0));
        var receipts=java.util.List.of(receipt);
        var work=assertDoesNotThrow(() -> Format15ExecutionWorkCodec.artifact(receipts));
        assertEquals(receipts,Format15ExecutionWorkCodec.read(work));
        var current=Format15NumericalPolicyReceipt.current("a".repeat(64),"b".repeat(64),
                BPolicy.adaptiveParameterHash(revision),TrackerMode.PROBABILISTIC,TrackerMode.PROBABILISTIC,receipts);
        String policy=new String(current.bytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(policy.contains(revision.version()));
        assertFalse(policy.contains("execution-unattested"));
        var wrong=Format15NumericalPolicyReceipt.current("a".repeat(64),"b".repeat(64),
                BPolicy.exactParameterHash(),TrackerMode.PROBABILISTIC,TrackerMode.PROBABILISTIC,receipts);
        assertTrue(new String(wrong.bytes(),java.nio.charset.StandardCharsets.UTF_8).contains("execution-unattested"));
        }
    }
    @Test void adaptiveChoiceSurvivesFrozenDecodeAndUnknownBaseCannotOptIn() {
        assertEquals("9814307c79fae2c8ec105c755c7373694d3c4e893be78a747062511361241b35",BPolicy.exactParameterHash(),
                "independent captured RC15 parameter identity");
        var fixture = V022FrozenFrameTest.input(V022FrozenFrameTest.frame());
        var old = fixture.request();
        var exact = parameter(old, BPolicy.exactParameterHash());
        assertEquals(BPolicy.EXACT_DEFAULT, BPolicy.recorded(exact));
        var adaptive = BPolicy.adaptiveRequest(exact);
        assertNotEquals(exact.parameterHash(), adaptive.parameterHash());
        var frozen = new FrozenReplayInput(adaptive, fixture.evidence(), fixture.network(), fixture.options());
        var decoded = FrozenReplayCodec.decode(FrozenReplayCodec.encode(frozen));
        assertEquals(BPolicy.ADAPTIVE_MULTIMODAL_V1, BPolicy.recorded(decoded.request()));
        assertEquals(adaptive.parameterHash(), decoded.request().parameterHash());
        assertNotEquals(fixture.canonicalHash(), frozen.canonicalHash());
        assertThrows(IllegalArgumentException.class, () -> BPolicy.adaptiveRequest(parameter(old, "b".repeat(64))));
    }

    private static TraceRequest parameter(TraceRequest r, String hash) {
        return new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),
                r.permissions(),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),
                r.networkContentHash(),r.settingsHash(),hash,r.samplerId(),r.configuredSampleStepMeters(),
                r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
    }
}
