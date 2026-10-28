package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine;

class LegacyBRefinedEngineTest {
    @Test void actualProductionBUsesLegacyCurveWhileOriginalNetworkRemainsUnchanged() {
        var fixture = LegacyBTestFixtures.alignedEndpointFixture();
        var section = LegacyBTestFixtures.section(fixture, 0);
        var reference = new LegacyBReferenceFactory().prepare(section, fixture.request(), fixture.evidence(),
                fixture.network(), fixture.request().budgets(), CancellationProbe.NONE);
        var run = new ProbabilisticTraceEngine("synthetic", EvidenceModelParameters.defaults())
                .traceRefinedWithUsage(reference, fixture.network(), CancellationProbe.NONE);
        assertTrue(run.result().evaluatedStates() > 0);
        assertFalse(run.result().hypotheses().isEmpty(), "A continuous bright ridge must yield a real B route");
        var points = run.result().hypotheses().get(0).points();
        assertTrue(fixture.original().get(1).yMeters() < -5.9,
                "The original midpoint lies beyond B's four-metre search radius");
        assertTrue(points.stream().allMatch(point -> Math.abs(point.yMeters()) < 1e-9),
                "B must follow the coherent Legacy reference/ridge rather than the displaced original midpoint");
        assertEquals(0, points.get(0).distanceTo(section.reference().get(0)), 1e-9);
        assertEquals(0, points.get(points.size() - 1).distanceTo(section.reference().get(2)), 1e-9);
        assertEquals(fixture.request().networkContentHash(), fixture.network().canonicalHash());
    }

    @Test void genericBEntryCannotSilentlyIgnoreTheBoundLegacyReference() {
        var fixture = LegacyBTestFixtures.fixture();
        var reference = new LegacyBReferenceFactory().prepare(LegacyBTestFixtures.section(fixture, 4),
                fixture.request(), fixture.evidence(), fixture.network(), fixture.request().budgets(),
                CancellationProbe.NONE);
        var failure = assertThrows(IllegalArgumentException.class, () ->
                new ProbabilisticTraceEngine("synthetic", EvidenceModelParameters.defaults())
                        .traceWithUsage(reference.request(), reference.evidence(), fixture.network(), CancellationProbe.NONE));
        assertTrue(failure.getMessage().contains("bound reference"));
    }

    @Test void cancelledRefinementIsTerminalAndDoesNoInferenceWork() {
        var fixture = LegacyBTestFixtures.fixture();
        var reference = new LegacyBReferenceFactory().prepare(LegacyBTestFixtures.section(fixture, 4),
                fixture.request(), fixture.evidence(), fixture.network(), fixture.request().budgets(),
                CancellationProbe.NONE);
        var run = new ProbabilisticTraceEngine("synthetic", EvidenceModelParameters.defaults())
                .traceRefinedWithUsage(reference, fixture.network(), () -> { throw new CancellationException(); });
        assertEquals(TraceHypothesisSet.Status.CANCELLED, run.result().status());
        assertEquals(0, run.usage().transitions());
        assertTrue(run.result().hypotheses().isEmpty());
    }
}
