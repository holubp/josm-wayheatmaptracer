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
            NativeRefinementSourceContext context,GeometryWorkContext work,AttemptMemoryLedger.Owner memory) {
        context.requireCurrent();var capture=run.captured();var evidence=run.evidence();
        long bytes=0;for(long charge:RefinementRootAuthority.detachedCharges(evidence).values())bytes=Math.addExact(bytes,charge);
        try{if(memory.retainIfAbsent(evidence).bytes()!=bytes)throw new IllegalArgumentException("Native evidence group differs");}
        catch(IllegalArgumentException absent){work.reserveOn(memory,"native-original-evidence",bytes).adopt(evidence);}
        SupportedInputRasterTransform transform;int width,height;String hash;
        if(capture.raster()!=null) {
            var raster=capture.raster();width=raster.width();height=raster.height();
            transform=SupportedInputRasterTransform.visibleWebMercator(raster.minimumEast(),raster.maximumNorth(),raster.projectionUnitsPerViewPixel(),6);
            try(var scratch=work.reserve("native-original-image-hash",Math.addExact(8192,8L*width*height))) {
                var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
                var pixels=raster.argb();
                for(int y=0;y<height;y++)for(int x=0;x<width;x+=256) {
                    int count=Math.min(256,width-x);work.visit(GeometryWorkContext.Kind.COPY,count);
                    image.setRGB(x,y,count,1,pixels,y*width+x,width);
                }
                hash=RasterEvidenceCapture.hashOriginalArgb(image,work,memory);
            }
        } else {
            var raster=capture.managedRaster();width=raster.image().getWidth();height=raster.image().getHeight();transform=raster.transform();
            hash=RasterEvidenceCapture.hashOriginalArgb(raster.image(),work,memory);
        }
        String mapping=ModernReviewRoot.mapping(run);
        var request=new RefinementSourcePlanner.SourceRequest(capture.palette(),mapping,mapping.equals("all-colors-combined"),"");
        // Aggregate input requires its complete original ARGB-set hash, never the selected image's hash.
        if(request.aggregate())throw new UnsupportedOperationException("Native aggregate original input-set receipt unavailable");
        var guard=context.detachedCommandGeneration();Runnable current=()->guard.getAsLong();
        return RetainedSmoothingSource.fromOriginalConversion(request,settings.intensity(),run.options().fieldName(),evidence,transform,width,height,bytes,current,
            evidence.sourceIdentity()+"/native-original/"+mapping,hash,capture.managedRaster()==null?OptionalLong.empty():OptionalLong.of(capture.managedRaster().generation().value()),
            capture.raster()==null?Optional.empty():Optional.of(capture.raster().sourceReceipt()),Optional.empty(),RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL,work,memory);
    }
    static FinalGeometryEvaluator.Result intervalQuality(LiveBPreviewService.Computed run,FixedIntervalEditPlanComposer.Assessment assessment) {
        throw new UnsupportedOperationException("Native interval final quality binding unavailable");
    }
}
