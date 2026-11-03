package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.CompetitiveAdmissionExecutor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class CompetitiveAdmissionExecutorTest {
    @Test void lateCheapOptionalMustBeatAnAlreadyCertifiedOrdinaryPath() {
        var vertices = List.of(
            new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, false, 1),
            new Vertex(2, 1, 0, true, false, false, 2),
            new Vertex(3, 2, 0, true, false, true, 3));
        var arcs = List.of(
            new Arc(0, 0, 1, 1, 0, false, false, 0),
            new Arc(1, 0, 2, 1, 0, true, false, 1),
            new Arc(2, 1, 3, 1, 0, false, false, 2),
            new Arc(3, 2, 3, 1, 0, true, false, 3));
        var source = new FixedSource(Map.of(
            0, Admission.syntheticExact(.2, 0, 0, 0, "ordinary-0"),
            1, Admission.syntheticExact(.35, 0, 0, 0, "optional-1"),
            2, Admission.syntheticExact(.2, 0, 0, 0, "ordinary-2"),
            3, Admission.syntheticExact(.001, 0, 0, 0, "optional-3")));
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var graph = Graph.createSynthetic(vertices, arcs, java.util.Set.of(), 1, budget, owner);
            var actual = new CompetitiveAdmissionExecutor(graph, source,
                budget, owner).run();
            assertEquals(Status.CERTIFIED, actual.status());
            assertEquals(List.of(0, 2, 3), actual.vertexIds());
            assertEquals(List.of(1, 3), actual.arcIds());
            assertMatchesExhaustiveOracle(actual, vertices, arcs, source);
            assertEquals(4, actual.potentialArcs());
            assertEquals(4, actual.measuredAdmitted());
            assertEquals(0, actual.measuredRefused());
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void scalarLowerBoundCoversOrdinaryUnavailableAndNegativeIntensityRange() {
        assertTrue(OutwardCostBounds.scalarLossLower(2, 1, -.5) <= 2.0,
            "ordinary unavailable loss one must bound a source whose measured loss exceeds one");
        assertTrue(OutwardCostBounds.scalarLossLower(2, 1, 1.5) <= -1.0,
            "above-one native intensity permits a negative measured Q");
    }

    @Test void publicAdmissionComputesQOnlyFromCompleteNativeScalarOrExplicitOrdinaryLoss() {
        var scalar = new BilinearSegmentIntegral.Result(BilinearSegmentIntegral.Status.COMPLETE,
            OptionalDouble.of(.25), OptionalDouble.of(.1), 2, 6, BilinearSegmentIntegral.Reason.NONE);
        assertEquals(1.5, Admission.measured(2, 1, scalar, 0, 0, 0, scalar).q());
        var unavailable = new BilinearSegmentIntegral.Result(BilinearSegmentIntegral.Status.UNAVAILABLE,
            OptionalDouble.empty(), OptionalDouble.empty(), 2, 3,
            BilinearSegmentIntegral.Reason.UNSUPPORTED_CELL);
        assertThrows(IllegalArgumentException.class, () ->
            Admission.measured(2, 1, unavailable, 0, 0, 0, unavailable));
        var ordinary = Admission.ordinaryUnavailable(2, 1, 0, 0, 0, "native-ordinary");
        assertEquals(2.0, ordinary.q());
        assertTrue(ordinary.scalar().isEmpty());
    }

    @Test void productionGraphAcceptsActualIntegralAndRejectsSyntheticRawQ() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, true, 1));
        var arcs = List.of(new Arc(0, 0, 1, 1, 0, true, false, 0));
        var source = new FixedSource(Map.of(), .5);
        var scalar = BilinearSegmentIntegral.evaluate(source.nativeGrid(),
            new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(0, 0),
            new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(1, 0),
            CancellationProbe.NONE, () -> { });
        assertEquals(BilinearSegmentIntegral.Status.COMPLETE, scalar.status());
        var measured = new FixedSource(Map.of(0, Admission.measured(1, 1, scalar, 0, 0, 0, scalar)), .5);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var result = new CompetitiveAdmissionExecutor(Graph.create(vertices, arcs, java.util.Set.of(), 1, budget, owner),
                measured, budget, owner).run();
            assertEquals(Status.CERTIFIED, result.status());
            assertEquals(.5, result.q());
            assertEquals(List.of(0), result.arcIds());
        }
        assertEquals(0, ledger.currentBytes());

        var rawLedger = AttemptMemoryLedger.production();
        var rawBudget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = rawLedger.rootOwner()) {
            var raw = new FixedSource(Map.of(0, Admission.syntheticExact(.5, 0, 0, 0, "raw")), .5);
            var engine = new CompetitiveAdmissionExecutor(Graph.create(vertices, arcs, java.util.Set.of(), 1, rawBudget, owner),
                raw, rawBudget, owner);
            assertThrows(CompetitiveAdmissionExecutor.InvalidCertificateException.class, engine::run);
        }
        assertEquals(0, rawLedger.currentBytes());
    }

    @Test void strictClosureRetainsUnknownAdmissionOnlyWithAWinningPathProof() {
        var vertices = List.of(
            new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, false, 1),
            new Vertex(2, 1, 0, true, false, false, 2),
            new Vertex(3, 2, 0, true, false, true, 3));
        var arcs = List.of(
            new Arc(0, 0, 1, 1, 0, false, false, 0),
            new Arc(1, 0, 2, 1, 0, false, false, 1),
            new Arc(2, 1, 3, 1, 0, false, false, 2),
            new Arc(3, 2, 3, 1, 0, true, false, 3));
        var complete = completeScalar(1.0);
        var source = new FixedSource(Map.of(
            0, Admission.measured(1, 1, complete, 0, 0, 0, "ordinary-start"),
            1, Admission.ordinaryUnavailable(1, 1, 0, 0, 0, "costly-prefix"),
            2, Admission.measured(1, 1, complete, 0, 0, 0, "ordinary-end"),
            3, Admission.measured(1, 1, complete, 0, 0, 0, "unmeasured-optional")));
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var graph = Graph.create(vertices, arcs, java.util.Set.of(), 1, budget, owner);
            var result = new CompetitiveAdmissionExecutor(graph, source, budget, owner).run();
            assertEquals(Status.CERTIFIED, result.status());
            assertEquals(List.of(0, 2), result.arcIds());
            assertEquals(1, result.unmeasuredCertifiedNoncompetitive());
            assertEquals(3, result.measuredAdmitted());
            assertEquals(0, result.measuredRefused());
            assertEquals(3, result.measuredTransitions().size());
            assertEquals(1, result.pendingProofs().size());
            assertEquals(3, result.pendingProofs().get(0).arcId());
            assertFalse(result.pendingProofs().get(0).admissionAlreadyMeasured());
            var proof = result.pendingProofs().get(0);
            assertEquals(OutwardCostBounds.scalarLossLower(proof.sourceSpanMeters(), 1,
                result.certifiedIntensityUpperBound()), proof.scalarLowerBound());
            assertEquals(OutwardCostBounds.completedPathLower(proof.certifiedPrefixCost(),
                proof.scalarLowerBound(), proof.laterIncrementsNonnegative()), proof.lowerBound());
            assertTrue(result.finalMinimumLowerBound() > result.certifiedUpperBound());
            assertEquals(0, source.calls.getOrDefault(3, 0));
            assertMatchesExhaustiveOracle(result, vertices, arcs, source);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void exactTieNeedsBothAdmissionsAndUsesFewerConsumedRows() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, true, 1));
        var arcs = List.of(new Arc(0, 0, 1, 0, 1, true, false, 0),
            new Arc(1, 0, 1, 0, 0, false, false, 1));
        var source = new FixedSource(Map.of(0, Admission.syntheticExact(0, 0, 0, 0, "consumes"),
            1, Admission.syntheticExact(0, 0, 0, 0, "emits")));
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var result = new CompetitiveAdmissionExecutor(Graph.createSynthetic(vertices, arcs, java.util.Set.of(), 1, budget, owner),
                source, budget, owner).run();
            assertEquals(List.of(1), result.arcIds());
            assertEquals(2, result.measuredAdmitted(), "LB equal to incumbent cannot close a tie");
            assertMatchesExhaustiveOracle(result, vertices, arcs, source);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void negativeQSourceKeepsEveryPotentiallyCheapSuffixOpen() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, false, 1),
            new Vertex(2, 1, 0, true, false, false, 2),
            new Vertex(3, 2, 0, true, false, true, 3));
        var arcs = List.of(new Arc(0, 0, 1, 1, 0, false, false, 0),
            new Arc(1, 0, 2, 1, 0, true, false, 1),
            new Arc(2, 1, 3, 1, 0, false, false, 2),
            new Arc(3, 2, 3, 1, 0, true, false, 3));
        var source = new FixedSource(Map.of(
            0, Admission.syntheticExact(.2, 0, 0, 0, "ordinary"),
            1, Admission.syntheticExact(.5, 0, 0, 0, "optional-prefix"),
            2, Admission.syntheticExact(.2, 0, 0, 0, "ordinary-end"),
            3, Admission.syntheticExact(-.4, 0, 0, 0, "negative-q")), 1.5);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var result = new CompetitiveAdmissionExecutor(Graph.createSynthetic(vertices, arcs, java.util.Set.of(), 1, budget, owner),
                source, budget, owner).run();
            assertEquals(Status.CERTIFIED, result.status());
            assertEquals(List.of(1, 3), result.arcIds());
            assertEquals(4, result.measuredAdmitted());
            assertMatchesExhaustiveOracle(result, vertices, arcs, source);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void zeroBoundaryChainTransportsTheExactPositiveOriginAndNumericMemory() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 0, 0, false, false, false, 1),
            new Vertex(2, 1, 0, true, false, false, 2),
            new Vertex(3, 2, 0, true, false, true, 3));
        var arcs = List.of(new Arc(0, 0, 1, 0, 0, false, false, 0),
            new Arc(1, 1, 2, 1, 0, false, true, 1),
            new Arc(2, 2, 3, 1, 0, true, true, 2));
        var calls = new AtomicInteger();
        var source = new FixedSource(Map.of(
            0, Admission.syntheticExact(0, 0, 0, 0, "positive-ordinary"),
            1, Admission.syntheticExact(.1, 0, 0, 0, "boundary-zero"),
            2, Admission.syntheticExact(.1, 0, 0, 0, "optional-zero"))) {
            @Override public Admission certify(CanonicalKey key, Arc arc) {
                calls.incrementAndGet();
                if (arc.id() == 1) {
                    assertEquals(0, key.effectivePositiveArcId());
                    assertEquals(0, key.lastNumericVertexId());
                    assertEquals(1, key.cursorId());
                }
                if (arc.id() == 2) {
                    assertEquals(0, key.effectivePositiveArcId());
                    assertEquals(2, key.lastNumericVertexId());
                    assertEquals(2, key.cursorId());
                }
                return super.certify(key, arc);
            }
        };
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var result = new CompetitiveAdmissionExecutor(Graph.createSynthetic(vertices, arcs, java.util.Set.of(1), 1, budget, owner),
                source, budget, owner).run();
            assertEquals(List.of(0, 1, 2, 3), result.vertexIds());
            assertEquals(3, calls.get());
            assertMatchesExhaustiveOracle(result, vertices, arcs, source);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void aPotentialArcCannotSkipADeclaredProtectedOccurrence() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 0, 0, false, false, false, 1),
            new Vertex(2, 1, 0, true, false, true, 2));
        var arcs = List.of(new Arc(0, 0, 2, 1, 0, true, false, 0));
        var source = new FixedSource(Map.of(0, Admission.syntheticExact(.1, 0, 0, 0, "skip")));
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var graph = Graph.createSynthetic(vertices, arcs, java.util.Set.of(1), 1, budget, owner);
            assertThrows(IllegalArgumentException.class, () ->
                new CompetitiveAdmissionExecutor(graph, source, budget, owner).run());
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void cheaperPrefixVersionRegeneratesTheSameCanonicalOutgoingTransition() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, false, 1),
            new Vertex(2, 2, 0, true, false, true, 2));
        var arcs = List.of(new Arc(0, 0, 1, 1, 0, false, true, 0),
            new Arc(1, 0, 1, 1, 0, true, true, 1),
            new Arc(2, 1, 2, 1, 0, false, false, 2));
        var source = new FixedSource(Map.of(
            0, Admission.syntheticExact(.8, 0, 0, 0, "dear-zero"),
            1, Admission.syntheticExact(.1, 0, 0, 0, "cheap-zero"),
            2, Admission.syntheticExact(.05, 0, 0, 0, "shared-outgoing")));
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var result = new CompetitiveAdmissionExecutor(Graph.createSynthetic(vertices, arcs, java.util.Set.of(), 1, budget, owner),
                source, budget, owner).run();
            assertEquals(List.of(1, 2), result.arcIds());
            assertEquals(.15, result.cost(), 1e-15);
            assertEquals(1, source.calls.getOrDefault(2, 0),
                "same exact canonical key and arc must reuse its owned admission");
            assertMatchesExhaustiveOracle(result, vertices, arcs, source);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void falseScalarBoundFailsClosedAndCannotPublishAnIncumbent() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, true, 1));
        var arcs = List.of(new Arc(0, 0, 1, 1, 0, true, false, 0));
        var source = new FixedSource(Map.of(0,
            Admission.measured(2, 1, completeScalar(.5), 0, 0, 0, "wrong-source-mass")), .5);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var engine = new CompetitiveAdmissionExecutor(Graph.create(vertices, arcs, java.util.Set.of(), 1, budget, owner),
                source, budget, owner);
            assertThrows(CompetitiveAdmissionExecutor.InvalidCertificateException.class, engine::run);
            assertThrows(IllegalStateException.class, engine::run, "a failed session cannot be retried");
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void oneUlpCostDifferenceMustBeatTheOtherPathBeforeLexTieOrder() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, true, 1));
        var arcs = List.of(new Arc(0, 0, 1, 1, 0, true, false, 0),
            new Arc(1, 0, 1, 1, 0, false, false, 1));
        assertTrue(.1 + .2 > .3);
        var source = new FixedSource(Map.of(
            0, Admission.syntheticExact(.1 + .2, 0, 0, 0, "first-near"),
            1, Admission.syntheticExact(.3, 0, 0, 0, "second-near")));
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var result = new CompetitiveAdmissionExecutor(Graph.createSynthetic(vertices, arcs, java.util.Set.of(), 1, budget, owner),
                source, budget, owner).run();
            assertEquals(List.of(1), result.arcIds());
            assertMatchesExhaustiveOracle(result, vertices, arcs, source);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void pairCapAndCancellationReturnNoPartialResultAndCloseScratch() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, true, 1));
        var arcs = List.of(new Arc(0, 0, 1, 1, 0, false, false, 0));
        var source = new FixedSource(Map.of(0, Admission.syntheticExact(.1, 0, 0, 0, "ordinary")));
        var pairLedger = AttemptMemoryLedger.production();
        var pairBudget = new EmissionWorkBudget(new TraceBudgets(96, 1, 1000, 8, 4), CancellationProbe.NONE);
        try (var owner = pairLedger.rootOwner()) {
            var engine = new CompetitiveAdmissionExecutor(Graph.createSynthetic(vertices, arcs, java.util.Set.of(), 1, pairBudget, owner),
                source, pairBudget, owner);
            assertThrows(EmissionWorkBudget.LimitExceededException.class, engine::run);
        }
        assertEquals(0, pairLedger.currentBytes());
        var cancelLedger = AttemptMemoryLedger.production();
        var checks = new AtomicInteger();
        var cancelBudget = new EmissionWorkBudget(TraceBudgets.defaults(), () -> checks.incrementAndGet() > 12);
        try (var owner = cancelLedger.rootOwner()) {
            var engine = new CompetitiveAdmissionExecutor(Graph.createSynthetic(vertices, arcs, java.util.Set.of(), 1, cancelBudget, owner),
                source, cancelBudget, owner);
            assertThrows(CancellationException.class, engine::run);
        }
        assertEquals(0, cancelLedger.currentBytes());
    }

    @Test void memoryPressureDuringFrontierConstructionPublishesNoIncumbent() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, true, 1));
        var arcs = List.of(new Arc(0, 0, 1, 1, 0, false, false, 0));
        var source = new FixedSource(Map.of(0, Admission.syntheticExact(.1, 0, 0, 0, "ordinary")));
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var graph = Graph.createSynthetic(vertices, arcs, java.util.Set.of(), 1, budget, owner);
            long remaining = ledger.limitBytes() - ledger.currentBytes();
            assertTrue(remaining > 800);
            try (var pressure = owner.reserve(remaining - 800)) {
                var engine = new CompetitiveAdmissionExecutor(graph, source, budget, owner);
                assertThrows(AttemptMemoryLedger.ResourceLimitException.class, engine::run);
                assertThrows(IllegalStateException.class, engine::run);
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void measuredRefusalsExhaustTheCompleteFrontierAsNoRoute() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, true, 1));
        var arcs = List.of(new Arc(0, 0, 1, 1, 0, true, false, 0));
        var source = new FixedSource(Map.of(0, Admission.refused("NO_COMPLETE_MODE")));
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner()) {
            var result = new CompetitiveAdmissionExecutor(Graph.createSynthetic(vertices, arcs,
                java.util.Set.of(), 1, budget, owner), source, budget, owner).run();
            assertEquals(Status.NO_ROUTE, result.status());
            assertEquals(1, result.measuredRefused());
            assertEquals(0, result.unmeasuredCertifiedNoncompetitive());
            assertEquals("NO_COMPLETE_MODE", result.measuredTransitions().get(0).reason());
            assertTrue(result.vertexIds().isEmpty());
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void graphFromAnotherAttemptCannotSupplyACompetitiveCertificate() {
        var vertices = List.of(new Vertex(0, 0, 0, true, true, false, 0),
            new Vertex(1, 1, 0, true, false, true, 1));
        var arcs = List.of(new Arc(0, 0, 1, 1, 0, false, false, 0));
        var source = new FixedSource(Map.of(0, Admission.syntheticExact(.1, 0, 0, 0, "foreign")));
        var ledgerA = AttemptMemoryLedger.production();
        var ledgerB = AttemptMemoryLedger.production();
        var budgetA = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        var budgetB = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var ownerA = ledgerA.rootOwner(); var ownerB = ledgerB.rootOwner()) {
            var foreign = Graph.createSynthetic(vertices, arcs, java.util.Set.of(), 1, budgetA, ownerA);
            assertThrows(IllegalArgumentException.class, () ->
                new CompetitiveAdmissionExecutor(foreign, source, budgetA, ownerB).run());
            assertThrows(IllegalArgumentException.class, () ->
                new CompetitiveAdmissionExecutor(foreign, source, budgetB, ownerA).run(),
                "a second budget must not reset graph preparation work");
        }
        assertEquals(0, ledgerA.currentBytes());
        assertEquals(0, ledgerB.currentBytes());
    }

    private record Oracle(double cost, int consumed, List<Integer> vertices, List<Integer> arcs,
            double q, double c, double t, double k) { }

    private static BilinearSegmentIntegral.Result completeScalar(double value) {
        var source = new FixedSource(Map.of(), value);
        var result = BilinearSegmentIntegral.evaluate(source.nativeGrid(),
            new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(0, 0),
            new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(1, 0),
            CancellationProbe.NONE, () -> { });
        assertEquals(BilinearSegmentIntegral.Status.COMPLETE, result.status());
        return result;
    }

    private static void assertMatchesExhaustiveOracle(Result actual, List<Vertex> vertices,
            List<Arc> arcs, FixedSource source) {
        Oracle expected = exhaustiveFrom(0, 0, 0, List.of(0), List.of(), 0, 0, 0, 0,
            vertices, arcs, source.values);
        assertNotNull(expected);
        assertEquals(expected.cost(), actual.cost(), 0.0);
        assertEquals(expected.consumed(), actual.arcIds().stream()
            .map(arcs::get).mapToInt(Arc::consumedRows).sum());
        assertEquals(expected.vertices(), actual.vertexIds());
        assertEquals(expected.arcs(), actual.arcIds());
        assertEquals(expected.q(), actual.q(), 0.0);
        assertEquals(expected.c(), actual.c(), 0.0);
        assertEquals(expected.t(), actual.t(), 0.0);
        assertEquals(expected.k(), actual.k(), 0.0);
    }

    private static Oracle exhaustiveFrom(int cursor, double cost, int consumed,
            List<Integer> vertexPath, List<Integer> arcPath,
            double q, double c, double t, double k,
            List<Vertex> vertices, List<Arc> arcs, Map<Integer, Admission> source) {
        if (vertices.get(cursor).terminal())
            return new Oracle(cost, consumed, vertexPath, arcPath, q, c, t, k);
        Oracle best = null;
        for (Arc arc : arcs) {
            if (arc.fromId() != cursor) continue;
            var admission = source.get(arc.id());
            if (!admission.admitted()) continue;
            double next = cost + admission.q() + admission.c() + admission.t() + admission.k();
            var nextVertices = new java.util.ArrayList<>(vertexPath);
            nextVertices.add(arc.toId());
            var nextArcs = new java.util.ArrayList<>(arcPath);
            nextArcs.add(arc.id());
            Oracle candidate = exhaustiveFrom(arc.toId(), next, consumed + arc.consumedRows(),
                List.copyOf(nextVertices), List.copyOf(nextArcs),
                q + admission.q(), c + admission.c(), t + admission.t(), k + admission.k(),
                vertices, arcs, source);
            if (candidate != null && (best == null || oracleCompare(candidate, best, vertices) < 0))
                best = candidate;
        }
        return best;
    }

    private static int oracleCompare(Oracle a, Oracle b, List<Vertex> vertices) {
        int cost = Double.compare(a.cost(), b.cost());
        if (cost != 0) return cost;
        int consumed = Integer.compare(a.consumed(), b.consumed());
        if (consumed != 0) return consumed;
        for (int i = 0; i < Math.min(a.vertices().size(), b.vertices().size()); i++) {
            int id = Integer.compare(vertices.get(a.vertices().get(i)).tieId(),
                vertices.get(b.vertices().get(i)).tieId());
            if (id != 0) return id;
        }
        return Integer.compare(a.vertices().size(), b.vertices().size());
    }

    private static class FixedSource implements TransitionProvider {
        private final Map<Integer, Admission> values;
        private final double nodeValue;
        private final java.util.Map<Integer, Integer> calls = new java.util.HashMap<>();
        private FixedSource(Map<Integer, Admission> values) { this(values, 1.0); }
        private FixedSource(Map<Integer, Admission> values, double nodeValue) {
            this.values = values;
            this.nodeValue = nodeValue;
        }
        @Override public BilinearSegmentIntegral.ReadOnlyScalarGrid nativeGrid() {
            return new BilinearSegmentIntegral.ReadOnlyScalarGrid() {
                @Override public int width() { return 2; }
                @Override public int height() { return 2; }
                @Override public boolean supportsCell(int x, int y) { return true; }
                @Override public double nodeValue(int x, int y) { return nodeValue; }
            };
        }
        @Override public Admission certify(CanonicalKey prefix, Arc arc) {
            calls.merge(arc.id(), 1, Integer::sum);
            return values.get(arc.id());
        }
    }
}
