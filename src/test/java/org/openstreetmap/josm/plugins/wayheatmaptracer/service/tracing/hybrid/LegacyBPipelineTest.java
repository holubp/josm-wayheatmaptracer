package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.*;

class LegacyBPipelineTest {
    private static final TraceBudgets BUDGET = new TraceBudgets(96, 100, 1000, 32, 8);
    private static MetricPoint p(double x, double y) { return new MetricPoint(x, y); }
    private static Seed seed(String id) {
        return new Seed(id, List.of(p(0,0), p(10,0), p(20,0), p(30,0)),
                Map.of(0,0, 1,1, 2,2, 3,3));
    }
    private static List<Section> sections(Seed seed) {
        return new LegacyBReferenceFactory().partition(seed, new OccurrenceRange(0,3),
                java.util.Set.of(1,2), List.of());
    }
    private static SectionExecution run(Section section, int alternatives, long transitions) {
        var hypotheses = new ArrayList<TraceHypothesis>();
        for (int i=0; i<alternatives; i++) {
            var a=section.reference().get(0);
            var b=section.reference().get(section.reference().size()-1);
            hypotheses.add(new TraceHypothesis("b-"+i, "branch-"+i,
                    List.of(a, p((a.xMeters()+b.xMeters())/2, i+1), b),
                    List.of(ObservationOwnership.DIRECT_TWO_SIDED, ObservationOwnership.DIRECT_TWO_SIDED,
                            ObservationOwnership.DIRECT_TWO_SIDED), 1, OptionalDouble.empty(), Map.of()));
        }
        var result = new TraceHypothesisSet(TrackerMode.PROBABILISTIC, hypotheses,
                alternatives==0 ? TraceHypothesisSet.Status.NO_ROUTE : TraceHypothesisSet.Status.COMPLETE,
                false, 10, transitions, "synthetic native stage");
        return new SectionExecution(new TraceEngineRun(result,
                new TraceWorkUsage(1, transitions, alternatives, alternatives)), Optional.empty());
    }
    private static LegacyBPipeline pipeline() { return new LegacyBPipeline(new Deadline(() -> 0, 300)); }

    @Test void failureInMiddleKeepsBothNeighborRefinementsAndOriginalAlternative() {
        var seed=seed("legacy-0");
        var calls=new ArrayList<Integer>();
        var batch=pipeline().refine(List.of(seed), sections(seed), (section, remaining, probe) -> {
            calls.add(section.originalRange().firstIndex());
            return run(section, section.originalRange().firstIndex()==1 ? 0 : 1, 7);
        }, BUDGET, CancellationProbe.NONE);
        assertEquals(List.of(0,1,2), calls);
        assertEquals(List.of(Outcome.B_REFINED, Outcome.LEGACY_RETAINED, Outcome.B_REFINED),
                batch.sections().stream().map(SectionOutcome::outcome).toList());
        assertEquals(2, batch.candidates().size());
        assertEquals(seed.points(), batch.candidates().get(0).points());
        var refined=batch.candidates().get(1);
        assertEquals(List.of(p(0,0),p(5,1),p(10,0),p(20,0),p(25,1),p(30,0)), refined.points());
        assertEquals(Map.of(0,0,1,2,2,3,3,5), refined.originalToVertex());
        assertEquals(List.of(Source.B,Source.LEGACY,Source.B),
                refined.sections().stream().map(SectionChoice::source).toList());
        assertEquals(new VertexRange(2,3),refined.sections().get(1).vertices());
        assertEquals(21,batch.usage().transitions());
        assertEquals(Status.PARTIAL,batch.status());
    }

    @Test void authorityProofLimitKeepsProvedRefinementAndStopsLaterNativeWork() {
        var seed = seed("legacy-proof-limit");
        var calls = new ArrayList<Integer>();
        var batch = pipeline().refine(List.of(seed), sections(seed), (section, remaining, probe) -> {
            calls.add(section.originalRange().firstIndex());
            var nativeRun = run(section, 1, 7);
            return new SectionExecution(nativeRun.run(), nativeRun.reference(), nativeRun.eligibleHypothesisIds(), 12, true);
        }, BUDGET, CancellationProbe.NONE);
        assertEquals(List.of(0), calls);
        assertEquals(Outcome.B_REFINED, batch.sections().get(0).outcome());
        assertEquals(TraceHypothesisSet.Status.COMPLETE,
                batch.sections().get(0).execution().orElseThrow().run().result().status(),
                "A later authority-proof limit must not rewrite the native B result");
        assertEquals(List.of(Outcome.B_REFINED, Outcome.UNATTEMPTED_BUDGET, Outcome.UNATTEMPTED_BUDGET),
                batch.sections().stream().map(SectionOutcome::outcome).toList());
        assertEquals(7, batch.usage().transitions());
        assertTrue(batch.truncated());
        assertEquals(Status.RESOURCE_LIMIT, batch.status());
        assertEquals(2, batch.candidates().size(), "Keep Legacy and the already-proved refinement");
    }

    @Test void allFailuresAndFrozenSectionsKeepOnlyUnchangedLegacyAndActualWork() {
        var seed=seed("legacy-0");
        var sections=new LegacyBReferenceFactory().partition(seed,new OccurrenceRange(0,3),
                java.util.Set.of(1,2),List.of(new OccurrenceRange(1,2)));
        var calls=new ArrayList<Integer>();
        var batch=pipeline().refine(List.of(seed),sections,(section,budget,probe)-> {
            calls.add(section.originalRange().firstIndex()); return run(section,0,5);
        },BUDGET,CancellationProbe.NONE);
        assertEquals(List.of(0,2),calls);
        assertEquals(Outcome.FROZEN,batch.sections().get(1).outcome());
        assertEquals(1,batch.candidates().size());
        assertEquals(10,batch.usage().transitions());
    }

    @Test void budgetIsSharedAndExhaustionDoesNotDiscardCompletedRefinements() {
        var seed=seed("legacy-0");
        var seen=new ArrayList<Long>();
        var batch=pipeline().refine(List.of(seed),sections(seed),(section,budget,probe)-> {
            seen.add(budget.maximumTransitions()); return run(section,1,5);
        },new TraceBudgets(96,100,10,32,8),CancellationProbe.NONE);
        assertEquals(List.of(10L,5L),seen);
        assertEquals(Outcome.UNATTEMPTED_BUDGET,batch.sections().get(2).outcome());
        assertEquals(10,batch.usage().transitions());
        assertEquals(2,batch.candidates().size());
        assertTrue(batch.truncated());
        assertEquals(Status.RESOURCE_LIMIT,batch.status());
    }

    @Test void deadlineIsSharedWithLegacyAndStopsLaterSectionsWithoutSleeping() {
        var clock=new AtomicLong(0);
        var pipeline=new LegacyBPipeline(new Deadline(clock::get,300));
        clock.set(301);
        var seed=seed("legacy-0");
        var batch=pipeline.refine(List.of(seed),sections(seed),(section,budget,probe)-> {
            fail("Expired deadline must not start B"); return null;
        },BUDGET,CancellationProbe.NONE);
        assertTrue(batch.sections().stream().allMatch(s->s.outcome()==Outcome.UNATTEMPTED_BUDGET));
        assertEquals(TraceWorkUsage.none(),batch.usage());
    }

    @Test void cancellationAndIntegrityFailuresAreNeverContainedAsLegacyFallback() {
        var seed=seed("legacy-0");
        assertThrows(CancellationException.class,()->pipeline().refine(List.of(seed),sections(seed),
                (s,b,p)->run(s,1,1),BUDGET,()->true));
        assertThrows(IllegalStateException.class,()->pipeline().refine(List.of(seed),sections(seed),
                (s,b,p)->{throw new IllegalStateException("stale capture");},BUDGET,CancellationProbe.NONE));
        assertThrows(IllegalArgumentException.class,()->pipeline().refine(List.of(seed),sections(seed),
                (s,b,p)->run(s,1,1001),BUDGET,CancellationProbe.NONE));
    }

    @Test void twoParentsAndTwoSectionRanksNeverGenerateCartesianProducts() {
        var a=seed("a"); var b=seed("b"); var c=seed("c");
        var all=new ArrayList<Section>(); all.addAll(sections(a)); all.addAll(sections(b)); all.addAll(sections(c));
        var batch=pipeline().refine(List.of(a,b,c),all,(section,budget,probe)->run(section,2,1),
                new TraceBudgets(96,100,1000,32,16),CancellationProbe.NONE);
        assertEquals(6,batch.candidates().size());
        assertEquals(6,batch.sections().size());
        assertEquals(1,batch.omittedParents());
        assertTrue(batch.truncated());
        assertEquals(12,batch.usage().rawAlternatives());
    }

    @Test void missingSecondRankFallsBackToFirstRankAndRejectsForeignSectionOwnership() {
        var seed=seed("a");
        var batch=pipeline().refine(List.of(seed),sections(seed),(section,budget,probe)->
                run(section,section.originalRange().firstIndex()==1?1:2,1),BUDGET,CancellationProbe.NONE);
        assertEquals(3,batch.candidates().size());
        assertEquals("b-0",batch.candidates().get(2).sections().get(1).hypothesisId().orElseThrow());
        var wrong=sections(seed("different"));
        assertThrows(IllegalArgumentException.class,()->pipeline().refine(List.of(seed),wrong,
                (s,b,p)->run(s,1,1),BUDGET,CancellationProbe.NONE));
    }

    @Test void missingCoverageIsLocalAndIdenticalRefinementsDoNotReceiveAnotherCandidate() {
        var seed=seed("a");
        var calls=new ArrayList<Integer>();
        var batch=pipeline().refine(List.of(seed),sections(seed),(section,budget,probe)-> {
            int index=section.originalRange().firstIndex(); calls.add(index);
            if (index==1) { throw new LegacyBReferenceFactory.ReferenceUnavailableException(
                    LegacyBReferenceFactory.UnavailableReason.EVIDENCE_COVERAGE); }
            var hypothesis=new TraceHypothesis("unchanged","native-unchanged",section.reference(),
                    java.util.Collections.nCopies(section.reference().size(),ObservationOwnership.DIRECT_TWO_SIDED),
                    1,OptionalDouble.empty(),Map.of());
            return new SectionExecution(new TraceEngineRun(new TraceHypothesisSet(TrackerMode.PROBABILISTIC,
                    List.of(hypothesis),TraceHypothesisSet.Status.COMPLETE,false,2,3,"unchanged stage"),
                    new TraceWorkUsage(1,3,1,1)),Optional.empty());
        },BUDGET,CancellationProbe.NONE);
        assertEquals(List.of(0,1,2),calls);
        assertEquals(1,batch.candidates().size());
        assertEquals(Reason.EVIDENCE_COVERAGE,batch.sections().get(1).reason());
        assertEquals(6,batch.usage().transitions());
        assertTrue(batch.sections().get(0).execution().isPresent());
    }

    @Test void nativeResourceRefusalStopsNewWorkAndRetainsItsFactualUsage() {
        var seed=seed("a");
        var batch=pipeline().refine(List.of(seed),sections(seed),(section,budget,probe)->
                new SectionExecution(new TraceEngineRun(new TraceHypothesisSet(TrackerMode.PROBABILISTIC,
                        List.of(),TraceHypothesisSet.Status.RESOURCE_LIMIT,false,1,7,"native memory refusal"),
                        new TraceWorkUsage(1,7,0,0)),Optional.empty()),BUDGET,CancellationProbe.NONE);
        assertEquals(Reason.RESOURCE_LIMIT,batch.sections().get(0).reason());
        assertEquals(Outcome.UNATTEMPTED_BUDGET,batch.sections().get(1).outcome());
        assertEquals(Reason.RESOURCE_LIMIT,batch.sections().get(1).reason());
        assertEquals(7,batch.usage().transitions());
        assertEquals(1,batch.candidates().size());
        assertEquals(Status.RESOURCE_LIMIT,batch.status());
    }

    @Test void inFlightDeadlineRetainsNativeWorkWithoutBeingReportedAsUserCancellation() {
        var clock=new AtomicLong();
        var pipeline=new LegacyBPipeline(new Deadline(clock::get,300));
        var seed=seed("a");
        var batch=pipeline.refine(List.of(seed),sections(seed),(section,budget,probe)-> {
            assertFalse(probe.cancelled()); clock.set(301); assertTrue(probe.cancelled());
            return new SectionExecution(new TraceEngineRun(new TraceHypothesisSet(TrackerMode.PROBABILISTIC,
                    List.of(),TraceHypothesisSet.Status.CANCELLED,false,2,7,"cancelled native work"),
                    new TraceWorkUsage(2,7,0,0)),Optional.empty());
        },BUDGET,CancellationProbe.NONE);
        assertEquals(Reason.DEADLINE,batch.sections().get(0).reason());
        assertEquals(Outcome.UNATTEMPTED_BUDGET,batch.sections().get(2).outcome());
        assertEquals(7,batch.usage().transitions());
    }

    @Test void completePolicyFrozenAndAbsentLegacyHaveExplicitTerminalStatuses() {
        var seed=seed("a");
        var complete=pipeline().refine(List.of(seed),sections(seed),(s,b,p)->run(s,1,1),BUDGET,CancellationProbe.NONE);
        assertEquals(Status.COMPLETE,complete.status());
        var frozen=new LegacyBReferenceFactory().partition(seed,new OccurrenceRange(0,3),java.util.Set.of(1,2),
                List.of(new OccurrenceRange(0,3)));
        var retained=pipeline().refine(List.of(seed),frozen,(s,b,p)->{fail("frozen");return null;},
                BUDGET,CancellationProbe.NONE);
        assertEquals(Status.COMPLETE,retained.status());
        var noLegacy=pipeline().refine(List.of(),List.of(),(s,b,p)->{fail("no seed");return null;},
                BUDGET,CancellationProbe.NONE);
        assertEquals(Status.NO_ROUTE,noLegacy.status());
    }
}
