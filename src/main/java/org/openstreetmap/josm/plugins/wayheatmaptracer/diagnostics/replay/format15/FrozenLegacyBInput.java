package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyManagedProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyVisibleProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBProductionRunner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyTopologyPlanner.Occurrence;

/** All additional algorithm-consumed Legacy inputs, included in the root frozen replay identity. */
public final class FrozenLegacyBInput {
    private final int sourceKind;
    private final byte[] sourceBytes;
    private final LegacyVisibleProfileSampler.Input visibleSource;
    private final LegacyManagedProfileSampler.Input managedSource;
    private final List<Occurrence> occurrences;
    private final List<OccurrenceRange> frozenRanges;
    private final boolean simplify;
    private final double projectedTolerance;

    private FrozenLegacyBInput(int sourceKind, byte[] sourceBytes, List<Occurrence> occurrences,
            List<OccurrenceRange> frozenRanges, boolean simplify, double projectedTolerance,
            LongConsumer reserve) {
        if (sourceKind != 1 && sourceKind != 2 || sourceBytes == null
                || sourceBytes.length > Format15Safety.MAX_ARTIFACT_BYTES
                || occurrences == null || occurrences.size() < 2 || frozenRanges == null
                || !Double.isFinite(projectedTolerance) || projectedTolerance < 0) {
            throw new IllegalArgumentException("Frozen Legacy source policy is invalid");
        }
        this.sourceKind = sourceKind;
        this.sourceBytes = sourceBytes.clone();
        visibleSource = sourceKind == 1 ? LegacySourceCodec.decodeVisible(sourceBytes, reserve) : null;
        managedSource = sourceKind == 2 ? LegacySourceCodec.decodeManaged(sourceBytes, reserve) : null;
        this.occurrences = List.copyOf(occurrences);
        this.frozenRanges = List.copyOf(frozenRanges);
        this.simplify = simplify;
        this.projectedTolerance = projectedTolerance;
        int count = visibleSource == null ? managedSource.sourcePolyline().size() : visibleSource.anchors().size();
        if (count != occurrences.size()) { throw new IllegalArgumentException("Legacy source occurrence count differs"); }
        for (int index = 0; index < occurrences.size(); index++) {
            if (occurrences.get(index).sourceIndex() != index) {
                throw new IllegalArgumentException("Legacy occurrence policy is reordered");
            }
        }
    }

    public static FrozenLegacyBInput visible(LegacyVisibleProfileSampler.Input source, List<Occurrence> occurrences,
            List<OccurrenceRange> frozen, boolean simplify, double tolerance) {
        return decode(1, LegacySourceCodec.encode(source), occurrences, frozen, simplify, tolerance, ignored -> { });
    }

    public static FrozenLegacyBInput managed(LegacyManagedProfileSampler.Input source, List<Occurrence> occurrences,
            List<OccurrenceRange> frozen, boolean simplify, double tolerance) {
        return decode(2, LegacySourceCodec.encode(source), occurrences, frozen, simplify, tolerance, ignored -> { });
    }

    /** Uses actual live production inputs, never reconstructed candidates or mutable OSM coordinates. */
    public static FrozenLegacyBInput capture(LiveBPreviewService.LegacyBComputation computation) {
        Objects.requireNonNull(computation);
        var input = computation.input();
        return computation.visibleSource() != null
                ? visible(computation.visibleSource(), input.occurrences(), input.frozenRanges(),
                        input.simplify(), input.projectedTolerance())
                : managed(computation.managedSource(), input.occurrences(), input.frozenRanges(),
                        input.simplify(), input.projectedTolerance());
    }

    static FrozenLegacyBInput decode(int kind, byte[] source, List<Occurrence> occurrences,
            List<OccurrenceRange> frozen, boolean simplify, double tolerance, LongConsumer reserve) {
        return new FrozenLegacyBInput(kind, source, occurrences, frozen, simplify, tolerance, reserve);
    }

    /** Revalidates all original occurrence authority through the same production runner contract. */
    public LegacyBProductionRunner.Input bind(FrozenReplayInput root) {
        if (!(root.network().primitives().get(root.request().selectedWayKey()) instanceof DetachedWay way)) {
            throw new IllegalArgumentException("Frozen selected way is unavailable");
        }
        var projected = visibleSource != null ? visibleSource.anchors().stream().map(
                LegacyVisibleProfileSampler.Anchor::source).toList() : managedSource.sourcePolyline();
        var metrics = new java.util.ArrayList<org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint>();
        for (int occurrence = root.request().selectedRange().firstIndex();
                occurrence <= root.request().selectedRange().lastIndex(); occurrence++) {
            if (occurrence >= way.nodeKeys().size()
                    || !(root.network().primitives().get(way.nodeKeys().get(occurrence)) instanceof DetachedNode node)) {
                throw new IllegalArgumentException("Frozen original occurrence is unavailable");
            }
            metrics.add(root.evidence().coordinateFrame().toMetric(node.coordinate()));
        }
        return new LegacyBProductionRunner.Input(root.request(), root.network(), root.evidence(),
                root.options().fieldName(), projected, metrics,
                visibleSource != null ? visibleSource.raster().projectionCode() : managedSource.projectionCode(),
                occurrences, frozenRanges, simplify, projectedTolerance);
    }

    int sourceKind() { return sourceKind; }
    // Preserve the original compressed bytes across hosts: zlib implementations need not encode identically.
    byte[] sourceBytes() { return sourceBytes.clone(); }
    int sourceByteCount() { return sourceBytes.length; }
    long rasterBytes() {
        if (visibleSource != null) return (long) visibleSource.raster().width() * visibleSource.raster().height() * 4;
        return managedSource.mosaics().values().stream().mapToLong(mosaic -> (long) mosaic.width() * mosaic.height() * 4).sum();
    }
    public LegacyVisibleProfileSampler.Input visibleSource() { return visibleSource; }
    public LegacyManagedProfileSampler.Input managedSource() { return managedSource; }
    public List<Occurrence> occurrences() { return occurrences; }
    public List<OccurrenceRange> frozenRanges() { return frozenRanges; }
    public boolean simplify() { return simplify; }
    public double projectedTolerance() { return projectedTolerance; }
}
