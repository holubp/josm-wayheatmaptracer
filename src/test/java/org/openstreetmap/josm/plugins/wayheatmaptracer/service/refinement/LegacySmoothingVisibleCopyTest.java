package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class LegacySmoothingVisibleCopyTest {
    @Test void admittedVisibleCopyIsLazyImmutableAndKeepsOriginalEpoch() {
        var epoch=new VisibleSourceEpoch();var source=new LegacyVisibleSource(0,0,100,100,6,3.86,OptionalDouble.empty(),"EPSG:3857",epoch.captureStable());
        var original=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB);original.setRGB(7,9,0xff123456);
        var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()) {
            var work=new GeometryWorkContext(1_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            var frozen=source.freeze(original,"original-visible",work);original.setRGB(7,9,0xffabcdef);
            assertEquals(0xff123456,frozen.argbAt(7,9,work));assertEquals(100,frozen.width());assertEquals(100,frozen.height());
            var copied=frozen.copyImage(work);copied.setRGB(7,9,0xff000000);assertEquals(0xff123456,frozen.argbAt(7,9,work));
            assertEquals(64,frozen.inputByteHash().length());epoch.sourceChanged();assertThrows(IllegalStateException.class,frozen::requireCurrent);
        }assertEquals(0,ledger.currentBytes());
    }
    @Test void refusalAndCancellationSettleBeforeAnUnownedVisibleCopySurvives() {
        var epoch=new VisibleSourceEpoch();var source=new LegacyVisibleSource(0,0,100,100,6,3.86,OptionalDouble.empty(),"EPSG:3857",epoch.captureStable());
        var original=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB);
        for(long limit:new long[]{0,257}) {
            var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()) {
                var work=new GeometryWorkContext(limit,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                assertThrows(GeometryWorkContext.LimitReached.class,()->source.freeze(original,"original-visible",work));
                assertEquals(0,ledger.currentBytes());assertTrue(work.visits()<=limit);
            }
        }
    }
}
