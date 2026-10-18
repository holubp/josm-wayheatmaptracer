package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import java.util.*;
import java.util.function.Supplier;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.ObservationStage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BObservationMixture.Outcome;

/** Area-presence/core-position-v2 empirical family; equations frozen before any fitting. */
final class BIdentifiedObservationKernel implements BObservationKernel {
    private static final String POLICY = "area-presence-core-position-v2;facts-3;L0=1m;"
            + "c=original-RAW-center;h=raw-high-core-halfwidth+max-stage-disagreement+rho/2;"
            + "rho=native-pitch-or-physical-sampling-gap;RAW-B3-B5-correlated;"
            + "e=max(0,I-own-background-lambda*max(RMS,filter));E=integral-own-shoulder-excess-area;"
            + "Z=integral-valid-core-tent;f=valid-core-tent/Z;H=G*(1+B*(left-span+right-span)/40m)*E;"
            + "qm=R*H/(1+sumH);q0=R/(1+sumH);"
            + "frozen-censored;full-admitted-domain-uniform;invalid-measured-zero;"
            + "exact-anchor-unary-and-log-measure-zero;no-positive-connection-certificate";
    private static final Feature[] REQUIRED = {Feature.BACKGROUND,Feature.RMS_DESCRIPTOR,Feature.FILTER_DESCRIPTOR,
            Feature.RAW_CENTER,Feature.SHOULDER_MINIMUM,Feature.SHOULDER_MAXIMUM,
            Feature.LEFT_DIRECT_SPAN,Feature.RIGHT_DIRECT_SPAN};
    /** Shared calculation receipt for inference and the bounded TRAIN mechanism report. */
    record ModeCalculation(double excessArea, double tentNormalizer, double presenceMass,
            int availableShoulderCells, Outcome outcome) { }
    record Parameters(double gain, double nuisanceMultiplier, double directSpanSlope) {
        Parameters {
            if (!(gain==1||gain==4||gain==16||gain==64) || !(nuisanceMultiplier==1||nuisanceMultiplier==2)
                    || !(directSpanSlope==0||directSpanSlope==1||directSpanSlope==3||directSpanSlope==7))
                throw new IllegalArgumentException("Unregistered parameter configuration");
        }
    }
    private final Parameters parameters;
    BIdentifiedObservationKernel(Parameters parameters) { this.parameters = Objects.requireNonNull(parameters); }
    public String identityHash() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((POLICY+"\n"+VERSION+"\n"+parameters)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    public BObservationMixture evaluate(BIdentificationFacts.ProfileFacts facts,
            ProbabilisticStateLattice lattice, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        Objects.requireNonNull(facts);Objects.requireNonNull(lattice);Objects.requireNonNull(cancellation).checkpoint();
        if (!VERSION.equals(facts.featureVersion())) throw new IllegalArgumentException("Feature version mismatch");
        var profile=facts.baseline();
        int count=lattice.components().size(), states=lattice.cells().size();
        if (Math.multiplyExact((long)count,states+profile.samples().size()+facts.modes().size())>8_000_000L)
            throw new IllegalArgumentException("Kernel work limit");
        var transaction=owner==null?null:owner.child("joint-observation");
        var scratch=transaction==null?null:transaction.child("joint-scratch");
        try {
            double[] widths=array(states,scratch), integrals=array(count,scratch), masses=array(count,scratch);
            Outcome[] outcomes=allocate(scratch,AttemptMemoryLedger.referenceArrayBytes(count),()->new Outcome[count]);
            double[][] rows=allocate(scratch,ProbabilisticInference.deepArrayBytes(count,states,Double.BYTES),
                    ()->new double[count][states]);
            double totalWidth=0;
            for(int i=0;i<states;i++) {cancellation.checkpoint();widths[i]=lattice.cells().get(i).quadratureWidthMeters();totalWidth+=widths[i];}
            // Original effective priors are consulted solely for frozen censored masses.
            double[] original=allocate(scratch,AttemptMemoryLedger.arrayBytes(count,Double.BYTES),
                    ()->ProbabilisticObservationModel.effectiveComponentPriors(profile,lattice.components()));
            double censored=0, measured=0;
            int missing=-1;
            for(int i=0;i<count;i++) {
                cancellation.checkpoint();var component=lattice.components().get(i);
                switch(component.kind()) {
                    case MISSING -> {if(missing>=0)throw new IllegalArgumentException("Duplicate uniform");missing=i;Arrays.fill(rows[i],-StrictMath.log(totalWidth));outcomes[i]=Outcome.BASELINE_MISSING;}
                    case CENSORED -> {
                        masses[i]=original[i];censored+=masses[i];
                        outcomes[i]=Outcome.BASELINE_CENSORED;
                        ProbabilisticObservationModel.fillBaselineCensoredRow(rows[i],profile,lattice.cells(),component,cancellation);
                    }
                    case MEASURED -> {
                        ModeFacts selected=null;
                        for(var mode:facts.modes()) if(mode.lineage().equals(component.evidenceLineage())) {
                            if(selected!=null&&!selected.equals(mode))throw new IllegalArgumentException("Inconsistent aliased facts");
                            selected=mode;
                        }
                        ModeCalculation calculation=component.groupedParent()?calculation(
                                0,0,0,0,Outcome.GROUPED_PARENT_UNAVAILABLE,scratch)
                                :selected==null?calculation(0,0,0,0,Outcome.UNAVAILABLE_ORIGINAL_RECEIPT,scratch)
                                :calculateMode(rows[i],widths,selected,profile,lattice,parameters,cancellation,scratch);
                        outcomes[i]=calculation.outcome();
                        integrals[i]=calculation.presenceMass();
                        if(!Double.isFinite(integrals[i])||integrals[i]<0)
                            throw new IllegalArgumentException("Nonfinite measured presence mass");
                        double nextMeasured=measured+integrals[i];
                        if(!Double.isFinite(nextMeasured))throw new IllegalArgumentException("Measured mass overflow");
                        measured=nextMeasured;
                        if(outcomes[i]==Outcome.MEASURED) {
                            if(!(integrals[i]>0))throw new IllegalArgumentException("Positive measured area lost to numerical underflow");
                        } else Arrays.fill(rows[i],Double.NEGATIVE_INFINITY);
                    }
                }
            }
            if(missing<0||!Double.isFinite(measured)||!Double.isFinite(censored)||censored<0||censored>1)
                throw new IllegalArgumentException("Invalid component allocation");
            double remainder=1-censored, denominator=1+measured;
            masses[missing]=remainder/denominator;
            if(remainder>0&&masses[missing]==0)
                throw new IllegalArgumentException("Positive missing mass lost to numerical underflow");
            for(int i=0;i<count;i++)if(lattice.components().get(i).kind()==ObservationComponent.Kind.MEASURED) {
                masses[i]=remainder*integrals[i]/denominator;
                if(remainder>0&&integrals[i]>0&&masses[i]==0)
                    throw new IllegalArgumentException("Positive measured mass lost to numerical underflow");
            }
            var components=allocate(scratch,ProbabilisticInference.listBytes(count),
                    ()->new ArrayList<BObservationMixture.Component>(count));
            for(int i=0;i<count;i++) {
                cancellation.checkpoint();var component=lattice.components().get(i);
                components.add(BObservationMixture.Component.create(component.id(),component.evidenceLineage(),
                        component.kind(),masses[i],rows[i],outcomes[i],transaction));
            }
            var result=BObservationMixture.create(components,transaction);
            cancellation.checkpoint();
            if(scratch!=null)scratch.close();
            if(transaction!=null)transaction.transferTo(owner);
            return result;
        } finally {if(transaction!=null)transaction.close();}
    }
    static ModeCalculation calculateMode(double[] output,double[] widths,ModeFacts facts,ProbabilisticProfile profile,
            ProbabilisticStateLattice lattice,Parameters parameters,CancellationProbe cancellation,
            AttemptMemoryLedger.Owner scratch) {
        Objects.requireNonNull(output);Objects.requireNonNull(widths);Objects.requireNonNull(facts);
        Objects.requireNonNull(profile);Objects.requireNonNull(lattice);Objects.requireNonNull(parameters);
        Objects.requireNonNull(cancellation).checkpoint();
        if(output.length!=lattice.cells().size()||widths.length!=output.length)
            throw new IllegalArgumentException("Mode calculation and lattice dimensions differ");
        for(int index=0;index<widths.length;index++) {
            cancellation.checkpoint();
            double cellWidth=lattice.cells().get(index).quadratureWidthMeters();
            if(!Double.isFinite(widths[index])||widths[index]<=0
                    ||Double.doubleToLongBits(widths[index])!=Double.doubleToLongBits(cellWidth))
                throw new IllegalArgumentException("Mode widths differ from the bound lattice");
        }
        for(var feature:REQUIRED)
            if(facts.fact(feature).availability()==Availability.UNAVAILABLE)
                return calculation(0,0,0,0,Outcome.UNAVAILABLE_ORIGINAL_RECEIPT,scratch);
        StageReceipt raw=null;boolean filtered=false;
        double center=value(facts,Feature.RAW_CENTER),disagreement=0;
        for(var receipt:facts.provenance()) if(receipt instanceof StageReceipt stage&&stage.original().available()) {
            if(stage.original().stage()==ObservationStage.RAW) {
                if(raw!=null)throw new IllegalArgumentException("Duplicate RAW receipt");raw=stage;
            } else filtered=true;
            disagreement=Math.max(disagreement,Math.abs(stage.original().centerOffsetMeters()-center));
        }
        if(raw==null||!filtered)return calculation(0,0,0,0,Outcome.UNAVAILABLE_ORIGINAL_RECEIPT,scratch);
        double left=value(facts,Feature.SHOULDER_MINIMUM),right=value(facts,Feature.SHOULDER_MAXIMUM);
        double rho=0;
        if(profile.nativePitchKnown()) {
            if(facts.fact(Feature.NATIVE_PITCH).availability()==Availability.UNAVAILABLE)
                return calculation(0,0,0,0,Outcome.UNAVAILABLE_SOURCE_PITCH,scratch);
            rho=value(facts,Feature.NATIVE_PITCH);
        } else {
            for(int i=1;i<profile.samples().size();i++) {
                cancellation.checkpoint();var a=profile.samples().get(i-1);var b=profile.samples().get(i);
                if(a.offsetMeters()>=left&&b.offsetMeters()<=right)rho=Math.max(rho,b.offsetMeters()-a.offsetMeters());
            }
        }
        double half=(raw.lastOffsetMeters()-raw.firstOffsetMeters())/2+disagreement+rho/2;
        if(!(rho>0)||!(half>0)||!Double.isFinite(half))
            return calculation(0,0,0,0,Outcome.UNAVAILABLE_SOURCE_PITCH,scratch);
        double tentIntegral=0, excessArea=0;
        int availableShoulderCells=0;
        double background=value(facts,Feature.BACKGROUND);
        double nuisance=Math.max(value(facts,Feature.RMS_DESCRIPTOR),value(facts,Feature.FILTER_DESCRIPTOR));
        double direct=(value(facts,Feature.LEFT_DIRECT_SPAN)+value(facts,Feature.RIGHT_DIRECT_SPAN))/40;
        double nuisanceThreshold=parameters.nuisanceMultiplier()*nuisance;
        if(direct<0||direct>1||nuisance<0||!Double.isFinite(nuisance)
                ||!Double.isFinite(background)||!Double.isFinite(nuisanceThreshold))
            throw new IllegalArgumentException("Invalid physical descriptor");
        for(int s=0;s<output.length;s++) {
            cancellation.checkpoint();var cell=lattice.cells().get(s);
            double u=cell.offsetMeters();
            output[s]=0;
            if(u<left||u>right)continue;
            // Covers the original interpolation helper's short-lived key/comparator objects.
            var temporary=scratch==null?null:scratch.reserve(128);
            double intensity;
            try {intensity=ProbabilisticObservationModel.interpolate(profile.samples(),u);}
            finally {if(temporary!=null)temporary.close();}
            if(!Double.isFinite(intensity))continue;
            availableShoulderCells++;
            double tent=Math.max(0,1-Math.abs(u-center)/half);
            double tentContribution=tent*cell.quadratureWidthMeters();
            if(!Double.isFinite(tentContribution)||tentContribution<0||tent>0&&tentContribution==0)
                throw new IllegalArgumentException("Positive tent contribution lost to numerical underflow");
            double nextTentIntegral=tentIntegral+tentContribution;
            if(!Double.isFinite(nextTentIntegral))throw new IllegalArgumentException("Tent normalizer overflow");
            tentIntegral=nextTentIntegral;
            output[s]=tent;
            double rawExcess=intensity-background-nuisanceThreshold;
            if(!Double.isFinite(rawExcess))throw new IllegalArgumentException("Nonfinite measured excess");
            double excess=Math.max(0,rawExcess);
            if(excess>0) {
                double areaContribution=excess*(cell.quadratureWidthMeters()/BObservationMixture.REFERENCE_LENGTH_METERS);
                if(areaContribution==0||!Double.isFinite(areaContribution))
                    throw new IllegalArgumentException("Positive shoulder area lost to numerical underflow or overflow");
                double nextArea=excessArea+areaContribution;
                if(!Double.isFinite(nextArea))throw new IllegalArgumentException("Shoulder area overflow");
                excessArea=nextArea;
            }
        }
        if(availableShoulderCells==0)
            return calculation(excessArea,tentIntegral,0,0,Outcome.UNAVAILABLE_ADMITTED_SHOULDER_SUPPORT,scratch);
        if(!(tentIntegral>0))
            return calculation(excessArea,tentIntegral,0,availableShoulderCells,Outcome.NO_ADMITTED_TENT_SUPPORT,scratch);
        if(!(excessArea>0))
            return calculation(excessArea,tentIntegral,0,availableShoulderCells,Outcome.OBSERVED_ZERO_EXCESS,scratch);
        double presence=parameters.gain()*(1+parameters.directSpanSlope()*direct)*excessArea;
        if(!Double.isFinite(presence)||!(presence>0))
            throw new IllegalArgumentException("Measured presence mass is nonfinite or lost to underflow");
        var normalized=BObservationMixture.normalizeMeasured(output,widths,cancellation,scratch);
        if(!normalized.hasSupport())throw new IllegalArgumentException("Positive area has no tent support");
        for(int s=0;s<output.length;s++)output[s]=normalized.logL0DensityAt(s);
        return calculation(excessArea,tentIntegral,presence,availableShoulderCells,Outcome.MEASURED,scratch);
    }
    private static ModeCalculation calculation(double excessArea,double tentNormalizer,double presenceMass,
            int availableShoulderCells,Outcome outcome,AttemptMemoryLedger.Owner owner) {
        return allocate(owner,AttemptMemoryLedger.objectBytes(5L*Long.BYTES),
                ()->new ModeCalculation(excessArea,tentNormalizer,presenceMass,availableShoulderCells,outcome));
    }
    private static double value(ModeFacts facts,Feature feature){return facts.fact(feature).value().orElseThrow();}
    private static double[] array(int count,AttemptMemoryLedger.Owner owner) {
        return allocate(owner,AttemptMemoryLedger.arrayBytes(count,Double.BYTES),()->new double[count]);
    }
    private static <T> T allocate(AttemptMemoryLedger.Owner owner,long bytes,Supplier<T> allocation) {
        if(owner==null)return allocation.get();
        try(var reservation=owner.reserve(bytes)){T result=allocation.get();reservation.adopt(result);return result;}
    }
}
