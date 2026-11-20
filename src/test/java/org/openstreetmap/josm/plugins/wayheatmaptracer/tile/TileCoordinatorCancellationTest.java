package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class TileCoordinatorCancellationTest {
 @TempDir Path temp;
 @Test void soleCancellationReachesOperationAndPreventsCache() throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var operation=new AtomicReference<CancellationToken>();var body=png();
  try(var c=coordinator((r,k)->{operation.set(r.cancellation());entered.countDown();await(release);return success(body);})){
   var token=new CancellationToken();var request=request(1,token);var result=c.fetch(request,credentials()).toCompletableFuture();
   try {assertTrue(entered.await(3,TimeUnit.SECONDS));token.cancel();assertEquals(TileFetchStatus.CANCELLED,result.get().status());assertTrue(operation.get().isCancelled());}
   finally {release.countDown();}
   settled(c);assertFalse(Files.exists(cachePath(request)));assertEquals(0,c.stats().memoryHits());
   assertEquals(TileFetchStatus.SUCCESS_NETWORK,c.fetch(request(1,new CancellationToken()),credentials()).toCompletableFuture().get(3,TimeUnit.SECONDS).status(),"Canceled operation must not seed memory or disk success");assertEquals(2,c.stats().transportExecutions());
  }
 }
 @Test void cancelledDrainingFlightCannotBeRevivedByLateJoin() throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();var body=png();
  try(var c=coordinator((r,k)->{calls.incrementAndGet();entered.countDown();await(release);return success(body);})){
   var token=new CancellationToken();c.fetch(request(1,token),credentials());
   try {assertTrue(entered.await(3,TimeUnit.SECONDS));token.cancel();var late=c.fetch(request(1,new CancellationToken()),credentials()).toCompletableFuture();assertTrue(late.isDone(),"Draining work must refuse late attachment");assertEquals(TileFetchStatus.CANCELLED,late.get().status());assertEquals(1,calls.get());}
   finally {release.countDown();}
   settled(c);assertTrue(c.fetch(request(1,new CancellationToken()),credentials()).toCompletableFuture().get(3,TimeUnit.SECONDS).usable());assertEquals(2,calls.get());
  }
 }
 @Test void queuedCancelledSubscriberNeverStartsTransport() throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();var body=png();
  try(var c=coordinator((r,k)->{calls.incrementAndGet();entered.countDown();await(release);return success(body);})){
   var first=c.fetch(request(1,new CancellationToken()),credentials());assertTrue(entered.await(3,TimeUnit.SECONDS));var token=new CancellationToken();var second=c.fetch(request(2,token),credentials()).toCompletableFuture();token.cancel();release.countDown();first.toCompletableFuture().get(3,TimeUnit.SECONDS);settled(c);assertEquals(TileFetchStatus.CANCELLED,second.get().status());assertEquals(1,calls.get());assertFalse(Files.exists(cachePath(request(2,token))));
  } finally {release.countDown();}
 }
 @Test void closedCoordinatorIsNotAnActiveSource() {
  var c=coordinator((r,k)->success(new byte[0]));c.updateActiveGeneration(new ManagedTileGeneration(9));assertTrue(c.isActiveGeneration(new ManagedTileGeneration(9)));c.close();c.close();assertFalse(c.isActiveGeneration(new ManagedTileGeneration(9)));
 }
 @Test void survivorKeepsOperationAndCommittedCacheSurvivesLaterCancellation() throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var operation=new AtomicReference<CancellationToken>();var body=png();var calls=new AtomicInteger();
  try(var c=coordinator((r,k)->{calls.incrementAndGet();operation.set(r.cancellation());entered.countDown();await(release);return success(body);})){
   var a=new CancellationToken();var b=new CancellationToken();var first=c.fetch(request(1,a),credentials()).toCompletableFuture();var second=c.fetch(request(1,b),credentials()).toCompletableFuture();assertTrue(entered.await(3,TimeUnit.SECONDS));a.cancel();assertEquals(TileFetchStatus.CANCELLED,first.get().status());assertFalse(operation.get().isCancelled());release.countDown();assertTrue(second.get(3,TimeUnit.SECONDS).usable());settled(c);b.cancel();assertTrue(Files.exists(cachePath(request(1,b))));assertEquals(1,calls.get());
  } finally {release.countDown();}
 }
 @Test void postDecodeCancellationGenerationAndCloseRejectStagedPublication() throws Exception {
  for(int mode=0;mode<3;mode++) {
   var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var body=png();
   var c=coordinator((r,k)->success(body),()->{entered.countDown();await(release);});
   var token=new CancellationToken();var request=request(10+mode,token);var result=c.fetch(request,credentials()).toCompletableFuture();
   try {
    assertTrue(entered.await(3,TimeUnit.SECONDS),"Decoder and temp staging must have completed");
    try(var files=Files.walk(temp)){assertTrue(files.anyMatch(f->f.getFileName().toString().startsWith(".whtr-")));}
    if(mode==0)token.cancel();else if(mode==1)c.updateActiveGeneration(new ManagedTileGeneration(10));else c.close();
    release.countDown();assertEquals(mode==1?TileFetchStatus.STALE_GENERATION:TileFetchStatus.CANCELLED,result.get(3,TimeUnit.SECONDS).status());
    if(mode==2){var field=TileFetchCoordinator.class.getDeclaredField("workers");field.setAccessible(true);assertTrue(((ExecutorService)field.get(c)).awaitTermination(3,TimeUnit.SECONDS));}else settled(c);
    assertFalse(Files.exists(cachePath(request)));try(var files=Files.walk(temp)){assertFalse(files.anyMatch(f->f.getFileName().toString().startsWith(".whtr-")),"Refused publication must discard temporary bytes");}
   } finally {release.countDown();c.close();}
  }
 }
 private TileFetchCoordinator coordinator(ManagedTileTransport transport){return coordinator(transport,()->{});}
 private TileFetchCoordinator coordinator(ManagedTileTransport transport,Runnable beforePublication){var d=new TileDecoderClassifier();var p=TileReliabilityPolicy.defaults();var one=new TileReliabilityPolicy(1,p.maximumQueueSize(),p.connectTimeoutMillis(),p.readTimeoutMillis(),p.maximumBodyBytes(),p.memoryCacheBytes(),p.negativeCacheEntries(),p.noTileTtl(),p.contentFailureTtl(),p.transientFailureTtl(),p.rateLimitFallback(),p.rateLimitMaximum());var c=new TileFetchCoordinator(transport,new ManagedTileCache(temp,d),d,one,Clock.systemUTC(),beforePublication);c.updateActiveGeneration(new ManagedTileGeneration(9));return c;}
 private TileRequest request(int x,CancellationToken token){return new TileRequest(new ManagedTileAddress("all","hot",15,x,1),new ManagedTileGeneration(9),TilePurpose.ALIGNMENT_REQUIRED,TileCachePolicy.USE_CACHE,Instant.now().plusSeconds(30),token);}
 private Path cachePath(TileRequest r){return new ManagedTileCache(temp,new TileDecoderClassifier()).path(r.generation(),r.address());}
 private static CredentialSnapshot credentials(){return CredentialSnapshot.forTesting("synthetic-cookie");}
 private static TransportResponse success(byte[] body){return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",body,null,Duration.ZERO,"");}
 private static byte[] png() throws Exception {var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.setRGB(x,y,0xffff7800);var out=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",out));return out.toByteArray();}
 private static void await(CountDownLatch latch){try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
 private static void settled(TileFetchCoordinator c) throws Exception {var done=new CompletableFuture<Void>();assertTrue(c.dispatch(TilePurpose.ALIGNMENT_REQUIRED,()->done.complete(null)));done.get(3,TimeUnit.SECONDS);}
}
