package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Arrays;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BObservationMixture.Outcome;

/** Corrected physical own-response family; arithmetic fixtures confer no source authority. */
final class BSharedPositionKernel implements BObservationKernel {
    //Reviewed baseline API frame816:sequential filter/min and primitive-max
    //paths in unchanged effective priors/censored normalization. Both native
    //calls occur with this slice live;it is not C-times retained payload.
    static final long NATIVE_ROW_API_FRAME=816;
    static final class Parameters {
        private final int gain;
        Parameters(int gain){this(gain,false);}
        private Parameters(int gain,boolean diagnostic) {
            if(!(gain==1||gain==4||gain==16||gain==64||diagnostic&&gain==0))
                throw new IllegalArgumentException("Unregistered shared-position gain");
            this.gain=gain;
        }
        int gain(){return gain;}
        boolean candidateEligible(){return gain!=0;}
        static Parameters diagnosticZeroGain(){return new Parameters(0,true);}
    }
    static final class Field implements AutoCloseable {
        private final BPhysicalResponse.OwnAccess own;
        private final BPhysicalWork work;
        private boolean closed,busy;
        private Field(BPhysicalResponse.OwnAccess own,BPhysicalWork work){this.own=own;this.work=work;}
        static Field open(BPhysicalResponse.OwnAccess own,BPhysicalWork work){
            Objects.requireNonNull(own).requireWork(Objects.requireNonNull(work));
            own.prepayKernel(0,0,0,0);work.sourceWork().metadata(40); //2 refs+2flags,O18=40
            return new Field(own,work);
        }
        private void enter(){if(closed||busy)throw new IllegalStateException("Closed or reentrant kernel field");own.requireWork(work);busy=true;}
        public void close(){closed=true;own.close();}
    }
    private final Field field;
    private final Parameters parameters;
    private BSharedPositionKernel(Field field,Parameters parameters,BPhysicalWork work){this.field=field;this.parameters=parameters;}
    static BSharedPositionKernel create(Field field,Parameters parameters,BPhysicalWork work){
        fund(field,work);
        return new BSharedPositionKernel(field,Objects.requireNonNull(parameters),work);
    }
    static BSharedPositionKernel createForTable(Field field,int gain,BPhysicalWork work){
        fund(field,work); //Includes config O4 BEFORE the table creates it.
        return new BSharedPositionKernel(field,new Parameters(gain),work);
    }
    private static void fund(Field field,BPhysicalWork work){
        Objects.requireNonNull(field).own.requireWork(work);
        if(field.work!=work)throw new IllegalArgumentException("Foreign physical work");
        field.own.prepayKernel(0,0,0,0);
        //Kernel O16,config O4,and String field upper24 +64 UTF16 units.
        work.sourceWork().metadata(32+24+AttemptMemoryLedger.objectBytes(24)+AttemptMemoryLedger.arrayBytes(64,2));
    }
    record Evaluation(BObservationMixture mixture,Calculation calculation) { }
    static final class Calculation {
        private final double[] areas,intensities,masses;
        private final double[][] logRows;
        private final BPhysicalResponse.Reason[][] reasons;
        private final AttemptMemoryLedger.Owner owner;
        private final double missing;
        private Calculation(double[] areas,double[] intensities,double[] masses,double[][] logRows,
                BPhysicalResponse.Reason[][] reasons,double missing,AttemptMemoryLedger.Owner owner) {
            this.areas=areas;this.intensities=intensities;this.masses=masses;this.logRows=logRows;
            this.reasons=reasons;this.missing=missing;this.owner=owner;
        }
        private void live(){if(owner.isClosed())throw new IllegalStateException("Calculation owner closed");}
        double area(int mode){live();return areas[mode];}
        double intensity(int mode){live();return intensities[mode];}
        double mass(int mode){live();return masses[mode];}
        double missingMass(){live();return missing;}
        double logDensity(int mode,int state){live();return logRows[mode][state];}
        BPhysicalResponse.Reason reason(int mode,int state){live();return reasons[mode][state];}
    }
    /** Arithmetic-only receipt; never grants a source/component capability. */
    static Calculation calculateMeasuredRows(double[][] response,BPhysicalResponse.Reason[][] reasons,
            double[] widths,int gain,double censoredMass,AttemptMemoryLedger.Owner owner) {
        return calculateMeasuredRows(response,reasons,widths,gain,censoredMass,owner,owner,null);
    }
    private static Calculation calculateMeasuredRows(double[][] response,BPhysicalResponse.Reason[][] reasons,
            double[] widths,int gain,double censoredMass,AttemptMemoryLedger.Owner owner,
            AttemptMemoryLedger.Owner lifetime,BPhysicalWork work) {
        Objects.requireNonNull(owner);Objects.requireNonNull(response);Objects.requireNonNull(reasons);
        if(!(gain==0||gain==1||gain==4||gain==16||gain==64)||response.length!=reasons.length
                ||!Double.isFinite(censoredMass)||censoredMass<0||censoredMass>1||widths.length==0)
            throw new IllegalArgumentException("Measured calculation domain");
        int c=response.length,s=widths.length;
        if(Math.multiplyExact((long)c,s)>8_000_000)throw new IllegalArgumentException("Local row limit");
        double width=0;
        for(double w:widths){if(work!=null)work.poll();if(!Double.isFinite(w)||w<=0)throw new IllegalArgumentException("Cell width");width=finiteSum(width,w);}
        var child=owner.child("physical-kernel-calculation");
        try {
            //6 refs+double=56 field bytes; both outer arrays and all rows copied.
            long bytes=AttemptMemoryLedger.objectBytes(56)+3*AttemptMemoryLedger.arrayBytes(c,8)
                +2*AttemptMemoryLedger.referenceArrayBytes(c)
                +Math.multiplyExact(c,AttemptMemoryLedger.arrayBytes(s,8)+AttemptMemoryLedger.referenceArrayBytes(s));
            var reservation=child.reserve(bytes);
            double[] a=new double[c],h=new double[c],q=new double[c];
            double[][] logs=new double[c][s];var copied=new BPhysicalResponse.Reason[c][s];
            double sum=0;
            for(int m=0;m<c;m++) {
                if(work!=null)work.poll();
                if(response[m].length!=s||reasons[m].length!=s)throw new IllegalArgumentException("Row shape");
                for(int i=0;i<s;i++) {
                    if(work!=null)work.poll();
                    var reason=Objects.requireNonNull(reasons[m][i]);copied[m][i]=reason;
                    if(reason==BPhysicalResponse.Reason.UNRESOLVED)throw new ArithmeticException("Uncertified physical response");
                    boolean known=reason==BPhysicalResponse.Reason.AVAILABLE||reason==BPhysicalResponse.Reason.OBSERVED_ZERO
                        ||reason==BPhysicalResponse.Reason.GEOMETRIC_ZERO;
                    double r=response[m][i];
                    if(known&&(!Double.isFinite(r)||r<0||reason!=BPhysicalResponse.Reason.AVAILABLE&&r!=0))
                        throw new IllegalArgumentException("Physical response/reason mismatch");
                    if(known) {a[m]=finiteSum(a[m],positiveProduct(r,widths[i]));logs[m][i]=r;}
                }
                h[m]=positiveProduct(gain,a[m]);sum=finiteSum(sum,h[m]);
                if(a[m]>0)for(int i=0;i<s;i++){if(work!=null)work.poll();logs[m][i]=logs[m][i]>0?StrictMath.log(logs[m][i])-StrictMath.log(a[m]):Double.NEGATIVE_INFINITY;}
                else java.util.Arrays.fill(logs[m],Double.NEGATIVE_INFINITY);
            }
            double remainder=1-censoredMass,denominator=finiteSum(1,sum);
            double missing=positiveQuotient(remainder,denominator);
            for(int m=0;m<c;m++){if(work!=null)work.poll();q[m]=positiveQuotient(positiveProduct(remainder,h[m]),denominator);}
            var result=new Calculation(a,h,q,logs,copied,missing,lifetime);
            reservation.adopt(result); //transferTo moves leases,not active reservations.
            child.transferTo(owner);return result;
        } finally {child.close();}
    }
    private static double finiteSum(double a,double b){double v=a+b;if(!Double.isFinite(v))throw new ArithmeticException("Kernel sum overflow");return v;}
    private static double positiveProduct(double a,double b){double v=a*b;if(!Double.isFinite(v)||a>0&&b>0&&v==0)throw new ArithmeticException("Kernel product underflow/overflow");return v;}
    private static double positiveQuotient(double a,double b){double v=a/b;if(!Double.isFinite(v)||a>0&&v==0)throw new ArithmeticException("Kernel quotient underflow/overflow");return v;}
    public BObservationMixture evaluate(BIdentificationFacts.ProfileFacts facts,ProbabilisticStateLattice lattice,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        return evaluateWithReceipt(facts,lattice,cancellation,owner).mixture();
    }
    Evaluation evaluateWithReceipt(BIdentificationFacts.ProfileFacts facts,ProbabilisticStateLattice lattice,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        field.enter();
        boolean began=false,normal=false;Throwable primary=null;
        try{Objects.requireNonNull(facts);Objects.requireNonNull(lattice);Objects.requireNonNull(owner);
            field.own.prepayKernel(lattice.components().size(),lattice.cells().size(),facts.baseline().samples().size(),facts.modes().size());
            field.own.begin();began=true;var result=evaluateBody(facts,lattice,cancellation,owner);normal=true;return result;}
        catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{try{if(began)try{field.own.end(normal);}catch(RuntimeException|Error secondary){if(primary==null)throw secondary;}}
            finally{field.busy=false;}}
    }
    private Evaluation evaluateBody(BIdentificationFacts.ProfileFacts facts,ProbabilisticStateLattice lattice,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        int c=lattice.components().size(),s=lattice.cells().size();
        int row=field.own.originalRow(facts,lattice,cancellation);
        var transaction=owner.child("shared-physical-observation");
        var scratch=transaction.child("shared-physical-local-row");
        try {
            //This same-ledger lease is checked before row allocation;identity
            //equality above binds the original facts and complete lattice.
            scratch.retainIfAbsent(facts);
            field.work.poll();field.own.requireWork(field.work);
            long bytes=AttemptMemoryLedger.arrayBytes(s,8)+2*AttemptMemoryLedger.referenceArrayBytes(c)
                +Math.multiplyExact(c,AttemptMemoryLedger.arrayBytes(s,8)+AttemptMemoryLedger.referenceArrayBytes(s))
                +AttemptMemoryLedger.arrayBytes(c,8)+AttemptMemoryLedger.referenceArrayBytes(c)
                +AttemptMemoryLedger.objectBytes(24)+AttemptMemoryLedger.referenceArrayBytes(c)+NATIVE_ROW_API_FRAME
                +AttemptMemoryLedger.referenceArrayBytes(c); //List.copyOf(ArrayList):extra transient toArray,distinct from retained copy.
            scratch.reserve(bytes);
            double[] widths=new double[s];double[][] responses=new double[c][s];
            var reasons=new BPhysicalResponse.Reason[c][s];var outcomes=new Outcome[c];
            double width=0;
            for(int i=0;i<s;i++){field.work.poll();widths[i]=lattice.cells().get(i).quadratureWidthMeters();
                if(!Double.isFinite(widths[i])||widths[i]<=0)throw new IllegalArgumentException("Admitted width");width=finiteSum(width,widths[i]);}
            //Native effective priors used ONLY for original frozen censored rows.
            double[] original=ProbabilisticObservationModel.effectiveComponentPriors(facts.baseline(),lattice.components());
            double censored=0;int missing=-1;
            for(int m=0;m<c;m++) {
                field.work.poll();var component=lattice.components().get(m);
                Arrays.fill(reasons[m],BPhysicalResponse.Reason.INELIGIBLE);
                if(component.kind()==ObservationComponent.Kind.MISSING) {
                    if(missing!=-1)throw new IllegalArgumentException("Duplicate uniform component");missing=m;outcomes[m]=Outcome.BASELINE_MISSING;
                } else if(component.kind()==ObservationComponent.Kind.CENSORED) {
                    censored=finiteSum(censored,original[m]);outcomes[m]=Outcome.BASELINE_CENSORED;
                } else {
                    int mode=component.groupedParent()?-1:field.own.originalMode(row,component);
                    outcomes[m]=component.groupedParent()?Outcome.GROUPED_PARENT_UNAVAILABLE:mode<0?Outcome.UNAVAILABLE_ORIGINAL_RECEIPT:Outcome.OBSERVED_ZERO_EXCESS;
                    if(mode>=0)for(int i=0;i<s;i++) {
                        field.work.poll();field.own.query(row,mode,i);var reason=field.own.reason();reasons[m][i]=reason;
                        responses[m][i]=reason.ordinal()<=BPhysicalResponse.Reason.GEOMETRIC_ZERO.ordinal()?field.own.value():Double.NaN;
                        if(reason==BPhysicalResponse.Reason.SOURCE_UNKNOWN)outcomes[m]=Outcome.UNAVAILABLE_ADMITTED_SHOULDER_SUPPORT;
                    }
                }
            }
            if(missing<0)throw new IllegalArgumentException("Missing uniform component");
            var calculation=calculateMeasuredRows(responses,reasons,widths,parameters.gain(),censored,transaction,owner,field.work);
            var components=new ArrayList<BObservationMixture.Component>(c);
            for(int m=0;m<c;m++) {
                field.work.poll();var component=lattice.components().get(m);double mass;
                if(component.kind()==ObservationComponent.Kind.MISSING) {
                    mass=calculation.missingMass();Arrays.fill(responses[m],-StrictMath.log(width));
                } else if(component.kind()==ObservationComponent.Kind.CENSORED) {
                    mass=original[m];ProbabilisticObservationModel.fillBaselineCensoredRow(responses[m],facts.baseline(),lattice.cells(),component,cancellation);
                } else {
                    mass=calculation.mass(m);if(calculation.area(m)>0)outcomes[m]=Outcome.MEASURED;
                    for(int i=0;i<s;i++)responses[m][i]=calculation.logDensity(m,i);
                }
                components.add(BObservationMixture.Component.create(component.id(),component.evidenceLineage(),component.kind(),mass,responses[m],outcomes[m],transaction));
            }
            var mixture=BObservationMixture.create(components,transaction);
            field.work.poll();field.own.requireWork(field.work);var receiptReservation=transaction.reserve(32);
            //Receipt getters bind the caller lifetime;transaction ownership is
            //transferred only after every source/probe/factory check succeeds.
            var result=new Evaluation(mixture,calculation);receiptReservation.adopt(result);
            scratch.close();transaction.transferTo(owner);return result;
        } finally {transaction.close();}
    }
    public String identityHash(){return policyIdentity(parameters);}
    static String policyIdentity(Parameters parameters){
        //Fixed case-independent policy identities;verified against SHA-256 in
        //tests. No per-evaluation provider/bootstrap work is hidden here.
        return switch(parameters.gain()) {
            case 0 -> "be750b3bcd2107d4a5a719328cd524f3dc215435aa2e23406dd11fbfda292c02";
            case 1 -> "4a76783012743dbdbb3c09482880ea822632d3e878efa882655a08fd1a1354e1";
            case 4 -> "1ef0bd200761515d59d4289b5d0afbba25e559c1aa5757f34a824c38b621b700";
            case 16 -> "5d91c12b21ddae99e184ee34e27ef326d2deb70723f1da19be8427ba2d1d254e";
            case 64 -> "e7687a16330e5561e4b448bbe906a5b5ca754ea3d7baf389e0d7d18cbdbba361";
            default -> throw new IllegalArgumentException("Unregistered gain");
        };
    }
}
