package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.StrictScalarSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Actual raster/callback controls; context may refuse but cannot replace original evidence. */
class V022RieContextVetoIntegrationTest {
    private static final MetricPoint QUERY = new MetricPoint(8, 0);
    private static final MetricPoint TANGENT = new MetricPoint(1, 0);

    @Test
    void actualClippedBroadLegIsZeroWhileBothNarrowBackgroundControlsRetainExactFactor() throws Exception {
        var broad = fixture(Double.NaN, false, false);
        assertEquals(0.0, leg(broad, false), 0.0);
        assertTrue(leg(broad, true) > 0.0, "the unaffected x=16..32 leg remains measured");
        for (double background : List.of(0.13, 0.10)) {
            var narrow = fixture(background, false, false);
            assertEquals(decisionSamples(broad), decisionSamples(narrow));
            assertEquals(raw(broad), raw(narrow), "endpoint observations/orientations remain unchanged");
            assertEquals(0.12497107299891838, leg(narrow, false), 0.0);
        }
    }

    @Test
    void actualSourceVetoAndPositiveControlsSurviveRotationAndReversedTraversal() throws Exception {
        for (double angle : List.of(0.0, 0.7)) {
            for (double background : List.of(Double.NaN, 0.13, 0.10)) {
                var evidence = rotate(fixture(background, false, false), angle);
                var source = List.of(rotatePoint(new MetricPoint(0, 0), angle),
                        rotatePoint(new MetricPoint(64, 0), angle));
                for (boolean reversed : List.of(false, true)) {
                    var route = reversed ? List.of(source.get(1), source.get(0)) : source;
                    var profiles = new ProbabilisticProfileFactory().create(route, 16, 7, false,
                            evidence, evidence.fields().get("scalar"), EvidenceModelParameters.defaults(),
                            CancellationProbe.NONE, false);
                    int left = reversed ? 3 : 0;
                    double factor = callback(evidence, null).support(profiles.get(left),
                            profiles.get(left).modes().get(0), profiles.get(left + 1),
                            profiles.get(left + 1).modes().get(0), CancellationProbe.NONE);
                    if (Double.isNaN(background)) assertEquals(0.0, factor, 0.0);
                    else assertEquals(0.12497107299891838, factor, 1e-10);
                }
            }
        }
    }

    @Test
    void completeNearBoundaryCoreAndBrighterSeparateContextSiblingKeepOriginalEvidence() throws Exception {
        assertEquals(0.12497107299891838, leg(fixture(0.13, true, false), false), 0.0);
        var near = fixture(0.13, false, false);
        var decision = new MetricRegion(List.of(
                MetricRegion.rectangle(-10, -10, 7, 10).polygons().get(0),
                MetricRegion.rectangle(7, -0.25, 9, 0.375).polygons().get(0),
                MetricRegion.rectangle(9, -10, 74, 10).polygons().get(0)));
        assertNotNull(local(callback(withDecision(near, decision), null), CancellationProbe.NONE),
                "an actual complete high core near the boundary must survive");
    }

    @Test
    void genuineRmsSuppressedEndpointIsSubjectToTheSameNegativeContextProof() throws Exception {
        var evidence = fixture(Double.NaN, false, true);
        var details = new LocalScalarProfileExtractor().extractDetailed(decisionSamples(evidence), 0.25,
                EvidenceModelParameters.defaults().localization());
        var suppression = details.suppressedModes().stream().filter(value ->
                value.mode().coreMinimumMeters() <= 0 && value.mode().coreMaximumMeters() >= 0)
                .findFirst().orElseThrow(() -> new AssertionError("fixture must produce actual RMS_ONLY: " + details));
        assertTrue(details.baseline().modes().stream().noneMatch(mode ->
                mode.coreMinimumMeters() <= 0 && mode.coreMaximumMeters() >= 0));
        var callback = callback(evidence, null);
        var method = callback.getClass().getDeclaredMethod("localizedSupport", MetricPoint.class,
                MetricPoint.class, double.class, CancellationProbe.class,
                LocalScalarProfileExtractor.SuppressedMode.class, boolean.class);
        method.setAccessible(true);
        assertNull(invoke(method, callback, QUERY, TANGENT, 0.25, CancellationProbe.NONE, suppression, true));
    }

    @Test
    void permissionHoleBetweenValidLatticePointsInvalidatesOriginalRawClosure() throws Exception {
        var evidence = base();
        var decision = new MetricRegion(List.of(
                MetricRegion.rectangle(-10, -10, 74, 0.03).polygons().get(0),
                MetricRegion.rectangle(-10, 0.04, 74, 10).polygons().get(0)));
        var hole = withDecision(evidence, decision);
        assertTrue(decisionSamples(hole).stream().allMatch(LocalScalarProfileExtractor.Sample::valid));
        assertFalse(decision.containsSegment(QUERY, new MetricPoint(8, 0.125)));
        assertNull(local(callback(hole, null), CancellationProbe.NONE));
    }

    @Test
    void subcellSourceHoleBetweenValidLatticePointsCannotCertifyOriginalClosure() throws Exception {
        int width = 513, height = 641;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int row = 0; row < height; row++) {
            double y = -10 + row / 32.0;
            for (int col = 0; col < width; col++) {
                values[row * width + col] = 0.02 + 0.12 * Math.exp(-0.5 * y * y / (0.75 * 0.75));
                if (row == 322) valid[row * width + col] = false; // y=0.0625, between 0 and 0.125.
            }
        }
        var original = base();
        var field = new ScalarEvidenceField(width, height, values, valid, original.fields().get("scalar").lineage());
        var transform = new RasterMetricTransform("context-subcell",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0, -10), 0.25, 0, 0, 0.25, 8);
        var region = MetricRegion.rectangle(0, -8, 16, 8);
        var evidence = new EvidenceSnapshot("context-subcell", original.coordinateFrame(), transform,
                EvidenceResolution.nativeSource(0.25, 1.0 / 32.0), region,
                MetricRegion.rectangle(0, -10, 16, 10), Map.of("scalar", field), "context-subcell");
        assertTrue(decisionSamples(evidence).stream().allMatch(LocalScalarProfileExtractor.Sample::valid));
        assertFalse(ProbabilisticProfileFactory.supportsEveryCrossedInterpolationCell(field, transform,
                QUERY, new MetricPoint(8, 0.125), CancellationProbe.NONE, ignored -> { }));
        assertNull(local(callback(evidence, null), CancellationProbe.NONE));
    }

    @Test
    void unrelatedMissingCapturedSupportIsUnknownRatherThanAContextReplacement() throws Exception {
        var evidence = fixture(0.13, false, false);
        var field = evidence.fields().get("scalar");
        var valid = field.copiedValidity();
        int col = (int) ((8 + 20) / 0.25), row = (int) ((6 + 20) / 0.25);
        valid[row * field.width() + col] = false;
        var masked = new ScalarEvidenceField(field.width(), field.height(), field.copiedValues(), valid, field.lineage());
        assertNotNull(local(callback(withField(evidence, masked), null), CancellationProbe.NONE));
    }

    @Test
    void bothSharedCapsRefuseLateContextWorkAndReleaseAllOwnedQueryState() throws Exception {
        var evidence = fixture(0.13, false, false);
        var successful = callback(evidence, null);
        assertNotNull(local(successful, CancellationProbe.NONE));
        long features = counter(successful, "featureUnits");
        for (String name : List.of("samplingUnits", "featureUnits")) {
            var ledger = AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            var callback = callback(evidence, owner);
            setCounter(callback, name, name.equals("samplingUnits") ? 2_000_000 - 129
                    : 8_000_000 - features + 1);
            assertThrows(LongitudinalModeReliability.ResourceLimitException.class,
                    () -> local(callback, CancellationProbe.NONE));
            assertTrue(ledger.peakBytes() > 0);
            assertEquals(indexBytes(evidence), ledger.currentBytes(),
                    "only the immutable attempt indexes survive query refusal");
            owner.close();
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void cancellationAndMemoryRefusalAfterContextSamplingStartsReleaseOwnedState() throws Exception {
        var evidence = fixture(0.13, false, false);
        for (boolean memory : List.of(false, true)) {
            var ledger = AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            var callback = callback(evidence, owner);
            CancellationProbe cancellation = () -> {
                try {
                    if (counter(callback, "scalarQueries") > 129) {
                        if (memory) owner.reserve(AttemptMemoryLedger.MAX_BYTES);
                        return true;
                    }
                    return false;
                } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
            };
            if (memory) assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> local(callback, cancellation));
            else assertThrows(CancellationException.class, () -> local(callback, cancellation));
            assertTrue(counter(callback, "scalarQueries") > 129);
            assertEquals(indexBytes(evidence), ledger.currentBytes(),
                    "failed query scratch closes while attempt indexes remain owned");
            owner.close();
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void normalAndSamplerScratchIsAdmittedAlongsideTheOwnedSampleVector() throws Exception {
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var evidence = fixture(0.13, false, false);
        var callback = callback(evidence, owner);
        long[] atFirstSegmentCheck = { -1 };
        CancellationProbe observer = () -> {
            try {
                if (atFirstSegmentCheck[0] < 0 && counter(callback, "scalarQueries") == 129
                        && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                            frame.getClassName().equals(MetricRegion.class.getName())
                                && frame.getMethodName().equals("containsSegment")))) {
                    atFirstSegmentCheck[0] = ledger.currentBytes();
                }
                return false;
            } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
        };
        assertNotNull(local(callback, observer));
        // Independently inventory the conservative simultaneous roles: first/last normal
        // points plus up to three closing-path points, two transformed RasterPoints,
        // and one OptionalDouble. Scalar vectors store primitive values, not optionals.
        long scratch = 5 * AttemptMemoryLedger.objectBytes(16)
                + 2 * AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.objectBytes(9);
        long samples = AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(129)
                + 129 * AttemptMemoryLedger.objectBytes(24);
        assertTrue(atFirstSegmentCheck[0] >= samples + scratch,
                "scratch must be reserved before the first owned segment traversal");
        assertEquals(indexBytes(evidence), ledger.currentBytes());
        owner.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void exactRegionIdentitySharesOneOwnedIndexAndPreparationOccursOnlyOnce() throws Exception {
        var original=fixture(0.13,false,false);
        var region=MetricRegion.rectangle(-10,-10,74,10);
        for(boolean sameIdentity:List.of(true,false)) {
            var captured=sameIdentity?region:new MetricRegion(region.polygons());
            var evidence=new EvidenceSnapshot(original.snapshotId(),original.coordinateFrame(),
                    original.transform(),original.resolution(),region,captured,original.fields(),
                    original.resampling(),original.sourceIdentity());
            var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
            try {
                var callback=callback(evidence,owner);
                prepareIndexes(callback,CancellationProbe.NONE);
                assertEquals(sameIdentity?11:20,counter(callback,"featureUnits"));
                assertEquals(sameIdentity?88:176,ledger.currentBytes());
                Object decision=index(callback,"decisionYIndex"), context=index(callback,"capturedYIndex");
                if(sameIdentity)assertSame(decision,context);else assertNotSame(decision,context);
                prepareIndexes(callback,CancellationProbe.NONE);
                assertSame(decision,index(callback,"decisionYIndex"));
                assertSame(context,index(callback,"capturedYIndex"));
                assertEquals(sameIdentity?12:21,counter(callback,"featureUnits"));
                assertEquals(sameIdentity?88:176,ledger.currentBytes());
            } finally {owner.close();}
            assertEquals(0,ledger.currentBytes());
        }
    }

    @Test
    void secondIndexMemoryFailureClosesBothUncommittedGraphs() throws Exception {
        var evidence=fixture(0.13,false,false);
        assertEquals(120,indexBytes(evidence.decisionRegion()));
        var ledger=new AttemptMemoryLedger(120);var owner=ledger.rootOwner();
        try {
            var callback=callback(evidence,owner);
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    ()->prepareIndexes(callback,CancellationProbe.NONE));
            assertEquals(120,ledger.peakBytes(),"decision index was actually built before captured refusal");
            assertEquals(0,ledger.currentBytes());
            assertNull(index(callback,"decisionYIndex"));assertNull(index(callback,"capturedYIndex"));
        } finally {owner.close();}
    }

    @Test
    void secondIndexWorkOrCancellationFailureClosesFirstSuccessfulIndex() throws Exception {
        var evidence=fixture(0.13,false,false);
        for(boolean work:List.of(true,false)) {
            var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
            try {
                var callback=callback(evidence,owner);
                if(work) {
                    setCounter(callback,"featureUnits",8_000_000-25);
                    assertThrows(LongitudinalModeReliability.ResourceLimitException.class,
                            ()->prepareIndexes(callback,CancellationProbe.NONE));
                } else {
                    assertThrows(CancellationException.class,()->prepareIndexes(callback,()->{
                        try{return counter(callback,"featureUnits")==25;}
                        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
                    }));
                }
                assertEquals(120,ledger.peakBytes());
                assertEquals(0,ledger.currentBytes());
                assertNull(index(callback,"decisionYIndex"));assertNull(index(callback,"capturedYIndex"));
            } finally {owner.close();}
        }
    }

    @Test
    void knownSamplingRefusalPrecedesIndexWorkAndAllocation() throws Exception {
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try {
            var callback=callback(fixture(0.13,false,false),owner);
            setCounter(callback,"samplingUnits",2_000_000);
            assertThrows(LongitudinalModeReliability.ResourceLimitException.class,
                    ()->local(callback,CancellationProbe.NONE));
            assertEquals(0,counter(callback,"featureUnits"));
            assertEquals(0,counter(callback,"queries"));
            assertEquals(0,ledger.peakBytes());
            assertNull(index(callback,"decisionYIndex"));assertNull(index(callback,"capturedYIndex"));
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }

    private static long indexBytes(MetricRegion region) {
        return AttemptMemoryLedger.objectBytes(24)
                +2L*AttemptMemoryLedger.arrayBytes(region.polygons().size(),Double.BYTES);
    }
    private static long indexBytes(EvidenceSnapshot evidence) {
        return indexBytes(evidence.decisionRegion())+(evidence.decisionRegion()==evidence.evidenceRegion()
                ?0:indexBytes(evidence.evidenceRegion()));
    }
    private static Object index(Object callback,String name) throws ReflectiveOperationException {
        Field field=callback.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(callback);
    }
    private static void prepareIndexes(Object callback,CancellationProbe cancellation) throws Exception {
        Method method=callback.getClass().getDeclaredMethod("prepareYMembership",CancellationProbe.class);
        method.setAccessible(true);invoke(method,callback,cancellation);
    }

    private static EvidenceSnapshot base() throws Exception {
        Method method = V022LocalizedBridgeReliabilityTest.class.getDeclaredMethod("scene", double.class, double.class);
        method.setAccessible(true);
        return (EvidenceSnapshot) method.invoke(null, 1.0, 0.12);
    }

    private static EvidenceSnapshot fixture(double background, boolean sibling, boolean rmsSlope) throws Exception {
        var original = base();
        var field = original.fields().get("scalar");
        double[] values = field.copiedValues();
        for (int row = 0; row < field.height(); row++) for (int col = 0; col < field.width(); col++) {
            double x = -20 + col * 0.25, y = -20 + row * 0.25;
            if (x > 7 && x < 9 && Math.abs(y) > 0.25) {
                if (Double.isFinite(background)) values[row * field.width() + col] = background;
                if (sibling) values[row * field.width() + col] += 0.6 * Math.exp(-0.5 * (y - 4) * (y - 4) / 0.09);
                if (rmsSlope && y == 0.5) values[row * field.width() + col] = 0.13351513626881184;
                if (rmsSlope && y == 0.75) values[row * field.width() + col] = 0.04;
                if (rmsSlope && y == 1) values[row * field.width() + col] = 0.9;
            }
        }
        var polygons = new ArrayList<>(List.of(
                MetricRegion.rectangle(-10, -10, 7, 10).polygons().get(0),
                MetricRegion.rectangle(7, -0.25, 9, rmsSlope ? 0.75 : 0.25).polygons().get(0),
                MetricRegion.rectangle(9, -10, 74, 10).polygons().get(0)));
        return withDecision(withField(original, new ScalarEvidenceField(field.width(), field.height(),
                values, field.copiedValidity(), field.lineage())), new MetricRegion(polygons));
    }

    private static EvidenceSnapshot withDecision(EvidenceSnapshot original, MetricRegion decision) {
        return new EvidenceSnapshot(original.snapshotId(), original.coordinateFrame(), original.transform(),
                original.resolution(), decision, original.evidenceRegion(), original.fields(),
                original.resampling(), original.sourceIdentity());
    }

    private static EvidenceSnapshot withField(EvidenceSnapshot original, ScalarEvidenceField field) {
        return new EvidenceSnapshot(original.snapshotId(), original.coordinateFrame(), original.transform(),
                original.resolution(), original.decisionRegion(), original.evidenceRegion(), Map.of("scalar", field),
                original.resampling(), original.sourceIdentity());
    }

    private static MetricPoint rotatePoint(MetricPoint point, double angle) {
        return new MetricPoint(point.xMeters() * Math.cos(angle) - point.yMeters() * Math.sin(angle),
                point.xMeters() * Math.sin(angle) + point.yMeters() * Math.cos(angle));
    }

    private static MetricRegion rotateRegion(MetricRegion region, double angle) {
        return new MetricRegion(region.polygons().stream().map(polygon ->
                polygon.stream().map(point -> rotatePoint(point, angle)).toList()).toList());
    }

    private static EvidenceSnapshot rotate(EvidenceSnapshot original, double angle) {
        var transform = new RasterMetricTransform("rotated-context",
                original.transform().originKind(), rotatePoint(original.transform().origin(), angle),
                0.25 * Math.cos(angle), 0.25 * Math.sin(angle),
                -0.25 * Math.sin(angle), 0.25 * Math.cos(angle), 1);
        return new EvidenceSnapshot(original.snapshotId(), original.coordinateFrame(), transform,
                original.resolution(), rotateRegion(original.decisionRegion(), angle),
                rotateRegion(original.evidenceRegion(), angle), original.fields(), original.resampling(),
                original.sourceIdentity());
    }

    private static List<LocalScalarProfileExtractor.Sample> decisionSamples(EvidenceSnapshot evidence) {
        var result = new ArrayList<LocalScalarProfileExtractor.Sample>();
        for (int i = 0; i <= 128; i++) {
            double offset = -8 + i * 0.125;
            var sampled = StrictScalarSampler.sample(evidence.fields().get("scalar"), evidence.transform(),
                    evidence.decisionRegion(), new MetricPoint(8, offset));
            result.add(new LocalScalarProfileExtractor.Sample(offset, sampled.orElse(Double.NaN), sampled.isPresent()));
        }
        return result;
    }

    private static List<ProbabilisticProfile> raw(EvidenceSnapshot evidence) {
        return new ProbabilisticProfileFactory().create(List.of(new MetricPoint(0, 0), new MetricPoint(64, 0)),
                16, 7, false, evidence, evidence.fields().get("scalar"), EvidenceModelParameters.defaults(),
                CancellationProbe.NONE, false);
    }

    private static double leg(EvidenceSnapshot evidence, boolean unaffected) {
        var profiles = raw(evidence);
        int left = unaffected ? 1 : 0;
        return callback(evidence, null).support(profiles.get(left), profiles.get(left).modes().get(0),
                profiles.get(left + 1), profiles.get(left + 1).modes().get(0), CancellationProbe.NONE);
    }

    private static ProbabilisticProfileFactory.RasterIntervalEvidence callback(EvidenceSnapshot evidence,
            AttemptMemoryLedger.Owner owner) {
        return new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence, evidence.fields().get("scalar"),
                EvidenceModelParameters.defaults().localization(), owner);
    }

    private static Object local(ProbabilisticProfileFactory.RasterIntervalEvidence callback,
            CancellationProbe cancellation) throws Exception {
        Method method = callback.getClass().getDeclaredMethod("localizedSupport", MetricPoint.class,
                MetricPoint.class, double.class, CancellationProbe.class);
        method.setAccessible(true);
        return invoke(method, callback, QUERY, TANGENT, 0.25, cancellation);
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            if (exception.getCause() instanceof Error error) throw error;
            throw exception;
        }
    }

    private static long counter(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.getLong(target);
    }

    private static void setCounter(Object target, String name, long value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.setLong(target, value);
    }
}
