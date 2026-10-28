package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

class LegacyBReferencePreparationTest {
    @Test void referenceDoesNotReplaceOriginalAuthority() {
        var fixture = LegacyBTestFixtures.fixture();
        String originalHash = fixture.network().canonicalHash();
        var section = LegacyBTestFixtures.section(fixture, 4);
        var reference = new LegacyBReferenceFactory().prepare(section, fixture.request(), fixture.evidence(),
                fixture.network(), fixture.request().budgets(), CancellationProbe.NONE);
        assertEquals(originalHash, fixture.network().canonicalHash());
        assertEquals(originalHash, reference.request().networkContentHash());
        assertEquals(fixture.request().permissions(), reference.request().permissions());
        assertEquals(fixture.request().settingsHash(), reference.request().settingsHash());
        assertTrue(reference.request().parameterHash().matches("[0-9a-f]{64}"),
                "Refinement parameters need a real digest usable by diagnostics, not a descriptive suffix");
        assertEquals(TrackerMode.PROBABILISTIC, reference.request().engine());
        assertEquals(new ProbabilisticProfileFactory().profileChainage(section.reference(), 2),
                reference.request().profileChainage());
        assertNotEquals(fixture.request().profileChainage(), reference.request().profileChainage());
        assertEquals(fixture.evidence().canonicalHash(), reference.originalEvidenceHash());
        assertEquals(reference.evidence().canonicalHash(), reference.request().evidenceContentHash());
        assertEquals(fixture.evidence().fields(), reference.evidence().fields());
        DetachedValueVerifier.verify(reference);
    }

    @Test void offRasterReferenceCannotInventSupport() {
        var fixture = LegacyBTestFixtures.fixture();
        assertThrows(LegacyBReferenceFactory.ReferenceUnavailableException.class,
                () -> new LegacyBReferenceFactory().prepare(LegacyBTestFixtures.section(fixture, 49),
                        fixture.request(), fixture.evidence(), fixture.network(), fixture.request().budgets(),
                        CancellationProbe.NONE));
    }

    @Test void originalProtectedBoundaryCannotBeMovedByAReference() {
        var fixture = LegacyBTestFixtures.fixture();
        var section = LegacyBTestFixtures.section(fixture, 4);
        var points = new java.util.ArrayList<>(section.reference());
        points.set(0, new MetricPoint(0, 1));
        var moved = new LegacyBModels.Section("part-0", "legacy-0", section.originalRange(), points,
                section.originalToVertex(), false);
        assertThrows(IllegalArgumentException.class, () -> new LegacyBReferenceFactory().prepare(moved,
                fixture.request(), fixture.evidence(), fixture.network(), fixture.request().budgets(),
                CancellationProbe.NONE));
    }

    @Test void evenMovableJunctionMustSeparateBIntervals() {
        var fixture = LegacyBTestFixtures.fixture(true);
        assertThrows(IllegalArgumentException.class, () -> new LegacyBReferenceFactory().prepare(
                LegacyBTestFixtures.section(fixture, 4), fixture.request(), fixture.evidence(),
                fixture.network(), fixture.request().budgets(), CancellationProbe.NONE));
    }

    @Test void degenerateLocalReferenceIsTypedUnavailableRatherThanAnAttemptFailure() {
        var fixture = LegacyBTestFixtures.fixture();
        var section = new LegacyBModels.Section("zero-length", "legacy-0", new OccurrenceRange(0, 1),
                java.util.List.of(fixture.original().get(0), fixture.original().get(0)),
                java.util.Map.of(0, 0, 1, 1), false);
        var failure = assertThrows(LegacyBReferenceFactory.ReferenceUnavailableException.class,
                () -> new LegacyBReferenceFactory().prepare(section, fixture.request(), fixture.evidence(),
                        fixture.network(), fixture.request().budgets(), CancellationProbe.NONE));
        assertEquals("DEGENERATE_REFERENCE", failure.reason().name());
    }
}
