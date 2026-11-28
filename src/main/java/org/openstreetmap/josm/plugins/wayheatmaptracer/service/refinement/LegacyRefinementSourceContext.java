package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.util.function.LongSupplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage;

/** Legacy's actual retained acquisition behind the shared source boundary. */
final class LegacyRefinementSourceContext implements RefinementSourceContext {
    private final LegacyRefinementBootstrap.OriginalSource originalSource;
    private final AlignmentResult original;
    private final LegacyRefinementBootstrap.Root root;
    private final TileFetchCoordinator coordinator;
    private final VisibleRefinementCapture.Port visibleCapture;
    private final CredentialSnapshot credentials;
    private Optional<RefinementAttemptDiagnostics.SourceFacts> facts=Optional.empty();
    LegacyRefinementSourceContext(LegacyRefinementBootstrap.Seed seed,LegacyRefinementBootstrap.Root root,
            TileFetchCoordinator coordinator,VisibleRefinementCapture.Port visibleCapture) {
        originalSource=seed.originalSource();original=seed.original();this.root=root;this.coordinator=coordinator;
        this.visibleCapture=visibleCapture;credentials=CredentialSnapshot.fromConfig(seed.config().heatmap());
    }
    public RefinementAttemptDiagnostics.SourceKind kind(){return originalSource.managed()?RefinementAttemptDiagnostics.SourceKind.MANAGED:RefinementAttemptDiagnostics.SourceKind.VISIBLE;}
    public void requireCurrent(){originalSource.requireCurrent(coordinator);}
    public Optional<RefinementAttemptDiagnostics.SourceFacts> sourceFacts(){return facts;}
    public LongSupplier detachedCommandGeneration() {
        long value=root.receipt().snapshot().sourceGeneration();
        if(originalSource instanceof LegacyRefinementBootstrap.ManagedOriginalSource managed) {
            var guard=managed.receipt().generationGuard();return ()->{guard.getAsLong();return value;};
        }
        var receipt=((LegacyRefinementBootstrap.VisibleOriginalSource)originalSource).metadata().sourceReceipt();
        return ()->{receipt.owner().requireCurrent(receipt);return value;};
    }
    public RefinementPipeline.Sources open(RefinementSourcePlanner.Plan plan,Stage parent,GeometryWorkContext work,
            AttemptMemoryLedger.Owner owner,CancellationProbe cancellation) {
        facts=Optional.empty();requireCurrent();
        if(originalSource.managed()) {
            var acquired=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquireMetered(plan,credentials,cancellation,owner,work);
            return RefinementPipeline.managedSources(acquired.value(),coordinator,work);
        }
        var metadata=((LegacyRefinementBootstrap.VisibleOriginalSource)originalSource).metadata();
        var evidencePlan=VisibleEvidencePlan.create(plan,metadata,work);
        var painted=metadata.paintedFrame().orElseThrow();var image=original.capturedHeatmap();
        long count=Math.multiplyExact((long)image.getWidth(),image.getHeight());
        LiveBPreviewService.VisibleRaster raster;
        try(var admission=work.reserveOn(owner,"visible-original-raster-copy",Math.addExact(4096L,Math.multiplyExact(count,8)))) {
            var pixels=new int[Math.toIntExact(count)];
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x+=256){int length=Math.min(256,image.getWidth()-x);work.visit(GeometryWorkContext.Kind.COPY,length);image.getRGB(x,y,length,1,pixels,y*image.getWidth()+x,image.getWidth());}
            for(long start=0;start<count;start+=256)work.visit(GeometryWorkContext.Kind.COPY,Math.min(256,count-start));
            raster=new LiveBPreviewService.VisibleRaster(image.getWidth(),image.getHeight(),pixels,painted.minimumEast(),painted.minimumNorth(),painted.maximumEast(),painted.maximumNorth(),painted.projectionUnitsPerViewPixel(),painted.groundMetersPerViewPixel(),painted.nativePitchMeters(),root.authority().sourceIdentity(),painted.projectionCode(),metadata.sourceReceipt());admission.adopt(raster);
        }
        var mappings=new LinkedHashMap<String,RefinementAcquisition.VisibleCapture>();
        var support=RefinementAcquisition.visibleSupport(evidencePlan,raster);
        var planHash=hash(evidencePlan.canonicalIdentity(),work);var frameHash=hash(painted.toString(),work);
        var sourceHash=hash(root.authority().sourceIdentity(),work);
        var coverage=switch(support){case COVERED->RefinementAttemptDiagnostics.Coverage.COVERED;case NEEDS_CAPTURE->RefinementAttemptDiagnostics.Coverage.NEEDS_CAPTURE;case UNSUPPORTED->RefinementAttemptDiagnostics.Coverage.UNSUPPORTED;};
        facts=Optional.of(new RefinementAttemptDiagnostics.SourceFacts(plan.ticket(),sourceHash,kind(),coverage,planHash,frameHash,root.originalConversion().inputByteHash(),RefinementAttemptDiagnostics.InputFrameRole.ORIGINAL_INPUT,raster.width(),raster.height(),evidencePlan.grid().width(),evidencePlan.grid().height(),metadata.sourceReceipt().revision(),OptionalLong.empty()));
        VisibleRefinementCapture.CaptureOperation capture=null;Accounted<VisibleRefinementCapture.Frame> frame=null;long start=-1;
        try {
            if(support==VisibleEvidencePlan.Support.NEEDS_CAPTURE) {
                start=work.visits();
                facts=Optional.of(new RefinementAttemptDiagnostics.SourceFacts(plan.ticket(),sourceHash,kind(),RefinementAttemptDiagnostics.Coverage.CAPTURE_REQUESTED,planHash,frameHash,root.originalConversion().inputByteHash(),RefinementAttemptDiagnostics.InputFrameRole.ORIGINAL_INPUT,raster.width(),raster.height(),evidencePlan.grid().width(),evidencePlan.grid().height(),metadata.sourceReceipt().revision(),OptionalLong.empty()));
                capture=visibleCapture.capture(new VisibleRefinementCapture.Request(evidencePlan,parent,List.of(original)),work);
                var actualCapture=capture;
                try(var registration=cancellation.onCancellation(actualCapture::cancel)){frame=capture.result().toCompletableFuture().join();}
                var actual=frame.value();facts=Optional.of(new RefinementAttemptDiagnostics.SourceFacts(plan.ticket(),sourceHash,kind(),RefinementAttemptDiagnostics.Coverage.RECAPTURED,planHash,hash(actual.actualBounds().toString()+actual.chunks(),work),actual.inputHash(),RefinementAttemptDiagnostics.InputFrameRole.CAPTURED_INPUT,actual.width(),actual.height(),evidencePlan.grid().width(),evidencePlan.grid().height(),metadata.sourceReceipt().revision(),OptionalLong.of(work.visits()-start)));
            } else if(support!=VisibleEvidencePlan.Support.COVERED)throw new UnsupportedOperationException("visible-original-frame-unavailable");
            for(var request:plan.requests())if(request.unavailableReason().isEmpty()&&!request.aggregate()&&request.palette().equals(plan.settings().selectedPalette())) {
                cancellation.checkpoint();var converted=frame==null?RefinementAcquisition.captureVisible(plan,evidencePlan,raster,request.mapping(),work):RefinementAcquisition.captureVisible(plan,evidencePlan,frame.value(),request.mapping(),work);
                converted.owner().transferTo(owner);mappings.put(request.mapping(),converted.value());
            }
            return RefinementPipeline.visibleSources(mappings);
        } finally {if(capture!=null)capture.settled().toCompletableFuture().join();if(frame!=null)frame.owner().close();}
    }
    private static String hash(String text,GeometryWorkContext work) {
        try(var admitted=work.reserve("source-fact-hash",Math.addExact(4096L,Math.multiplyExact(3L,text.length())))) {
            byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);var hash=java.security.MessageDigest.getInstance("SHA-256");
            for(int offset=0;offset<bytes.length;offset+=256){int count=Math.min(256,bytes.length-offset);work.visit(GeometryWorkContext.Kind.HASH,count);hash.update(bytes,offset,count);}
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("Required SHA-256 is unavailable");}
    }
}
