package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.util.function.LongSupplier;
import java.awt.image.BufferedImage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage;

/** The native capture's actual source, independent of any root detector choice. */
final class NativeRefinementSourceContext implements RefinementSourceContext {
    private final LiveBPreviewService.Captured captured;
    private final AlignmentConfig config;
    private final TileFetchCoordinator coordinator;
    private final LongSupplier guard;
    NativeRefinementSourceContext(LiveBPreviewService.Captured captured,AlignmentConfig config,TileFetchCoordinator coordinator) {
        this.captured=captured;this.config=config;this.coordinator=coordinator;
        long value=captured.network().sourceGeneration();
        if(captured.raster()!=null) {
            var receipt=captured.raster().sourceReceipt();
            if(receipt==null)throw new IllegalStateException("Original visible source receipt unavailable");
            guard=()->{receipt.owner().requireCurrent(receipt);return value;};
        } else {
            if(coordinator==null||captured.sourceOwner()!=null&&captured.sourceOwner()!=coordinator)throw new IllegalStateException("Original managed source owner unavailable");
            var generation=captured.managedRaster().generation();
            guard=()->{if(!coordinator.isActiveGeneration(generation))throw new IllegalStateException("Original managed source changed");return value;};
        }
    }
    public RefinementAttemptDiagnostics.SourceKind kind(){return captured.raster()==null?RefinementAttemptDiagnostics.SourceKind.MANAGED:RefinementAttemptDiagnostics.SourceKind.VISIBLE;}
    public void requireCurrent(){guard.getAsLong();}
    public LongSupplier detachedCommandGeneration(){return guard;}
    public RefinementPipeline.Sources open(RefinementSourcePlanner.Plan plan,Stage parent,GeometryWorkContext work,
            AttemptMemoryLedger.Owner owner,CancellationProbe cancellation) {
        requireCurrent();
        if(captured.managedRaster()!=null) {
            var acquired=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(config.heatmap()),cancellation,owner);
            return RefinementPipeline.managedSources(acquired.value(),coordinator,work);
        }
        var raster=captured.raster();
        var chunk=new LegacyVisibleSource.PaintedChunk(0,0,raster.width(),raster.height(),raster.minimumEast(),raster.minimumNorth(),raster.maximumEast(),raster.maximumNorth(),raster.projectionUnitsPerViewPixel(),raster.groundMetersPerViewPixel(),raster.nativePitchMeters(),-1,raster.projectionCode());
        var painted=new LegacyVisibleSource.PaintedFrame(raster.width(),raster.height(),raster.minimumEast(),raster.minimumNorth(),raster.maximumEast(),raster.maximumNorth(),raster.projectionUnitsPerViewPixel(),raster.groundMetersPerViewPixel(),raster.nativePitchMeters(),raster.projectionCode(),List.of(chunk));
        var metadata=new LegacyVisibleSource(raster.minimumEast(),raster.minimumNorth(),raster.maximumEast(),raster.maximumNorth(),raster.projectionUnitsPerViewPixel(),raster.groundMetersPerViewPixel(),raster.nativePitchMeters(),raster.projectionCode(),raster.sourceReceipt(),OptionalDouble.of(captured.searchRadiusMeters()),OptionalDouble.of(captured.sampleStepMeters()),Optional.of(painted));
        var evidencePlan=VisibleEvidencePlan.create(plan,metadata,work);
        var mappings=new LinkedHashMap<String,RefinementAcquisition.VisibleCapture>();
        for(var request:plan.requests())if(request.unavailableReason().isEmpty()&&!request.aggregate()&&request.palette().equals(plan.settings().selectedPalette())) {
            cancellation.checkpoint();var converted=RefinementAcquisition.captureVisible(plan,evidencePlan,raster,request.mapping(),work);
            converted.owner().transferTo(owner);mappings.put(request.mapping(),converted.value());
        }
        requireCurrent();return RefinementPipeline.visibleSources(mappings);
    }
    static RetainedSmoothingSource retain(LiveBPreviewService.Computed run,RefinementSourcePlanner.Settings settings,
            NativeRefinementSourceContext context,GeometryWorkContext work,AttemptMemoryLedger.Owner memory,IdentityHashMap<Object,String> hashes) {
        context.requireCurrent();var capture=run.captured();var evidence=run.evidence();
        long bytes=0;for(long charge:RefinementRootAuthority.detachedCharges(evidence).values())bytes=Math.addExact(bytes,charge);
        AttemptMemoryLedger.MemoryLease backing;
        try{backing=memory.retainIfAbsent(evidence);}
        catch(IllegalArgumentException absent){backing=work.reserveOn(memory,"native-original-evidence",bytes).adopt(evidence);}
        if(backing.bytes()!=bytes)throw new IllegalStateException("Native evidence group differs from original admission");
        String mapping=ModernReviewRoot.mapping(run);
        var request=new RefinementSourcePlanner.SourceRequest(capture.palette(),mapping,mapping.equals("all-colors-combined"),"");
        SupportedInputRasterTransform transform;int width,height;String hash;
        String hashProtocol=RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL;
        if(request.aggregate()) {
            var sources=capture.sourceRasters();
            if(sources==null||!sources.provenCompleteAggregate(context.coordinator)||!sources.allRequiredZoomsWereAvailableAtAcquisition())
                throw new IllegalStateException("Complete original aggregate input unavailable");
            var selected=sources.selectedRaster();width=selected.image().getWidth();height=selected.image().getHeight();transform=selected.transform();
            hash=hashes.get(sources);
            if(hash==null) {
                var inputs=sources.palettes().entrySet().stream().map(entry->new RasterEvidenceCapture.NamedOriginalRaster(entry.getKey(),entry.getValue().image(),entry.getValue().transform())).toList();
                hash=RasterEvidenceCapture.hashOriginalArgbSet(inputs,work,memory);hashes.put(sources,hash);
            }
            hashProtocol=RasterEvidenceCapture.ORIGINAL_ARGB_SET_PROTOCOL;
        } else if(capture.raster()!=null) {
            var raster=capture.raster();width=raster.width();height=raster.height();
            transform=SupportedInputRasterTransform.visibleWebMercator(raster.minimumEast(),raster.maximumNorth(),raster.projectionUnitsPerViewPixel(),6);
            hash=hashes.get(raster);
            if(hash==null)try(var scratch=work.reserve("native-original-image-hash",Math.addExact(8192,8L*width*height))) {
                var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
                var pixels=raster.argb();
                for(int y=0;y<height;y++)for(int x=0;x<width;x+=256) {
                    int count=Math.min(256,width-x);work.visit(GeometryWorkContext.Kind.COPY,count);
                    image.setRGB(x,y,count,1,pixels,y*width+x,width);
                }
                hash=RasterEvidenceCapture.hashOriginalArgb(image,work,memory);
                hashes.put(raster,hash);
            }
        } else {
            var raster=capture.managedRaster();width=raster.image().getWidth();height=raster.image().getHeight();transform=raster.transform();
            hash=hashes.get(raster.image());
            if(hash==null){hash=RasterEvidenceCapture.hashOriginalArgb(raster.image(),work,memory);hashes.put(raster.image(),hash);}
        }
        var guard=context.detachedCommandGeneration();Runnable current=()->guard.getAsLong();
        return RetainedSmoothingSource.fromOriginalConversion(request,settings.intensity(),run.options().fieldName(),evidence,transform,width,height,bytes,current,
            evidence.sourceIdentity()+"/native-original/"+mapping,hash,capture.managedRaster()==null?OptionalLong.empty():OptionalLong.of(capture.managedRaster().generation().value()),
            capture.raster()==null?Optional.empty():Optional.of(capture.raster().sourceReceipt()),Optional.empty(),hashProtocol,work,memory);
    }
    static List<FinalRoutePointId> intervalPointIds(FixedIntervalEditPlanComposer.Assessment assessment) {
        var plan=assessment.plan().orElseThrow();
        var before=(DetachedWay)plan.before().primitives().get(plan.selectedWayKey());
        var after=(DetachedWay)plan.after().primitives().get(plan.selectedWayKey());
        int first=plan.selectedRange().firstIndex();
        int end=after.nodeKeys().size()-(before.nodeKeys().size()-plan.selectedRange().lastIndex()-1);
        var byKey=new LinkedHashMap<PrimitiveKey,FinalRoutePointId>();
        for(var id:assessment.assignments().keySet()) {
            PrimitiveKey key=id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                ?plan.disconnectedOccurrenceReplacements().getOrDefault(existing.originalOccurrenceIndex(),existing.nodeKey())
                :PrimitiveKey.planned(PrimitiveKey.Type.NODE,((FinalRoutePointId.GeneratedCandidatePoint)id).originalPointIndex());
            if(byKey.put(key,id)!=null)throw new IllegalArgumentException("Interval point identity is duplicated");
        }
        var ids=new ArrayList<FinalRoutePointId>();
        for(var key:after.nodeKeys().subList(first,end)) {
            var id=byKey.remove(key);if(id==null)throw new IllegalArgumentException("Interval final point identity unavailable");
            var actual=((DetachedNode)plan.after().primitives().get(key)).coordinate();
            if(plan.metricFrame().toMetric(actual).distanceTo(assessment.assignments().get(id))>1e-6)
                throw new IllegalArgumentException("Interval final assignment differs from plan");
            ids.add(id);
        }
        if(!byKey.isEmpty())throw new IllegalArgumentException("Interval assignments exceed selected range");
        return List.copyOf(ids);
    }
    static FinalGeometryEvaluator.Result intervalQuality(LiveBPreviewService.Computed run,FixedIntervalEditPlanComposer.Assessment assessment,GeometryWorkContext work) {
        var ids=intervalPointIds(assessment);var points=ids.stream().map(assessment.assignments()::get).toList();
        var protectedPoints=new LinkedHashMap<Integer,MetricPoint>();
        for(int i=0;i<ids.size();i++)if(ids.get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                &&run.captured().network().closure().protectedExistingNodeKeys().contains(existing.nodeKey()))protectedPoints.put(i,points.get(i));
        var image=ImageCostField.fromEvidence(run.evidence(),run.options().fieldName(),work);
        return new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request(assessment.plan().orElseThrow().routeIdentity(),points,ids,image,
            run.evidence().resolution().effectivePitchMeters(),protectedPoints,List.of(),false,false,false,false));
    }
}
