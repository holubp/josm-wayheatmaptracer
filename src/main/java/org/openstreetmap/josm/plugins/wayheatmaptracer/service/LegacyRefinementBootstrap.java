package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.*;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;

/** Lazy, admitted conversion of an actual retained original Legacy acquisition. Never runs Legacy again. */
public final class LegacyRefinementBootstrap {
    public sealed interface OriginalSource permits ManagedOriginalSource, VisibleOriginalSource {
        boolean managed();
        void requireCurrent(TileFetchCoordinator coordinator);
    }
    public record ManagedOriginalSource(TileHeatmapSampler.AcquisitionReceipt receipt, AlignmentResult original) implements OriginalSource {
        public ManagedOriginalSource { Objects.requireNonNull(receipt); Objects.requireNonNull(original); }
        public boolean managed() { return true; }
        public void requireCurrent(TileFetchCoordinator coordinator) { receipt.requireCurrent(coordinator,original.tileMosaics()); }
    }
    public record VisibleOriginalSource(LegacyVisibleSource metadata, AlignmentResult original) implements OriginalSource {
        public VisibleOriginalSource { Objects.requireNonNull(metadata); Objects.requireNonNull(original); }
        public boolean managed() { return false; }
        public void requireCurrent(TileFetchCoordinator coordinator) { metadata.sourceReceipt().owner().requireCurrent(metadata.sourceReceipt()); }
    }
    public record Seed(LiveBPreviewService.LegacyRootSeed facts, AlignmentResult original,
            AlignmentConfig config, OriginalSource originalSource,
            RefinementSourcePlanner.Settings settings, String selectedChoice,
            AttemptMemoryLedger.Owner memory, long payloadLimit, SmoothingSeed factualSeed,
            GeometryWorkContext context, LegacyReviewOwner review, AttemptMemoryLedger.Owner preparation) implements AutoCloseable {
        public Seed(LiveBPreviewService.LegacyRootSeed facts, AlignmentResult original, AlignmentConfig config,
                TileHeatmapSampler.AcquisitionReceipt receipt, RefinementSourcePlanner.Settings settings,
                String selectedChoice, AttemptMemoryLedger.Owner memory, long payloadLimit) {
            this(facts,original,config,new ManagedOriginalSource(receipt,original),settings,selectedChoice,memory,payloadLimit,null,null,null,null);
        }
        @Override public void close() { if (preparation!=null && !preparation.isClosed()) preparation.close(); }
        public TileHeatmapSampler.AcquisitionReceipt receipt() {
            if (originalSource instanceof ManagedOriginalSource managed) return managed.receipt();
            throw new IllegalStateException("The original source is visible, not managed");
        }
    }
    public record Root(RefinementRootAuthority authority, Stage stage,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture.CapturedSnapshot receipt,
            RetainedSmoothingSource originalConversion) {
        public Root(RefinementRootAuthority authority, Stage stage,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture.CapturedSnapshot receipt) {
            this(authority,stage,receipt,null);
        }
    }
    public sealed interface SmoothingSeed permits ManagedSeed,VisibleSeed {
        LiveBPreviewService.LegacyRootSeed facts();AlignmentResult original();AlignmentConfig config();
        String selectedChoice();RefinementSourcePlanner.SourceRequest selectedSource();AttemptMemoryLedger.Owner memory();long payloadLimit();
    }
    public record ManagedSeed(LiveBPreviewService.LegacyRootSeed facts,AlignmentResult original,AlignmentConfig config,
            String selectedChoice,RefinementSourcePlanner.SourceRequest selectedSource,AttemptMemoryLedger.Owner memory,long payloadLimit,
            TileHeatmapSampler.AcquisitionReceipt receipt) implements SmoothingSeed { }
    public record VisibleSeed(LiveBPreviewService.LegacyRootSeed facts,AlignmentResult original,AlignmentConfig config,
            String selectedChoice,RefinementSourcePlanner.SourceRequest selectedSource,AttemptMemoryLedger.Owner memory,long payloadLimit,
            LegacyVisibleSource metadata) implements SmoothingSeed { }
    public record SmoothingRoot(Root root,Candidate selected,RetainedSmoothingSource source) { }
    public enum SmoothingUnavailableReason { SOURCE_MAPPING_UNAVAILABLE, ORIGINAL_SOURCE_UNAVAILABLE,
        VISIBLE_PROJECTION_TRANSFORM_UNAVAILABLE, VISIBLE_SAMPLING_METADATA_UNAVAILABLE, VISIBLE_EPOCH_UNAVAILABLE, SOURCE_COVERAGE_UNAVAILABLE, SELECTED_FINAL_PREVIEW_UNAVAILABLE }
    public static final class SmoothingUnavailable extends IllegalStateException {
        private final SmoothingUnavailableReason reason;
        public SmoothingUnavailable(SmoothingUnavailableReason reason){super(reason.name().toLowerCase(Locale.ROOT).replace('_','-'));this.reason=reason;}
        public SmoothingUnavailableReason reason(){return reason;}
    }

    /** Explicit-click metadata capture only. It never copies/maps/hashes pixels or starts acquisition. */
    public SmoothingSeed captureForSmoothing(LegacyReviewOwner review,TileFetchCoordinator coordinator,GeometryWorkContext work) {
        return captureForSmoothing(review,coordinator,work,null);
    }
    /** Reuses already-frozen original facts for a later selection; never mints a new authority. */
    public SmoothingSeed captureForSmoothing(LegacyReviewOwner review,TileFetchCoordinator coordinator,GeometryWorkContext work,SmoothingSeed previous) {
        return captureForSmoothing(review,coordinator,work,previous,work.owner());
    }
    private SmoothingSeed captureForSmoothing(LegacyReviewOwner review,TileFetchCoordinator coordinator,GeometryWorkContext work,SmoothingSeed previous,AttemptMemoryLedger.Owner owner) {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Original smoothing facts require EDT");
        work.visit(GeometryWorkContext.Kind.BIND,1);review.requireOriginalAttemptCurrent();
        var original=review.originalResult();var config=review.originalConfig();var selected=review.selectedCandidate();
        if(selected.finalPreviewPoints().size()<2)throw new SmoothingUnavailable(SmoothingUnavailableReason.SELECTED_FINAL_PREVIEW_UNAVAILABLE);
        var attempts=original.detectorAttempts().stream().filter(a->a.candidateIds().contains(selected.id())).toList();
        var bindings=attempts.stream().map(a->new RefinementSourcePlanner.SourceRequest(a.sourcePalette(),a.mappingName(),a.mappingName().equals("all-colors-combined"),"")).distinct().toList();
        if(bindings.size()!=1)throw new SmoothingUnavailable(SmoothingUnavailableReason.SOURCE_MAPPING_UNAVAILABLE);
        var source=bindings.get(0);long maximum=RefinementLimits.defaults().maxPayloadBytes();
        if(previous!=null&&(!previous.facts().sourceIdentity().equals(review.sourceIdentity())||previous.original()!=original||!previous.config().equals(config)))
            throw new SmoothingUnavailable(SmoothingUnavailableReason.ORIGINAL_SOURCE_UNAVAILABLE);
        var receipt=review.managedReceipt();var visible=review.originalVisibleSource();
        double radius,step;
        if(receipt!=null) {
            receipt.requireCurrent(coordinator,original.tileMosaics());
            var mosaic=original.tileMosaics().require(AlignmentTileSourcePlan.from(config.heatmap()).selectedColor());
            double length=0;for(int i=1;i<original.sourcePolyline().size();i++){work.visit(GeometryWorkContext.Kind.BIND,1);length+=original.sourcePolyline().get(i).distance(original.sourcePolyline().get(i-1));}
            var shape=TileHeatmapSampler.legacySamplingShape(length,original.sourcePolyline().size(),mosaic.parameters());
            radius=shape.referenceHalfWidthPx()*RenderedHeatmapSampler.RASTER_SCALE/mosaic.virtualRasterScale()*mosaic.parameters().metersPerPixel();step=mosaic.parameters().sampleStepMeters();
        } else {
            if(visible==null||original.capturedHeatmap()==null)throw new SmoothingUnavailable(SmoothingUnavailableReason.ORIGINAL_SOURCE_UNAVAILABLE);
            if(!"EPSG:3857".equals(visible.projectionCode()))throw new SmoothingUnavailable(SmoothingUnavailableReason.VISIBLE_PROJECTION_TRANSFORM_UNAVAILABLE);
            if(visible.sourceReceipt()==null)throw new SmoothingUnavailable(SmoothingUnavailableReason.VISIBLE_EPOCH_UNAVAILABLE);
            visible.sourceReceipt().owner().requireCurrent(visible.sourceReceipt());
            if(visible.decisionRadiusMeters().isEmpty()||visible.sampleStepMeters().isEmpty())throw new SmoothingUnavailable(SmoothingUnavailableReason.VISIBLE_SAMPLING_METADATA_UNAVAILABLE);
            if(source.aggregate())throw new SmoothingUnavailable(SmoothingUnavailableReason.ORIGINAL_SOURCE_UNAVAILABLE);
            radius=visible.decisionRadiusMeters().getAsDouble();step=visible.sampleStepMeters().getAsDouble();
        }
        var facts=previous==null?new LiveBPreviewService().captureLegacyRootSeed(review.dataSet(),original.selection(),config,radius,step,review.sourceIdentity(),owner,work).value():previous.facts();
        if(facts.decisionRadiusMeters()!=radius||facts.sampleStepMeters()!=step)throw new SmoothingUnavailable(SmoothingUnavailableReason.ORIGINAL_SOURCE_UNAVAILABLE);
        // Alias an existing original group on Back/Redo rather than registering it twice.
        try{owner.retainIfAbsent(original);}catch(IllegalArgumentException absent){
            long estimate=RefinementPayload.estimateBytes(original);if(estimate>maximum)throw new RefinementSession.Refused(RefusalReason.PAYLOAD_LIMIT);
            work.reserveOn(owner,"original-legacy-source",estimate).adopt(original);
        }
        review.requireOriginalAttemptCurrent();
        return receipt!=null?new ManagedSeed(facts,original,config,review.choiceId(selected),source,work.owner(),maximum,receipt):
            new VisibleSeed(facts,original,config,review.choiceId(selected),source,work.owner(),maximum,visible);
    }

    /** Maps the exact selected original source lazily, using one admitted operation context. */
    public SmoothingRoot completeForSmoothing(SmoothingSeed seed,TileFetchCoordinator coordinator,GeometryWorkContext work) {
        return completeForSmoothing(seed,coordinator,work,null);
    }
    /** A later binding preserves the already-published original root verbatim. */
    public SmoothingRoot completeForSmoothing(SmoothingSeed seed,TileFetchCoordinator coordinator,GeometryWorkContext work,Root existing) {
        return completeForSmoothing(seed,coordinator,work,existing,work.owner());
    }
    private SmoothingRoot completeForSmoothing(SmoothingSeed seed,TileFetchCoordinator coordinator,GeometryWorkContext work,Root existing,AttemptMemoryLedger.Owner owner) {
        if(SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Original smoothing pixels require worker thread");
        if(seed.memory()!=work.owner())throw new IllegalArgumentException("Smoothing preparation must use its original operation owner");
        work.visit(GeometryWorkContext.Kind.BIND,1);
        var facts=seed.facts();var selected=seed.selectedSource();var intensity=seed.config().heatmap().intensitySamplingMode();
        try(var frameWorkspace=work.reserve("original-smoothing-frame",Math.addExact(262144L,Math.multiplyExact(1024L,facts.sourceMetric().size())))) {
        if(intensity==null)intensity=IntensitySamplingMode.COLOR_MAPPING;final var mode=intensity;
        var capture=new RasterEvidenceCapture();RasterEvidenceCapture.AdmittedEvidence admitted;SupportedInputRasterTransform transform;
        int width,height;String imageIdentity,inputHash="";Runnable currentness;
        String inputHashProtocol=selected.aggregate()?RasterEvidenceCapture.ORIGINAL_ARGB_SET_PROTOCOL:RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL;
        if(seed instanceof ManagedSeed managed) {
            managed.receipt().requireCurrent(coordinator,seed.original().tileMosaics());
            var mosaics=seed.original().tileMosaics();var mosaic=mosaics.require(selected.palette());
            width=mosaic.image().getWidth();height=mosaic.image().getHeight();
            // TileMosaic coordinates use512-world pixels; the exact transform uses256-world pixels.
            transform=SupportedInputRasterTransform.webMercator(mosaic.zoom(),mosaic.originWorldPxX()/2,mosaic.originWorldPxY()/2,2.0);
            var grid=ManagedModernPreviewSource.managedOutputGrid(facts.frame(),facts.sourceMetric(),mosaic.zoom(),facts.decisionRadiusMeters());
            requireCovered(transform,grid,width,height);
            var guard=managed.receipt().generationGuard();currentness=()->guard.getAsLong();imageIdentity=facts.sourceIdentity()+"/original-managed-input/"+(selected.aggregate()?"all-colors":selected.palette());
            long count=Math.multiplyExact((long)width,height);
            try(var validMemory=work.reserve("original-managed-validity",AttemptMemoryLedger.arrayBytes(count,1))) {
                var valid=new boolean[Math.toIntExact(count)];for(int i=0;i<valid.length;i++){work.visit(GeometryWorkContext.Kind.COPY,1);valid[i]=true;}
                var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                    selected.aggregate()?EvidenceFieldLineage.DerivationKind.ALL_COLOR_AGGREGATE:mode.usesColorMapping()?EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING:EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                    mode.usesColorMapping()?selected.mapping():selected.palette()+"/"+mode.detectorName(),EvidenceCorrelationGroup.STRAVA_RENDERINGS,selected.aggregate());
                if(selected.aggregate()) {
                    if(!mode.usesColorMapping())throw new SmoothingUnavailable(SmoothingUnavailableReason.SOURCE_MAPPING_UNAVAILABLE);
                    var images=new LinkedHashMap<String,java.awt.image.BufferedImage>();
                    for(var other:mosaics.mosaics().values())if(other.zoom()==mosaic.zoom()&&RefinementSourcePlanner.BASE_PALETTES.contains(other.color())) {
                        work.visit(GeometryWorkContext.Kind.BIND,1);
                        if(Math.abs(other.originWorldPxX()-mosaic.originWorldPxX())>=1e-9||Math.abs(other.originWorldPxY()-mosaic.originWorldPxY())>=1e-9
                                ||other.image().getWidth()!=width||other.image().getHeight()!=height||Math.abs(other.virtualRasterScale()-mosaic.virtualRasterScale())>=1e-9)
                            throw new SmoothingUnavailable(SmoothingUnavailableReason.ORIGINAL_SOURCE_UNAVAILABLE);
                        if(images.put(other.color(),other.image())!=null)throw new SmoothingUnavailable(SmoothingUnavailableReason.ORIGINAL_SOURCE_UNAVAILABLE);
                    }
                    if(!images.keySet().containsAll(RefinementSourcePlanner.BASE_PALETTES)||images.size()!=5)throw new SmoothingUnavailable(SmoothingUnavailableReason.ORIGINAL_SOURCE_UNAVAILABLE);
                    var originals=images.entrySet().stream().map(entry->new RasterEvidenceCapture.NamedOriginalRaster(entry.getKey(),entry.getValue(),transform)).toList();
                    inputHash=RasterEvidenceCapture.hashOriginalArgbSet(originals,work,owner);currentness.run();
                    try(var scalarMemory=work.reserve("original-aggregate-scalar",AttemptMemoryLedger.arrayBytes(count,8))) {
                        var scalar=new double[Math.toIntExact(count)];
                        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                            // The unchanged native aggregate reads four image samples from each of five palettes.
                            work.visit(GeometryWorkContext.Kind.MAP,20);scalar[y*width+x]=RenderedHeatmapSampler.aggregatedSourceIntensityAt(images,x,y);
                        }
                        admitted=capture.captureWithMetricScalarSource(facts.network().snapshotId()+"/smoothing-source/"+selected.mapping(),mosaic.image(),valid,scalar,facts.sourceGeographic(),facts.sourceMetric(),transform,grid,
                            EvidenceResolution.nativeSource(mosaic.parameters().metersPerPixel(),grid.pitchMeters()),facts.decisionRadiusMeters(),facts.sourceIdentity(),lineage.acquisitionKind(),"native",lineage,CancellationProbe.NONE,work,owner);
                    }
                } else {
                    inputHash=RasterEvidenceCapture.hashOriginalArgb(mosaic.image(),work,owner);currentness.run();
                    var field=RasterEvidenceCapture.FieldSpec.direct("native",argb->ManagedModernPreviewSource.scalarIntensity(argb,selected.mapping(),mode),lineage);
                    admitted=capture.captureWithMetricSource(facts.network().snapshotId()+"/smoothing-source/"+selected.mapping(),mosaic.image(),valid,facts.sourceGeographic(),facts.sourceMetric(),transform,grid,
                        EvidenceResolution.nativeSource(mosaic.parameters().metersPerPixel(),grid.pitchMeters()),facts.decisionRadiusMeters(),facts.sourceIdentity(),lineage.acquisitionKind(),List.of(field),CancellationProbe.NONE,work,owner);
                }
            }
            currentness.run();
        } else {
            var visible=(VisibleSeed)seed;var metadata=visible.metadata();var frozen=metadata.freeze(seed.original().capturedHeatmap(),facts.sourceIdentity(),work,owner);
            var image=frozen.copyImage(work,owner);width=frozen.width();height=frozen.height();inputHash=frozen.inputByteHash();
            transform=SupportedInputRasterTransform.visibleWebMercator(metadata.minimumEast(),metadata.maximumNorth(),metadata.projectionUnitsPerViewPixel(),RenderedHeatmapSampler.RASTER_SCALE);
            double pitch=metadata.groundMetersPerViewPixel()/RenderedHeatmapSampler.RASTER_SCALE;
            var grid=visibleSmoothingGrid(facts,pitch,work);requireCovered(transform,grid,width,height);
            var receipt=metadata.sourceReceipt();currentness=()->receipt.owner().requireCurrent(receipt);imageIdentity=facts.sourceIdentity()+"/original-visible-input/"+receipt.revision();
            long count=Math.multiplyExact((long)width,height);
            try(var validMemory=work.reserve("original-visible-validity",AttemptMemoryLedger.arrayBytes(count,1))) {
                var valid=new boolean[Math.toIntExact(count)];for(int i=0;i<valid.length;i++){work.visit(GeometryWorkContext.Kind.COPY,1);valid[i]=true;}
                var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,
                    mode.usesColorMapping()?EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING:EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                    mode.usesColorMapping()?selected.mapping():selected.palette()+"/"+mode.detectorName(),EvidenceCorrelationGroup.STRAVA_RENDERINGS,false);
                var field=RasterEvidenceCapture.FieldSpec.direct("native",argb->ManagedModernPreviewSource.scalarIntensity(argb,selected.mapping(),mode),lineage);
                admitted=capture.captureWithMetricSource(facts.network().snapshotId()+"/smoothing-source/"+selected.mapping(),image,valid,facts.sourceGeographic(),facts.sourceMetric(),transform,grid,
                    metadata.nativePitchMeters().isPresent()?EvidenceResolution.nativeSource(metadata.nativePitchMeters().getAsDouble(),pitch):EvidenceResolution.renderedOnly(pitch),
                    facts.decisionRadiusMeters(),facts.sourceIdentity(),lineage.acquisitionKind(),List.of(field),CancellationProbe.NONE,work,owner);
            }
            currentness.run();
        }
        CenterlineCandidate originalSelected=null;
        for(int i=0;i<seed.original().candidates().size();i++)if(seed.selectedChoice().equals("legacy-choice-"+i)){originalSelected=seed.original().candidates().get(i);break;}
        if(originalSelected==null)throw new SmoothingUnavailable(SmoothingUnavailableReason.SOURCE_MAPPING_UNAVAILABLE);
        var disposition=new RetainedSmoothingSource.LegacyDisposition(originalSelected.id(),seed.original().applicableCandidates().contains(originalSelected));
        var source=RetainedSmoothingSource.fromOriginalConversion(selected,mode,"native",admitted.evidence(),transform,width,height,admitted.retainedBytes(),currentness,imageIdentity,inputHash,
            seed instanceof ManagedSeed managed?OptionalLong.of(managed.receipt().generation().value()):OptionalLong.empty(),
            seed instanceof VisibleSeed visible?Optional.of(visible.metadata().sourceReceipt()):Optional.empty(),Optional.of(disposition),inputHashProtocol,work,owner);
        Root root;
        if(existing!=null) {
            if(existing.authority().network()!=facts.network()||!existing.authority().sourceIdentity().equals(facts.sourceIdentity())||!existing.authority().effectiveConfig().equals(seed.config()))
                throw new SmoothingUnavailable(SmoothingUnavailableReason.ORIGINAL_SOURCE_UNAVAILABLE);
            root=existing;
        } else root=materializeSmoothingRoot(seed,source,work,owner);
        var candidate=root.stage().candidates().stream().filter(c->c.id().value().equals(seed.selectedChoice())).findFirst().orElseThrow();
        source.requireCurrent();return new SmoothingRoot(root,candidate,source);
        }
    }

    private static void requireCovered(SupportedInputRasterTransform transform,MetricRasterGrid grid,int width,int height) {
        var bounds=transform.boundsForMetricCell(grid.coordinateFrame(),grid.footprint().polygons().get(0));
        if(bounds.minimumX()<0||bounds.minimumY()<0||bounds.maximumX()>=width-1||bounds.maximumY()>=height-1)
            throw new SmoothingUnavailable(SmoothingUnavailableReason.SOURCE_COVERAGE_UNAVAILABLE);
    }
    private static MetricRasterGrid visibleSmoothingGrid(LiveBPreviewService.LegacyRootSeed facts,double pitch,GeometryWorkContext work) {
        var decision=MetricCorridorRegion.aroundPolyline(facts.sourceMetric(),facts.decisionRadiusMeters());
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
        for(var polygon:decision.polygons())for(var point:polygon){work.visit(GeometryWorkContext.Kind.BIND,1);minX=Math.min(minX,point.xMeters());minY=Math.min(minY,point.yMeters());maxX=Math.max(maxX,point.xMeters());maxY=Math.max(maxY,point.yMeters());}
        minX-=pitch;minY-=pitch;maxX+=pitch;maxY+=pitch;
        double width=Math.ceil((maxX-minX)/pitch),height=Math.ceil((maxY-minY)/pitch);
        if(!Double.isFinite(width)||!Double.isFinite(height)||width>Integer.MAX_VALUE||height>Integer.MAX_VALUE)throw new SmoothingUnavailable(SmoothingUnavailableReason.SOURCE_COVERAGE_UNAVAILABLE);
        return new MetricRasterGrid(facts.frame(),new MetricPoint(minX+.5*pitch,maxY-.5*pitch),1,0,0,-1,pitch,Math.max(2,(int)width),Math.max(2,(int)height));
    }

    private Root materializeSmoothingRoot(SmoothingSeed seed,RetainedSmoothingSource source,GeometryWorkContext work,AttemptMemoryLedger.Owner owner) {
        var facts=seed.facts();var evidence=source.evidence();var config=seed.config();
        var chainage=new ProbabilisticProfileFactory().profileChainage(facts.sourceMetric(),facts.sampleStepMeters());
        var request=new TraceRequest(facts.specification().selectedWayKey(),facts.specification().selectedRange(),TrackerMode.LEGACY_V02,config.heatmap().alignmentMode(),facts.specification().permissions(),TraceBudgets.defaults(),
            evidence.snapshotId(),evidence.canonicalHash(work),facts.network().snapshotId(),facts.network().canonicalHash(),facts.settingsHash(),"original-legacy-authority-v1","original-legacy-reference-window-v1",facts.sampleStepMeters(),chainage,evidence.resolution());
        var authority=new RefinementRootAuthority(facts.network(),request,evidence,config,facts.projectionCode(),facts.sourceIdentity(),facts.partition(),source,work,owner);
        var way=(DetachedWay)facts.network().primitives().get(request.selectedWayKey());var sourceNodes=new ArrayList<RefinementPayload.SourceNode>();
        for(var key:way.nodeKeys()){work.visit(GeometryWorkContext.Kind.BIND,1);var node=(DetachedNode)facts.network().primitives().get(key);sourceNodes.add(new RefinementPayload.SourceNode(key.id(),node.coordinate().latitudeDegrees(),node.coordinate().longitudeDegrees()));}
        var provenance=new RefinementPayload.SourceSelection(way.key().id(),request.selectedRange().firstIndex(),request.selectedRange().lastIndex(),sourceNodes,
            way.nodeKeys().subList(request.selectedRange().firstIndex(),request.selectedRange().lastIndex()+1).stream().map(PrimitiveKey::id).toList());
        long metadataBytes=Math.addExact(16384L,Math.multiplyExact(8192L,(long)way.nodeKeys().size()+seed.original().candidates().stream().mapToLong(c->c.finalPreviewPoints().size()).sum()));
        try(var materialization=work.reserveOn(owner,"legacy-smoothing-root-choices",metadataBytes)) {
            var choices=new ArrayList<Candidate>();int index=0;
            for(var original:seed.original().candidates()) {
                work.visit(GeometryWorkContext.Kind.BIND,1);
                var id=new CandidateId("legacy-choice-"+index++);
                if(original.finalPreviewPoints().size()<2)continue;
                var one=new AlignmentResult(seed.original().selection(),null,List.of(original),seed.original().sourcePolyline(),original.finalPreviewPoints(),List.of(),null,null,
                    seed.original().detectorAttempts().stream().filter(a->a.candidateIds().contains(original.id())).toList(),
                    seed.original().applicableCandidates().contains(original)?List.of(original):List.of());
                var payload=RefinementPayload.captureOriginal(one,provenance,seed.payloadLimit());
                var projection=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode(facts.projectionCode());
                var points=original.finalPreviewPoints().stream().map(p->{work.visit(GeometryWorkContext.Kind.BIND,1);var ll=projection.eastNorth2latlon(p);return facts.frame().toMetric(new GeographicPoint(ll.lat(),ll.lon()));}).toList();
                var arcs=correspondence(facts,original,points);var palettes=one.detectorAttempts().stream().map(DetectorAttempt::sourcePalette).distinct().toList();
                if(palettes.size()!=1)throw new SmoothingUnavailable(SmoothingUnavailableReason.SOURCE_MAPPING_UNAVAILABLE);
                choices.add(new Candidate(id,payload,arcs,facts.sourceIdentity()+"/"+id.value(),original.id(),palettes.get(0)));
            }
            var stage=Stage.root(choices,new CandidateId(seed.selectedChoice()));materialization.adopt(stage);return new Root(authority,stage,facts.receipt(),source);
        }
    }

    /** Bind the original source and authority to the caller's existing operation context. */
    public Seed capture(LegacyReviewOwner review, TileFetchCoordinator coordinator, GeometryWorkContext work) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Original facts require EDT");
        work.visit(GeometryWorkContext.Kind.BIND,1);
        review.requireLegacyAuthorityCurrent();
        var config=review.originalConfig();
        var mode=config.heatmap().intensitySamplingMode();
        if (review.managedReceipt()==null && mode!=null && !mode.usesColorMapping())
            throw new SmoothingUnavailable(SmoothingUnavailableReason.SOURCE_MAPPING_UNAVAILABLE);
        var preparation=work.owner().child("original-refinement-preparation");
        try {
        var facts=captureForSmoothing(review,coordinator,work,null,preparation);
        OriginalSource binding=facts instanceof ManagedSeed managed
            ? new ManagedOriginalSource(managed.receipt(),facts.original())
            : new VisibleOriginalSource(((VisibleSeed)facts).metadata(),facts.original());
        var settings=RefinementSourcePlanner.Settings.capture(binding.managed(),config.heatmap(),AlignmentService.alternativeDetectorMappings());
        binding.requireCurrent(coordinator); review.requireLegacyAuthorityCurrent();
        return new Seed(facts.facts(),facts.original(),config,binding,settings,facts.selectedChoice(),work.owner(),facts.payloadLimit(),facts,work,review,preparation);
        } catch (RuntimeException | Error failure) { preparation.close(); throw failure; }
    }

    /** Complete factual conversion under the exact same operation allowance and source authority. */
    public Root complete(Seed seed, TileFetchCoordinator coordinator, GeometryWorkContext work) {
        if (seed.context()!=work || seed.factualSeed()==null) throw new IllegalArgumentException("Original preparation requires the same work context");
        try {
            work.visit(GeometryWorkContext.Kind.BIND,1);
            requireReviewCurrentOnEdt(seed.review()); seed.originalSource().requireCurrent(coordinator);
            var result=completeForSmoothing(seed.factualSeed(),coordinator,work,null,seed.preparation());
            requireReviewCurrentOnEdt(seed.review()); seed.originalSource().requireCurrent(coordinator);
            work.visit(GeometryWorkContext.Kind.BIND,1);
            seed.preparation().transferTo(work.owner()); seed.preparation().close();
            return result.root();
        } catch (RuntimeException | Error failure) { seed.preparation().close(); throw failure; }
    }

    private static void requireReviewCurrentOnEdt(LegacyReviewOwner review) {
        if (SwingUtilities.isEventDispatchThread()) { review.requireLegacyAuthorityCurrent(); return; }
        try { SwingUtilities.invokeAndWait(review::requireLegacyAuthorityCurrent); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while validating original review",interrupted); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failure.getCause());
        }
    }

    public Seed capture(LegacyReviewOwner review, TileFetchCoordinator coordinator, AttemptMemoryLedger.Owner memory) {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Original facts require EDT");
        review.requireOriginalAttemptCurrent();
        var receipt=review.managedReceipt();
        if(receipt==null)throw new IllegalStateException("The original managed source receipt is unavailable");
        var original=review.originalResult();var config=review.originalConfig();
        receipt.requireCurrent(coordinator,original.tileMosaics());
        var mosaic=original.tileMosaics().require(AlignmentTileSourcePlan.from(config.heatmap()).selectedColor());
        double length=0;for(int i=1;i<original.sourcePolyline().size();i++)length+=original.sourcePolyline().get(i).distance(original.sourcePolyline().get(i-1));
        var shape=TileHeatmapSampler.legacySamplingShape(length,original.sourcePolyline().size(),mosaic.parameters());
        // This is the original reference decision window, not the native acquisition padding.
        double radius=shape.referenceHalfWidthPx()*RenderedHeatmapSampler.RASTER_SCALE/mosaic.virtualRasterScale()*mosaic.parameters().metersPerPixel();
        var seed=new LiveBPreviewService().captureLegacyRootSeed(review.dataSet(),original.selection(),config,radius,
            mosaic.parameters().sampleStepMeters(),review.sourceIdentity(),memory);
        long maximum=RefinementLimits.defaults().maxPayloadBytes();
        long estimate=RefinementPayload.estimateBytes(original);
        if(estimate>maximum)throw new RefinementSession.Refused(RefusalReason.PAYLOAD_LIMIT);
        memory.reserve(estimate).adopt(original);
        var settings=RefinementSourcePlanner.Settings.capture(true,config.heatmap(),AlignmentService.alternativeDetectorMappings());
        review.requireOriginalAttemptCurrent();receipt.requireCurrent(coordinator,original.tileMosaics());
        return new Seed(seed.value(),original,config,receipt,settings,review.choiceId(review.selectedCandidate()),memory,maximum);
    }

    public Root complete(Seed seed,TileFetchCoordinator coordinator,CancellationProbe cancellation) {
        if(SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Original pixels require worker thread");
        cancellation.checkpoint();seed.receipt().requireCurrent(coordinator,seed.original().tileMosaics());
        var facts=seed.facts();var config=seed.config();var sourcePlan=AlignmentTileSourcePlan.from(config.heatmap());
        var mosaic=seed.original().tileMosaics().require(sourcePlan.selectedColor());
        var grid=ManagedModernPreviewSource.managedOutputGrid(facts.frame(),facts.sourceMetric(),mosaic.zoom(),facts.decisionRadiusMeters());
        long cells=Math.addExact((long)mosaic.image().getWidth()*mosaic.image().getHeight(),(long)grid.width()*grid.height());
        var scratch=seed.memory().reserve(Math.addExact(1048576L,Math.multiplyExact(cells,96L)));
        var retained=seed.memory().reserve(Math.addExact(1048576L,Math.multiplyExact((long)grid.width()*grid.height(),80L)));
        try {
            var valid=new boolean[Math.multiplyExact(mosaic.image().getWidth(),mosaic.image().getHeight())];Arrays.fill(valid,true);
            var mode=config.heatmap().intensitySamplingMode();if(mode==null)mode=IntensitySamplingMode.COLOR_MAPPING;
            final var intensity=mode;
            var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                mode.usesColorMapping()?EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING:EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                sourcePlan.selectedColor(),EvidenceCorrelationGroup.STRAVA_RENDERINGS,false);
            var field=RasterEvidenceCapture.FieldSpec.direct("native",argb->ManagedModernPreviewSource.scalarIntensity(argb,sourcePlan.selectedColor(),intensity),lineage);
            var transform=SupportedInputRasterTransform.webMercator(mosaic.zoom(),mosaic.originWorldPxX(),mosaic.originWorldPxY(),1.0);
            var evidence=new RasterEvidenceCapture().captureWithMetricSource(facts.network().snapshotId()+"/original-evidence",mosaic.image(),valid,
                facts.sourceGeographic(),facts.sourceMetric(),transform,grid,EvidenceResolution.nativeSource(mosaic.parameters().metersPerPixel(),grid.pitchMeters()),
                facts.decisionRadiusMeters(),facts.sourceIdentity(),EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,List.of(field),cancellation);
            var chainage=new ProbabilisticProfileFactory().profileChainage(facts.sourceMetric(),facts.sampleStepMeters());
            var request=new TraceRequest(facts.specification().selectedWayKey(),facts.specification().selectedRange(),TrackerMode.LEGACY_V02,
                config.heatmap().alignmentMode(),facts.specification().permissions(),TraceBudgets.defaults(),evidence.snapshotId(),evidence.canonicalHash(),
                facts.network().snapshotId(),facts.network().canonicalHash(),facts.settingsHash(),"original-legacy-authority-v1","original-legacy-reference-window-v1",
                facts.sampleStepMeters(),chainage,evidence.resolution());
            var authority=new RefinementRootAuthority(facts.network(),request,evidence,config,facts.projectionCode(),facts.sourceIdentity(),facts.partition());
            var way=(DetachedWay)facts.network().primitives().get(request.selectedWayKey());
            var sourceNodes=way.nodeKeys().stream().map(key->{var n=(DetachedNode)facts.network().primitives().get(key);return new RefinementPayload.SourceNode(key.id(),n.coordinate().latitudeDegrees(),n.coordinate().longitudeDegrees());}).toList();
            var provenance=new RefinementPayload.SourceSelection(way.key().id(),request.selectedRange().firstIndex(),request.selectedRange().lastIndex(),sourceNodes,
                way.nodeKeys().subList(request.selectedRange().firstIndex(),request.selectedRange().lastIndex()+1).stream().map(PrimitiveKey::id).toList());
            var choices=new ArrayList<Candidate>();int index=0;
            for(var original:seed.original().candidates()) {
                cancellation.checkpoint();
                var id=new CandidateId("legacy-choice-"+index++);
                if(original.finalPreviewPoints().size()<2)continue;
                // Per-choice payload isolates duplicate native IDs without relabeling the native geometry.
                var one=new AlignmentResult(seed.original().selection(),null,List.of(original),seed.original().sourcePolyline(),original.finalPreviewPoints(),List.of(),null,null,
                    seed.original().detectorAttempts().stream().filter(a->a.candidateIds().contains(original.id())).toList(),List.of(original));
                var payload=RefinementPayload.captureOriginal(one,provenance,seed.payloadLimit());
                var projected=original.finalPreviewPoints();
                var points=projected.stream().map(p->{var ll=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode(facts.projectionCode()).eastNorth2latlon(p);return facts.frame().toMetric(new GeographicPoint(ll.lat(),ll.lon()));}).toList();
                var arcs=correspondence(facts,original,points);
                var palettes=one.detectorAttempts().stream().map(DetectorAttempt::sourcePalette).distinct().toList();
                if(palettes.size()!=1)throw new IllegalStateException("The original candidate source palette is ambiguous");
                choices.add(new Candidate(id,payload,arcs,facts.sourceIdentity()+"/"+id.value(),original.id(),palettes.get(0)));
            }
            var stage=Stage.root(choices,new CandidateId(seed.selectedChoice()));
            retained.adopt(authority);
            cancellation.checkpoint();seed.receipt().requireCurrent(coordinator,seed.original().tileMosaics());
            return new Root(authority,stage,facts.receipt());
        } finally {scratch.close();}
    }
    private static List<ParentArc> correspondence(LiveBPreviewService.LegacyRootSeed facts,CenterlineCandidate candidate,List<MetricPoint> points) {
        var range=facts.specification().selectedRange();var arcs=new ArrayList<ParentArc>();
        if(facts.network().closure().mayCreateNodes()==false) {
            if(points.size()!=range.size())throw new IllegalStateException("Original Move candidate occurrence count differs");
            for(int i=0;i<range.size();i++)arcs.add(new ParentArc(range.firstIndex()+i,Math.min(i,points.size()-2),i==points.size()-1?1:0));
            return arcs;
        }
        // Original precise replacement retains protected/shared identities at their actual candidate-owned targets.
        var way=(DetachedWay)facts.network().primitives().get(facts.specification().selectedWayKey());
        int previous=-1;
        for(int i=0;i<range.size();i++) {
            var key=way.nodeKeys().get(range.firstIndex()+i);
            if(facts.network().closure().removableExistingNodeKeys().contains(key))continue;
            var target=candidate.proposedNodePositions().get(key.id());
            MetricPoint expected=facts.sourceMetric().get(i);
            if(target!=null){var ll=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode(facts.projectionCode()).eastNorth2latlon(target);expected=facts.frame().toMetric(new GeographicPoint(ll.lat(),ll.lon()));}
            int best=-1;double distance=Double.POSITIVE_INFINITY;
            for(int j=previous+1;j<points.size();j++){double d=points.get(j).distanceTo(expected);if(d<distance){distance=d;best=j;}}
            if(best<0||distance>1e-6)throw new IllegalStateException("Original protected occurrence lacks exact final-preview correspondence");
            arcs.add(new ParentArc(range.firstIndex()+i,Math.min(best,points.size()-2),best==points.size()-1?1:0));previous=best;
        }
        // Removable occurrences still need geometric lineage. Interpolate their original
        // chainage monotonically BETWEEN the exact protected correspondences. This does
        // not assign a surviving Node identity or grant movement/removal authority.
        double[] sourceChain=new double[range.size()],parentChain=new double[points.size()];
        for(int i=1;i<sourceChain.length;i++)sourceChain[i]=sourceChain[i-1]+facts.sourceMetric().get(i-1).distanceTo(facts.sourceMetric().get(i));
        for(int i=1;i<parentChain.length;i++)parentChain[i]=parentChain[i-1]+points.get(i-1).distanceTo(points.get(i));
        var complete=new ArrayList<ParentArc>();
        for(int interval=0;interval<arcs.size()-1;interval++) {
            var left=arcs.get(interval);var right=arcs.get(interval+1);complete.add(left);
            int first=left.originalOccurrence()-range.firstIndex(),last=right.originalOccurrence()-range.firstIndex();
            double sourceSpan=sourceChain[last]-sourceChain[first];
            double start=parentChain[left.parentEdge()]+left.fraction()*(parentChain[left.parentEdge()+1]-parentChain[left.parentEdge()]);
            double end=parentChain[right.parentEdge()]+right.fraction()*(parentChain[right.parentEdge()+1]-parentChain[right.parentEdge()]);
            if(!(sourceSpan>0)||!(end>start))throw new IllegalStateException("Original occurrence lineage is degenerate");
            for(int i=first+1;i<last;i++) {
                double at=start+(end-start)*(sourceChain[i]-sourceChain[first])/sourceSpan;int edge=left.parentEdge();
                while(edge<points.size()-2&&parentChain[edge+1]<=at)edge++;
                double fraction=(at-parentChain[edge])/(parentChain[edge+1]-parentChain[edge]);
                complete.add(new ParentArc(range.firstIndex()+i,edge,fraction));
            }
        }
        complete.add(arcs.get(arcs.size()-1));return complete;
    }
}
