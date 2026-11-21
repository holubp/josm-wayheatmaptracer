package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Bounded worker/EDT visible recapture. Each paint is a separate restoring viewport transaction. */
public final class VisibleRefinementCapture {
    public record Bounds(double minimumEast,double minimumNorth,double maximumEast,double maximumNorth) {
        public Bounds {
            if(!Double.isFinite(minimumEast)||!Double.isFinite(minimumNorth)||!Double.isFinite(maximumEast)||!Double.isFinite(maximumNorth)
                    ||minimumEast>=maximumEast||minimumNorth>=maximumNorth)throw new IllegalArgumentException("Invalid numeric capture bounds");
        }
    }
    public record Viewport(double east,double north,double scale,int width,int height) {
        public Viewport {if(!Double.isFinite(east)||!Double.isFinite(north)||!Double.isFinite(scale)||scale<=0||width<1||height<1)throw new IllegalArgumentException("Invalid viewport");}
    }
    /** The publisher supplies its stable admitted Stage metadata, never a selected wrapper.
     * Extra identities are immutable original input/history groups already admitted on the same ledger.
     */
    public record Request(VisibleEvidencePlan evidencePlan,RefinementModels.Stage publishedStage,List<Object> inputIdentities) {
        public Request {
            Objects.requireNonNull(evidencePlan);Objects.requireNonNull(publishedStage);inputIdentities=List.copyOf(inputIdentities);
            var source=evidencePlan.sourcePlan();
            if(!publishedStage.id().equals(source.ticket().parent())||publishedStage.candidates().stream().noneMatch(c->c.id().equals(source.ticket().choice())))
                throw new IllegalArgumentException("Capture must pin exact published parent stage");
            var selected=publishedStage.candidates().stream().filter(c->c.id().equals(source.ticket().choice())).findFirst().orElseThrow();
            if(!selected.immutableIdentity().equals(source.parent().parentIdentity())||!selected.nativeCandidateId().equals(source.parent().nativeCandidateId()))
                throw new IllegalArgumentException("Capture published parent content differs");
            evidencePlan.requireCurrent();rasterBytes(evidencePlan.viewWidth(),evidencePlan.viewHeight());
        }
        public List<VisibleEvidencePlan.ProjectedPoint> projectedParent(){return evidencePlan.projectedParent();}
        public Bounds requestedBounds(){return evidencePlan.requestedBounds();}
        public void requireChunk(Chunk chunk) {
            var p=evidencePlan;
            if((long)chunk.viewX()+chunk.viewWidth()>p.viewWidth()||(long)chunk.viewY()+chunk.viewHeight()>p.viewHeight())throw new IllegalArgumentException("Chunk exceeds frozen frame");
            var b=p.roundedBounds();double scale=p.projectedScale();
            var expected=new Bounds(b.minimumEast()+chunk.viewX()*scale,b.maximumNorth()-(chunk.viewY()+chunk.viewHeight())*scale,b.minimumEast()+(chunk.viewX()+chunk.viewWidth())*scale,b.maximumNorth()-chunk.viewY()*scale);
            if(!expected.equals(chunk.bounds()))throw new IllegalArgumentException("Chunk differs from frozen frame");
        }
    }
    public record Chunk(int viewX,int viewY,int viewWidth,int viewHeight,Bounds bounds) {
        public Chunk {
            Objects.requireNonNull(bounds);
            if(viewX<0||viewY<0||viewWidth<1||viewHeight<1||viewWidth>5000||viewHeight>5000)throw new IllegalArgumentException("Invalid capture chunk");
            rasterBytes(viewWidth,viewHeight);
        }
    }
    public record ChunkResolution(Chunk chunk,Bounds actualBounds,int sourceZoom,OptionalDouble nativePitchMeters,double groundMetersPerViewPixel) { }
    public record RenderedChunk(BufferedImage image,Bounds actualBounds,int sourceZoom,OptionalDouble nativePitchMeters,double groundMetersPerViewPixel) {
        public RenderedChunk {
            Objects.requireNonNull(image);Objects.requireNonNull(actualBounds);Objects.requireNonNull(nativePitchMeters);
            if(!Double.isFinite(groundMetersPerViewPixel)||groundMetersPerViewPixel<=0||nativePitchMeters.isPresent()&&(!Double.isFinite(nativePitchMeters.getAsDouble())||nativePitchMeters.getAsDouble()<=0))throw new IllegalArgumentException("Invalid actual resolution");
        }
    }
    /** Image is detached by the worker before its EDT-owned temporary reference is discarded. */
    public interface Renderer {
        void requireCurrent(Request request);
        Viewport snapshot();
        RenderedChunk render(Request request,Chunk chunk);
        void restore(Viewport saved);
    }
    public interface Port {
        CaptureOperation capture(Request request,GeometryWorkContext work);
    }
    public interface CaptureOperation {
        CompletionStage<Accounted<Frame>> result();
        CompletionStage<Void> settled();
        void cancel();
    }
    /** Requested and actual frame plus spatial resolution receipts; visible phase never uses -.5. */
    public static final class Frame {
        private final VisibleEvidencePlan plan;
        private final int width,height;
        private final int[] pixels;
        private final Bounds bounds;
        private final List<ChunkResolution> chunks;
        private final String hash;
        private final double groundScale;
        private final OptionalDouble nativePitch;
        private final AttemptMemoryLedger.Owner owner;
        // Only the producer can hand off its private mosaic after its last write and hash.
        private Frame(VisibleEvidencePlan plan,int width,int height,int[] pixels,Bounds bounds,List<ChunkResolution> chunks,
                String hash,double groundScale,OptionalDouble nativePitch,AttemptMemoryLedger.Owner owner) {
            this.plan=plan;this.width=width;this.height=height;this.pixels=pixels;this.bounds=bounds;this.chunks=List.copyOf(chunks);
            this.hash=hash;this.groundScale=groundScale;this.nativePitch=nativePitch;this.owner=owner;
            if(pixels.length!=Math.multiplyExact(width,height)||plan.supportFrame(bounds,width,height,plan.projectedScale(),sourceIdentity(),plan.original().projectionCode(),sourceReceipt())!=VisibleEvidencePlan.Support.COVERED)
                throw new IllegalArgumentException("Actual frame misses frozen engine support");
        }
        public VisibleEvidencePlan evidencePlan(){return plan;}
        public int width(){return width;}public int height(){return height;}
        public Bounds actualBounds(){return bounds;}public List<ChunkResolution> chunks(){return chunks;}
        public boolean commonResolution(){return chunks.isEmpty()||chunks.stream().allMatch(c->c.sourceZoom()==chunks.get(0).sourceZoom()&&c.nativePitchMeters().equals(chunks.get(0).nativePitchMeters())&&c.groundMetersPerViewPixel()==chunks.get(0).groundMetersPerViewPixel());}
        public String inputHash(){return hash;}
        public String sourceIdentity(){return plan.sourcePlan().parent().root().sourceIdentity();}
        public org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch.Receipt sourceReceipt(){return plan.original().sourceReceipt();}
        public double groundMetersPerViewPixel(){return groundScale;}public OptionalDouble nativePitchMeters(){return nativePitch;}
        public void requireCurrent(){if(owner.isClosed())throw new IllegalStateException("Visible frame owner has settled");plan.requireCurrent();}
        /** Read-only known-pixel probes do not allocate a second full raster. */
        public int argbAt(int x,int y,GeometryWorkContext work) {
            requireWorker();plan.requireWork(work);requireCurrent();
            if(x<0||y<0||x>=width||y>=height)throw new IndexOutOfBoundsException();
            work.visit(GeometryWorkContext.Kind.COPY,1);return pixels[y*width+x];
        }
        /** Optional live conversion copy, separately admitted and charged; standalone port capture omits it. */
        public Accounted<LiveBPreviewService.VisibleRaster> copyRaster(GeometryWorkContext work) {
            requireWorker();plan.requireWork(work);requireCurrent();var copy=work.owner().child("visible-frame-copy");
            try(var admitted=work.reserveOn(copy,"visible-frame-copy",Math.addExact(8192L,AttemptMemoryLedger.arrayBytes(pixels.length,4)))) {
                for(int start=0;start<pixels.length;start+=256)work.visit(GeometryWorkContext.Kind.COPY,Math.min(256,pixels.length-start));
                var result=new LiveBPreviewService.VisibleRaster(width,height,pixels,bounds.minimumEast(),bounds.minimumNorth(),bounds.maximumEast(),bounds.maximumNorth(),plan.projectedScale(),groundScale,nativePitch,sourceIdentity(),plan.original().projectionCode(),sourceReceipt());
                requireCurrent();work.visit(GeometryWorkContext.Kind.BIND,1);admitted.adopt(result);return new Accounted<>(result,copy);
            }catch(RuntimeException|Error failure){copy.close();throw failure;}
        }
    }
    private final Renderer renderer;
    private final Executor edt,worker;
    private final int chunkWidth,chunkHeight;
    private final AtomicBoolean rendering=new AtomicBoolean();
    public VisibleRefinementCapture(Renderer renderer,Executor edt,Executor worker,int chunkWidth,int chunkHeight) {
        this.renderer=Objects.requireNonNull(renderer);this.edt=Objects.requireNonNull(edt);this.worker=Objects.requireNonNull(worker);
        if(chunkWidth<1||chunkHeight<1||chunkWidth>5000||chunkHeight>5000)throw new IllegalArgumentException("Invalid chunk threshold");
        this.chunkWidth=chunkWidth;this.chunkHeight=chunkHeight;
    }
    /** Checked sixfold PER AXIS, before any image allocation or EDT dispatch. */
    public static long rasterBytes(int viewWidth,int viewHeight) {
        if(viewWidth<1||viewHeight<1)throw new IllegalArgumentException("Invalid view dimensions");
        int width=Math.multiplyExact(viewWidth,6),height=Math.multiplyExact(viewHeight,6);
        int pixels=Math.multiplyExact(width,height);return Math.multiplyExact((long)pixels,4);
    }
    public Port port(){return this::capture;}
    public CaptureOperation capture(Request request,GeometryWorkContext work) {
        return capture(request,null,work);
    }
    /** Covered original pixels bypass the renderer; one recapture is attempted only when needed. */
    public CaptureOperation capture(Request request,LiveBPreviewService.VisibleRaster originalRaster,GeometryWorkContext work) {
        Objects.requireNonNull(work);Objects.requireNonNull(request);request.evidencePlan().requireWork(work);request.evidencePlan().requireCurrent();
        if(originalRaster!=null)request.evidencePlan().requireOriginalFrame(originalRaster);
        var operation=new Operation(request,originalRaster,work);
        try{worker.execute(operation::begin);}catch(RuntimeException rejected){operation.fail(rejected);}
        return operation;
    }
    private final class Operation implements CaptureOperation {
        Request request;final GeometryWorkContext work;
        LiveBPreviewService.VisibleRaster originalRaster;
        final AttemptMemoryLedger.Owner producer,output;
        final CompletableFuture<Accounted<Frame>> result=new CompletableFuture<>();
        final CompletableFuture<Void> settled=new CompletableFuture<>();final AtomicBoolean cancelled=new AtomicBoolean();
        final ArrayList<ChunkResolution> resolutions=new ArrayList<>();
        AttemptMemoryLedger.Reservation workspace,retained;
        int[] pixels;int x,y;boolean terminal;
        Bounds actualFrame;
        Operation(Request request,LiveBPreviewService.VisibleRaster originalRaster,GeometryWorkContext work) {
            this.originalRaster=originalRaster;
            this.request=request;this.work=work;producer=work.owner().child("visible-capture-producer");output=work.owner().child("visible-capture-output");
            try {
                producer.retainIfAbsent(request.publishedStage());
                producer.retainIfAbsent(request.evidencePlan());
                if(originalRaster!=null)producer.retainIfAbsent(originalRaster);
                for(Object identity:request.inputIdentities())producer.retainIfAbsent(identity);
                for(var candidate:request.publishedStage().candidates())candidate.payload().nativeReview().ifPresent(review->review.retainedSources().values().forEach(source->source.retainInto(producer)));
            }catch(RuntimeException|Error failure){producer.close();output.close();throw failure;}
        }
        public CompletionStage<Accounted<Frame>> result(){return result.minimalCompletionStage();}
        public CompletionStage<Void> settled(){return settled.minimalCompletionStage();}
        public void cancel(){cancelled.set(true);}
        void check(){if(cancelled.get())throw new CancellationException("visible-capture-cancelled");work.visit(GeometryWorkContext.Kind.BIND,1);request.evidencePlan().requireCurrent();}
        void begin() {
            try {
                requireWorker();
                check();var p=request.evidencePlan();
                if(originalRaster!=null) {
                    var support=p.support(originalRaster);
                    if(support==VisibleEvidencePlan.Support.UNSUPPORTED)throw new UnsupportedOperationException("visible-original-frame-unavailable");
                    if(support==VisibleEvidencePlan.Support.COVERED){covered();return;}
                    originalRaster=null;
                }
                long bytes=rasterBytes(p.viewWidth(),p.viewHeight());
                long chunk=rasterBytes(Math.min(chunkWidth,p.viewWidth()),Math.min(chunkHeight,p.viewHeight()));
                long chunks=Math.multiplyExact((p.viewWidth()+chunkWidth-1L)/chunkWidth,(p.viewHeight()+chunkHeight-1L)/chunkHeight);
                // Mosaic backing, defensive frozen raster copy, one paint image and bounded hash buffer coexist.
                retained=work.reserveOn(output,"visible-frozen-frame",Math.addExact(Math.addExact(bytes,8192),Math.multiplyExact(chunks,256)));
                workspace=work.reserveOn(producer,"visible-active-chunk",Math.addExact(chunk,8192));
                pixels=new int[Math.multiplyExact(Math.multiplyExact(p.viewWidth(),6),Math.multiplyExact(p.viewHeight(),6))];
                enqueueChunk();
            }catch(RuntimeException|Error failure){fail(failure);}
        }
        void enqueueChunk() {
            check();var p=request.evidencePlan();int w=Math.min(chunkWidth,p.viewWidth()-x),h=Math.min(chunkHeight,p.viewHeight()-y);
            double scale=p.projectedScale();var b=p.roundedBounds();
            var chunk=new Chunk(x,y,w,h,new Bounds(b.minimumEast()+x*scale,b.maximumNorth()-(y+h)*scale,b.minimumEast()+(x+w)*scale,b.maximumNorth()-y*scale));
            edt.execute(()->paint(chunk));
        }
        void paint(Chunk chunk) {
            RenderedChunk rendered=null;Throwable failure=null;Viewport saved=null;boolean acquired=false;
            try {
                acquired=rendering.compareAndSet(false,true);if(!acquired)throw new IllegalStateException("visible-capture-reentrant");
                request.requireChunk(chunk);
                check();renderer.requireCurrent(request);saved=renderer.snapshot();rendered=renderer.render(request,chunk);
                if(rendered.image().getWidth()!=chunk.viewWidth()*6||rendered.image().getHeight()!=chunk.viewHeight()*6)throw new IllegalArgumentException("Paint returned wrong dimensions");
                acceptActualFrame(chunk,rendered.actualBounds());
                check();renderer.requireCurrent(request);
            }catch(RuntimeException|Error caught){failure=caught;}
            finally {
                if(saved!=null)try{renderer.restore(saved);}catch(RuntimeException|Error restore){if(failure!=null)restore.addSuppressed(failure);failure=restore;}
                if(acquired)rendering.set(false);
            }
            if(failure==null)try{check();renderer.requireCurrent(request);}catch(RuntimeException|Error stale){failure=stale;}
            var transfer=new Transfer(rendered,failure);rendered=null;failure=null;
            try{worker.execute(()->consume(chunk,transfer));}
            catch(RuntimeException rejected){transfer.image=null;transfer.failure=null;fail(rejected);}
        }
        final class Transfer {
            RenderedChunk image;Throwable failure;
            Transfer(RenderedChunk image,Throwable failure){this.image=image;this.failure=failure;}
        }
        void acceptActualFrame(Chunk chunk,Bounds actual) {
            var p=request.evidencePlan();double scale=p.projectedScale();
            if(!sameCoordinate(actual.minimumEast(),actual.maximumEast()-chunk.viewWidth()*scale)
                    ||!sameCoordinate(actual.maximumNorth(),actual.minimumNorth()+chunk.viewHeight()*scale))throw new IllegalArgumentException("Paint returned a different sampling scale");
            if(actualFrame==null) {
                double left=actual.minimumEast()-chunk.viewX()*scale,top=actual.maximumNorth()+chunk.viewY()*scale;
                var global=new Bounds(left,top-p.viewHeight()*scale,left+p.viewWidth()*scale,top);
                if(p.supportFrame(global,p.viewWidth()*6,p.viewHeight()*6,scale,p.sourcePlan().parent().root().sourceIdentity(),p.original().projectionCode(),p.original().sourceReceipt())!=VisibleEvidencePlan.Support.COVERED)
                    throw new IllegalArgumentException("Actual frame misses frozen engine support");
                actualFrame=global;
            }
            var expected=new Bounds(actualFrame.minimumEast()+chunk.viewX()*scale,actualFrame.maximumNorth()-(chunk.viewY()+chunk.viewHeight())*scale,actualFrame.minimumEast()+(chunk.viewX()+chunk.viewWidth())*scale,actualFrame.maximumNorth()-chunk.viewY()*scale);
            if(!compatibleBounds(expected,actual))throw new IllegalArgumentException("Paint chunks have incompatible sampling frames");
        }
        void consume(Chunk chunk,Transfer transfer) {
            var actual=transfer.image;var failure=transfer.failure;transfer.image=null;transfer.failure=null;
            if(failure==null)try{assemble(chunk,actual);}catch(RuntimeException|Error caught){failure=caught;}
            actual=null;
            if(failure!=null){fail(failure);return;}
            // No callback/worker image references survive into the next queue boundary.
            try{worker.execute(this::advance);}catch(RuntimeException rejected){fail(rejected);}
        }
        void advance(){try{if(y==request.evidencePlan().viewHeight())finish();else enqueueChunk();}catch(RuntimeException|Error failure){fail(failure);}}
        void assemble(Chunk chunk,RenderedChunk actual) {
                requireWorker();
                check();int stride=request.evidencePlan().viewWidth()*6;
                for(int row=0;row<actual.image().getHeight();row++)for(int col=0;col<actual.image().getWidth();col+=256) {
                    int count=Math.min(256,actual.image().getWidth()-col);work.visit(GeometryWorkContext.Kind.COPY,count);
                    actual.image().getRGB(col,row,count,1,pixels,(chunk.viewY()*6+row)*stride+chunk.viewX()*6+col,stride);
                }
                resolutions.add(new ChunkResolution(chunk,actual.actualBounds(),actual.sourceZoom(),actual.nativePitchMeters(),actual.groundMetersPerViewPixel()));
                x+=chunk.viewWidth();if(x==request.evidencePlan().viewWidth()){x=0;y+=chunk.viewHeight();}
        }
        void covered() {
            // The exact original backing identity must remain pinned through result consumption.
            long bytes=Math.multiplyExact(Math.multiplyExact((long)originalRaster.width(),originalRaster.height()),4);
            retained=work.reserveOn(output,"visible-covered-frame",Math.addExact(bytes,8192));
            workspace=work.reserveOn(producer,"visible-covered-hash",8192);
            for(int start=0;start<(long)originalRaster.width()*originalRaster.height();start+=256)work.visit(GeometryWorkContext.Kind.COPY,Math.min(256,(long)originalRaster.width()*originalRaster.height()-start));
            pixels=originalRaster.argb();String hash=hashPixels();
            var bounds=new Bounds(originalRaster.minimumEast(),originalRaster.minimumNorth(),originalRaster.maximumEast(),originalRaster.maximumNorth());
            var frame=new Frame(request.evidencePlan(),originalRaster.width(),originalRaster.height(),pixels,bounds,List.of(),hash,originalRaster.groundMetersPerViewPixel(),originalRaster.nativePitchMeters(),output);check();
            originalRaster=null;publish(frame);
        }
        String hashPixels() {
            var digest=sha256();
            for(int start=0;start<pixels.length;start+=256) {
                int count=Math.min(256,pixels.length-start);work.visit(GeometryWorkContext.Kind.HASH,count);
                for(int i=start;i<start+count;i++){int v=pixels[i];digest.update((byte)(v>>>24));digest.update((byte)(v>>>16));digest.update((byte)(v>>>8));digest.update((byte)v);}
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        void finish() {
            check();var p=request.evidencePlan();
            // A heterogeneous mosaic keeps spatial chunk facts and reports rendered-only globally.
            var first=resolutions.get(0);boolean compatible=resolutions.stream().allMatch(r->r.sourceZoom()==first.sourceZoom()&&r.nativePitchMeters().equals(first.nativePitchMeters()));
            var b=actualFrame;
            var pitch=compatible?first.nativePitchMeters():OptionalDouble.empty();
            double ground=resolutions.stream().mapToDouble(ChunkResolution::groundMetersPerViewPixel).average().orElseThrow();
            String hash=hashPixels();
            var frame=new Frame(p,p.viewWidth()*6,p.viewHeight()*6,pixels,b,resolutions,hash,ground,pitch,output);check();publish(frame);
        }
        void publish(Frame frame) {
            output.retainIfAbsent(request.evidencePlan());
            for(Object identity:request.inputIdentities())output.retainIfAbsent(identity);
            for(var candidate:request.publishedStage().candidates())candidate.payload().nativeReview().ifPresent(review->review.retainedSources().values().forEach(source->source.retainInto(output)));
            retained.adopt(frame);pixels=null;workspace.close();workspace=null;producer.close();terminal=true;
            request=null;resolutions.clear();
            result.complete(new Accounted<>(frame,output));settled.complete(null);
        }
        void fail(Throwable failure) {
            if(terminal)return;terminal=true;request=null;originalRaster=null;pixels=null;resolutions.clear();output.close();producer.close();result.completeExceptionally(failure);settled.complete(null);
        }
    }
    private static void requireWorker(){if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Visible pixel work requires worker thread");}
    private static boolean compatibleBounds(Bounds requested,Bounds actual) {
        return sameCoordinate(requested.minimumEast(),actual.minimumEast())&&sameCoordinate(requested.minimumNorth(),actual.minimumNorth())
            &&sameCoordinate(requested.maximumEast(),actual.maximumEast())&&sameCoordinate(requested.maximumNorth(),actual.maximumNorth());
    }
    /** Only composed binary64 coordinate arithmetic may differ; never a physical pixel/phase offset. */
    private static boolean sameCoordinate(double a,double b){return Math.abs(a-b)<=32*Math.max(Math.ulp(a),Math.ulp(b));}
    private static java.security.MessageDigest sha256(){try{return java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
