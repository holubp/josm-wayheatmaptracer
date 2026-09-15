package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRasterGrid;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorEngineAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernCandidateAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

/** Experimental read-only live producer for the explicitly supported B preview. */
public final class LiveBPreviewService {
    private static final double WEB_MERCATOR_RADIUS = 6_378_137.0;
    private static final String FIELD = "selected-visible-source";

    /** Immutable visible-render pixels and exact slide-time sampling metadata. */
    public record VisibleRaster(int width, int height, int[] argb,
            double minimumEast, double minimumNorth, double maximumEast, double maximumNorth,
            double projectionUnitsPerViewPixel, double groundMetersPerViewPixel,
            OptionalDouble nativePitchMeters, String sourceIdentity, String projectionCode) {
        public VisibleRaster {
            nativePitchMeters = nativePitchMeters == null ? OptionalDouble.empty() : nativePitchMeters;
            long pixels = (long) width * height;
            if (width < 2 || height < 2 || pixels > Integer.MAX_VALUE || argb == null
                    || argb.length != pixels || !finite(minimumEast, minimumNorth, maximumEast,
                            maximumNorth, projectionUnitsPerViewPixel, groundMetersPerViewPixel)
                    || minimumEast >= maximumEast || minimumNorth >= maximumNorth
                    || projectionUnitsPerViewPixel <= 0.0 || groundMetersPerViewPixel <= 0.0
                    || nativePitchMeters.isPresent() && (!Double.isFinite(nativePitchMeters.getAsDouble())
                            || nativePitchMeters.getAsDouble() <= 0.0)
                    || sourceIdentity == null || sourceIdentity.isBlank()
                    || projectionCode == null || projectionCode.isBlank()) {
                throw new IllegalArgumentException("Visible preview raster metadata is incomplete");
            }
            if (!matchesRenderedDimension(width, maximumEast - minimumEast,
                    projectionUnitsPerViewPixel)
                    || !matchesRenderedDimension(height, maximumNorth - minimumNorth,
                            projectionUnitsPerViewPixel)) {
                throw new IllegalArgumentException(
                        "Visible preview raster dimensions do not match its bounds and view scale");
            }
            argb = argb.clone();
        }
        @Override public int[] argb() { return argb.clone(); }
        BufferedImage image() {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, width, height, argb, 0, width);
            return image;
        }
    }

    /** Complete detached worker input captured on the EDT. */
    public record Captured(VisibleRaster raster, NetworkSnapshotCapture.Specification specification,
            NetworkSnapshot network, List<GeographicPoint> sourceGeographic,
            List<MetricPoint> sourceMetric, MetricRasterGrid outputGrid,
            String palette, double searchRadiusMeters, double sampleStepMeters,
            String settingsHash, String parameterHash) {
        public Captured {
            sourceGeographic = List.copyOf(sourceGeographic);
            sourceMetric = List.copyOf(sourceMetric);
        }
    }

    /** Detached result from the actual common modern final pipeline. */
    public record Computed(Captured captured, EvidenceSnapshot evidence,
            ModernTracePipeline.Result pipeline) { }

    /** Captures the bounded network and exact visible-render frame on the EDT without mutation. */
    public Captured capture(DataSet dataSet, SelectionContext selection,
            VisibleRaster raster, AlignmentConfig config) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Live B preview capture must execute on the EDT");
        }
        if (raster == null) {
            throw new IllegalArgumentException("Live B preview raster is required");
        }
        requireSupported(selection, raster.projectionCode(), config);
        List<GeographicPoint> source = selection.segmentNodes().stream()
                .map(LiveBPreviewService::geographic).toList();
        GeographicPoint southWest = inverseMercator(raster.minimumEast(), raster.minimumNorth());
        GeographicPoint northEast = inverseMercator(raster.maximumEast(), raster.maximumNorth());
        GeographicPoint origin = source.get(source.size() / 2);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin, southWest, northEast);
        List<MetricPoint> metric = source.stream().map(frame::toMetric).toList();
        double pitch = raster.groundMetersPerViewPixel() / RenderedHeatmapSampler.RASTER_SCALE;
        MetricRasterGrid grid = grid(frame, southWest, northEast, pitch);
        double radius = config.heatmap().crossSectionHalfWidthPx()
                * raster.groundMetersPerViewPixel();
        double step = config.heatmap().crossSectionStepPx() * raster.groundMetersPerViewPixel();
        MetricRegion decision = MetricCorridorRegion.aroundPolyline(metric, radius);
        if (!grid.footprint().containsRegion(decision)) {
            throw new IllegalArgumentException("Visible capture does not contain the complete B decision corridor");
        }
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                selection.way().getUniqueId());
        OccurrenceRange range = new OccurrenceRange(selection.startIndex(), selection.endIndex());
        Set<PrimitiveKey> protectedNodes = new LinkedHashSet<>();
        for (Node node : selection.segmentNodes()) {
            protectedNodes.add(PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId()));
        }
        String settingsHash = hash(config.heatmap().toRedactedJson(), config.cleanup().toRedactedJson());
        String parameterHash = hash("live-probabilistic-default-v1");
        String snapshotId = "live-b-network-" + hash(Long.toString(selection.way().getUniqueId()),
                source.toString(), settingsHash).substring(0, 16);
        NetworkSnapshotCapture.Specification specification = new NetworkSnapshotCapture.Specification(
                snapshotId, "josm-dataset-" + Integer.toUnsignedString(System.identityHashCode(dataSet)),
                0L, way, range, frame, decision, decision,
                Map.of(way, List.of(range)), Set.of(way), Set.of(), Set.of(), protectedNodes,
                true, RecoveryPermissions.disabled(radius));
        NetworkSnapshot network = NetworkSnapshotCapture.capture(dataSet, specification);
        return new Captured(raster, specification, network, source, metric, grid,
                config.heatmap().color(), radius, step, settingsHash, parameterHash);
    }

    /** Runs RasterEvidenceCapture, production B, and common final processing off the EDT. */
    public Computed compute(Captured captured, CancellationProbe cancellation) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Live B preview inference must execute off the EDT");
        }
        if (captured == null || cancellation == null) {
            throw new IllegalArgumentException("Live B preview computation is incomplete");
        }
        EvidenceSnapshot evidence = captureEvidence(captured, cancellation);
        ProfileChainage chainage = new ProbabilisticProfileFactory().profileChainage(
                captured.sourceMetric(), captured.sampleStepMeters());
        TraceRequest request = new TraceRequest(captured.specification().selectedWayKey(),
                captured.specification().selectedRange(), TrackerMode.PROBABILISTIC,
                AlignmentMode.PRECISE_SHAPE, captured.specification().permissions(),
                TraceBudgets.defaults(), evidence.snapshotId(), evidence.canonicalHash(),
                captured.network().snapshotId(), captured.network().canonicalHash(),
                captured.settingsHash(), captured.parameterHash(), "visible-b-v1",
                captured.sampleStepMeters(), chainage, evidence.resolution());
        ModernTracePipeline.Result pipeline = new ModernTracePipeline(new CorridorEngineAdapter(FIELD))
                .run(request, evidence, captured.network(),
                        new ModernTracePipeline.Options(FIELD,
                                org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig.disabled(),
                                "selected-visible", 0), cancellation);
        return new Computed(captured, evidence, pipeline);
    }

    EvidenceSnapshot captureEvidence(Captured captured, CancellationProbe cancellation) {
        cancellation.checkpoint();
        boolean[] valid = new boolean[Math.multiplyExact(captured.raster().width(),
                captured.raster().height())];
        Arrays.fill(valid, true);
        EvidenceResolution resolution = captured.raster().nativePitchMeters().isPresent()
                ? EvidenceResolution.nativeSource(captured.raster().nativePitchMeters().getAsDouble(),
                        captured.raster().groundMetersPerViewPixel()
                                / RenderedHeatmapSampler.RASTER_SCALE)
                : EvidenceResolution.renderedOnly(captured.raster().groundMetersPerViewPixel()
                        / RenderedHeatmapSampler.RASTER_SCALE);
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,
                EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING,
                captured.palette(), EvidenceCorrelationGroup.STRAVA_RENDERINGS, false);
        RasterEvidenceCapture.FieldSpec field = RasterEvidenceCapture.FieldSpec.direct(FIELD,
                argb -> intensity(argb, captured.palette()), lineage);
        SupportedInputRasterTransform transform = SupportedInputRasterTransform.visibleWebMercator(
                captured.raster().minimumEast(), captured.raster().maximumNorth(),
                captured.raster().projectionUnitsPerViewPixel(), RenderedHeatmapSampler.RASTER_SCALE);
        return new RasterEvidenceCapture().capture(
                captured.network().snapshotId() + "-evidence", captured.raster().image(), valid,
                captured.sourceGeographic(), transform, captured.outputGrid(), resolution,
                captured.searchRadiusMeters(), captured.raster().sourceIdentity(),
                EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER, List.of(field), cancellation);
    }

    /** Repeats the exact bounded live query and rejects any relevant source or referrer change. */
    public void requireCurrent(DataSet dataSet, Captured captured) {
        requireCurrent(dataSet, captured, captured.raster().sourceIdentity(),
                captured.raster().projectionCode());
    }

    /** Revalidates network, layer identity, and projection before publication or candidate switch. */
    public void requireCurrent(DataSet dataSet, Captured captured,
            String sourceIdentity, String projectionCode) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Live B preview revalidation must execute on the EDT");
        }
        if (dataSet == null || captured == null
                || !captured.raster().sourceIdentity().equals(sourceIdentity)
                || !captured.raster().projectionCode().equals(projectionCode)) {
            throw new IllegalStateException("Live B preview source, layer, or projection is stale");
        }
        NetworkSnapshot current = NetworkSnapshotCapture.capture(dataSet, captured.specification());
        if (!current.canonicalHash().equals(captured.network().canonicalHash())) {
            throw new IllegalStateException("Live B preview network snapshot is stale");
        }
    }

    /** Converts final common-pipeline routes into the existing overlay candidate contract. */
    public List<CenterlineCandidate> adapt(Computed computed,
            Function<GeographicPoint, EastNorth> projector) {
        return new ModernCandidateAdapter().adaptRoutes(computed.pipeline().routes(),
                computed.evidence(), FIELD, computed.captured().sourceMetric(), projector);
    }

    /** Rejects unsupported live settings before any raster or network acquisition. */
    public static void requireSupported(SelectionContext selection, String projectionCode,
            AlignmentConfig config) {
        if (selection == null || selection.segmentNodes() == null || selection.segmentNodes().size() < 2
                || projectionCode == null || config == null) {
            throw new IllegalArgumentException("Live B preview inputs are incomplete");
        }
        var heatmap = config.heatmap();
        if (heatmap.trackerMode() != TrackerMode.PROBABILISTIC) {
            throw new IllegalArgumentException("Experimental live preview supports Probabilistic B only");
        }
        if (heatmap.alignmentMode() != AlignmentMode.PRECISE_SHAPE) {
            throw new IllegalArgumentException("Experimental live B preview requires Precise Shape");
        }
        if (!config.cleanup().isDisabled() || heatmap.simplifyEnabled()) {
            throw new IllegalArgumentException("Experimental live B preview requires cleanup and simplification Off");
        }
        if (heatmap.hasManagedAccessValues() || heatmap.multiColorDetection()
                || heatmap.aggregateAllColorSchemes() || heatmap.parallelWayAwareness()
                || heatmap.adjustJunctionNodes() || config.searchHalfWidthMetersOverride().isPresent()) {
            throw new IllegalArgumentException("Experimental live B preview does not support managed, expanded, or junction options");
        }
        if (heatmap.intensitySamplingMode() != IntensitySamplingMode.COLOR_MAPPING) {
            throw new IllegalArgumentException("Experimental live B preview supports Color mapping only");
        }
        if (!"EPSG:3857".equals(projectionCode)) {
            throw new IllegalArgumentException("Experimental live B preview requires EPSG:3857");
        }
        Set<Long> identities = new LinkedHashSet<>();
        for (Node node : selection.way().getNodes()) {
            if (!identities.add(node.getUniqueId())) {
                throw new IllegalArgumentException("Experimental live B preview rejects repeated node identities");
            }
        }
    }

    private static MetricRasterGrid grid(LocalMetricFrame frame, GeographicPoint southWest,
            GeographicPoint northEast, double pitch) {
        MetricPoint sw = frame.toMetric(southWest);
        MetricPoint ne = frame.toMetric(northEast);
        double widthMeters = ne.xMeters() - sw.xMeters();
        double heightMeters = ne.yMeters() - sw.yMeters();
        int width = (int) Math.floor(widthMeters / pitch);
        int height = (int) Math.floor(heightMeters / pitch);
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Visible capture is too small for metric resampling");
        }
        return new MetricRasterGrid(frame,
                new MetricPoint(sw.xMeters() + 0.5 * pitch, ne.yMeters() - 0.5 * pitch),
                1.0, 0.0, 0.0, -1.0, pitch, width, height);
    }

    private static GeographicPoint geographic(Node node) {
        if (node == null || !node.isLatLonKnown()) {
            throw new IllegalArgumentException("Selected node has no geographic coordinate");
        }
        return new GeographicPoint(node.lat(), node.lon());
    }

    private static GeographicPoint inverseMercator(double east, double north) {
        double longitude = Math.toDegrees(east / WEB_MERCATOR_RADIUS);
        double latitude = Math.toDegrees(2.0 * Math.atan(Math.exp(north / WEB_MERCATOR_RADIUS))
                - Math.PI / 2.0);
        return new GeographicPoint(latitude, longitude);
    }

    private static double intensity(int argb, String palette) {
        int alpha = argb >>> 24;
        if (alpha == 0) {
            return 0.0;
        }
        return RenderedHeatmapSampler.colorIntensity((argb >>> 16) & 0xff,
                (argb >>> 8) & 0xff, argb & 0xff, palette);
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean matchesRenderedDimension(int pixels, double projectedSpan,
            double projectionUnitsPerViewPixel) {
        double expected = projectedSpan / projectionUnitsPerViewPixel
                * RenderedHeatmapSampler.RASTER_SCALE;
        return Double.isFinite(expected) && Math.abs(pixels - expected) <= 0.5;
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) return false;
        }
        return true;
    }
}
