package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.lang.reflect.*;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;

/** Fixed, source-bound access to unchanged private pipeline seams; no arbitrary reflective targets. */
final class BSingleTrainDiagnosticBridge {
    @FunctionalInterface interface Verification {void verify() throws Exception;}
    @FunctionalInterface interface Provider {Scene render(CaseDefinition definition) throws Exception;}
    private static Object invoke(Method method,Object receiver,Object... arguments) throws Exception {
        try {return method.invoke(receiver,arguments);}
        catch(InvocationTargetException wrapped) {
            if(wrapped.getCause() instanceof Exception failure)throw failure;
            if(wrapped.getCause() instanceof Error failure)throw failure;
            throw new AssertionError(wrapped.getCause());
        }
    }
    private static Class<?> nested(String name) throws Exception {
        return Class.forName(BObservationIdentificationTool.class.getName()+"$"+name);
    }
    private static Method method(Class<?> type,String name,Class<?>... arguments) throws Exception {
        var method=type.getDeclaredMethod(name,arguments);method.setAccessible(true);return method;
    }
    private static Object field(Object instance,String name) throws Exception {
        var field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);
    }
    @SuppressWarnings("unchecked")
    static Optional<List<MetricPoint>> clip(MetricPoint a,MetricPoint b) throws Exception {
        return (Optional<List<MetricPoint>>)invoke(method(BIdentificationAcceptance.class,"clip",
                MetricPoint.class,MetricPoint.class,double.class,double.class),null,a,b,40d,80d);
    }
    static void verifySignatures() throws Exception {
        method(BObservationIdentificationTool.class,"openScene",CaseDefinition.class,nested("InputIdentity"),
                nested("SourceGuard"),nested("InputProvider"),BObservationIdentificationTool.Controls.class,CancellationProbe.class);
        method(BIdentificationAcceptance.class,"clip",MetricPoint.class,MetricPoint.class,double.class,double.class);
        for(String name:List.of("ledger","owner","scene","profiles","facts","admitted","input"))
            nested("SceneInputs").getDeclaredField(name);
    }
    @SuppressWarnings("unchecked") static Map<String,Object> inputFields(BObservationIdentificationTool.TrainInputBinding input) throws Exception {
        return (Map<String,Object>)invoke(method(BObservationIdentificationTool.class,"inputFields",
                BObservationIdentificationTool.TrainInputBinding.class),null,input);
    }
    static Loaded open(CaseDefinition definition,String source,String renderer,String catalog,Verification verification,
            Provider provider,BObservationIdentificationTool.Controls controls) throws Exception {
        if(!definition.key().id().equals("sparse-crossing-noisy-strands-vs-persistent-parallel/train/v0/right")
                ||definition.key().split()!=Split.TRAIN)throw new SecurityException("Fixed original TRAIN case required");
        verification.verify();controls.cancellation().checkpoint();verifySignatures();
        var identityConstructor=nested("InputIdentity").getDeclaredConstructor(String.class,String.class,String.class);
        identityConstructor.setAccessible(true);Object identity=identityConstructor.newInstance(source,renderer,catalog);
        Object guard=Proxy.newProxyInstance(BSingleTrainDiagnosticBridge.class.getClassLoader(),new Class<?>[]{nested("SourceGuard")},
                (proxy,method,args)->{verification.verify();return null;});
        Object input=Proxy.newProxyInstance(BSingleTrainDiagnosticBridge.class.getClassLoader(),new Class<?>[]{nested("InputProvider")},
                (proxy,method,args)->{
                    if(!definition.equals(args[0]))throw new SecurityException("Fixed provider case changed");
                    verification.verify();return provider.render(definition);
                });
        Object loaded=invoke(method(BObservationIdentificationTool.class,"openScene",CaseDefinition.class,nested("InputIdentity"),
                nested("SourceGuard"),nested("InputProvider"),BObservationIdentificationTool.Controls.class,CancellationProbe.class),
                null,definition,identity,guard,input,controls,controls.cancellation());
        try {return new Loaded(loaded);}
        catch(Exception|Error failure){invoke(method(loaded.getClass(),"close"),loaded);throw failure;}
    }
    static final class Loaded implements AutoCloseable {
        private final Object original;
        final AttemptMemoryLedger ledger;final AttemptMemoryLedger.Owner owner;final Scene scene;
        final List<ProbabilisticProfile> profiles;final List<BIdentificationFacts.ProfileFacts> facts;
        final BObservationIdentificationTool.AdmittedLattices admitted;
        final BObservationIdentificationTool.TrainInputBinding input;
        @SuppressWarnings("unchecked") private Loaded(Object original) throws Exception {
            this.original=original;ledger=(AttemptMemoryLedger)field(original,"ledger");owner=(AttemptMemoryLedger.Owner)field(original,"owner");
            scene=(Scene)field(original,"scene");profiles=(List<ProbabilisticProfile>)field(original,"profiles");
            facts=(List<BIdentificationFacts.ProfileFacts>)field(original,"facts");
            admitted=(BObservationIdentificationTool.AdmittedLattices)field(original,"admitted");
            input=(BObservationIdentificationTool.TrainInputBinding)field(original,"input");
        }
        List<List<MetricPoint>> coordinates(CancellationProbe cancellation) {
            long totalCells=admitted.lattices().stream().mapToLong(l->l.cells().size()).sum();
            // Conservative retained list/point envelope, separately labelled in the receipt.
            owner.reserve(1024+profiles.size()*128L+totalCells*64L);
            var rows=new ArrayList<List<MetricPoint>>(profiles.size());
            for(int i=0;i<profiles.size();i++)try(var scratch=owner.child("coordinate-profile")) {
                cancellation.checkpoint();var original=profiles.get(i);var cells=admitted.lattices().get(i).cells();
                // Inert coordinate carrier only: never passed to inference or used as evidence/likelihood.
                var profile=new InferenceProfile(original.chainageMeters(),original.anchor(),original.normalUnit(),cells,
                        new double[cells.size()],List.of(),0,
                        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership.NO_RASTER,true);
                var row=new ArrayList<MetricPoint>(profile.cells().size());
                for(int state=0;state<profile.cells().size();state++){cancellation.checkpoint();row.add(profile.point(state));}
                rows.add(Collections.unmodifiableList(row));
            }
            return Collections.unmodifiableList(rows);
        }
        public void close() throws Exception {invoke(method(original.getClass(),"close"),original);}
    }
    private BSingleTrainDiagnosticBridge() { }
}
