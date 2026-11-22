package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewWorkGate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket;

/** EDT visibility only. The existing work gate and session retain cancellation/history ownership. */
public final class RefinementReviewLifecycle implements AutoCloseable {
    private final Runnable hideReview,showReview,showProgress,hideProgress;
    private final Consumer<String> showFailure;
    private Object owner;
    private PreviewWorkGate.Work active;
    private OperationTicket ticket;
    private boolean closed;
    public RefinementReviewLifecycle(Runnable hideReview,Runnable showReview,Runnable showProgress,
            Runnable hideProgress,Consumer<String> showFailure) {
        this.hideReview=Objects.requireNonNull(hideReview);this.showReview=Objects.requireNonNull(showReview);
        this.showProgress=Objects.requireNonNull(showProgress);this.hideProgress=Objects.requireNonNull(hideProgress);
        this.showFailure=Objects.requireNonNull(showFailure);
    }
    public void begin(Object nextOwner,PreviewWorkGate.Work work) {
        requireEdt();if(closed)throw new IllegalStateException("Refinement review is closed");
        Objects.requireNonNull(nextOwner);Objects.requireNonNull(work);
        boolean transfer=active!=null&&owner==nextOwner;
        owner=nextOwner;active=work;ticket=null;
        if(transfer)return;
        try{hideReview.run();showProgress.run();}
        catch(RuntimeException failed){
            active=null;owner=null;
            try{hideProgress.run();}catch(RuntimeException ignored){ }
            try{showReview.run();}catch(RuntimeException ignored){ }
            try{showFailure.accept("Refinement progress could not be displayed.");}catch(RuntimeException ignored){ }
            throw new IllegalStateException("Refinement progress could not be displayed");
        }
    }
    public void attachTicket(PreviewWorkGate.Work work,OperationTicket value) {
        requireEdt();if(closed||active!=work)return;
        Objects.requireNonNull(value);
        if(ticket!=null&&!ticket.equals(value))throw new IllegalStateException("Refinement ticket differs from current work");
        ticket=value;
    }
    public void complete(PreviewWorkGate.Work work,boolean published,String safeMessage) {
        requireEdt();if(closed||active!=work)return;
        active=null;owner=null;ticket=null;
        try {
            if(!published&&safeMessage!=null&&!safeMessage.isBlank())showFailure.accept(safeMessage);
        } finally {
            try{hideProgress.run();}finally{showReview.run();}
        }
    }
    public void invalidate(String safeMessage) {
        requireEdt();if(closed)return;closed=true;active=null;owner=null;ticket=null;
        try{hideProgress.run();}finally{if(safeMessage!=null&&!safeMessage.isBlank())showFailure.accept(safeMessage);}
    }
    @Override public void close(){requireEdt();if(closed)return;closed=true;active=null;owner=null;ticket=null;hideProgress.run();}
    private static void requireEdt(){if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Review visibility requires EDT");}
}
