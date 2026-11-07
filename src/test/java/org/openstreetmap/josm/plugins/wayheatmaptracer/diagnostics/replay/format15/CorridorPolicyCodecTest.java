package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

class CorridorPolicyCodecTest {
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
        var b=V022ProductionReplayTest.syntheticRidgeInput();var r=b.request();
        var c=r.corridorInput().orElseThrow().withPolicy(CorridorTraceInput.ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1,fixed);
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),TrackerMode.CORRIDOR_AWARE,r.geometryMode(),r.permissions(),r.budgets(),
            r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),
            r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),Optional.of(c));
        return new FrozenReplayInput(request,b.evidence(),b.network(),b.options());
    }
    private static int indexOf(byte[] data,byte[] needle){
        outer:for(int i=0;i<=data.length-needle.length;i++){for(int j=0;j<needle.length;j++)if(data[i+j]!=needle[j])continue outer;return i;}return -1;
    }
}
