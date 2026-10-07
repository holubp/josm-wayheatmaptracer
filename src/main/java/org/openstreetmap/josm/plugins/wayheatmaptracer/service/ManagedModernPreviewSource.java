package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRasterGrid;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CancellationToken;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileAddress;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileCachePolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TilePurpose;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileRequest;

/** Builds detached same-frame managed palette rasters through the shared coordinator. */
public final class ManagedModernPreviewSource {
    private static final List<String> BASE_PALETTES = List.of("hot", "blue", "bluered", "purple", "gray");
    public static final String UNSUPPORTED_COLOR_OPTIONS = "Modern managed tracing cannot yet use "
            + "'Run alternative detector mappings on current source' or "
            + "'Aggregate all managed color schemes into one intensity map'. "
            + "Turn both off in Heatmap settings to trace the selected palette.";
    static final int TILE_SIZE = 512;
    private static final long MAX_INPUT_PIXELS = 16_777_216L;
    private final TileFetchCoordinator coordinator;

    public record Request(List<GeographicPoint> source, String activity, String palette, int zoom,
            ManagedTileGeneration generation, double searchRadiusMeters, double sampleStepMeters,
            String sourceIdentity, List<String> requiredPalettes) {
        public Request(List<GeographicPoint> source, String activity, String palette, int zoom,
                ManagedTileGeneration generation, double searchRadiusMeters, double sampleStepMeters,
                String sourceIdentity) {
            this(source, activity, palette, zoom, generation, searchRadiusMeters,
                    sampleStepMeters, sourceIdentity, List.of(palette));
        }
        public Request {
            source = List.copyOf(source);
            requiredPalettes = List.copyOf(requiredPalettes);
            if (source.size() < 2 || activity == null || activity.isBlank() || palette == null
                    || palette.isBlank() || zoom < 0 || zoom > 22 || generation == null
                    || !Double.isFinite(searchRadiusMeters) || searchRadiusMeters <= 0.0
                    || !Double.isFinite(sampleStepMeters) || sampleStepMeters <= 0.0
                    || sourceIdentity == null || sourceIdentity.isBlank()
                    || requiredPalettes.isEmpty() || !palette.equals(requiredPalettes.get(0))
                    || new LinkedHashSet<>(requiredPalettes).size() != requiredPalettes.size()
                    || requiredPalettes.stream().anyMatch(value -> value == null || value.isBlank())
                    || requiredPalettes.size() != 1
                            && (!new LinkedHashSet<>(requiredPalettes).equals(
                                    new LinkedHashSet<>(BASE_PALETTES))
                                    || requiredPalettes.size() != BASE_PALETTES.size())) {
                throw new IllegalArgumentException("Managed preview request is incomplete");
            }
        }
    }

    /** One native color raster and its copied support mask. */
    public record PaletteRaster(BufferedImage image, boolean[] validity) {
        public PaletteRaster {
            if (image == null || validity == null
                    || validity.length != Math.multiplyExact(image.getWidth(), image.getHeight())) {
                throw new IllegalArgumentException("Managed palette raster is incomplete");
            }
            validity = validity.clone();
        }
        @Override public boolean[] validity() { return validity.clone(); }
    }

    public enum AggregateAvailability {
        NOT_REQUESTED, COMPLETE, PALETTE_UNAVAILABLE, BUDGET_UNAVAILABLE
    }

    /** Safe reason a requested aggregate could not acquire every native palette. */
    public record AggregateFailure(String palette, TileFetchStatus status) {
        public AggregateFailure {
            if (!BASE_PALETTES.contains(palette) || status == null || status.usable()) {
                throw new IllegalArgumentException("Aggregate failure reason is incomplete");
            }
        }
    }

    /** Opaque receipt tied to the coordinator and exact raster objects it acquired. */
    public static final class AcquisitionProof {
        private final TileFetchCoordinator owner;
        private final Map<String, Raster> rasters;
        private final AlignmentTileSourcePlan plan;
        private final byte[] rasterFingerprint;
        private AcquisitionProof(TileFetchCoordinator owner, Map<String, Raster> rasters,
                AlignmentTileSourcePlan plan) {
            this.owner = owner;
            this.rasters = Map.copyOf(rasters);
            this.plan = plan;
            this.rasterFingerprint = SourceRasters.fingerprint(rasters);
        }
        private boolean matches(Map<String, Raster> candidate, AlignmentTileSourcePlan candidatePlan) {
            return matchesReferences(candidate, candidatePlan)
                    && MessageDigest.isEqual(rasterFingerprint, SourceRasters.fingerprint(candidate));
        }
        private boolean matchesReferences(Map<String, Raster> candidate,
                AlignmentTileSourcePlan candidatePlan) {
            if (plan != candidatePlan || !rasters.keySet().equals(candidate.keySet())) return false;
            for (String palette : rasters.keySet()) {
                if (rasters.get(palette) != candidate.get(palette)) return false;
            }
            return true;
        }
    }

    public record Raster(BufferedImage image, boolean[] validity,
            SupportedInputRasterTransform transform, String palette, int zoom,
            String sourceIdentity, ManagedTileGeneration generation) {
        public Raster {
            if (image == null || validity == null || validity.length != image.getWidth() * image.getHeight()
                    || transform == null || palette == null || palette.isBlank() || zoom < 0
                    || sourceIdentity == null || sourceIdentity.isBlank() || generation == null) {
                throw new IllegalArgumentException("Managed preview raster is incomplete");
            }
            validity = validity.clone();
        }
        @Override public boolean[] validity() { return validity.clone(); }

        /** Maps this one native palette raster before any geometry extraction. */
        public double[] scalarValues(String mapping, IntensitySamplingMode samplingMode,
                CancellationProbe cancellation) {
            Objects.requireNonNull(cancellation, "cancellation");
            IntensitySamplingMode effective = samplingMode == null
                    ? IntensitySamplingMode.COLOR_MAPPING : samplingMode;
            if (effective.usesColorMapping() && (mapping == null || mapping.isBlank())) {
                throw new IllegalArgumentException("Color mapping name is required");
            }
            int count = Math.multiplyExact(image.getWidth(), image.getHeight());
            if (count > MAX_INPUT_PIXELS) {
                throw new IllegalArgumentException("Scalar mapping exceeds the input-pixel cap");
            }
            double[] values = new double[count];
            for (int y = 0; y < image.getHeight(); y++) {
                cancellation.checkpoint();
                for (int x = 0; x < image.getWidth(); x++) {
                    int index = y * image.getWidth() + x;
                    if (!validity[index]) continue;
                    int argb = image.getRGB(x, y);
                    int alpha = argb >>> 24 & 0xff;
                    if (alpha == 0) continue;
                    int red = argb >>> 16 & 0xff;
                    int green = argb >>> 8 & 0xff;
                    int blue = argb & 0xff;
                    values[index] = effective.usesColorMapping()
                            ? RenderedHeatmapSampler.colorIntensity(red, green, blue, mapping)
                            : RenderedHeatmapSampler.directIntensity(red, green, blue, alpha, effective);
                }
            }
            return values;
        }
    }

    /** Immutable same-frame palette set returned from one coordinator acquisition. */
    public static final class SourceRasters {
        private final Map<String, Raster> palettes;
        private final AlignmentTileSourcePlan plan;
        private final AggregateAvailability aggregateAvailability;
        private final AggregateFailure aggregateFailure;
        private final AcquisitionProof proof;

        /** Creates a non-authoritative raster set; only coordinator acquisition can prove an aggregate. */
        public SourceRasters(Map<String, Raster> palettes, AlignmentTileSourcePlan plan) {
            this(palettes, plan, AggregateAvailability.NOT_REQUESTED, null, null);
        }

        private SourceRasters(Map<String, Raster> palettes, AlignmentTileSourcePlan plan,
                AggregateAvailability aggregateAvailability, AggregateFailure aggregateFailure,
                AcquisitionProof proof) {
            if (palettes == null || plan == null || aggregateAvailability == null
                    || (aggregateAvailability == AggregateAvailability.PALETTE_UNAVAILABLE)
                            != (aggregateFailure != null)
                    || aggregateAvailability != AggregateAvailability.NOT_REQUESTED
                            && !plan.aggregateDetectorRequested()
                    || !palettes.containsKey(plan.selectedColor())
                    || aggregateAvailability == AggregateAvailability.COMPLETE
                            && (!palettes.keySet().equals(new LinkedHashSet<>(plan.orderedColors()))
                                    || proof == null || !proof.matchesReferences(palettes, plan))
                    || (aggregateAvailability == AggregateAvailability.PALETTE_UNAVAILABLE
                            || aggregateAvailability == AggregateAvailability.BUDGET_UNAVAILABLE)
                            && palettes.size() != 1) {
                throw new IllegalArgumentException("Managed source raster set is incomplete");
            }
            Raster selected = palettes.get(plan.selectedColor());
            for (Map.Entry<String, Raster> entry : palettes.entrySet()) {
                Raster raster = entry.getValue();
                if (!entry.getKey().equals(raster.palette())
                        || !selected.transform().equals(raster.transform())
                        || selected.image().getWidth() != raster.image().getWidth()
                        || selected.image().getHeight() != raster.image().getHeight()
                        || selected.zoom() != raster.zoom()
                        || !selected.generation().equals(raster.generation())
                        || !selected.sourceIdentity().equals(raster.sourceIdentity())
                        || aggregateAvailability == AggregateAvailability.COMPLETE
                                && !allValid(raster.validity())) {
                    throw new IllegalArgumentException("Managed palette rasters do not share one source frame");
                }
            }
            this.palettes = Collections.unmodifiableMap(new LinkedHashMap<>(palettes));
            this.plan = plan;
            this.aggregateAvailability = aggregateAvailability;
            this.aggregateFailure = aggregateFailure;
            this.proof = proof;
        }

        public Map<String, Raster> palettes() { return palettes; }
        public AlignmentTileSourcePlan plan() { return plan; }
        public AggregateAvailability aggregateAvailability() { return aggregateAvailability; }
        public AggregateFailure aggregateFailure() { return aggregateFailure; }
        public Raster selectedRaster() { return palettes.get(plan.selectedColor()); }
        public boolean provenCompleteAggregate(TileFetchCoordinator owner) {
            return aggregateAvailability == AggregateAvailability.COMPLETE
                    && proof != null && proof.owner == owner && proof.matches(palettes, plan);
        }
        /** Converts every required native palette and fuses their normalized scalar values. */
        public double[] completeAggregateScalars(CancellationProbe cancellation) {
            Objects.requireNonNull(cancellation, "cancellation");
            if (aggregateAvailability != AggregateAvailability.COMPLETE
                    || proof == null || !proof.matches(palettes, plan)) {
                throw new IllegalStateException("Complete managed all-color evidence is unavailable");
            }
            Raster selected = selectedRaster();
            Map<String, BufferedImage> images = new LinkedHashMap<>();
            palettes.forEach((palette, raster) -> images.put(palette, raster.image()));
            double[] values = new double[Math.multiplyExact(selected.image().getWidth(),
                    selected.image().getHeight())];
            for (int y = 0; y < selected.image().getHeight(); y++) {
                cancellation.checkpoint();
                for (int x = 0; x < selected.image().getWidth(); x++) {
                    values[y * selected.image().getWidth() + x] =
                            RenderedHeatmapSampler.aggregatedSourceIntensityAt(images, x, y);
                }
            }
            return values;
        }

        private static byte[] fingerprint(Map<String, Raster> rasters) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (String palette : rasters.keySet().stream().sorted().toList()) {
                    Raster raster = rasters.get(palette);
                    digest.update(palette.getBytes(StandardCharsets.US_ASCII));
                    BufferedImage image = raster.image();
                    int[] row = new int[image.getWidth()];
                    ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(image.getWidth(), 4));
                    boolean[] support = raster.validity();
                    for (int y = 0; y < image.getHeight(); y++) {
                        image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
                        bytes.clear();
                        for (int argb : row) bytes.putInt(argb);
                        digest.update(bytes.array());
                        int start = y * image.getWidth();
                        for (int x = 0; x < image.getWidth(); x++) {
                            digest.update(support[start + x] ? (byte) 1 : (byte) 0);
                        }
                    }
                }
                return digest.digest();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }

    /**
     * Creates a compatibility request that acquires only the selected native palette.
     */
    public static Request selectedOnly(List<GeographicPoint> source, ManagedHeatmapConfig config,
            String sourceIdentity) {
        Objects.requireNonNull(config, "config");
        AlignmentTileSourcePlan plan = AlignmentTileSourcePlan.from(config);
        return new Request(source, config.activity(), plan.selectedColor(), config.inferenceZoom(),
                new ManagedTileGeneration(Math.max(0L, config.cacheBuster())),
                config.searchHalfWidthMeters(), config.sampleStepMeters(), sourceIdentity);
    }

    /** Creates a source plan for the exact selected and optional aggregate palettes. */
    public static Request plannedColors(List<GeographicPoint> source, ManagedHeatmapConfig config,
            String sourceIdentity) {
        Objects.requireNonNull(config, "config");
        AlignmentTileSourcePlan plan = AlignmentTileSourcePlan.from(config);
        return new Request(source, config.activity(), plan.selectedColor(), config.inferenceZoom(),
                new ManagedTileGeneration(Math.max(0L, config.cacheBuster())),
                config.searchHalfWidthMeters(), config.sampleStepMeters(), sourceIdentity,
                plan.orderedColors());
    }

    public ManagedModernPreviewSource(TileFetchCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Acquires the planned palette set off the EDT and returns the selected native raster. */
    public Raster acquire(Request request, CredentialSnapshot credentials,
            CancellationProbe cancellation) {
        AlignmentTileSourcePlan plan = planForRequest(request);
        return acquireSources(request, plan, credentials, cancellation).selectedRaster();
    }

    /** Acquires the exact planned palette set through one coordinator generation and raster frame. */
    public SourceRasters acquireSources(List<GeographicPoint> source, ManagedHeatmapConfig config,
            String sourceIdentity, CredentialSnapshot credentials, CancellationProbe cancellation) {
        Objects.requireNonNull(config, "config");
        AlignmentTileSourcePlan plan = AlignmentTileSourcePlan.from(config);
        return acquireSources(plannedColors(source, config, sourceIdentity), plan,
                credentials, cancellation);
    }

    private SourceRasters acquireSources(Request request, CredentialSnapshot credentials,
            CancellationProbe cancellation) {
        return acquireSources(request, planForRequest(request), credentials, cancellation);
    }

    private SourceRasters acquireSources(Request request, AlignmentTileSourcePlan plan,
            CredentialSnapshot credentials, CancellationProbe cancellation) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Managed preview acquisition must execute off the EDT");
        }
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.checkpoint();
        requireActiveGeneration(request);
        TileBounds bounds = tileBounds(request);
        int width = Math.multiplyExact(bounds.width(), TILE_SIZE);
        int height = Math.multiplyExact(bounds.height(), TILE_SIZE);
        long selectedPixels = (long) width * height;
        if (selectedPixels > MAX_INPUT_PIXELS) {
            throw new IllegalArgumentException("Managed preview capture exceeds the input-pixel cap");
        }
        long plannedPixels = Math.multiplyExact(selectedPixels, request.requiredPalettes().size());
        boolean aggregateBudgetAvailable = request.requiredPalettes().size() == 1
                || plannedPixels <= MAX_INPUT_PIXELS;
        Map<String, Raster> rasters = new LinkedHashMap<>();
        PaletteAcquisition selectedAcquisition = acquirePalette(request, bounds, width, height,
                request.palette(), credentials, cancellation, false);
        SupportedInputRasterTransform transform = rasterTransform(request, bounds);
        Raster selected = toRaster(selectedAcquisition.raster(), transform, request,
                request.palette());
        rasters.put(request.palette(), selected);
        AggregateAvailability availability = request.requiredPalettes().size() == 1
                ? AggregateAvailability.NOT_REQUESTED
                : AggregateAvailability.BUDGET_UNAVAILABLE;
        AggregateFailure failure = null;
        boolean aggregateComplete = false;
        if (request.requiredPalettes().size() > 1 && aggregateBudgetAvailable) {
            if (!allValid(selectedAcquisition.raster().validity())) {
                availability = AggregateAvailability.PALETTE_UNAVAILABLE;
                failure = new AggregateFailure(request.palette(), selectedAcquisition.failure());
            } else {
                availability = AggregateAvailability.COMPLETE;
                aggregateComplete = true;
                for (String palette : request.requiredPalettes()) {
                    if (palette.equals(request.palette())) continue;
                    cancellation.checkpoint();
                    requireActiveGeneration(request);
                    PaletteAcquisition acquired = acquirePalette(request, bounds, width, height,
                            palette, credentials, cancellation, true);
                    if (acquired.failure() != null) {
                        availability = AggregateAvailability.PALETTE_UNAVAILABLE;
                        failure = new AggregateFailure(palette, acquired.failure());
                        aggregateComplete = false;
                        rasters.clear();
                        rasters.put(request.palette(), selected);
                        break;
                    }
                    rasters.put(palette, toRaster(acquired.raster(), transform, request, palette));
                }
            }
        }
        requireActiveGeneration(request);
        AcquisitionProof proof = aggregateComplete ? new AcquisitionProof(coordinator, rasters, plan) : null;
        return new SourceRasters(rasters, plan, availability, failure, proof);
    }

    private static AlignmentTileSourcePlan planForRequest(Request request) {
        if (request == null) throw new NullPointerException("request");
        LinkedHashSet<String> required = new LinkedHashSet<>(List.of(request.palette()));
        LinkedHashSet<String> optional = new LinkedHashSet<>(request.requiredPalettes());
        optional.remove(request.palette());
        return new AlignmentTileSourcePlan(request.palette(),
                Collections.unmodifiableSet(required), Collections.unmodifiableSet(optional),
                Collections.unmodifiableSet(new LinkedHashSet<>(List.of(request.zoom()))),
                request.requiredPalettes().size() > 1);
    }

    private static Raster toRaster(PaletteRaster raster, SupportedInputRasterTransform transform,
            Request request, String palette) {
        return new Raster(raster.image(), raster.validity(), transform, palette, request.zoom(),
                request.sourceIdentity(), request.generation());
    }

    private static SupportedInputRasterTransform rasterTransform(Request request, TileBounds bounds) {
        double originX = bounds.minimumX() * (double) TILE_SIZE;
        double originY = bounds.minimumY() * (double) TILE_SIZE;
        return SupportedInputRasterTransform.webMercator(request.zoom(),
                originX / 2.0, originY / 2.0, 2.0);
    }

    private record PaletteAcquisition(PaletteRaster raster, TileFetchStatus failure) { }

    private PaletteAcquisition acquirePalette(Request request, TileBounds bounds, int width, int height,
            String palette, CredentialSnapshot credentials, CancellationProbe cancellation,
            boolean requiredForAggregate) {
        BufferedImage mosaic = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        boolean[] valid = new boolean[Math.multiplyExact(width, height)];
        TileFetchStatus firstFailure = null;
        Graphics2D graphics = mosaic.createGraphics();
        try {
            for (int x = bounds.minimumX(); x <= bounds.maximumX(); x++) {
                for (int y = bounds.minimumY(); y <= bounds.maximumY(); y++) {
                    cancellation.checkpoint();
                    CancellationToken requestCancellation = new CancellationToken();
                    TileRequest tile = new TileRequest(new ManagedTileAddress(request.activity(),
                            palette, request.zoom(), x, y), request.generation(),
                            requiredForAggregate ? TilePurpose.ALIGNMENT_OPTIONAL_AGGREGATE
                                    : TilePurpose.ALIGNMENT_REQUIRED,
                            TileCachePolicy.USE_CACHE, Instant.now().plusSeconds(30), requestCancellation);
                    TileFetchResult result;
                    try (CancellationProbe.Registration ignored =
                            cancellation.onCancellation(requestCancellation::cancel)) {
                        result = coordinator.fetch(tile, credentials).toCompletableFuture().join();
                    }
                    cancellation.checkpoint();
                    if (result.status() == TileFetchStatus.STALE_GENERATION) {
                        throw new IllegalStateException("Managed preview settings changed during acquisition");
                    }
                    if (requiredForAggregate && !result.usable()) {
                        return new PaletteAcquisition(null, result.status());
                    }
                    if (!result.usable() && firstFailure == null) firstFailure = result.status();
                    int left = (x - bounds.minimumX()) * TILE_SIZE;
                    int top = (y - bounds.minimumY()) * TILE_SIZE;
                    if (result.usable()) {
                        graphics.drawImage(result.image(), left, top, null);
                        for (int row = top; row < top + TILE_SIZE; row++) {
                            java.util.Arrays.fill(valid, row * width + left,
                                    row * width + left + TILE_SIZE, true);
                        }
                    }
                }
            }
        } finally {
            graphics.dispose();
        }
        return new PaletteAcquisition(new PaletteRaster(mosaic, valid), firstFailure);
    }

    private static boolean allValid(boolean[] validity) {
        for (boolean value : validity) if (!value) return false;
        return true;
    }

    private void requireActiveGeneration(Request request) {
        if (!coordinator.isActiveGeneration(request.generation())) {
            throw new IllegalStateException("Managed preview settings generation is stale");
        }
    }

    private static TileBounds tileBounds(Request request) {
        MetricRasterGrid grid = managedOutputGrid(request.source(), request.zoom(),
                request.searchRadiusMeters());
        SupportedInputRasterTransform world = SupportedInputRasterTransform.webMercator(
                request.zoom(), 0.0, 0.0, 2.0);
        SupportedInputRasterTransform.RasterBounds support = world.boundsForMetricCell(
                grid.coordinateFrame(), grid.footprint().polygons().get(0));
        int minimumPixelX = checkedFloor(support.minimumX());
        int minimumPixelY = checkedFloor(support.minimumY());
        int maximumPixelX = Math.addExact(checkedFloor(support.maximumX()), 1);
        int maximumPixelY = Math.addExact(checkedFloor(support.maximumY()), 1);
        int minimumX = Math.floorDiv(minimumPixelX, TILE_SIZE);
        int minimumY = Math.floorDiv(minimumPixelY, TILE_SIZE);
        int maximumX = Math.floorDiv(maximumPixelX, TILE_SIZE);
        int maximumY = Math.floorDiv(maximumPixelY, TILE_SIZE);
        int tileCount = 1 << request.zoom();
        if (minimumX < 0 || minimumY < 0 || maximumX >= tileCount || maximumY >= tileCount) {
            throw new IllegalArgumentException("Managed preview crosses an unsupported tile boundary");
        }
        return new TileBounds(minimumX, minimumY, maximumX, maximumY);
    }

    static MetricRasterGrid managedOutputGrid(List<GeographicPoint> source, int zoom, double radius) {
        LocalMetricFrame frame = managedFrame(source, zoom, radius);
        return managedOutputGrid(frame, source.stream().map(frame::toMetric).toList(), zoom, radius);
    }

    static MetricRasterGrid managedOutputGrid(LocalMetricFrame frame, List<MetricPoint> source,
            int zoom, double radius) {
        double pitch = TileHeatmapSampler.metersPerPixel(zoom,
                frame.origin().latitudeDegrees());
        MetricRegion decision = MetricCorridorRegion.aroundPolyline(source, radius);
        double minX = decision.polygons().stream().flatMap(List::stream)
                .mapToDouble(MetricPoint::xMeters).min().orElseThrow() - pitch;
        double maxX = decision.polygons().stream().flatMap(List::stream)
                .mapToDouble(MetricPoint::xMeters).max().orElseThrow() + pitch;
        double minY = decision.polygons().stream().flatMap(List::stream)
                .mapToDouble(MetricPoint::yMeters).min().orElseThrow() - pitch;
        double maxY = decision.polygons().stream().flatMap(List::stream)
                .mapToDouble(MetricPoint::yMeters).max().orElseThrow() + pitch;
        int width = Math.max(2, checkedCeil((maxX - minX) / pitch));
        int height = Math.max(2, checkedCeil((maxY - minY) / pitch));
        return new MetricRasterGrid(frame, new MetricPoint(minX + 0.5 * pitch,
                maxY - 0.5 * pitch), 1.0, 0.0, 0.0, -1.0, pitch, width, height);
    }

    static LocalMetricFrame managedFrame(List<GeographicPoint> source, int zoom, double radius) {
        if (source == null || source.size() < 2 || source.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Managed preview source is incomplete");
        }
        double minLat = source.stream().mapToDouble(GeographicPoint::latitudeDegrees).min().orElseThrow();
        double maxLat = source.stream().mapToDouble(GeographicPoint::latitudeDegrees).max().orElseThrow();
        double minLon = source.stream().mapToDouble(GeographicPoint::longitudeDegrees).min().orElseThrow();
        double maxLon = source.stream().mapToDouble(GeographicPoint::longitudeDegrees).max().orElseThrow();
        double representativeLatitude = source.get(source.size() / 2).latitudeDegrees();
        double pitch = TileHeatmapSampler.metersPerPixel(zoom, representativeLatitude);
        double latitudeMargin = Math.max(0.02,
                Math.toDegrees((radius + 3.0 * pitch) / 6_378_137.0));
        double longitudeMargin = Math.max(0.02, latitudeMargin
                / Math.max(0.01, Math.cos(Math.toRadians(representativeLatitude))));
        GeographicPoint southwest = new GeographicPoint(minLat - latitudeMargin,
                minLon - longitudeMargin);
        GeographicPoint northeast = new GeographicPoint(maxLat + latitudeMargin,
                maxLon + longitudeMargin);
        return LocalMetricFrame.certifiedEquirectangular(source.get(source.size() / 2),
                southwest, northeast);
    }

    private static int checkedFloor(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Managed preview footprint exceeds coordinate limits");
        }
        return (int) Math.floor(value);
    }

    private static int checkedCeil(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Managed preview output grid exceeds coordinate limits");
        }
        return (int) Math.ceil(value);
    }

    private record TileBounds(int minimumX, int minimumY, int maximumX, int maximumY) {
        int width() { return maximumX - minimumX + 1; }
        int height() { return maximumY - minimumY + 1; }
    }
}
