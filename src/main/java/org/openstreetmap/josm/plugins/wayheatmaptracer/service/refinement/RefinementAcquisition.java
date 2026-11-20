package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

/** Parent-centered coordinator acquisition and scalar-before-resampling evidence, without OSM writes. */
public final class RefinementAcquisition {
    private final ManagedModernPreviewSource source;
    private final TileFetchCoordinator coordinator;
    public RefinementAcquisition(ManagedModernPreviewSource source,TileFetchCoordinator coordinator) {
        this.source=Objects.requireNonNull(source);this.coordinator=Objects.requireNonNull(coordinator);
    }
    /** Visible source acquisition reuses only factual captured pixels; it cannot fetch palettes. */
    public static Accounted<VisibleCapture> captureVisible(RefinementSourcePlanner.Plan plan,
            LiveBPreviewService.VisibleRaster raster,String mapping,CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Visible evidence copies require worker thread");
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
            retained.adopt(result);reservation.close();return new Accounted<>(new VisibleCapture(plan,result,raster.sourceReceipt(),memory),memory);
        } catch(RuntimeException|Error failure){memory.close();throw failure;}
    }
    /** Visible evidence retains its actual source receipt and native memory ownership. */
    public static final class VisibleCapture {
        private final RefinementSourcePlanner.Plan plan;
        private final EvidenceSnapshot evidence;
        private final org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch.Receipt receipt;
        private final AttemptMemoryLedger.Owner owner;
        private VisibleCapture(RefinementSourcePlanner.Plan plan,EvidenceSnapshot evidence,
                org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch.Receipt receipt,AttemptMemoryLedger.Owner owner) {
            this.plan=plan;this.evidence=evidence;this.receipt=receipt;this.owner=owner;
        }
        public RefinementSourcePlanner.Plan plan(){return plan;}
        public void requireCurrent() {
            if(owner.isClosed())throw new IllegalStateException("Visible evidence owner has settled");
            receipt.owner().requireCurrent(receipt);
        }
        public EvidenceSnapshot evidence(){requireCurrent();return evidence;}
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
        var acquired=source.acquireRefinementSources(request,new LinkedHashSet<>(List.of(settings.inferenceZoom(),settings.validationZoom())),
            plan.aggregateRequested(),credentials,cancellation,operation,budget,grid);
        acquired.value().requireCurrent(coordinator);
        var capture=new Capture(plan,acquired.value(),operation,coordinator,grid,geographic);
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
        private Capture(RefinementSourcePlanner.Plan plan,ManagedModernPreviewSource.RefinementSources sources,
                AttemptMemoryLedger.Owner owner,TileFetchCoordinator coordinator,MetricRasterGrid grid,List<GeographicPoint> geographic) {
            this.plan=plan;this.sources=sources;this.owner=owner;this.coordinator=coordinator;this.grid=grid;this.geographic=List.copyOf(geographic);
        }
        public RefinementSourcePlanner.Plan plan(){return plan;}
        public Set<String> palettes(){return sources.palettes();}
        public Map<String,ManagedModernPreviewSource.RefinementStatus> sourceOutcomes(){return sources.sourceOutcomes();}
        public boolean completeAggregate(){return sources.completeAggregate();}
        public Map<String,Map<Integer,ManagedModernPreviewSource.ZoomReceipt>> sourceReceipts(){return sources.sourceReceipts();}
        public ManagedModernPreviewSource.RefinementWorkReceipt workReceipt(){return sources.workReceipt();}
        public boolean isCurrent(TileFetchCoordinator actual){return sources.isCurrent(actual);}
        /** Fresh evidence/profile locations belong to the exact selected parent, never root-centered rows. */
        public EvidenceSnapshot evidence(String palette,CancellationProbe cancellation) {
            return evidence(palette,palette,false,cancellation);
        }
        /** Explicit selected-raster variants are mappings, not additional acquired palettes. */
        public EvidenceSnapshot mappingEvidence(String mapping,CancellationProbe cancellation) {
            if(!plan.settings().intensity().usesColorMapping()||!plan.settings().alternatives().contains(mapping))throw new IllegalArgumentException("Detector mapping was not requested");
            return evidence(plan.settings().selectedPalette(),mapping,false,cancellation);
        }
        public EvidenceSnapshot aggregateEvidence(CancellationProbe cancellation) {
            if(!plan.aggregateRequested()||!completeAggregate())throw new IllegalStateException("Complete aggregate unavailable");
            return evidence(plan.acquiredPalettes().get(0),"all-colors-combined",true,cancellation);
        }
        private EvidenceSnapshot evidence(String palette,String mapping,boolean aggregate,CancellationProbe cancellation) {
            if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Evidence copies require worker thread");
            cancellation.checkpoint();sources.requireCurrent(coordinator);
            var raster=sources.raster(palette,coordinator);
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
                cancellation.checkpoint();sources.requireCurrent(coordinator);retained.adopt(result);reservation.close();return result;
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
