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

class LegacySmoothingBootstrapTest {
    @TempDir Path temp;
    @BeforeAll static void setup(){Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));}
    private static AlignmentConfig config(AlignmentMode mode,boolean managed) {
        return config(mode,managed,false);
    }
    private static AlignmentConfig config(AlignmentMode mode,boolean managed,boolean aggregate) {
        return AlignmentConfig.withoutCleanup(new ManagedHeatmapConfig(managed?"key":"",managed?"policy":"",managed?"signature":"",managed?"session":"","all","hot","",".*",
            mode,TrackerMode.LEGACY_V02,false,false,true,aggregate,false,false,false,true,false,false,7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,IntensitySamplingMode.COLOR_MAPPING,7));
    }
    private static SelectionContext selection(DataSet data) {
        return selection(data,50);
    }
    private static SelectionContext selection(DataSet data,double latitude) {
        var nodes=new ArrayList<Node>();for(int i=0;i<5;i++){var node=new Node(new LatLon(latitude,14+(i-2)*.0002));data.addPrimitive(node);nodes.add(node);}
        var way=new Way();way.setNodes(nodes);way.put("highway","path");data.addPrimitive(way);data.allPrimitives().forEach(p->p.setModified(false));
        return new SelectionContext(way,0,4,nodes,Set.of(nodes.get(0),nodes.get(4)));
    }
    @Test void actualManagedAggregateRetainsAllFiveSourcesBeforeScalarResampling() throws Exception {
        var decoder=new TileDecoderClassifier();var palettes=new HashSet<String>();var calls=new AtomicInteger();
        try(var coordinator=new TileFetchCoordinator((r,c)->{palettes.add(r.address().color());calls.incrementAndGet();return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(r.address(),49.998),null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
            var review=new AtomicReference<LegacyReviewOwner>();var seed=new AtomicReference<LegacyRefinementBootstrap.SmoothingSeed>();var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                SwingUtilities.invokeAndWait(()->{
                    var data=new DataSet();var selected=selection(data,49.998);var cfg=config(AlignmentMode.PRECISE_SHAPE,true,true);var current=LegacyReviewOwner.begin(data,selected,cfg);review.set(current);
                    var receipt=new AtomicReference<TileHeatmapSampler.AcquisitionReceipt>();
                    var result=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selected,null,null,cfg,new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt.set(actual);}});
                    var attempt=result.detectorAttempts().stream().filter(a->a.mappingName().equals("all-colors-combined")&&!a.candidateIds().isEmpty()).findFirst().orElseThrow();
                    current.captured(result,null,receipt.get());current.select(result.candidates().stream().filter(c->attempt.candidateIds().contains(c.id())).findFirst().orElseThrow());
                    seed.set(new LegacyRefinementBootstrap().captureForSmoothing(current,coordinator,work));
                });
                int before=calls.get();var root=new LegacyRefinementBootstrap().completeForSmoothing(seed.get(),coordinator,work);
                assertEquals(independentInputSet(seed.get().original().tileMosaics()),root.source().inputByteHash());
                assertEquals(Set.copyOf(RefinementSourcePlanner.BASE_PALETTES),palettes);assertEquals(before,calls.get());
                assertTrue(root.source().source().aggregate());assertTrue(root.source().evidence().fields().get("native").lineage().completeAggregate());
                assertEquals(64,root.source().inputByteHash().length());assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.ORIGINAL_ARGB_SET_PROTOCOL,root.source().inputHashProtocol());
                assertEquals("all-colors-combined",root.source().source().mapping());assertTrue(ledger.peakBytes()<=AttemptMemoryLedger.MAX_BYTES);
                System.out.println("smoothing-legacy aggregate groupBytes="+root.source().backingBytes()+" currentBytes="+ledger.currentBytes()+" peakBytes="+ledger.peakBytes()+" visits="+work.visits());
            }finally{if(review.get()!=null)SwingUtilities.invokeAndWait(review.get()::close);}
        }
    }
    private static CenterlineCandidate lowerMapping(AlignmentResult result) {
        var attempt=result.detectorAttempts().stream().filter(a->a.mappingName().equals("hot-corridor")&&!a.candidateIds().isEmpty()).findFirst().orElseThrow();
        return result.candidates().stream().filter(c->attempt.candidateIds().contains(c.id())).findFirst().orElseThrow();
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void earlierShortDiagnosticKeepsOriginalOwnerChoiceAndExactSelectedGeometry(boolean managed) throws Exception {
        var decoder=new TileDecoderClassifier();var review=new AtomicReference<LegacyReviewOwner>();var seed=new AtomicReference<LegacyRefinementBootstrap.SmoothingSeed>();
        var nativeSeed=new AtomicReference<LegacyRefinementBootstrap.Seed>();var expected=new AtomicReference<CenterlineCandidate>();
        try(var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(r.address()),null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,System.nanoTime()+60_000_000_000L,CancellationProbe.NONE,owner);
            try {
                SwingUtilities.invokeAndWait(()->{
                    var data=new DataSet();var selected=selection(data);var cfg=config(AlignmentMode.PRECISE_SHAPE,managed);var current=LegacyReviewOwner.begin(data,selected,cfg);review.set(current);
                    var source=selected.segmentNodes().stream().map(Node::getEastNorth).toList();var first=new ArrayList<EastNorth>();var second=new ArrayList<EastNorth>();
                    for(int i=0;i<source.size();i++){var point=source.get(i);first.add(new EastNorth(point.east(),point.north()+(i==0||i==source.size()-1?0:.2)));second.add(new EastNorth(point.east(),point.north()-(i==0||i==source.size()-1?0:.2)));}
                    var shortDiagnostic=new CenterlineCandidate("short-diagnostic",0,List.of(),List.of(),List.of(),List.of(),Map.of(),List.of(),0,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var a=new CenterlineCandidate("selected-original-A",1,List.of(),List.of(),first,first,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var b=new CenterlineCandidate("later-original-B",1,List.of(),List.of(),second,second,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());expected.set(a);
                    var receipt=new AtomicReference<TileHeatmapSampler.AcquisitionReceipt>();AlignmentResult captured=null;
                    if(managed)captured=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selected,null,null,cfg,new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt.set(actual);}});
                    var image=managed?null:new BufferedImage(600,600,BufferedImage.TYPE_INT_ARGB);if(image!=null)for(int y=0;y<600;y++)for(int x=0;x<600;x++)image.setRGB(x,y,0xffffffff);
                    var result=new AlignmentResult(selected,image,List.of(shortDiagnostic,a,b),source,first,List.of(),null,managed?captured.tileMosaics():null,List.of(new DetectorAttempt("hot","hot",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(shortDiagnostic.id(),a.id(),b.id()),"","")),List.of(a,b));
                    var center=Projections.getProjectionByCode("EPSG:3857").latlon2eastNorth(new LatLon(50,14));var visible=managed?null:new LegacyVisibleSource(center.east()-300,center.north()-300,center.east()+300,center.north()+300,6,3.86,OptionalDouble.empty(),"EPSG:3857",new VisibleSourceEpoch().captureStable(),OptionalDouble.of(3.5),OptionalDouble.of(1.2));
                    current.captured(result,visible,receipt.get());current.select(a);assertEquals("legacy-choice-1",current.choiceId(a));
                    if(managed)nativeSeed.set(new LegacyRefinementBootstrap().capture(current,coordinator,owner));
                    seed.set(new LegacyRefinementBootstrap().captureForSmoothing(current,coordinator,work));
                });
                if(managed){var nativeRoot=new LegacyRefinementBootstrap().complete(nativeSeed.get(),coordinator,CancellationProbe.NONE);var selected=nativeRoot.stage().candidates().stream().filter(c->c.id().equals(nativeRoot.stage().selected())).findFirst().orElseThrow();assertEquals(expected.get().id(),selected.nativeCandidateId());}
                var result=new LegacyRefinementBootstrap().completeForSmoothing(seed.get(),coordinator,work);
                assertEquals("legacy-choice-1",result.selected().id().value());assertEquals(expected.get().id(),result.selected().nativeCandidateId());assertEquals(expected.get().finalPreviewPoints(),result.selected().payload().finalPreviewGeometry());
                SwingUtilities.invokeAndWait(()->{var current=review.get();current.select(current.originalResult().candidates().get(0));var refused=assertThrows(LegacyRefinementBootstrap.SmoothingUnavailable.class,()->new LegacyRefinementBootstrap().captureForSmoothing(current,coordinator,work));assertEquals("selected-final-preview-unavailable",refused.getMessage());});
            }finally{if(review.get()!=null)SwingUtilities.invokeAndWait(review.get()::close);}
        }
    }
    @Test void historicalVisibleMetadataRefusesBeforePixelsOrRootCaptureAndKeepsLegacyReview() throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((r,c)->{fail("Missing source facts must not acquire");return null;},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=AttemptMemoryLedger.production().rootOwner()) {
            var reads=new AtomicInteger();var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB){
                @Override public int[] getRGB(int x,int y,int width,int height,int[] values,int offset,int scan){reads.incrementAndGet();return super.getRGB(x,y,width,height,values,offset,scan);}
            };
            SwingUtilities.invokeAndWait(()->{
                var data=new DataSet();var selected=selection(data);var cfg=config(AlignmentMode.PRECISE_SHAPE,false);var review=LegacyReviewOwner.begin(data,selected,cfg);
                try {
                    var points=selected.segmentNodes().stream().map(Node::getEastNorth).toList();var candidate=new CenterlineCandidate("historical-visible",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var result=new AlignmentResult(selected,image,List.of(candidate),points,points,List.of(),null,null,List.of(new DetectorAttempt("hot","hot-corridor",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"","")),List.of(candidate));
                    review.captured(result,new LegacyVisibleSource(0,0,8,8,1,1,OptionalDouble.empty(),"EPSG:3857",new VisibleSourceEpoch().captureStable()));review.select(candidate);
                    var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                    var unavailable=assertThrows(LegacyRefinementBootstrap.SmoothingUnavailable.class,()->new LegacyRefinementBootstrap().captureForSmoothing(review,coordinator,work));
                    assertEquals(LegacyRefinementBootstrap.SmoothingUnavailableReason.VISIBLE_SAMPLING_METADATA_UNAVAILABLE,unavailable.reason());
                    assertEquals("visible-sampling-metadata-unavailable",unavailable.getMessage());
                    assertEquals(0,reads.get());assertEquals(0,owner.currentBytes());assertFalse(data.isModified());
                    review.requireOriginalAttemptCurrent();assertSame(candidate,review.selectedCandidate());assertEquals(List.of(candidate),result.applicableCandidates());
                }finally{review.close();}
            });
        }
    }
    @ParameterizedTest @EnumSource(AlignmentMode.class)
    void managedLowerMappingUsesOriginalRasterWithoutRefreshingOrCallingAnEngine(AlignmentMode mode) throws Exception {
        var requests=new AtomicInteger();var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((r,c)->{requests.incrementAndGet();return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(r.address()),null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
            var review=new AtomicReference<LegacyReviewOwner>();var seed=new AtomicReference<LegacyRefinementBootstrap.SmoothingSeed>();var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                SwingUtilities.invokeAndWait(()->{
                    var data=new DataSet();var selected=selection(data);var cfg=config(mode,true);var current=LegacyReviewOwner.begin(data,selected,cfg);review.set(current);
                    var receipt=new AtomicReference<TileHeatmapSampler.AcquisitionReceipt>();
                    var result=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selected,null,null,cfg,new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt.set(actual);}});
                    current.captured(result,null,receipt.get());current.select(lowerMapping(result));
                    assertEquals(0,work.visits());assertEquals(0,ledger.currentBytes(),"Ordinary Legacy capture/review performs no optional smoothing preparation");
                    seed.set(new LegacyRefinementBootstrap().captureForSmoothing(current,coordinator,work));assertFalse(data.isModified());
                });
                int before=requests.get();var root=new LegacyRefinementBootstrap().completeForSmoothing(seed.get(),coordinator,work);
                assertEquals(independentInput(seed.get().original().tileMosaics().require("hot").image()),root.source().inputByteHash());
                assertEquals(64,root.source().inputByteHash().length());assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL,root.source().inputHashProtocol());
                assertEquals(before,requests.get());assertEquals("hot-corridor",root.source().source().mapping());assertEquals("hot",root.source().source().palette());
                var transform=(org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.WebMercator)root.source().referenceTransform();
                assertEquals(2.,transform.rasterPixelsPerWorldPixel());assertTrue(transform.inputWorldPixelBoundaryX()>0);
                var center=new GeographicPoint(50,14);var pixel=transform.toRasterCenter(center);
                double expectedWorld=(14.+180.)/360.*512.*(1<<15);
                assertEquals(expectedWorld-transform.inputWorldPixelBoundaryX()*2-.5,pixel.x(),1e-8);
                var candidate=root.selected();var stage=root.root().stage();var input=new SmoothingInput(stage.id(),candidate.id(),stage.id(),candidate.id(),"Medium");
                var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,stage.id(),candidate.id(),TrackerMode.LEGACY_V02,mode,StageKind.SMOOTH,Optional.of(input));
                var parent=new SmoothingParentBinder().bind(root.root().authority(),ticket,candidate,root.source(),work);
                assertEquals("hot-corridor",parent.frame().mappingIdentity());assertTrue(parent.nativeBasis().isEmpty());assertEquals(before,requests.get());
                assertEquals(root.source().referenceWidth(),parent.frame().inputWidth());
                System.out.println("smoothing-legacy managed mode="+mode+" groupBytes="+root.source().backingBytes()+" currentBytes="+ledger.currentBytes()+" peakBytes="+ledger.peakBytes()+" visits="+work.visits());
            }finally{if(review.get()!=null)SwingUtilities.invokeAndWait(review.get()::close);}
            assertEquals(0,ledger.currentBytes());
        }
    }
    @ParameterizedTest @EnumSource(AlignmentMode.class)
    void visibleBothModesUseRecordedSamplingWindowAndEpochWithNoAcquisition(AlignmentMode mode) throws Exception {
        var epoch=new VisibleSourceEpoch();var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((r,c)->{fail("Smoothing must not fetch an image");return null;},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
            var review=new AtomicReference<LegacyReviewOwner>();var seed=new AtomicReference<LegacyRefinementBootstrap.SmoothingSeed>();var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                SwingUtilities.invokeAndWait(()->{
                    var data=new DataSet();var selected=selection(data);var cfg=config(mode,false);var current=LegacyReviewOwner.begin(data,selected,cfg);review.set(current);
                    var points=selected.segmentNodes().stream().map(Node::getEastNorth).toList();var center=Projections.getProjectionByCode("EPSG:3857").latlon2eastNorth(new LatLon(50,14));
                    var image=new BufferedImage(600,600,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<600;y++)for(int x=0;x<600;x++)image.setRGB(x,y,0xffffffff);
                    var candidate=new CenterlineCandidate("visible-lower",1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
                    var result=new AlignmentResult(selected,image,List.of(candidate),points,points,List.of(),null,null,List.of(new DetectorAttempt("hot","hot-corridor",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(candidate.id()),"","")),List.of(candidate));
                    var metadata=new LegacyVisibleSource(center.east()-300,center.north()-300,center.east()+300,center.north()+300,6,3.86,OptionalDouble.empty(),"EPSG:3857",epoch.captureStable(),OptionalDouble.of(3.5),OptionalDouble.of(1.2));
                    current.captured(result,metadata);current.select(candidate);seed.set(new LegacyRefinementBootstrap().captureForSmoothing(current,coordinator,work));
                });
                var root=new LegacyRefinementBootstrap().completeForSmoothing(seed.get(),coordinator,work);
                assertEquals(3.5,root.root().authority().request().permissions().ordinaryRadiusMeters());assertEquals(1.2,root.root().authority().request().configuredSampleStepMeters());
                assertEquals("hot-corridor",root.source().source().mapping());assertEquals(600,root.source().referenceWidth());assertEquals(600,root.source().referenceHeight());
                var candidate=root.selected();var stage=root.root().stage();var input=new SmoothingInput(stage.id(),candidate.id(),stage.id(),candidate.id(),"Medium");
                var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,stage.id(),candidate.id(),TrackerMode.LEGACY_V02,mode,StageKind.SMOOTH,Optional.of(input));
                var parent=new SmoothingParentBinder().bind(root.root().authority(),ticket,candidate,root.source(),work);
                assertSame(root.source().evidence(),parent.evidence());assertEquals(root.source().inputByteHash(),parent.frame().inputHash());
                assertEquals(64,parent.frame().inputHash().length());assertTrue(parent.frame().originalAffine().isEmpty());
                var transform=(org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.VisibleWebMercator)root.source().referenceTransform();
                var point=transform.toRasterCenter(new GeographicPoint(50,14));assertEquals(300,point.x(),1e-8);assertEquals(300,point.y(),1e-8);
                root.source().requireCurrent();epoch.sourceChanged();assertThrows(IllegalStateException.class,root.source()::requireCurrent);
                System.out.println("smoothing-legacy visible mode="+mode+" groupBytes="+root.source().backingBytes()+" currentBytes="+ledger.currentBytes()+" peakBytes="+ledger.peakBytes()+" visits="+work.visits());
            }finally{if(review.get()!=null)SwingUtilities.invokeAndWait(review.get()::close);}
            assertEquals(0,ledger.currentBytes());
        }
    }
    private static byte[] ridge(ManagedTileAddress address) {
        return ridge(address,50);
    }
    private static String independentInput(BufferedImage image) throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest))){for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)out.writeInt(image.getRGB(x,y));}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String independentInputSet(TileHeatmapSampler.TileMosaicSet mosaics) throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest))) {
            out.write("g004-original-argb-set-v1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.writeInt(5);
            for(String palette:List.of("hot","blue","bluered","purple","gray")) {
                var image=mosaics.require(palette).image();var name=palette.getBytes(java.nio.charset.StandardCharsets.US_ASCII);out.writeInt(name.length);out.write(name);out.writeInt(image.getWidth());out.writeInt(image.getHeight());
                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)out.writeInt(image.getRGB(x,y));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static byte[] ridge(ManagedTileAddress address,double centerLatitude) {
        try{var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<512;y++){double world=(address.y()*512.+y+.5)/(512.*(1<<address.zoom()));double latitude=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*world))));
                int value=(int)Math.round(250*Math.exp(-.5*Math.pow((latitude-centerLatitude)*111195/2,2)));for(int x=0;x<512;x++)image.setRGB(x,y,value==0?0:0xff000000|value<<16|value<<8|value);}
            var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
