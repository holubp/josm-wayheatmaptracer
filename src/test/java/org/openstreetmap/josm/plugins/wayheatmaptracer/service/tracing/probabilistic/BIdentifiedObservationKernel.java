package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import java.util.*;
import java.util.function.Supplier;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.ObservationStage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BObservationMixture.Outcome;

/** Joint core-excess-v1 empirical family; equations frozen before any fitting. */
final class BIdentifiedObservationKernel implements BObservationKernel {
    private static final String POLICY = "joint-core-excess-v1;facts-3;L0=1m;"
            + "c=original-RAW-center;h=raw-high-core-halfwidth+max-stage-disagreement+rho/2;"
            + "rho=native-pitch-or-physical-sampling-gap;RAW-B3-B5-correlated;"
            + "tau=physical-normalized-tent;e=max(0,I-own-background-lambda*max(RMS,filter));"
            + "A=G*(1+B*(left-span+right-span)/40m)*tau*e;H=integral(A);"
            + "qm=R*H/(1+sumH);fm=A/H;q0=R/(1+sumH);"
            + "frozen-censored;full-admitted-domain-uniform;invalid-measured-zero;"
            + "exact-anchor-unary-and-log-measure-zero;no-positive-connection-certificate";
    private static final Feature[] REQUIRED = {Feature.BACKGROUND,Feature.RMS_DESCRIPTOR,Feature.FILTER_DESCRIPTOR,
            Feature.RAW_CENTER,Feature.SHOULDER_MINIMUM,Feature.SHOULDER_MAXIMUM,
            Feature.LEFT_DIRECT_SPAN,Feature.RIGHT_DIRECT_SPAN};
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
                        outcomes[i]=component.groupedParent()?Outcome.GROUPED_PARENT_UNAVAILABLE
                                :selected==null?Outcome.UNAVAILABLE_ORIGINAL_RECEIPT
                                :fillJoint(rows[i],selected,profile,lattice,cancellation,scratch);
                        for(int s=0;s<states;s++) {cancellation.checkpoint();integrals[i]+=rows[i][s]*widths[s];}
                        if(!Double.isFinite(integrals[i])||integrals[i]<0)throw new IllegalArgumentException("Nonfinite joint integral");
                        if(integrals[i]==0&&outcomes[i]==Outcome.MEASURED)
                            throw new IllegalArgumentException("Positive joint field lost to numerical underflow");
                        measured+=integrals[i];
                        var normalized=BObservationMixture.normalizeMeasured(rows[i],widths,cancellation,scratch);
                        for(int s=0;s<states;s++) rows[i][s]=normalized.logL0DensityAt(s);
                    }
                }
            }
            if(missing<0||!Double.isFinite(measured)||!Double.isFinite(censored)||censored<0||censored>1)
                throw new IllegalArgumentException("Invalid component allocation");
            double remainder=1-censored, denominator=1+measured;
            masses[missing]=remainder/denominator;
            for(int i=0;i<count;i++)if(lattice.components().get(i).kind()==ObservationComponent.Kind.MEASURED)
                masses[i]=remainder*integrals[i]/denominator;
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
    private Outcome fillJoint(double[] output,ModeFacts facts,ProbabilisticProfile profile,
            ProbabilisticStateLattice lattice,CancellationProbe cancellation,AttemptMemoryLedger.Owner scratch) {
        for(var feature:REQUIRED)
            if(facts.fact(feature).availability()==Availability.UNAVAILABLE)return Outcome.UNAVAILABLE_ORIGINAL_RECEIPT;
        StageReceipt raw=null;boolean filtered=false;
        double center=value(facts,Feature.RAW_CENTER),disagreement=0;
        for(var receipt:facts.provenance()) if(receipt instanceof StageReceipt stage&&stage.original().available()) {
            if(stage.original().stage()==ObservationStage.RAW) {
                if(raw!=null)throw new IllegalArgumentException("Duplicate RAW receipt");raw=stage;
            } else filtered=true;
            disagreement=Math.max(disagreement,Math.abs(stage.original().centerOffsetMeters()-center));
        }
        if(raw==null||!filtered)return Outcome.UNAVAILABLE_ORIGINAL_RECEIPT;
        double left=value(facts,Feature.SHOULDER_MINIMUM),right=value(facts,Feature.SHOULDER_MAXIMUM);
        double rho=0;
        if(profile.nativePitchKnown()) {
            if(facts.fact(Feature.NATIVE_PITCH).availability()==Availability.UNAVAILABLE)return Outcome.UNAVAILABLE_SOURCE_PITCH;
            rho=value(facts,Feature.NATIVE_PITCH);
        } else {
            for(int i=1;i<profile.samples().size();i++) {
                cancellation.checkpoint();var a=profile.samples().get(i-1);var b=profile.samples().get(i);
                if(a.offsetMeters()>=left&&b.offsetMeters()<=right)rho=Math.max(rho,b.offsetMeters()-a.offsetMeters());
            }
        }
        double half=(raw.lastOffsetMeters()-raw.firstOffsetMeters())/2+disagreement+rho/2;
        if(!(rho>0)||!(half>0)||!Double.isFinite(half))return Outcome.UNAVAILABLE_SOURCE_PITCH;
        double tentIntegral=0;
        for(int s=0;s<output.length;s++) {
            cancellation.checkpoint();var cell=lattice.cells().get(s);
            output[s]=Math.max(0,1-Math.abs(cell.offsetMeters()-center)/half);
            tentIntegral+=output[s]*cell.quadratureWidthMeters();
        }
        if(!(tentIntegral>0)) {Arrays.fill(output,0);return Outcome.NO_ADMITTED_TENT_SUPPORT;}
        double nuisance=Math.max(value(facts,Feature.RMS_DESCRIPTOR),value(facts,Feature.FILTER_DESCRIPTOR));
        double direct=(value(facts,Feature.LEFT_DIRECT_SPAN)+value(facts,Feature.RIGHT_DIRECT_SPAN))/40;
        if(direct<0||direct>1||nuisance<0)throw new IllegalArgumentException("Invalid physical descriptor");
        double multiplier=parameters.gain()*(1+parameters.directSpanSlope()*direct);
        boolean positive=false;
        for(int s=0;s<output.length;s++) {
            cancellation.checkpoint();double u=lattice.cells().get(s).offsetMeters();
            if(output[s]==0||u<left||u>right) {output[s]=0;continue;}
            // Covers the original interpolation helper's short-lived key/comparator objects.
            var temporary=scratch==null?null:scratch.reserve(128);
            double intensity;
            try {intensity=ProbabilisticObservationModel.interpolate(profile.samples(),u);}
            finally {if(temporary!=null)temporary.close();}
            double excess=Double.isFinite(intensity)?Math.max(0,intensity-value(facts,Feature.BACKGROUND)
                    -parameters.nuisanceMultiplier()*nuisance):0;
            output[s]=multiplier*output[s]/tentIntegral*excess;
            positive|=output[s]>0;
        }
        return positive?Outcome.MEASURED:Outcome.OBSERVED_ZERO_EXCESS;
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
