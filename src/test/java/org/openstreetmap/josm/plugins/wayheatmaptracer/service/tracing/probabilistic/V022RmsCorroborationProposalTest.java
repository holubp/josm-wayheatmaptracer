package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Eligibility cannot promote a suppressed shape or manufacture raster support. */
class V022RmsCorroborationProposalTest {
    @Test
    void actualImpulseProducesOnlyTheWeakCoreProposalWithoutChangingBaselineIdentities() {
        Fixture fixture = rasterFixture((x, y) -> Math.min(1.0, 0.02
                + 0.08 * gaussian(y - 26, 0.9) + (x == 60 && y == 28 ? 0.9 : 0.0)),
                List.of(new MetricPoint(55, 26), new MetricPoint(65, 26)), 5.0);
        var profiles = fixture.profiles();
        var before = List.copyOf(profiles);
        var modes = profiles.stream().map(ProbabilisticProfile::modes).toList();
        var proposals = build(fixture);
        assertEquals(1, proposals.size());
        var proposal = proposals.get(0);
        assertSame(profiles.get(1), proposal.profile());
        assertSame(profiles.get(0), proposal.leftProfile());
        assertSame(profiles.get(2), proposal.rightProfile());
        assertTrue(fixture.details().get(1).suppressedModes().stream()
                .anyMatch(value -> value == proposal.suppression()));
        assertEquals(0.0, proposal.suppression().mode().centerMeters(), 1e-12);
        assertSame(profiles.get(0).modes().get(0), proposal.leftMode());
        assertSame(profiles.get(2).modes().get(0), proposal.rightMode());
        assertEquals(before, profiles);
        for (int i = 0; i < profiles.size(); i++) assertSame(modes.get(i), profiles.get(i).modes());
        assertTrue(profiles.get(1).modes().stream().noneMatch(mode ->
                Math.abs(mode.coreCenterMeters()) < 0.5), "eligibility must not restore the rejected mode");
        assertThrows(UnsupportedOperationException.class, proposals::clear);
    }

    @Test
    void physicalCoreEligibilityIsInvariantUnderTranslationRotationAndTraversalReversal() {
        for (double angle : List.of(0.0, 0.7, 1.9)) {
            for (boolean reversed : List.of(false, true)) {
                var fixture = analytic(5.0, angle, reversed, 0.75, 1.0, true, false);
                assertEquals(1, build(fixture).size(), "angle=" + angle + " reversed=" + reversed);
            }
        }
    }

    @Test
    void finalCoreRatherThanNearbyRawPeakMustLieWithinOneFactualNativePitch() {
        assertEquals(1, build(analytic(5, 0, false, 1.0, 1.0, true, false)).size());
        assertTrue(build(analytic(5, 0, false, 1.001, 1.0, true, false)).isEmpty());
        assertTrue(build(analytic(5, 0, false, 0.75, 0.25, true, false)).isEmpty(),
                "pitch is physical metres, not a profile sample count");
    }

    @Test
    void fixedUnknownPitchAndMoreThanTwentyMeterSpansCannotReceiveProposals() {
        assertTrue(build(analytic(5, 0, false, 0, 1, true, true)).isEmpty());
        assertTrue(build(analytic(5, 0, false, 0, 1, false, false)).isEmpty());
        assertEquals(1, build(analytic(10, 0, false, 0, 1, true, false)).size());
        assertTrue(build(analytic(10.01, 0, false, 0, 1, true, false)).isEmpty());
    }

    @Test
    void finalCoreOutsideUnchangedDecisionWindowIsRefused() {
        assertTrue(build(analytic(5, 0, false, 8.5, 10, true, false)).isEmpty());
        assertTrue(build(analytic(5, 0, false, 7.75, 10, true, false)).isEmpty(),
                "a high core ending exactly at the decision boundary remains censored");
    }

    @Test
    void proximityToChordExtensionCannotSubstituteForTheFiniteExteriorSegment() {
        Fixture fixture = analytic(5, 0, false, 6, 1, true, false);
        var profile = fixture.profiles().get(1);
        // Center = (110.9699, -39.4), exterior chord x=[100,110], y=-40.
        // Distance to its infinite extension is 0.6 m, but to the segment is 1.14 m.
        var skew = new ProbabilisticProfile(profile.profileIndex(), profile.chainageMeters(),
                profile.anchor(), new MetricPoint(Math.sqrt(0.99), 0.1), -8, 8, 1, true,
                profile.noiseFloor(), profile.samples(), profile.modes(), profile.censoredModes(),
                profile.exactAnchorOffsetMeters(), profile.orientationSupport());
        var profiles = new ArrayList<>(fixture.profiles());
        profiles.set(1, skew);
        assertTrue(build(new Fixture(List.copyOf(profiles), fixture.details())).isEmpty());
    }

    @Test
    void unmeasuredAndGroupedExteriorModesCannotCorroborate() {
        Fixture fixture = analytic(5, 0, false, 0, 1, true, false);
        var left = fixture.profiles().get(0);
        var mode = left.modes().get(0);
        var unknown = ImageOrientationSupport.unknown(ImageOrientationSupport.Status.UNKNOWN_FLAT);
        assertTrue(build(withModes(fixture, 0, List.of(mode(mode, "unknown", 0, false, unknown)))).isEmpty());
        assertTrue(build(withModes(fixture, 0, List.of(mode(mode, "parent", 0, true,
                mode.orientationSupport())))).isEmpty());
    }

    @Test
    void exactReciprocalBestTiesRefuseInsteadOfSelectingFirstInInputOrder() {
        Fixture fixture = analytic(5, 0, false, 0, 1, true, false);
        var mode = fixture.profiles().get(0).modes().get(0);
        assertTrue(build(withModes(fixture, 0, List.of(mode,
                mode(mode, "equal", 0, false, mode.orientationSupport())))).isEmpty());
        assertTrue(build(withModes(fixture, 0, List.of(
                mode(mode, "equal", 0, false, mode.orientationSupport()), mode))).isEmpty());
    }

    @Test
    void completeParallelBranchesAndTheirOrderingSurviveAmbiguityRefusal() {
        Fixture fixture = analytic(5, 0, false, 0, 1, true, false);
        for (int index : List.of(0, 2)) {
            var mode = fixture.profiles().get(index).modes().get(0);
            fixture = withModes(fixture, index, List.of(mode,
                    mode(mode, "parallel", 3, false, mode.orientationSupport())));
        }
        var before = fixture.profiles();
        assertTrue(build(fixture).isEmpty());
        assertSame(before, fixture.profiles());
        assertEquals(List.of("exterior-0", "parallel"), fixture.profiles().get(0).modes()
                .stream().map(ProbabilisticProfile.Mode::id).toList());
    }

    @Test
    void rawExteriorRivalsVetoEvenWhenTheyWereNotAdmittedAsModes() {
        Fixture fixture = analytic(5, 0, false, 0, 1, true, false);
        for (int index : List.of(0, 2)) {
            var detail = fixture.details().get(index);
            var rivals = new ArrayList<>(detail.rawPeaks());
            rivals.add(new LocalScalarProfileExtractor.RawPeak(22, 22, 3,
                    fixture.profiles().get(index).samples().get(22).intensity()));
            assertTrue(build(withDetail(fixture, index, new LocalScalarProfileExtractor.DetailedResult(
                    detail.baseline(), detail.suppressedModes(), rivals))).isEmpty());
        }
    }

    @Test
    void twoQualifyingSuppressionIdentitiesRefuseTheEntireInteriorChoice() {
        Fixture fixture = analytic(5, 0, false, 0, 1, true, false);
        var detail = fixture.details().get(1);
        var suppression = detail.suppressedModes().get(0);
        assertTrue(build(withDetail(fixture, 1, new LocalScalarProfileExtractor.DetailedResult(
                detail.baseline(), List.of(suppression, shifted(suppression, 0.2)),
                detail.rawPeaks()))).isEmpty());
    }

    @Test
    void adjacentSuppressedProfilesCannotBecomeTransitiveExteriorEvidence() {
        Fixture fixture = analytic(5, 0, false, 0, 1, true, false);
        var profiles = new ArrayList<>(fixture.profiles());
        profiles.set(1, copy(profiles.get(1), List.of(), profiles.get(1).exactAnchorOffsetMeters()));
        profiles.add(2, new ProbabilisticProfile(3, 7.5, new MetricPoint(107.5, -40),
                new MetricPoint(0, 1), -8, 8, 1, true, 0.02, profiles.get(1).samples(),
                List.of(), List.of(), OptionalDouble.empty(), profiles.get(1).orientationSupport()));
        var details = new ArrayList<>(fixture.details());
        details.add(2, details.get(1));
        assertTrue(build(new Fixture(List.copyOf(profiles), List.copyOf(details))).isEmpty());
    }

    @Test
    void censoredAndInvalidScalarSupportCannotSupplyAnEligibleSuppression() {
        for (double center : List.of(-8.0, 8.0)) {
            Fixture fixture = rasterFixture((x, y) -> 0.02 + 0.08 * gaussian(y - 26 - center, 0.9)
                    + (x == 60 && y == 28 ? 0.9 : 0),
                    List.of(new MetricPoint(55, 26), new MetricPoint(65, 26)), 5);
            assertTrue(build(fixture).isEmpty());
        }
        Fixture fixture = analytic(5, 0, false, 0, 1, true, false);
        var profile = fixture.profiles().get(1);
        var samples = new ArrayList<>(profile.samples());
        samples.set(16, new ProbabilisticProfile.Sample(0, Double.NaN, false));
        var invalid = new ProbabilisticProfile(profile.profileIndex(), profile.chainageMeters(),
                profile.anchor(), profile.normalUnit(), -8, 8, 1, true, profile.noiseFloor(),
                samples, profile.modes(), List.of(), OptionalDouble.empty(), profile.orientationSupport());
        var profiles = new ArrayList<>(fixture.profiles());
        profiles.set(1, invalid);
        assertTrue(build(new Fixture(List.copyOf(profiles), fixture.details())).isEmpty());
    }

    @Test
    void allFiftyOneSparseThreeStrandRowsProduceNoProposal() {
        Fixture fixture = rasterFixture((x, y) -> {
            double value = 0.02;
            for (int strand = 0; strand < 3; strand++) {
                double phase = 2 * Math.PI * (x - 10) / 24 + strand * 2 * Math.PI / 3;
                value += 0.06 * gaussian(y - 30 - 2.5 * Math.sin(phase), 0.65);
            }
            return value;
        }, List.of(new MetricPoint(10, 30), new MetricPoint(110, 30)), 2, 7.01);
        assertEquals(51, fixture.profiles().size());
        assertTrue(fixture.details().stream().anyMatch(detail -> !detail.suppressedModes().isEmpty()));
        assertTrue(fixture.details().stream().allMatch(detail -> detail.rawPeaks().size() >= 2),
                "every original exterior row must retain its unfiltered competing-peak census");
        for (int index = 1; index < 50; index++) {
            assertTrue(build(new Fixture(fixture.profiles().subList(index - 1, index + 2),
                    fixture.details().subList(index - 1, index + 2))).isEmpty(), "interior=" + index);
        }
        assertTrue(build(fixture).isEmpty());
    }

    @Test
    void alternatingFlecksAcrossSamplingPhasesCannotBecomeProposals() {
        for (double phase : List.of(0.0, 0.25, 0.5, 0.75)) {
            Fixture fixture = rasterFixture((x, y) -> ((int) y % 2 == 0 ? 0.42 : 0.40),
                    List.of(new MetricPoint(10, 30 + phase), new MetricPoint(110, 30 + phase)), 2);
            assertTrue(build(fixture).isEmpty(), "phase=" + phase);
        }
    }

    @Test
    void cancellationAndCallerWorkRefusalReleaseOwnedPartialOutputs() {
        Fixture fixture = analytic(5, 0, false, 0, 1, true, false);
        var count = new AtomicInteger();
        build(fixture, null, () -> { count.incrementAndGet(); return false; }, ignored -> { });
        for (int stop : List.of(1, count.get() - 1, count.get())) {
            var ledger = AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            var visits = new AtomicInteger();
            assertThrows(CancellationException.class, () -> build(fixture, owner,
                    () -> visits.incrementAndGet() >= stop, ignored -> { }));
            assertEquals(0, ledger.currentBytes());
            owner.close();
        }
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var charges = new AtomicInteger();
        assertThrows(WorkRefusal.class, () -> build(fixture, owner, CancellationProbe.NONE, units -> {
            assertEquals(1L, units);
            if (charges.incrementAndGet() >= count.get()) throw new WorkRefusal();
        }));
        assertEquals(0, ledger.currentBytes());
        owner.close();
    }

    @Test
    void ownedResultChargesOnlyNewDescriptorAndImmutableListAndReleasesOnMemoryRefusal() {
        Fixture fixture = analytic(5, 0, false, 0, 1, true, false);
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var result = build(fixture, owner, CancellationProbe.NONE, ignored -> { });
        assertEquals(1, result.size());
        long retained = ledger.currentBytes();
        assertEquals(AttemptMemoryLedger.objectBytes(48)
                + AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(1),
                retained, "inputs are borrowed, not allocated again");
        long peak = ledger.peakBytes();
        assertTrue(peak > retained);
        owner.close();
        assertEquals(0, ledger.currentBytes());
        var tiny = new AttemptMemoryLedger(peak - 1);
        var tinyOwner = tiny.rootOwner();
        assertThrows(ProbabilisticInference.MemoryLimit.class, () ->
                build(fixture, tinyOwner, CancellationProbe.NONE, ignored -> { }));
        assertEquals(0, tiny.currentBytes());
        tinyOwner.close();
    }

    private static List<ProbabilisticRmsCorroborationProposals.Proposal> build(Fixture fixture) {
        return build(fixture, null, CancellationProbe.NONE, ignored -> { });
    }

    private static List<ProbabilisticRmsCorroborationProposals.Proposal> build(Fixture fixture,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation,
            java.util.function.LongConsumer charge) {
        return ProbabilisticRmsCorroborationProposals.build(fixture.profiles(), fixture.details(),
                cancellation, owner, charge);
    }

    private static Fixture analytic(double spacing, double angle, boolean reversed, double core,
            double pitch, boolean knownPitch, boolean fixed) {
        Fixture original = rasterFixture((x, y) -> Math.min(1.0, 0.02
                + 0.08 * gaussian(y - 26, 0.9) + (x == 60 && y == 28 ? 0.9 : 0.0)),
                List.of(new MetricPoint(55, 26), new MetricPoint(65, 26)), 5);
        var details = new ArrayList<>(original.details());
        var middle = details.get(1);
        var suppression = middle.suppressedModes().stream()
                .filter(value -> Math.abs(value.mode().centerMeters()) < 0.5).findFirst().orElseThrow();
        details.set(1, new LocalScalarProfileExtractor.DetailedResult(middle.baseline(),
                List.of(shifted(suppression, core)), middle.rawPeaks()));
        var profiles = new ArrayList<ProbabilisticProfile>();
        double bearing = ImageOrientationSupport.normalize(angle);
        var orientation = new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                List.of(new ImageOrientationSupport.AngularMode(bearing, bearing, bearing, 1)), 1);
        for (int i = 0; i < 3; i++) {
            double x = (reversed ? 2 - i : i) * spacing;
            var profile = original.profiles().get(i);
            var modes = new ArrayList<ProbabilisticProfile.Mode>();
            if (i != 1) modes.add(mode(profile.modes().get(0), "exterior-" + i, 0, false, orientation));
            profiles.add(new ProbabilisticProfile(i, i * spacing,
                    new MetricPoint(100 + x * Math.cos(angle), -40 + x * Math.sin(angle)),
                    new MetricPoint(-Math.sin(angle), Math.cos(angle)), -8, 8, pitch, knownPitch,
                    profile.noiseFloor(), profile.samples(), modes, profile.censoredModes(),
                    i == 1 && fixed ? OptionalDouble.of(0) : OptionalDouble.empty(), orientation));
        }
        return new Fixture(List.copyOf(profiles), List.copyOf(details));
    }

    private static LocalScalarProfileExtractor.SuppressedMode shifted(
            LocalScalarProfileExtractor.SuppressedMode source, double shift) {
        var mode = source.mode();
        var moved = new LocalScalarProfileExtractor.Mode(mode.coreMinimumMeters() + shift,
                mode.coreMaximumMeters() + shift, mode.localizationSigmaMeters(), mode.existenceConfidence(),
                mode.localizationConfidence(), mode.peakOffsetsMeters(), mode.nestedCenterOffsetsMeters(),
                mode.scalarAmplitudeReliability());
        return new LocalScalarProfileExtractor.SuppressedMode(moved, source.firstSampleIndex(),
                source.lastSampleIndex(), source.rawPeakOffsetMeters(), source.rawPeakValue(),
                source.localBackground(), source.prominence(), source.rmsUncertainty(),
                source.filterUncertainty(), source.fractionalThreshold(), source.reason());
    }

    private static ProbabilisticProfile.Mode mode(ProbabilisticProfile.Mode source, String id,
            double center, boolean parent, ImageOrientationSupport orientation) {
        return new ProbabilisticProfile.Mode(id, source.evidenceLineage(), center - 0.25,
                center + 0.25, source.localizationSigmaMeters(), source.existenceConfidence(),
                source.localizationConfidence(), List.of(center), List.of(center, center, center),
                parent, orientation, source.scalarAmplitudeReliability(), source.branchCoherence(),
                source.positionalReliability());
    }

    private static Fixture withModes(Fixture fixture, int index, List<ProbabilisticProfile.Mode> modes) {
        var profiles = new ArrayList<>(fixture.profiles());
        profiles.set(index, copy(profiles.get(index), modes, profiles.get(index).exactAnchorOffsetMeters()));
        return new Fixture(List.copyOf(profiles), fixture.details());
    }

    private static ProbabilisticProfile copy(ProbabilisticProfile source,
            List<ProbabilisticProfile.Mode> modes, OptionalDouble fixed) {
        return new ProbabilisticProfile(source.profileIndex(), source.chainageMeters(), source.anchor(),
                source.normalUnit(), source.minimumOffsetMeters(), source.maximumOffsetMeters(),
                source.sourcePitchMeters(), source.nativePitchKnown(), source.noiseFloor(), source.samples(),
                modes, source.censoredModes(), fixed, source.orientationSupport());
    }

    private static Fixture withDetail(Fixture fixture, int index, LocalScalarProfileExtractor.DetailedResult detail) {
        var details = new ArrayList<>(fixture.details());
        details.set(index, detail);
        return new Fixture(fixture.profiles(), List.copyOf(details));
    }

    private static Fixture rasterFixture(DoubleBinaryOperator intensity, List<MetricPoint> route, double step) {
        return rasterFixture(intensity, route, step, 8.0);
    }

    private static Fixture rasterFixture(DoubleBinaryOperator intensity, List<MetricPoint> route,
            double step, double halfWidth) {
        int width = 121, height = 61;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            values[y * width + x] = intensity.applyAsDouble(x, y);
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "rms-prepass",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(width, height, values, valid, lineage);
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        var transform = new RasterMetricTransform("rms-prepass",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0, 0), 1, 0, 0, 1, 1);
        var region = MetricRegion.rectangle(0, 0, 120, 60);
        var evidence = new EvidenceSnapshot("rms-prepass", frame, transform,
                EvidenceResolution.nativeSource(1, 1), region, region, Map.of("scalar", field), "rms-prepass");
        var profiles = new ProbabilisticProfileFactory().create(route, step, halfWidth, false, evidence, field,
                EvidenceModelParameters.defaults(), CancellationProbe.NONE, false);
        var details = profiles.stream().map(profile -> new LocalScalarProfileExtractor().extractDetailed(
                profile.samples().stream().map(sample -> new LocalScalarProfileExtractor.Sample(
                        sample.offsetMeters(), sample.intensity(), sample.valid())).toList(),
                profile.sourcePitchMeters(), EvidenceModelParameters.defaults().localization())).toList();
        return new Fixture(profiles, details);
    }

    private static double gaussian(double residual, double sigma) {
        return StrictMath.exp(-0.5 * residual * residual / (sigma * sigma));
    }

    private record Fixture(List<ProbabilisticProfile> profiles,
            List<LocalScalarProfileExtractor.DetailedResult> details) { }
    private static final class WorkRefusal extends RuntimeException { }
}
