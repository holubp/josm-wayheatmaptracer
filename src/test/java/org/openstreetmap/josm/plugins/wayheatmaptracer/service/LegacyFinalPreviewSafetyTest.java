package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class LegacyFinalPreviewSafetyTest {
    @Test
    void newProperCrossingIsBlocked() {
        assertFalse(LegacyFinalPreviewSafety.warnings(line(), bowtie()).isEmpty());
    }

    @Test
    void exactInheritedContactSurvivesUnrelatedChange() {
        var source = points(0,0, 4,4, 0,4, 4,0, 6,0);
        var preview = points(0,0, 4,4, 0,4, 4,0, 6,1);
        assertTrue(LegacyFinalPreviewSafety.warnings(source, preview).isEmpty());
    }

    @Test
    void exactInheritedTouchesAndOverlapsRetainTheirCertificates() {
        var touch = points(0,0, 2,2, 4,0, 2,2, 6,0, 8,0);
        assertTrue(LegacyFinalPreviewSafety.warnings(touch,
            points(0,0, 2,2, 4,0, 2,2, 6,0, 8,1)).isEmpty());
        var overlap = points(0,0, 4,0, 4,4, 1,0, 3,0, 6,-1, 8,-1);
        assertTrue(LegacyFinalPreviewSafety.warnings(overlap,
            points(0,0, 4,0, 4,4, 1,0, 3,0, 6,-1, 8,-2)).isEmpty());
    }

    @Test
    void subdividedContactSegmentsRemainExplicitlyUnproven() {
        assertTrue(LegacyFinalPreviewSafety.warnings(bowtie(), points(0,0, 2,2, 4,4, 0,4, 4,0))
            .stream().anyMatch(warning -> warning.contains("inheritance unproven")));
    }

    @Test
    void changedBranchesAtSameContactCoordinateDoNotProveInheritance() {
        var preview = points(0,1, 4,3, 0,3, 4,1);
        assertTrue(LegacyFinalPreviewSafety.warnings(bowtie(), preview).stream()
            .anyMatch(warning -> warning.contains("inheritance unproven")));
    }

    @Test
    void ambiguousOriginalSegmentDoesNotProveInheritance() {
        var source = points(0,0, 4,4, 0,4, 4,0, 0,-2, 0,0, 4,4, 6,4);
        assertFalse(LegacyFinalPreviewSafety.warnings(source, bowtie()).isEmpty());
    }

    @Test
    void reversedOriginalSegmentsDoNotProveDirectedInheritance() {
        assertFalse(LegacyFinalPreviewSafety.warnings(bowtie(), points(4,0, 0,4, 4,4, 0,0)).isEmpty());
    }

    @Test
    void newNonAdjacentVertexTouchIsBlocked() {
        assertTrue(LegacyFinalPreviewSafety.warnings(line(), points(0,0, 2,2, 4,0, 2,2, 6,0))
            .stream().anyMatch(warning -> warning.contains("touch")));
    }

    @Test
    void newCollinearOverlapIsBlocked() {
        assertTrue(LegacyFinalPreviewSafety.warnings(line(), points(0,0, 4,0, 4,2, 1,0, 3,0))
            .stream().anyMatch(warning -> warning.contains("overlap")));
    }

    @Test
    void ordinaryInheritedClosedSeamIsAllowed() {
        var square = points(0,0, 4,0, 4,4, 0,4, 0,0);
        assertTrue(LegacyFinalPreviewSafety.warnings(square, square).isEmpty());
    }

    @Test
    void closingOverlapIsNotMistakenForOrdinarySeam() {
        var source = points(0,0, 4,0, 4,4, 0,4, 0,0);
        var preview = points(0,0, 4,0, 4,4, 2,0, 0,0);
        assertTrue(LegacyFinalPreviewSafety.warnings(source, preview).stream()
            .anyMatch(warning -> warning.contains("overlap")));
    }

    @Test
    void openSourceCannotAcquireAnUnownedClosingContact() {
        var square = points(0,0, 4,0, 4,4, 0,4, 0,0);
        assertFalse(LegacyFinalPreviewSafety.warnings(line(), square).isEmpty());
    }

    @Test
    void nonintersectingNearParallelSegmentsAreNotInflatedIntoContacts() {
        assertTrue(LegacyFinalPreviewSafety.warnings(line(),
            points(0,0, 4,0, 4,4, 0,1e-12, 3,1e-12)).isEmpty());
    }

    @Test
    void sharpNonCrossingBendHasNoAngleCutoff() {
        assertTrue(LegacyFinalPreviewSafety.warnings(line(), points(0,0, 4,0, 0.1,0.2, 4,4)).isEmpty());
    }

    @Test
    void exactStoredSafePreviewPassesWithoutNodeAssignmentMap() {
        var source = line();
        var preview = points(0,0, 2,1, 4,0);
        assertDoesNotThrow(() -> LegacyFinalPreviewSafety.requireSafe(source, preview, preview));
    }

    @Test
    void displayedPreviewCannotDifferFromStoredGeometryEvenIfBothAreSafe() {
        assertThrows(IllegalStateException.class, () -> LegacyFinalPreviewSafety.requireSafe(
            line(), points(0,0, 2,1, 4,0), points(0,0, 2,2, 4,0)));
    }

    @Test
    void applyRechecksContactsWithoutTrustingMissingCachedWarnings() {
        assertThrows(IllegalStateException.class,
            () -> LegacyFinalPreviewSafety.requireSafe(line(), bowtie(), bowtie()));
    }

    @Test
    void missingOrNonFiniteFinalGeometryCannotBeApplied() {
        assertThrows(IllegalStateException.class,
            () -> LegacyFinalPreviewSafety.requireSafe(line(), List.of(), List.of()));
        assertThrows(IllegalStateException.class,
            () -> LegacyFinalPreviewSafety.requireSafe(line(), points(0,0, Double.NaN,1),
                points(0,0, Double.NaN,1)));
    }

    private static List<EastNorth> line() { return points(0,0, 4,0); }
    private static List<EastNorth> bowtie() { return points(0,0, 4,4, 0,4, 4,0); }
    private static List<EastNorth> points(double... coordinates) {
        var result = new java.util.ArrayList<EastNorth>();
        for (int index = 0; index < coordinates.length; index += 2) {
            result.add(new EastNorth(coordinates[index], coordinates[index + 1]));
        }
        return List.copyOf(result);
    }
}
