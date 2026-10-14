package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Pure observed-band graph oracles: the callback alone grants sampled inequality continuity. */
class V022CensoredDirectionReliabilityTest {
    @Test void persistentUniqueBandsRetainInteriorConfidenceAndSelectionEdgesDoNotInventReach() {
        var rows = rows(21, -1, -1);
        var result = CensoredDirectionReliability.confidence(rows, (a,b,c,d,e) -> true,
                CancellationProbe.NONE, ignored -> { }, null);
        assertEquals(1, result[10][0], 1e-12);
        assertEquals(0, result[0][0]);
        assertEquals(0, result[1][0]);
        assertEquals(0, result[20][0]);
        assertEquals(1, result[2][0], 1e-12);
    }

    @Test void shortIslandRetainsRowsButCannotCertifyEitherFourMetreExterior() {
        var rows = rows(21, 7, 9);
        var result = CensoredDirectionReliability.confidence(rows, (a,b,c,d,e) -> true,
                CancellationProbe.NONE, ignored -> { }, null);
        assertEquals(21, result.length);
        for (int i = 7; i <= 9; i++) assertEquals(0, result[i][0]);
    }

    @Test void disconnectedRasterBreaksChainAndMissingWindowMassIsNotRenormalizedAway() {
        var rows = rows(21, -1, -1);
        var result = CensoredDirectionReliability.confidence(rows,
                (a,b,c,d,e) -> a.profileIndex() != 10, CancellationProbe.NONE, ignored -> { }, null);
        assertEquals(0, result[10][0]);
        assertEquals(0, result[11][0]);
        // p15 has eight observed metres left, a twenty-metre available window,
        // and its whole ten-metre available window right: sqrt((8-.5*8^2/20)/10).
        assertEquals(.8, result[15][0], 1e-12);
    }

    @Test void ambiguousPairsAreFullyExaminedAndNoPrefixBecomesUnique() {
        var rows = new ArrayList<>(rows(21, -1, -1));
        var original = rows.get(10);
        rows.set(10, copy(original, List.of(band("first", -7), band("second", -6))));
        var calls = new AtomicInteger();
        var result = CensoredDirectionReliability.confidence(rows, (a,b,c,d,e) -> {
            calls.incrementAndGet(); return true;
        }, CancellationProbe.NONE, ignored -> { }, null);
        assertEquals(22, calls.get(), "all candidates on both two-pair boundaries are checked");
        assertEquals(0, result[10][0]);
        assertEquals(0, result[10][1]);
        assertEquals(0, result[9][0]);
    }

    @Test void unknownLegacyThresholdOrExtentCannotGrantDirectionalCorroboration() {
        var rows = new ArrayList<>(rows(21, -1, -1));
        var original = rows.get(10);
        rows.set(10, copy(original, List.of(new ProbabilisticProfile.CensoredMode(
                "legacy", "legacy", ProbabilisticProfile.CensorSide.LEFT, -7, 1, true))));
        var result = CensoredDirectionReliability.confidence(rows, (a,b,c,d,e) -> true,
                CancellationProbe.NONE, ignored -> { }, null);
        assertEquals(0, result[10][0]);
        assertEquals(0, result[9][0]);
    }

    @Test void zeroExistenceNeighborsCannotSupplyExteriorCorroborationToAnObservedIsland() {
        var rows = new ArrayList<>(rows(21,-1,-1));
        for (int i = 0; i < rows.size(); i++) if (i != 10) {
            var p = rows.get(i); var b = p.censoredModes().get(0);
            rows.set(i, copy(p,List.of(new ProbabilisticProfile.CensoredMode(b.id(),b.evidenceLineage(),
                    b.side(),b.observedBoundaryMeters(),0,b.gradientSupported(),
                    b.inwardExtentMeasured(),b.inwardSupportOffsetMeters(),b.shoulderThreshold()))));
        }
        var result = CensoredDirectionReliability.confidence(rows,(a,b,c,d,e) -> true,
                CancellationProbe.NONE,ignored -> { },null);
        assertEquals(0,result[10][0],"zero existence is not an observed exterior band");
    }

    @Test void uniformCensoredRowBreaksDirectionalContinuationOnBothSides() {
        var rows = new ArrayList<>(rows(21,-1,-1));
        var p = rows.get(10); var b = p.censoredModes().get(0);
        rows.set(10,copy(p,List.of(new ProbabilisticProfile.CensoredMode(b.id(),b.evidenceLineage(),
                b.side(),b.observedBoundaryMeters(),b.existenceConfidence(),false,
                b.inwardExtentMeasured(),b.inwardSupportOffsetMeters(),b.shoulderThreshold()))));
        var result = CensoredDirectionReliability.confidence(rows,(a,b1,c,d,e) -> true,
                CancellationProbe.NONE,ignored -> { },null);
        assertEquals(0,result[9][0]);
        assertEquals(0,result[10][0]);
        assertEquals(0,result[11][0]);
    }

    @Test void adjacentIdsDoNotBridgeMoreThanTwentyMetresOfUnsampledProfiles() {
        var rows = new ArrayList<>(rows(21,-1,-1));
        for (int i = 11; i < rows.size(); i++) {
            var p = rows.get(i);
            rows.set(i,new ProbabilisticProfile(p.profileIndex(),p.chainageMeters()+23,p.anchor(),p.normalUnit(),
                    p.minimumOffsetMeters(),p.maximumOffsetMeters(),p.sourcePitchMeters(),p.nativePitchKnown(),
                    p.noiseFloor(),p.samples(),p.modes(),p.censoredModes(),p.exactAnchorOffsetMeters(),p.orientationSupport()));
        }
        var result = CensoredDirectionReliability.confidence(rows,(a,b,c,d,e) -> true,
                CancellationProbe.NONE,ignored -> { },null);
        assertEquals(0,result[10][0],"adjacent IDs with a 25m gap have no right observed exterior");
        assertEquals(0,result[11][0]);
    }

    @Test void refusalAndCancellationReleasePartialOwnersAndSuccessLivesUntilCallerClose() {
        var rows = rows(21, -1, -1);
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            assertThrows(IllegalStateException.class, () -> CensoredDirectionReliability.confidence(rows,
                    (a,b,c,d,e) -> true, CancellationProbe.NONE,
                    units -> { throw new IllegalStateException("work refusal"); }, owner));
            assertEquals(0, ledger.currentBytes());
            var calls = new AtomicInteger();
            assertThrows(CancellationException.class, () -> CensoredDirectionReliability.confidence(rows,
                    (a,b,c,d,e) -> true, () -> {
                        if (calls.incrementAndGet() == 30) throw new CancellationException();
                        return false;
                    }, ignored -> { }, owner));
            assertEquals(0, ledger.currentBytes());
            assertEquals(1, CensoredDirectionReliability.confidence(rows, (a,b,c,d,e) -> true,
                    CancellationProbe.NONE, ignored -> { }, owner)[10][0], 1e-12);
            assertTrue(ledger.currentBytes() > 0);
        }
        assertEquals(0, ledger.currentBytes());
        var tiny = new AttemptMemoryLedger(64);
        try (var owner = tiny.rootOwner()) {
            assertThrows(ProbabilisticInference.MemoryLimit.class, () ->
                    CensoredDirectionReliability.confidence(rows, (a,b,c,d,e) -> true,
                            CancellationProbe.NONE, ignored -> { }, owner));
            assertEquals(0, tiny.currentBytes());
        }
    }

    private static List<ProbabilisticProfile> rows(int count, int first, int last) {
        var result = new ArrayList<ProbabilisticProfile>();
        for (int i = 0; i < count; i++) result.add(new ProbabilisticProfile(i, 2.0*i,
                new MetricPoint(2.0*i, 0), new MetricPoint(0,1), -7,7,1,true,.02,
                List.of(new ProbabilisticProfile.Sample(-7,.8,true)), List.of(),
                first < 0 || i >= first && i <= last ? List.of(band("band-"+i,-7)) : List.of(),
                OptionalDouble.empty(), ImageOrientationSupport.unknown(
                    ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT)));
        return result;
    }
    private static ProbabilisticProfile.CensoredMode band(String id, double boundary) {
        return new ProbabilisticProfile.CensoredMode(id,id,ProbabilisticProfile.CensorSide.LEFT,
                boundary,1,true,true,boundary,.5);
    }
    private static ProbabilisticProfile copy(ProbabilisticProfile p, List<ProbabilisticProfile.CensoredMode> bands) {
        return new ProbabilisticProfile(p.profileIndex(),p.chainageMeters(),p.anchor(),p.normalUnit(),
                p.minimumOffsetMeters(),p.maximumOffsetMeters(),p.sourcePitchMeters(),p.nativePitchKnown(),
                p.noiseFloor(),p.samples(),p.modes(),bands,p.exactAnchorOffsetMeters(),p.orientationSupport());
    }
}
