package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyBParentAdmissionTest {
    @Test void completeAggregateKeepsARepresentativeBeforeNativeSecondRank() {
        var admitted = LegacyBParentAdmission.select(List.of(
                new LegacyBParentAdmission.Source("selected",List.of("n0","n1")),
                new LegacyBParentAdmission.Source("aggregate",List.of("a0","a1")),
                new LegacyBParentAdmission.Source("alternative",List.of("x0"))));
        assertEquals(List.of("n0"),admitted.get(0).admittedParentIds());
        assertEquals(List.of("a0"),admitted.get(1).admittedParentIds());
        assertTrue(admitted.get(2).admittedParentIds().isEmpty());
        assertEquals(List.of(1,1,1),admitted.stream().map(LegacyBParentAdmission.Admission::omittedParents).toList());
    }

    @Test void emptySourcesDoNotConsumeSlotsAndSingleSourceRetainsTwoNativeRanks() {
        var sources = List.of(new LegacyBParentAdmission.Source("empty",List.of()),
                new LegacyBParentAdmission.Source("selected",List.of("n0","n1","n2")));
        var admitted = LegacyBParentAdmission.select(sources);
        assertTrue(admitted.get(0).admittedParentIds().isEmpty());
        assertEquals(List.of("n0","n1"),admitted.get(1).admittedParentIds());
        assertEquals(1,admitted.get(1).omittedParents());
        assertEquals(admitted,LegacyBParentAdmission.select(sources));
    }

    @Test void requestedAlternativeGetsARepresentativeBeforeAnotherRankFromTheSameSource() {
        var admitted = LegacyBParentAdmission.select(List.of(
                new LegacyBParentAdmission.Source("selected",List.of("n0","n1")),
                new LegacyBParentAdmission.Source("alternative",List.of("x0","x1"))));
        assertEquals(List.of("n0","x0"),admitted.stream().flatMap(source -> source.admittedParentIds().stream()).toList());
    }

    @Test void duplicateSourceOrLocalParentIdentitiesCannotCreateAmbiguousAdmission() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyBParentAdmission.Source("selected",List.of("n0","n0")));
        assertThrows(IllegalArgumentException.class, () -> LegacyBParentAdmission.select(List.of(
                new LegacyBParentAdmission.Source("selected",List.of("n0")),
                new LegacyBParentAdmission.Source("selected",List.of("n1")))));
    }
}
