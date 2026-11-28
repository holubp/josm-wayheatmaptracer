package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

/** Identity-inventory arithmetic over real acquired scalar groups; no synthetic native choice is claimed. */
class MarginalPayloadAdmissionTest {
    @TempDir java.nio.file.Path temp;
    @Test void newlySharedGroupCountsOnceHistoryCountsZeroAndDifferentMappingsRemainSeparate() throws Exception {
        var parent=RefinementSourcePlannerTest.parent();var ticket=RefinementSourcePlannerTest.ticket(parent);var plan=RefinementSourcePlanner.plan(ticket,RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.COLOR_MAPPING,false),parent);
        var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.setRGB(x,y,0xff8090ff);
        var png=new ByteArrayOutputStream();ImageIO.write(image,"png",png);var bytes=png.toByteArray();var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());var owner=AttemptMemoryLedger.production().rootOwner()) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var capture=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner).value();
            var alternative=RefinementAcquisitionTest.selectedPlan(plan,"blue","hot-corridor",false);
            var otherCapture=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator)
                    .acquire(alternative,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner).value();
            var evidence=capture.evidence("blue",CancellationProbe.NONE);var mapped=otherCapture.mappingEvidence("hot-corridor",CancellationProbe.NONE);
            var first=capture.retentionDescriptor(plan.requests().stream().filter(s->s.palette().equals("blue")&&s.mapping().equals("blue")).findFirst().orElseThrow(),evidence);
            var other=otherCapture.retentionDescriptor(alternative.requests().get(0),mapped);assertNotSame(first.evidence(),other.evidence());
            long group=first.charges().values().stream().mapToLong(Long::longValue).sum();long metadata=4096;
            var a=inventory(first,metadata);var b=inventory(first,metadata);var distinct=inventory(other,metadata);
            var union=new MarginalPayloadAdmission(List.of(),group+2*metadata,CancellationProbe.NONE);
            long admitted=union.require(a,0);union.admit(a,0,admitted);assertEquals(group+metadata,union.used());
            long second=union.require(b,0);assertEquals(metadata,second);union.admit(b,0,second);assertEquals(group+2*metadata,union.used());
            var history=new MarginalPayloadAdmission(List.of(a),metadata,CancellationProbe.NONE);assertEquals(metadata,history.require(b,0));assertThrows(RefinementSession.Refused.class,()->history.require(distinct,0));assertEquals(0,history.used());
            var different=new MarginalPayloadAdmission(List.of(),group+2*metadata,CancellationProbe.NONE);long once=different.require(a,0);different.admit(a,0,once);assertThrows(RefinementSession.Refused.class,()->different.require(distinct,0));assertEquals(group+metadata,different.used());
            first.requireCurrent();long physical=owner.currentBytes();try(var historyOwner=owner.child("actual-history-group-pin")){first.retainInto(historyOwner);assertEquals(physical,owner.currentBytes());first.requireCurrent();}assertEquals(physical,owner.currentBytes());
        }
    }
    @Test void rejectedOrOverflowingProspectiveInventoryCannotConsumeAnAllowance() {
        var admission=new MarginalPayloadAdmission(List.of(),100,CancellationProbe.NONE);var values=new IdentityHashMap<Object,Long>();values.put(new Object(),Long.MAX_VALUE);
        assertThrows(RefinementSession.Refused.class,()->admission.require(values,1));assertEquals(0,admission.used());
        values.clear();values.put(new Object(),-1L);assertThrows(IllegalArgumentException.class,()->admission.require(values,0));assertEquals(0,admission.used());
    }
    private static Map<Object,Long> inventory(RetainedSmoothingSource source,long metadata){var values=new IdentityHashMap<Object,Long>();values.putAll(source.charges());values.put(new Object(),metadata);return Collections.unmodifiableMap(values);}
}
