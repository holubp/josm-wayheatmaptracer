package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.awt.image.BufferedImage;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.Accounted;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

/**
 * Public, synthetic, discovery-only performance harness for Legacy normal policies.
 * The measured interval includes candidate policy planning, owner setup, production discovery, and close.
 * It never calls {@code runVisible}, {@code runManaged}, or {@code refine}.
 *
 * <p>Run one process per fork with {@code --fork 0|1|2 NEW_OUTPUT_DIRECTORY}, or one bounded
 * fixture/API preflight with {@code --preflight NEW_OUTPUT_DIRECTORY}. This file is
 * intentionally stored as an ignored preparation artifact and is not wired into CI.</p>
 */
public final class LegacyWideSecantPerformanceMain {
    private static final int WARMUP_PAIRS = 12;
    private static final int MEASURED_PAIRS = 20;
    private static final int WIDTH = 8400;
    private static final int HEIGHT = 720;
    private static final double MIN_E = -700;
    private static final double MAX_E = 700;
    private static final double MIN_N = -40;
    private static final double MAX_N = 80;
    private static final double VIEW_METERS_PER_PIXEL = 1.0;
    private static final double RASTER_METERS_PER_PIXEL = VIEW_METERS_PER_PIXEL / RenderedHeatmapSampler.RASTER_SCALE;
    private static final double STEP_M = 1200.0 / 14.0;
    private static final int MANAGED_ZOOM = 15;
    private static final int MANAGED_TILE_SIZE = 512;
    private static final double EARTH_RADIUS_M = 6378137.0;
    private static final List<Integer> FIXED = List.of(0, 14);
    private static final Projection PROJECTION = Projections.getProjectionByCode("EPSG:3857");
    private static final String SOURCE_ID = "synthetic-public-legacy-wide-secant-v1";
    private static final String POLICY_HASH = "4f7b1ef87be24be0841b3dd040fe8e15d945af284f1bcf8a162b9840d26bdc7e";
    private static final String SETTINGS_HASH = "9b7f8cb4e0f54d1ba9416a412330a6740ab95c1b38308a437a2fa8ea36be6d8";

    private enum Scene { STRAIGHT, GAUSSIAN_KINK, SUPPORTED_HAIRPIN, PARALLEL_STRANDS }
    private enum SourceKind { VISIBLE, MANAGED }
    private record XY(double x, double y) { }
    private record Fixture(Scene scene, LegacyBProductionRunner.Input input,
            LegacyVisibleProfileSampler.Input visibleImmediate,
            LegacyManagedProfileSampler.Input managedImmediate,
            List<XY> trueRidge, String fixtureHash, Map<SourceKind, Boolean> normalDirectionsDiffer) { }
    private record Observation(Scene scene, SourceKind sourceKind, int pair, String order, String policy,
            long elapsedNanos, int profileCount, int alternativeCount, int seedCount,
            String alternativesDigest, String geometryDigest, boolean fixedPointsExact, String warnings,
            int supportedProfiles, String fixtureHash, String alternativesJson,
            Map<String, List<ProjectedPoint>> acceptedGeometry) { }

    private LegacyWideSecantPerformanceMain() { }

    public static void main(String[] args) throws Throwable {
        boolean preflight = args.length == 2 && "--preflight".equals(args[0]);
        if (!preflight && (args.length != 3 || !"--fork".equals(args[0]))) {
            throw new IllegalArgumentException("Expected --preflight NEW_OUTPUT_DIRECTORY or --fork 0|1|2 NEW_OUTPUT_DIRECTORY");
        }
        int fork = preflight ? -1 : Integer.parseInt(args[1]);
        if (!preflight && (fork < 0 || fork > 2)) throw new IllegalArgumentException("Fork index must be 0, 1, or 2");
        ProjectionRegistry.setProjection(PROJECTION);
        Path output = Path.of(args[preflight ? 1 : 2]).toAbsolutePath().normalize();
        Files.createDirectory(output);
        var observations = new ArrayList<Observation>();
        for (Scene scene : Scene.values()) {
            Fixture fixture = fixture(scene);
            for (SourceKind sourceKind : SourceKind.values()) {
                if (scene == Scene.GAUSSIAN_KINK && !fixture.normalDirectionsDiffer().get(sourceKind)) {
                    throw new IllegalStateException("Protected secant normals did not change in the noisy " + sourceKind + " fixture");
                }
                int warmups = preflight ? 0 : WARMUP_PAIRS;
                int measuredPairs = preflight ? 1 : MEASURED_PAIRS;
                for (int pair = 0; pair < warmups; pair++) {
                    boolean candidateFirst = ((pair + fork) & 1) == 1;
                    timedPair(fixture, scene, sourceKind, pair, candidateFirst, false, observations);
                }
                for (int pair = 0; pair < measuredPairs; pair++) {
                    boolean candidateFirst = ((pair + fork) & 1) == 1;
                    timedPair(fixture, scene, sourceKind, pair, candidateFirst, true, observations);
                }
            }
        }
        writeCsv(output.resolve("observations.csv"), observations);
        writeSummary(output.resolve("summary.json"), fork, observations, preflight);
    }

    /** Times the real retained Legacy discovery for both policies on one frozen public fixture. */
    private static void timedPair(Fixture fixture, Scene scene, SourceKind sourceKind, int pair, boolean candidateFirst,
            boolean measured, List<Observation> out) throws Throwable {
        Observation candidate;
        Observation baseline;
        if (candidateFirst) {
            candidate = discover(fixture, scene, sourceKind, pair, "PROTECTED_SECANT3_V1", true);
            baseline = discover(fixture, scene, sourceKind, pair, "IMMEDIATE_V1", true);
        } else {
            baseline = discover(fixture, scene, sourceKind, pair, "IMMEDIATE_V1", false);
            candidate = discover(fixture, scene, sourceKind, pair, "PROTECTED_SECANT3_V1", false);
        }
        if (scene == Scene.GAUSSIAN_KINK && !materialGeometryDifference(candidate.acceptedGeometry(), baseline.acceptedGeometry())) {
            throw new IllegalStateException("Protected policy produced no material actual discovery geometry change on Gaussian-kink control");
        }
        if (measured) {
            if (candidateFirst) { out.add(candidate); out.add(baseline); }
            else { out.add(baseline); out.add(candidate); }
        }
    }

    private static Observation discover(Fixture f, Scene scene, SourceKind sourceKind, int pair, String policy,
            boolean candidateFirst) throws Throwable {
        long start = System.nanoTime();
        boolean candidate = policy.equals("PROTECTED_SECANT3_V1");
        List<Integer> fixed = candidate
                ? LegacyBProductionRunner.samplingFixedIndices(f.input(), CancellationProbe.NONE) : List.of();
        LegacyVisibleProfileSampler.Input visible = null;
        LegacyManagedProfileSampler.Input managed = null;
        if (sourceKind == SourceKind.VISIBLE) {
            visible = candidate ? f.visibleImmediate().withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, fixed)
                    : f.visibleImmediate();
            if (candidate && visible.normalPolicy() != LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1)
                throw new IllegalStateException("Visible protected source fell back: " + visible.normalPolicyFallbackReason());
        } else {
            managed = candidate ? f.managedImmediate().withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, fixed)
                    : f.managedImmediate();
            if (candidate && managed.normalPolicy() != LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1)
                throw new IllegalStateException("Managed protected source fell back: " + managed.normalPolicyFallbackReason());
        }
        AttemptMemoryLedger.Owner root = AttemptMemoryLedger.production().rootOwner();
        Accounted<LegacyBProductionRunner.Discovery> accounted = null;
        LegacyBProductionRunner.Discovery result;
        try {
            var runner = new LegacyBProductionRunner();
            var deadline = new LegacyBPipeline.Deadline(System::nanoTime, 120_000_000_000L);
            accounted = sourceKind == SourceKind.VISIBLE
                    ? runner.discoverVisible(f.input(), visible,
                        CancellationProbe.NONE, root, deadline)
                    : runner.discoverManaged(f.input(), managed,
                        CancellationProbe.NONE, root, deadline);
            result = accounted.value();
        } finally {
            if (accounted != null) accounted.owner().close();
            root.close();
        }
        long elapsed = System.nanoTime() - start;
        if (result.profileCount() < 10) throw new IllegalStateException("Need at least ten native profiles");
        if (result.alternatives().isEmpty() || result.seeds().isEmpty()) {
            throw new IllegalStateException("Discovery returned no usable native alternatives/seeds");
        }
        if (result.alternatives().stream().noneMatch(a -> a.rejection().isEmpty())) {
            throw new IllegalStateException("Discovery returned no accepted native alternative");
        }
        boolean exact = exactFixedPoints(f.input().originalProjected(), result.alternatives());
        if (!exact) throw new IllegalStateException("Discovery final preview moved an exact fixed endpoint");
        int supported = result.alternatives().stream().mapToInt(a -> a.evidence().supportedProfiles()).max().orElse(0);
        String digest = alternativesDigest(result);
        String geometry = geometryDigest(result);
        String warnings = result.alternatives().stream().map(a -> String.join("|", a.safetyWarnings()))
                .distinct().sorted().toList().toString();
        String alternativesJson = alternativesJson(result);
        Map<String, List<ProjectedPoint>> acceptedGeometry = new LinkedHashMap<>();
        for (var alternative : result.alternatives()) if (alternative.rejection().isEmpty()) {
            acceptedGeometry.put(alternative.nativeId(), alternative.finalProjected());
        }
        return new Observation(scene, sourceKind, pair, candidateFirst ? "candidate-first" : "baseline-first",
                policy, elapsed, result.profileCount(), result.alternatives().size(), result.seeds().size(),
                digest, geometry, exact, warnings, supported,
                f.fixtureHash(), alternativesJson, Map.copyOf(acceptedGeometry));
    }

    private static boolean materialGeometryDifference(Map<String, List<ProjectedPoint>> candidate,
            Map<String, List<ProjectedPoint>> baseline) {
        for (String nativeId : candidate.keySet()) {
            List<ProjectedPoint> next = candidate.get(nativeId), old = baseline.get(nativeId);
            if (old == null) continue;
            if (next.size() != old.size()) return true;
            double maximum = 0.0;
            for (int i = 0; i < next.size(); i++) maximum = Math.max(maximum,
                    Math.hypot(next.get(i).east() - old.get(i).east(), next.get(i).north() - old.get(i).north()));
            if (maximum > 0.25) return true;
        }
        return false;
    }

    private static boolean exactFixedPoints(List<ProjectedPoint> source,
            List<LegacyBProductionRunner.LegacyAlternative> alternatives) {
        if (source.size() < 2) return false;
        for (var alternative : alternatives) {
            if (alternative.rejection().isPresent()) continue;
            List<ProjectedPoint> points = alternative.finalProjected();
            if (points.size() < 2 || points.stream().anyMatch(p -> p == null
                    || !Double.isFinite(p.east()) || !Double.isFinite(p.north()))) return false;
            // The public fixture protects only original endpoints; PRECISE_SHAPE
            // may legally remove interior occurrences from its final preview.
            if (!points.get(0).equals(source.get(0))
                    || !points.get(points.size() - 1).equals(source.get(source.size() - 1))) return false;
        }
        return true;
    }

    private static String alternativesDigest(LegacyBProductionRunner.Discovery result) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (var a : result.alternatives()) {
            digest.update(a.id().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(a.nativeId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(String.join("\u0000", a.safetyWarnings()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(a.rejection().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(a.evidence().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(16).putLong(Double.doubleToRawLongBits(a.score()))
                    .putLong(a.finalProjected().size()).array());
            for (double offset : a.offsetsPx()) digest.update(ByteBuffer.allocate(8).putLong(Double.doubleToRawLongBits(offset)).array());
            for (ProjectedPoint point : a.rawProjected()) {
                digest.update(ByteBuffer.allocate(16).putLong(Double.doubleToRawLongBits(point.east()))
                        .putLong(Double.doubleToRawLongBits(point.north())).array());
            }
            for (ProjectedPoint point : a.finalProjected()) {
                digest.update(ByteBuffer.allocate(16).putLong(Double.doubleToRawLongBits(point.east()))
                        .putLong(Double.doubleToRawLongBits(point.north())).array());
            }
            a.seed().ifPresent(seed -> {
                digest.update(seed.id().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for (MetricPoint point : seed.points()) digest.update(ByteBuffer.allocate(16)
                        .putLong(Double.doubleToRawLongBits(point.xMeters()))
                        .putLong(Double.doubleToRawLongBits(point.yMeters())).array());
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String geometryDigest(LegacyBProductionRunner.Discovery result) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int accepted = 0;
        for (var a : result.alternatives()) {
            if (a.rejection().isPresent()) continue;
            accepted++;
            for (ProjectedPoint point : a.finalProjected()) {
                digest.update(ByteBuffer.allocate(16).putLong(Double.doubleToRawLongBits(point.east()))
                        .putLong(Double.doubleToRawLongBits(point.north())).array());
            }
        }
        digest.update(ByteBuffer.allocate(4).putInt(accepted).array());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String alternativesJson(LegacyBProductionRunner.Discovery result) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < result.alternatives().size(); i++) {
            var a = result.alternatives().get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":\"").append(escape(a.id())).append("\",\"nativeId\":\"")
                    .append(escape(a.nativeId())).append("\",\"rank\":").append(a.nativeRank())
                    .append(",\"score\":").append(a.score()).append(",\"supportedProfiles\":")
                    .append(a.evidence().supportedProfiles()).append(",\"warnings\":\"")
                    .append(escape(String.join("|", a.safetyWarnings()))).append("\",\"rejection\":\"")
                    .append(escape(a.rejection().toString())).append("\",\"seedId\":\"")
                    .append(escape(a.seed().map(seed -> seed.id()).orElse("UNAVAILABLE")))
                    .append("\",\"offsetsPx\":").append(numberListJson(a.offsetsPx()))
                    .append(",\"rawProjected\":").append(projectedJson(a.rawProjected()))
                    .append(",\"finalProjected\":").append(projectedJson(a.finalProjected()))
                    .append(",\"seedMetric\":").append(a.seed().map(seed -> metricJson(seed.points())).orElse("[]"))
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String projectedJson(List<ProjectedPoint> points) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) json.append(',');
            json.append('[').append(points.get(i).east()).append(',').append(points.get(i).north()).append(']');
        }
        return json.append(']').toString();
    }

    private static String metricJson(List<MetricPoint> points) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) json.append(',');
            json.append('[').append(points.get(i).xMeters()).append(',').append(points.get(i).yMeters()).append(']');
        }
        return json.append(']').toString();
    }

    private static String numberListJson(List<Double> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append(values.get(i));
        }
        return json.append(']').toString();
    }

    private static Fixture fixture(Scene scene) throws Exception {
        List<XY> source = source(scene);
        List<XY> ridge = target(scene);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-0.025, -0.025), new GeographicPoint(0.025, 0.025));
        List<GeographicPoint> coordinates = source.stream().map(p -> frame.toGeographic(new MetricPoint(p.x(), p.y()))).toList();
        List<ProjectedPoint> projected = coordinates.stream().map(g -> {
            EastNorth en = PROJECTION.latlon2eastNorth(new LatLon(g.latitudeDegrees(), g.longitudeDegrees()));
            return new ProjectedPoint(en.east(), en.north());
        }).toList();
        List<MetricPoint> metric = coordinates.stream().map(frame::toMetric).toList();
        double[] chainages = new double[metric.size()];
        for (int i = 1; i < metric.size(); i++) chainages[i] = chainages[i - 1]
                + Math.hypot(metric.get(i).xMeters() - metric.get(i - 1).xMeters(),
                    metric.get(i).yMeters() - metric.get(i - 1).yMeters());

        int[] argb = new int[WIDTH * HEIGHT];
        double[] scalar = new double[argb.length];
        boolean[] valid = new boolean[argb.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < HEIGHT; y++) {
            double north = MAX_N - (y + 0.5) * RASTER_METERS_PER_PIXEL;
            for (int x = 0; x < WIDTH; x++) {
                double east = MIN_E + (x + 0.5) * RASTER_METERS_PER_PIXEL;
                double value = intensity(scene, ridge, east, north);
                int gray = (int) Math.round(255 * value);
                int pixel = 0xFF000000 | gray << 16 | gray << 8 | gray;
                int index = y * WIDTH + x;
                argb[index] = pixel;
                scalar[index] = gray / 255.0;
            }
        }
        String sourceIdentity = SOURCE_ID + "/" + scene.name().toLowerCase(java.util.Locale.ROOT);
        var raster = new LiveBPreviewService.VisibleRaster(WIDTH, HEIGHT, argb,
                MIN_E, MIN_N, MAX_E, MAX_N, 1.0, 1.0,
                java.util.OptionalDouble.of(RASTER_METERS_PER_PIXEL), sourceIdentity, "EPSG:3857");
        MetricPoint firstCenter = new MetricPoint(MIN_E + RASTER_METERS_PER_PIXEL / 2,
                MAX_N - RASTER_METERS_PER_PIXEL / 2);
        var transform = RasterMetricTransform.visible(firstCenter, RASTER_METERS_PER_PIXEL);
        MetricRegion footprint = new MetricRegion(List.of(List.of(
                transform.pixelCenterToMetric(-0.5, -0.5),
                transform.pixelCenterToMetric(WIDTH - 0.5, -0.5),
                transform.pixelCenterToMetric(WIDTH - 0.5, HEIGHT - 0.5),
                transform.pixelCenterToMetric(-0.5, HEIGHT - 0.5))));
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "hot",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(WIDTH, HEIGHT, scalar, valid, lineage);
        var resolution = EvidenceResolution.renderedOnly(RASTER_METERS_PER_PIXEL);
        String evidenceId = hex(sourceIdentity + "/evidence");
        var evidence = new EvidenceSnapshot(evidenceId, frame, transform, resolution, footprint, footprint,
                Map.of("hot", field), sourceIdentity);

        PrimitiveKey wayKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 91001 + scene.ordinal());
        List<PrimitiveKey> nodeKeys = new ArrayList<>();
        Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>();
        for (int i = 0; i < coordinates.size(); i++) {
            PrimitiveKey key = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 92000 + scene.ordinal() * 100 + i);
            nodeKeys.add(key);
            primitives.put(key, new DetachedNode(key, coordinates.get(i), Map.of(), false, false));
        }
        var way = new DetachedWay(wayKey, nodeKeys, Map.of("highway", "path"), false, false);
        primitives.put(wayKey, way);
        Set<PrimitiveKey> protectedNodes = Set.of(nodeKeys.get(FIXED.get(0)), nodeKeys.get(FIXED.get(1)));
        var closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY, "synthetic-public-v1",
                primitives.keySet(), Set.of(wayKey), Set.of(), protectedNodes, Set.of(),
                Map.of(wayKey, List.of(new OccurrenceRange(0, source.size() - 1))), List.of(), footprint,
                footprint, false, true, true, true);
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>();
        for (PrimitiveKey key : nodeKeys) watches.put(key, Set.of(wayKey));
        watches.put(wayKey, Set.of());
        var network = new NetworkSnapshot(hex(sourceIdentity + "/network"), SnapshotRole.CAPTURED_BEFORE,
                hex(sourceIdentity + "/dataset"), 1L, closure, primitives, watches, null);
        var occurrences = java.util.stream.IntStream.range(0, source.size()).mapToObj(i ->
                new LegacyTopologyPlanner.Occurrence(i, FIXED.contains(i), FIXED.contains(i))).toList();
        var profileChainage = new ProbabilisticProfileFactory().profileChainage(metric, 50.0);
        var request = new TraceRequest(wayKey, new OccurrenceRange(0, source.size() - 1), TrackerMode.LEGACY_THEN_B,
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(4), TraceBudgets.defaults(),
                evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
                SETTINGS_HASH, POLICY_HASH, "public-synthetic-performance-v1", 50.0, profileChainage, resolution);
        var input = new LegacyBProductionRunner.Input(request, network, evidence, "hot", projected, metric,
                "EPSG:3857", occurrences, List.of(), false, 0.0);
        List<LegacyVisibleProfileSampler.Anchor> anchors = new ArrayList<>();
        for (int i = 0; i < projected.size(); i++) {
            var p = projected.get(i);
            anchors.add(new LegacyVisibleProfileSampler.Anchor(p,
                    new RasterPoint((p.east() - MIN_E) / RASTER_METERS_PER_PIXEL,
                            (MAX_N - p.north()) / RASTER_METERS_PER_PIXEL), chainages[i]));
        }
        var immediate = new LegacyVisibleProfileSampler.Input(raster, anchors, List.of("hot"),
                new LegacyVisibleProfileSampler.Options(25, 1, 6.0, IntensitySamplingMode.DIRECT_VALUE));
        double world = MANAGED_TILE_SIZE * Math.pow(2, MANAGED_ZOOM);
        double nativePitch = 2 * Math.PI * EARTH_RADIUS_M / world;
        double origin = world / 2 - 512;
        BufferedImage nativeImage = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 1024; y++) {
            double north = (world / 2 - origin - y - 0.5) * nativePitch;
            for (int x = 0; x < 1024; x++) {
                double east = (origin + x + 0.5 - world / 2) * nativePitch;
                int gray = (int) Math.round(255 * intensity(scene, ridge, east, north));
                nativeImage.setRGB(x, y, 0xFF000000 | gray << 16 | gray << 8 | gray);
            }
        }
        var nativeParameters = new TileHeatmapSampler.SamplingParameters(MANAGED_ZOOM, 0, nativePitch,
                25.0, 1.56, (int) Math.round(25.0 / nativePitch), 1);
        double virtualScale = nativePitch / (0.389 / 6.0);
        var mosaic = new TileHeatmapSampler.TileMosaic("hot", MANAGED_ZOOM, origin, origin,
                nativeImage, List.of(), Map.of(), nativeParameters, false, virtualScale);
        var mosaicSet = new TileHeatmapSampler.TileMosaicSet(InferenceMode.RAW_HIGH_RESOLUTION,
                MANAGED_ZOOM, MANAGED_ZOOM, MANAGED_ZOOM, MANAGED_ZOOM, MANAGED_TILE_SIZE,
                Map.of("hot@" + MANAGED_ZOOM, mosaic), nativeParameters, nativeParameters);
        var managedImmediate = LegacyManagedProfileSampler.freeze(mosaicSet, projected, "EPSG:3857",
                "hot", List.of("hot"), IntensitySamplingMode.DIRECT_VALUE, virtualScale);
        var protectedSource = immediate.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, FIXED);
        var oldProfiles = new LegacyVisibleProfileSampler().sample(immediate, CancellationProbe.NONE).get(0).profiles();
        var newProfiles = new LegacyVisibleProfileSampler().sample(protectedSource, CancellationProbe.NONE).get(0).profiles();
        boolean changed = java.util.stream.IntStream.range(0, oldProfiles.size()).anyMatch(i ->
                Double.doubleToRawLongBits(oldProfiles.get(i).normalScreen().x) != Double.doubleToRawLongBits(newProfiles.get(i).normalScreen().x)
                || Double.doubleToRawLongBits(oldProfiles.get(i).normalScreen().y) != Double.doubleToRawLongBits(newProfiles.get(i).normalScreen().y));
        var managedProtected = managedImmediate.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, FIXED);
        var managedOldProfiles = new LegacyManagedProfileSampler().sample(managedImmediate, CancellationProbe.NONE).get(0).profiles();
        var managedNewProfiles = new LegacyManagedProfileSampler().sample(managedProtected, CancellationProbe.NONE).get(0).profiles();
        boolean managedChanged = java.util.stream.IntStream.range(0, managedOldProfiles.size()).anyMatch(i ->
                Double.doubleToRawLongBits(managedOldProfiles.get(i).normalScreen().x) != Double.doubleToRawLongBits(managedNewProfiles.get(i).normalScreen().x)
                || Double.doubleToRawLongBits(managedOldProfiles.get(i).normalScreen().y) != Double.doubleToRawLongBits(managedNewProfiles.get(i).normalScreen().y));
        if (source.size() < 15 || oldProfiles.size() < 10 || newProfiles.size() != oldProfiles.size()
                || managedOldProfiles.size() < 7 || managedNewProfiles.size() != managedOldProfiles.size()) {
            throw new IllegalStateException("Synthetic source/profile census is insufficient");
        }
        return new Fixture(scene, input, immediate, managedImmediate,
                ridge, fixtureHash(scene, source, ridge, scalar, nativeImage), Map.of(SourceKind.VISIBLE, changed,
                        SourceKind.MANAGED, managedChanged));
    }

    private static List<XY> source(Scene scene) {
        if (scene == Scene.GAUSSIAN_KINK) {
            int[] stations = {-80, -40, -20, -9, -3, -2, -1, 0, 1, 2, 3, 9, 20, 40, 80};
            return Arrays.stream(stations).mapToObj(raw -> {
                double x = raw * 4.0;
                return new XY(x, 4.0 * Math.exp(-(x * x) / 32.0));
            }).toList();
        }
        List<XY> out = new ArrayList<>(15);
        for (int i = 0; i < 15; i++) {
            double x = i * STEP_M - 600.0;
            double y = switch (scene) {
                case STRAIGHT, PARALLEL_STRANDS -> 0.0;
                case GAUSSIAN_KINK -> throw new AssertionError("Gaussian source constructed separately");
                case SUPPORTED_HAIRPIN -> hairpin(i).y();
            };
            out.add(scene == Scene.SUPPORTED_HAIRPIN ? hairpin(i) : new XY(x, y));
        }
        return List.copyOf(out);
    }

    private static XY hairpin(int i) {
        if (i <= 7) return new XY(-600.0 + i * STEP_M, 0.0);
        double k = i - 7;
        return new XY(-STEP_M * k, 12 + 8 * k - 0.55 * k * k);
    }

    private static List<XY> target(Scene scene) {
        if (scene == Scene.SUPPORTED_HAIRPIN) return java.util.stream.IntStream.range(0, 15).mapToObj(LegacyWideSecantPerformanceMain::hairpin).toList();
        if (scene == Scene.PARALLEL_STRANDS) return List.of(new XY(-600, 24), new XY(600, 24), new XY(600, -24), new XY(-600, -24));
        if (scene == Scene.GAUSSIAN_KINK) return java.util.stream.IntStream.range(0, 15).mapToObj(i -> {
            double x = i * STEP_M - 600.0;
            return new XY(x, 21.0);
        }).toList();
        return List.of(new XY(-600, 12), new XY(600, 12));
    }

    private static double intensity(Scene scene, List<XY> ridges, double east, double north) {
        double distance = scene == Scene.PARALLEL_STRANDS
                ? Math.min(distanceToLine(ridges, 0, 1, east, north), distanceToLine(ridges, 2, 3, east, north))
                : distanceToPolyline(ridges, east, north);
        double value = Math.exp(-0.5 * Math.pow(distance / 3.0, 2));
        return Math.min(1.0, value);
    }

    private static double distanceToPolyline(List<XY> points, double x, double y) {
        return distanceToLine(points, 0, points.size() - 1, x, y);
    }

    private static double distanceToLine(List<XY> points, int start, int end, double x, double y) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = start; i < end; i++) {
            XY a = points.get(i), b = points.get(i + 1);
            double dx = b.x() - a.x(), dy = b.y() - a.y();
            double t = Math.max(0, Math.min(1, ((x - a.x()) * dx + (y - a.y()) * dy) / (dx * dx + dy * dy)));
            best = Math.min(best, Math.hypot(x - a.x() - t * dx, y - a.y() - t * dy));
        }
        return best;
    }

    private static String fixtureHash(Scene scene, List<XY> source, List<XY> target, double[] scalar,
            BufferedImage managedNativeImage) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(scene.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (XY p : source) digest.update(ByteBuffer.allocate(16).putLong(Double.doubleToRawLongBits(p.x()))
                .putLong(Double.doubleToRawLongBits(p.y())).array());
        for (XY p : target) digest.update(ByteBuffer.allocate(16).putLong(Double.doubleToRawLongBits(p.x()))
                .putLong(Double.doubleToRawLongBits(p.y())).array());
        ByteBuffer scalarBytes = ByteBuffer.allocate(scalar.length * Long.BYTES);
        for (double value : scalar) scalarBytes.putLong(Double.doubleToRawLongBits(value));
        digest.update(scalarBytes.array());
        int[] nativeArgb = managedNativeImage.getRGB(0, 0, managedNativeImage.getWidth(),
                managedNativeImage.getHeight(), null, 0, managedNativeImage.getWidth());
        ByteBuffer nativeBytes = ByteBuffer.allocate(nativeArgb.length * Integer.BYTES);
        for (int pixel : nativeArgb) nativeBytes.putInt(pixel);
        digest.update(nativeBytes.array());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hex(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void writeCsv(Path path, List<Observation> observations) throws Exception {
        StringBuilder csv = new StringBuilder("scene,source_kind,pair,order,policy,elapsed_ns,profile_count,alternative_count,seed_count,alternatives_sha256,geometry_sha256,fixed_points_exact,warnings,supported_profiles,fixture_sha256,alternatives_json\n");
        for (Observation row : observations) {
            csv.append(row.scene()).append(',').append(row.sourceKind()).append(',').append(row.pair()).append(',').append(row.order()).append(',')
                    .append(row.policy()).append(',').append(row.elapsedNanos()).append(',').append(row.profileCount())
                    .append(',').append(row.alternativeCount()).append(',').append(row.seedCount()).append(',')
                    .append(row.alternativesDigest()).append(',').append(row.geometryDigest()).append(',').append(row.fixedPointsExact()).append(',')
                    .append(quote(row.warnings())).append(',').append(row.supportedProfiles()).append(',')
                    .append(row.fixtureHash()).append(',').append(quote(row.alternativesJson())).append('\n');
        }
        Files.writeString(path, csv);
    }

    private static void writeSummary(Path path, int fork, List<Observation> rows, boolean preflight) throws Exception {
        String runnerHash = resourceHash(LegacyBProductionRunner.class);
        String samplerHash = resourceHash(LegacyVisibleProfileSampler.class) + "/" + resourceHash(LegacyManagedProfileSampler.class);
        StringBuilder json = new StringBuilder("{\n  \"status\":\"" + (preflight ? "COMPLETED_ONE_PUBLIC_PREFLIGHT_PAIR_PER_SCENE_AND_SOURCE" : "COMPLETED_ONE_PUBLIC_SYNTHETIC_FORK") + "\",\n")
                .append("  \"fork\":").append(fork).append(",\n  \"sourceKinds\":[\"VISIBLE_RENDERED\",\"MANAGED_NATIVE_Z15\"],\n")
                .append("  \"decisionStatus\":\"").append(preflight ? "PREFLIGHT_ONLY_NO_PERFORMANCE_DECISION" : "INCONCLUSIVE_UNTIL_THREE_FORKS").append("\",\n")
                .append("  \"immediatePolicyId\":\"").append(LegacyProfileNormalPolicy.IMMEDIATE_V1.id()).append("\",\n")
                .append("  \"candidatePolicyId\":\"").append(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1.id()).append("\",\n")
                .append("  \"runnerClassSha256\":\"").append(runnerHash).append("\",\n")
                .append("  \"samplerClassSha256\":\"").append(samplerHash).append("\",\n")
                .append("  \"harnessClassSha256\":\"").append(resourceHash(LegacyWideSecantPerformanceMain.class)).append("\",\n")
                .append("  \"runtimeCodeLocation\":\"").append(escape(String.valueOf(LegacyWideSecantPerformanceMain.class
                        .getProtectionDomain().getCodeSource().getLocation()))).append("\",\n")
                .append("  \"recommendedHeapMiB\":512,\n  \"deadlinePerDiscoverySeconds\":120,\n")
                .append("  \"plannedWarmupPairsPerSceneAndSource\":").append(WARMUP_PAIRS).append(",\n")
                .append("  \"plannedMeasuredPairsPerSceneAndSource\":").append(MEASURED_PAIRS).append(",\n")
                .append("  \"plannedFreshJvmForks\":3,\n  \"measuredRows\":").append(rows.size()).append(",\n")
                .append("  \"predeclaredBlock\":\"overhead > max(5ms,10% baseline) or p95 > max(10ms,20% baseline) in >=2/3 forks/scene/source\",\n")
                .append("  \"sourceSupport\":\"synthetic heat raster; supported-profile count and safety warnings retained per discovery\",\n")
                .append("  \"noBExecution\":true,\n  \"timingScope\":\"policy planning + actual production discovery + owner close; excludes fixture creation, acquisition, rendering, export\",\n")
                .append("  \"managedSource\":\"decoded native 1024x1024 z15 raster; pixel-center EPSG:3857 mapping; virtual scale P/(0.389/6); RAW_HIGH_RESOLUTION\",\n")
                .append("  \"fixtureGeometry\":\"15 source occurrences; prescribed Gaussian source x=4*[-80,-40,-20,-9,-3,-2,-1,0,1,2,3,9,20,40,80], y=4*exp(-x*x/32); true ridge y=21; other scenes centered within +/-600m; fixed original endpoints 0 and 14\",\n")
                .append("  \"pairedTimingSummary\":").append(pairedSummary(rows)).append(",\n")
                .append("  \"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            Observation r = rows.get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"scene\":\"").append(r.scene()).append("\",\"sourceKind\":\"").append(r.sourceKind()).append("\",\"pair\":").append(r.pair())
                    .append(",\"policy\":\"").append(r.policy()).append("\",\"elapsedNanos\":").append(r.elapsedNanos())
                    .append(",\"fixtureSha256\":\"").append(r.fixtureHash()).append("\",\"alternativesSha256\":\"")
                    .append(r.alternativesDigest()).append("\",\"geometrySha256\":\"").append(r.geometryDigest()).append("\"}");
        }
        json.append("\n  ]\n}\n");
        Files.writeString(path, json);
    }

    private static String pairedSummary(List<Observation> rows) {
        StringBuilder out = new StringBuilder("{");
        for (Scene scene : Scene.values()) for (SourceKind sourceKind : SourceKind.values()) {
            Map<Integer, Long> old = new LinkedHashMap<>(), next = new LinkedHashMap<>();
            for (Observation row : rows) if (row.scene() == scene && row.sourceKind() == sourceKind) {
                (row.policy().equals("IMMEDIATE_V1") ? old : next).put(row.pair(), row.elapsedNanos());
            }
            List<Double> overheadMs = new ArrayList<>(), overheadPercent = new ArrayList<>(), ratios = new ArrayList<>();
            StringBuilder raw = new StringBuilder("[");
            int pairLimit = rows.stream().filter(row -> row.scene() == scene && row.sourceKind() == sourceKind)
                    .mapToInt(Observation::pair).max().orElse(-1) + 1;
            for (int pair = 0; pair < pairLimit; pair++) {
                Long baseline = old.get(pair), candidate = next.get(pair);
                if (baseline == null || candidate == null) continue;
                double ms = (candidate - baseline) / 1_000_000.0;
                double percent = 100.0 * (candidate - baseline) / Math.max(1.0, baseline);
                double ratio = (double) candidate / Math.max(1.0, baseline);
                overheadMs.add(ms); overheadPercent.add(percent); ratios.add(ratio);
                if (raw.length() > 1) raw.append(',');
                raw.append("{\"pair\":").append(pair).append(",\"baselineNs\":").append(baseline)
                        .append(",\"candidateNs\":").append(candidate).append(",\"overheadMs\":").append(ms)
                        .append(",\"overheadPercent\":").append(percent).append(",\"ratio\":").append(ratio).append('}');
            }
            if (out.length() > 1) out.append(',');
            out.append('"').append(scene).append('/').append(sourceKind).append("\":{\"pairedCount\":").append(overheadMs.size())
                    .append(",\"medianOverheadMs\":").append(median(overheadMs))
                    .append(",\"p95OverheadMs\":").append(percentile(overheadMs, 0.95))
                    .append(",\"medianOverheadPercent\":").append(median(overheadPercent))
                    .append(",\"p95OverheadPercent\":").append(percentile(overheadPercent, 0.95))
                    .append(",\"medianRatio\":").append(median(ratios)).append(",\"rawPairs\":").append(raw).append("]}");
        }
        return out.append('}').toString();
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = values.stream().sorted().toList();
        int m = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(m - 1) + sorted.get(m)) / 2 : sorted.get(m);
    }

    private static double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
    }

    private static String resourceHash(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) return "UNAVAILABLE";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }

    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
