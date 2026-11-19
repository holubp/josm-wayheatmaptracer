package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;

class V022CertifiedContextBranchPreparationTest {
    private static final LocalMetricFrame FRAME = LocalMetricFrame.certifiedEquirectangular(
            new GeographicPoint(0, 0), new GeographicPoint(-.001, -.001),
            new GeographicPoint(.001, .001));

    @Test
    void repeatedPairTestsPrepareOnlyTwoTranslatedChordsPerContext() {
        var prepared = CertifiedContextSegmentClipper.prepareOriginalContactFrame(FRAME);
        var contexts = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> chord(i * 2, 0, i * 2 + 1, 0)).toList();
        for (var first : contexts) for (var second : contexts)
            CertifiedContextSegmentClipper.originalContactEvidencePrepared(
                    first, null, null, second, null, null, FRAME, prepared);
        assertEquals(2L * contexts.size(), prepared.branchPreparations(),
                "translation preparation must scale with immutable contexts, not tested pairs");
    }

    @Test
    void zeroShiftPreservesChordIdentity() {
        var original = chord(0, 0, 1, 1);
        assertSame(original, original.shifted(BigDecimal.ZERO));
    }

    @Test
    void branchRetentionIsBoundedAndOverflowKeepsExactEvidence() {
        var prepared = CertifiedContextSegmentClipper.prepareOriginalContactFrame(FRAME);
        var changed = chord(0, 0, 1, 0);
        for (int i = 0; i < CertifiedContextSegmentClipper.OriginalContactFrame.MAX_CACHED_CONTEXTS; i++)
            prepared.branch(chord(i, 0, i + 1, 0), -1);
        var overflow = chord(.5, -1, .5, 1);
        var expected = CertifiedContextSegmentClipper.originalContactEvidence(
                changed, null, null, overflow, null, null, FRAME);
        for (int repeat = 0; repeat < 2; repeat++)
            assertEquals(expected, CertifiedContextSegmentClipper.originalContactEvidencePrepared(
                    changed, null, null, overflow, null, null, FRAME, prepared));
        assertEquals(CertifiedContextSegmentClipper.OriginalContactFrame.MAX_CACHED_CONTEXTS,
                prepared.cachedContexts());
        assertEquals(2L * prepared.cachedContexts() + 4, prepared.branchPreparations());
        assertSame(overflow, prepared.branch(overflow, 0));
    }

    @Test
    void immutableBranchValuesAreReusedOnlyWithinTheirAssessment() {
        var first = CertifiedContextSegmentClipper.prepareOriginalContactFrame(FRAME);
        var second = CertifiedContextSegmentClipper.prepareOriginalContactFrame(FRAME);
        var context = chord(0, 0, 1, 1);
        assertSame(first.branch(context, -1), first.branch(context, -1));
        assertSame(first.branch(context, 1), first.branch(context, 1));
        assertSame(context, first.branch(context, 0));
        assertNotSame(first.branch(context, -1), second.branch(context, -1));
        assertEquals(first.branch(context, -1).start(), second.branch(context, -1).start());
    }

    private static CertifiedContextSegmentClipper.ExactChord chord(double ax, double ay,
            double bx, double by) {
        return new CertifiedContextSegmentClipper.ExactChord(
                new CertifiedContextSegmentClipper.ExactPoint(new BigDecimal(ax), new BigDecimal(ay)),
                new CertifiedContextSegmentClipper.ExactPoint(new BigDecimal(bx), new BigDecimal(by)));
    }
}
