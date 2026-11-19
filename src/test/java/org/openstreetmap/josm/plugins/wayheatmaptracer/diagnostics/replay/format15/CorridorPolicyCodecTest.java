package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

class CorridorPolicyCodecTest {
    @Test void oldPolicyEncodingsAndIdentitiesMatchIndependentBaselineGoldens() {
        record Golden(CorridorTraceInput.ExecutionPolicy policy, Set<Integer> fixed, int fullLength,
                String fullHash, int requestLength, String requestHash) { }
        // Extracted using the archived 3f93 codec and this fixture's unchanged baseline factory.
        var goldens=List.of(
            new Golden(CorridorTraceInput.ExecutionPolicy.HISTORICAL_V1,Set.of(),164806,"af58af188950059173e54fe2a24c86511dba327d7f0127851aa6423901181972",578,"c7d1b50275329811b656c3891b982545d3ac52ae7037a5bababe895e5807dcea"),
            new Golden(CorridorTraceInput.ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1,Set.of(),164873,"0c6bbee901916a8ac076a608ec837c8474468beeb4e48197efeabb98166fe595",630,"59319689ffe0fde6e34f242f45e0bd1dab99933953ac3e3cb6ba66af96ece9d2"),
            new Golden(CorridorTraceInput.ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1,Set.of(0),164877,"100118727d236df7b28f8a87fda45a050f862b36b7d4526fbe07051fd607c55e",634,"7afa08a5b14e360fa207d780631844638a5c6ceb154f94f814dc52d3f882872d"),
            new Golden(CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1,Set.of(),164869,"a6b2a11a08a52a1e795c76536a07b342f1a0d972f7cd2024a82691755e11ec8d",626,"7e46a61016cb354ccd66d8f32b83a3df6f235bc85bc96f8d1cb69a07487bf011"),
            new Golden(CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1,Set.of(0),164873,"394a2ac737cb7270b447274cc3913fac6a911ca5ff664c0a21cf1509ebde0323",630,"bda025c2c0976710100519d053d01be96c12583c41b75e2133de4c13046b4741"),
            new Golden(CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1,Set.of(),164876,"f523d2b1b0f62f5f96bcda7af831bd7d40b2c87e113e909fee4914f150818e25",633,"26a2b8cb1987c294b202b44179a15d988c92836cec3686d9df3fc109ae5e6476"),
            new Golden(CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1,Set.of(0),164880,"ed5a2bfc51a204b8a031e7fec0246ee3f5027447022c47856887eb2cc864afb7",637,"82f97d2c0db80da57687e49338150af282473494323ad4775fd3168673dbe7b2"));
        for(var golden:goldens) {
            var input=modern(golden.fixed(),golden.policy());
            var full=FrozenReplayCodec.encode(input);var request=FrozenReplayCodec.encodeRequestOnly(input.request());
            assertEquals(golden.fullLength(),full.length);assertEquals(golden.fullHash(),Format15Safety.sha256(full));
            assertEquals(golden.fullHash(),input.canonicalHash());
            assertEquals(golden.requestLength(),request.length);assertEquals(golden.requestHash(),Format15Safety.sha256(request));
            assertEquals(golden.requestHash(),FrozenReplayCodec.canonicalRequestHash(input.request()));
        }
    }
    @Test void r6AndR7RoundTripWithDistinctIdentity() {
        var r6=modern(Set.of(0),CorridorTraceInput.ExecutionPolicy.R6_OWNED_DIRECT_POSITION_COST_V1);
        var r7=modern(Set.of(0),CorridorTraceInput.ExecutionPolicy.R7_OWNED_MEASURED_CORE_COST_V1);
        assertNotEquals(r6.canonicalHash(),r7.canonicalHash());
        for(var input:List.of(r6,r7)) {
            var decoded=FrozenReplayCodec.decode(FrozenReplayCodec.encode(input));
            assertEquals(input.request(),decoded.request());
            assertEquals(input.request(),FrozenReplayCodec.decodeRequestOnly(FrozenReplayCodec.encodeRequestOnly(input.request()),input.evidence()));
        }
    }
    @Test void r6AndR7RejectMoveAndCorruptionInFrozenRequests() {
        for(var policy:List.of(CorridorTraceInput.ExecutionPolicy.R6_OWNED_DIRECT_POSITION_COST_V1,
                CorridorTraceInput.ExecutionPolicy.R7_OWNED_MEASURED_CORE_COST_V1)) {
            var input=modern(Set.of(),policy);var r=input.request();
            var move=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),AlignmentMode.MOVE_EXISTING_NODES,r.permissions(),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),
                r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
            assertThrows(IllegalArgumentException.class,()->FrozenReplayCodec.decodeRequestOnly(FrozenReplayCodec.encodeRequestOnly(move),input.evidence()));
            var bytes=FrozenReplayCodec.encodeRequestOnly(r);int index=indexOf(bytes,policy.name().getBytes(StandardCharsets.UTF_8));
            assertTrue(index>=0);bytes[index]='X';
            assertThrows(IllegalArgumentException.class,()->FrozenReplayCodec.decodeRequestOnly(bytes,input.evidence()));
            var complete=FrozenReplayCodec.encodeRequestOnly(r);
            assertThrows(IllegalArgumentException.class,()->FrozenReplayCodec.decodeRequestOnly(Arrays.copyOf(complete,complete.length-1),input.evidence()));
        }
    }
    @Test void r5CannotBeAttachedToBHybridOrLegacyManualRequests() {
        var input=modern(Set.of(),CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1); var r=input.request();
        for(var engine:List.of(TrackerMode.PROBABILISTIC,TrackerMode.LEGACY_V02,TrackerMode.HYBRID,TrackerMode.LEGACY_THEN_B))
            assertThrows(IllegalArgumentException.class,()->new TraceRequest(r.selectedWayKey(),r.selectedRange(),engine,r.geometryMode(),r.permissions(),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),
                r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput()));
    }
    @Test void r5AppendsObservedShoulderPolicyWithoutChangingR3R4Encoding() {
        assertEquals(3,CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1.ordinal());
        var r5=modern(Set.of(0),CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1);
        var bytes=FrozenReplayCodec.encode(r5); var decoded=FrozenReplayCodec.decode(bytes);
        assertEquals(9,FrozenReplayCodec.encodedVersion(bytes)); assertEquals(r5.request(),decoded.request());
        assertArrayEquals(bytes,FrozenReplayCodec.encode(decoded));
        assertEquals(r5.request(),FrozenReplayCodec.decodeRequestOnly(FrozenReplayCodec.encodeRequestOnly(r5.request()),r5.evidence()));
        var input=decoded.request().corridorInput().orElseThrow();
        assertTrue(input.observedShoulderPolicy()); assertTrue(input.certifiedGapDomainPolicy()); assertTrue(input.boundedBridgePolicy());
        assertFalse(modern(Set.of(),CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1).request().corridorInput().orElseThrow().observedShoulderPolicy());
        assertNotEquals(r5.canonicalHash(),modern(Set.of(0),CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1).canonicalHash());
    }
    @Test void r4HasAnAppendedTypedIdentityAndKeepsR3WireNative() {
        var r3 = modern(Set.of());
        var r4 = modern(Set.of(), CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1);
        assertEquals(1, CorridorTraceInput.ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1.ordinal());
        assertEquals(2, CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1.ordinal());
        assertNotEquals(r3.canonicalHash(), r4.canonicalHash());
        for (var input : List.of(r3, r4)) {
            var bytes = FrozenReplayCodec.encode(input);
            var decoded = FrozenReplayCodec.decode(bytes);
            assertEquals(9, FrozenReplayCodec.encodedVersion(bytes));
            assertEquals(input.request(), decoded.request());
            assertArrayEquals(bytes, FrozenReplayCodec.encode(decoded));
            assertArrayEquals(FrozenReplayCodec.encodeRequestOnly(input.request()), FrozenReplayCodec.encodeRequestOnly(decoded.request()));
        }
    }
    @Test void historicalPublicFixtureRetainsPrePolicyGoldenBytesAndRequestHash() {
        var old=V022ProductionReplayTest.syntheticRidgeInput();
        // Generated with immutable pre-policy4855 runtime b61044ee..., no inference.
        assertEquals("adecf2ca46858456ad2c08496ccabc8183b72dffce12f16a4c04ccf54ca23281",old.canonicalHash());
        assertEquals("04c3987419549a4e8cdd6948d398431f89bfa098d30726d12bc5f07fa5d4bf70",FrozenReplayCodec.canonicalRequestHash(old.request()));
        var bytes=FrozenReplayCodec.encode(old);
        assertTrue(FrozenReplayCodec.encodedVersion(bytes)<9);
        assertArrayEquals(bytes,FrozenReplayCodec.encode(FrozenReplayCodec.decode(bytes)));
        assertEquals(CorridorTraceInput.ExecutionPolicy.HISTORICAL_V1,
            FrozenReplayCodec.decode(bytes).request().corridorInput().orElseThrow().executionPolicy());
    }

    @Test void explicitPolicyAndFixedSetRoundTripAndBindCanonicalIdentities() {
        var input=modern(Set.of(1,0));var bytes=FrozenReplayCodec.encode(input);
        assertEquals(9,FrozenReplayCodec.encodedVersion(bytes));
        var decoded=FrozenReplayCodec.decode(bytes);
        assertEquals(input.request(),decoded.request());assertArrayEquals(bytes,FrozenReplayCodec.encode(decoded));
        assertEquals(List.of(0,1),new ArrayList<>(decoded.request().corridorInput().orElseThrow().additionalFixedOccurrences()));
        var requestBytes=FrozenReplayCodec.encodeRequestOnly(input.request());
        assertEquals(input.request(),FrozenReplayCodec.decodeRequestOnly(requestBytes,input.evidence()));
        assertNotEquals(input.canonicalHash(),modern(Set.of()).canonicalHash());
        assertNotEquals(FrozenReplayCodec.canonicalRequestHash(input.request()),FrozenReplayCodec.canonicalRequestHash(modern(Set.of()).request()));
        assertEquals(9,FrozenReplayCodec.encodedVersion(FrozenReplayCodec.encode(modern(Set.of()))));
    }

    @Test void unknownPolicyAndMissingFixedFieldFailClosed() {
        var input=modern(Set.of());var bytes=FrozenReplayCodec.encodeRequestOnly(input.request());
        byte[] name=CorridorTraceInput.ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1.name().getBytes(StandardCharsets.UTF_8);
        int index=indexOf(bytes,name);assertTrue(index>=0);bytes[index]='X';
        assertThrows(IllegalArgumentException.class,()->FrozenReplayCodec.decodeRequestOnly(bytes,input.evidence()));
        var complete=FrozenReplayCodec.encodeRequestOnly(input.request());
        assertThrows(IllegalArgumentException.class,()->FrozenReplayCodec.decodeRequestOnly(Arrays.copyOf(complete,complete.length-4),input.evidence()));
        var full=FrozenReplayCodec.encode(input);full[7]=8;
        assertThrows(IllegalArgumentException.class,()->FrozenReplayCodec.decode(full));
    }

    @Test void originalOccurrenceRangeAndHistoricalAuthorityCannotBeSilentlyBroadened() {
        var old=V022ProductionReplayTest.syntheticRidgeInput();var c=old.request().corridorInput().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->c.withPolicy(CorridorTraceInput.ExecutionPolicy.HISTORICAL_V1,Set.of(0)));
        assertThrows(IllegalArgumentException.class,()->modern(Set.of(2)));
    }

    private static FrozenReplayInput modern(Set<Integer> fixed) {
        return modern(fixed, CorridorTraceInput.ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1);
    }
    private static FrozenReplayInput modern(Set<Integer> fixed, CorridorTraceInput.ExecutionPolicy policy) {
        var b=V022ProductionReplayTest.syntheticRidgeInput();var r=b.request();
        var c=r.corridorInput().orElseThrow().withPolicy(policy,fixed);
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),TrackerMode.CORRIDOR_AWARE,r.geometryMode(),r.permissions(),r.budgets(),
            r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),
            r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),Optional.of(c));
        return new FrozenReplayInput(request,b.evidence(),b.network(),b.options());
    }
    private static int indexOf(byte[] data,byte[] needle){
        outer:for(int i=0;i<=data.length-needle.length;i++){for(int j=0;j<needle.length;j++)if(data[i+j]!=needle[j])continue outer;return i;}return -1;
    }
}
