package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import java.util.Objects;

/** Fixed synthetic production target; never executed by the UNIT handoff. */
final class BSharedRuntimeInitializer {
    private BSharedRuntimeInitializer() { }
    @FunctionalInterface interface WorkBody<T> {T run(BPhysicalWork work);}
    /** DATA mechanics only. The body receives no runtime or capture permission. */
    static <T> T withPhysicalWork(CancellationProbe probe,WorkBody<T> phases) {
        return ownPhysicalWork(null,probe,phases);
    }
    /** Future admitted branch: initializer and ALL owned phases share one Work. */
    static <T> T initializeAndRun(BSharedRuntimeBootstrap.InitializationPermit permit,
            CancellationProbe probe,WorkBody<T> phases) {
        if(permit==null)throw new IllegalArgumentException("No trusted initialization permit");
        permit.require();
        throw new UnsupportedOperationException("Runtime requires an owned deadline context");
    }
    /** Future GNU branch:only a genuine permit AND non-UNIT context may enter. */
    static <T> T initializeOwnedAndRun(BSharedRuntimeBootstrap.InitializationPermit permit,
            BSharedOwnedCaptureProbe.Context context,WorkBody<T> phases){
        if(permit==null)throw new IllegalArgumentException("No trusted initialization permit");
        permit.require();context.requireRuntime(permit);
        return ownOwnedPhysicalWork(permit,context,phases);
    }
    /** Fixed callers own the returned live Work through their complete pipeline.
     * No caller phase/clock is invoked and no UNIT context grants admission.
     * Rejection before createOwnedRuntime leaves the borrowed context untouched. */
    static BPhysicalWork openOwnedWork(BSharedRuntimeBootstrap.InitializationPermit permit,
            BSharedOwnedCaptureProbe.Context context){
        if(permit==null)throw new IllegalArgumentException("No trusted initialization permit");
        permit.require();context.requireRuntime(permit);
        BPhysicalWork work=BPhysicalWork.createOwnedRuntime(permit,context);
        try{
            work.probe().checkpoint();work.live();
            initialize(permit,work);
            work.live();work.probe().checkpoint();work.live();
            return work;
        }catch(RuntimeException|Error primary){
            try{work.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
            throw primary;
        }
    }
    /** Actual same-root mechanics only;arbitrary UNIT callbacks grant no authority. */
    static <T> T withOwnedPhysicalWorkUnit(BSharedOwnedCaptureProbe.Context context,WorkBody<T> phases){
        return ownOwnedPhysicalWork(null,context,phases);
    }
    private static <T> T ownOwnedPhysicalWork(BSharedRuntimeBootstrap.InitializationPermit permit,
            BSharedOwnedCaptureProbe.Context context,WorkBody<T> phases){
        Objects.requireNonNull(phases,"phases");
        BPhysicalWork work=permit==null?BPhysicalWork.createOwnedUnit(context):BPhysicalWork.createOwnedRuntime(permit,context);Throwable primary=null;
        try{
            work.probe().checkpoint();work.live();
            if(permit!=null)initialize(permit,work);
            T value=phases.run(work);
            work.live();work.probe().checkpoint();work.live();return value;
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{try{work.close();}catch(RuntimeException|Error secondary){if(primary==null)throw secondary;}}
    }
    private static <T> T ownPhysicalWork(BSharedRuntimeBootstrap.InitializationPermit permit,
            CancellationProbe probe,WorkBody<T> phases) {
        Objects.requireNonNull(probe,"probe");Objects.requireNonNull(phases,"phases");
        //The original factory creates the ONLY ledger/root, then reserves the
        //1MiB holder and3560B slice BEFORE constructing its physical arrays.
        BPhysicalWork work=BPhysicalWork.create(probe);
        Throwable primary=null;
        try {
            probe.checkpoint();work.live();
            if(permit!=null)initialize(permit,work);
            T value=phases.run(work);
            work.live();probe.checkpoint();work.live();
            return value;
        } catch(RuntimeException|Error failure) {primary=failure;throw failure;}
        finally {
            try {work.close();}
            catch(RuntimeException|Error secondary) {if(primary==null)throw secondary;}
        }
    }
    private static String initialize(BSharedRuntimeBootstrap.InitializationPermit permit,BPhysicalWork work) {
        permit.require();work.live();
        var origin=new GeographicPoint(0,0);
        var frame=LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(-.001,-.001),new GeographicPoint(.001,.001));
        var transform=new SupportedInputRasterTransform.LocalMetricAffine(frame,new RasterPoint(0,0),1,0,0,1);
        //No replacement encoder: this exact original method resolves the
        //reviewed ten-String callsite once the genuine startup premises exist.
        return transform.parameterIdentity();
    }
}
