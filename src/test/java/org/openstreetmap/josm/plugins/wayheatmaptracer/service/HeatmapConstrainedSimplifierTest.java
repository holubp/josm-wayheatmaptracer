package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedSimplificationResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;

class HeatmapConstrainedSimplifierTest {
    private static final HeatmapConstrainedSimplifier SIMPLIFIER = new HeatmapConstrainedSimplifier();

    @Test
    void failsClosedWithoutExplicitLateralGroundScale() {
        List<EastNorth> geometry = geometry(0.0, 0.1, -0.1, 0.0);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 3)),
            Set.of(),
            evidenceWithoutGroundScale(geometry, direct(geometry.size()), Set.of(), Set.of()),
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(HeatmapConstrainedSimplificationResult.Status.UNCHANGED, result.status());
        assertEquals(geometry, result.geometry());
        assertEquals(List.of(0, 1, 2, 3), result.retainedSourceIndexes());
        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.MISSING_GROUND_SCALE));
        assertFalse(result.metrics().maximumRemovedPointDeviationMeters().isPresent());
        assertFalse(result.metrics().worstFitRetention().isPresent());
        assertEquals(0, result.metrics().acceptedChordCount());
        assertEquals(geometry.size(), result.metrics().afterPointCount());
    }

    @Test
    void collapsesAWellSupportedStraightIntervalWithoutRedistribution() {
        List<EastNorth> geometry = geometry(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 6)),
            Set.of(), evidence(geometry, direct(geometry.size()), Set.of(), Set.of()),
            GeometryCleanupPreset.CONSERVATIVE.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(HeatmapConstrainedSimplificationResult.Status.SIMPLIFIED, result.status());
        assertEquals(List.of(geometry.get(0), geometry.get(6)), result.geometry());
        assertEquals(List.of(0, 6), result.retainedSourceIndexes());
        assertEquals(7, result.metrics().beforePointCount());
        assertEquals(2, result.metrics().afterPointCount());
        assertEquals(1, result.metrics().acceptedChordCount());
        assertEquals(0.0, result.metrics().maximumRemovedPointDeviationMeters().orElseThrow(), 0.0);
        assertEquals(1.0, result.metrics().worstFitRetention().orElseThrow(), 0.0);
    }

    @Test
    void convertsLateralRasterDeviationUsingOnlyTheExplicitGroundScale() {
        List<EastNorth> geometry = geometry(0.0, 0.0, 1.5, 0.0, 0.0);
        GeometryCleanupMode mode = GeometryCleanupMode.REDUCE_POINTS_ONLY;

        HeatmapConstrainedSimplificationResult coarseGroundScale = SIMPLIFIER.simplify(
            geometry, List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)), Set.of(),
            evidence(geometry, direct(geometry.size()), Set.of(), Set.of(), Set.of(), 2.0),
            GeometryCleanupPreset.BALANCED.apply(mode));
        HeatmapConstrainedSimplificationResult fineGroundScale = SIMPLIFIER.simplify(
            geometry, List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)), Set.of(),
            evidence(geometry, direct(geometry.size()), Set.of(), Set.of(), Set.of(), 0.5),
            GeometryCleanupPreset.BALANCED.apply(mode));

        assertTrue(coarseGroundScale.retainedSourceIndexes().contains(2));
        assertEquals(List.of(0, 4), fineGroundScale.retainedSourceIndexes());
        assertEquals(0.75,
            fineGroundScale.metrics().maximumRemovedPointDeviationMeters().orElseThrow(), 1e-12);
    }

    @Test
    void aDirectSupportedApexIsARequiredSplitAndRetainsFullAmplitude() {
        List<EastNorth> geometry = geometry(0.0, 0.0, 3.0, 0.0, 0.0);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry, List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)), Set.of(),
            evidence(geometry, direct(geometry.size()), Set.of(), Set.of(), Set.of(2), 1.0),
            GeometryCleanupPreset.STRONG.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(geometry, result.geometry());
        assertEquals(List.of(0, 1, 2, 3, 4), result.retainedSourceIndexes());
        assertEquals(geometry.get(2), result.geometry().get(result.retainedSourceIndexes().indexOf(2)));
        assertEquals(1, result.metrics().retainedSupportedAnchorCount());
        assertTrue(result.metrics().supportedAmplitudeRetention() >= 0.90);
    }

    @Test
    void rejectsAChordThatLosesRawB3OrB5Fit() {
        List<EastNorth> geometry = geometry(0.0, 0.0, 2.0, 0.0, 0.0);
        List<Double> centers = geometry.stream().map(EastNorth::north).toList();

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry, List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)), Set.of(),
            evidence(geometry, direct(geometry.size()), Set.of(), Set.of(), Set.of(), 1.0,
                centers, 1.0, 3.0),
            GeometryCleanupPreset.STRONG.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.FIT_RETENTION));
        assertEquals(geometry, result.geometry());
        assertEquals(List.of(0, 1, 2, 3, 4), result.retainedSourceIndexes());
    }

    @Test
    void rejectsAChordOutsideTheSelectedCorridorShoulder() {
        List<EastNorth> geometry = geometry(0.0, 0.0, 2.0, 0.0, 0.0);
        List<Double> centers = geometry.stream().map(EastNorth::north).toList();

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry, List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)), Set.of(),
            evidence(geometry, direct(geometry.size()), Set.of(), Set.of(), Set.of(), 1.0,
                centers, 0.25, 0.5),
            GeometryCleanupPreset.STRONG.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.CORRIDOR_CONTAINMENT));
        assertTrue(result.metrics().containmentFailureCount() > 0);
        assertEquals(geometry, result.geometry());
        assertEquals(List.of(0, 1, 2, 3, 4), result.retainedSourceIndexes());
    }

    @Test
    void rejectsAChordThatWorsensCenterBiasBeyondQuarterSourcePixel() {
        List<EastNorth> geometry = geometry(0.40, 0.05, -0.05, 0.05, 0.40);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry, List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)), Set.of(),
            evidence(geometry, direct(geometry.size()), Set.of(), Set.of()),
            GeometryCleanupPreset.STRONG.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.CENTER_RETENTION));
        assertTrue(result.retainedSourceIndexes().size() > 2, result.retainedSourceIndexes().toString());
    }

    @Test
    void reducesOnlyInsideExplicitIntervalsAndKeepsProtectedCoordinatesExact() {
        List<EastNorth> geometry = geometry(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry, List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(1, 5)), Set.of(3),
            evidence(geometry, direct(geometry.size()), Set.of(), Set.of()),
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(List.of(0, 1, 3, 5, 6), result.retainedSourceIndexes());
        for (int retained = 0; retained < result.geometry().size(); retained++) {
            assertEquals(geometry.get(result.retainedSourceIndexes().get(retained)),
                result.geometry().get(retained));
        }
    }

    @Test
    void rejectsAReplacementChordThatWouldCrossAnOutsideSegment() {
        List<EastNorth> geometry = List.of(
            new EastNorth(0.0, 0.0), new EastNorth(2.0, 2.0), new EastNorth(4.0, 0.0),
            new EastNorth(5.0, -2.0), new EastNorth(2.0, -1.0), new EastNorth(2.0, 1.0));

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry, List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 2)), Set.of(),
            evidence(geometry, direct(geometry.size()), Set.of(), Set.of()),
            GeometryCleanupPreset.STRONG.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(geometry, result.geometry());
        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.TOPOLOGY_CONTACT));
    }

    @Test
    void recordsOneMetricScaleRejectionPerExplicitProtectedInterval() {
        List<EastNorth> geometry = geometry(0.0, 0.1, 0.0, -0.1, 0.0);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 2),
                new HeatmapConstrainedSimplifier.ProtectedInterval(2, 4)),
            Set.of(2),
            evidenceWithoutGroundScale(geometry, direct(geometry.size()), Set.of(), Set.of()),
            GeometryCleanupPreset.STRONG.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(2, result.metrics().attemptedChordCount());
        assertEquals(2, result.chordRejections().size());
        assertTrue(result.chordRejections().stream().allMatch(rejection ->
            rejection.reason() == HeatmapConstrainedSimplificationResult.FailureReason.MISSING_GROUND_SCALE));
        assertEquals(3, result.metrics().protectedPointCount());
        assertTrue(result.retainedSourceIndexes().contains(2));
    }

    @Test
    void unsupportedGapFailsClosedBeforeAnyRemoval() {
        List<EastNorth> geometry = geometry(0.0, 0.1, 0.0, -0.1, 0.0);
        List<CleanupEvidenceProvenance> provenance = direct(geometry.size());
        provenance.set(2, CleanupEvidenceProvenance.UNSUPPORTED);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)),
            Set.of(), evidence(geometry, provenance, Set.of(), Set.of()),
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertTrue(result.retainedSourceIndexes().contains(2));
        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.UNSUPPORTED_GAP));
        assertTrue(result.chordRejections().stream().anyMatch(rejection ->
            rejection.reason() == HeatmapConstrainedSimplificationResult.FailureReason.UNSUPPORTED_GAP
                && rejection.blockingProfileIndex() == 2));
    }

    @Test
    void offRasterUnsupportedGapIsDiagnosedSeparately() {
        List<EastNorth> geometry = geometry(0.0, 0.1, 0.0, -0.1, 0.0);
        List<CleanupEvidenceProvenance> provenance = direct(geometry.size());
        provenance.set(2, CleanupEvidenceProvenance.UNSUPPORTED);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)),
            Set.of(), evidence(geometry, provenance, Set.of(2), Set.of()),
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.OFF_RASTER_GAP));
        assertTrue(result.retainedSourceIndexes().contains(2));
    }

    @Test
    void noSignalDirectGapCannotAuthorizeAnInventedChord() {
        List<EastNorth> geometry = geometry(0.0, 0.1, 0.0, -0.1, 0.0);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)),
            Set.of(), evidence(geometry, direct(geometry.size()), Set.of(), Set.of(2)),
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.NO_SIGNAL_GAP));
        assertTrue(result.retainedSourceIndexes().contains(2));
    }

    @Test
    void boundedInterpolationConstrainsButDoesNotAuthorizeAWholeInterval() {
        List<EastNorth> geometry = geometry(0.0, 0.1, 0.0, -0.1, 0.0);
        List<CleanupEvidenceProvenance> provenance = new ArrayList<>(Collections.nCopies(
            geometry.size(), CleanupEvidenceProvenance.BOUNDED_INTERPOLATION));

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)),
            Set.of(), evidence(geometry, provenance, Set.of(), Set.of()),
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.NO_DIRECT_AUTHORIZATION));
        assertEquals(geometry, result.geometry());
    }

    @Test
    void preservesExplicitProtectedIndexesAndSupportedApexesExactly() {
        List<EastNorth> geometry = geometry(0.0, 0.1, 3.0, 0.1, 0.0);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4)),
            Set.of(1), evidence(geometry, direct(geometry.size()), Set.of(), Set.of(), Set.of(2)),
            GeometryCleanupPreset.STRONG.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(geometry, result.geometry());
        assertEquals(List.of(0, 1, 2, 3, 4), result.retainedSourceIndexes());
        assertEquals(3, result.metrics().protectedPointCount());
        assertEquals(1, result.metrics().retainedSupportedAnchorCount());
        assertEquals(1.0, result.metrics().supportedAmplitudeRetention(), 0.0);
    }

    @Test
    void disabledModeReturnsImmutableUnchangedGeometry() {
        List<EastNorth> mutable = new ArrayList<>(geometry(0.0, 0.1, 0.0));
        List<EastNorth> snapshot = List.copyOf(mutable);

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            mutable,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 2)),
            Set.of(), evidence(mutable, direct(mutable.size()), Set.of(), Set.of()),
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.NONE));

        assertEquals(HeatmapConstrainedSimplificationResult.Status.UNCHANGED, result.status());
        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.MODE_DISABLED));
        assertEquals(snapshot, mutable);
        assertThrows(UnsupportedOperationException.class,
            () -> result.geometry().add(new EastNorth(8.0, 8.0)));
        assertThrows(UnsupportedOperationException.class,
            () -> result.retainedSourceIndexes().add(8));
    }

    @Test
    void rejectsMisalignedEvidenceAndInvalidProtectedIntervals() {
        List<EastNorth> geometry = geometry(0.0, 0.1, 0.0, -0.1);

        HeatmapConstrainedSimplificationResult misaligned = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 3)),
            Set.of(), CandidateCleanupEvidence.empty(),
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));
        assertEquals(HeatmapConstrainedSimplificationResult.Status.REJECTED, misaligned.status());
        assertTrue(misaligned.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.INELIGIBLE_EVIDENCE));

        HeatmapConstrainedSimplificationResult invalidIntervals = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 2),
                new HeatmapConstrainedSimplifier.ProtectedInterval(1, 3)),
            Set.of(), evidence(geometry, direct(geometry.size()), Set.of(), Set.of()),
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));
        assertEquals(HeatmapConstrainedSimplificationResult.Status.REJECTED, invalidIntervals.status());
        assertTrue(invalidIntervals.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.INVALID_PROTECTED_INTERVALS));
    }

    @Test
    void rejectsSourceCrossingsTouchesAndCollinearOverlaps() {
        List<List<EastNorth>> unsafe = List.of(
            List.of(new EastNorth(0.0, 0.0), new EastNorth(2.0, 2.0),
                new EastNorth(0.0, 2.0), new EastNorth(2.0, 0.0)),
            List.of(new EastNorth(0.0, 0.0), new EastNorth(2.0, 0.0),
                new EastNorth(2.0, 2.0), new EastNorth(1.0, 0.0)),
            List.of(new EastNorth(0.0, 0.0), new EastNorth(3.0, 0.0),
                new EastNorth(1.0, 0.0), new EastNorth(4.0, 0.0)));

        for (List<EastNorth> geometry : unsafe) {
            HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
                geometry,
                List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 3)),
                Set.of(), evidence(geometry, direct(geometry.size()), Set.of(), Set.of()),
                GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));
            assertEquals(HeatmapConstrainedSimplificationResult.Status.REJECTED, result.status());
            assertTrue(result.failureReasons().contains(
                HeatmapConstrainedSimplificationResult.FailureReason.SOURCE_TOPOLOGY_UNSAFE));
        }
    }

    @Test
    void rejectsAnAdjacentCollinearReversalBeforeReduction() {
        List<EastNorth> geometry = List.of(
            new EastNorth(0.0, 0.0), new EastNorth(2.0, 0.0), new EastNorth(1.0, 0.0));

        HeatmapConstrainedSimplificationResult result = SIMPLIFIER.simplify(
            geometry,
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 2)),
            Set.of(), evidence(geometry, direct(geometry.size()), Set.of(), Set.of()),
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(HeatmapConstrainedSimplificationResult.Status.REJECTED, result.status());
        assertEquals(geometry, result.geometry());
        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedSimplificationResult.FailureReason.SOURCE_TOPOLOGY_UNSAFE));
    }

    @Test
    void resultAndDiagnosticsAreDeterministicAndDeeplyImmutable() {
        List<EastNorth> geometry = geometry(0.0, 0.1, 0.0, -0.1, 0.0);
        CandidateCleanupEvidence evidence = evidence(
            geometry, direct(geometry.size()), Set.of(), Set.of());
        List<HeatmapConstrainedSimplifier.ProtectedInterval> intervals =
            List.of(new HeatmapConstrainedSimplifier.ProtectedInterval(0, 4));

        HeatmapConstrainedSimplificationResult first = SIMPLIFIER.simplify(
            geometry, intervals, Set.of(), evidence,
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));
        HeatmapConstrainedSimplificationResult second = SIMPLIFIER.simplify(
            geometry, intervals, Set.of(), evidence,
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class,
            () -> first.failureReasons().add(
                HeatmapConstrainedSimplificationResult.FailureReason.FIT_RETENTION));
        assertThrows(UnsupportedOperationException.class,
            () -> first.chordRejections().clear());
    }

    private static List<EastNorth> geometry(double... lateralOffsets) {
        List<EastNorth> result = new ArrayList<>(lateralOffsets.length);
        for (int index = 0; index < lateralOffsets.length; index++) {
            result.add(new EastNorth(index * 2.0, lateralOffsets[index]));
        }
        return result;
    }

    private static List<CleanupEvidenceProvenance> direct(int size) {
        return new ArrayList<>(Collections.nCopies(size, CleanupEvidenceProvenance.DIRECT));
    }

    private static CandidateCleanupEvidence evidence(
        List<EastNorth> geometry,
        List<CleanupEvidenceProvenance> provenance,
        Set<Integer> offRaster,
        Set<Integer> noSignal
    ) {
        return evidence(geometry, provenance, offRaster, noSignal, Set.of(), 1.0);
    }

    private static CandidateCleanupEvidence evidence(
        List<EastNorth> geometry,
        List<CleanupEvidenceProvenance> provenance,
        Set<Integer> offRaster,
        Set<Integer> noSignal,
        Set<Integer> supportedTurns
    ) {
        return evidence(geometry, provenance, offRaster, noSignal, supportedTurns, 1.0);
    }

    private static CandidateCleanupEvidence evidence(
        List<EastNorth> geometry,
        List<CleanupEvidenceProvenance> provenance,
        Set<Integer> offRaster,
        Set<Integer> noSignal,
        Set<Integer> supportedTurns,
        double groundMetersPerRasterPixel
    ) {
        return evidence(geometry, provenance, offRaster, noSignal, supportedTurns,
            groundMetersPerRasterPixel,
            new ArrayList<>(Collections.nCopies(geometry.size(), 0.0)), 1.0, 3.0);
    }

    private static CandidateCleanupEvidence evidence(
        List<EastNorth> geometry,
        List<CleanupEvidenceProvenance> provenance,
        Set<Integer> offRaster,
        Set<Integer> noSignal,
        Set<Integer> supportedTurns,
        double groundMetersPerRasterPixel,
        List<Double> intensityCenters,
        double coreRadius,
        double shoulderRadius
    ) {
        List<CleanupSamplingProfile> samples = new ArrayList<>();
        List<CandidateCleanupProfile> rows = new ArrayList<>();
        double[] offsets = {-5.0, -4.0, -3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0};
        boolean[] valid = new boolean[offsets.length];
        java.util.Arrays.fill(valid, true);
        for (int index = 0; index < geometry.size(); index++) {
            double[] intensity = new double[offsets.length];
            double center = intensityCenters.get(index);
            if (!noSignal.contains(index)) {
                for (int sample = 0; sample < intensity.length; sample++) {
                    double distance = offsets[sample] - center;
                    intensity[sample] = Math.exp(-0.5 * distance * distance);
                }
            }
            EastNorth point = geometry.get(index);
            samples.add(new CleanupSamplingProfile(index, index * 2.0, !offRaster.contains(index), 1.0,
                new ProjectedLateralTransform(new EastNorth(point.east(), 0.0), 0.0, 1.0),
                offsets, intensity, intensity, intensity, valid));
            CleanupEvidenceProvenance source = provenance.get(index);
            rows.add(source == CleanupEvidenceProvenance.UNSUPPORTED
                ? new CandidateCleanupProfile(index, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    0.0, 1.0, source, 0.0, 0.0, false)
                : new CandidateCleanupProfile(index,
                    center - coreRadius, center + coreRadius,
                    center - shoulderRadius, center + shoulderRadius,
                    center, 1.0, source, supportedTurns.contains(index) ? 1.0 : 0.0,
                    supportedTurns.contains(index) ? 1.0 : 0.0, false));
        }
        return CandidateCleanupEvidence.complete(
            new CleanupSamplingFrame("simplifier-test", samples, groundMetersPerRasterPixel), rows);
    }

    private static CandidateCleanupEvidence evidenceWithoutGroundScale(
        List<EastNorth> geometry,
        List<CleanupEvidenceProvenance> provenance,
        Set<Integer> offRaster,
        Set<Integer> noSignal
    ) {
        CandidateCleanupEvidence explicit = evidence(
            geometry, provenance, offRaster, noSignal, Set.of(), 1.0);
        return CandidateCleanupEvidence.complete(new CleanupSamplingFrame(
            explicit.samplingFrame().detectorMode(), explicit.samplingFrame().profiles()), explicit.profiles());
    }
}
