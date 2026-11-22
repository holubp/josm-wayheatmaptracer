package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openstreetmap.josm.data.osm.DataSourceListener;
import org.openstreetmap.josm.data.osm.event.DataSetListenerAdapter;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionIntegrity;

/** Lightweight Legacy-first ownership. Native authority and scalar evidence are deliberately absent. */
public final class LegacyReviewOwner implements RefinementReviewRoot {
    private static final Set<String> MATERIAL_SETTINGS = Set.of("keyPairId", "policy", "signature", "session",
        "activity", "color", "manualLayerName", "layerRegex", "alignmentMode", "trackerMode", "multiColorDetection",
        "aggregateAllColorSchemes", "parallelWayAwareness", "allowUndownloadedAlignment", "adjustJunctionNodes",
        "simplifyEnabled", "crossSectionHalfWidthPx", "crossSectionStepPx", "simplifyTolerancePx", "inferenceMode",
        "inferenceZoom", "validationZoom", "searchHalfWidthMeters", "sampleStepMeters", "intensitySamplingMode", "cacheBuster",
        "tracing.engine", "tracing.sourceMode", "recovery.junctionPolicy", "recovery.reconstructIncidentWays");
    public static boolean isMaterialSetting(String key) {
        return key.startsWith("wayheatmaptracer.")&&MATERIAL_SETTINGS.contains(key.substring("wayheatmaptracer.".length()));
    }
    private final org.openstreetmap.josm.spi.preferences.IPreferences preferences = org.openstreetmap.josm.spi.preferences.Config.getPref();
    private final AtomicBoolean settingsChanged = new AtomicBoolean();
    private final org.openstreetmap.josm.spi.preferences.PreferenceChangedListener settingsListener = event -> {
        String key = event.getKey();
        if (isMaterialSetting(key))
            settingsChanged.set(true);
    };
    private final DataSet dataSet;
    // Event callbacks can arrive from dataset mutation threads. The owning EDT only reads this stamp.
    private final AtomicBoolean changed = new AtomicBoolean();
    private final DataSetListenerAdapter changes = new DataSetListenerAdapter(event -> changed.set(true));
    private final DataSourceListener downloadedAreas = event -> changed.set(true);
    private final SelectionContext selection;
    private final List<Node> wholeWay;
    private final List<EastNorth> originalGeometry;
    private final String projectionCode;
    private final AlignmentConfig config;
    /** Opaque owner-local choice identity; native detector IDs and labels are descriptive only. */
    public record Choice(String id, CenterlineCandidate candidate) { }
    private List<Choice> choices = List.of();
    private AlignmentResult result;
    private CenterlineCandidate selected;
    private LegacyVisibleSource source;
    private org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.AcquisitionReceipt managedReceipt;
    private final String sourceIdentity = "legacy-original-" + java.util.UUID.randomUUID();
    private boolean closed;

    private LegacyReviewOwner(DataSet dataSet, SelectionContext selection, AlignmentConfig config) {
        this(dataSet,selection,config,false);
    }
    private LegacyReviewOwner(DataSet dataSet,SelectionContext selection,AlignmentConfig config,boolean nativeObserver) {
        this.dataSet = Objects.requireNonNull(dataSet);
        this.selection = new SelectionContext(selection.way(), selection.startIndex(), selection.endIndex(),
                List.copyOf(selection.segmentNodes()), Set.copyOf(selection.fixedNodes()));
        wholeWay = List.copyOf(selection.way().getNodes());
        originalGeometry = selection.segmentNodes().stream().map(Node::getEastNorth).toList();
        projectionCode = ProjectionRegistry.getProjection().toCode();
        this.config = Objects.requireNonNull(config);
        if (!nativeObserver&&config.heatmap().trackerMode() != TrackerMode.LEGACY_V02) {
            throw new IllegalArgumentException("A live refinement review requires an original Legacy attempt");
        }
        SelectionIntegrity.requirePreviewSourceUnchanged(dataSet, this.selection, originalGeometry);
        dataSet.addDataSetListener(changes);
        dataSet.addDataSourceListener(downloadedAreas);
        preferences.addPreferenceChangeListener(settingsListener);
    }
    /** Starts before the original alignment, without reading pixels or acquiring native authority. */
    public static LegacyReviewOwner begin(DataSet dataSet, SelectionContext selection, AlignmentConfig config) {
        requireEdt();
        Objects.requireNonNull(dataSet);
        // JOSM ends a batch by delivering its events under a downgraded read lock.
        // The no-mutation exclusive barrier waits for those callbacks before taking facts.
        // Host lock acquisition has no promised time bound.
        return dataSet.update(() -> new LegacyReviewOwner(dataSet, selection, config));
    }
    /** Geometry/settings observer only; native authority remains the original native capture receipt. */
    static LegacyReviewOwner observeNative(DataSet dataSet,SelectionContext selection,AlignmentConfig config) {
        requireEdt();return dataSet.update(()->new LegacyReviewOwner(dataSet,selection,config,true));
    }
    public TrackerMode engine(){return config.heatmap().trackerMode();}
    public SelectionContext selection(){requireOpen();return selection;}
    public void requireAuthorityCurrent(){requireLegacyAuthorityCurrent();}
    /** Retains the actual existing Legacy result and its factual slide-time metadata without copying its raster. */
    public void captured(AlignmentResult result, LegacyVisibleSource source) {
        captured(result, source, null);
    }
    public void captured(AlignmentResult result, LegacyVisibleSource source,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.AcquisitionReceipt managedReceipt) {
        requireOpen();
        if (managedReceipt != null && managedReceipt.mosaics() != result.tileMosaics())
            throw new IllegalArgumentException("Managed receipt does not own the original Legacy result");
        if (this.result != null || result == null || !selection.equals(result.selection())
                || !originalGeometry.equals(result.sourcePolyline())) {
            throw new IllegalArgumentException("Legacy result differs from its original attempt");
        }
        this.result = result;
        this.source = source;
        this.managedReceipt = managedReceipt;
        choices = java.util.stream.IntStream.range(0, result.candidates().size())
            .mapToObj(index -> new Choice("legacy-choice-" + index, result.candidates().get(index))).toList();
        selected = result.candidates().isEmpty() ? null : result.candidates().get(0);
    }
    public void select(CenterlineCandidate candidate) {
        requireOpen();
        if (result == null || result.candidates().stream().noneMatch(value -> value == candidate)) {
            throw new IllegalArgumentException("Candidate is not owned by the original Legacy review");
        }
        selected = candidate;
    }
    public List<Choice> choices() { requireOpen(); return choices; }
    public String choiceId(CenterlineCandidate candidate) {
        requireOpen();
        return choices.stream().filter(choice -> choice.candidate() == candidate).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Candidate is not owned by the original Legacy review")).id();
    }
    public CenterlineCandidate candidateForChoice(String id) {
        requireOpen();
        return choices.stream().filter(choice -> choice.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown Legacy review choice")).candidate();
    }
    public CenterlineCandidate selectedCandidate() { requireOpen(); return selected; }
    public AlignmentResult originalResult() { requireOpen(); return result; }
    public org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.AcquisitionReceipt managedReceipt() {
        requireOpen(); return managedReceipt;
    }
    public DataSet dataSet() { requireOpen(); return dataSet; }
    public String sourceIdentity() { requireOpen(); return sourceIdentity; }
    public String projectionCode() { requireOpen(); return projectionCode; }
    /** Thread-safe invalidation probe; never traverses live dataset primitives. */
    public boolean invalidated() { return changed.get() || settingsChanged.get(); }
    public AlignmentConfig originalConfig() { requireOpen(); return config; }
    /** Original capture metadata only; obtaining it performs no optional pixel work. */
    public LegacyVisibleSource originalVisibleSource(){requireOpen();return source;}
    /** Requires original facts before lazy capture; this check never refreshes authority. */
    public void requireOriginalAttemptCurrent() {
        requireOpen();
        dataSet.update(this::requireUnchangedFacts);
    }
    /** Live Legacy Confirm/Apply authority. Unknown bootstrap capability does not invalidate Legacy. */
    public void requireLegacyAuthorityCurrent() {
        requireOriginalAttemptCurrent();
        if(result==null)throw new IllegalStateException("The original Legacy result is unavailable");
        if(managedReceipt!=null)managedReceipt.generationGuard().getAsLong();
        if(source!=null&&source.sourceReceipt()!=null)source.sourceReceipt().owner().requireCurrent(source.sourceReceipt());
    }
    private void requireUnchangedFacts() {
        if (settingsChanged.get() || preferences != org.openstreetmap.josm.spi.preferences.Config.getPref())
            throw new IllegalStateException("Heatmap source or alignment settings changed after the original Legacy attempt began");
        if (changed.get()) {
            throw new IllegalStateException("The dataset changed after the original Legacy attempt began; run alignment again before refinement");
        }
        if (!projectionCode.equals(ProjectionRegistry.getProjection().toCode())
                || !wholeWay.equals(selection.way().getNodes())) {
            throw new IllegalStateException("The original selection or projection changed after alignment began");
        }
        SelectionIntegrity.requirePreviewSourceUnchanged(dataSet, selection, originalGeometry);
    }
    /**
     * Input-preparation seam only, never modern execution or fresh authority. The bootstrap caller must
     * separately admit transient/retained memory and validate the active dataset/layer/settings owner.
     */
    LiveBPreviewService.VisibleRaster freezeVisibleSource(String sourceIdentity) {
        requireOriginalAttemptCurrent();
        if (result == null || source == null) {
            throw new IllegalStateException("The original Legacy source capture metadata is unavailable");
        }
        // No dataset lock is held across pixel freezing, conversion, inference or acquisition.
        var frozen = source.freeze(result.capturedHeatmap(), sourceIdentity);
        requireOriginalAttemptCurrent();
        return frozen;
    }
    /** Explicit optional preparation. Ordinary Legacy Confirm/Apply never enters this overload. */
    LegacyVisibleSource.SmoothingRaster freezeVisibleSource(String sourceIdentity,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work) {
        requireOriginalAttemptCurrent();
        if(result==null||source==null)throw new IllegalStateException("Original visible capture is unavailable");
        var frozen=source.freeze(result.capturedHeatmap(),sourceIdentity,work);requireOriginalAttemptCurrent();return frozen;
    }
    @Override public void close() {
        requireEdt();
        if (!closed) {
            closed = true;
            dataSet.removeDataSetListener(changes);
            dataSet.removeDataSourceListener(downloadedAreas);
            preferences.removePreferenceChangeListener(settingsListener);
        }
    }
    private void requireOpen() {
        requireEdt();
        if (closed) throw new IllegalStateException("The Legacy review is closed");
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Legacy review ownership requires the event thread");
    }
}
