package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

class LegacyVisibleRefinementBootstrapTest {
    /** Explicit synthetic painted pixels; never used by historical production readers. */
    static LegacyVisibleSource factualFrame(LegacyVisibleSource metadata,int width,int height,double eastShift,double northShift) {
        double pitch=metadata.projectionUnitsPerViewPixel()/6,left=metadata.minimumEast()+eastShift,top=metadata.maximumNorth()+northShift;
        var chunk=new LegacyVisibleSource.PaintedChunk(0,0,width,height,left,top-height*pitch,left+width*pitch,top,metadata.projectionUnitsPerViewPixel(),metadata.groundMetersPerViewPixel(),metadata.nativePitchMeters(),15,metadata.projectionCode());
        return metadata.withPaintedFrame(LegacyVisibleSource.PaintedFrame.common(width,height,List.of(chunk)).orElseThrow());
    }
    @TempDir Path temp;
    @BeforeAll static void setup(){var preferences=new org.openstreetmap.josm.data.Preferences();preferences.enableSaveOnPut(false);Config.setPreferencesInstance(preferences);ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));}
    private static AlignmentConfig config(AlignmentMode mode,boolean managed) {
        return config(mode,managed,false);
    }
    private static AlignmentConfig config(AlignmentMode mode,boolean managed,boolean aggregate) {
        return config(mode,managed,aggregate,IntensitySamplingMode.COLOR_MAPPING);
    }
    private static AlignmentConfig config(AlignmentMode mode,boolean managed,boolean aggregate,IntensitySamplingMode intensity) {
        return AlignmentConfig.withoutCleanup(new ManagedHeatmapConfig(managed?"key":"",managed?"policy":"",managed?"signature":"",managed?"session":"","all","hot","",".*",
            mode,TrackerMode.LEGACY_V02,false,false,true,aggregate,false,false,false,true,false,false,7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,intensity,7));
    }
    private static SelectionContext selection(DataSet data) {
        return selection(data,50);
    }
    private static SelectionContext selection(DataSet data,double latitude) {
        var nodes=new ArrayList<Node>();for(int i=0;i<5;i++){var node=new Node(new LatLon(latitude,14+(i-2)*.0002));data.addPrimitive(node);nodes.add(node);}
        var way=new Way();way.setNodes(nodes);way.put("highway","path");data.addPrimitive(way);data.allPrimitives().forEach(p->p.setModified(false));
        return new SelectionContext(way,0,4,nodes,Set.of(nodes.get(0),nodes.get(4)));
    }
    @ParameterizedTest @EnumSource(AlignmentMode.class)
    void visibleBothModesBindOriginalFactsAndChosenParent(AlignmentMode mode) throws Exception {
        var epoch=new VisibleSourceEpoch();var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((r,c)->{fail("Smoothing must not fetch an image");return null;},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
            var review=new AtomicReference<LegacyReviewOwner>();var seed=new AtomicReference<LegacyRefinementBootstrap.Seed>();var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                SwingUtilities.invokeAndWait(()->{
                    var data=new DataSet();var selected=selection(data);var cfg=config(mode,false);var current=LegacyReviewOwner.begin(data,selected,cfg);review.set(current);
                    var points=selected.segmentNodes().stream().map(Node::getEastNorth).toList();var center=Projections.getProjectionByCode("EPSG:3857").latlon2eastNorth(new LatLon(50,14));
                    var image=new BufferedImage(600,600,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<600;y++)for(int x=0;x<600;x++)image.setRGB(x,y,0xffffffff);
                    var candidate=new CenterlineCandidate("visible-lower",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var shortDiagnostic=new CenterlineCandidate("short",0,List.of(),List.of(),List.of(),List.of(),Map.of(),List.of(),0,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var top=new CenterlineCandidate("visible-top",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var result=new AlignmentResult(selected,image,List.of(shortDiagnostic,top,candidate),points,points,List.of(),null,null,List.of(new DetectorAttempt("hot","hot",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(shortDiagnostic.id(),top.id()),"",""),new DetectorAttempt("hot","hot-corridor",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"","")),List.of(candidate));
                    var metadata=new LegacyVisibleSource(center.east()-300,center.north()-300,center.east()+300,center.north()+300,6,3.86,OptionalDouble.empty(),"EPSG:3857",epoch.captureStable(),OptionalDouble.of(3.5),OptionalDouble.of(1.2));
                    current.captured(result,factualFrame(metadata,600,600,12,-18));current.select(candidate);seed.set(new LegacyRefinementBootstrap().capture(current,coordinator,work));
                });
                assertFalse(seed.get().originalSource().managed());
                assertThrows(IllegalStateException.class,seed.get()::receipt);
                var alien=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                assertThrows(IllegalArgumentException.class,()->new LegacyRefinementBootstrap().complete(seed.get(),coordinator,alien));
                var root=new LegacyRefinementBootstrap().complete(seed.get(),coordinator,work);
                var actual=root.originalConversion().referenceTransform().toRasterCenter(new GeographicPoint(50,14));
                assertEquals(288,actual.x(),1e-6);assertEquals(282,actual.y(),1e-6);
                assertEquals(3.5,root.authority().request().permissions().ordinaryRadiusMeters());
                assertEquals(1.2,root.authority().request().configuredSampleStepMeters());
                var selected=root.stage().candidates().stream().filter(c->c.id().equals(root.stage().selected())).findFirst().orElseThrow();
                assertEquals("legacy-choice-2",selected.id().value());
                assertEquals(1,root.stage().candidates().size());
                assertEquals(1,selected.payload().result().applicableCandidates().size());
                assertSame(seed.get().facts().receipt(),root.receipt());
                assertSame(seed.get().facts().network(),root.authority().network());
                assertEquals(seed.get().facts().specification().selectedRange(),root.authority().request().selectedRange());
                assertEquals(seed.get().facts().specification().permissions(),root.authority().request().permissions());
                assertTrue(root.authority().frozenGeometryProof().isPresent());
                assertEquals("visible-lower",selected.nativeCandidateId());
                assertEquals(seed.get().original().candidates().get(2).finalPreviewPoints(),selected.payload().finalPreviewGeometry());
                assertEquals(mode,root.authority().request().geometryMode());
                assertEquals(5,selected.correspondence().size());
                assertEquals("hot-corridor",root.originalConversion().source().mapping());
                assertEquals(independentInput(seed.get().original().capturedHeatmap()),root.originalConversion().inputByteHash());
                var transform=(org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.VisibleWebMercator)root.originalConversion().referenceTransform();
                var pixel=transform.toRasterCenter(new GeographicPoint(50,14));
                assertEquals(288,pixel.x(),1e-8);assertEquals(282,pixel.y(),1e-8);
                var compatibility=((LegacyRefinementBootstrap.VisibleOriginalSource)seed.get().originalSource()).metadata();
                var oldPixel=org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.visibleWebMercator(compatibility.minimumEast(),compatibility.maximumNorth(),compatibility.projectionUnitsPerViewPixel(),6).toRasterCenter(new GeographicPoint(50,14));
                assertEquals(300,oldPixel.x(),1e-8);assertEquals(300,oldPixel.y(),1e-8);
                epoch.sourceChanged();assertThrows(IllegalStateException.class,()->seed.get().originalSource().requireCurrent(coordinator));
                SwingUtilities.invokeAndWait(()->assertFalse(review.get().dataSet().isModified()));
            }finally{if(review.get()!=null)SwingUtilities.invokeAndWait(review.get()::close);}
            assertEquals(0,ledger.currentBytes());
        }
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"copy","hash","root"})
    void visibleCancellationReleasesPreparationAndPreservesCallerOwner(String phase) throws Exception {
        var decoder=new TileDecoderClassifier();var review=new AtomicReference<LegacyReviewOwner>();var seed=new AtomicReference<LegacyRefinementBootstrap.Seed>();
        var checks=new AtomicInteger();var observed=new AtomicBoolean();var previousBoundary=new AtomicLong(-1);var workRef=new AtomicReference<GeometryWorkContext>();var ledger=AttemptMemoryLedger.production();
        try(var coordinator=new TileFetchCoordinator((r,c)->{fail("No acquisition");return null;},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=ledger.rootOwner()) {
            var sentinel=new Object();owner.reserve(1024).adopt(sentinel);
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,()->{
                int n=checks.incrementAndGet();var current=workRef.get();if(current==null)return false;
                if(phase.equals("copy")||phase.equals("hash")) {
                    var counts=current.counters();long copied=counts.get(GeometryWorkContext.Kind.COPY),hashed=counts.get(GeometryWorkContext.Kind.HASH);
                    if(phase.equals("copy")?copied>0&&hashed==0:hashed>1024&&copied>0&&copied<360_000){observed.set(true);return true;}
                } else {
                    var counts=current.counters();long boundary=(counts.get(GeometryWorkContext.Kind.BIND)<<32)|counts.get(GeometryWorkContext.Kind.RESERVATION);
                    long previous=previousBoundary.getAndSet(boundary);
                    if(counts.get(GeometryWorkContext.Kind.MAP)>0&&boundary!=previous)
                        for(var frame:Thread.currentThread().getStackTrace())if(frame.getMethodName().equals("materializeSmoothingRoot")){observed.set(true);return true;}
                }
                return false;
            },owner);workRef.set(work);
            try {
                SwingUtilities.invokeAndWait(()->{
                    var data=new DataSet();var selected=selection(data);var current=LegacyReviewOwner.begin(data,selected,config(AlignmentMode.PRECISE_SHAPE,false));review.set(current);
                    var points=selected.segmentNodes().stream().map(Node::getEastNorth).toList();var center=Projections.getProjectionByCode("EPSG:3857").latlon2eastNorth(new LatLon(50,14));
                    var image=new BufferedImage(600,600,BufferedImage.TYPE_INT_ARGB);
                    var candidate=new CenterlineCandidate("visible",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var result=new AlignmentResult(selected,image,List.of(candidate),points,points,List.of(),null,null,List.of(new DetectorAttempt("hot","hot",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"","")),List.of(candidate));
                    current.captured(result,factualFrame(new LegacyVisibleSource(center.east()-300,center.north()-300,center.east()+300,center.north()+300,6,3.86,OptionalDouble.empty(),"EPSG:3857",new VisibleSourceEpoch().captureStable(),OptionalDouble.of(3.5),OptionalDouble.of(1.2)),600,600,0,0));current.select(candidate);
                    seed.set(new LegacyRefinementBootstrap().capture(current,coordinator,work));
                });
                assertThrows(java.util.concurrent.CancellationException.class,()->new LegacyRefinementBootstrap().complete(seed.get(),coordinator,work));
                assertTrue(observed.get(),"Cancellation reached the requested phase: "+phase);assertEquals(1024,ledger.currentBytes());assertFalse(owner.isClosed());assertEquals(1024,owner.retainIfAbsent(sentinel).bytes());
                SwingUtilities.invokeAndWait(()->{review.get().requireLegacyAuthorityCurrent();assertSame(seed.get().original().candidates().get(0),review.get().selectedCandidate());});
            } finally {if(review.get()!=null)SwingUtilities.invokeAndWait(review.get()::close);}
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void cancellationWhileFinalEdtValidationWaitsRefusesAndReleasesPreparation() throws Exception {
        var decoder=new TileDecoderClassifier();var ledger=AttemptMemoryLedger.production();var review=new AtomicReference<LegacyReviewOwner>();var seed=new AtomicReference<LegacyRefinementBootstrap.Seed>();
        var cancelled=new AtomicBoolean();var scheduled=new AtomicBoolean();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var checks=new AtomicInteger();
        var workerFailure=new AtomicReference<Throwable>();var result=new AtomicReference<LegacyRefinementBootstrap.Root>();
        try(var coordinator=new TileFetchCoordinator((r,c)->{fail("No acquisition");return null;},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=ledger.rootOwner()) {
            var sentinel=new Object();owner.reserve(1024).adopt(sentinel);
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,()->{
                if(!scheduled.get()&&checks.incrementAndGet()%128==0) {
                    for(var frame:Thread.currentThread().getStackTrace())if(frame.getMethodName().equals("materializeSmoothingRoot")&&scheduled.compareAndSet(false,true)) {
                        SwingUtilities.invokeLater(()->{entered.countDown();try {if(!release.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("EDT release timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}});
                        try {if(!entered.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("EDT did not block");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                        break;
                    }
                }
                return cancelled.get();
            },owner);
            try {
                SwingUtilities.invokeAndWait(()->{
                    var data=new DataSet();var selected=selection(data);var current=LegacyReviewOwner.begin(data,selected,config(AlignmentMode.PRECISE_SHAPE,false));review.set(current);
                    var points=selected.segmentNodes().stream().map(Node::getEastNorth).toList();var center=Projections.getProjectionByCode("EPSG:3857").latlon2eastNorth(new LatLon(50,14));
                    var candidate=new CenterlineCandidate("visible",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    current.captured(new AlignmentResult(selected,new BufferedImage(600,600,BufferedImage.TYPE_INT_ARGB),List.of(candidate),points,points,List.of(),null,null,List.of(new DetectorAttempt("hot","hot",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"","")),List.of(candidate)),factualFrame(new LegacyVisibleSource(center.east()-300,center.north()-300,center.east()+300,center.north()+300,6,3.86,OptionalDouble.empty(),"EPSG:3857",new VisibleSourceEpoch().captureStable(),OptionalDouble.of(3.5),OptionalDouble.of(1.2)),600,600,0,0));current.select(candidate);
                    seed.set(new LegacyRefinementBootstrap().capture(current,coordinator,work));
                });
                var worker=new Thread(()->{try{result.set(new LegacyRefinementBootstrap().complete(seed.get(),coordinator,work));}catch(Throwable failure){workerFailure.set(failure);}},"unit1-final-validation-worker");
                worker.start();
                try {
                    assertTrue(entered.await(30,java.util.concurrent.TimeUnit.SECONDS),"Materialization must block EDT");
                    long deadline=System.nanoTime()+30_000_000_000L;boolean waiting=false;
                    while(System.nanoTime()<deadline&&!waiting&&worker.isAlive()) {
                        boolean guard=false,invoke=false;for(var frame:worker.getStackTrace()){guard|=frame.getMethodName().equals("requireReviewCurrentOnEdt");invoke|=frame.getMethodName().equals("invokeAndWait");}
                        waiting=guard&&invoke;Thread.yield();
                    }
                    assertTrue(waiting,"Worker must be queued at the final EDT validation");cancelled.set(true);
                } finally {release.countDown();worker.join(30000);}
                assertFalse(worker.isAlive());assertInstanceOf(java.util.concurrent.CancellationException.class,workerFailure.get());assertNull(result.get());
                assertEquals(1024,ledger.currentBytes());assertFalse(owner.isClosed());assertEquals(1024,owner.retainIfAbsent(sentinel).bytes());
                SwingUtilities.invokeAndWait(()->{review.get().requireLegacyAuthorityCurrent();assertSame(seed.get().original().candidates().get(0),review.get().selectedCandidate());});
            } finally {release.countDown();if(review.get()!=null)SwingUtilities.invokeAndWait(review.get()::close);}
        }
        assertEquals(0,ledger.currentBytes());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"raster","radius","step","epoch","projection","mapping","aggregate","direct","actual-frame"})
    void visibleMissingFactsRefusesBeforeOptionalPixelsOrAuthority(String missing) throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((r,c)->{fail("Missing source facts must not acquire");return null;},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=AttemptMemoryLedger.production().rootOwner()) {
            var reads=new AtomicInteger();var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB){
                @Override public int[] getRGB(int x,int y,int width,int height,int[] values,int offset,int scan){reads.incrementAndGet();return super.getRGB(x,y,width,height,values,offset,scan);}
            };
            SwingUtilities.invokeAndWait(()->{
                var data=new DataSet();var selected=selection(data);var cfg=config(AlignmentMode.PRECISE_SHAPE,false,false,missing.equals("direct")?IntensitySamplingMode.DIRECT_ALPHA:IntensitySamplingMode.COLOR_MAPPING);var review=LegacyReviewOwner.begin(data,selected,cfg);
                try {
                    var points=selected.segmentNodes().stream().map(Node::getEastNorth).toList();var candidate=new CenterlineCandidate("historical-visible",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var attempts=missing.equals("mapping")?List.of(new DetectorAttempt("hot","hot",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"",""),new DetectorAttempt("hot","hot-corridor",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"","")):
                        List.of(new DetectorAttempt("hot",missing.equals("aggregate")?"all-colors-combined":"hot-corridor",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"",""));
                    var result=new AlignmentResult(selected,missing.equals("raster")?null:image,List.of(candidate),points,points,List.of(),null,null,attempts,List.of(candidate));
                    var metadata=new LegacyVisibleSource(0,0,8,8,1,1,OptionalDouble.empty(),missing.equals("projection")?"EPSG:4326":"EPSG:3857",missing.equals("epoch")?null:new VisibleSourceEpoch().captureStable(),missing.equals("radius")?OptionalDouble.empty():OptionalDouble.of(3.5),missing.equals("step")?OptionalDouble.empty():OptionalDouble.of(1.2));
                    review.captured(result,metadata);review.select(candidate);
                    var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                    var unavailable=assertThrows(LegacyRefinementBootstrap.SmoothingUnavailable.class,()->new LegacyRefinementBootstrap().capture(review,coordinator,work));
                    var reason=switch(missing) {
                        case "raster","aggregate" -> LegacyRefinementBootstrap.SmoothingUnavailableReason.ORIGINAL_SOURCE_UNAVAILABLE;
                        case "radius","step" -> LegacyRefinementBootstrap.SmoothingUnavailableReason.VISIBLE_SAMPLING_METADATA_UNAVAILABLE;
                        case "epoch" -> LegacyRefinementBootstrap.SmoothingUnavailableReason.VISIBLE_EPOCH_UNAVAILABLE;
                        case "projection" -> LegacyRefinementBootstrap.SmoothingUnavailableReason.VISIBLE_PROJECTION_TRANSFORM_UNAVAILABLE;
                        case "actual-frame" -> LegacyRefinementBootstrap.SmoothingUnavailableReason.VISIBLE_PAINTED_FRAME_UNAVAILABLE;
                        default -> LegacyRefinementBootstrap.SmoothingUnavailableReason.SOURCE_MAPPING_UNAVAILABLE;
                    };
                    assertEquals(reason,unavailable.reason());
                    assertEquals(0,reads.get());assertEquals(0,owner.currentBytes());assertFalse(data.isModified());
                    review.requireLegacyAuthorityCurrent();assertSame(candidate,review.selectedCandidate());assertEquals(List.of(candidate),result.applicableCandidates());
                }finally{review.close();}
            });
        }
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"visits","bytes"})
    void visibleLimitsConsumedAfterCaptureReleaseOnlyPreparation(String limit) throws Exception {
        var decoder=new TileDecoderClassifier();var ledger=AttemptMemoryLedger.production();var review=new AtomicReference<LegacyReviewOwner>();var seed=new AtomicReference<LegacyRefinementBootstrap.Seed>();
        try(var coordinator=new TileFetchCoordinator((r,c)->{fail("No acquisition");return null;},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=ledger.rootOwner()) {
            var sentinel=new Object();owner.reserve(1024).adopt(sentinel);long maxVisits=2_000_000,maxBytes=64L*1024*1024;
            var work=new GeometryWorkContext(maxVisits,maxBytes,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            try {
                SwingUtilities.invokeAndWait(()->{
                    var data=new DataSet();var selected=selection(data);var current=LegacyReviewOwner.begin(data,selected,config(AlignmentMode.PRECISE_SHAPE,false));review.set(current);
                    var points=selected.segmentNodes().stream().map(Node::getEastNorth).toList();var center=Projections.getProjectionByCode("EPSG:3857").latlon2eastNorth(new LatLon(50,14));
                    var candidate=new CenterlineCandidate("visible",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    current.captured(new AlignmentResult(selected,new BufferedImage(600,600,BufferedImage.TYPE_INT_ARGB),List.of(candidate),points,points,List.of(),null,null,List.of(new DetectorAttempt("hot","hot",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"","")),List.of(candidate)),factualFrame(new LegacyVisibleSource(center.east()-300,center.north()-300,center.east()+300,center.north()+300,6,3.86,OptionalDouble.empty(),"EPSG:3857",new VisibleSourceEpoch().captureStable(),OptionalDouble.of(3.5),OptionalDouble.of(1.2)),600,600,0,0));current.select(candidate);
                    seed.set(new LegacyRefinementBootstrap().capture(current,coordinator,work));
                });
                if(limit.equals("visits")) {
                    while(work.visits()<maxVisits)work.visit(GeometryWorkContext.Kind.BIND,Math.min(256,maxVisits-work.visits()));
                    assertThrows(GeometryWorkContext.LimitReached.class,()->new LegacyRefinementBootstrap().complete(seed.get(),coordinator,work));
                } else {
                    try(var occupied=work.reserve("test-caller-held-bytes",maxBytes-ledger.currentBytes())) {
                        assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->new LegacyRefinementBootstrap().complete(seed.get(),coordinator,work));
                    }
                }
                assertEquals(1024,ledger.currentBytes());assertFalse(owner.isClosed());assertEquals(1024,owner.retainIfAbsent(sentinel).bytes());
                SwingUtilities.invokeAndWait(()->{review.get().requireLegacyAuthorityCurrent();assertSame(seed.get().original().candidates().get(0),review.get().selectedCandidate());});
            }finally{if(review.get()!=null)SwingUtilities.invokeAndWait(review.get()::close);}
        }
        assertEquals(0,ledger.currentBytes());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"visits","bytes","deadline"})
    void visibleWorkLimitsPreserveCallerOwnedSentinel(String limit) throws Exception {
        var decoder=new TileDecoderClassifier();var ledger=AttemptMemoryLedger.production();
        try(var coordinator=new TileFetchCoordinator((r,c)->{fail("No acquisition");return null;},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=ledger.rootOwner()) {
            var sentinel=new Object();owner.reserve(1024).adopt(sentinel);
            SwingUtilities.invokeAndWait(()->{
                var data=new DataSet();var selected=selection(data);var review=LegacyReviewOwner.begin(data,selected,config(AlignmentMode.PRECISE_SHAPE,false));
                try {
                    var points=selected.segmentNodes().stream().map(Node::getEastNorth).toList();var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);
                    var candidate=new CenterlineCandidate("visible",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    review.captured(new AlignmentResult(selected,image,List.of(candidate),points,points,List.of(),null,null,List.of(new DetectorAttempt("hot","hot",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"","")),List.of(candidate)),factualFrame(new LegacyVisibleSource(0,0,8,8,1,1,OptionalDouble.empty(),"EPSG:3857",new VisibleSourceEpoch().captureStable(),OptionalDouble.of(3.5),OptionalDouble.of(1.2)),8,8,0,0));review.select(candidate);
                    var work=new GeometryWorkContext(limit.equals("visits")?2:8_000_000,limit.equals("bytes")?1024:AttemptMemoryLedger.MAX_BYTES,limit.equals("deadline")?0:Long.MAX_VALUE,CancellationProbe.NONE,owner);
                    if(limit.equals("bytes"))assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->new LegacyRefinementBootstrap().capture(review,coordinator,work));
                    else assertThrows(GeometryWorkContext.LimitReached.class,()->new LegacyRefinementBootstrap().capture(review,coordinator,work));
                    assertEquals(1024,ledger.currentBytes());assertFalse(owner.isClosed());assertEquals(1024,owner.retainIfAbsent(sentinel).bytes());review.requireLegacyAuthorityCurrent();
                } finally {review.close();}
            });
        }
        assertEquals(0,ledger.currentBytes());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"settings","geometry","epoch"})
    void visibleKnownInvalidationIsStaleNotMissingCapability(String change) throws Exception {
        var decoder=new TileDecoderClassifier();var epoch=new VisibleSourceEpoch();var ledger=AttemptMemoryLedger.production();
        try(var coordinator=new TileFetchCoordinator((r,c)->{fail("No acquisition");return null;},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=ledger.rootOwner()) {
            SwingUtilities.invokeAndWait(()->{
                var data=new DataSet();var selected=selection(data);var review=LegacyReviewOwner.begin(data,selected,config(AlignmentMode.PRECISE_SHAPE,false));
                try {
                    var points=selected.segmentNodes().stream().map(Node::getEastNorth).toList();var candidate=new CenterlineCandidate("visible",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    review.captured(new AlignmentResult(selected,new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),List.of(candidate),points,points,List.of(),null,null,List.of(new DetectorAttempt("hot","hot",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"","")),List.of(candidate)),factualFrame(new LegacyVisibleSource(0,0,8,8,1,1,OptionalDouble.empty(),"EPSG:3857",epoch.captureStable(),OptionalDouble.of(3.5),OptionalDouble.of(1.2)),8,8,0,0));review.select(candidate);
                    if(change.equals("epoch"))epoch.sourceChanged();else if(change.equals("geometry"))selected.segmentNodes().get(2).setCoor(new LatLon(50.0001,14));else {String before=Config.getPref().get("wayheatmaptracer.activity","all");Config.getPref().put("wayheatmaptracer.activity","ride");Config.getPref().put("wayheatmaptracer.activity",before);}
                    var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                    var failure=assertThrows(IllegalStateException.class,()->new LegacyRefinementBootstrap().capture(review,coordinator,work));
                    assertFalse(failure instanceof LegacyRefinementBootstrap.SmoothingUnavailable);assertEquals(0,ledger.currentBytes());
                } finally {review.close();if(change.equals("settings"))Config.getPref().put("wayheatmaptracer.activity",null);}
            });
        }
    }
    @ParameterizedTest @EnumSource(AlignmentMode.class)
    void managedContextOverloadRetainsOldChoiceAndOriginalSource(AlignmentMode mode) throws Exception {
        var calls=new AtomicInteger();var decoder=new TileDecoderClassifier();var review=new AtomicReference<LegacyReviewOwner>();
        var oldSeed=new AtomicReference<LegacyRefinementBootstrap.Seed>();var newSeed=new AtomicReference<LegacyRefinementBootstrap.Seed>();
        try(var coordinator=new TileFetchCoordinator((r,c)->{calls.incrementAndGet();return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(r.address()),null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            try {
                SwingUtilities.invokeAndWait(()->{
                    var data=new DataSet();var selected=selection(data);var cfg=config(mode,true,true);var current=LegacyReviewOwner.begin(data,selected,cfg);review.set(current);
                    var receipt=new AtomicReference<TileHeatmapSampler.AcquisitionReceipt>();
                    var result=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selected,null,null,cfg,new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt.set(actual);}});
                    current.captured(result,null,receipt.get());
                    current.select(result.candidates().stream().filter(candidate->result.detectorAttempts().stream()
                            .anyMatch(attempt->attempt.sourcePalette().equals("hot")&&attempt.mappingName().equals("hot")
                                    &&attempt.candidateIds().contains(candidate.id()))).findFirst().orElseThrow());
                    assertEquals(0,work.visits());assertEquals(0,owner.currentBytes());
                    oldSeed.set(new LegacyRefinementBootstrap().capture(current,coordinator,owner));
                    newSeed.set(new LegacyRefinementBootstrap().capture(current,coordinator,work));
                    assertSame(receipt.get(),newSeed.get().receipt());assertTrue(newSeed.get().originalSource().managed());assertFalse(data.isModified());
                });
                int before=calls.get();var oldRoot=new LegacyRefinementBootstrap().complete(oldSeed.get(),coordinator,CancellationProbe.NONE);
                var newRoot=new LegacyRefinementBootstrap().complete(newSeed.get(),coordinator,work);
                assertEquals(before,calls.get());assertEquals(oldRoot.stage().selected(),newRoot.stage().selected());
                assertEquals(oldRoot.stage().candidates().stream().map(c->c.id().value()).toList(),newRoot.stage().candidates().stream().map(c->c.id().value()).toList());
                var oldCandidate=oldRoot.stage().candidates().stream().filter(c->c.id().equals(oldRoot.stage().selected())).findFirst().orElseThrow();
                var newCandidate=newRoot.stage().candidates().stream().filter(c->c.id().equals(newRoot.stage().selected())).findFirst().orElseThrow();
                assertEquals(oldCandidate.payload().finalPreviewGeometry(),newCandidate.payload().finalPreviewGeometry());
                assertEquals(independentInput(newSeed.get().original().tileMosaics().require(newSeed.get().factualSeed().selectedSource().palette()).image()),newRoot.originalConversion().inputByteHash());
                assertNull(oldRoot.originalConversion(),"This regression preserves the historical managed root without a smoothing descriptor");
                assertNull(oldSeed.get().factualSeed());var authority=oldRoot.authority();var request=authority.request();var frozen=authority.frozenGeometryProof().orElseThrow();
                var laterWork=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                var later=new AtomicReference<LegacyRefinementBootstrap.SmoothingSeed>();
                SwingUtilities.invokeAndWait(()->later.set(new LegacyRefinementBootstrap().captureForSmoothingFromOriginal(review.get(),coordinator,laterWork,oldSeed.get())));
                assertSame(oldSeed.get().facts(),later.get().facts());assertSame(oldSeed.get().facts().network(),later.get().facts().network());assertSame(oldRoot.receipt(),later.get().facts().receipt());
                var bound=new LegacyRefinementBootstrap().completeForSmoothing(later.get(),coordinator,laterWork,oldRoot);
                assertSame(oldRoot,bound.root());assertSame(authority,bound.root().authority());assertSame(request,bound.root().authority().request());assertSame(frozen,bound.root().authority().frozenGeometryProof().orElseThrow());
                assertEquals(oldCandidate.nativeCandidateId(),bound.source().legacyDisposition().orElseThrow().nativeCandidateId());assertEquals(before,calls.get());
                var alternativeWork=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);var alternative=new AtomicReference<LegacyRefinementBootstrap.SmoothingSeed>();
                SwingUtilities.invokeAndWait(()->{
                    var current=review.get();var selectedSource=later.get().selectedSource();var other=current.originalResult().candidates().stream().filter(candidate->candidate.finalPreviewPoints().size()>=2&&current.originalResult().detectorAttempts().stream().anyMatch(attempt->attempt.candidateIds().contains(candidate.id())&&(!attempt.mappingName().equals(selectedSource.mapping())||!attempt.sourcePalette().equals(selectedSource.palette())))).findFirst().orElseThrow();
                    current.select(other);alternative.set(new LegacyRefinementBootstrap().captureForSmoothingFromOriginal(current,coordinator,alternativeWork,oldSeed.get()));
                });
                assertSame(oldSeed.get().facts(),alternative.get().facts());assertNotEquals(later.get().selectedSource(),alternative.get().selectedSource());
                var alternativeBound=new LegacyRefinementBootstrap().completeForSmoothing(alternative.get(),coordinator,alternativeWork,oldRoot);
                assertSame(oldRoot,alternativeBound.root());assertEquals(alternative.get().selectedSource(),alternativeBound.source().source());assertEquals(before,calls.get());
                coordinator.updateActiveGeneration(new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(coordinator.activeGenerationValue()+1));
                assertThrows(IllegalStateException.class,alternativeBound.source()::requireCurrent);
                SwingUtilities.invokeAndWait(()->assertThrows(IllegalStateException.class,()->new LegacyRefinementBootstrap().captureForSmoothingFromOriginal(review.get(),coordinator,alternativeWork,oldSeed.get())));
                assertSame(request,oldRoot.authority().request());assertSame(frozen,oldRoot.authority().frozenGeometryProof().orElseThrow());
            }finally{if(review.get()!=null)SwingUtilities.invokeAndWait(review.get()::close);}
        }
    }
    private static byte[] ridge(ManagedTileAddress address) {
        try {var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<512;y++){double world=(address.y()*512.+y+.5)/(512.*(1<<address.zoom()));double latitude=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*world))));
                int value=(int)Math.round(250*Math.exp(-.5*Math.pow((latitude-50)*111195/2,2)));for(int x=0;x<512;x++)image.setRGB(x,y,value==0?0:0xff000000|value<<16|value<<8|value);}
            var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
    private static String independentInput(BufferedImage image) throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest))){for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)out.writeInt(image.getRGB(x,y));}
        return HexFormat.of().formatHex(digest.digest());
    }
}
