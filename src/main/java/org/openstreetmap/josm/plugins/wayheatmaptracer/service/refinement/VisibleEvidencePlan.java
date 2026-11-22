package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.VisibleRefinementCapture.Bounds;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** One original-authority, engine-specific visible acquisition frame. No pixel work or OSM edits. */
public final class VisibleEvidencePlan {
    public enum Support { COVERED, NEEDS_CAPTURE, UNSUPPORTED }
    /** Attempt-wide source invalidation; never a coverage or detector outcome. */
    public static final class StaleSource extends IllegalStateException {
        private final RefinementModels.OperationTicket ticket;
        private final String sourceIdentity;
        StaleSource(RefinementSourcePlanner.Plan source) {
            this(source,"visible-original-epoch-changed");
        }
        private StaleSource(RefinementSourcePlanner.Plan source,String reason) {
            super(reason);ticket=source.ticket();sourceIdentity=source.parent().root().sourceIdentity();
        }
        public RefinementModels.OperationTicket ticket(){return ticket;}
        public String sourceIdentity(){return sourceIdentity;}
    }
    public record ProjectedPoint(double east,double north) {
        public ProjectedPoint {if(!Double.isFinite(east)||!Double.isFinite(north))throw new IllegalArgumentException("Nonfinite projected parent");}
    }
    private final RefinementSourcePlanner.Plan source;
    private final LegacyVisibleSource original;
    private final LegacyVisibleSource.PaintedFrame painted;
    private final MetricRasterGrid grid;
    private final MetricRegion decision;
    private final int support,halo,reduction,width,height;
    private final Bounds requested,rounded;
    private final List<ProjectedPoint> projectedParent;
    private final String identity;
    private final GeometryWorkContext work;
    private VisibleEvidencePlan(RefinementSourcePlanner.Plan source,LegacyVisibleSource original,
            MetricRasterGrid grid,MetricRegion decision,int support,int halo,int reduction,
            Bounds requested,int width,int height,List<ProjectedPoint> projectedParent,GeometryWorkContext work) {
        this.source=source;this.original=original;this.painted=original.paintedFrame().orElseThrow();this.grid=grid;this.decision=decision;
        this.support=support;this.halo=halo;this.reduction=reduction;this.requested=requested;
        this.width=width;this.height=height;this.projectedParent=List.copyOf(projectedParent);this.work=work;
        double e=(requested.minimumEast()+requested.maximumEast())/2,n=(requested.minimumNorth()+requested.maximumNorth())/2;
        double ew=width*projectedScale()/2,ns=height*projectedScale()/2;
        rounded=new Bounds(e-ew,n-ns,e+ew,n+ns);
        identity=source.operationIdentity()+"/"+source.parent().canonicalIdentity()+"/visible-plan/"+source.ticket().engine()+"/"+original.sourceReceipt().revision()+"/"+grid
            +"/"+source.parent().root().sourceIdentity()+"/"+original.projectionCode()+"/"+original.minimumEast()+"/"+original.minimumNorth()+"/"+original.maximumEast()+"/"+original.maximumNorth()
            +"/"+projectedScale()+"/"+original.groundMetersPerViewPixel()+"/"+original.nativePitchMeters()+"/"+requested+"/"+rounded+"/"+width+"/"+height+"/painted/"+painted;
    }
    public static VisibleEvidencePlan create(RefinementSourcePlanner.Plan source,LegacyVisibleSource original,GeometryWorkContext work) {
        if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Visible evidence planning requires worker thread");
        Objects.requireNonNull(source);Objects.requireNonNull(original);Objects.requireNonNull(work);
        try(var admitted=work.reserve("visible-evidence-plan",Math.addExact(32768L,Math.multiplyExact(4096L,source.parent().points().size())))) {
        work.visit(GeometryWorkContext.Kind.BIND,1);source.settings().requireRootPolicy(source.parent().root());
        if(!source.equals(RefinementSourcePlanner.plan(source.ticket(),source.settings(),source.parent())))throw new IllegalArgumentException("Noncanonical source plan");
        if(original.sourceReceipt()!=null)requireEpoch(source,original);
        var painted=original.paintedFrame().orElseThrow(()->new UnsupportedOperationException("visible-painted-frame-unavailable"));
        if(source.settings().managed()||!source.settings().intensity().usesColorMapping()||source.ticket().kind()==RefinementModels.StageKind.SMOOTH
                ||original.sourceReceipt()==null||!original.projectionCode().equals("EPSG:3857")
                ||!original.projectionCode().equals(source.parent().root().projectionCode())
                ||original.decisionRadiusMeters().isEmpty()||original.sampleStepMeters().isEmpty())
            throw new UnsupportedOperationException("visible-original-frame-unavailable");
        double radius=source.parent().root().request().permissions().ordinaryRadiusMeters();
        if(Double.compare(radius,original.decisionRadiusMeters().getAsDouble())!=0)throw new IllegalArgumentException("Original decision authority differs");
        if(!painted.projectionCode().equals(original.projectionCode())||!painted.commonResolution())throw new UnsupportedOperationException("visible-spatial-resolution-unavailable");
        double pitch=painted.groundMetersPerViewPixel()/6;
        double ratio=painted.nativePitchMeters().orElse(pitch)/pitch;
        int reduction=1,support,halo;
        if(source.ticket().engine()==TrackerMode.CORRIDOR_AWARE) {
            // Same nearest-level selection as the unchanged scalar sampler; ties prefer smaller R.
            reduction=selectedReduction(ratio);
            support=Math.addExact(Math.multiplyExact(2,reduction-1),reduction);
            halo=Math.addExact(1,Math.multiplyExact((support-1+reduction-1)/reduction,reduction));
        } else {
            support=orientationSupport(painted.nativePitchMeters().orElse(pitch),pitch);halo=support;
        }
        var decision=MetricCorridorRegion.aroundPolyline(source.parent().points(),radius);
        var vertices=decision.polygons().stream().flatMap(List::stream).toList();
        double left=vertices.stream().mapToDouble(MetricPoint::xMeters).min().orElseThrow()-halo*pitch;
        double right=vertices.stream().mapToDouble(MetricPoint::xMeters).max().orElseThrow()+halo*pitch;
        double top=vertices.stream().mapToDouble(MetricPoint::yMeters).max().orElseThrow()+halo*pitch;
        double bottom=vertices.stream().mapToDouble(MetricPoint::yMeters).min().orElseThrow()-halo*pitch;
        int gw=checkedDimension((right-left)/pitch),gh=checkedDimension((top-bottom)/pitch);
        var frame=source.parent().root().evidence().coordinateFrame();
        var grid=new MetricRasterGrid(frame,new MetricPoint(left+pitch/2,top-pitch/2),1,0,0,-1,pitch,gw,gh);
        // Certify the complete ceil-rounded footprint before any checked geographic conversion.
        frame=frame.certifiedExtension(grid.footprint());
        grid=new MetricRasterGrid(frame,new MetricPoint(left+pitch/2,top-pitch/2),1,0,0,-1,pitch,gw,gh);
        var transform=SupportedInputRasterTransform.visibleWebMercator(0,0,1,1);
        var bounds=transform.boundsForMetricCell(frame,grid.footprint().polygons().get(0));
        // A full input sample on every side supports the legacy visible interpolation convention.
        double pad=painted.projectionUnitsPerViewPixel()/6;
        var requested=new Bounds(bounds.minimumX()-pad,-bounds.maximumY()-pad,bounds.maximumX()+pad,-bounds.minimumY()+pad);
        // One view pixel per side covers JOSM's <=.501px origin-lattice centering.
        // This is acquisition canvas only; decision radius and the certified engine grid stay exact.
        int width=Math.max(checkedDimension(painted.width()/6.0),Math.addExact(checkedDimension((requested.maximumEast()-requested.minimumEast())/painted.projectionUnitsPerViewPixel()),2));
        int height=Math.max(checkedDimension(painted.height()/6.0),Math.addExact(checkedDimension((requested.maximumNorth()-requested.minimumNorth())/painted.projectionUnitsPerViewPixel()),2));
        if(Math.multiplyExact((long)width,height)>3_300_000)throw new IllegalArgumentException("visible-view-area-limit");
        Math.multiplyExact(Math.multiplyExact(width,6),Math.multiplyExact(height,6));
        var points=source.parent().points().stream().map(frame::toGeographic).map(g->{var p=transform.toRasterCenter(g);return new ProjectedPoint(p.x(),-p.y());}).toList();
        requireEpoch(source,original);
        var result=new VisibleEvidencePlan(source,original,grid,decision,support,halo,reduction,requested,width,height,points,work);
        admitted.adopt(result);return result;
        }
    }
    private static int checkedDimension(double value) {
        if(!Double.isFinite(value)||value>Integer.MAX_VALUE||value<0)throw new IllegalArgumentException("visible-dimension-limit");
        return Math.max(2,(int)Math.ceil(value));
    }
    private static int selectedReduction(double sourceOutputRatio) {
        double target=4*sourceOutputRatio;
        if(!Double.isFinite(target)||target<=0||target>Integer.MAX_VALUE)throw new UnsupportedOperationException("visible-engine-support-unavailable");
        int maximum=Math.max(4,(int)Math.ceil(target));
        int available=2;for(int level=1;level<8&&available<maximum;level++)available=Math.multiplyExact(available,2);
        int reduction=2;
        for(int r=4;r<=available;r*=2)if(Math.abs(r-target)<Math.abs(reduction-target))reduction=r;
        return reduction;
    }
    private static int orientationSupport(double sourcePitch,double outputPitch) {
        var policy=EvidenceModelParameters.defaults().localization();
        double ray=Math.max(policy.minimumOrientationRayMeters(),policy.orientationRayLengthPitches()*sourcePitch);
        double samples=Math.ceil(ray/outputPitch)+1;
        if(!Double.isFinite(samples)||samples>Integer.MAX_VALUE)throw new UnsupportedOperationException("visible-engine-support-unavailable");
        return (int)samples;
    }
    private static void requireEpoch(RefinementSourcePlanner.Plan source,LegacyVisibleSource original) {
        try{original.sourceReceipt().owner().requireCurrent(original.sourceReceipt());}catch(IllegalStateException stale){throw new StaleSource(source);}
    }
    public void requireCurrent(){requireEpoch(source,original);}
    public StaleSource sourceContextChanged(){return new StaleSource(source,"visible-original-context-changed");}
    public void requireWork(GeometryWorkContext actual){if(actual!=work)throw new IllegalArgumentException("Visible plan belongs to another operation context");}
    public void requireOriginalFrame(VisibleRaster raster) {
        requireCurrent();
        if(raster.sourceReceipt()!=null&&!raster.sourceReceipt().equals(original.sourceReceipt()))throw new StaleSource(source);
        if(raster.sourceReceipt()==null||!raster.sourceIdentity().equals(source.parent().root().sourceIdentity())||!raster.projectionCode().equals(painted.projectionCode())
                ||raster.minimumEast()!=painted.minimumEast()||raster.minimumNorth()!=painted.minimumNorth()||raster.maximumEast()!=painted.maximumEast()||raster.maximumNorth()!=painted.maximumNorth()
                ||raster.width()!=painted.width()||raster.height()!=painted.height()||raster.projectionUnitsPerViewPixel()!=painted.projectionUnitsPerViewPixel()||raster.groundMetersPerViewPixel()!=painted.groundMetersPerViewPixel()||!raster.nativePitchMeters().equals(painted.nativePitchMeters()))
            throw new UnsupportedOperationException("visible-original-frame-disagrees");
    }
    /** A returned factual source may not silently select a wider bank than this frozen plan. */
    public void requireActualSourcePitch(double actualSourcePitchMeters) {
        if(!Double.isFinite(actualSourcePitchMeters)||actualSourcePitchMeters<=0)throw new IllegalArgumentException("Invalid actual source pitch");
        if(source.ticket().engine()==TrackerMode.CORRIDOR_AWARE) {
            int r=selectedReduction(actualSourcePitchMeters/grid.pitchMeters());
            int required=Math.addExact(Math.multiplyExact(2,r-1),r);
            if(required>support)throw new UnsupportedOperationException("visible-actual-source-exceeds-frozen-support");
        } else if(orientationSupport(actualSourcePitchMeters,grid.pitchMeters())>support)throw new UnsupportedOperationException("visible-actual-source-exceeds-frozen-support");
    }
    public Support support(VisibleRaster raster) {
        requireCurrent();if(raster==null)return Support.UNSUPPORTED;
        return supportFrame(new Bounds(raster.minimumEast(),raster.minimumNorth(),raster.maximumEast(),raster.maximumNorth()),raster.width(),raster.height(),raster.projectionUnitsPerViewPixel(),raster.sourceIdentity(),raster.projectionCode(),raster.sourceReceipt());
    }
    /** Analytic proof over the complete grid, without constructing or cloning a pixel container. */
    public Support supportFrame(Bounds bounds,int width,int height,double scale,String sourceIdentity,String projectionCode,
            org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch.Receipt receipt) {
        requireCurrent();
        if(receipt==null)return Support.UNSUPPORTED;
        if(!receipt.equals(original.sourceReceipt()))throw new StaleSource(source);
        if(width<2||height<2||!sourceIdentity.equals(source.parent().root().sourceIdentity())||!projectionCode.equals(original.projectionCode())
                ||Double.compare(scale,projectedScale())!=0)return Support.UNSUPPORTED;
        var transform=SupportedInputRasterTransform.visibleWebMercator(bounds.minimumEast(),bounds.maximumNorth(),scale,6);
        var mapped=transform.boundsForMetricCell(grid.coordinateFrame(),grid.footprint().polygons().get(0));
        return mapped.minimumX()>=0&&mapped.minimumY()>=0&&mapped.maximumX()<=width-1&&mapped.maximumY()<=height-1?Support.COVERED:Support.NEEDS_CAPTURE;
    }
    public RefinementSourcePlanner.Plan sourcePlan(){return source;}
    public LegacyVisibleSource original(){return original;}
    public LegacyVisibleSource.PaintedFrame originalFrame(){return painted;}
    public MetricRasterGrid grid(){return grid;}
    public MetricRegion decisionFootprint(){return decision;}
    public double decisionRadiusMeters(){return source.parent().root().request().permissions().ordinaryRadiusMeters();}
    public int requiredSupportPixels(){return support;}public int haloPixels(){return halo;}public int maximumReduction(){return reduction;}
    public double projectedScale(){return painted.projectionUnitsPerViewPixel();}
    public Bounds requestedBounds(){return requested;}public Bounds roundedBounds(){return rounded;}
    public int viewWidth(){return width;}public int viewHeight(){return height;}
    public List<ProjectedPoint> projectedParent(){return projectedParent;}public String canonicalIdentity(){return identity;}
}
