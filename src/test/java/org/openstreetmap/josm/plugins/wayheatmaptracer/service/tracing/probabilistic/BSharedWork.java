package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** One funded metadata/publication holder and truthful registered build-unit census. */
final class BSharedWork implements AutoCloseable {
    static final long METADATA_BYTES = 1L << 20;
    static final long BYTES = 64L << 20;
    static final long EXACT_PROXY = (2 * 512 + 2L) * (2 * 512 + 2L);
    private final AttemptMemoryLedger.Owner attempt, metadata;
    private final CancellationProbe cancellation;
    private long metadataUsed = 512, upper, build, primitiveUpper, sourceReads, maskReads,
            strictMathCalls, exactCalls, complexityProxy, checkpoints, lookups, writes, interpolations;
    /** Separate source/API/control proxy; never added to historical build or primitiveUpper. */
    private long captureControlProxyUpper, captureControlProxyUsed;
    private boolean boundClaimed, sourceExtractionClaimed, captureInputsClaimed,
            captureControlPhaseClaimed, captureControlImportAttempted;
    private long authorityMetadataSlots, sourceExtractionStartedCalls, sourceExtractionCompletedCalls,
            sourceExtractionNormalReturns, sourceExtractionCancelledCalls, sourceIssuerCancellations;
    private boolean exploratory;

    void markExploratory(){ensureOpen();if(boundClaimed||sourceExtractionClaimed||captureInputsClaimed)
        throw new IllegalStateException("Exploratory scope must precede source acquisition");exploratory=true;}
    boolean isExploratory(){ensureOpen();return exploratory;}

    private BSharedWork(AttemptMemoryLedger.Owner attempt, AttemptMemoryLedger.Owner metadata,
            CancellationProbe cancellation) {
        this.attempt = attempt; this.metadata = metadata; this.cancellation = cancellation;
    }

    /** The existing1MiB holder is reserved BEFORE Work/Input/reference wrappers are constructed. */
    static BSharedWork create(AttemptMemoryLedger.Owner owner, CancellationProbe cancellation) {
        Objects.requireNonNull(owner); Objects.requireNonNull(cancellation);
        var child = owner.child("shared-feature-metadata-publication");
        try {
            if (METADATA_BYTES > BYTES - owner.currentBytes())
                throw new IllegalArgumentException("Shared feature64MiB envelope");
            child.reserve(METADATA_BYTES);
            return new BSharedWork(owner, child, cancellation);
        } catch (RuntimeException | Error primary) {
            try { child.close(); }
            catch (RuntimeException | Error secondary) { /* preserve primary */ }
            throw primary;
        }
    }

    void requireOwner(AttemptMemoryLedger.Owner owner) {
        ensureOpen();
        if (owner != attempt) throw new IllegalArgumentException("Foreign shared-feature owner");
    }
    void requireProbe(CancellationProbe probe) {
        ensureOpen();if(probe!=cancellation)throw new IllegalArgumentException("Foreign shared-feature cancellation scope");
    }
    /** Owned capture attempt is consumed before producer construction;never reset. */
    synchronized void claimCaptureInputs(BSharedOwnedCaptureProbe.Context context) {
        context.check(attempt);
        if(metadata.isClosed()||captureInputsClaimed)context.refuseCapture();
        captureInputsClaimed=true;
        if(BSharedCaptureAdmission.METADATA_BYTES>METADATA_BYTES-metadataUsed)context.refuseCapture();
        metadata(BSharedCaptureAdmission.METADATA_BYTES);
    }
    boolean captureOwnerClosed(){return metadata.isClosed();}
    CancellationProbe cancellation(){ensureOpen();return cancellation;}

    /** One capture phase on this Work. Its claim survives every failure and child close. */
    synchronized void claimCaptureControlPhase(){
        ensureOpen();
        if(captureControlPhaseClaimed)throw new IllegalStateException("Capture control phase already claimed");
        captureControlPhaseClaimed=true;
    }
    /** Imports one checked, source-normalized whole-phase upper. No caller-facing currency conversion. */
    synchronized void importCaptureControlProxyUpper(long units){
        ensureOpen();
        if(!captureControlPhaseClaimed||captureControlImportAttempted)
            throw new IllegalStateException("Capture control upper cannot be imported");
        captureControlImportAttempted=true;
        if(units<=0)throw new IllegalArgumentException("Capture control upper must be positive");
        captureControlProxyUpper=units;
    }
    synchronized void chargeCaptureControlProxy(long units){
        ensureOpen();
        if(captureControlProxyUpper==0)throw new IllegalStateException("Capture control upper unavailable");
        if(units<0)throw new IllegalArgumentException("Negative capture control charge");
        long next=Math.addExact(captureControlProxyUsed,units);
        if(next>captureControlProxyUpper)throw new IllegalArgumentException("Capture control upper exceeded");
        captureControlProxyUsed=next;
    }
    synchronized long captureControlProxyUpper(){ensureOpen();return captureControlProxyUpper;}
    synchronized long captureControlProxyUsed(){ensureOpen();return captureControlProxyUsed;}

    void metadata(long bytes) {
        ensureOpen();
        if (bytes < 0) throw new IllegalArgumentException("Negative metadata slice");
        long next = Math.addExact(metadataUsed, bytes);
        if (next > METADATA_BYTES) throw new IllegalArgumentException("Shared metadata holder exhausted");
        metadataUsed = next;
    }

    long remainingMetadataBytes() { ensureOpen(); return METADATA_BYTES - metadataUsed; }

    /** Only the bounded dimensions/receipt pass may execute before U is established. */
    void preflight(long units) { charge(units, true); }

    void reserveUpper(long units) {
        ensureOpen();
        if (units < build || units <= 0 || upper != 0)
            throw new IllegalArgumentException("Shared source work upper already claimed or insufficient");
        upper = units;
    }

    /** Captured own-only extension;actual counters/once claims are never reset. */
    void extendCapturedAuthority(BSharedPositionEvidence.Bound bound,long sourceLiveJoins) {
        ensureOpen();
        if(upper==0||sourceLiveJoins<=0||bound.work!=this||bound.input.captured==null&&!exploratory)
            throw new IllegalArgumentException("No authenticated captured authority allowance");
        bound.ensureOpen();authoritySlots(8);
        if(exploratory)return; //Prospective source allowance is explicitly deferred.
        //3 checked producers,guards/operands/store and own registration fit8 slots.
        long extra=Math.addExact(8*256,Math.multiplyExact(27*256,sourceLiveJoins));
        upper=Math.addExact(upper,extra);
    }

    void numeric(long units) { charge(units, false); }

    /** Bounded, owned descriptor/index validation is the only pre-U construction pass. */
    void binding(long units) { charge(units, true); }

    private void charge(long units, boolean preflight) {
        ensureOpen();
        if (units < 0 || !preflight && upper == 0)
            throw new IllegalArgumentException("Unfunded shared-feature work");
        long next = Math.addExact(build, units);
        long primitive = Math.addExact(primitiveUpper, units);
        if (!exploratory && upper != 0 && next > upper) throw new IllegalArgumentException("Shared source work upper exceeded");
        build = next; primitiveUpper = primitive;
    }

    void sourceRead(long count) {
        ensureOpen();
        if (count < 0) throw new IllegalArgumentException("Negative source reads");
        sourceReads = Math.addExact(sourceReads, count);
    }

    void maskRead(long count) {
        ensureOpen();
        if (count < 0) throw new IllegalArgumentException("Negative mask reads");
        maskReads = Math.addExact(maskReads, count);
    }

    void write(long count) {
        ensureOpen(); if (count < 0) throw new IllegalArgumentException("Negative stores");
        writes = Math.addExact(writes, count);
    }
    /** Completed explicitly recorded payload stores; excludes JVM zeroing and enclosure scratch. */
    long recordedArrayWrites(){ensureOpen();return writes;}

    void interpolation() { ensureOpen(); interpolations = Math.addExact(interpolations, 1); }

    /** All additional payload shares the caller's actual ledger and the unchanged64MiB envelope. */
    void reserve(AttemptMemoryLedger.Owner owner, long bytes) {
        ensureOpen();
        if (bytes < 0 || bytes > BYTES - attempt.currentBytes())
            throw new IllegalArgumentException("Shared feature64MiB envelope");
        owner.reserve(bytes);
    }

    void strictMath() { ensureOpen(); strictMathCalls = Math.addExact(strictMathCalls, 1); }

    void integerCall() {
        ensureOpen();
        if (upper == 0) throw new IllegalArgumentException("Exact call before source work preflight");
        long next = Math.addExact(build, 1 + EXACT_PROXY);
        long calls = Math.addExact(exactCalls, 1), proxy = Math.addExact(complexityProxy, EXACT_PROXY);
        if (!exploratory && next > upper) throw new IllegalArgumentException("Shared exact-call upper exceeded");
        build = next; exactCalls = calls; complexityProxy = proxy;
    }

    void poll() { ensureOpen(); checkpoints = Math.addExact(checkpoints, 1); cancellation.checkpoint(); }

    void lookup() { ensureOpen(); lookups = Math.addExact(lookups, 1); }

    void claimBound() {
        ensureOpen();
        if (boundClaimed) throw new IllegalStateException("Shared source capability already claimed");
        boundClaimed = true;
    }

    /** One shared-position extraction attempt; failure/closure never resets the old8M guard. */
    void claimSourceExtraction() {
        ensureOpen();
        if(sourceExtractionClaimed)throw new IllegalStateException("Shared-position issuer already attempted");
        sourceExtractionClaimed=true;
        // Fixed attempt-control identity, not evidence/payload ownership. Adoption is
        // globally unique in this ledger and survives holder/token closure until owner close.
        try(var marker=attempt.reserve(0)){marker.adopt(SourceExtractionMarker.class);}
    }
    private static final class SourceExtractionMarker {private SourceExtractionMarker(){}}

    /** Registered metadata-slot blocks, included verbatim in T before U is reserved. */
    void authoritySlots(long count) {
        if(count<0)throw new IllegalArgumentException("Negative authority slots");
        long next=Math.addExact(authorityMetadataSlots,count);
        binding(Math.multiplyExact(256,count));authorityMetadataSlots=next;
    }
    long authorityMetadataSlots(){ensureOpen();return authorityMetadataSlots;}

    /** Observed completion of the one actual call, including its exceptional return. */
    void sourceExtractionStarting() {
        if(!sourceExtractionClaimed||sourceExtractionStartedCalls!=0)
            throw new IllegalStateException("Invalid source extraction entry");
        sourceExtractionStartedCalls=1;
    }
    void sourceExtractionFinished(boolean normalReturn,boolean cancelled) {
        if(sourceExtractionStartedCalls!=1||sourceExtractionCompletedCalls!=0||normalReturn&&cancelled)
            throw new IllegalStateException("Invalid source extraction completion");
        sourceExtractionCompletedCalls=1;
        sourceExtractionNormalReturns=normalReturn?1:0;sourceExtractionCancelledCalls=cancelled?1:0;
    }
    void sourceIssuerCancelled() {
        if(!sourceExtractionClaimed)throw new IllegalStateException("Issuer was not attempted");
        sourceIssuerCancellations=Math.addExact(sourceIssuerCancellations,1);
    }

    Counters snapshot() {
        metadata(208);
        return new Counters(build, exploratory?-1:upper, primitiveUpper, sourceReads, maskReads, strictMathCalls,
                exactCalls, complexityProxy, checkpoints, lookups, writes, interpolations, metadataUsed,
                METADATA_BYTES - metadataUsed,authorityMetadataSlots,sourceExtractionStartedCalls,sourceExtractionCompletedCalls,
                sourceExtractionNormalReturns,sourceExtractionCancelledCalls,sourceIssuerCancellations,
                captureControlProxyUpper,captureControlProxyUsed,captureControlPhaseClaimed,captureControlImportAttempted);
    }

    record Counters(long buildUnits, long reservedUpperUnits, long primitiveUpperUnits,
            long sourceReads, long maskReads, long strictMathCalls, long exactApiCalls,
            long weightedLimbPairProxy, long checkpoints, long hotLookups,
            long arrayWrites, long sourceInterpolations,
            long retainedMetadataSliceBytes, long remainingPublicationBytes,long authorityMetadataSlots,long sourceExtractionStartedCalls,
            long sourceExtractionCompletedCalls,long sourceExtractionNormalReturns,
            long sourceExtractionCancelledCalls,long sourceIssuerCancellations,
            long captureControlProxyUpper,long captureControlProxyUsed,
            boolean captureControlPhaseClaimed,boolean captureControlImportAttempted) {
        @Override public long reservedUpperUnits(){
            if(reservedUpperUnits<0)throw new UnsupportedOperationException("Qualified prospective source upper UNAVAILABLE");
            return reservedUpperUnits;
        }
    }

    void ensureOpen() {
        if (metadata.isClosed() || attempt.isClosed()) throw new IllegalStateException("Shared work closed");
    }

    @Override public void close() { metadata.close(); }
}
