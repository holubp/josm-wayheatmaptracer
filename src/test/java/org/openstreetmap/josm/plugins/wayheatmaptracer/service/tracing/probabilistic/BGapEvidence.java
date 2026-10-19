package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;

/** Identification-only immutable bindings. No field, probability or connection certificate is computed here. */
final class BGapEvidence {
    static final String PROOF_SHA256 = "e6cc90f646c4dfbb0f05c8169d7a9bebb62a0d359de3def595be7a5fef9e4bcf";
    static final long BUILD_UNITS = 78_320_919_079_993_196L;
    static final long BYTES = 67_108_864L;
    static final long SLABS = 3_595_200L;
    static final int MAX_FACT_MODES = 8192;
    // The reviewed finite experiment has81 zero-origin profiles. This is NOT a
    // production admission cap. Promotion must generalize this resource contract
    // and bind ProfileChainage.sourceOriginGroundMeters for live source sampling.
    static final int MAX_EXPERIMENT_PROFILES = 81;
    // Experiment-only source selection: the unchanged real evaluator samples this field.
    // Production promotion must bind its actual selected trace field, never relabel a different one.
    static final String SELECTED_FIELD = "scalar";
    record ResourceContract(String proofSha256,long maximumBuildUnits,long maximumBytes,long maximumSlabs) {
        ResourceContract {
            if (!PROOF_SHA256.equals(proofSha256) || maximumBuildUnits!=BUILD_UNITS
                    || maximumBytes!=BYTES || maximumSlabs!=SLABS)
                throw new IllegalArgumentException("Unreviewed gap resource contract");
        }
    }
    static ResourceContract approvedContract() { return new ResourceContract(PROOF_SHA256,BUILD_UNITS,BYTES,SLABS); }
    enum Status { OBSERVED_GAP, OBSERVED_NO_GAP, UNAVAILABLE_RECEIPT, UNAVAILABLE_SOURCE_GRID,
        UNAVAILABLE_SUPPORT, AMBIGUOUS_STRIP }
    record Input(EvidenceSnapshot evidence,String fieldKey,List<ProfileFacts> facts,
            List<InferenceProfile> profiles,ResourceContract contract) {
        Input {
            Objects.requireNonNull(evidence);Objects.requireNonNull(contract);
            if (!SELECTED_FIELD.equals(fieldKey) || !evidence.fields().containsKey(fieldKey))
                throw new IllegalArgumentException("Selected scalar field is absent");
            Objects.requireNonNull(facts);Objects.requireNonNull(profiles);
            if (facts.size()!=profiles.size() || facts.size()>MAX_EXPERIMENT_PROFILES)
                throw new IllegalArgumentException("Gap source profile inventory differs from contract");
            // Only these bounded<=81-reference container copies occur here. All deep
            // bindings, provenance, mode counts and leases are checked in the owned path.
            facts=List.copyOf(facts);profiles=List.copyOf(profiles);
        }
    }
    /** Byte fields describe NEW gap payload reservations. Co-owned original scene/fact
     * charges remain visible in the attempt ledger and are never counted twice here. */
    record Counters(long sourceElements,long modeComparisons,long stripPredicates,long cacheReads,
            long maskReads,long prefixOperations,long enclosureOperations,long pairWrites,
            long slabEvaluations,long solverLookups,long cancellationChecks,long buildUnits,
            long currentOwnedBytes,long peakOwnedBytes) { }
    record WorkDetails(long bigIntegerApiCalls,long complexityUpperUnits,long reservedUpperUnits,
            long hypotCalls,long primitiveUpperUnits) { }
    enum Work { SOURCE_ELEMENT, MODE_COMPARISON, STRIP_PREDICATE, CACHE_READ, MASK_READ,
        PREFIX_OPERATION, ENCLOSURE_OPERATION, PAIR_WRITE, SLAB_EVALUATION, SOLVER_LOOKUP, CANCELLATION_CHECK,
        VALIDATION_OPERATION }
    static final class WorkCounter {
        private long sourceElements,modeComparisons,stripPredicates,cacheReads,maskReads,prefixOperations,
                enclosureOperations,pairWrites,slabEvaluations,solverLookups,cancellationChecks,buildUnits,
                currentOwnedBytes,peakOwnedBytes;
        private long bigIntegerApiCalls,complexityUpperUnits,reservedUpperUnits,hypotCalls,primitiveUpperUnits;
        void add(Work work,long units) {
            Objects.requireNonNull(work);
            if(units<0)throw new IllegalArgumentException("Negative executed work");
            long nextBuild=work==Work.SOLVER_LOOKUP?buildUnits:Math.addExact(buildUnits,units);
            if(nextBuild>BUILD_UNITS || reservedUpperUnits!=0&&nextBuild>reservedUpperUnits
                    || work==Work.SLAB_EVALUATION && units>SLABS-slabEvaluations)
                throw new IllegalArgumentException("Gap build work limit");
            switch(work) {
                case SOURCE_ELEMENT -> sourceElements=Math.addExact(sourceElements,units);
                case MODE_COMPARISON -> modeComparisons=Math.addExact(modeComparisons,units);
                case STRIP_PREDICATE -> stripPredicates=Math.addExact(stripPredicates,units);
                case CACHE_READ -> cacheReads=Math.addExact(cacheReads,units);
                case MASK_READ -> maskReads=Math.addExact(maskReads,units);
                case PREFIX_OPERATION -> prefixOperations=Math.addExact(prefixOperations,units);
                case ENCLOSURE_OPERATION -> enclosureOperations=Math.addExact(enclosureOperations,units);
                case PAIR_WRITE -> pairWrites=Math.addExact(pairWrites,units);
                case SLAB_EVALUATION -> slabEvaluations=Math.addExact(slabEvaluations,units);
                case SOLVER_LOOKUP -> solverLookups=Math.addExact(solverLookups,units);
                case CANCELLATION_CHECK -> cancellationChecks=Math.addExact(cancellationChecks,units);
                // Labelled conservative primitive upper charge; never a measured JVM instruction count.
                case VALIDATION_OPERATION -> { }
            }
            buildUnits=nextBuild;
        }
        Counters snapshot() {
            return new Counters(sourceElements,modeComparisons,stripPredicates,cacheReads,maskReads,
                    prefixOperations,enclosureOperations,pairWrites,slabEvaluations,solverLookups,
                    cancellationChecks,buildUnits,currentOwnedBytes,peakOwnedBytes);
        }
        WorkDetails details() {return new WorkDetails(bigIntegerApiCalls,complexityUpperUnits,reservedUpperUnits,hypotCalls,primitiveUpperUnits);}
        void reserveUpper(long units) {
            if(units<buildUnits||units>BUILD_UNITS)throw new IllegalArgumentException("Gap preflight work bound");
            reservedUpperUnits=units;
        }
        void integerCall(long complexity) {
            long next=Math.addExact(complexityUpperUnits,complexity);
            add(Work.VALIDATION_OPERATION,Math.addExact(1,complexity));
            bigIntegerApiCalls=Math.addExact(bigIntegerApiCalls,1);complexityUpperUnits=next;
        }
        /** Statically enumerated conservative scalar/index blocks, not measured JVM instructions. */
        void numeric(long operations) {add(Work.VALIDATION_OPERATION,operations);primitiveUpperUnits=Math.addExact(primitiveUpperUnits,operations);}
        void hypot() {numeric(1);hypotCalls=Math.addExact(hypotCalls,1);}
        void own(long bytes) {currentOwnedBytes=Math.addExact(currentOwnedBytes,bytes);peakOwnedBytes=Math.max(peakOwnedBytes,currentOwnedBytes);}
        void release(long bytes) {if(bytes>currentOwnedBytes)throw new IllegalStateException("Gap ownership underflow");currentOwnedBytes-=bytes;}
        void absorb(Counters c) {
            add(Work.SOURCE_ELEMENT,c.sourceElements());add(Work.MODE_COMPARISON,c.modeComparisons());
            add(Work.STRIP_PREDICATE,c.stripPredicates());add(Work.CACHE_READ,c.cacheReads());add(Work.MASK_READ,c.maskReads());
            add(Work.PREFIX_OPERATION,c.prefixOperations());add(Work.ENCLOSURE_OPERATION,c.enclosureOperations());
            add(Work.PAIR_WRITE,c.pairWrites());add(Work.SLAB_EVALUATION,c.slabEvaluations());
            add(Work.SOLVER_LOOKUP,c.solverLookups());add(Work.CANCELLATION_CHECK,c.cancellationChecks());
            long categorized=c.sourceElements()+c.modeComparisons()+c.stripPredicates()+c.cacheReads()+c.maskReads()
                    +c.prefixOperations()+c.enclosureOperations()+c.pairWrites()+c.slabEvaluations()+c.cancellationChecks();
            add(Work.VALIDATION_OPERATION,Math.subtractExact(c.buildUnits(),categorized));
        }
    }
    /** Conservative alias capability: only a shared immutable snapshot and selected field are proven here.
     * Equal labels, equal pitch, resampling provenance or equal-looking arrays alone are not proof. */
    static boolean sameObservation(Input a,Input b) {
        return a.evidence()==b.evidence() && a.fieldKey().equals(b.fieldKey());
    }
    static long sourceCloneBytes(ScalarEvidenceField f) {
        long vertices=Math.multiplyExact((long)f.width(),f.height());
        long cells=Math.multiplyExact((long)f.width()-1,f.height()-1);
        if(vertices>191401 || cells>190404)throw new IllegalArgumentException("Source exceeds proved raster inventory");
        // Field wrapper192 + one counter/report128 + owner64. The mutable construction
        // counter is no longer retained when read-only snapshots become available.
        return Math.addExact(384,Math.addExact(AttemptMemoryLedger.arrayBytes(vertices,8),
                Math.addExact(AttemptMemoryLedger.arrayBytes(vertices,1),AttemptMemoryLedger.arrayBytes(cells,1))));
    }
    /** Owned defensive clones, with read-only access; numeric validity remains independent from intensity. */
    static final class SourceCopy implements AutoCloseable {
        private final double[] values;
        private final boolean[] valid,interpolationValid;
        private final AttemptMemoryLedger.Owner owner;
        private final long sourceElements,modeComparisons,stripPredicates,cacheReads,maskReads,prefixOperations,
                enclosureOperations,pairWrites,slabEvaluations,solverLookups,cancellationChecks,buildUnits,bytes;
        SourceCopy(double[] values,boolean[] valid,boolean[] interpolationValid,
                AttemptMemoryLedger.Owner owner,WorkCounter work) {
            this.values=values;this.valid=valid;this.interpolationValid=interpolationValid;
            this.owner=owner;this.sourceElements=work.sourceElements;
            this.modeComparisons=work.modeComparisons;this.stripPredicates=work.stripPredicates;
            this.cacheReads=work.cacheReads;this.maskReads=work.maskReads;this.prefixOperations=work.prefixOperations;
            this.enclosureOperations=work.enclosureOperations;this.pairWrites=work.pairWrites;
            this.slabEvaluations=work.slabEvaluations;this.solverLookups=work.solverLookups;
            this.cancellationChecks=work.cancellationChecks;this.buildUnits=work.buildUnits;
            this.bytes=work.currentOwnedBytes;
        }
        double value(int i) { ensureOpen();return values[i]; }
        boolean valid(int i) { ensureOpen();return valid[i]; }
        boolean interpolationValid(int i) { ensureOpen();return interpolationValid[i]; }
        /** One caller-owned report at a time fits the reserved128B report slot. No graph work is inferred. */
        Counters counters() {
            return new Counters(sourceElements,modeComparisons,stripPredicates,cacheReads,maskReads,prefixOperations,
                    enclosureOperations,pairWrites,slabEvaluations,solverLookups,cancellationChecks,buildUnits,
                    owner.isClosed()?0:bytes,bytes);
        }
        private void ensureOpen() { if(owner.isClosed())throw new IllegalStateException("Source copy is closed"); }
        public void close() { owner.close(); }
    }
    static SourceCopy copySource(Input input,AttemptMemoryLedger.Owner owner,CancellationProbe cancellation) {
        Objects.requireNonNull(input);Objects.requireNonNull(owner);Objects.requireNonNull(cancellation);
        var field=input.evidence().fields().get(input.fieldKey());
        long bytes=sourceCloneBytes(field);
        if(bytes>BYTES-owner.currentBytes())throw new IllegalArgumentException("Gap attempt memory limit");
        var child=owner.child("gap-source-copy");
        try(var reservation=child.reserve(bytes)) {
            var work=new WorkCounter();work.currentOwnedBytes=bytes;work.peakOwnedBytes=bytes;
            checkpoint(cancellation,work);
            validateAndRetain(input,child,cancellation,work);
            double[] values=field.copiedValues();work.add(Work.SOURCE_ELEMENT,2L*values.length);
            checkpoint(cancellation,work);
            boolean[] valid=field.copiedValidity();work.add(Work.SOURCE_ELEMENT,2L*valid.length);
            checkpoint(cancellation,work);
            boolean[] interpolation=field.copiedInterpolationValidity();work.add(Work.SOURCE_ELEMENT,2L*interpolation.length);
            checkpoint(cancellation,work);
            var result=new SourceCopy(values,valid,interpolation,child,work);
            reservation.adopt(result);
            return result;
        } catch(RuntimeException|Error failure) {child.close();throw failure;}
    }
    private static void checkpoint(CancellationProbe cancellation,WorkCounter work) {
        work.add(Work.CANCELLATION_CHECK,1);cancellation.checkpoint();
    }
    /** Test/preflight check against actual same-attempt leases, never a caller-supplied mode count. */
    static Counters requireOwnedFacts(Input input,AttemptMemoryLedger.Owner owner,CancellationProbe cancellation) {
        Objects.requireNonNull(input);Objects.requireNonNull(cancellation);
        try(var check=Objects.requireNonNull(owner).child("gap-facts-check");var reservation=check.reserve(384)) {
            var work=new WorkCounter();work.currentOwnedBytes=384;work.peakOwnedBytes=384;
            checkpoint(cancellation,work);
            validateAndRetain(input,check,cancellation,work);
            work.currentOwnedBytes=0;return work.snapshot();
        }
    }
    /** Single counted binding/ownership path. Each variable loop checkpoints, and
     * string comparison checkpoints every64 characters (well below1024 primitive operations).
     * Cache/mode counters are actual visits/reads; buildUnits also includes labelled
     * conservative fixed-body primitive charges, not inferred graph work. */
    static void validateAndRetain(Input input,AttemptMemoryLedger.Owner owner,
            CancellationProbe cancellation,WorkCounter work) {
        long modeCount=0;
        for(int row=0;row<input.facts().size();row++) {
            checkpoint(cancellation,work);
            var fact=input.facts().get(row);var p=input.profiles().get(row);var raw=fact.baseline();
            work.add(Work.CACHE_READ,3);work.add(Work.VALIDATION_OPERATION,32);
            modeCount=Math.addExact(modeCount,fact.modes().size());
            if(modeCount>MAX_FACT_MODES || raw.samples().size()>401 || p.cells().size()>96
                    || raw.modes().size()!=fact.modes().size())
                throw new IllegalArgumentException("Gap original facts exceed proved inventory");
            if(p.anchor()!=raw.anchor() || p.normalUnit()!=raw.normalUnit()
                    || Double.doubleToRawLongBits(p.chainageMeters())!=Double.doubleToRawLongBits(raw.chainageMeters()))
                throw new IllegalArgumentException("Adapted profile lost original source geometry");
            var resolution=input.evidence().resolution();
            if(raw.nativePitchKnown()!=resolution.nativePitchMeters().isPresent()
                    || Double.doubleToRawLongBits(raw.sourcePitchMeters())!=Double.doubleToRawLongBits(
                            effectivePitch(resolution,raw.chainageMeters(),cancellation,work)))
                throw new IllegalArgumentException("Profile source pitch differs from actual snapshot resolution");
            for(int previous=0;previous<row;previous++) {
                checkpoint(cancellation,work);
                var prior=input.facts().get(previous);work.add(Work.CACHE_READ,1);work.add(Work.VALIDATION_OPERATION,4);
                if(prior==fact)throw new IllegalArgumentException("Duplicate original fact identity");
            }
            var lease=owner.retain(fact);
            work.add(Work.VALIDATION_OPERATION,16);
            long required=Math.addExact(1024L+96L*raw.samples().size(),8192L*fact.modes().size());
            if(lease.bytes()<required)throw new IllegalArgumentException("Original facts reservation is insufficient");
            for(int m=0;m<fact.modes().size();m++) {
                checkpoint(cancellation,work);
                var mode=fact.modes().get(m);var original=raw.modes().get(m);
                work.add(Work.CACHE_READ,2);work.add(Work.MODE_COMPARISON,1);work.add(Work.VALIDATION_OPERATION,24);
                required=Math.addExact(required,4L*((long)original.id().length()+original.evidenceLineage().length()));
                if(lease.bytes()<required)throw new IllegalArgumentException("Original facts reservation is insufficient");
                if(!sameString(mode.lineage(),original.evidenceLineage(),cancellation,work))
                    throw new IllegalArgumentException("Original mode inventory changed");
                for(var receipt:mode.provenance()) {
                    checkpoint(cancellation,work);work.add(Work.CACHE_READ,1);work.add(Work.VALIDATION_OPERATION,16);
                    if(receipt instanceof CorrelationReceipt correlation
                            && (!sameString(input.evidence().sourceIdentity(),correlation.fieldLineage(),cancellation,work)
                                || !sameString(input.evidence().transform().transformId(),correlation.transformId(),cancellation,work)
                                || raw.nativePitchKnown()!=correlation.nativePitchKnown()))
                        throw new IllegalArgumentException("Receipt belongs to a different source");
                }
            }
        }
    }
    private static boolean sameString(String a,String b,CancellationProbe cancellation,WorkCounter work) {
        work.add(Work.VALIDATION_OPERATION,4);
        if(a==b)return true;
        if(a==null || b==null || a.length()!=b.length())return false;
        for(int i=0;i<a.length();i++) {
            if((i&63)==0)checkpoint(cancellation,work);
            char left=a.charAt(i),right=b.charAt(i);
            work.add(Work.CACHE_READ,2);work.add(Work.VALIDATION_OPERATION,4);
            if(left!=right)return false;
        }
        return true;
    }
    /** Exact grouping of EvidenceResolution.effectivePitchMetersAt, with counted
     * cancellable metadata traversal; no output-raster-pitch substitution. The fixed
     * experiment has sourceOriginGroundMeters=0; live promotion must bind its origin. */
    private static double effectivePitch(EvidenceResolution resolution,double chainage,
            CancellationProbe cancellation,WorkCounter work) {
        var samples=resolution.spatialPitchSamples();int count=samples.size();
        var first=samples.get(0);var last=samples.get(count-1);
        work.add(Work.CACHE_READ,3);work.add(Work.VALIDATION_OPERATION,16);
        if(!Double.isFinite(chainage) || chainage<0 || count>1 && chainage>last.chainageMeters()+1e-12)
            throw new IllegalArgumentException("Chainage lies outside actual resolution metadata");
        if(count==1)return first.effectivePitchMeters();
        for(int i=1;i<count;i++) {
            checkpoint(cancellation,work);
            var right=samples.get(i);work.add(Work.CACHE_READ,1);work.add(Work.VALIDATION_OPERATION,16);
            if(chainage<=right.chainageMeters()) {
                var left=samples.get(i-1);work.add(Work.CACHE_READ,1);work.add(Work.VALIDATION_OPERATION,16);
                double fraction=(chainage-left.chainageMeters())/(right.chainageMeters()-left.chainageMeters());
                return left.effectivePitchMeters()+fraction*(right.effectivePitchMeters()-left.effectivePitchMeters());
            }
        }
        return last.effectivePitchMeters();
    }
    private BGapEvidence() { }
}
