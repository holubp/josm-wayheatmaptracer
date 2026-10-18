package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.util.function.LongSupplier;

/** Fixed aggregate-only timing; ordinary identification uses the allocation-free no-op. */
final class BObservationRuntimeTelemetry {
    enum Phase { SOURCE_VERIFY, OPEN_SCENE, RENDER, PROFILE_BUILD, FACT_EXTRACTION, LATTICE_ADMISSION,
        INPUT_FINGERPRINT, SCENE_SCORE, BASELINE_SCORE, CANDIDATE_PROFILES, PROPER_SCORE,
        RECONSTRUCTION, MAP_SOLVE, ROUTE_CHECK, PAIR_PROFILES, PAIR_CACHE_WRITE, PAIR_CACHE_READ,
        PAIR_COMPARE, COUPLED, RECEIPT_WRITE;
        boolean progress(){return this!=SOURCE_VERIFY&&this!=RECEIPT_WRITE;}
    }
    enum Work { RASTER_READS, LABEL_READS, PROFILES, MODE_CELLS, ADMITTED_STATES, CANDIDATE_PROFILE_EVALUATIONS,
        MAP_TRANSITIONS, PAIR_PROFILE_EVALUATIONS, PAIR_CHECKS, ORDINARY_RECEIPTS, ORDINARY_BYTES }
    @FunctionalInterface interface Sink {void publish(Map<String,Object> fields) throws Exception;}
    static final BObservationRuntimeTelemetry NONE=new BObservationRuntimeTelemetry(null,null);
    private static final Token NOOP=new Token();
    private final LongSupplier clock;private final Sink sink;
    private final long[][] values;private final long[] work;
    BObservationRuntimeTelemetry(LongSupplier clock,Sink sink) {
        if((clock==null)!=(sink==null))throw new IllegalArgumentException("Clock and sink are one capability");
        this.clock=clock;this.sink=sink;
        values=clock==null?null:new long[Phase.values().length][5];
        work=clock==null?null:new long[Work.values().length];
    }
    boolean enabled(){return clock!=null;}
    Token begin(Phase phase) throws Exception {
        if(clock==null)return NOOP;
        Objects.requireNonNull(phase);var row=values[phase.ordinal()];
        row[0]=Math.addExact(row[0],1);row[3]=Math.addExact(row[3],1);
        var token=new Token(this,phase,clock.getAsLong());
        try {if(phase.progress())sink.publish(snapshot());return token;}
        catch(Exception|Error failure){token.close();throw failure;}
    }
    Map<String,Object> snapshot(){
        if(clock==null)return Map.of();
        var rows=new LinkedHashMap<String,Object>();
        for(var phase:Phase.values()) {var v=values[phase.ordinal()];rows.put(phase.name(),Map.of(
                "attempted",v[0],"succeeded",v[1],"failed",v[2],"active",v[3],"elapsedNanos",v[4]));}
        var counts=new LinkedHashMap<String,Object>();for(var name:Work.values())counts.put(name.name(),work[name.ordinal()]);
        return Map.of("schema",1,"timingConvention","INCLUSIVE_COMPLETED_SCOPES_NOT_ADDITIVE",
                "phases",rows,"work",counts);
    }
    void count(Work name,long amount) {
        if(clock==null)return;
        if(amount<0)throw new IllegalArgumentException("Negative work count");
        work[name.ordinal()]=Math.addExact(work[name.ordinal()],amount);
    }
    static final class Token implements AutoCloseable {
        private final BObservationRuntimeTelemetry telemetry;private final Phase phase;private final long started;
        private boolean succeeded,closed;
        private Token(){telemetry=null;phase=null;started=0;}
        private Token(BObservationRuntimeTelemetry telemetry,Phase phase,long started){this.telemetry=telemetry;this.phase=phase;this.started=started;}
        void succeeded() {if(telemetry!=null){if(closed)throw new IllegalStateException("Closed phase");succeeded=true;}}
        public void close() {
            if(telemetry==null||closed)return;closed=true;
            var row=telemetry.values[phase.ordinal()];row[3]--;
            long duration=telemetry.clock.getAsLong()-started;
            if(duration<0){row[2]++;throw new IllegalStateException("Nonmonotonic diagnostic clock");}
            row[4]=Math.addExact(row[4],duration);row[succeeded?1:2]++;
        }
    }
}
