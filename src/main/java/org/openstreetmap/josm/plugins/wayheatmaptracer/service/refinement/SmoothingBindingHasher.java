package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.security.*;
import java.util.*;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorExecutionReceipt;

/** Versioned typed streaming binding. No metadata tree is rendered into a temporary string. */
final class SmoothingBindingHasher implements AutoCloseable {
    private final GeometryWorkContext work;
    private final MessageDigest digest;
    private final AttemptMemoryLedger.Reservation scratch;
    private int depth;
    SmoothingBindingHasher(GeometryWorkContext work) {
        this.work=Objects.requireNonNull(work);
        // Includes reflection arrays at the bounded nesting depth and digest state.
        scratch=work.reserve("smoothing-binding-stream",1_048_576L);
        try {digest=MessageDigest.getInstance("SHA-256");value("g004-smoothing-binding-v2");}
        catch(NoSuchAlgorithmException impossible){scratch.close();throw new IllegalStateException(impossible);}
        catch(RuntimeException failure){scratch.close();throw failure;}
    }
    private void integer(long value){for(int i=7;i>=0;i--)digest.update((byte)(value>>>(i*8)));}
    private void text(String value){integer(value.length());for(int i=0;i<value.length();i++){work.visit(GeometryWorkContext.Kind.HASH,1);char c=value.charAt(i);digest.update((byte)(c>>>8));digest.update((byte)c);}}
    void value(Object value) {
        work.visit(GeometryWorkContext.Kind.HASH,1);
        if(++depth>64){depth--;throw new IllegalArgumentException("Binding metadata nesting exceeds bound");}
        try {
            if(value==null){digest.update((byte)0);return;}
            if(value instanceof String s){digest.update((byte)1);text(s);return;}
            if(value instanceof Boolean b){digest.update((byte)2);digest.update((byte)(b?1:0));return;}
            if(value instanceof Number n&&(n instanceof Byte||n instanceof Short||n instanceof Integer||n instanceof Long||n instanceof Float||n instanceof Double)){digest.update((byte)3);text(n.getClass().getName());integer(n instanceof Double||n instanceof Float?Double.doubleToLongBits(n.doubleValue()):n.longValue());return;}
            if(value instanceof Enum<?> e){digest.update((byte)4);text(e.getDeclaringClass().getName());text(e.name());return;}
            if(value instanceof UUID id){digest.update((byte)5);integer(id.getMostSignificantBits());integer(id.getLeastSignificantBits());return;}
            if(value instanceof Optional<?> o){digest.update((byte)6);value(o.orElse(null));return;}
            if(value instanceof OptionalInt o){digest.update((byte)7);value(o.isPresent()?o.getAsInt():null);return;}
            if(value instanceof OptionalLong o){digest.update((byte)8);value(o.isPresent()?o.getAsLong():null);return;}
            if(value instanceof OptionalDouble o){digest.update((byte)9);value(o.isPresent()?o.getAsDouble():null);return;}
            if(value instanceof EastNorth p){digest.update((byte)10);value(p.east());value(p.north());return;}
            if(value instanceof List<?> list){digest.update((byte)11);integer(list.size());for(Object item:list)value(item);return;}
            if(value instanceof Set<?> set){digest.update((byte)12);ordered(set,false);return;}
            if(value instanceof Map<?,?> map){digest.update((byte)13);ordered(map.entrySet(),true);return;}
            if(value instanceof FinalControlBasis basis){digest.update((byte)14);value(basis.rows());value(basis.initialCoordinates());value(basis.protectedIndices());return;}
            if(value instanceof CorridorExecutionReceipt r){digest.update((byte)15);value(r.requestHash());value(r.evidenceHash());value(r.networkHash());value(r.resultHash());value(r.settingsHash());value(r.networkSnapshotId());value(r.executionPolicy());value(r.certificateStatus());value(r.certificateIdentity());value(r.phase());value(r.totals());value(r.sampling());value(r.originalCostPolicies());value(r.tracks());value(r.candidates());value(r.sourceOccurrences());value(r.fixedSourceCoordinates());value(r.discoveredTracks());value(r.visitedTracks());return;}
            if(value instanceof CorridorExecutionReceipt.CandidateProvenance p){digest.update((byte)16);value(p.hypothesisId());value(p.trackId());value(p.mapping());value(p.cleanupStatus());return;}
            if(value.getClass().isRecord()) {
                digest.update((byte)17);text(value.getClass().getName());var components=value.getClass().getRecordComponents();integer(components.length);
                for(var component:components){work.visit(GeometryWorkContext.Kind.HASH,1);text(component.getName());try{value(component.getAccessor().invoke(value));}catch(ReflectiveOperationException failure){throw new IllegalArgumentException("Binding record accessor unavailable",failure);}}
                return;
            }
            throw new IllegalArgumentException("Unsupported binding metadata type: "+value.getClass().getName());
        } finally {depth--;}
    }
    private void ordered(Collection<?> values,boolean entries) {
        try(var order=work.reserve("smoothing-binding-order",Math.addExact(1024L,Math.multiplyExact(96L,values.size())))) {
            var sorted=new ArrayList<Object>(values.size());for(Object value:values){work.visit(GeometryWorkContext.Kind.HASH,1);sorted.add(value);}
            sorted.sort((a,b)->compare(entries?((Map.Entry<?,?>)a).getKey():a,entries?((Map.Entry<?,?>)b).getKey():b));
            integer(sorted.size());for(Object value:sorted)if(entries){var entry=(Map.Entry<?,?>)value;value(entry.getKey());value(entry.getValue());}else value(value);
        }
    }
    private int compare(Object a,Object b) {
        work.visit(GeometryWorkContext.Kind.HASH,1);
        if(a instanceof String x&&b instanceof String y){for(int i=0;i<Math.min(x.length(),y.length());i++){work.visit(GeometryWorkContext.Kind.HASH,1);int c=Character.compare(x.charAt(i),y.charAt(i));if(c!=0)return c;}return Integer.compare(x.length(),y.length());}
        if(a instanceof Integer x&&b instanceof Integer y)return Integer.compare(x,y);
        if(a instanceof Long x&&b instanceof Long y)return Long.compare(x,y);
        if(a instanceof Enum<?> x&&b instanceof Enum<?> y&&x.getDeclaringClass()==y.getDeclaringClass())return Integer.compare(x.ordinal(),y.ordinal());
        throw new IllegalArgumentException("Unsupported binding key type");
    }
    String finish(){work.visit(GeometryWorkContext.Kind.HASH,1);return HexFormat.of().formatHex(digest.digest());}
    @Override public void close(){scratch.close();}
}
