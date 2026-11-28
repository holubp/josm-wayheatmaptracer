package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Bundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15BundleWriter;

/** Stores exactly the latest slide diagnostics; stale producers cannot replace a newer attempt. */
public final class DiagnosticsRegistry {
    private static volatile LastSlideDebugBundle lastBundle;
    private static volatile Format15Bundle lastModernBundle;
    private static RefinementDiagnosticsMemory.Snapshot lastRefinement;
    private static RefinementOwner refinementOwner;
    private static WeakReference<RefinementProducer> refinementProducer=new WeakReference<>(null);
    private DiagnosticsRegistry() { }

    public static final class PluginOwner {private PluginOwner(){}}
    private static PluginOwner currentPlugin;
    public static PluginOwner beginPlugin() {
        RefinementDiagnosticsMemory.Snapshot retired;PluginOwner next=new PluginOwner();
        synchronized(DiagnosticsRegistry.class){retired=clearRefinement();lastBundle=null;lastModernBundle=null;currentPlugin=next;}
        if(retired!=null)retired.close();return next;
    }
    public static void clearPlugin(PluginOwner owner) {
        RefinementDiagnosticsMemory.Snapshot retired;
        synchronized(DiagnosticsRegistry.class){if(owner==null||owner!=currentPlugin)return;currentPlugin=null;retired=clearRefinement();lastBundle=null;lastModernBundle=null;}
        if(retired!=null)retired.close();
    }

    /** Live producer is weakly referenced; encoded terminal snapshots retain no UI/workflow/input objects. */
    public interface RefinementProducer {
        boolean isCurrent();
        void prepareExport();
    }
    /** Opaque registry epoch, independent of dataset/settings identity. */
    public static final class RefinementOwner {
        private boolean sealed;
        private RefinementUnavailable unavailable;
        private RefinementOwner() { }
    }
    public static RefinementOwner claimRefinement(RefinementProducer producer) {
        Objects.requireNonNull(producer);
        RefinementDiagnosticsMemory.Snapshot retired;
        RefinementOwner owner=new RefinementOwner();
        synchronized(DiagnosticsRegistry.class) {
            retired=lastRefinement;lastRefinement=null;
            lastBundle=null;lastModernBundle=null;
            refinementOwner=owner;refinementProducer=new WeakReference<>(producer);
        }
        if(retired!=null)retired.close();return owner;
    }
    public static synchronized boolean ownsRefinement(RefinementOwner owner) {
        return owner!=null&&owner==refinementOwner&&!owner.sealed;
    }
    /** Never calls owner/UI code or performs I/O while holding the registry monitor. */
    public static boolean publishRefinement(RefinementOwner owner,RefinementDiagnosticsMemory.Snapshot snapshot,boolean terminal) {
        Objects.requireNonNull(snapshot);
        RefinementProducer producer;
        synchronized(DiagnosticsRegistry.class){producer=owner==refinementOwner&&!owner.sealed?refinementProducer.get():null;}
        boolean current=producer!=null&&producer.isCurrent();
        RefinementDiagnosticsMemory.Snapshot retired=null;boolean accepted=false;
        synchronized(DiagnosticsRegistry.class) {
            if(current&&owner==refinementOwner&&!owner.sealed&&producer==refinementProducer.get()) {
                retired=lastRefinement;lastRefinement=snapshot;lastBundle=null;lastModernBundle=null;
                owner.sealed=terminal;if(terminal)refinementProducer.clear();accepted=true;
            }
        }
        if(retired!=null)retired.close();if(!accepted)snapshot.close();return accepted;
    }
    public enum RefinementUnavailable { BUDGET, BINDING, ENCODING }
    public static boolean sealRefinementUnavailable(RefinementOwner owner,RefinementUnavailable reason) {
        Objects.requireNonNull(reason);RefinementProducer producer;
        synchronized(DiagnosticsRegistry.class){producer=owner==refinementOwner&&!owner.sealed?refinementProducer.get():null;}
        boolean current=producer!=null&&producer.isCurrent();RefinementDiagnosticsMemory.Snapshot retired=null;boolean accepted=false;
        synchronized(DiagnosticsRegistry.class){if(current&&owner==refinementOwner&&!owner.sealed&&producer==refinementProducer.get()){
            retired=lastRefinement;lastRefinement=null;lastBundle=null;lastModernBundle=null;owner.sealed=true;owner.unavailable=reason;refinementProducer.clear();accepted=true;
        }}
        if(retired!=null)retired.close();return accepted;
    }
    private static String unavailableMessage(RefinementUnavailable reason){return switch(reason){case BUDGET->"Refinement diagnostics unavailable: memory budget reached";case BINDING->"Refinement diagnostics unavailable: retained binding could not be verified";case ENCODING->"Refinement diagnostics unavailable: archive encoding failed";};}

    /** Navigation/selection invalidates an older encoded view without creating a new native attempt. */
    public static void invalidateRefinement(RefinementOwner owner) {
        RefinementDiagnosticsMemory.Snapshot retired=null;
        synchronized(DiagnosticsRegistry.class){if(owner==refinementOwner&&!owner.sealed){retired=lastRefinement;lastRefinement=null;}}
        if(retired!=null)retired.close();
    }
    /** Replaces the export and retires any charged refinement lease, including on plugin unload. */
    public static void setLastBundle(LastSlideDebugBundle bundle) {
        RefinementDiagnosticsMemory.Snapshot retired;
        synchronized(DiagnosticsRegistry.class){retired=clearRefinement();lastBundle=bundle;lastModernBundle=null;}
        if(retired!=null)retired.close();
    }
    public static void setLastModernBundle(Format15Bundle bundle) {
        Objects.requireNonNull(bundle,"Modern diagnostic bundle is required");
        RefinementDiagnosticsMemory.Snapshot retired;
        synchronized(DiagnosticsRegistry.class){retired=clearRefinement();lastModernBundle=bundle;lastBundle=null;}
        if(retired!=null)retired.close();
    }
    private static RefinementDiagnosticsMemory.Snapshot clearRefinement() {
        var retired=lastRefinement;lastRefinement=null;refinementOwner=null;refinementProducer.clear();return retired;
    }
    public static synchronized boolean hasLatest() {
        return lastModernBundle!=null||lastBundle!=null||lastRefinement!=null||refinementProducer.get()!=null||refinementOwner!=null&&refinementOwner.unavailable!=null;
    }
    /** Prepares the live current selection, then pins encoded bytes across replacement during export. */
    public static File writeLatest(File file) throws Exception {
        RefinementProducer producer;
        synchronized(DiagnosticsRegistry.class){producer=refinementProducer.get();}
        if(producer!=null&&producer.isCurrent())producer.prepareExport();
        Format15Bundle modern;LastSlideDebugBundle legacy;RefinementDiagnosticsMemory.Reader reader;RefinementUnavailable unavailable;
        synchronized(DiagnosticsRegistry.class){modern=lastModernBundle;legacy=lastBundle;reader=lastRefinement==null?null:lastRefinement.pin();unavailable=refinementOwner==null?null:refinementOwner.unavailable;}
        if(reader!=null){try(reader){reader.writeTo(file.toPath());}return file;}
        if(modern!=null){Format15BundleWriter.write(modern,file.toPath());return file;}
        if(legacy!=null)return legacy.writeTo(file);
        throw new IllegalStateException(unavailable==null?"No alignment diagnostics are available":unavailableMessage(unavailable));
    }
    public static LastSlideDebugBundle getLastBundle(){return lastBundle;}
}
