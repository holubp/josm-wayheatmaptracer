package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** R02: persistent clipped inequality is useful; a brief edge island cannot own a position. */
class V022CensoredDirectionContinuityTest {
    private static final double HALF_WIDTH = 7.01;

    @ParameterizedTest
    @CsvSource({"0.08,false,2", "0.8,false,2", "0.08,true,2", "0.8,true,2", "0.08,false,1"})
    void persistentActualClippedRidgeRetainsDirectionalEvidenceWithoutMeasuredCenter(
            double amplitude, boolean reversed, double step) {
        var profiles = profiles(scene(amplitude, false), reversed, step);
        var profile = profiles.get(profiles.size() / 2);
        assertTrue(profile.modes().isEmpty(), "the real ridge center is outside the decision window");
        assertFalse(profile.censoredModes().isEmpty());
        assertTrue(profile.censoredModes().stream().anyMatch(ProbabilisticProfile.CensoredMode::gradientSupported));
        var evaluated = evaluate(profile);
        int edge = nearest(evaluated, reversed ? HALF_WIDTH : -HALF_WIDTH);
        int middle = nearest(evaluated, 0);
        assertTrue(evaluated.unaryCost(edge) + .01 < evaluated.unaryCost(middle),
                "a persistent strong or faint clipped ridge retains a soft inequality");
        assertEquals(ObservationOwnership.CORE_CENSORED, evaluated.ownership());
        assertTrue(evaluated.entirelyMissing(), "censored signal never grants a measured center");
        assertTrue(evaluated.orientationSupport(edge).modes().isEmpty());
    }

    @Test
    void obliquePersistentClipHasObservedScalarLinksAndNoFabricatedCenter() {
        double angle = .4;
        var evidence = scene(.08, false, false, angle);
        var rows = profiles(evidence, false, 2, angle);
        var interval = new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,
                evidence.fields().get("scalar"), EvidenceModelParameters.defaults().localization(), null);
        var left = rows.get(15); var right = rows.get(16);
        assertTrue(left.modes().isEmpty() && right.modes().isEmpty());
        assertTrue(interval.observedCensoredBand(left, left.censoredModes().get(0),
                right, right.censoredModes().get(0), CancellationProbe.NONE));
    }

    @Test
    void sameSideClippedEndpointsCannotBridgeAnActualDarkConnector() {
        var evidence = scene(.8, false, true, 0);
        var rows = profiles(evidence, false, 2);
        var left = rows.get(16); var right = rows.get(17);
        assertFalse(left.censoredModes().isEmpty());
        assertFalse(right.censoredModes().isEmpty());
        assertEquals(left.censoredModes().get(0).side(), right.censoredModes().get(0).side());
        var interval = new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,
                evidence.fields().get("scalar"), EvidenceModelParameters.defaults().localization(), null);
        assertFalse(interval.observedCensoredBand(left, left.censoredModes().get(0),
                right, right.censoredModes().get(0), CancellationProbe.NONE),
                "a below-threshold raster strip between equal-side endpoints breaks continuity");
    }

    @Test
    void anisotropicRasterCannotHideAPixelWideDarkConnectorBehindRepresentativePitch() {
        var evidence = anisotropicScene();
        var rows = profiles(evidence,false,2);
        var left = rows.get(16); var right = rows.get(17);
        assertFalse(left.censoredModes().isEmpty());
        assertFalse(right.censoredModes().isEmpty());
        var interval = new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,
                evidence.fields().get("scalar"),EvidenceModelParameters.defaults().localization(),null);
        assertFalse(interval.observedCensoredBand(left,left.censoredModes().get(0),
                right,right.censoredModes().get(0),CancellationProbe.NONE),
                "the .05m horizontal raster axis must govern sampling, not the 1m representative pitch");
    }

    @Test void foreignBandAndInvalidConnectorNeverGrantScalarContinuation() {
        var evidence = scene(.8,false);
        var rows = profiles(evidence,false,2);
        var left = rows.get(16); var right = rows.get(17);
        var band = left.censoredModes().get(0);
        var foreign = duplicateBand(band,"foreign");
        var field = evidence.fields().get("scalar");
        var interval = new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,field,
                EvidenceModelParameters.defaults().localization(),null);
        assertTrue(interval.observedCensoredBand(left,band,right,right.censoredModes().get(0),CancellationProbe.NONE));
        assertFalse(interval.observedCensoredBand(left,foreign,right,right.censoredModes().get(0),CancellationProbe.NONE));
        var valid = field.copiedValidity();
        int stripX = 212; // x=33m: strictly between the observed x=32m and x=34m endpoints.
        for (int y = 0; y < field.height(); y++) valid[y*field.width()+stripX] = false;
        var broken = new ScalarEvidenceField(field.width(),field.height(),field.copiedValues(),valid,field.lineage());
        var invalid = new EvidenceSnapshot(evidence.snapshotId(),evidence.coordinateFrame(),evidence.transform(),
                evidence.resolution(),evidence.decisionRegion(),evidence.evidenceRegion(),Map.of("scalar",broken),
                evidence.sourceIdentity());
        var invalidInterval = new ProbabilisticProfileFactory.RasterIntervalEvidence(invalid,broken,
                EvidenceModelParameters.defaults().localization(),null);
        assertFalse(invalidInterval.observedCensoredBand(left,band,right,right.censoredModes().get(0),CancellationProbe.NONE));
    }

    @Test void actualRasterAmbiguityCannotTurnTwoSuccessfulLinksIntoUniqueDirection() {
        var evidence = scene(.8,false);
        var rows = new ArrayList<>(profiles(evidence,false,2));
        var p = rows.get(16); var b = p.censoredModes().get(0);
        rows.set(16,new ProbabilisticProfile(p.profileIndex(),p.chainageMeters(),p.anchor(),p.normalUnit(),
                p.minimumOffsetMeters(),p.maximumOffsetMeters(),p.sourcePitchMeters(),p.nativePitchKnown(),
                p.noiseFloor(),p.samples(),p.modes(),List.of(b,duplicateBand(b,"ambiguous")),
                p.exactAnchorOffsetMeters(),p.orientationSupport()));
        var interval = new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,evidence.fields().get("scalar"),
                EvidenceModelParameters.defaults().localization(),null);
        assertTrue(interval.observedCensoredBand(rows.get(15),rows.get(15).censoredModes().get(0),
                rows.get(16),b,CancellationProbe.NONE));
        assertTrue(interval.observedCensoredBand(rows.get(15),rows.get(15).censoredModes().get(0),
                rows.get(16),rows.get(16).censoredModes().get(1),CancellationProbe.NONE));
        var result = CensoredDirectionReliability.confidence(rows,interval::observedCensoredBand,
                CancellationProbe.NONE,interval,null);
        assertEquals(0,result[16][0]); assertEquals(0,result[16][1]);
        assertEquals(0,result[15][0]); assertEquals(0,result[17][0]);
    }

    @Test void actualScalarCallbackRefusalAndMidQueryCancellationDoNotLeakScratch() throws Exception {
        var evidence = scene(.8,false); var rows = profiles(evidence,false,2);
        var l = rows.get(16); var r = rows.get(17);
        for (String counter : List.of("samplingUnits","featureUnits")) {
            var ledger = AttemptMemoryLedger.production();
            try (var owner = ledger.rootOwner()) {
                var interval = new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,evidence.fields().get("scalar"),
                        EvidenceModelParameters.defaults().localization(),owner);
                var f = interval.getClass().getDeclaredField(counter); f.setAccessible(true);
                f.setLong(interval,counter.equals("samplingUnits") ? 2_000_000 : 8_000_000);
                assertThrows(LongitudinalModeReliability.ResourceLimitException.class,()->
                        interval.observedCensoredBand(l,l.censoredModes().get(0),r,r.censoredModes().get(0),CancellationProbe.NONE));
                assertEquals(0,ledger.currentBytes()); assertEquals(0,ledger.peakBytes());
            }
        }
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            var interval = new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,evidence.fields().get("scalar"),
                    EvidenceModelParameters.defaults().localization(),owner);
            // Prime retained membership indexes so only query scratch is measured afterward.
            assertTrue(interval.observedCensoredBand(l,l.censoredModes().get(0),r,r.censoredModes().get(0),CancellationProbe.NONE));
            long retained = ledger.currentBytes();
            long queries = counter(interval,"scalarQueries");
            assertThrows(CancellationException.class,()->interval.observedCensoredBand(l,l.censoredModes().get(0),
                    r,r.censoredModes().get(0),()->{
                        if(counter(interval,"scalarQueries") > queries) throw new CancellationException(); return false;
                    }));
            assertTrue(counter(interval,"scalarQueries") > queries,"cancellation occurred inside the scalar loop");
            assertEquals(retained,ledger.currentBytes());
        }
        assertEquals(0,ledger.currentBytes());
    }

    private static long counter(Object target,String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.getLong(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @ParameterizedTest
    @CsvSource({"true","false"})
    void lateFactoryCancellationDoesNotPublishAnOrdinaryReliabilityGraphToCaller(boolean clipped) {
        var evidence = clipped ? scene(.8,true) : V022LocalizationEvidenceTest.snapshot((x,y)->
                .02+.6*StrictMath.exp(-.5*Math.pow((y-26)/.9,2)));
        var source = clipped ? List.of(new MetricPoint(0,0),new MetricPoint(64,0))
                : List.of(new MetricPoint(10,26),new MetricPoint(110,26));
        var ledger = AttemptMemoryLedger.production(); var factory = new ProbabilisticProfileFactory();
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.begin();
        try (var owner = ledger.rootOwner()) {
            assertThrows(CancellationException.class,()->factory.create(source,factory.profileChainage(source,2),
                    HALF_WIDTH,false,evidence,evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),
                    ()->org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.snapshot()
                        .containsKey("reliability.windowMeters"),true,owner));
            assertTrue(org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.snapshot()
                    .containsKey("reliability.windowMeters"),"cancellation follows completed ordinary reliability");
            assertEquals(0,ledger.currentBytes(),"failed factory must not publish partial output into the still-open caller");
        } finally { org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.end(); }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void productionOwnedCensoredOutputRetainsExistenceAndReleasesItsGraphOnClose() {
        var evidence = scene(.8,true); var factory = new ProbabilisticProfileFactory();
        var source = List.of(new MetricPoint(0,0),new MetricPoint(64,0));
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            var rows = factory.create(source,factory.profileChainage(source,2),HALF_WIDTH,false,evidence,
                    evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),CancellationProbe.NONE,true,owner);
            var p = rows.get(16); var b = p.censoredModes().get(0);
            assertTrue(b.existenceConfidence() > 0); assertTrue(b.gradientSupported());
            assertEquals(0,b.directionalReliability()); assertEquals(.25*b.existenceConfidence(),b.strength());
            assertTrue(ledger.currentBytes() > 0);
            var lattice = new ProbabilisticStateBuilder().build(p,96).lattice().orElseThrow();
            var priors = ProbabilisticObservationModel.effectiveComponentPriors(p,lattice.components());
            assertEquals(1,Arrays.stream(priors).sum(),1e-12);
            for (int i = 0; i < priors.length; i++) assertEquals(
                    lattice.components().get(i).kind() == ObservationComponent.Kind.MISSING ? 1 : 0,priors[i],1e-12);
        }
        assertEquals(0,ledger.currentBytes());
    }

    private static ProbabilisticProfile.CensoredMode duplicateBand(ProbabilisticProfile.CensoredMode b,String id) {
        return new ProbabilisticProfile.CensoredMode(id,b.evidenceLineage(),b.side(),b.observedBoundaryMeters(),
                b.existenceConfidence(),b.gradientSupported(),b.inwardExtentMeasured(),b.inwardSupportOffsetMeters(),
                b.shoulderThreshold());
    }

    @Test
    void factoryRetainsMeasuredCensoredBandExtentAndOriginalThreshold() throws Exception {
        var evidence = scene(.8, false);
        var profile = profiles(evidence, false, 2).get(16);
        var samples = profile.samples().stream().map(s ->
                new org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.Sample(
                        s.offsetMeters(), s.intensity(), s.valid())).toList();
        var original = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor()
                .extract(samples, profile.sourcePitchMeters(), EvidenceModelParameters.defaults().localization())
                .censoredModes().get(0);
        var retained = profile.censoredModes().get(0);
        for (var type : List.of(original.getClass(), retained.getClass())) {
            assertTrue(Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals("shoulderThreshold")),
                    "the already computed scalar threshold must survive adaptation");
        }
        double threshold = (double) original.getClass().getMethod("shoulderThreshold").invoke(original);
        assertTrue(Double.isFinite(threshold) && threshold > profile.noiseFloor());
        assertEquals(threshold, (double) retained.getClass().getMethod("shoulderThreshold").invoke(retained));
        assertEquals(original.inwardExtentMeasured(),
                (boolean) retained.getClass().getMethod("inwardExtentMeasured").invoke(retained));
        assertEquals(original.inwardSupportOffsetMeters(),
                (double) retained.getClass().getMethod("inwardSupportOffsetMeters").invoke(retained));
        for (var s : samples) if (s.offsetMeters() >= Math.min(original.boundaryOffsetMeters(), original.inwardSupportOffsetMeters())
                && s.offsetMeters() <= Math.max(original.boundaryOffsetMeters(), original.inwardSupportOffsetMeters()))
            assertTrue(s.valid() && s.intensity() >= threshold, "only factual above-threshold scalar support is retained");
    }

    @Test
    void shortActualEdgeIslandCannotSteerOtherwiseUnlocalizedProfiles() {
        var profiles = profiles(scene(.8, true), false, 2);
        var observed = profiles.stream().filter(p -> !p.censoredModes().isEmpty()).toList();
        assertFalse(observed.isEmpty(), "the island is observed rather than erased from diagnostics");
        assertTrue(observed.get(observed.size() - 1).chainageMeters()
                - observed.get(0).chainageMeters() < 8,
                "the measured island cannot have four metres of evidence on both sides");
        var profile = profiles.get(profiles.size() / 2);
        assertTrue(profile.modes().isEmpty());
        assertTrue(profile.censoredModes().stream().anyMatch(ProbabilisticProfile.CensoredMode::gradientSupported));
        var evaluated = evaluate(profile);
        assertEquals(evaluated.unaryCost(nearest(evaluated, 0)),
                evaluated.unaryCost(nearest(evaluated, -HALF_WIDTH)), 1e-12,
                "a brief clipped island retains existence but no directional positional force");
    }

    private static InferenceProfile evaluate(ProbabilisticProfile profile) {
        var lattice = new ProbabilisticStateBuilder().build(profile, 96).lattice().orElseThrow();
        return new ProbabilisticObservationModel().evaluate(profile, lattice, EvidenceModelParameters.defaults());
    }

    private static int nearest(InferenceProfile profile, double offset) {
        int selected = 0;
        for (int i = 1; i < profile.cells().size(); i++)
            if (Math.abs(profile.cells().get(i).offsetMeters() - offset)
                    < Math.abs(profile.cells().get(selected).offsetMeters() - offset)) selected = i;
        return selected;
    }

    private static List<ProbabilisticProfile> profiles(EvidenceSnapshot evidence, boolean reversed, double step) {
        return profiles(evidence, reversed, step, 0);
    }
    private static List<ProbabilisticProfile> profiles(EvidenceSnapshot evidence, boolean reversed, double step, double angle) {
        var end = rotate(64, 0, angle);
        var source = reversed ? List.of(end, new MetricPoint(0, 0))
                : List.of(new MetricPoint(0, 0), end);
        return new ProbabilisticProfileFactory().create(source, step, HALF_WIDTH, false, evidence,
                evidence.fields().get("scalar"), EvidenceModelParameters.defaults(), CancellationProbe.NONE, true);
    }

    private static EvidenceSnapshot scene(double amplitude, boolean shortIsland) {
        return scene(amplitude, shortIsland, false, 0);
    }
    private static EvidenceSnapshot scene(double amplitude, boolean shortIsland, boolean darkConnector, double angle) {
        int width = 417, height = 417;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double px = -20 + .25 * x, py = -20 + .25 * y;
            double along = px * StrictMath.cos(angle) + py * StrictMath.sin(angle);
            double lateral = -px * StrictMath.sin(angle) + py * StrictMath.cos(angle);
            double longitudinal = shortIsland ? Math.max(0, 1 - Math.abs(along - 32) / 3) : 1;
            if (darkConnector && Math.abs(along-33) <= .125) longitudinal = 0;
            values[y * width + x] = .02 + amplitude * longitudinal
                    * StrictMath.exp(-.5 * Math.pow((lateral + 7.6) / .9, 2));
        }
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "censored-direction-continuity",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        var transform = new RasterMetricTransform("censored-direction-continuity",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(-20, -20), .25, 0, 0, .25, 1);
        return new EvidenceSnapshot("censored-direction-continuity", frame, transform,
                EvidenceResolution.nativeSource(1, .25), new MetricRegion(List.of(List.of(
                    rotate(-10,-HALF_WIDTH,angle),rotate(74,-HALF_WIDTH,angle),
                    rotate(74,HALF_WIDTH,angle),rotate(-10,HALF_WIDTH,angle)))),
                MetricRegion.rectangle(-19, -19, 83, 83),
                Map.of("scalar", new ScalarEvidenceField(width, height, values, valid, lineage)),
                "censored-direction-continuity");
    }
    private static MetricPoint rotate(double x, double y, double angle) {
        return new MetricPoint(x*StrictMath.cos(angle)-y*StrictMath.sin(angle),
                x*StrictMath.sin(angle)+y*StrictMath.cos(angle));
    }

    private static EvidenceSnapshot anisotropicScene() {
        int width=2081,height=105;
        double[] values=new double[width*height];boolean[] valid=new boolean[values.length];
        Arrays.fill(valid,true);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            double along=-20+.05*x,lateral=-20+y;
            double amplitude=Math.abs(along-32.1)<.0125 ? 0 : .8;
            values[y*width+x]=.02+amplitude*StrictMath.exp(-.5*Math.pow((lateral+7.6)/.9,2));
        }
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"anisotropic-censor",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        return new EvidenceSnapshot("anisotropic-censor",
                LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),
                    new GeographicPoint(-1,-1),new GeographicPoint(1,1)),
                RasterMetricTransform.metricGrid(new MetricPoint(-20,-20),.05,0,0,1),
                EvidenceResolution.nativeSource(1,1),MetricRegion.rectangle(-10,-HALF_WIDTH,74,HALF_WIDTH),
                MetricRegion.rectangle(-19,-19,83,83),
                Map.of("scalar",new ScalarEvidenceField(width,height,values,valid,lineage)),"anisotropic-censor");
    }
}
