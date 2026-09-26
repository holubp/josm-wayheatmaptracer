package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.GuidedProbabilisticTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.InferenceProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.LateralStateCell;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticStructuralGuide;

/** T033-T038: independent A proposals and unguided B orchestration. */
class V022HybridRecoveryTest {
    @Test
    void cancelledSecondStageReportsPriorWorkWithoutClaimingRetainedRoutes() {
        Fixture fixture = fixture();
        BudgetReportingTraceEngine b = (request, evidence, network, cancellation) ->
                new TraceEngineRun(route(TrackerMode.PROBABILISTIC, "b", 1.0),
                        new TraceWorkUsage(3, 4, 1, 1));
        BudgetReportingTraceEngine a = (request, evidence, network, cancellation) ->
                new TraceEngineRun(new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE,
                        List.of(), TraceHypothesisSet.Status.CANCELLED, false, 0, 0,
                        "cancelled"), TraceWorkUsage.none());

        TraceEngineRun run = new HybridTraceEngine(a, b).traceWithUsage(
                fixture.request(), fixture.evidence(), fixture.network(), CancellationProbe.NONE);

        assertEquals(TraceHypothesisSet.Status.CANCELLED, run.result().status());
        assertEquals(3, run.usage().pairVisits());
        assertEquals(4, run.usage().transitions());
        assertEquals(1, run.usage().rawAlternatives());
        assertEquals(0, run.usage().distinctAlternatives());
    }

    @Test
    void productionHybridUsageChargesCompletedUnguidedAndCorridorStages() {
        Fixture fixture = fixture();
        BudgetReportingTraceEngine a = (request, evidence, network, cancellation) ->
                new TraceEngineRun(route(TrackerMode.CORRIDOR_AWARE, "a", 1.0),
                        new TraceWorkUsage(5, 7, 1, 1));
        BudgetReportingTraceEngine b = (request, evidence, network, cancellation) ->
                new TraceEngineRun(route(TrackerMode.PROBABILISTIC, "b", 1.0),
                        new TraceWorkUsage(3, 4, 1, 1));

        TraceEngineRun run = new HybridTraceEngine(a, b).traceWithUsage(
                fixture.request(), fixture.evidence(), fixture.network(), CancellationProbe.NONE);

        assertEquals(8, run.usage().pairVisits());
        assertEquals(11, run.usage().transitions());
        assertEquals(2, run.usage().rawAlternatives());
        assertEquals(2, run.usage().distinctAlternatives());
        assertFalse(run.result().hypotheses().isEmpty());
    }

    @Test
    void T033_unguidedBIsRetainedAlongsideA() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(route(TrackerMode.CORRIDOR_AWARE, "a", 4.0),
                route(TrackerMode.PROBABILISTIC, "b", 2.0)).trace(fixture.request(),
                fixture.evidence(), fixture.network());

        assertEquals(2, result.hypotheses().size());
        assertTrue(result.hypotheses().stream().anyMatch(route -> route.id().startsWith("hybrid-b-")));
        assertEquals(TraceHypothesisSet.Status.AMBIGUOUS, result.status());
    }

    @Test
    void T034_aProposalIsMarkedStructuralAndDoesNotAddPosteriorCertainty() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(route(TrackerMode.CORRIDOR_AWARE, "a", 1.0),
                route(TrackerMode.PROBABILISTIC, "b", 2.0)).trace(fixture.request(),
                fixture.evidence(), fixture.network());

        TraceHypothesis proposal = result.hypotheses().stream()
            .filter(route -> route.id().startsWith("hybrid-a-")).findFirst().orElseThrow();
        assertEquals(1.0, proposal.diagnostics().get("structuralPriorOnly"));
        assertTrue(proposal.posteriorProbability().isEmpty());
        assertEquals(0.7, proposal.diagnostics().get("sourcePosteriorProbability"));
    }

    @Test
    void T035_falseApexCannotCreateAnUnobservedGuidedRoute() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(route(TrackerMode.CORRIDOR_AWARE, "false-apex", 0.1),
                noRoute(TrackerMode.PROBABILISTIC)).trace(fixture.request(), fixture.evidence(), fixture.network());

        assertEquals(1, result.hypotheses().size());
        assertFalse(result.hypotheses().stream().anyMatch(route -> route.id().contains("guided")));
    }

    @Test
    void T036_bWorksWhenAIsEmpty() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(noRoute(TrackerMode.CORRIDOR_AWARE),
                route(TrackerMode.PROBABILISTIC, "b", 2.0)).trace(fixture.request(),
                fixture.evidence(), fixture.network());

        assertEquals(TraceHypothesisSet.Status.COMPLETE, result.status());
        assertEquals("hybrid-b-b", result.hypotheses().get(0).id());
    }

    @Test
    void T037_sameImageEvidenceIsNotDoubleCounted() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(route(TrackerMode.CORRIDOR_AWARE, "a", 1.0),
                route(TrackerMode.PROBABILISTIC, "b", 2.0)).trace(fixture.request(),
                fixture.evidence(), fixture.network());

        assertEquals(1, fixture.evidence().independentEvidenceGroups().size());
        assertTrue(result.hypotheses().stream().allMatch(route ->
                route.diagnostics().get("independentImageObservationCount") == 1.0));
    }

    @Test
    void T038_strongerLaterBEvidenceRemainsAvailableToCommonRanking() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(route(TrackerMode.CORRIDOR_AWARE, "a", 0.1),
                route(TrackerMode.PROBABILISTIC, "strong-b", 8.0)).trace(fixture.request(),
                fixture.evidence(), fixture.network());

        assertTrue(result.hypotheses().stream().anyMatch(route ->
                route.branchSignature().equals("b:strong-b")));
        assertTrue(result.explanation().contains("common final ranking"));
    }

    @Test
    void T039_qualifiedStructuralRunAddsGuidedBWithoutReplacingUnguidedB() {
        Fixture fixture = fixture();
        List<MetricPoint> points = java.util.stream.IntStream.range(0, 7)
            .mapToObj(index -> new MetricPoint(2.0 * index, 1.0)).toList();
        TraceHypothesis qualified = new TraceHypothesis("qualified-a", "qualified-a", points,
            java.util.Collections.nCopies(points.size(), ObservationOwnership.DIRECT_TWO_SIDED),
            1.0, 0.7, Map.of("scaleConflictFraction", 0.0, "localDefect", 0.0,
                "junctionAmbiguity", 0.0, "competingPersistentMode", 0.0));
        TraceHypothesisSet a = new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE,
            List.of(qualified), TraceHypothesisSet.Status.COMPLETE, false, 7, 6, "complete");

        TraceHypothesisSet result = engine(a, route(TrackerMode.PROBABILISTIC, "b", 2.0))
            .trace(fixture.request(), fixture.evidence(), fixture.network());

        assertTrue(result.hypotheses().stream().anyMatch(route -> route.id().startsWith("hybrid-b-")));
        assertTrue(result.hypotheses().stream().anyMatch(route ->
            route.id().startsWith("hybrid-guided-b-")));
    }

    @Test
    void T040_productionHybridRunsActualAAndBothExactBVariants() {
        Fixture fixture = productionFixture();
        CorridorEngineAdapter corridorEngine = new CorridorEngineAdapter("synthetic");
        TraceHypothesisSet standaloneA = corridorEngine.trace(
            withEngine(fixture.request(), TrackerMode.CORRIDOR_AWARE), fixture.evidence(), fixture.network());
        TraceHypothesisSet result = new HybridTraceEngine(corridorEngine,
            new ProbabilisticTraceEngine("synthetic")).trace(fixture.request(), fixture.evidence(),
                fixture.network());

        TraceHypothesis hybridA = result.hypotheses().stream()
            .filter(route -> route.id().startsWith("hybrid-a-")).findFirst().orElseThrow();
        assertEquals(standaloneA.hypotheses().get(0).points(), hybridA.points());
        assertEquals(standaloneA.hypotheses().get(0).objective(), hybridA.objective());
        assertTrue(result.hypotheses().stream().anyMatch(route -> route.id().startsWith("hybrid-b-")));
        TraceHypothesis guided = result.hypotheses().stream()
            .filter(route -> route.id().startsWith("hybrid-guided-b-")).findFirst()
            .orElseThrow(() -> new AssertionError(result));
        assertEquals(1.0, guided.diagnostics().get("structuralGuideApplied"));
        assertEquals(0.5, guided.diagnostics().get("structuralGuideWeight"));
        assertEquals(4.0, guided.diagnostics().get("structuralGuideCap"));
        assertEquals(1.0, guided.diagnostics().get("structuralGuideSameImage"));
        assertEquals(1.0, guided.diagnostics().get("independentImageObservationCount"));
        assertTrue(result.evaluatedStates() > 0);
        assertTrue(result.evaluatedTransitions() > 0);
    }

    @Test
    void T041_wrongQualifiedAStructureCannotSuppressActuallyObservedUnguidedB() {
        Fixture fixture = productionFixture();
        List<MetricPoint> wrongPoints = java.util.stream.IntStream.range(0, 11)
            .mapToObj(index -> new MetricPoint(5.0 + 2.0 * index, 21.0)).toList();
        List<ObservationOwnership> support = new java.util.ArrayList<>(
            java.util.Collections.nCopies(11, ObservationOwnership.DIRECT_TWO_SIDED));
        support.set(0, ObservationOwnership.FIXED_TOPOLOGY_ONLY);
        support.set(10, ObservationOwnership.FIXED_TOPOLOGY_ONLY);
        TraceHypothesis wrong = new TraceHypothesis("wrong-a", "wrong-a", wrongPoints, support,
            -100.0, 0.99, Map.of("scaleConflictFraction", 0.0, "localDefect", 0.0,
                "junctionAmbiguity", 0.0, "competingPersistentMode", 0.0));
        TraceHypothesisSet a = new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE, List.of(wrong),
            TraceHypothesisSet.Status.COMPLETE, false, 11, 10, "complete");
        ProbabilisticTraceEngine probabilisticEngine = new ProbabilisticTraceEngine("synthetic");
        TraceHypothesisSet standaloneB = probabilisticEngine.trace(
            withEngine(fixture.request(), TrackerMode.PROBABILISTIC), fixture.evidence(), fixture.network());
        TraceHypothesisSet result = new HybridTraceEngine(stub(TrackerMode.CORRIDOR_AWARE, a),
            probabilisticEngine).trace(fixture.request(), fixture.evidence(), fixture.network());

        TraceHypothesis unguided = result.hypotheses().stream()
            .filter(route -> route.id().startsWith("hybrid-b-")).findFirst().orElseThrow();
        assertEquals(standaloneB.hypotheses().get(0).points(), unguided.points());
        assertEquals(standaloneB.hypotheses().get(0).objective(), unguided.objective());
        assertEquals(standaloneB.hypotheses().get(0).posteriorProbability().orElseThrow(),
            unguided.diagnostics().get("sourcePosteriorProbability"));
        assertTrue(unguided.points().subList(1, unguided.points().size() - 1).stream()
            .allMatch(point -> Math.abs(point.yMeters() - 18.0) < 1.5));
        assertTrue(result.hypotheses().stream()
            .anyMatch(route -> route.id().startsWith("hybrid-guided-b-")));
        assertTrue(unguided.support().subList(1, unguided.support().size() - 1).stream()
            .allMatch(value -> value == ObservationOwnership.DIRECT_TWO_SIDED));
    }

    @Test
    void T042_guideCostIsCappedAndCannotCreateImageOwnership() {
        Fixture fixture = productionFixture();
        List<MetricPoint> points = java.util.stream.IntStream.range(0, 11)
            .mapToObj(index -> new MetricPoint(5.0 + 2.0 * index, 18.0)).toList();
        TraceHypothesis hypothesis = new TraceHypothesis("a", "a", points,
            java.util.Collections.nCopies(11, ObservationOwnership.DIRECT_TWO_SIDED),
            1.0, 0.7, Map.of("scaleConflictFraction", 0.0, "localDefect", 0.0,
                "junctionAmbiguity", 0.0, "competingPersistentMode", 0.0));
        TraceHypothesisSet a = new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE,
            List.of(hypothesis), TraceHypothesisSet.Status.COMPLETE, false, 11, 10, "complete");
        ProbabilisticStructuralGuide guide = ProbabilisticStructuralGuide
            .qualified(fixture.request(), a, fixture.network()).orElseThrow();
        List<LateralStateCell> cells = List.of(
            new LateralStateCell(3.0, 1.0, false, false, "ridge"),
            new LateralStateCell(-6.0, 1.0, false, false, "ridge"));
        InferenceProfile direct = new InferenceProfile(10.0, new MetricPoint(15.0, 15.0),
            new MetricPoint(0.0, 1.0), cells, new double[] {1.0, 1.0}, List.of(), 0.0,
            ObservationOwnership.DIRECT_TWO_SIDED, false);
        InferenceProfile guided = guide.apply(direct, 1.0);
        assertEquals(0.0, guided.structuralGuideCost(0), 1.0e-12);
        assertEquals(4.0, guided.structuralGuideCost(1), 1.0e-12);
        InferenceProfile missing = new InferenceProfile(10.0, new MetricPoint(15.0, 15.0),
            new MetricPoint(0.0, 1.0), cells, new double[] {1.0, 1.0}, List.of(), 0.0,
            ObservationOwnership.NO_SIGNAL_VALID_RASTER, true);
        InferenceProfile stillMissing = guide.apply(missing, 1.0);
        assertEquals(ObservationOwnership.NO_SIGNAL_VALID_RASTER, stillMissing.ownership());
        assertEquals(0.0, stillMissing.structuralGuideCost(0));
        assertEquals(0.0, stillMissing.structuralGuideCost(1));
    }

    @Test
    void T043_cancelledBStopsBeforeAAndPublishesNoPartialRoutes() {
        Fixture fixture = fixture();
        AtomicInteger aCalls = new AtomicInteger();
        TraceEngine a = (request, evidence, network, cancellation) -> {
            aCalls.incrementAndGet();
            return route(TrackerMode.CORRIDOR_AWARE, "a", 1.0);
        };
        TraceEngine cancelledB = (request, evidence, network, cancellation) ->
            new TraceHypothesisSet(TrackerMode.PROBABILISTIC, List.of(),
                TraceHypothesisSet.Status.CANCELLED, false, 3, 2, "cancelled");

        TraceHypothesisSet result = new HybridTraceEngine(a, cancelledB).trace(
            fixture.request(), fixture.evidence(), fixture.network());

        assertEquals(TraceHypothesisSet.Status.CANCELLED, result.status());
        assertTrue(result.hypotheses().isEmpty());
        assertEquals(0, aCalls.get());
        assertEquals(3, result.evaluatedStates());
        assertEquals(2, result.evaluatedTransitions());
    }

    @Test
    void T044_partialResourceLimitRemainsExplicitWhileRetainedRoutesStayInspectable() {
        Fixture fixture = fixture();
        TraceHypothesisSet limitedA = new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE,
            route(TrackerMode.CORRIDOR_AWARE, "a", 1.0).hypotheses(),
            TraceHypothesisSet.Status.RESOURCE_LIMIT, true, 5, 7, "limited");

        TraceHypothesisSet result = engine(limitedA, route(TrackerMode.PROBABILISTIC, "b", 2.0))
            .trace(fixture.request(), fixture.evidence(), fixture.network());

        assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, result.status());
        assertTrue(result.alternativesTruncated());
        assertEquals(2, result.hypotheses().size());
        assertTrue(result.hypotheses().stream().map(TraceHypothesis::branchSignature)
            .allMatch(new java.util.HashSet<>()::add));
    }

    @Test
    void T045_censoredOrUnprovenASectionsCannotGuideB() {
        Fixture fixture = productionFixture();
        List<MetricPoint> points = java.util.stream.IntStream.range(0, 11)
            .mapToObj(index -> new MetricPoint(5.0 + 2.0 * index, 18.0)).toList();
        List<ObservationOwnership> interrupted = new java.util.ArrayList<>(
            java.util.Collections.nCopies(11, ObservationOwnership.DIRECT_TWO_SIDED));
        interrupted.set(5, ObservationOwnership.CORE_CENSORED);
        TraceHypothesis censored = new TraceHypothesis("censored", "censored", points, interrupted,
            1.0, 0.7, Map.of("scaleConflictFraction", 0.0, "localDefect", 0.0,
                "junctionAmbiguity", 0.0, "competingPersistentMode", 0.0));
        TraceHypothesis unproven = new TraceHypothesis("unproven", "unproven", points,
            java.util.Collections.nCopies(11, ObservationOwnership.DIRECT_TWO_SIDED),
            1.0, 0.7, Map.of());

        assertTrue(ProbabilisticStructuralGuide.qualified(fixture.request(),
            new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE, List.of(censored),
                TraceHypothesisSet.Status.COMPLETE, false, 11, 10, "complete"), fixture.network()).isEmpty());
        assertTrue(ProbabilisticStructuralGuide.qualified(fixture.request(),
            new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE, List.of(unproven),
                TraceHypothesisSet.Status.COMPLETE, false, 11, 10, "complete"), fixture.network()).isEmpty());
    }

    @Test
    void T046_attemptWideDistinctCapCannotBeMultipliedAcrossThreeStages() {
        Fixture fixture = fixture();
        List<MetricPoint> points = java.util.stream.IntStream.range(0, 7)
            .mapToObj(index -> new MetricPoint(2.0 * index, 1.0)).toList();
        TraceHypothesis qualified = new TraceHypothesis("qualified-a", "qualified-a", points,
            java.util.Collections.nCopies(7, ObservationOwnership.DIRECT_TWO_SIDED), 1.0, 0.7,
            Map.of("scaleConflictFraction", 0.0, "localDefect", 0.0,
                "junctionAmbiguity", 0.0, "competingPersistentMode", 0.0));
        TraceHypothesisSet a = new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE,
            List.of(qualified), TraceHypothesisSet.Status.COMPLETE, false, 1, 1, "complete");
        TraceRequest bounded = withBudgets(fixture.request(),
            new TraceBudgets(96, 100, 100, 1, 1));

        TraceHypothesisSet result = engine(a, route(TrackerMode.PROBABILISTIC, "b", 2.0))
            .trace(bounded, fixture.evidence(), fixture.network());

        assertEquals(1, result.hypotheses().size());
        assertTrue(result.hypotheses().get(0).id().startsWith("hybrid-b-"));
        assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, result.status());
        assertTrue(result.alternativesTruncated());
    }

    @Test
    void T049_defaultRawAlternativeBudgetRetainsExactBAndOmitsA() {
        Fixture fixture = fixture();
        AtomicInteger aCalls = new AtomicInteger();
        TraceEngine a = (request, evidence, network, cancellation) -> {
            aCalls.incrementAndGet();
            return route(TrackerMode.CORRIDOR_AWARE, "a", 1.0);
        };
        BudgetReportingTraceEngine b = new BudgetReportingTraceEngine() {
            @Override
            public TraceEngineRun traceWithUsage(TraceRequest request, EvidenceSnapshot evidence,
                    NetworkSnapshot network, CancellationProbe cancellation) {
                assertEquals(fixture.request().budgets(), request.budgets());
                TraceHypothesisSet result = routeWithCounters(
                    TrackerMode.PROBABILISTIC, "b", 12);
                return new TraceEngineRun(result, new TraceWorkUsage(20, 12,
                    request.budgets().maximumRawAlternatives(), 1));
            }
        };

        TraceHypothesisSet result = new HybridTraceEngine(a, b).trace(
            fixture.request(), fixture.evidence(), fixture.network());

        assertEquals(TraceBudgets.defaults(), fixture.request().budgets());
        assertEquals(0, aCalls.get());
        assertEquals(1, result.hypotheses().size());
        assertTrue(result.hypotheses().get(0).id().startsWith("hybrid-b-"));
        assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, result.status());
        assertTrue(result.alternativesTruncated());
        assertTrue(result.explanation().contains("raw-alternative budget exhausted"));
    }

    @Test
    void T047_unguidedBConsumesItsFullBudgetBeforeOtherHybridWorkIsAdmitted() {
        Fixture fixture = fixture();
        TraceRequest bounded = withBudgets(fixture.request(),
            new TraceBudgets(96, 10, 10, 4, 4));
        TraceEngine a = (request, evidence, network, cancellation) ->
            routeWithCounters(TrackerMode.CORRIDOR_AWARE, "a", request.budgets().maximumTransitions());
        GuidedProbabilisticTraceEngine b = new GuidedProbabilisticTraceEngine() {
            @Override
            public TraceEngineRun traceWithUsage(TraceRequest request, EvidenceSnapshot evidence,
                    NetworkSnapshot network, CancellationProbe cancellation) {
                return run(routeWithCounters(TrackerMode.PROBABILISTIC, "b",
                    request.budgets().maximumTransitions()), request.budgets().maximumPairVisits());
            }

            @Override
            public TraceEngineRun traceGuidedWithUsage(TraceRequest request, EvidenceSnapshot evidence,
                    NetworkSnapshot network, ProbabilisticStructuralGuide guide,
                    CancellationProbe cancellation) {
                return run(routeWithCounters(TrackerMode.PROBABILISTIC, "guided",
                    request.budgets().maximumTransitions()), request.budgets().maximumPairVisits());
            }
        };

        TraceHypothesisSet result = new HybridTraceEngine(a, b).trace(
            bounded, fixture.evidence(), fixture.network());

        assertEquals(10, result.evaluatedTransitions());
        assertEquals(1, result.hypotheses().size());
        assertTrue(result.hypotheses().get(0).id().startsWith("hybrid-b-"));
        assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, result.status());
    }

    @Test
    void T048_thrownCancellationAfterCompletedBRetainsItsCountersAndSkipsA() {
        Fixture fixture = fixture();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger aCalls = new AtomicInteger();
        TraceEngine a = (request, evidence, network, cancellation) -> {
            aCalls.incrementAndGet();
            return routeWithCounters(TrackerMode.CORRIDOR_AWARE, "a", 3);
        };
        TraceEngine b = (request, evidence, network, cancellation) -> {
            cancelled.set(true);
            return routeWithCounters(TrackerMode.PROBABILISTIC, "b", 7);
        };

        TraceHypothesisSet result = new HybridTraceEngine(a, b).trace(
            fixture.request(), fixture.evidence(), fixture.network(), cancelled::get);

        assertEquals(TraceHypothesisSet.Status.CANCELLED, result.status());
        assertEquals(0, aCalls.get());
        assertEquals(2, result.evaluatedStates());
        assertEquals(7, result.evaluatedTransitions());
    }

    @Test
    void T050_aPreflightRejectionRetainsMeasuredRawProposalUsageInsideHybrid() {
        Fixture fixture = productionFixture();
        TraceRequest constrained = withBudgets(fixture.request(),
            new TraceBudgets(96, 2, 2, 2, 2));
        AtomicReference<TraceEngineRun> measuredA = new AtomicReference<>();
        BudgetReportingTraceEngine a = new BudgetReportingTraceEngine() {
            private final CorridorEngineAdapter delegate = new CorridorEngineAdapter("synthetic");

            @Override
            public TraceEngineRun traceWithUsage(TraceRequest request, EvidenceSnapshot evidence,
                    NetworkSnapshot network, CancellationProbe cancellation) {
                TraceEngineRun run = delegate.traceWithUsage(request, evidence, network, cancellation);
                measuredA.set(run);
                return run;
            }
        };
        BudgetReportingTraceEngine b = (request, evidence, network, cancellation) ->
            run(routeWithCounters(TrackerMode.PROBABILISTIC, "b", 1), 1);

        TraceHypothesisSet result = new HybridTraceEngine(a, b).trace(
            constrained, fixture.evidence(), fixture.network());

        assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, result.status());
        assertEquals(1, result.hypotheses().size());
        assertTrue(result.hypotheses().get(0).id().startsWith("hybrid-b-"));
        assertEquals(1, measuredA.get().usage().rawAlternatives());
        assertEquals(0, measuredA.get().usage().pairVisits());
        assertEquals(0, measuredA.get().usage().transitions());
    }

    private static HybridTraceEngine engine(TraceHypothesisSet a, TraceHypothesisSet b) {
        return new HybridTraceEngine(stub(TrackerMode.CORRIDOR_AWARE, a),
                stub(TrackerMode.PROBABILISTIC, b));
    }

    private static TraceEngine stub(TrackerMode expected, TraceHypothesisSet result) {
        if (expected == TrackerMode.PROBABILISTIC) {
            return new GuidedProbabilisticTraceEngine() {
                @Override
                public TraceEngineRun traceWithUsage(TraceRequest request, EvidenceSnapshot evidence,
                        NetworkSnapshot network, CancellationProbe cancellation) {
                    assertEquals(expected, request.engine());
                    return run(result, result.evaluatedTransitions());
                }

                @Override
                public TraceEngineRun traceGuidedWithUsage(TraceRequest request,
                        EvidenceSnapshot evidence, NetworkSnapshot network,
                        ProbabilisticStructuralGuide guide, CancellationProbe cancellation) {
                    assertEquals(expected, request.engine());
                    return run(result, result.evaluatedTransitions());
                }
            };
        }
        return (request, evidence, network, cancellation) -> {
            assertEquals(expected, request.engine());
            return result;
        };
    }

    private static TraceHypothesisSet route(TrackerMode mode, String id, double objective) {
        TraceHypothesis hypothesis = new TraceHypothesis(id, id,
                List.of(new MetricPoint(0, 0), new MetricPoint(4, 0)),
                List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED), objective, 0.7, Map.of());
        return new TraceHypothesisSet(mode, List.of(hypothesis), TraceHypothesisSet.Status.COMPLETE,
                false, 2, 1, "complete");
    }

    private static TraceEngineRun run(TraceHypothesisSet result, long pairVisits) {
        return new TraceEngineRun(result, new TraceWorkUsage(pairVisits,
            result.evaluatedTransitions(), result.hypotheses().size(), result.hypotheses().size()));
    }

    private static TraceHypothesisSet routeWithCounters(
            TrackerMode mode, String id, long transitions) {
        TraceHypothesisSet base = route(mode, id, 1.0);
        return new TraceHypothesisSet(mode, base.hypotheses(), TraceHypothesisSet.Status.COMPLETE,
            false, 2, transitions, "complete");
    }

    private static TraceHypothesisSet noRoute(TrackerMode mode) {
        return new TraceHypothesisSet(mode, List.of(), TraceHypothesisSet.Status.NO_ROUTE,
                false, 0, 0, "no route");
    }

    private static Fixture fixture() {
        GeographicPoint origin = new GeographicPoint(42, 19);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(first, new DetachedNode(first, origin, Map.of(), false, false));
        values.put(last, new DetachedNode(last, frame.toGeographic(new MetricPoint(4, 0)),
                Map.of(), false, false));
        values.put(way, new DetachedWay(way, List.of(first, last), Map.of("highway", "path"),
                false, false));
        MetricRegion region = MetricRegion.rectangle(-5, -5, 6, 6);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
                "hybrid-test-v1", values.keySet(), Set.of(way), Set.of(), Set.of(first, last), Set.of(),
                Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region,
                false, true, true, true);
        NetworkSnapshot network = new NetworkSnapshot("network", SnapshotRole.CAPTURED_BEFORE,
                "dataset", 1, closure, values,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                    .closedWorldReferrerWatches(values));
        int size = 12;
        double[] intensity = new double[size * size];
        boolean[] valid = new boolean[intensity.length];
        java.util.Arrays.fill(intensity, 0.8);
        java.util.Arrays.fill(valid, true);
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        ScalarEvidenceField field = new ScalarEvidenceField(size, size, intensity, valid, lineage);
        EvidenceResolution resolution = EvidenceResolution.nativeSource(1, 1);
        EvidenceSnapshot evidence = new EvidenceSnapshot("evidence", frame,
                new RasterMetricTransform("hybrid-test-raster",
                        RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                        new MetricPoint(-5, -5), 1, 0, 0, 1, 1), resolution,
                MetricRegion.rectangle(-4, -4, 5, 5), region, Map.of("synthetic", field), "source");
        ProfileChainage chainage = new ProfileChainage(
            List.of(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0), 2.0);
        TraceRequest request = new TraceRequest(way, new OccurrenceRange(0, 1), TrackerMode.HYBRID,
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(4), TraceBudgets.defaults(),
                evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
                "settings", "parameters", "sampler", 2, chainage, resolution);
        return new Fixture(request, evidence, network);
    }

    private static TraceRequest withBudgets(TraceRequest request, TraceBudgets budgets) {
        return new TraceRequest(request.selectedWayKey(), request.selectedRange(), request.engine(),
            request.geometryMode(), request.permissions(), budgets, request.evidenceSnapshotId(),
            request.evidenceContentHash(), request.networkSnapshotId(), request.networkContentHash(),
            request.settingsHash(), request.parameterHash(), request.samplerId(),
            request.configuredSampleStepMeters(), request.profileChainage(), request.evidenceResolution(),
            request.corridorInput());
    }

    private static TraceRequest withEngine(TraceRequest request, TrackerMode engine) {
        return new TraceRequest(request.selectedWayKey(), request.selectedRange(), engine,
            request.geometryMode(), request.permissions(), request.budgets(), request.evidenceSnapshotId(),
            request.evidenceContentHash(), request.networkSnapshotId(), request.networkContentHash(),
            request.settingsHash(), request.parameterHash(), request.samplerId(),
            request.configuredSampleStepMeters(), request.profileChainage(), request.evidenceResolution(),
            request.corridorInput());
    }

    private static Fixture productionFixture() {
        int width = 41;
        int height = 35;
        GeographicPoint origin = new GeographicPoint(50, 14);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(49.99, 13.99), new GeographicPoint(50.01, 14.02));
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0, 0), 1, 0, 0, 1);
        double[] intensity = new double[width * height];
        Arrays.fill(intensity, 0.02);
        for (int y = 17; y <= 19; y++) {
            Arrays.fill(intensity, y * width, (y + 1) * width, y == 18 ? 1.0 : 0.6);
        }
        boolean[] valid = new boolean[intensity.length];
        Arrays.fill(valid, true);
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, intensity, valid, lineage);
        EvidenceResolution resolution = EvidenceResolution.nativeSource(2.0, 1.0).resampledTo(1.0);
        MetricRegion decision = MetricRegion.rectangle(1, 1, 39, 33);
        MetricRegion evidenceRegion = MetricRegion.rectangle(-0.5, -0.5, 40.5, 34.5);
        EvidenceSnapshot evidence = new EvidenceSnapshot("production-hybrid", frame, transform,
            resolution, decision, evidenceRegion, Map.of("synthetic", field), "source");
        GeographicPoint firstPoint = frame.toGeographic(new MetricPoint(5.0, 18.0));
        GeographicPoint lastPoint = frame.toGeographic(new MetricPoint(25.0, 18.0));
        double actualLength = frame.toMetric(firstPoint).distanceTo(frame.toMetric(lastPoint));
        double configuredStep = actualLength / 10.0 + 1.0e-6;
        List<Double> chainage = java.util.stream.IntStream.range(0, 11)
            .mapToObj(index -> actualLength * index / 10.0).toList();
        List<DetachedProfileSamplingLocation> locations = java.util.stream.IntStream.range(0, 11)
            .mapToObj(index -> DetachedProfileSamplingLocation.at(
                frame.toGeographic(new MetricPoint(5.0 + 2.0 * index, 18.0)), frame, transform,
                chainage.get(index))).toList();
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 11);
        PrimitiveKey last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 12);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 13);
        Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>();
        primitives.put(first, new DetachedNode(first, locations.get(0).geographicPoint(), Map.of(), false, false));
        primitives.put(last, new DetachedNode(last, locations.get(10).geographicPoint(), Map.of(), false, false));
        primitives.put(way, new DetachedWay(way, List.of(first, last), Map.of("highway", "path"), false, false));
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
            "hybrid-production-v1", primitives.keySet(), Set.of(way), Set.of(), Set.of(first, last), Set.of(),
            Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), decision, decision,
            false, true, true, true);
        NetworkSnapshot network = new NetworkSnapshot("production-network", SnapshotRole.CAPTURED_BEFORE,
            "dataset", 1, closure, primitives,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                .closedWorldReferrerWatches(primitives));
        ProfileChainage measured = new ProfileChainage(chainage, configuredStep);
        TraceRequest request = new TraceRequest(way, new OccurrenceRange(0, 1), TrackerMode.HYBRID,
            AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(6.0), TraceBudgets.fundedHybrid(),
            evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
            "settings", "parameters", "sampler", configuredStep, measured, resolution,
            Optional.of(new CorridorTraceInput(locations, 1.0)));
        return new Fixture(request, evidence, network);
    }

    private record Fixture(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network) { }
}
