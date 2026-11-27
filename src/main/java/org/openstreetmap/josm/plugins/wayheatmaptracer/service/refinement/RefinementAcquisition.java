package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

/** Parent-centered coordinator acquisition and scalar-before-resampling evidence, without OSM writes. */
public final class RefinementAcquisition {
    /** One preflight authority shared verbatim with the renderer request and scalar capture. */
    public static VisibleEvidencePlan.Support visibleSupport(VisibleEvidencePlan plan,LiveBPreviewService.VisibleRaster raster) {
        return Objects.requireNonNull(plan).support(raster);
    }
    /** Live conversion is explicit; standalone port capture never pays for this additional copy. */
    public static Accounted<VisibleCapture> captureVisible(RefinementSourcePlanner.Plan sourcePlan,
            VisibleEvidencePlan evidencePlan,VisibleRefinementCapture.Frame frame,String mapping,GeometryWorkContext work) {
        evidencePlan.requireWork(work);
        if(evidencePlan.sourcePlan()!=sourcePlan)throw new IllegalArgumentException("Visible plan identity differs");
        if(frame.evidencePlan()!=evidencePlan)throw new IllegalArgumentException("Visible frame belongs to another plan");
        frame.requireCurrent();
        if(!frame.commonResolution())throw new UnsupportedOperationException("visible-spatial-resolution-unavailable");
        if(sourcePlan.requests().stream().noneMatch(r->r.palette().equals(sourcePlan.settings().selectedPalette())&&r.mapping().equals(mapping)&&!r.aggregate()&&r.unavailableReason().isEmpty()))throw new IllegalArgumentException("Visible mapping not requested");
        evidencePlan.requireActualSourcePitch(frame.nativePitchMeters().orElse(frame.groundMetersPerViewPixel()/6));
        var copied=frame.copyRaster(work);
        try{return captureVisible(sourcePlan,evidencePlan,copied.value(),mapping,work);}finally{copied.owner().close();}
    }
    public static Accounted<VisibleCapture> captureVisible(RefinementSourcePlanner.Plan sourcePlan,
            VisibleEvidencePlan evidencePlan,LiveBPreviewService.VisibleRaster raster,String mapping,GeometryWorkContext work) {
        return captureVisible(sourcePlan,evidencePlan,raster,mapping,work,Optional.empty());
    }
    /** Only the exact original producer raster may reuse its already measured ARGB hash. */
    public static Accounted<VisibleCapture> captureVisible(RefinementSourcePlanner.Plan sourcePlan,
            VisibleEvidencePlan evidencePlan,LiveBPreviewService.VisibleRaster raster,String mapping,GeometryWorkContext work,
            Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService.VisibleInputReceipt> originalReceipt) {
        evidencePlan.requireWork(work);
        if(evidencePlan.sourcePlan()!=sourcePlan)throw new IllegalArgumentException("Visible plan identity differs");
        if(visibleSupport(evidencePlan,raster)!=VisibleEvidencePlan.Support.COVERED)throw new IllegalStateException("Actual visible frame lacks engine support");
        evidencePlan.requireActualSourcePitch(raster.nativePitchMeters().orElse(raster.groundMetersPerViewPixel()/6));
        if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Visible evidence copies require worker thread");
        var request=sourcePlan.requests().stream().filter(r->r.palette().equals(sourcePlan.settings().selectedPalette())&&r.mapping().equals(mapping)&&!r.aggregate()&&r.unavailableReason().isEmpty()).findFirst().orElseThrow(()->new IllegalArgumentException("Visible mapping not requested"));
        var provedHash=Objects.requireNonNull(originalReceipt).map(receipt->provedOriginalVisibleInputHash(raster,receipt,work));
        var memory=work.owner().child("frozen-visible-conversion");
        try {
            long count=Math.multiplyExact((long)raster.width(),raster.height());
            try(var copies=work.reserveOn(memory,"visible-copy-and-validity",Math.addExact(8192,Math.multiplyExact(count,9)))) {
                for(long start=0;start<count;start+=256)work.visit(GeometryWorkContext.Kind.COPY,Math.min(256,count-start));
                var argb=raster.argb();
                var image=new java.awt.image.BufferedImage(raster.width(),raster.height(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var valid=new boolean[Math.toIntExact(count)];
                for(int y=0;y<raster.height();y++)for(int x=0;x<raster.width();x+=256) {
                    int length=Math.min(256,raster.width()-x);work.visit(GeometryWorkContext.Kind.COPY,length);
                    image.setRGB(x,y,length,1,argb,y*raster.width()+x,raster.width());Arrays.fill(valid,y*raster.width()+x,y*raster.width()+x+length,true);
                }
                String inputHash=provedHash.orElseGet(()->RasterEvidenceCapture.hashOriginalArgb(image,work,memory));
                var grid=evidencePlan.grid();var transform=org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.visibleWebMercator(raster.minimumEast(),raster.maximumNorth(),raster.projectionUnitsPerViewPixel(),6);
                double inputPitch=raster.groundMetersPerViewPixel()/6;
                var resolution=raster.nativePitchMeters().isPresent()?EvidenceResolution.nativeSource(raster.nativePitchMeters().getAsDouble(),inputPitch):EvidenceResolution.renderedOnly(inputPitch);
                var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING,mapping,EvidenceCorrelationGroup.STRAVA_RENDERINGS,false);
                var field=RasterEvidenceCapture.FieldSpec.direct("native",v->ManagedModernPreviewSource.scalarIntensity(v,mapping,sourcePlan.settings().intensity()),lineage);
                CancellationProbe guard=()->{work.visit(GeometryWorkContext.Kind.BIND,1);evidencePlan.requireCurrent();return false;};
                var converted=new RasterEvidenceCapture().captureWithMetricSource(evidencePlan.canonicalIdentity()+"/"+mapping,image,valid,sourcePlan.parent().points().stream().map(grid.coordinateFrame()::toGeographic).toList(),sourcePlan.parent().points(),transform,grid,resolution,evidencePlan.decisionRadiusMeters(),raster.sourceIdentity(),EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,List.of(field),guard,work,memory);
                evidencePlan.requireCurrent();var receipt=evidencePlan.original().sourceReceipt();
                var descriptor=new RetainedSmoothingSource(request,sourcePlan.settings().intensity(),"native",converted.evidence(),transform,raster.width(),raster.height(),converted.retainedBytes(),evidencePlan::requireCurrent,raster.sourceIdentity()+"/visible-input-ownership/"+receipt.revision()+"/"+transform.parameterIdentity()+"/"+inputHash,inputHash,OptionalLong.empty(),Optional.of(receipt),Optional.empty(),RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL);
                try(var metadata=work.reserveOn(memory,"visible-descriptor",descriptor.descriptorBytes())){metadata.adopt(descriptor);}
                return new Accounted<>(new VisibleCapture(sourcePlan,converted.evidence(),receipt,memory,descriptor),memory);
            }
        }catch(RuntimeException|Error failure){memory.close();throw failure;}
    }
    static String provedOriginalVisibleInputHash(LiveBPreviewService.VisibleRaster raster,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService.VisibleInputReceipt receipt,GeometryWorkContext work) {
        work.visit(GeometryWorkContext.Kind.BIND,8);receipt.requireRaster(raster);return receipt.inputArgbHash();
    }
    private final ManagedModernPreviewSource source;
    private final TileFetchCoordinator coordinator;
    public RefinementAcquisition(ManagedModernPreviewSource source,TileFetchCoordinator coordinator) {
        this.source=Objects.requireNonNull(source);this.coordinator=Objects.requireNonNull(coordinator);
    }
    /** Visible source acquisition reuses only factual captured pixels; it cannot fetch palettes. */
    public static Accounted<VisibleCapture> captureVisible(RefinementSourcePlanner.Plan plan,
            LiveBPreviewService.VisibleRaster raster,String mapping,CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        return captureVisible(plan,raster,mapping,cancellation,owner,null,null);
    }
    public static Accounted<VisibleCapture> captureVisible(RefinementSourcePlanner.Plan plan,
            LiveBPreviewService.VisibleRaster raster,String mapping,CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner,GeometryWorkContext hashWork,RasterEvidenceCapture.OriginalInputHashes hashes) {
        if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Visible evidence copies require worker thread");
        if((hashWork==null)!=(hashes==null))throw new IllegalArgumentException("Shared original hash context and cache required together");
        if(hashWork!=null){if(!owner.sharesLedgerWith(hashWork.owner()))throw new IllegalArgumentException("Original hash owner belongs to another attempt");hashes.requireContext(hashWork);}
        requirePlan(plan);
        if(plan.settings().managed()||!plan.settings().intensity().usesColorMapping()
                ||plan.ticket().kind()==RefinementModels.StageKind.SMOOTH)
            throw new IllegalStateException("Visible source combination is unavailable");
        if(!raster.sourceIdentity().equals(plan.parent().root().sourceIdentity())
                ||!raster.projectionCode().equals(plan.parent().root().projectionCode())
                ||!"EPSG:3857".equals(raster.projectionCode())||raster.sourceReceipt()==null)
            throw new IllegalStateException("Visible source/frame ownership is unavailable");
        if(plan.requests().stream().noneMatch(r->r.palette().equals(plan.settings().selectedPalette())
                &&r.mapping().equals(mapping)&&r.unavailableReason().isEmpty()))
            throw new IllegalArgumentException("Visible detector mapping was not requested");
        cancellation.checkpoint();raster.sourceReceipt().owner().requireCurrent(raster.sourceReceipt());
        var memory=owner.child("refinement-visible-evidence");
        try {
            var geometry=memory.reserve(frameWorkspaceBytes(plan.parent()));
            geometry.adopt(new Object());
        double pitch=raster.groundMetersPerViewPixel()/RenderedHeatmapSampler.RASTER_SCALE;
        var grid=gridAtPitch(plan.parent(),plan.parent().root().request().permissions().ordinaryRadiusMeters(),pitch);
        var frame=grid.coordinateFrame();
        var transform=org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.visibleWebMercator(
            raster.minimumEast(),raster.maximumNorth(),raster.projectionUnitsPerViewPixel(),RenderedHeatmapSampler.RASTER_SCALE);
        grid.footprint().polygons().forEach(poly->poly.forEach(frame::toGeographic));
        var bounds=transform.boundsForMetricCell(frame,grid.footprint().polygons().get(0));
        if(bounds.minimumX()<0||bounds.minimumY()<0||bounds.maximumX()>raster.width()-1||bounds.maximumY()>raster.height()-1)
            throw new IllegalStateException("Visible parent corridor and halo exceed captured raster");
            long input=(long)raster.width()*raster.height(),output=(long)grid.width()*grid.height();
            var reservation=memory.reserve(Math.addExact(1048576L,Math.multiplyExact(Math.addExact(input,output),96L)));
            var retained=memory.reserve(retainedEvidenceBytes(plan,grid,mapping));
            var image=new java.awt.image.BufferedImage(raster.width(),raster.height(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0,0,raster.width(),raster.height(),raster.argb(),0,raster.width());
            String inputHash=hashes==null?"":hashes.single(raster,image);
            cancellation.checkpoint();raster.sourceReceipt().owner().requireCurrent(raster.sourceReceipt());
            var valid=new boolean[Math.multiplyExact(raster.width(),raster.height())];Arrays.fill(valid,true);
            var resolution=raster.nativePitchMeters().isPresent()?EvidenceResolution.nativeSource(raster.nativePitchMeters().getAsDouble(),pitch):EvidenceResolution.renderedOnly(pitch);
            var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,
                EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING,mapping,EvidenceCorrelationGroup.STRAVA_RENDERINGS,false);
            var field=RasterEvidenceCapture.FieldSpec.direct("native",argb->ManagedModernPreviewSource.scalarIntensity(argb,mapping,plan.settings().intensity()),lineage);
            var geographic=plan.parent().points().stream().map(frame::toGeographic).toList();
            var result=new RasterEvidenceCapture().captureWithMetricSource(plan.operationIdentity()+"/visible/"+mapping+"/"+plan.parent().canonicalIdentity(),
                image,valid,geographic,plan.parent().points(),transform,grid,resolution,
                plan.parent().root().request().permissions().ordinaryRadiusMeters(),raster.sourceIdentity(),
                EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,List.of(field),cancellation);
            cancellation.checkpoint();raster.sourceReceipt().owner().requireCurrent(raster.sourceReceipt());
            var request=plan.requests().stream().filter(s->s.palette().equals(plan.settings().selectedPalette())&&s.mapping().equals(mapping)
                &&!s.aggregate()&&s.unavailableReason().isEmpty()).findFirst().orElseThrow();
            var receipt=raster.sourceReceipt();
            var descriptor=new RetainedSmoothingSource(request,plan.settings().intensity(),"native",result,transform,raster.width(),raster.height(),
                retainedEvidenceBytes(plan,grid,mapping),()->receipt.owner().requireCurrent(receipt),raster.sourceIdentity()+"/visible-input-ownership/"+receipt.revision(),inputHash,OptionalLong.empty(),Optional.of(receipt),Optional.empty(),
                hashes==null?"":RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL);
            var metadata=memory.reserve(descriptor.descriptorBytes());
            retained.adopt(result);metadata.adopt(descriptor);reservation.close();return new Accounted<>(new VisibleCapture(plan,result,raster.sourceReceipt(),memory,descriptor),memory);
        } catch(RuntimeException|Error failure){memory.close();throw failure;}
    }
    /** Visible evidence retains its actual source receipt and native memory ownership. */
    public static final class VisibleCapture {
        private final RefinementSourcePlanner.Plan plan;
        private final EvidenceSnapshot evidence;
        private final org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch.Receipt receipt;
        private final AttemptMemoryLedger.Owner owner;
        private final RetainedSmoothingSource descriptor;
        private VisibleCapture(RefinementSourcePlanner.Plan plan,EvidenceSnapshot evidence,
                org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch.Receipt receipt,AttemptMemoryLedger.Owner owner,
                RetainedSmoothingSource descriptor) {
            this.plan=plan;this.evidence=evidence;this.receipt=receipt;this.owner=owner;this.descriptor=descriptor;
        }
        public RefinementSourcePlanner.Plan plan(){return plan;}
        public void requireCurrent() {
            if(owner.isClosed())throw new IllegalStateException("Visible evidence owner has settled");
            try{receipt.owner().requireCurrent(receipt);}catch(IllegalStateException stale){throw new VisibleEvidencePlan.StaleSource(plan);}
        }
        public EvidenceSnapshot evidence(){requireCurrent();return evidence;}
        public RetainedSmoothingSource retentionDescriptor(RefinementSourcePlanner.SourceRequest source,EvidenceSnapshot actual) {
            if(actual!=evidence||!source.equals(descriptor.source()))throw new IllegalArgumentException("Visible mapping belongs to another capture");
            descriptor.requireCurrent();return descriptor;
        }
    }
    /** Returned imagery/evidence stays owned by the supplied native operation ledger, not its cache. */
    public Accounted<Capture> acquire(RefinementSourcePlanner.Plan plan,CredentialSnapshot credentials,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        return acquire(plan,credentials,cancellation,owner,new ManagedModernPreviewSource.RefinementSourceBudget(64,
            Math.multiplyExact(64L,coordinator.maximumTransportBodyAllowancePerTile()),java.time.Instant.now().plusSeconds(30)));
    }
    /** Allows the pipeline to pass its one remaining source/deadline envelope without restarting it. */
    public Accounted<Capture> acquire(RefinementSourcePlanner.Plan plan,CredentialSnapshot credentials,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner,ManagedModernPreviewSource.RefinementSourceBudget budget) {
        if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Managed evidence acquisition requires worker thread");
        requirePlan(plan);
        if(!plan.settings().managed()||plan.acquiredPalettes().isEmpty())
            throw new IllegalArgumentException("Managed refinement acquisition is unavailable for this source policy");
        var operation=owner.child("refinement-source-operation");
        try {
        var geometry=operation.reserve(frameWorkspaceBytes(plan.parent()));
        var parent=plan.parent();var root=parent.root();var settings=plan.settings();
        double radius=root.request().permissions().ordinaryRadiusMeters();
        var frame=root.evidence().coordinateFrame();
        var geographic=parent.points().stream().map(frame::toGeographic).toList();
        // Construct the finite parent/halo output grid inside the ORIGINAL certified frame.
        // Failure to certify any corner refuses; no extrapolated pixels or expanded edit envelope.
        var grid=grid(parent,radius,settings.inferenceZoom(),plan.ticket().engine());
        certifyImageGrid(grid);
        var request=new ManagedModernPreviewSource.Request(geographic,settings.activity(),plan.acquiredPalettes().get(0),
            settings.inferenceZoom(),settings.generation(),radius,root.request().configuredSampleStepMeters(),root.sourceIdentity(),plan.acquiredPalettes());
        var stamp=coordinator.captureGenerationStamp(settings.generation());
        var acquired=source.acquireRefinementSources(request,new LinkedHashSet<>(List.of(settings.inferenceZoom(),settings.validationZoom())),
            plan.aggregateRequested(),credentials,cancellation,operation,budget,grid);
        acquired.value().requireCurrent(coordinator);
        if(!coordinator.isCurrent(stamp))throw new IllegalStateException("Acquired source generation is stale");
        var capture=new Capture(plan,acquired.value(),operation,coordinator,grid,geographic,stamp);
        geometry.adopt(capture);
        return new Accounted<>(capture,operation);
        } catch(RuntimeException|Error failure){operation.close();throw failure;}
    }
    /** Immutable provenance plus coordinator-sealed source ownership; all pixel work remains off EDT. */
    public static final class Capture {
        private final RefinementSourcePlanner.Plan plan;
        private final ManagedModernPreviewSource.RefinementSources sources;
        private final AttemptMemoryLedger.Owner owner;
        private final TileFetchCoordinator coordinator;
        private final MetricRasterGrid grid;
        private final List<GeographicPoint> geographic;
        private final TileFetchCoordinator.GenerationStamp generationStamp;
        private final Map<EvidenceSnapshot,RetainedSmoothingSource> retainedSources=new IdentityHashMap<>();
        private GeometryWorkContext hashWork;
        private RasterEvidenceCapture.OriginalInputHashes inputHashes;
        private Capture(RefinementSourcePlanner.Plan plan,ManagedModernPreviewSource.RefinementSources sources,
                AttemptMemoryLedger.Owner owner,TileFetchCoordinator coordinator,MetricRasterGrid grid,List<GeographicPoint> geographic) {
            this(plan,sources,owner,coordinator,grid,geographic,coordinator.captureGenerationStamp(plan.settings().generation()));
        }
        private Capture(RefinementSourcePlanner.Plan plan,ManagedModernPreviewSource.RefinementSources sources,
                AttemptMemoryLedger.Owner owner,TileFetchCoordinator coordinator,MetricRasterGrid grid,List<GeographicPoint> geographic,
                TileFetchCoordinator.GenerationStamp generationStamp) {
            this.plan=plan;this.sources=sources;this.owner=owner;this.coordinator=coordinator;this.grid=grid;this.geographic=List.copyOf(geographic);
            this.generationStamp=generationStamp;
        }
        public RefinementSourcePlanner.Plan plan(){return plan;}
        public Set<String> palettes(){return sources.palettes();}
        public Map<String,ManagedModernPreviewSource.RefinementStatus> sourceOutcomes(){return sources.sourceOutcomes();}
        public boolean completeAggregate(){return sources.completeAggregate();}
        public Map<String,Map<Integer,ManagedModernPreviewSource.ZoomReceipt>> sourceReceipts(){return sources.sourceReceipts();}
        public ManagedModernPreviewSource.RefinementWorkReceipt workReceipt(){return sources.workReceipt();}
        public boolean isCurrent(TileFetchCoordinator actual){return actual==coordinator&&coordinator.isCurrent(generationStamp)&&sources.isCurrent(actual);}
        private void requireCurrent(){if(!isCurrent(coordinator))throw new IllegalStateException("Acquired source generation is stale");}
        /** Only a scalar snapshot produced by this capture may be published as its retained source. */
        public RetainedSmoothingSource retentionDescriptor(RefinementSourcePlanner.SourceRequest source,EvidenceSnapshot evidence) {
            var descriptor=retainedSources.get(evidence);
            if(descriptor==null||!descriptor.source().equals(source))throw new IllegalArgumentException("Selected source was not produced by this capture");
            descriptor.requireCurrent();return descriptor;
        }
        /** Fresh evidence/profile locations belong to the exact selected parent, never root-centered rows. */
        public EvidenceSnapshot evidence(String palette,CancellationProbe cancellation) {
            return evidence(palette,palette,false,cancellation);
        }
        public EvidenceSnapshot evidence(String palette,CancellationProbe cancellation,GeometryWorkContext work){return evidence(palette,palette,false,cancellation,work);}
        /** Explicit selected-raster variants are mappings, not additional acquired palettes. */
        public EvidenceSnapshot mappingEvidence(String mapping,CancellationProbe cancellation) {
            return mappingEvidence(mapping,cancellation,null);
        }
        public EvidenceSnapshot mappingEvidence(String mapping,CancellationProbe cancellation,GeometryWorkContext work) {
            var request=plan.requests().stream().filter(source->source.mapping().equals(mapping)
                    &&!source.aggregate()&&source.unavailableReason().isEmpty()).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Detector mapping was not requested"));
            return evidence(request.palette(),mapping,false,cancellation,work);
        }
        public EvidenceSnapshot aggregateEvidence(CancellationProbe cancellation) {
            return aggregateEvidence(cancellation,null);
        }
        public EvidenceSnapshot aggregateEvidence(CancellationProbe cancellation,GeometryWorkContext work) {
            if(!plan.aggregateRequested()||!completeAggregate())throw new IllegalStateException("Complete aggregate unavailable");
            return evidence(plan.acquiredPalettes().get(0),"all-colors-combined",true,cancellation,work);
        }
        private EvidenceSnapshot evidence(String palette,String mapping,boolean aggregate,CancellationProbe cancellation) {
            return evidence(palette,mapping,aggregate,cancellation,null);
        }
        private EvidenceSnapshot evidence(String palette,String mapping,boolean aggregate,CancellationProbe cancellation,GeometryWorkContext work) {
            if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Evidence copies require worker thread");
            var request=plan.requests().stream().filter(s->s.palette().equals(palette)&&s.mapping().equals(mapping)
                    &&s.aggregate()==aggregate&&s.unavailableReason().isEmpty()).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Detector source was not requested"));
            cancellation.checkpoint();requireCurrent();sources.requireCurrent(coordinator);
            if(work!=null) {
                if(!owner.sharesLedgerWith(work.owner()))throw new IllegalArgumentException("Original hash owner belongs to another attempt");
                if(inputHashes==null){hashWork=work;inputHashes=new RasterEvidenceCapture.OriginalInputHashes(work);}else inputHashes.requireContext(work);
            }
            var raster=sources.raster(palette,coordinator);
            String inputHash="",inputHashProtocol="";
            if(work!=null) {
                if(aggregate) {
                    var originals=new ArrayList<RasterEvidenceCapture.NamedOriginalRaster>();
                    for(String name:RefinementSourcePlanner.BASE_PALETTES){var original=sources.raster(name,coordinator);originals.add(new RasterEvidenceCapture.NamedOriginalRaster(name,original.image(),original.transform()));}
                    inputHash=inputHashes.completeSet(sources,originals);inputHashProtocol=RasterEvidenceCapture.ORIGINAL_ARGB_SET_PROTOCOL;
                }else{inputHash=inputHashes.single(raster.image(),raster.image());inputHashProtocol=RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL;}
                cancellation.checkpoint();requireCurrent();sources.requireCurrent(coordinator);
            }
            var bounds=raster.transform().boundsForMetricCell(grid.coordinateFrame(),grid.footprint().polygons().get(0));
            if(bounds.minimumX()<0||bounds.minimumY()<0||bounds.maximumX()>=raster.image().getWidth()-1
                    ||bounds.maximumY()>=raster.image().getHeight()-1)
                throw new ParentTraceReference.UnavailableException(ParentTraceReference.Unavailable.EVIDENCE_COVERAGE);
            long input=(long)raster.image().getWidth()*raster.image().getHeight();
            long output=(long)grid.width()*grid.height();
            // Bounds scalar/mask copies, strict rectification, field construction and provenance together.
            var reservation=owner.reserve(Math.addExact(1048576L,Math.multiplyExact(Math.addExact(input,output),96L)));
            AttemptMemoryLedger.Reservation retained=null;
            try {
                retained=owner.reserve(retainedEvidenceBytes(plan,grid,mapping));
                double[] scalar=aggregate?sources.aggregateScalars(cancellation):raster.scalarValues(mapping,plan.settings().intensity(),cancellation);
                double pitch=ManagedModernPreviewSource.nativePitchMeters(raster.zoom(),grid.coordinateFrame().origin().latitudeDegrees());
                var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                    aggregate?EvidenceFieldLineage.DerivationKind.ALL_COLOR_AGGREGATE:plan.settings().intensity().usesColorMapping()?EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING:EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                    plan.settings().intensity().usesColorMapping()?mapping:palette+"/"+plan.settings().intensity().detectorName(),EvidenceCorrelationGroup.STRAVA_RENDERINGS,aggregate);
                var result=new RasterEvidenceCapture().captureWithMetricScalarSource(
                    plan.operationIdentity()+"/"+mapping+"/"+plan.parent().canonicalIdentity(),raster.image(),raster.validity(),scalar,
                    geographic,plan.parent().points(),raster.transform(),grid,EvidenceResolution.nativeSource(pitch,grid.pitchMeters()),
                    plan.parent().root().request().permissions().ordinaryRadiusMeters(),raster.sourceIdentity(),
                    EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,"native",lineage,cancellation);
                cancellation.checkpoint();requireCurrent();sources.requireCurrent(coordinator);
                // Capture only the external generation owner, never the disposed five-palette acquisition.
                var externalCoordinator=coordinator;var stamp=generationStamp;
                var descriptor=new RetainedSmoothingSource(request,plan.settings().intensity(),"native",result,raster.transform(),
                    raster.image().getWidth(),raster.image().getHeight(),retainedEvidenceBytes(plan,grid,mapping),
                    ()->{if(!externalCoordinator.isCurrent(stamp))throw new IllegalStateException("Retained source generation is stale");},
                    plan.operationIdentity()+"/input-ownership/"+(aggregate?"all-colors":palette),inputHash,OptionalLong.of(plan.settings().generation().value()),Optional.empty(),Optional.empty(),inputHashProtocol);
                var metadata=owner.reserve(descriptor.descriptorBytes());
                retained.adopt(result);metadata.adopt(descriptor);retainedSources.put(result,descriptor);reservation.close();return result;
            } catch(RuntimeException|Error failure){if(retained!=null)retained.close();reservation.close();throw failure;}
        }
    }
    /** Output doubles/vertex/cell masks, bounded region polygons, IDs and provenance; no input pixel buffers survive. */
    private static long retainedEvidenceBytes(RefinementSourcePlanner.Plan plan,MetricRasterGrid grid,String mapping) {
        long pixels=Math.multiplyExact((long)grid.width(),grid.height());
        long metadata=Math.multiplyExact(2048L,plan.parent().points().size());
        long chars=(long)plan.parent().root().sourceIdentity().length()+mapping.length()+plan.operationIdentity().length()+plan.parent().canonicalIdentity().length();
        return Math.addExact(1048576L,Math.addExact(Math.multiplyExact(16L,pixels),Math.addExact(metadata,Math.multiplyExact(8L,chars))));
    }
    private static long frameWorkspaceBytes(ParentTraceReference parent) {
        return Math.addExact(262144L,Math.multiplyExact(1024L,parent.points().size()));
    }
    private static void requirePlan(RefinementSourcePlanner.Plan plan) {
        plan.settings().requireRootPolicy(plan.parent().root());
        if(!plan.equals(RefinementSourcePlanner.plan(plan.ticket(),plan.settings(),plan.parent())))
            throw new IllegalArgumentException("Refinement source plan differs from canonical parent/policy requests");
    }
    private static void certifyImageGrid(MetricRasterGrid grid) {
        try {grid.footprint().polygons().forEach(poly->poly.forEach(grid.coordinateFrame()::toGeographic));}
        catch(IllegalArgumentException unavailable) {
            throw new ParentTraceReference.UnavailableException(ParentTraceReference.Unavailable.EVIDENCE_COVERAGE);
        }
    }
    private static MetricRasterGrid grid(ParentTraceReference parent,double radius,int zoom,TrackerMode engine) {
        var frame=parent.root().evidence().coordinateFrame();
        double pitch=ManagedModernPreviewSource.nativePitchMeters(zoom,frame.origin().latitudeDegrees());
        int halo;
        if(engine==TrackerMode.CORRIDOR_AWARE) {
            // Managed output/source pitches coincide: selected Gaussian bank is R=1,2,4.
            // R4 needs 2*(4-1)+4=10 L0 pixels. Padding13 preserves old padding1 phase (delta12).
            halo=13;
        } else {
            var policy=org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters.defaults().localization();
            double ray=Math.max(policy.minimumOrientationRayMeters(),policy.orientationRayLengthPitches()*pitch);
            halo=Math.toIntExact((long)Math.ceil(ray/pitch)+1L);
        }
        return gridAtPitch(parent,radius,pitch,halo);
    }
    private static MetricRasterGrid gridAtPitch(ParentTraceReference parent,double radius,double pitch) {
        return gridAtPitch(parent,radius,pitch,1);
    }
    private static MetricRasterGrid gridAtPitch(ParentTraceReference parent,double radius,double pitch,int haloPixels) {
        var frame=parent.root().evidence().coordinateFrame();
        var decision=MetricCorridorRegion.aroundPolyline(parent.points(),radius);
        var points=decision.polygons().stream().flatMap(List::stream).toList();
        double halo=haloPixels*pitch;
        double left=points.stream().mapToDouble(MetricPoint::xMeters).min().orElseThrow()-halo;
        double right=points.stream().mapToDouble(MetricPoint::xMeters).max().orElseThrow()+halo;
        double bottom=points.stream().mapToDouble(MetricPoint::yMeters).min().orElseThrow()-halo;
        double top=points.stream().mapToDouble(MetricPoint::yMeters).max().orElseThrow()+halo;
        double width=Math.ceil((right-left)/pitch),height=Math.ceil((top-bottom)/pitch);
        if(!Double.isFinite(width)||!Double.isFinite(height)||width>Integer.MAX_VALUE||height>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Parent halo exceeds finite raster dimensions");
        return new MetricRasterGrid(frame,new MetricPoint(left+.5*pitch,top-.5*pitch),1,0,0,-1,pitch,
            Math.max(2,(int)width),Math.max(2,(int)height));
    }
}
