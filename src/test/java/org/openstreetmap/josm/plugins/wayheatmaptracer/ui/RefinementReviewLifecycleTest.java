package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewWorkGate;

class RefinementReviewLifecycleTest {
    @Test void hidesImmediatelyAndOnlyCurrentCompletionRestoresReviewWithoutSettlingWorkers() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var events=new ArrayList<String>();var owner=new Object();var gate=new PreviewWorkGate(Runnable::run);gate.adopt(owner,()->{});
            var lifecycle=new RefinementReviewLifecycle(()->events.add("hide"),()->events.add("show"),()->events.add("progress"),()->events.add("settled-view"),events::add);
            var first=gate.beginWork(owner);lifecycle.begin(owner,first);assertEquals(List.of("hide","progress"),events);
            gate.workerSettled(first);var second=gate.beginWork(owner);lifecycle.begin(owner,second);
            lifecycle.complete(first,true,"");assertFalse(events.contains("show"),"Bootstrap transfer must not flash parent review");
            lifecycle.complete(second,false,"Operation cancelled");
            assertEquals(List.of("hide","progress","Operation cancelled","settled-view","show"),events);
            lifecycle.complete(second,true,"");assertEquals(1,Collections.frequency(events,"show"));
            assertFalse(gate.settlement().toCompletableFuture().isDone(),"Visibility does not imply physical settlement");gate.workerSettled(second);
        });
    }
    @Test void closedAndInvalidatedOwnersNeverReopenOnLatePublication() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var events=new ArrayList<String>();var owner=new Object();var gate=new PreviewWorkGate(Runnable::run);gate.adopt(owner,()->{});
            var lifecycle=new RefinementReviewLifecycle(()->events.add("hide"),()->events.add("show"),()->events.add("progress"),()->events.add("settled-view"),events::add);
            var work=gate.beginWork(owner);lifecycle.begin(owner,work);lifecycle.close();lifecycle.complete(work,true,"");
            assertFalse(events.contains("show"));gate.workerSettled(work);
        });
    }
    @Test void progressPresentationFailureRestoresParentAndDoesNotReleaseWork() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var events=new ArrayList<String>();var owner=new Object();var gate=new PreviewWorkGate(Runnable::run);gate.adopt(owner,()->{});
            var lifecycle=new RefinementReviewLifecycle(()->events.add("hide"),()->events.add("show"),()->{throw new IllegalStateException("synthetic-private-text");},()->events.add("settled-view"),events::add);
            var work=gate.beginWork(owner);assertThrows(IllegalStateException.class,()->lifecycle.begin(owner,work));
            assertTrue(events.contains("show"));assertFalse(events.toString().contains("synthetic-private-text"));
            assertFalse(gate.settlement().toCompletableFuture().isDone());gate.workerSettled(work);
        });
    }
}
