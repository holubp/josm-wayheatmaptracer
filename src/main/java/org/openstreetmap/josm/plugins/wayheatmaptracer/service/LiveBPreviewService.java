package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.JunctionAuthorityBounds;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorEngineAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernCandidateAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

/** Experimental read-only live producer for the explicitly supported B preview. */
public final class LiveBPreviewService {
    private static final double WEB_MERCATOR_RADIUS = 6_378_137.0;
    private static final double MAXIMUM_JUNCTION_RELOCATION_METERS = 20.0;
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
    public record Captured(VisibleRaster raster, ManagedModernPreviewSource.Raster managedRaster,
            NetworkSnapshotCapture.Specification specification,
            NetworkSnapshot network, List<GeographicPoint> sourceGeographic,
            List<MetricPoint> sourceMetric, MetricRasterGrid outputGrid,
            String palette, double searchRadiusMeters, double sampleStepMeters,
            String settingsHash, String parameterHash, GeometryCleanupConfig cleanup, TrackerMode engine, String projectionCode) {
        public Captured {
            if ((raster == null) == (managedRaster == null)) {
                throw new IllegalArgumentException("Live preview capture requires exactly one source raster");
            }
            sourceGeographic = List.copyOf(sourceGeographic);
            sourceMetric = List.copyOf(sourceMetric);
            if (cleanup == null || engine == null || projectionCode == null || projectionCode.isBlank()) {
                throw new IllegalArgumentException("Live preview engine is required");
            }
        }
    }

    /** EDT-owned managed source and network snapshot, excluding credentials and acquired pixels. */
    public record ManagedCaptureSeed(NetworkSnapshotCapture.Specification specification,
            NetworkSnapshot network, List<GeographicPoint> sourceGeographic,
            List<MetricPoint> sourceMetric, LocalMetricFrame frame, String palette,
            double searchRadiusMeters, double sampleStepMeters, String settingsHash,
            String parameterHash, GeometryCleanupConfig cleanup, TrackerMode engine, String sourceIdentity, String projectionCode) {
        public ManagedCaptureSeed {
            sourceGeographic = List.copyOf(sourceGeographic);
            sourceMetric = List.copyOf(sourceMetric);
            if (specification == null || network == null || frame == null || sourceGeographic.size() < 2
                    || sourceMetric.size() != sourceGeographic.size() || palette == null || palette.isBlank()
                    || !Double.isFinite(searchRadiusMeters) || searchRadiusMeters <= 0.0
                    || !Double.isFinite(sampleStepMeters) || sampleStepMeters <= 0.0
                    || settingsHash == null || parameterHash == null || cleanup == null || engine == null
                    || sourceIdentity == null || sourceIdentity.isBlank()
                    || projectionCode == null || projectionCode.isBlank()) {
                throw new IllegalArgumentException("Managed preview seed is incomplete");
            }
        }
    }

    /** Detached result from the actual common modern final pipeline. */
    public record Computed(Captured captured, EvidenceSnapshot evidence, TraceRequest request,
            ModernTracePipeline.Result pipeline) { }

    /** Captures the bounded network and exact visible-render frame on the EDT without mutation. */
    public Captured capture(DataSet dataSet, SelectionContext selection,
            VisibleRaster raster, AlignmentConfig config) {
        return captureInternal(dataSet, selection, raster, config, false, null);
    }

    /** Captures a visible source explicitly selected for this read-only preview session. */
    public Captured capture(DataSet dataSet, SelectionContext selection,
            VisibleRaster raster, AlignmentConfig config, boolean explicitVisibleSource) {
        return captureInternal(dataSet, selection, raster, config, explicitVisibleSource, null);
    }

    /** Captures a visible source with explicit detached junction/edit permissions. */
    public Captured capture(DataSet dataSet, SelectionContext selection,
            VisibleRaster raster, AlignmentConfig config, boolean explicitVisibleSource,
            RecoveryPermissions permissions) {
        if (permissions == null) {
            throw new IllegalArgumentException("Live preview recovery permissions are required");
        }
        return captureInternal(dataSet, selection, raster, config, explicitVisibleSource,
                permissions);
    }

    private Captured captureInternal(DataSet dataSet, SelectionContext selection,
            VisibleRaster raster, AlignmentConfig config, boolean explicitVisibleSource,
            RecoveryPermissions requestedPermissions) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Live preview capture must execute on the EDT");
        }
        if (raster == null) {
            throw new IllegalArgumentException("Live preview raster is required");
        }
        requireSupported(selection, raster.projectionCode(), config, explicitVisibleSource);
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
        RecoveryPermissions permissions = requestedPermissions == null
                ? RecoveryPermissions.disabled(radius) : requestedPermissions;
        if (permissions.ordinaryRadiusMeters() > radius + 1.0e-9) {
            throw new IllegalArgumentException(
                    "Live preview recovery radius exceeds the captured decision corridor");
        }
        double step = config.heatmap().crossSectionStepPx() * raster.groundMetersPerViewPixel();
        MetricRegion decision = MetricCorridorRegion.aroundPolyline(metric, radius);
        if (!grid.footprint().containsRegion(decision)) {
            throw new IllegalArgumentException("Visible capture does not contain the complete B decision corridor");
        }
        PluginLog.verbose("Live B metric corridor accepted before worker: geometry=%s.",
                RasterEvidenceCapture.handoffFingerprint(grid, metric, radius));
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                selection.way().getUniqueId());
        OccurrenceRange range = new OccurrenceRange(selection.startIndex(), selection.endIndex());
        CaptureAuthority authority = captureAuthority(dataSet, selection, way, range, frame,
                decision, permissions);
        TrackerMode engine = config.heatmap().trackerMode();
        String settingsHash = hash(config.heatmap().toRedactedJson(), config.cleanup().toRedactedJson(),
                permissions.toString());
        String parameterHash = hash("live-" + engine.name().toLowerCase(java.util.Locale.ROOT) + "-v1");
        String snapshotId = "live-modern-network-" + hash(Long.toString(selection.way().getUniqueId()),
                source.toString(), settingsHash).substring(0, 16);
        NetworkSnapshotCapture.Specification specification = new NetworkSnapshotCapture.Specification(
                snapshotId, "josm-dataset-" + Integer.toUnsignedString(System.identityHashCode(dataSet)),
                0L, way, range, frame, authority.collisionEnvelope(), authority.editRegion(),
                authority.editableWayOccurrences(), authority.editableExistingKeys(),
                authority.movableNodes(), Set.of(), authority.protectedNodes(),
                true, permissions);
        NetworkSnapshot network = NetworkSnapshotCapture.capture(dataSet, specification);
        return new Captured(raster, null, specification, network, source, metric, grid,
                config.heatmap().color(), radius, step, settingsHash, parameterHash, config.cleanup(), engine,
                raster.projectionCode());
    }

    private static CaptureAuthority captureAuthority(DataSet dataSet, SelectionContext selection,
            PrimitiveKey selectedWay, OccurrenceRange selectedRange, LocalMetricFrame frame,
            MetricRegion decision, RecoveryPermissions permissions) {
        Map<PrimitiveKey, List<OccurrenceRange>> occurrences = new LinkedHashMap<>();
        occurrences.put(selectedWay, List.of(selectedRange));
        Set<PrimitiveKey> editable = new LinkedHashSet<>();
        editable.add(selectedWay);
        Set<PrimitiveKey> movable = new LinkedHashSet<>();
        Set<PrimitiveKey> protectedNodes = new LinkedHashSet<>();
        selection.segmentNodes().stream()
                .map(LiveBPreviewService::key).forEach(protectedNodes::add);
        List<List<MetricPoint>> editPolygons = new ArrayList<>(decision.polygons());
        List<List<MetricPoint>> collisionPolygons = new ArrayList<>(decision.polygons());
        if (permissions.junctionPolicy() != JunctionPolicy.FIXED) {
            List<Node> boundaries = List.of(selection.segmentNodes().get(0),
                    selection.segmentNodes().get(selection.segmentNodes().size() - 1));
            for (Node boundary : boundaries) {
                List<Way> incidentWays = dataSet.getWays().stream()
                        .filter(incident -> !incident.isDeleted() && !incident.isIncomplete()
                                && incident.getNodes().contains(boundary))
                        .toList();
                boolean reattachableJunction = incidentWays.size() > 1;
                if (permissions.junctionPolicy() == JunctionPolicy.REATTACH
                        && !reattachableJunction) {
                    continue;
                }
                PrimitiveKey boundaryKey = key(boundary);
                movable.add(boundaryKey);
                editable.add(boundaryKey);
                protectedNodes.remove(boundaryKey);
                for (Way incident : incidentWays) {
                    PrimitiveKey incidentKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                            incident.getUniqueId());
                    editable.add(incidentKey);
                    if (!incidentKey.equals(selectedWay)) {
                        List<OccurrenceRange> localGeometry = List.of(
                                JunctionAuthorityBounds.localOccurrenceRange(
                                        incident, boundary, frame));
                        List<OccurrenceRange> authorized = permissions.junctionPolicy()
                                == JunctionPolicy.REATTACH ? localGeometry
                                : occurrenceRanges(incident, boundary);
                        occurrences.merge(incidentKey, authorized,
                                JunctionAuthorityBounds::mergeOccurrenceRanges);
                        for (OccurrenceRange local : localGeometry) {
                            if (permissions.reconstructIncidentWays()) {
                                for (int index = local.firstIndex(); index <= local.lastIndex(); index++) {
                                    Node localNode = incident.getNode(index);
                                    PrimitiveKey localKey = key(localNode);
                                    boolean receiverShapeNode = index > local.firstIndex()
                                            && index < local.lastIndex()
                                            && localNode != boundary
                                            && !localNode.hasKeys()
                                            && localNode.getReferrers().stream()
                                                .allMatch(referrer -> referrer == incident);
                                    if (receiverShapeNode) {
                                        movable.add(localKey);
                                        editable.add(localKey);
                                        protectedNodes.remove(localKey);
                                    }
                                }
                            }
                            List<MetricPoint> incidentMetric = incident.getNodes().subList(
                                    local.firstIndex(), local.lastIndex() + 1).stream()
                                    .map(LiveBPreviewService::geographic).map(frame::toMetric).toList();
                            MetricRegion localRegion = incidentMetric.size() == 1
                                    ? aroundPoint(incidentMetric.get(0),
                                            MAXIMUM_JUNCTION_RELOCATION_METERS)
                                    : MetricCorridorRegion.aroundPolyline(incidentMetric,
                                            MAXIMUM_JUNCTION_RELOCATION_METERS);
                            editPolygons.addAll(localRegion.polygons());
                            collisionPolygons.addAll(localRegion.polygons());
                        }
                    }
                }
            }
        }
        return new CaptureAuthority(Map.copyOf(occurrences), Set.copyOf(editable),
                Set.copyOf(movable), Set.copyOf(protectedNodes),
                new MetricRegion(collisionPolygons), new MetricRegion(editPolygons));
    }

    private static MetricRegion aroundPoint(MetricPoint point, double radius) {
        return MetricRegion.rectangle(point.xMeters() - radius, point.yMeters() - radius,
                point.xMeters() + radius, point.yMeters() + radius);
    }

    private static List<OccurrenceRange> occurrenceRanges(Way way, Node node) {
        List<OccurrenceRange> result = new ArrayList<>();
        for (int index = 0; index < way.getNodesCount(); index++) {
            if (way.getNode(index) == node) {
                result.add(new OccurrenceRange(index, index));
            }
        }
        return List.copyOf(result);
    }

    private static PrimitiveKey key(Node node) {
        return PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId());
    }

    private record CaptureAuthority(Map<PrimitiveKey, List<OccurrenceRange>> editableWayOccurrences,
            Set<PrimitiveKey> editableExistingKeys, Set<PrimitiveKey> movableNodes,
            Set<PrimitiveKey> protectedNodes, MetricRegion collisionEnvelope,
            MetricRegion editRegion) { }

    /** Captures the detached managed source/network seed on the EDT before background acquisition. */
    public ManagedCaptureSeed captureManagedSeed(DataSet dataSet, SelectionContext selection,
            AlignmentConfig config, String sourceIdentity) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Managed preview seed capture must execute on the EDT");
        }
        requireManagedSupported(selection, config);
        List<GeographicPoint> source = selection.segmentNodes().stream()
                .map(LiveBPreviewService::geographic).toList();
        LocalMetricFrame frame = ManagedModernPreviewSource.managedFrame(source,
                config.heatmap().inferenceZoom(), config.heatmap().searchHalfWidthMeters());
        List<MetricPoint> metric = source.stream().map(frame::toMetric).toList();
        double radius = config.heatmap().searchHalfWidthMeters();
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, selection.way().getUniqueId());
        OccurrenceRange range = new OccurrenceRange(selection.startIndex(), selection.endIndex());
        Set<PrimitiveKey> protectedNodes = new LinkedHashSet<>();
        for (Node node : selection.segmentNodes()) {
            protectedNodes.add(PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId()));
        }
        MetricRegion decision = MetricCorridorRegion.aroundPolyline(metric, radius);
        String settingsHash = hash(config.heatmap().toRedactedJson(), config.cleanup().toRedactedJson());
        String snapshotId = "live-managed-network-" + hash(Long.toString(selection.way().getUniqueId()),
                source.toString(), settingsHash).substring(0, 16);
        NetworkSnapshotCapture.Specification specification = new NetworkSnapshotCapture.Specification(snapshotId,
                "josm-dataset-" + Integer.toUnsignedString(System.identityHashCode(dataSet)), 0L, way, range,
                frame, decision, decision, Map.of(way, List.of(range)), Set.of(way), Set.of(), Set.of(),
                protectedNodes, true, RecoveryPermissions.disabled(radius));
        return new ManagedCaptureSeed(specification, NetworkSnapshotCapture.capture(dataSet, specification),
                source, metric, frame, config.heatmap().color(), radius,
                config.heatmap().sampleStepMeters(), settingsHash,
                hash("managed-live-" + config.heatmap().trackerMode().name().toLowerCase(java.util.Locale.ROOT)),
                config.cleanup(), config.heatmap().trackerMode(), sourceIdentity,
                ProjectionRegistry.getProjection().toCode());
    }

    /** Attaches immutable native pixels to an EDT-captured seed without retaining credentials. */
    public Captured attachManagedRaster(ManagedCaptureSeed seed, ManagedModernPreviewSource.Raster raster) {
        if (seed == null || raster == null || !seed.palette().equals(raster.palette())
                || !seed.sourceIdentity().equals(raster.sourceIdentity())) {
            throw new IllegalArgumentException("Managed raster does not match its captured seed");
        }
        MetricRasterGrid grid = ManagedModernPreviewSource.managedOutputGrid(seed.frame(),
                seed.sourceMetric(), raster.zoom(), seed.searchRadiusMeters());
        return new Captured(null, raster, seed.specification(), seed.network(), seed.sourceGeographic(),
                seed.sourceMetric(), grid, seed.palette(), seed.searchRadiusMeters(),
                seed.sampleStepMeters(), seed.settingsHash(), seed.parameterHash(), seed.cleanup(), seed.engine(),
                seed.projectionCode());
    }

    /** Runs RasterEvidenceCapture, production B, and common final processing off the EDT. */
    public Computed compute(Captured captured, CancellationProbe cancellation) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Live preview inference must execute off the EDT");
        }
        if (captured == null || cancellation == null) {
            throw new IllegalArgumentException("Live preview computation is incomplete");
        }
        EvidenceSnapshot evidence = captureEvidence(captured, cancellation);
        ProfileChainage chainage = new ProbabilisticProfileFactory().profileChainage(
                captured.sourceMetric(), captured.sampleStepMeters());
        Optional<CorridorTraceInput> corridorInput =
                (captured.engine() == TrackerMode.CORRIDOR_AWARE
                        || captured.engine() == TrackerMode.HYBRID)
                ? Optional.of(CorridorTraceInput.from(chainage, captured.sourceMetric(),
                        evidence.coordinateFrame(), evidence.transform(),
                        evidence.resolution().outputRasterPitchMeters()))
                : Optional.empty();
        TraceRequest request = new TraceRequest(captured.specification().selectedWayKey(),
                captured.specification().selectedRange(), captured.engine(),
                AlignmentMode.PRECISE_SHAPE, captured.specification().permissions(),
                captured.engine() == TrackerMode.HYBRID ? TraceBudgets.fundedHybrid()
                        : TraceBudgets.defaults(), evidence.snapshotId(), evidence.canonicalHash(),
                captured.network().snapshotId(), captured.network().canonicalHash(),
                captured.settingsHash(), captured.parameterHash(), "visible-"
                        + captured.engine().name().toLowerCase(java.util.Locale.ROOT) + "-v1",
                captured.sampleStepMeters(), chainage, evidence.resolution(), corridorInput);
        ModernTracePipeline.Result pipeline = new ModernTracePipeline(new CorridorEngineAdapter(FIELD))
                .run(request, evidence, captured.network(),
                        new ModernTracePipeline.Options(FIELD,
                                captured.cleanup(),
                                "selected-visible", 0), cancellation);
        return new Computed(captured, evidence, request, pipeline);
    }

    EvidenceSnapshot captureEvidence(Captured captured, CancellationProbe cancellation) {
        if (captured.managedRaster() != null) {
            return captureManagedEvidence(captured, cancellation);
        }
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
        return new RasterEvidenceCapture().captureWithMetricSource(
                captured.network().snapshotId() + "-evidence", captured.raster().image(), valid,
                captured.sourceGeographic(), captured.sourceMetric(), transform, captured.outputGrid(), resolution,
                captured.searchRadiusMeters(), captured.raster().sourceIdentity(),
                EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER, List.of(field), cancellation);
    }

    private EvidenceSnapshot captureManagedEvidence(Captured captured, CancellationProbe cancellation) {
        ManagedModernPreviewSource.Raster raster = captured.managedRaster();
        double pitch = TileHeatmapSampler.metersPerPixel(raster.zoom(),
                captured.sourceGeographic().get(captured.sourceGeographic().size() / 2).latitudeDegrees());
        EvidenceResolution resolution = EvidenceResolution.nativeSource(pitch,
                captured.outputGrid().pitchMeters());
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING,
                captured.palette(), EvidenceCorrelationGroup.STRAVA_RENDERINGS, false);
        RasterEvidenceCapture.FieldSpec field = RasterEvidenceCapture.FieldSpec.direct(FIELD,
                argb -> intensity(argb, captured.palette()), lineage);
        return new RasterEvidenceCapture().captureWithMetricSource(captured.network().snapshotId() + "-evidence",
                raster.image(), raster.validity(), captured.sourceGeographic(), captured.sourceMetric(), raster.transform(),
                captured.outputGrid(), resolution, captured.searchRadiusMeters(), raster.sourceIdentity(),
                EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE, List.of(field), cancellation);
    }

    /** Repeats the exact bounded live query and rejects any relevant source or referrer change. */
    public void requireCurrent(DataSet dataSet, Captured captured) {
        if (captured.managedRaster() != null) {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("Live preview revalidation must execute on the EDT");
            }
            if (!captured.projectionCode().equals(ProjectionRegistry.getProjection().toCode())) {
                throw new IllegalStateException("Live preview source, layer, or projection is stale");
            }
            NetworkSnapshot current = NetworkSnapshotCapture.capture(dataSet, captured.specification());
            if (!current.canonicalHash().equals(captured.network().canonicalHash())) {
                throw new IllegalStateException("Live preview network snapshot is stale");
            }
            return;
        }
        requireCurrent(dataSet, captured, captured.raster().sourceIdentity(),
                captured.raster().projectionCode());
    }

    /** Repeats the same visible frame and rejects changed scalar evidence before use. */
    public void requireCurrent(DataSet dataSet, Captured captured, VisibleRaster currentRaster) {
        if (captured == null || captured.managedRaster() != null || currentRaster == null) {
            throw new IllegalStateException("Live preview visible source is stale");
        }
        VisibleRaster capturedRaster = captured.raster();
        if (!sameVisibleFrame(capturedRaster, currentRaster)) {
            throw new IllegalStateException("Live preview visible source frame is stale");
        }
        requireCurrent(dataSet, captured, currentRaster.sourceIdentity(), currentRaster.projectionCode());
        Captured refreshed = new Captured(currentRaster, null, captured.specification(), captured.network(),
                captured.sourceGeographic(), captured.sourceMetric(), captured.outputGrid(), captured.palette(),
                captured.searchRadiusMeters(), captured.sampleStepMeters(), captured.settingsHash(),
                captured.parameterHash(), captured.cleanup(), captured.engine(), captured.projectionCode());
        if (!captureEvidence(captured, CancellationProbe.NONE).canonicalHash().equals(
                captureEvidence(refreshed, CancellationProbe.NONE).canonicalHash())) {
            throw new IllegalStateException("Live preview visible evidence is stale");
        }
    }

    private static boolean sameVisibleFrame(VisibleRaster captured, VisibleRaster current) {
        return captured.width() == current.width() && captured.height() == current.height()
                && Double.compare(captured.minimumEast(), current.minimumEast()) == 0
                && Double.compare(captured.minimumNorth(), current.minimumNorth()) == 0
                && Double.compare(captured.maximumEast(), current.maximumEast()) == 0
                && Double.compare(captured.maximumNorth(), current.maximumNorth()) == 0
                && Double.compare(captured.projectionUnitsPerViewPixel(),
                        current.projectionUnitsPerViewPixel()) == 0
                && Double.compare(captured.groundMetersPerViewPixel(),
                        current.groundMetersPerViewPixel()) == 0
                && captured.nativePitchMeters().equals(current.nativePitchMeters())
                && captured.sourceIdentity().equals(current.sourceIdentity())
                && captured.projectionCode().equals(current.projectionCode());
    }

    /** Revalidates network, layer identity, and projection before publication or candidate switch. */
    public void requireCurrent(DataSet dataSet, Captured captured,
            String sourceIdentity, String projectionCode) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Live preview revalidation must execute on the EDT");
        }
        if (dataSet == null || captured == null
                || !captured.raster().sourceIdentity().equals(sourceIdentity)
                || !captured.raster().projectionCode().equals(projectionCode)) {
            throw new IllegalStateException("Live preview source, layer, or projection is stale");
        }
        NetworkSnapshot current = NetworkSnapshotCapture.capture(dataSet, captured.specification());
        if (!current.canonicalHash().equals(captured.network().canonicalHash())) {
            throw new IllegalStateException("Live preview network snapshot is stale");
        }
    }

    /** Converts final common-pipeline routes into the existing overlay candidate contract. */
    public List<CenterlineCandidate> adapt(Computed computed,
            Function<GeographicPoint, EastNorth> projector) {
        return new ModernCandidateAdapter().adaptRoutes(computed.pipeline().routes(),
                computed.request().engine(), computed.evidence(), FIELD,
                computed.captured().sourceMetric(), projector);
    }

    /** Rejects unsupported live settings before any raster or network acquisition. */
    public static void requireSupported(SelectionContext selection, String projectionCode,
            AlignmentConfig config) {
        requireSupported(selection, projectionCode, config, false);
    }

    /**
     * Rejects unsupported live settings before acquisition, allowing managed credentials only when a
     * caller explicitly selected the rendered visible source for this one read-only session.
     */
    public static void requireSupported(SelectionContext selection, String projectionCode,
            AlignmentConfig config, boolean explicitVisibleSource) {
        if (selection == null || selection.segmentNodes() == null || selection.segmentNodes().size() < 2
                || projectionCode == null || config == null) {
            throw new IllegalArgumentException("Live preview inputs are incomplete");
        }
        var heatmap = config.heatmap();
        if (heatmap.trackerMode() != TrackerMode.PROBABILISTIC
                && heatmap.trackerMode() != TrackerMode.CORRIDOR_AWARE
                && heatmap.trackerMode() != TrackerMode.HYBRID
                && heatmap.trackerMode() != TrackerMode.DIRECTIONAL_IMAGE) {
            throw new IllegalArgumentException("Experimental live preview supports only Corridor-aware A, "
                    + "Probabilistic B, visible Hybrid A+B, or visible Directional Image");
        }
        if (heatmap.trackerMode() == TrackerMode.DIRECTIONAL_IMAGE && !explicitVisibleSource) {
            throw new IllegalArgumentException("Directional Image preview is visible-source only");
        }
        if (heatmap.alignmentMode() != AlignmentMode.PRECISE_SHAPE) {
            throw new IllegalArgumentException("Experimental live preview requires Precise Shape");
        }
        if (heatmap.simplifyEnabled()) {
            throw new IllegalArgumentException("Experimental live preview requires simplification Off");
        }
        if ((heatmap.hasManagedAccessValues() && !explicitVisibleSource)
                || heatmap.multiColorDetection() || heatmap.aggregateAllColorSchemes()
                || heatmap.parallelWayAwareness()
                || heatmap.adjustJunctionNodes() || config.searchHalfWidthMetersOverride().isPresent()) {
            throw new IllegalArgumentException("Experimental live preview does not support managed acquisition, expanded, or junction options");
        }
        if (heatmap.intensitySamplingMode() != IntensitySamplingMode.COLOR_MAPPING) {
            throw new IllegalArgumentException("Experimental live preview supports Color mapping only");
        }
        if (!"EPSG:3857".equals(projectionCode)) {
            throw new IllegalArgumentException("Experimental live preview requires EPSG:3857");
        }
        Set<Long> identities = new LinkedHashSet<>();
        for (Node node : selection.way().getNodes()) {
            if (!identities.add(node.getUniqueId())) {
                throw new IllegalArgumentException("Experimental live preview rejects repeated node identities");
            }
        }
    }

    private static void requireManagedSupported(SelectionContext selection, AlignmentConfig config) {
        if (selection == null || config == null || !config.heatmap().hasManagedAccessValues()) {
            throw new IllegalArgumentException("Managed preview requires configured managed source access");
        }
        var heatmap = config.heatmap();
        if (heatmap.trackerMode() != TrackerMode.CORRIDOR_AWARE
                && heatmap.trackerMode() != TrackerMode.PROBABILISTIC
                || heatmap.alignmentMode() != AlignmentMode.PRECISE_SHAPE
                || heatmap.simplifyEnabled()
                || heatmap.multiColorDetection() || heatmap.aggregateAllColorSchemes()
                || heatmap.parallelWayAwareness() || heatmap.adjustJunctionNodes()
                || config.searchHalfWidthMetersOverride().isPresent()
                || heatmap.intensitySamplingMode() != IntensitySamplingMode.COLOR_MAPPING) {
            throw new IllegalArgumentException("Managed experimental preview supports selected-palette Precise Shape only");
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
