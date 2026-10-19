package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.lang.reflect.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Fixed diagnostic access to the unchanged objective; never performs inference. */
final class BSingleTrainObjectiveAccounting {
    static final class SnapshotKernel implements BObservationKernel {
        final BObservationKernel delegate;final int[] states;final Counts counts;final AttemptMemoryLedger.Owner snapshotOwner;
        List<List<Map<String,Object>>> selected=List.of();
        SnapshotKernel(BObservationKernel delegate,int[] states,Counts counts,AttemptMemoryLedger.Owner owner){
            if(states.length!=2)throw new IllegalArgumentException("Exactly two fixed paths required");
            this.delegate=delegate;this.states=states.clone();this.counts=counts;this.snapshotOwner=Objects.requireNonNull(owner);
        }
        public String identityHash(){return delegate.identityHash();}
        public BObservationMixture evaluate(BIdentificationFacts.ProfileFacts facts,ProbabilisticStateLattice lattice,
                CancellationProbe cancellation,org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner owner) {
            cancellation.checkpoint();
            for(int state:states)if(state<0||state>=lattice.cells().size())throw new IllegalArgumentException("Selected state outside admitted row");
            if(lattice.components().size()>3||counts.components>972-2*lattice.components().size())
                throw new BObservationIdentificationTool.RunLimitException("COMPONENT_RECORDS");
            try(var transaction=snapshotOwner.child("selected-cell-copies")) {
                // Fixed <=6 scalar records, lists/maps/strings references and wrapper overhead.
                // Reserved before allocation; not an exact JVM-heap claim or an alias to scratch arrays.
                transaction.reserve(8192);
                var mixture=delegate.evaluate(facts,lattice,cancellation,owner);
                var paths=new ArrayList<List<Map<String,Object>>>();
                for(int state:states) {
                    var components=new ArrayList<Map<String,Object>>();
                    for(int c=0;c<mixture.componentCount();c++) {
                        cancellation.checkpoint();var component=mixture.component(c);double logf=component.logL0DensityAt(state);
                        components.add(Map.of("id",component.id(),"lineage",component.lineage(),"kind",component.kind().name(),
                                "outcome",component.outcome().name(),"q",component.mass(),"logL0Density",number(logf),
                                "L0Density",number(StrictMath.exp(logf))));counts.components++;
                    }
                    paths.add(List.copyOf(components));
                }
                selected=List.copyOf(paths);transaction.transferTo(snapshotOwner);
                return mixture; // Preserve identity, reducer, ordering and scratch ownership.
            }
        }
    }
    record Account(double energy,List<Map<String,Object>> rows) { }
    static final class Counts {
        int profiles,pairs,triples,calls,components,adapters,completedAccounts;
        final int maxProfiles,maxPairs,maxTriples,maxCalls;
        Counts(){this(324,320,316,4096);}
        Counts(int profiles,int pairs,int triples,int calls){maxProfiles=profiles;maxPairs=pairs;maxTriples=triples;maxCalls=calls;}
        void call(String kind) {
            if(calls>=maxCalls||kind.equals("profileEnergy")&&profiles>=maxProfiles
                    ||kind.equals("pairEnergy")&&pairs>=maxPairs||kind.equals("tripleEnergy")&&triples>=maxTriples)
                throw new BObservationIdentificationTool.RunLimitException("ACCOUNTING_WORK");
            calls++;switch(kind){case "profileEnergy"->profiles++;case "pairEnergy"->pairs++;case "tripleEnergy"->triples++;default->{}}
        }
        Map<String,Object> fields(){return Map.of("profileTerms",profiles,"pairTerms",pairs,"tripleTerms",triples,
                "primitiveCalls",calls,"selectedCellComponentRecords",components,"adapterEvaluations",adapters);}
    }
    private static Method method(String name,Class<?>... types) throws Exception {
        var method=ProbabilisticInference.class.getDeclaredMethod(name,types);method.setAccessible(true);return method;
    }
    static void verifySignatures() throws Exception {
        method("profileEnergy",List.class,int.class,int.class,EvidenceModelParameters.class);
        method("pairEnergy",List.class,int.class,int.class,int.class,EvidenceModelParameters.class);
        method("tripleEnergy",List.class,int.class,int.class,int.class,int.class,EvidenceModelParameters.class);
        method("profileQuadrature",List.class,int.class);
        method("orientationMismatch",InferenceProfile.class,int.class,double.class);
    }
    private static double invoke(String name,Class<?>[] types,Counts counts,CancellationProbe cancellation,Object... arguments) throws Exception {
        cancellation.checkpoint();counts.call(name);
        try{return (Double)method(name,types).invoke(null,arguments);}
        catch(InvocationTargetException wrapped){
            if(wrapped.getCause() instanceof Exception failure)throw failure;
            if(wrapped.getCause() instanceof Error failure)throw failure;
            throw new AssertionError(wrapped.getCause());
        }
    }
    static Object number(double value) {
        if(Double.isFinite(value))return value;
        return Double.isNaN(value)?"NONFINITE_NAN":value<0?"NEGATIVE_INFINITY":"POSITIVE_INFINITY";
    }
    static Account account(List<InferenceProfile> profiles,int[] states,EvidenceModelParameters parameters,
            CancellationProbe cancellation) throws Exception {
        return account(profiles,states,parameters,cancellation,new Counts());
    }
    static Account account(List<InferenceProfile> profiles,int[] states,EvidenceModelParameters parameters,
            CancellationProbe cancellation,Counts counts) throws Exception {
        if(profiles.size()<2||states.length!=profiles.size()||states.length>81)throw new IllegalArgumentException("Complete bounded path required");
        for(int i=0;i<states.length;i++)if(states[i]<0||states[i]>=profiles.get(i).cells().size())
            throw new IllegalArgumentException("State outside original admitted row");
        var rows=new ArrayList<Map<String,Object>>();double energy=0,first=0;
        for(int i=0;i<states.length;i++) {
            cancellation.checkpoint();var profile=profiles.get(i);int state=states[i];var cell=profile.cells().get(state);
            double p=invoke("profileEnergy",new Class<?>[]{List.class,int.class,int.class,EvidenceModelParameters.class},counts,cancellation,profiles,i,state,parameters);
            double quad=invoke("profileQuadrature",new Class<?>[]{List.class,int.class},counts,cancellation,profiles,i);
            var row=new LinkedHashMap<String,Object>();row.put("profile",i);row.put("state",state);row.put("point",Map.of("xMeters",profile.point(state).xMeters(),"yMeters",profile.point(state).yMeters()));
            row.put("branch",cell.branchLabel());row.put("cellWidthMeters",cell.quadratureWidthMeters());row.put("exactAnchor",cell.exactAnchor());
            row.put("unary",number(profile.unaryCost(state)));row.put("guideCost",number(profile.structuralGuideCost(state)));
            row.put("longitudinalQuadratureMeters",quad);row.put("profileTerm",number(p));
            row.put("dataPieceSeparatelyRounded",number(quad*parameters.dataWeight()*profile.unaryCost(state)));
            row.put("guidePieceSeparatelyRounded",number(quad*parameters.guideWeight()*profile.structuralGuideCost(state)));
            var support=profile.orientationSupport(state);row.put("orientationStatus",support.status().name());
            row.put("orientationCertainty",support.certainty());row.put("orientationReliability",profile.orientationReliability(state));
            row.put("orientationModes",support.modes().stream().map(m->Map.of("startRadians",m.startRadians(),"endRadians",m.endRadians(),"peakRadians",m.peakBearingRadians(),"response",m.response())).toList());
            double a=0,t=0;
            if(i>0) {
                a=invoke("pairEnergy",new Class<?>[]{List.class,int.class,int.class,int.class,EvidenceModelParameters.class},counts,cancellation,profiles,i-1,states[i-1],state,parameters);
                var prev=profiles.get(i-1).point(states[i-1]);var current=profile.point(state);
                double heading=StrictMath.atan2(current.yMeters()-prev.yMeters(),current.xMeters()-prev.xMeters());
                row.put("arrivalHeadingRadians",heading);
                row.put("departureMismatch",number(invoke("orientationMismatch",new Class<?>[]{InferenceProfile.class,int.class,double.class},counts,cancellation,profiles.get(i-1),states[i-1],heading)));
                row.put("arrivalMismatch",number(invoke("orientationMismatch",new Class<?>[]{InferenceProfile.class,int.class,double.class},counts,cancellation,profile,state,heading)));
            }
            if(i>=2)t=invoke("tripleEnergy",new Class<?>[]{List.class,int.class,int.class,int.class,int.class,EvidenceModelParameters.class},counts,cancellation,profiles,i,states[i-2],states[i-1],state,parameters);
            if(i==0){first=p;row.put("prefixEnergy","UNAVAILABLE_INITIAL_PAIR_NOT_COMPLETE");}
            else {double increment=i==1?(first+p)+a:(p+a)+t;energy=i==1?increment:energy+increment;
                row.put("groupedIncrement",number(increment));row.put("prefixEnergy",number(energy));}
            row.put("pairTerm",i>0?number(a):"UNAVAILABLE_NO_PREDECESSOR");row.put("tripleTerm",i>=2?number(t):"UNAVAILABLE_NO_TRIPLE");
            rows.add(Collections.unmodifiableMap(row));
        }
        var result=new Account(energy,List.copyOf(rows));counts.completedAccounts++;return result;
    }
    private BSingleTrainObjectiveAccounting() { }
}
