package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

class RefinementSourcePreparationTest {
    @TempDir java.nio.file.Path temp;
    @Test void actualManagedHashRefusalRecordsTheFailingSourceBeforeAnyNativeEntry() throws Exception {
        var parent=RefinementSourcePlannerTest.parent();var ticket=RefinementSourcePlannerTest.ticket(parent);
        var plan=RefinementSourcePlanner.plan(ticket,RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.COLOR_MAPPING,false),parent);
        var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.setRGB(x,y,0xffe0e0e0);
        var png=new ByteArrayOutputStream();ImageIO.write(image,"png",png);var bytes=png.toByteArray();var decoder=new TileDecoderClassifier();var nativeEntries=new java.util.concurrent.atomic.AtomicInteger();
        try(var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=AttemptMemoryLedger.production().rootOwner()) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(9));
            var capture=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner).value();
            var work=new GeometryWorkContext(0,AttemptMemoryLedger.MAX_BYTES,System.nanoTime()+60_000_000_000L,CancellationProbe.NONE,owner);
            var result=new RefinementPipeline().run(ticket,parent,plan,new RefinementPipeline.Adapters(RefinementPipeline.managedSources(capture,coordinator,work),RefinementPipeline.productionRunner(t->nativeEntries.incrementAndGet()),owner),RefinementPipeline.OperationBudget.production(parent.root().request().budgets()),CancellationProbe.NONE);
            assertEquals(RefinementModels.Outcome.RESOURCE_LIMIT,result.outcome());assertEquals(0,nativeEntries.get());assertEquals(TraceWorkUsage.none(),result.actualWork().orElseThrow());
            var failed=result.attempts().get(0);assertEquals(plan.requests().get(0),failed.source());assertEquals(RefinementPipeline.AttemptKind.UNAVAILABLE,failed.kind());assertEquals("source-preparation-resource-limit",failed.reason());assertEquals(0,failed.nativeCallAvailability().orElseThrow());
            assertTrue(result.attempts().stream().skip(1).allMatch(a->a.kind()==RefinementPipeline.AttemptKind.UNATTEMPTED));
        }
    }
}
