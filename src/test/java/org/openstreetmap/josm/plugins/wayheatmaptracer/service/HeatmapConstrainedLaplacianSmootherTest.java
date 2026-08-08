package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedLaplacianResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;

class HeatmapConstrainedLaplacianSmootherTest {
    private static final HeatmapConstrainedLaplacianSmoother SMOOTHER =
        new HeatmapConstrainedLaplacianSmoother();

    @Test
    void smoothsRipplePerProtectedIntervalWithoutChangingPointIdentity() {
        List<EastNorth> geometry = geometry(0.0, 1.2, -1.2, 1.2, -1.2, 1.2, 0.0);
        CandidateCleanupEvidence evidence = evidence(geometry, allDirect(geometry.size()),
            zeros(geometry.size()), Set.of(), Set.of());

        HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 3),
                new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(3, 6)),
            Set.of(3),
            evidence,
            GeometryCleanupPreset.STRONG.apply());

        assertEquals(HeatmapConstrainedLaplacianResult.Status.APPLIED, result.status());
        assertEquals(geometry.size(), result.geometry().size());
        assertEquals(geometry.get(0), result.geometry().get(0));
        assertEquals(geometry.get(3), result.geometry().get(3));
        assertEquals(geometry.get(6), result.geometry().get(6));
        assertTrue(secondDifferenceRms(result.geometry()) < secondDifferenceRms(geometry) * 0.85,
            () -> "before=" + secondDifferenceRms(geometry) + ", after="
                + secondDifferenceRms(result.geometry()));
        assertEquals(3, result.metrics().protectedPointCount());
        assertTrue(result.metrics().maximumDisplacementProjectionUnits() > 0.0);
    }

    @Test
    void broadCenteredCorridorKeepsMeanAbsoluteBiasWithinQuarterSourcePixel() {
        List<EastNorth> geometry = geometry(0.0, 0.55, -0.45, 0.50, -0.50, 0.45, -0.55, 0.0);
        CandidateCleanupEvidence evidence = evidence(
            geometry, allDirect(geometry.size()), zeros(geometry.size()), Set.of(), Set.of());

        HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, geometry.size() - 1)),
            Set.of(), evidence, GeometryCleanupPreset.STRONG.apply());

        double meanAbsoluteBiasSourcePixels = result.geometry().stream()
            .mapToDouble(point -> Math.abs(point.north()))
            .average().orElseThrow();
        assertTrue(meanAbsoluteBiasSourcePixels <= 0.25,
            () -> "broad-corridor center bias=" + meanAbsoluteBiasSourcePixels
                + " source px, geometry=" + result.geometry());
    }

    @Test
    void movesOnlyDirectUnprotectedRowsAndOnlyAlongCandidateOwnedNormal() {
        List<EastNorth> geometry = geometry(0.0, 1.0, -1.0, 1.0, -1.0, 0.0);
        List<CleanupEvidenceProvenance> provenance = allDirect(geometry.size());
        provenance.set(2, CleanupEvidenceProvenance.BOUNDED_INTERPOLATION);
        provenance.set(4, CleanupEvidenceProvenance.UNSUPPORTED);
        CandidateCleanupEvidence evidence = evidence(geometry, provenance, zeros(geometry.size()), Set.of(), Set.of());

        HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 5)),
            Set.of(),
            evidence,
            GeometryCleanupPreset.STRONG.apply());

        assertEquals(geometry.get(2), result.geometry().get(2));
        assertEquals(geometry.get(4), result.geometry().get(4));
        for (int index = 0; index < geometry.size(); index++) {
            assertEquals(geometry.get(index).east(), result.geometry().get(index).east(), 0.0,
                "normal-only movement changed chainage at index " + index);
        }
    }

    @Test
    void directRowsWithoutScalarSignalCannotAuthorizeMovement() {
        List<EastNorth> geometry = geometry(0.0, 1.0, -1.0, 1.0, 0.0);

        HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 4)),
            Set.of(), zeroSignalEvidence(geometry), GeometryCleanupPreset.STRONG.apply());

        assertEquals(HeatmapConstrainedLaplacianResult.Status.UNCHANGED, result.status());
        assertEquals(geometry, result.geometry());
        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedLaplacianResult.FailureReason.NO_AUTHORIZED_MOVEMENT));
    }

    @Test
    void retainsRawB3B5FitAndBacktracksWithinShoulder() {
        List<EastNorth> geometry = geometry(0.0, 1.5, -1.5, 1.5, 0.0);
        CandidateCleanupEvidence evidence = evidence(geometry, allDirect(geometry.size()),
            offsets(geometry), Set.of(), Set.of());
        GeometryCleanupConfig strict = GeometryCleanupPreset.STRONG.apply()
            .withMinimumFitRetention(0.99);

        HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 4)),
            Set.of(), evidence, strict);

        assertTrue(result.metrics().fitAfter() >= result.metrics().fitBefore() * 0.99 - 1e-12);
        assertTrue(result.metrics().backtrackCount() > 0 || result.status()
            == HeatmapConstrainedLaplacianResult.Status.UNCHANGED);
        assertTrue(result.metrics().fitRetentionFailureCount() > 0
            || result.metrics().containmentFailureCount() > 0);
        assertTrue(result.geometry().stream().allMatch(point -> Math.abs(point.north()) <= 3.0 + 1e-12));
    }

    @Test
    void preservesSupportedTurnAmplitudeAndFreezesScaleConflict() {
        List<EastNorth> geometry = geometry(0.0, 2.0, 4.0, 6.0, 4.0, 2.0, 0.0);
        Set<Integer> turnSupport = Set.of(2, 3, 4);
        Set<Integer> scaleConflict = Set.of(1);
        CandidateCleanupEvidence evidence = evidence(geometry, allDirect(geometry.size()),
            offsets(geometry), turnSupport, scaleConflict);

        HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 6)),
            Set.of(), evidence, GeometryCleanupPreset.STRONG.apply());

        assertEquals(geometry.get(1), result.geometry().get(1));
        assertTrue(amplitude(result.geometry()) >= 0.90 * amplitude(geometry),
            () -> "before=" + amplitude(geometry) + ", after=" + amplitude(result.geometry()));
        assertTrue(result.metrics().supportedTurnRetention() >= 0.90);
    }

    @Test
    void isDeterministicAndDoesNotMutateCallerGeometry() {
        List<EastNorth> mutable = new ArrayList<>(geometry(0.0, 1.0, -1.0, 1.0, -1.0, 0.0));
        List<EastNorth> snapshot = List.copyOf(mutable);
        CandidateCleanupEvidence evidence = evidence(mutable, allDirect(mutable.size()),
            zeros(mutable.size()), Set.of(), Set.of());
        List<HeatmapConstrainedLaplacianSmoother.ProtectedInterval> intervals =
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 5));

        HeatmapConstrainedLaplacianResult first = SMOOTHER.smooth(
            mutable, intervals, Set.of(), evidence, GeometryCleanupPreset.BALANCED.apply());
        HeatmapConstrainedLaplacianResult second = SMOOTHER.smooth(
            mutable, intervals, Set.of(), evidence, GeometryCleanupPreset.BALANCED.apply());

        assertEquals(first, second);
        assertEquals(snapshot, mutable);
        assertFalse(first.geometry() == mutable);
    }

    @Test
    void isInvariantToEquivalentRasterOversampling() {
        List<EastNorth> geometry = geometry(0.0, 1.2, -1.2, 1.2, -1.2, 0.0);
        CandidateCleanupEvidence nativeEvidence = evidence(
            geometry, allDirect(geometry.size()), zeros(geometry.size()), Set.of(), Set.of(), 1.0, 1.0);
        CandidateCleanupEvidence oversampledEvidence = evidence(
            geometry, allDirect(geometry.size()), zeros(geometry.size()), Set.of(), Set.of(), 2.0, 2.0);
        List<HeatmapConstrainedLaplacianSmoother.ProtectedInterval> intervals =
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 5));

        HeatmapConstrainedLaplacianResult nativeResult = SMOOTHER.smooth(
            geometry, intervals, Set.of(), nativeEvidence, GeometryCleanupPreset.STRONG.apply());
        HeatmapConstrainedLaplacianResult oversampledResult = SMOOTHER.smooth(
            geometry, intervals, Set.of(), oversampledEvidence, GeometryCleanupPreset.STRONG.apply());

        assertEquals(nativeResult.status(), oversampledResult.status());
        for (int index = 0; index < geometry.size(); index++) {
            assertEquals(nativeResult.geometry().get(index).east(),
                oversampledResult.geometry().get(index).east(), 1e-12);
            assertEquals(nativeResult.geometry().get(index).north(),
                oversampledResult.geometry().get(index).north(), 1e-12);
        }
    }

    @Test
    void physicalChainageWeightsKeepLinearGeometryStationaryAtUnevenProfileDensity() {
        List<EastNorth> geometry = List.of(
            new EastNorth(0.0, 0.0), new EastNorth(1.0, 1.0),
            new EastNorth(4.0, 4.0), new EastNorth(6.0, 6.0));
        List<Double> chainages = List.of(0.0, 1.0, 4.0, 6.0);
        CandidateCleanupEvidence evidence = evidence(
            geometry, allDirect(geometry.size()), offsets(geometry), Set.of(), Set.of(),
            1.0, 1.0, chainages);

        HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 3)),
            Set.of(), evidence, GeometryCleanupPreset.STRONG.apply());

        assertEquals(HeatmapConstrainedLaplacianResult.Status.UNCHANGED, result.status());
        assertEquals(geometry, result.geometry());
    }

    @Test
    void changingNormalsUseTheNormalComponentOfACurvedSineLaplacian() {
        List<Double> chainages = List.of(0.0, 1.1, 2.8, 4.2, 6.7, 8.1, 11.0);
        List<EastNorth> geometry = List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(1.0, 1.35),
            new EastNorth(2.6, 1.45),
            new EastNorth(4.0, 2.15),
            new EastNorth(6.1, 0.65),
            new EastNorth(7.6, -0.65),
            new EastNorth(10.0, -1.5));
        List<EastNorth> normals = localNormals(geometry);
        CandidateCleanupEvidence evidence = evidenceWithNormals(
            geometry, chainages, normals, Set.of(), Set.of(), Set.of());

        HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 6)),
            Set.of(), evidence, GeometryCleanupPreset.STRONG.apply());

        assertEquals(HeatmapConstrainedLaplacianResult.Status.APPLIED, result.status());
        int moved = 0;
        for (int index = 1; index < geometry.size() - 1; index++) {
            EastNorth displacement = difference(result.geometry().get(index), geometry.get(index));
            if (Math.hypot(displacement.east(), displacement.north()) > 1e-9) {
                moved++;
                EastNorth normal = normals.get(index);
                assertEquals(0.0,
                    displacement.east() * normal.north() - displacement.north() * normal.east(),
                    1e-9, "movement was not parallel to the candidate normal at " + index);
            }
        }
        assertTrue(moved >= 2, "curved normal-component smoothing moved only " + moved + " points");
    }

    @Test
    void supportedApexRetainsNinetyPercentWithChangingNormalsAndUnevenChainage() {
        List<Double> chainages = List.of(0.0, 1.3, 3.0, 4.1, 6.5, 8.4, 11.7);
        List<EastNorth> geometry = List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(1.1, 1.2),
            new EastNorth(2.7, 3.4),
            new EastNorth(3.8, 5.2),
            new EastNorth(5.9, 3.1),
            new EastNorth(7.4, 1.1),
            new EastNorth(10.0, 0.0));
        CandidateCleanupEvidence evidence = evidenceWithNormals(
            geometry, chainages, localNormals(geometry), Set.of(3), Set.of(), Set.of());

        HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 6)),
            Set.of(), evidence, GeometryCleanupPreset.STRONG.apply());

        assertEquals(HeatmapConstrainedLaplacianResult.Status.APPLIED, result.status());
        assertTrue(result.metrics().supportedTurnRetention() >= 0.90,
            () -> "supported apex retention=" + result.metrics().supportedTurnRetention());
        assertTrue(result.geometry().get(3).north() >= geometry.get(3).north() * 0.90);
    }

    @Test
    void interiorProtectedPointsSplitASuppliedIntervalExactlyLikeExplicitIntervals() {
        List<EastNorth> geometry = geometry(0.0, 1.2, -1.2, 3.0, -1.2, 1.2, 0.0);
        CandidateCleanupEvidence evidence = evidence(
            geometry, allDirect(geometry.size()), zeros(geometry.size()), Set.of(), Set.of());

        HeatmapConstrainedLaplacianResult implicit = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 6)),
            Set.of(3), evidence, GeometryCleanupPreset.STRONG.apply());
        HeatmapConstrainedLaplacianResult explicit = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 3),
                new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(3, 6)),
            Set.of(3), evidence, GeometryCleanupPreset.STRONG.apply());

        assertEquals(explicit, implicit);
        assertEquals(geometry.get(3), implicit.geometry().get(3));
    }

    @Test
    void directRowCannotMoveBesidePredictedUnsupportedOrNoSignalNeighbor() {
        List<EastNorth> geometry = geometry(0.0, 0.0, 2.0, 0.0, 0.0);
        List<NeighborCase> cases = List.of(
            new NeighborCase("predicted", CleanupEvidenceProvenance.BOUNDED_INTERPOLATION, Set.of()),
            new NeighborCase("unsupported", CleanupEvidenceProvenance.UNSUPPORTED, Set.of()),
            new NeighborCase("no-signal", CleanupEvidenceProvenance.DIRECT, Set.of(1)));

        for (NeighborCase neighborCase : cases) {
            CandidateCleanupEvidence evidence = neighborEvidence(
                geometry, neighborCase.provenance(), neighborCase.noSignalIndexes());
            HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
                geometry,
                List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 4)),
                Set.of(1, 3), evidence, GeometryCleanupPreset.STRONG.apply());

            assertEquals(HeatmapConstrainedLaplacianResult.Status.UNCHANGED, result.status(),
                neighborCase.name());
            assertEquals(geometry, result.geometry(), neighborCase.name());
            assertTrue(result.failureReasons().contains(
                HeatmapConstrainedLaplacianResult.FailureReason.NO_AUTHORIZED_MOVEMENT),
                neighborCase.name());
        }
    }

    @Test
    void backtracksProposalsCreatingCrossingTouchOrCollinearOverlap() {
        List<TopologyCase> cases = List.of(
            new TopologyCase("proper crossing", ContactType.PROPER_CROSSING, List.of(
                new EastNorth(0.0, 0.0), new EastNorth(2.0, 2.0), new EastNorth(4.0, 0.0),
                new EastNorth(3.2, 0.65), new EastNorth(2.8, 0.65))),
            new TopologyCase("endpoint touch", ContactType.ENDPOINT_TOUCH, List.of(
                new EastNorth(0.0, 0.0), new EastNorth(2.0, 2.0), new EastNorth(4.0, 0.0),
                new EastNorth(3.0, 0.65), new EastNorth(2.8, 0.65))),
            new TopologyCase("collinear overlap", ContactType.COLLINEAR_OVERLAP, List.of(
                new EastNorth(0.0, 0.0), new EastNorth(2.0, 2.0), new EastNorth(4.0, 0.0),
                new EastNorth(3.0, 0.65), new EastNorth(2.5, 0.975))));
        GeometryCleanupConfig singlePass = GeometryCleanupPreset.STRONG.apply()
            .withLaplacianPassCount(1).withMinimumFitRetention(0.0);

        for (TopologyCase topologyCase : cases) {
            List<EastNorth> geometry = topologyCase.geometry();
            List<EastNorth> unsafeFullStep = new ArrayList<>(geometry);
            unsafeFullStep.set(1, new EastNorth(2.0, 1.30));
            assertEquals(ContactType.NONE, contact(geometry), topologyCase.name() + " source");
            assertEquals(topologyCase.expectedContact(), contact(unsafeFullStep),
                topologyCase.name() + " fixture");
            HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
                geometry,
                List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 4)),
                Set.of(2, 3), broadVerticalEvidence(geometry), singlePass);

            assertTrue(result.failureReasons().contains(
                HeatmapConstrainedLaplacianResult.FailureReason.SELF_INTERSECTION),
                topologyCase.name() + ": " + result.failureReasons());
            assertTrue(result.metrics().backtrackCount() > 0, topologyCase.name());
            assertEquals(ContactType.NONE, contact(result.geometry()),
                topologyCase.name() + " accepted unsafe contact: " + result.geometry());
        }
    }

    @Test
    void failsClosedForIneligibleEvidenceAndUnsafeSourceGeometry() {
        List<EastNorth> geometry = geometry(0.0, 1.0, -1.0, 0.0);
        CandidateCleanupEvidence ineligible = CandidateCleanupEvidence.empty();

        HeatmapConstrainedLaplacianResult missingEvidence = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 3)),
            Set.of(), ineligible, GeometryCleanupPreset.BALANCED.apply());
        assertEquals(HeatmapConstrainedLaplacianResult.Status.REJECTED, missingEvidence.status());
        assertTrue(missingEvidence.failureReasons().contains(
            HeatmapConstrainedLaplacianResult.FailureReason.INELIGIBLE_EVIDENCE));

        List<EastNorth> crossing = List.of(
            new EastNorth(0.0, 0.0), new EastNorth(2.0, 2.0),
            new EastNorth(0.0, 2.0), new EastNorth(2.0, 0.0));
        CandidateCleanupEvidence crossingEvidence = evidence(crossing, allDirect(crossing.size()),
            offsets(crossing), Set.of(), Set.of());
        HeatmapConstrainedLaplacianResult unsafe = SMOOTHER.smooth(
            crossing,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 3)),
            Set.of(), crossingEvidence, GeometryCleanupPreset.BALANCED.apply());
        assertEquals(HeatmapConstrainedLaplacianResult.Status.REJECTED, unsafe.status());
        assertTrue(unsafe.failureReasons().contains(
            HeatmapConstrainedLaplacianResult.FailureReason.SELF_INTERSECTION));
        assertEquals(crossing, unsafe.geometry());
    }

    @Test
    void nonSmoothingModesReturnAnImmutableUnchangedResult() {
        List<EastNorth> geometry = geometry(0.0, 1.0, -1.0, 0.0);
        CandidateCleanupEvidence evidence = evidence(geometry, allDirect(geometry.size()),
            zeros(geometry.size()), Set.of(), Set.of());
        GeometryCleanupConfig reduceOnly = GeometryCleanupPreset.BALANCED
            .apply(GeometryCleanupMode.REDUCE_POINTS_ONLY);

        HeatmapConstrainedLaplacianResult result = SMOOTHER.smooth(
            geometry,
            List.of(new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(0, 3)),
            Set.of(), evidence, reduceOnly);

        assertEquals(HeatmapConstrainedLaplacianResult.Status.UNCHANGED, result.status());
        assertEquals(geometry, result.geometry());
        assertTrue(result.failureReasons().contains(
            HeatmapConstrainedLaplacianResult.FailureReason.MODE_DISABLED));
    }

    private static List<EastNorth> geometry(double... offsets) {
        List<EastNorth> result = new ArrayList<>(offsets.length);
        for (int index = 0; index < offsets.length; index++) {
            result.add(new EastNorth(index * 2.0, offsets[index]));
        }
        return result;
    }

    private static CandidateCleanupEvidence evidence(
        List<EastNorth> geometry,
        List<CleanupEvidenceProvenance> provenance,
        List<Double> intensityCenters,
        Set<Integer> turnSupport,
        Set<Integer> scaleConflict
    ) {
        List<Double> chainages = new ArrayList<>(geometry.size());
        for (int index = 0; index < geometry.size(); index++) {
            chainages.add(index * 2.0);
        }
        return evidence(geometry, provenance, intensityCenters, turnSupport, scaleConflict,
            1.0, 1.0, chainages);
    }

    private static CandidateCleanupEvidence evidence(
        List<EastNorth> geometry,
        List<CleanupEvidenceProvenance> provenance,
        List<Double> intensityCenters,
        Set<Integer> turnSupport,
        Set<Integer> scaleConflict,
        double rasterPixelsPerProjectionUnit,
        double sourcePixelPitchRasterPx
    ) {
        List<Double> chainages = new ArrayList<>(geometry.size());
        for (int index = 0; index < geometry.size(); index++) {
            chainages.add(index * 2.0);
        }
        return evidence(geometry, provenance, intensityCenters, turnSupport, scaleConflict,
            rasterPixelsPerProjectionUnit, sourcePixelPitchRasterPx, chainages);
    }

    private static CandidateCleanupEvidence evidence(
        List<EastNorth> geometry,
        List<CleanupEvidenceProvenance> provenance,
        List<Double> intensityCenters,
        Set<Integer> turnSupport,
        Set<Integer> scaleConflict,
        double rasterPixelsPerProjectionUnit,
        double sourcePixelPitchRasterPx,
        List<Double> chainages
    ) {
        List<CleanupSamplingProfile> samples = new ArrayList<>();
        List<CandidateCleanupProfile> rows = new ArrayList<>();
        for (int index = 0; index < geometry.size(); index++) {
            double center = intensityCenters.get(index) * rasterPixelsPerProjectionUnit;
            int sampleRadius = (int) Math.ceil(4.0 * rasterPixelsPerProjectionUnit);
            double[] sampleOffsets = new double[2 * sampleRadius + 1];
            double[] intensity = new double[sampleOffsets.length];
            boolean[] valid = new boolean[sampleOffsets.length];
            for (int sample = 0; sample < sampleOffsets.length; sample++) {
                sampleOffsets[sample] = sample - sampleRadius;
                double distance = sampleOffsets[sample] - center;
                double sigmaRasterPx = 1.2 * rasterPixelsPerProjectionUnit;
                intensity[sample] = Math.exp(-0.5 * distance * distance
                    / (sigmaRasterPx * sigmaRasterPx));
                valid[sample] = true;
            }
            EastNorth point = geometry.get(index);
            samples.add(new CleanupSamplingProfile(index, chainages.get(index), true,
                sourcePixelPitchRasterPx,
                new ProjectedLateralTransform(new EastNorth(point.east(), 0.0),
                    0.0, 1.0 / rasterPixelsPerProjectionUnit),
                sampleOffsets, intensity, intensity, intensity, valid));
            CleanupEvidenceProvenance source = provenance.get(index);
            rows.add(source == CleanupEvidenceProvenance.UNSUPPORTED
                ? new CandidateCleanupProfile(index, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    0.0, 1.0, source, 0.0, 0.0, scaleConflict.contains(index))
                : new CandidateCleanupProfile(index,
                    center - rasterPixelsPerProjectionUnit, center + rasterPixelsPerProjectionUnit,
                    center - 3.0 * rasterPixelsPerProjectionUnit,
                    center + 3.0 * rasterPixelsPerProjectionUnit,
                    center, sourcePixelPitchRasterPx, source,
                    turnSupport.contains(index) ? 1.0 : 0.0,
                    turnSupport.contains(index) ? 1.0 : 0.0,
                    scaleConflict.contains(index)));
        }
        return CandidateCleanupEvidence.complete(new CleanupSamplingFrame("test", samples), rows);
    }

    private static CandidateCleanupEvidence zeroSignalEvidence(List<EastNorth> geometry) {
        List<CleanupSamplingProfile> samples = new ArrayList<>();
        List<CandidateCleanupProfile> rows = new ArrayList<>();
        double[] offsets = {-2.0, -1.0, 0.0, 1.0, 2.0};
        double[] intensity = new double[offsets.length];
        boolean[] valid = {true, true, true, true, true};
        for (int index = 0; index < geometry.size(); index++) {
            EastNorth point = geometry.get(index);
            samples.add(new CleanupSamplingProfile(index, index * 2.0, true, 1.0,
                new ProjectedLateralTransform(new EastNorth(point.east(), 0.0), 0.0, 1.0),
                offsets, intensity, intensity, intensity, valid));
            rows.add(new CandidateCleanupProfile(index, -1.0, 1.0, -2.0, 2.0,
                0.0, 1.0, CleanupEvidenceProvenance.DIRECT, 0.0, 0.0, false));
        }
        return CandidateCleanupEvidence.complete(new CleanupSamplingFrame("no-signal", samples), rows);
    }

    private static CandidateCleanupEvidence evidenceWithNormals(
        List<EastNorth> geometry,
        List<Double> chainages,
        List<EastNorth> normals,
        Set<Integer> turnSupport,
        Set<Integer> scaleConflict,
        Set<Integer> noSignalIndexes
    ) {
        List<CleanupSamplingProfile> samples = new ArrayList<>();
        List<CandidateCleanupProfile> rows = new ArrayList<>();
        double[] offsets = {-4.0, -3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0, 4.0};
        boolean[] valid = {true, true, true, true, true, true, true, true, true};
        for (int index = 0; index < geometry.size(); index++) {
            double[] intensity = new double[offsets.length];
            if (!noSignalIndexes.contains(index)) {
                for (int sample = 0; sample < offsets.length; sample++) {
                    intensity[sample] = Math.exp(-0.5 * offsets[sample] * offsets[sample] / 4.0);
                }
            }
            EastNorth normal = normals.get(index);
            samples.add(new CleanupSamplingProfile(index, chainages.get(index), true, 1.0,
                new ProjectedLateralTransform(geometry.get(index), normal.east(), normal.north()),
                offsets, intensity, intensity, intensity, valid));
            rows.add(new CandidateCleanupProfile(index, -1.5, 1.5, -3.5, 3.5,
                0.0, 1.0, CleanupEvidenceProvenance.DIRECT,
                turnSupport.contains(index) ? 1.0 : 0.0,
                turnSupport.contains(index) ? 1.0 : 0.0,
                scaleConflict.contains(index)));
        }
        return CandidateCleanupEvidence.complete(new CleanupSamplingFrame("curved", samples), rows);
    }

    private static CandidateCleanupEvidence neighborEvidence(
        List<EastNorth> geometry,
        CleanupEvidenceProvenance neighborProvenance,
        Set<Integer> noSignalIndexes
    ) {
        List<CleanupSamplingProfile> samples = new ArrayList<>();
        List<CandidateCleanupProfile> rows = new ArrayList<>();
        double[] offsets = {-3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0};
        boolean[] valid = {true, true, true, true, true, true, true};
        for (int index = 0; index < geometry.size(); index++) {
            double[] intensity = new double[offsets.length];
            if (!noSignalIndexes.contains(index)) {
                java.util.Arrays.fill(intensity, 1.0);
            }
            samples.add(new CleanupSamplingProfile(index, index * 2.0, true, 1.0,
                new ProjectedLateralTransform(new EastNorth(geometry.get(index).east(), 0.0), 0.0, 1.0),
                offsets, intensity, intensity, intensity, valid));
            CleanupEvidenceProvenance provenance = index == 1
                ? neighborProvenance : CleanupEvidenceProvenance.DIRECT;
            rows.add(provenance == CleanupEvidenceProvenance.UNSUPPORTED
                ? new CandidateCleanupProfile(index, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    0.0, 1.0, provenance, 0.0, 0.0, false)
                : new CandidateCleanupProfile(index, -2.0, 2.0, -3.0, 3.0,
                    0.0, 1.0, provenance, 0.0, 0.0, false));
        }
        return CandidateCleanupEvidence.complete(new CleanupSamplingFrame("neighbor", samples), rows);
    }

    private static CandidateCleanupEvidence broadVerticalEvidence(List<EastNorth> geometry) {
        List<Double> chainages = new ArrayList<>();
        for (int index = 0; index < geometry.size(); index++) {
            chainages.add((double) index);
        }
        return evidenceWithNormals(geometry, chainages,
            java.util.Collections.nCopies(geometry.size(), new EastNorth(0.0, 1.0)),
            Set.of(), Set.of(), Set.of());
    }

    private static List<EastNorth> localNormals(List<EastNorth> geometry) {
        List<EastNorth> normals = new ArrayList<>();
        for (int index = 0; index < geometry.size(); index++) {
            EastNorth left = geometry.get(Math.max(0, index - 1));
            EastNorth right = geometry.get(Math.min(geometry.size() - 1, index + 1));
            double tangentEast = right.east() - left.east();
            double tangentNorth = right.north() - left.north();
            double length = Math.hypot(tangentEast, tangentNorth);
            normals.add(new EastNorth(-tangentNorth / length, tangentEast / length));
        }
        return List.copyOf(normals);
    }

    private static EastNorth difference(EastNorth left, EastNorth right) {
        return new EastNorth(left.east() - right.east(), left.north() - right.north());
    }

    private static ContactType contact(List<EastNorth> geometry) {
        EastNorth a = geometry.get(1);
        EastNorth b = geometry.get(2);
        EastNorth c = geometry.get(3);
        EastNorth d = geometry.get(4);
        double abC = orientation(a, b, c);
        double abD = orientation(a, b, d);
        double cdA = orientation(c, d, a);
        double cdB = orientation(c, d, b);
        if (Math.abs(abC) <= 1e-9 && Math.abs(abD) <= 1e-9
            && Math.abs(cdA) <= 1e-9 && Math.abs(cdB) <= 1e-9) {
            double overlapEast = Math.min(Math.max(a.east(), b.east()), Math.max(c.east(), d.east()))
                - Math.max(Math.min(a.east(), b.east()), Math.min(c.east(), d.east()));
            double overlapNorth = Math.min(Math.max(a.north(), b.north()), Math.max(c.north(), d.north()))
                - Math.max(Math.min(a.north(), b.north()), Math.min(c.north(), d.north()));
            if (Math.max(overlapEast, overlapNorth) > 1e-9) {
                return ContactType.COLLINEAR_OVERLAP;
            }
        }
        if (opposite(abC, abD) && opposite(cdA, cdB)) {
            return ContactType.PROPER_CROSSING;
        }
        if (Math.abs(abC) <= 1e-9 && onSegment(c, a, b)
            || Math.abs(abD) <= 1e-9 && onSegment(d, a, b)
            || Math.abs(cdA) <= 1e-9 && onSegment(a, c, d)
            || Math.abs(cdB) <= 1e-9 && onSegment(b, c, d)) {
            return ContactType.ENDPOINT_TOUCH;
        }
        return ContactType.NONE;
    }

    private static double orientation(EastNorth a, EastNorth b, EastNorth c) {
        return (b.east() - a.east()) * (c.north() - a.north())
            - (b.north() - a.north()) * (c.east() - a.east());
    }

    private static boolean opposite(double left, double right) {
        return left > 1e-9 && right < -1e-9 || left < -1e-9 && right > 1e-9;
    }

    private static boolean onSegment(EastNorth point, EastNorth start, EastNorth end) {
        return point.east() >= Math.min(start.east(), end.east()) - 1e-9
            && point.east() <= Math.max(start.east(), end.east()) + 1e-9
            && point.north() >= Math.min(start.north(), end.north()) - 1e-9
            && point.north() <= Math.max(start.north(), end.north()) + 1e-9;
    }

    private static List<CleanupEvidenceProvenance> allDirect(int size) {
        return new ArrayList<>(java.util.Collections.nCopies(size, CleanupEvidenceProvenance.DIRECT));
    }

    private static List<Double> zeros(int size) {
        return new ArrayList<>(java.util.Collections.nCopies(size, 0.0));
    }

    private static List<Double> offsets(List<EastNorth> geometry) {
        return geometry.stream().map(EastNorth::north).toList();
    }

    private static double secondDifferenceRms(List<EastNorth> geometry) {
        double sum = 0.0;
        for (int index = 1; index < geometry.size() - 1; index++) {
            double value = geometry.get(index - 1).north() - 2.0 * geometry.get(index).north()
                + geometry.get(index + 1).north();
            sum += value * value;
        }
        return Math.sqrt(sum / (geometry.size() - 2));
    }

    private static double amplitude(List<EastNorth> geometry) {
        double minimum = geometry.stream().mapToDouble(EastNorth::north).min().orElseThrow();
        double maximum = geometry.stream().mapToDouble(EastNorth::north).max().orElseThrow();
        return (maximum - minimum) * 0.5;
    }

    private record NeighborCase(
        String name,
        CleanupEvidenceProvenance provenance,
        Set<Integer> noSignalIndexes
    ) {
    }

    private enum ContactType {
        NONE,
        PROPER_CROSSING,
        ENDPOINT_TOUCH,
        COLLINEAR_OVERLAP
    }

    private record TopologyCase(String name, ContactType expectedContact, List<EastNorth> geometry) {
    }
}
